package edu.illinois.cs.cs125.jeed.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import mu.KotlinLogging
import org.objectweb.asm.*
import org.objectweb.asm.util.TraceClassVisitor
import java.io.*
import java.security.*
import java.time.Duration
import java.time.Instant
import java.util.concurrent.*
import kotlin.reflect.jvm.javaMethod

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

object Sandbox {
    open class ExecutionArguments<T>(
            val timeout: Long = DEFAULT_TIMEOUT,
            val permissions: Set<Permission> = setOf(),
            val whitelistedClasses: Set<String> = setOf(),
            blacklistedClasses: Set<String> = setOf(),
            unsafeExceptions: Set<String> = setOf(),
            val maxExtraThreads: Int = DEFAULT_MAX_EXTRA_THREADS
    ) {
        val blacklistedClasses = blacklistedClasses.union(PERMANENTLY_BLACKLISTED_CLASSES)
        val unsafeExceptions = unsafeExceptions.union(ALWAYS_UNSAFE_EXCEPTIONS)
        init {
            unsafeExceptions.forEach { name ->
                val klass = Class.forName(name) ?: error("$name does not refer to a Java class")
                require(Throwable::class.java.isAssignableFrom(klass)) { "$name does not refer to a Java Throwable" }
            }
        }
        companion object {
            const val DEFAULT_TIMEOUT = 100L
            const val DEFAULT_MAX_EXTRA_THREADS = 0
        }
    }

    class TaskResults<T>(
            val returned: T?,
            val threw: Throwable?,
            val timeout: Boolean,
            val outputLines: MutableList<OutputLine> = mutableListOf(),
            val permissionRequests: MutableList<PermissionRequest> = mutableListOf(),
            val interval: Interval,
            val executionInterval: Interval
    ) {
        data class OutputLine (val console: Console, val line: String, val timestamp: Instant, val thread: Long) {
            enum class Console(val fd: Int) { STDOUT(1), STDERR(2) }
        }
        data class PermissionRequest(val permission: Permission, val granted: Boolean)

        val completed: Boolean
            get() { return threw == null && !timeout }
        val permissionDenied: Boolean
            get() { return permissionRequests.any { !it.granted } }
        val stdoutLines: List<OutputLine>
            get() { return outputLines.filter { it.console == OutputLine.Console.STDOUT } }
        val stderrLines: List<OutputLine>
            get() { return outputLines.filter { it.console == OutputLine.Console.STDERR } }
        val stdout: String
            get() { return stdoutLines.joinToString(separator = "\n") { it.line } }
        val stderr: String
            get() { return stderrLines.joinToString(separator = "\n") { it.line } }
        val output: String
            get() { return outputLines.sortedBy { it.timestamp }.joinToString(separator = "\n") { it.line } }
        val totalDuration: Duration
            get() { return Duration.between(interval.start, interval.end) }
    }

    val BLACKLISTED_PERMISSIONS = setOf(
            // Suggestions from here: https://github.com/pro-grade/pro-grade/issues/31.
            RuntimePermission("createClassLoader"),
            RuntimePermission("accessClassInPackage.sun"),
            RuntimePermission("setSecurityManager"),
            // Required for Java Streams to work...
            // ReflectPermission("suppressAccessChecks")
            SecurityPermission("setPolicy"),
            SecurityPermission("setProperty.package.access"),
            // Other additions from here: https://docs.oracle.com/javase/7/docs/technotes/guides/security/permissions.html
            SecurityPermission("createAccessControlContext"),
            SecurityPermission("getDomainCombiner"),
            RuntimePermission("createSecurityManager"),
            RuntimePermission("exitVM"),
            RuntimePermission("shutdownHooks"),
            RuntimePermission("setIO"),
            RuntimePermission("queuePrintJob"),
            RuntimePermission("setDefaultUncaughtExceptionHandler"),
            // These are particularly important to prevent untrusted code from escaping the sandbox which is based on thread groups
            RuntimePermission("modifyThread"),
            RuntimePermission("modifyThreadGroup")
    )
    val PERMANENTLY_BLACKLISTED_CLASSES = setOf(
            "java.lang.reflect.",
            "edu.illinois.cs.cs125.jeed.",
            "org.objectweb.asm.*"
    )
    val ALWAYS_UNSAFE_EXCEPTIONS = setOf("java.lang.Error")

    suspend fun <T> execute(
            byteCodeProvidingClassLoader: ByteCodeProvidingClassLoader = EmptyClassLoader,
            executionArguments: ExecutionArguments<T> = ExecutionArguments(),
            callable: (ClassLoader)->T
    ): TaskResults<out T?> {
        require(executionArguments.permissions.intersect(BLACKLISTED_PERMISSIONS).isEmpty()) {
            "attempt to allow unsafe permissions"
        }
        require(!executionArguments.whitelistedClasses.any {whitelistedClass ->
            PERMANENTLY_BLACKLISTED_CLASSES.any {blacklistedClass ->
                whitelistedClass.startsWith(blacklistedClass)
            }
        }) {
            "attempt to allow access to unsafe classes"
        }
        require(!(executionArguments.whitelistedClasses.isNotEmpty()
                && executionArguments.blacklistedClasses != PERMANENTLY_BLACKLISTED_CLASSES)) {
            "can't set both a class whitelist and blacklist"
        }

        val sandboxedClassLoader = SandboxedClassLoader(byteCodeProvidingClassLoader, executionArguments)

        return coroutineScope {
            val resultsChannel = Channel<ExecutorResult<T>>()
            val executor = Executor(callable, sandboxedClassLoader, executionArguments, resultsChannel)
            threadPool.submit(executor)
            val result = executor.resultChannel.receive()
            result.taskResults ?: throw result.executionException
        }
    }

    private const val MAX_THREAD_SHUTDOWN_RETRIES = 64
    private const val THREADGROUP_SHUTDOWN_DELAY = 10L

    private val threadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()) ?: error("thread pool should be available")
    private data class ExecutorResult<T>(val taskResults: TaskResults<T>?, val executionException: Throwable)
    private class Executor<T>(
            val callable: (ClassLoader) -> T,
            val sandboxedClassLoader: SandboxedClassLoader,
            val executionArguments: ExecutionArguments<T>,
            val resultChannel: Channel<ExecutorResult<T>>
    ): Callable<Any> {
        private data class TaskResult<T>(val returned: T, val threw: Throwable? = null, val timeout: Boolean = false)

        override fun call() {
            try {
                val confinedTask = confine(callable, sandboxedClassLoader, executionArguments)
                val executionStarted = Instant.now()
                val taskResult = try {
                    confinedTask.thread.start()
                    TaskResult(confinedTask.task.get(executionArguments.timeout, TimeUnit.MILLISECONDS))
                } catch (e: TimeoutException) {
                    confinedTask.task.cancel(true)
                    @Suppress("DEPRECATION")
                    confinedTask.thread.stop()
                    TaskResult(null, null, true)
                } catch (e: Throwable) {
                    TaskResult(null, e.cause)
                }
                val executionEnded = Instant.now()
                release(confinedTask)

                val executionResult = TaskResults<T>(
                        taskResult.returned, taskResult.threw, taskResult.timeout,
                        confinedTask.outputLines,
                        confinedTask.permissionRequests,
                        Interval(confinedTask.started, Instant.now()),
                        Interval(executionStarted, executionEnded)
                )
                runBlocking { resultChannel.send(ExecutorResult(executionResult, Exception())) }
            } catch (e: Throwable) {
                runBlocking { resultChannel.send(ExecutorResult(null, e)) }
            } finally {
                resultChannel.close()
            }
        }
    }

    private class ConfinedTask<T> (
            val classLoader: SandboxedClassLoader,
            val task: FutureTask<T>,
            val thread: Thread,
            executionArguments: ExecutionArguments<*>
    ) {
        val threadGroup = thread.threadGroup ?: error("thread should be in thread group")
        val accessControlContext: AccessControlContext
        init {
            val permissions = Permissions()
            executionArguments.permissions.forEach { permissions.add(it) }
            accessControlContext = AccessControlContext(arrayOf(ProtectionDomain(null, permissions)))
        }
        val maxExtraThreads: Int = executionArguments.maxExtraThreads
        val unsafeExceptions = executionArguments.unsafeExceptions

        var shuttingDown: Boolean = false
        val currentLines: MutableMap<TaskResults.OutputLine.Console, CurrentLine> = mutableMapOf()
        val outputLines: MutableList<TaskResults.OutputLine> = mutableListOf()
        val permissionRequests: MutableList<TaskResults.PermissionRequest> = mutableListOf()

        data class CurrentLine(
                var started: Instant = Instant.now(),
                val line: StringBuilder = StringBuilder(),
                val startedThread: Long = Thread.currentThread().id
        )
        val started: Instant = Instant.now()
        lateinit var interval: Interval
        lateinit var executionInterval: Interval

        fun addPermissionRequest(permission: Permission, granted: Boolean, throwException: Boolean = true) {
            permissionRequests.add(TaskResults.PermissionRequest(permission, granted))
            if (!granted && throwException) {
                throw SecurityException()
            }
        }
    }

    private val confinedTasks: MutableMap<ThreadGroup, ConfinedTask<*>> = mutableMapOf()
    private fun confinedTaskByThreadGroup(trapIfShuttingDown: Boolean = true): ConfinedTask<*>? {
        val confinedTask = confinedTasks[Thread.currentThread().threadGroup] ?: return null
        if (confinedTask.shuttingDown && trapIfShuttingDown) { while (true) { Thread.sleep(Long.MAX_VALUE) } }
        return confinedTask
    }

    private fun <T> confine(
            callable: (ClassLoader) -> T,
            sandboxedClassLoader: SandboxedClassLoader,
            executionArguments: ExecutionArguments<*>
    ): ConfinedTask<T> {
        val threadGroup = object : ThreadGroup("Sandbox") {
            override fun uncaughtException(t: Thread?, e: Throwable?) { }
        }
        threadGroup.maxPriority = Thread.MIN_PRIORITY
        val task = FutureTask<T>(SandboxedCallable<T>(callable, sandboxedClassLoader))
        val thread = Thread(threadGroup, task)
        val confinedTask = ConfinedTask(sandboxedClassLoader, task, thread, executionArguments)
        confinedTasks[threadGroup] = confinedTask
        return confinedTask
    }
    private fun <T> release(confinedTask: ConfinedTask<T>) {
        val threadGroup = confinedTask.threadGroup
        require(confinedTasks.containsKey(threadGroup)) { "thread group is not confined" }

        confinedTask.shuttingDown = true

        if (threadGroup.activeGroupCount() > 0) {
            val threadGroups = Array<ThreadGroup?>(threadGroup.activeGroupCount()) { null }
            threadGroup.enumerate(threadGroups, true)
            assert(threadGroups.toList().filterNotNull().map { it.activeCount() }.sum() == 0)
        }

        // TODO: Fix shutdown logic
        @Suppress("DEPRECATION") threadGroup.stop()

        val threadGroupShutdownRetries = (0..MAX_THREAD_SHUTDOWN_RETRIES).find {
            return@find if (threadGroup.activeCount() == 0) {
                true
            } else {
                Thread.sleep(THREADGROUP_SHUTDOWN_DELAY)
                false
            }
        }

        assert(threadGroupShutdownRetries != null) { "failed to shut down thread group" }
        threadGroup.destroy()
        assert(threadGroup.isDestroyed)

        for (console in TaskResults.OutputLine.Console.values()) {
            val currentLine = confinedTask.currentLines[console] ?: continue
            if (currentLine.line.isNotEmpty()) {
                confinedTask.outputLines.add(
                        TaskResults.OutputLine(
                                console,
                                currentLine.line.toString(),
                                currentLine.started,
                                currentLine.startedThread
                        )
                )
            }
        }

        confinedTasks.remove(threadGroup)
    }

    private class SandboxedCallable<V>(val callable: (ClassLoader)->V, val sandboxedClassLoader: SandboxedClassLoader): Callable<V> {
        override fun call(): V { return callable(sandboxedClassLoader as ClassLoader) }
    }

    private class SandboxedClassLoader(
            byteCodeProvidingClassLoader: ByteCodeProvidingClassLoader,
            executionArguments: ExecutionArguments<*>
    ) : ClassLoader((byteCodeProvidingClassLoader as ClassLoader).parent) {
        val whitelistedClasses = executionArguments.whitelistedClasses
        val blacklistedClasses = executionArguments.blacklistedClasses
        val unsafeExceptionClasses: Set<Class<*>> = executionArguments.unsafeExceptions.map { name ->
            val klass = Class.forName(name) ?: error("$name does not refer to a Java class")
            require(Throwable::class.java.isAssignableFrom(klass)) { "$name does not refer to a Java Throwable" }
            klass
        }.toSet()

        val knownClasses: Map<String, Class<*>>

        init {
            knownClasses = byteCodeProvidingClassLoader.bytecodeForClasses.mapValues { (name, unsafeByteArray) ->
                val safeByteArray = RewriteTryCatchFinally.rewrite(unsafeByteArray, unsafeExceptionClasses)
                defineClass(name, safeByteArray, 0, safeByteArray.size)
            }
        }

        override fun findClass(name: String): Class<*> {
            return knownClasses[name] ?: super.findClass(name)
        }

        val filter = whitelistedClasses.isNotEmpty() || blacklistedClasses.isNotEmpty()
        val isWhiteList = whitelistedClasses.isNotEmpty()
        override fun loadClass(name: String): Class<*> {
            if (!filter) {
                return super.loadClass(name)
            }
            val confinedTask = confinedTaskByThreadGroup() ?: return super.loadClass(name)

            if (knownClasses.containsKey(name)) {
                return super.loadClass(name)
            }
            if (name == pathToClassName(RewriteTryCatchFinally.checkClassName)) {
                return super.loadClass(name)
            }
            return if (isWhiteList) {
                if (whitelistedClasses.any { name.startsWith(it) }) {
                    super.loadClass(name)
                } else {
                    confinedTask.addPermissionRequest(RuntimePermission("loadClass $name"), granted = false, throwException = false)
                    throw ClassNotFoundException(name)
                }
            } else {
                if (blacklistedClasses.any { name.startsWith(it) }) {
                    confinedTask.addPermissionRequest(RuntimePermission("loadClass $name"), granted = false, throwException = false)
                    throw ClassNotFoundException(name)
                } else {
                    super.loadClass(name)
                }
            }
        }
    }

    object RewriteTryCatchFinally {
        val checkClassName = classNameToPath(RewriteTryCatchFinally::class.java.name ?: error("should have a class name"))
        private val checkMethodName = RewriteTryCatchFinally::checkException.javaMethod?.name
                ?: error("should have a method name")
        private val checkMethodDescription = Type.getMethodDescriptor(RewriteTryCatchFinally::checkException.javaMethod)
                ?: error("should be able to retrieve method signature")

        @JvmStatic
        fun checkException(throwable: Throwable) {
            val confinedTask = confinedTaskByThreadGroup()
                    ?: error("only confined tasks should call this method")
            // This check is required because of how we handle finally blocks
            if (confinedTask.classLoader.unsafeExceptionClasses.any { throwable.javaClass.isAssignableFrom(it) }) {
                throw(throwable)
            }
        }

        fun rewrite(originalByteArray: ByteArray, unsafeExceptionClasses: Set<Class<*>>): ByteArray {
            val classReader = ClassReader(originalByteArray)
            val classWriter = ClassWriter(classReader, ClassWriter.COMPUTE_MAXS)
            val ourVisitor = object : ClassVisitor(Opcodes.ASM5, classWriter) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                    return OurMethodVisitor(unsafeExceptionClasses, super.visitMethod(access, name, descriptor, signature, exceptions))
                }
            }

            classReader.accept(ourVisitor, 0)
            val rewrittenByteArray = classWriter.toByteArray()
            return rewrittenByteArray
        }

        private class OurMethodVisitor(
                val unsafeExceptionClasses: Set<Class<*>>,
                methodVisitor: MethodVisitor
        ) : MethodVisitor(Opcodes.ASM5, methodVisitor) {
            private val labelsToRewrite: MutableSet<Label> = mutableSetOf()
            private var rewroteLabel = false

            override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
                if (type == null) {
                    labelsToRewrite.add(handler)
                } else {
                    val exceptionClass = Class.forName(pathToClassName(type))
                            ?: error("no class for type $type")

                    if (unsafeExceptionClasses.any { exceptionClass.isAssignableFrom(it) }) {
                        labelsToRewrite.add(handler)
                    }
                }
                super.visitTryCatchBlock(start, end, handler, type)
            }
            private var nextLabel: Label? = null
            override fun visitLabel(label: Label) {
                if (labelsToRewrite.contains(label)) {
                    assert(nextLabel == null)
                    nextLabel = label
                }
                super.visitLabel(label)
            }
            override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any>?, numStack: Int, stack: Array<out Any>?) {
                super.visitFrame(type, numLocal, local, numStack, stack)
                if (nextLabel != null) {
                    super.visitInsn(Opcodes.DUP)
                    super.visitMethodInsn(Opcodes.INVOKESTATIC, checkClassName, checkMethodName, checkMethodDescription,false)
                    rewroteLabel = true
                    labelsToRewrite.remove(nextLabel ?: error("nextLabel changed"))
                    nextLabel = null
                }
            }
            override fun visitEnd() {
                assert(labelsToRewrite.isEmpty())
                super.visitEnd()
            }
        }
    }

    private fun classByteArrayToString(byteArray: ByteArray): String {
        val classReader = ClassReader(byteArray)
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        val traceVisitor = TraceClassVisitor(printWriter)
        classReader.accept(traceVisitor, 0)
        return stringWriter.toString()
    }

    val systemSecurityManager: SecurityManager? = System.getSecurityManager()

    private object SandboxSecurityManager : SecurityManager() {
        private fun confinedTaskByClassLoader(trapIfShuttingDown: Boolean = true): ConfinedTask<*>? {
            val confinedTask = confinedTaskByThreadGroup(trapIfShuttingDown) ?: return null
            val classIsConfined = classContext.toList().subList(1, classContext.size).reversed().any { klass ->
                klass.classLoader == confinedTask.classLoader
            }
            return if (classIsConfined) confinedTask else null
        }
        override fun checkRead(file: String) {
            val confinedTask = confinedTaskByClassLoader()
                    ?: return systemSecurityManager?.checkRead(file) ?: return
            if (!file.endsWith(".class")) {
                confinedTask.addPermissionRequest(FilePermission(file, "read"), false)
            } else {
                systemSecurityManager?.checkRead(file)
            }
        }
        override fun checkAccess(thread: Thread) {
            val confinedTask = confinedTaskByThreadGroup()
                    ?: return systemSecurityManager?.checkAccess(thread) ?: return
            if (thread.threadGroup != Thread.currentThread().threadGroup) {
                confinedTask.addPermissionRequest(RuntimePermission("changeThreadGroup"), false)
            } else {
                systemSecurityManager?.checkAccess(thread)
            }
        }
        override fun checkAccess(threadGroup: ThreadGroup) {
            val confinedTask = confinedTaskByThreadGroup()
                    ?: return systemSecurityManager?.checkAccess(threadGroup) ?: return
            if (threadGroup != Thread.currentThread().threadGroup) {
                confinedTask.addPermissionRequest(RuntimePermission("changeThreadGroup"), false)
            } else {
                systemSecurityManager?.checkAccess(threadGroup)
            }
        }
        override fun getThreadGroup(): ThreadGroup {
            val threadGroup = Thread.currentThread().threadGroup
            val confinedTask = confinedTaskByThreadGroup()
                    ?: return systemSecurityManager?.threadGroup ?: return threadGroup
            if (Thread.currentThread().threadGroup.activeCount() >= confinedTask.maxExtraThreads + 1) {
                confinedTask.permissionRequests.add(TaskResults.PermissionRequest(RuntimePermission("exceedThreadLimit"), false))
                throw SecurityException()
            } else {
                return systemSecurityManager?.threadGroup ?: threadGroup
            }
        }
        override fun checkPermission(permission: Permission) {
            // Special case to prevent even trusted task code from calling System.setOut
            val confinedTask = if (permission == RuntimePermission("setIO")) {
                confinedTaskByThreadGroup()
            } else {
                confinedTaskByClassLoader()
            } ?: return systemSecurityManager?.checkPermission(permission) ?: return

            try {
                confinedTask.accessControlContext.checkPermission(permission)
                confinedTask.addPermissionRequest(permission, true)
            } catch (e: SecurityException) {
                confinedTask.addPermissionRequest(permission, granted = false, throwException = false)
                throw e
            }
            systemSecurityManager?.checkPermission(permission)
        }
    }
    init {
        System.setSecurityManager(SandboxSecurityManager)
    }

    private fun redirectedWrite(int: Int, console: TaskResults.OutputLine.Console) {
        val confinedTask = confinedTaskByThreadGroup() ?: return defaultWrite(int, console)

        val currentLine = confinedTask.currentLines.getOrPut(console, { ConfinedTask.CurrentLine() })
        when (val char = int.toChar()) {
            '\n' -> {
                confinedTask.outputLines.add(
                        TaskResults.OutputLine(
                                console,
                                currentLine.line.toString(),
                                currentLine.started,
                                currentLine.startedThread
                        )
                )
                confinedTask.currentLines.remove(console)
            }
            else -> {
                currentLine.line.append(char)
            }
        }
    }
    fun defaultWrite(int: Int, console: TaskResults.OutputLine.Console) {
        when (console) {
            TaskResults.OutputLine.Console.STDOUT -> originalStdout.write(int)
            TaskResults.OutputLine.Console.STDERR -> originalStderr.write(int)
        }
    }

    private var originalStdout = System.out
    private var originalStderr = System.err
    init {
        System.setOut(PrintStream(object : OutputStream() {
            override fun write(int: Int) { redirectedWrite(int, TaskResults.OutputLine.Console.STDOUT) }
        }))
        System.setErr(PrintStream(object : OutputStream() {
            override fun write(int: Int) { redirectedWrite(int, TaskResults.OutputLine.Console.STDERR) }
        }))
    }
}
