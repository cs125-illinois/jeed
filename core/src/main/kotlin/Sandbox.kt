package edu.illinois.cs.cs125.jeed.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import mu.KotlinLogging
import org.objectweb.asm.*
import org.objectweb.asm.util.TraceClassVisitor
import java.io.*
import java.lang.reflect.InvocationTargetException
import java.security.*
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.*
import kotlin.reflect.jvm.javaMethod

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

private typealias SandboxCallableArguments<T> = (Pair<ClassLoader, (() -> Unit) -> Pair<String, String>>)->T

const val DEFAULT_CONSOLE_OVERFLOW_MESSAGE = "(additional console output not shown)"

object Sandbox {
    class ClassLoaderConfiguration(
            val whitelistedClasses: Set<String> = DEFAULT_WHITELISTED_CLASSES,
            blacklistedClasses: Set<String> = DEFAULT_BLACKLISTED_CLASSES,
            unsafeExceptions: Set<String> = DEFAULT_UNSAFE_EXCEPTIONS
    ) {
        val blacklistedClasses = blacklistedClasses.union(PERMANENTLY_BLACKLISTED_CLASSES)
        val unsafeExceptions = unsafeExceptions.union(ALWAYS_UNSAFE_EXCEPTIONS)
        init {
            require(!whitelistedClasses.any {whitelistedClass ->
                PERMANENTLY_BLACKLISTED_CLASSES.any { blacklistedClass -> whitelistedClass.startsWith(blacklistedClass) }
            }) {
                "attempt to allow access to unsafe classes"
            }
            require(!(whitelistedClasses.isNotEmpty()
                    && blacklistedClasses.minus(PERMANENTLY_BLACKLISTED_CLASSES.union(DEFAULT_BLACKLISTED_CLASSES)).isNotEmpty())) {
                "can't set both a class whitelist and blacklist"
            }
            unsafeExceptions.forEach { name ->
                val klass = Class.forName(name) ?: error("$name does not refer to a Java class")
                require(Throwable::class.java.isAssignableFrom(klass)) { "$name does not refer to a Java Throwable" }
            }
        }
        companion object {
            val DEFAULT_WHITELISTED_CLASSES = setOf<String>()
            val DEFAULT_BLACKLISTED_CLASSES = setOf("java.lang.reflect.")
            val DEFAULT_UNSAFE_EXCEPTIONS = setOf<String>()
            val PERMANENTLY_BLACKLISTED_CLASSES = setOf("edu.illinois.cs.cs125.jeed.", "org.objectweb.asm.", "java.lang.invoke.MethodHandles")
            val ALWAYS_UNSAFE_EXCEPTIONS = setOf("java.lang.Error")
        }
    }
    open class ExecutionArguments(
            val timeout: Long = DEFAULT_TIMEOUT,
            val permissions: Set<Permission> = setOf(),
            val maxExtraThreads: Int = DEFAULT_MAX_EXTRA_THREADS,
            val maxOutputLines: Int = DEFAULT_MAX_OUTPUT_LINES,
            val classLoaderConfiguration: ClassLoaderConfiguration = ClassLoaderConfiguration()
    ) {
        companion object {
            const val DEFAULT_TIMEOUT = 100L
            const val DEFAULT_MAX_EXTRA_THREADS = 0
            const val DEFAULT_MAX_OUTPUT_LINES = 1024
            val DEFAULT_BLACKLISTED_CLASSES = setOf("java.lang.reflect.")
        }
    }

    class TaskResults<T>(
            val returned: T?,
            val threw: Throwable?,
            val timeout: Boolean,
            val outputLines: MutableList<OutputLine> = mutableListOf(),
            val permissionRequests: MutableList<PermissionRequest> = mutableListOf(),
            val interval: Interval,
            val executionInterval: Interval,
            @Transient val sandboxedClassLoader: SandboxedClassLoader? = null,
            val outputTruncated: Boolean
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
    val PERMANENTLY_BLACKLISTED_CLASSES = setOf("edu.illinois.cs.cs125.jeed.", "org.objectweb.asm.*")
    val ALWAYS_UNSAFE_EXCEPTIONS = setOf("java.lang.Error")

    suspend fun <T> execute(
            sandboxedClassLoader: SandboxedClassLoader,
            executionArguments: ExecutionArguments,
            callable: SandboxCallableArguments<T>
    ): TaskResults<out T?> {
        require(executionArguments.permissions.intersect(BLACKLISTED_PERMISSIONS).isEmpty()) {
            "attempt to allow unsafe permissions"
        }

        return coroutineScope {
            val resultsChannel = Channel<ExecutorResult<T>>()
            val executor = Executor(callable, sandboxedClassLoader, executionArguments, resultsChannel)
            submitToThreadPool(executor)
            val result = executor.resultChannel.receive()
            result.taskResults ?: throw result.executionException
        }
    }

    suspend fun <T> execute(
            sandboxableClassLoader: SandboxableClassLoader = EmptyClassLoader,
            executionArguments: ExecutionArguments = ExecutionArguments(),
            callable: SandboxCallableArguments<T>
    ): TaskResults<out T?> {
        val sandboxedClassLoader = SandboxedClassLoader(sandboxableClassLoader, executionArguments.classLoaderConfiguration)
        return execute(sandboxedClassLoader, executionArguments, callable)
    }

    private const val MAX_THREAD_SHUTDOWN_RETRIES = 256
    private const val THREAD_SHUTDOWN_DELAY = 20L

    private var threadPool: ExecutorService? = null
    private val threadPoolSynclock = Object()
    private fun submitToThreadPool(task: Executor<*>) {
        synchronized(threadPoolSynclock) {
            if (threadPool == null) {
                threadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors()) ?: error("thread pool should be available")
            }
            threadPool!!.submit(task)
        }
    }
    @JvmStatic
    fun shutdownThreadPool() {
        synchronized(threadPoolSynclock) {
            threadPool?.shutdownNow()
            threadPool = null
        }
    }

    private data class ExecutorResult<T>(val taskResults: TaskResults<T>?, val executionException: Throwable)
    private class Executor<T>(
            val callable: SandboxCallableArguments<T>,
            val sandboxedClassLoader: SandboxedClassLoader,
            val executionArguments: ExecutionArguments,
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
                    TaskResult(null, null, true)
                } catch (e: Throwable) {
                    TaskResult(null, e.cause)
                }
                val executionEnded = Instant.now()
                release(confinedTask)

                val executionResult = TaskResults(
                        taskResult.returned, taskResult.threw, taskResult.timeout,
                        confinedTask.outputLines,
                        confinedTask.permissionRequests,
                        Interval(confinedTask.started, Instant.now()),
                        Interval(executionStarted, executionEnded),
                        sandboxedClassLoader,
                        confinedTask.outputTruncated
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
            executionArguments: ExecutionArguments
    ) {
        val threadGroup = thread.threadGroup ?: error("thread should be in thread group")
        val accessControlContext: AccessControlContext
        init {
            val permissions = Permissions()
            executionArguments.permissions.forEach { permissions.add(it) }
            accessControlContext = AccessControlContext(arrayOf(ProtectionDomain(null, permissions)))
        }
        val maxExtraThreads: Int = executionArguments.maxExtraThreads
        val maxOutputLines: Int = executionArguments.maxOutputLines

        @Volatile
        var shuttingDown: Boolean = false

        var outputTruncated: Boolean = false
        val currentLines: MutableMap<TaskResults.OutputLine.Console, CurrentLine> = mutableMapOf()
        val outputLines: MutableList<TaskResults.OutputLine> = mutableListOf()

        val redirectedOutputLines: MutableMap<TaskResults.OutputLine.Console, StringBuilder> = mutableMapOf(
                TaskResults.OutputLine.Console.STDOUT to StringBuilder(),
                TaskResults.OutputLine.Console.STDERR to StringBuilder()
        )
        var redirectingOutput: Boolean = false

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

        private val ourStdout = object : OutputStream() {
            override fun write(int: Int) {
                redirectedWrite(int, TaskResults.OutputLine.Console.STDOUT)
            }
        }
        private val ourStderr = object : OutputStream() {
            override fun write(int: Int) {
                redirectedWrite(int, TaskResults.OutputLine.Console.STDERR)
            }
        }
        val printStreams: Map<TaskResults.OutputLine.Console, PrintStream> = mapOf(
                TaskResults.OutputLine.Console.STDOUT to PrintStream(ourStdout),
                TaskResults.OutputLine.Console.STDERR to PrintStream(ourStderr)
        )
        private fun redirectedWrite(int: Int, console: TaskResults.OutputLine.Console) {
            if (shuttingDown) {
                return
            }

            val currentLine = currentLines.getOrPut(console, { CurrentLine() })
            when (val char = int.toChar()) {
                '\n' -> {
                    if (outputLines.size < maxOutputLines) {
                        outputLines.add(
                                TaskResults.OutputLine(
                                        console,
                                        currentLine.line.toString(),
                                        currentLine.started,
                                        currentLine.startedThread
                                )
                        )
                    } else {
                        outputTruncated = true
                    }
                    currentLines.remove(console)

                    if (redirectingOutput) {
                        redirectedOutputLines[console]?.append(currentLine.line.toString())
                    }
                }
                '\r' -> {
                    // Ignore - results will contain Unix line endings only
                }
                else -> {
                    if (!outputTruncated) {
                        currentLine.line.append(char)
                    }
                }
            }
        }
    }

    private val confinedTasks: MutableMap<ThreadGroup, ConfinedTask<*>> = mutableMapOf()
    private fun confinedTaskByThreadGroup(): ConfinedTask<*>? {
        return confinedTasks[Thread.currentThread().threadGroup]
    }

    @Synchronized
    private fun <T> confine(
            callable: SandboxCallableArguments<T>,
            sandboxedClassLoader: SandboxedClassLoader,
            executionArguments: ExecutionArguments
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
    @Synchronized
    private fun <T> release(confinedTask: ConfinedTask<T>) {
        val threadGroup = confinedTask.threadGroup
        require(confinedTasks.containsKey(threadGroup)) { "thread group is not confined" }

        confinedTask.shuttingDown = true

        if (threadGroup.activeGroupCount() > 0) {
            val threadGroups = Array<ThreadGroup?>(threadGroup.activeGroupCount()) { null }
            threadGroup.enumerate(threadGroups, true)
            assert(threadGroups.toList().filterNotNull().map { it.activeCount() }.sum() == 0)
        }

        runBlocking {
            val stoppedThreads: MutableSet<Thread> = mutableSetOf()
            val threadGroupShutdownRetries = (0..MAX_THREAD_SHUTDOWN_RETRIES).find {
                if (threadGroup.activeCount() == 0) {
                    return@find true
                }
                val activeThreads = arrayOfNulls<Thread>(threadGroup.activeCount() * 2)
                threadGroup.enumerate(activeThreads)
                activeThreads.filterNotNull().filter { !stoppedThreads.contains(it) }.forEach {
                    stoppedThreads.add(it)
                    @Suppress("DEPRECATION") it.stop()
                }
                threadGroup.maxPriority = Thread.NORM_PRIORITY
                stoppedThreads.filter { it.isAlive }.forEach {
                    it.priority = Thread.NORM_PRIORITY
                    it.join(THREAD_SHUTDOWN_DELAY)
                    it.priority = Thread.MIN_PRIORITY
                }
                false
            }

            assert(threadGroupShutdownRetries != null) { "failed to shut down thread group" }
            threadGroup.destroy()
            assert(threadGroup.isDestroyed)
        }


        if (!confinedTask.outputTruncated) {
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
        }

        confinedTasks.remove(threadGroup)
    }

    private class SandboxedCallable<T>(
            val callable: SandboxCallableArguments<T>,
            val sandboxedClassLoader: SandboxedClassLoader
    ): Callable<T> {
        override fun call(): T {
            return callable(Pair(sandboxedClassLoader, Sandbox::redirectOutput))
        }
    }

    interface SandboxableClassLoader {
        val bytecodeForClasses: Map<String, ByteArray>
        val classLoader: ClassLoader
    }
    interface EnumerableClassLoader {
        val definedClasses: Set<String>
        val providedClasses: Set<String>
        val loadedClasses: Set<String>
    }

    class SandboxedClassLoader(
            sandboxableClassLoader: SandboxableClassLoader,
            classLoaderConfiguration: ClassLoaderConfiguration
    ) : ClassLoader(sandboxableClassLoader.classLoader.parent), EnumerableClassLoader {
        val whitelistedClasses = classLoaderConfiguration.whitelistedClasses
        val blacklistedClasses = classLoaderConfiguration.blacklistedClasses
        val unsafeExceptionClasses: Set<Class<*>> = classLoaderConfiguration.unsafeExceptions.map { name ->
            val klass = Class.forName(name) ?: error("$name does not refer to a Java class")
            require(Throwable::class.java.isAssignableFrom(klass)) { "$name does not refer to a Java Throwable" }
            klass
        }.toSet()

        override val definedClasses: Set<String> get() = knownClasses.keys.toSet()
        override val providedClasses: MutableSet<String> = mutableSetOf()
        override val loadedClasses: MutableSet<String> = mutableSetOf()

        val knownClasses: Map<String, ByteArray>
        init {
            knownClasses = sandboxableClassLoader.bytecodeForClasses.mapValues { (_, unsafeByteArray) ->
                RewriteTryCatchFinally.rewrite(unsafeByteArray, unsafeExceptionClasses)
            }
        }

        override fun findClass(name: String): Class<*> {
            return if (knownClasses.containsKey(name)) {
                loadedClasses.add(name)
                providedClasses.add(name)
                val byteArray = knownClasses[name] ?: error("should still be in map")
                return defineClass(name, byteArray, 0, byteArray.size)
            } else {
                super.findClass(name)
            }
        }

        val filter = whitelistedClasses.isNotEmpty() || blacklistedClasses.isNotEmpty()
        val isWhiteList = whitelistedClasses.isNotEmpty()
        private fun delegateClass(name: String): Class<*> {
            val klass = super.loadClass(name)
            loadedClasses.add(name)
            return klass
        }
        override fun loadClass(name: String): Class<*> {
            if (!filter) {
                return delegateClass(name)
            }
            val confinedTask = confinedTaskByThreadGroup() ?: return super.loadClass(name)

            if (knownClasses.containsKey(name)) {
                return delegateClass(name)
            }
            if (name in ALWAYS_ALLOWED_CLASS_NAMES) {
                return delegateClass(name)
            }
            return if (isWhiteList) {
                if (whitelistedClasses.any { name.startsWith(it) }) {
                    delegateClass(name)
                } else {
                    confinedTask.addPermissionRequest(RuntimePermission("loadClass $name"), granted = false, throwException = false)
                    throw ClassNotFoundException(name)
                }
            } else {
                if (blacklistedClasses.any { name.startsWith(it) }) {
                    confinedTask.addPermissionRequest(RuntimePermission("loadClass $name"), granted = false, throwException = false)
                    throw ClassNotFoundException(name)
                } else {
                    delegateClass(name)
                }
            }
        }

        companion object {
            private val ALWAYS_ALLOWED_CLASS_NAMES = setOf(RewriteTryCatchFinally::class.java.name, InvocationTargetException::class.java.name)
        }
    }

    object EmptyClassLoader : ClassLoader(getSystemClassLoader()), SandboxableClassLoader {
        override val bytecodeForClasses: Map<String, ByteArray> = mapOf()
        override val classLoader: ClassLoader = this
        override fun findClass(name: String): Class<*> {
            throw ClassNotFoundException(name)
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
            if (confinedTask.shuttingDown) {
                throw ThreadDeath()
            }
            // This check is required because of how we handle finally blocks
            if (confinedTask.classLoader.unsafeExceptionClasses.any { it.isAssignableFrom(throwable.javaClass) }) {
                throw(throwable)
            }
        }

        fun rewrite(originalByteArray: ByteArray, unsafeExceptionClasses: Set<Class<*>>): ByteArray {
            val classReader = ClassReader(originalByteArray)
            val classWriter = ClassWriter(classReader, ClassWriter.COMPUTE_MAXS)
            val ourVisitor = object : ClassVisitor(Opcodes.ASM5, classWriter) {
                override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
                    return if (name == "finalize" && descriptor == "()V") {
                        null // Drop the finalizer
                    } else {
                        OurMethodVisitor(unsafeExceptionClasses, super.visitMethod(access, name, descriptor, signature, exceptions))
                    }
                }
            }

            classReader.accept(ourVisitor, 0)
            return classWriter.toByteArray()
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

                    if (unsafeExceptionClasses.any { exceptionClass.isAssignableFrom(it) || it.isAssignableFrom(exceptionClass) }) {
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
        private fun confinedTaskByClassLoader(): ConfinedTask<*>? {
            val confinedTask = confinedTaskByThreadGroup() ?: return null
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
                if (confinedTask.shuttingDown) throw ThreadDeath()
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
            if (confinedTask.shuttingDown) {
                confinedTask.permissionRequests.add(TaskResults.PermissionRequest(RuntimePermission("createThreadAfterTimeout"), false))
                throw ThreadDeath()
            }
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

    private fun defaultWrite(int: Int, console: TaskResults.OutputLine.Console) {
        when (console) {
            TaskResults.OutputLine.Console.STDOUT -> originalStdout.write(int)
            TaskResults.OutputLine.Console.STDERR -> originalStderr.write(int)
        }
    }

    @JvmStatic
    fun redirectOutput(block: () -> Unit): Pair<String, String> {
        val confinedTask = confinedTaskByThreadGroup() ?: check { "should only be used from a confined task" }
        check(!confinedTask.redirectingOutput) { "can't nest calls to redirectOutput" }

        confinedTask.redirectingOutput = true
        block()
        confinedTask.redirectingOutput = false

        val toReturn = Pair(
                confinedTask.redirectedOutputLines[TaskResults.OutputLine.Console.STDOUT].toString(),
                confinedTask.redirectedOutputLines[TaskResults.OutputLine.Console.STDERR].toString()
        )
        confinedTask.redirectedOutputLines[TaskResults.OutputLine.Console.STDOUT] = StringBuilder()
        confinedTask.redirectedOutputLines[TaskResults.OutputLine.Console.STDERR] = StringBuilder()

        return toReturn
    }

    private var originalStdout = System.out
    private var originalStderr = System.err

    private var originalPrintStreams: Map<TaskResults.OutputLine.Console, PrintStream> = mapOf(
            TaskResults.OutputLine.Console.STDOUT to originalStdout,
            TaskResults.OutputLine.Console.STDERR to originalStderr
    )

    /*
     * Obviously this requires some explanation. One of the saddest pieces of code I've ever written...
     *
     * The problem is that System.out is a PrintStream. And, internally, it passes content through several buffered
     * streams before it gets to the output stream that you pass.
     *
     * This becomes a problem once you have to kill off runaway threads. If a thread exits uncleanly,
     * it can leave content somewhere in the buffers hidden by the PrintStream. Which is then spewed out at whoever
     * happens to use the stream next. Not OK.
     *
     * Our original plan was to have _one_ PrintStream (per console) that sent all output to a shared OutputStream
     * and then separate it there by thread group. This is much, much cleaner since you only have to override the
     * OutputStream public interface which is one function. (See the code above on the ConfinedTask class.) But again,
     * this fails in the presence of unclean exits.
     *
     * Note that the problem here isn't really with concurrency: it's with unclean exit. Concurrency just means that
     * you don't know whose output stream might be poluted by the garbage left over if you share a PrintStream.
     *
     * So the "solution" is to create one PrintStream per confined task. This works because unclean exit just leaves
     * detritus in that thread group's PrintStream which is eventually cleaned up and destroyed.
     *
     * But the result is that you have to implement the entire PrintStream public API. Leaving anything out means that
     * stuff doesn't get forwarded, and we have to make sure that nothing is shared.
     *
     * Hence this sadness.
     *
     * PS: also fuck you Java for leaving me no choice but to resort to this. There are a half-dozen different terrible
     * design decisions that led us to this place. Please do better next time.
     */
    private val nullOutputStream = object : OutputStream() { override fun write(b: Int) { } }
    private class RedirectingPrintStream(val console: TaskResults.OutputLine.Console) : PrintStream(nullOutputStream) {
        override fun append(char: Char): PrintStream {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).append(char)
            return (confinedTask.printStreams[console] ?: error("console should exist")).append(char)
        }
        override fun append(charSequence: CharSequence): PrintStream {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).append(charSequence)
            return (confinedTask.printStreams[console] ?: error("console should exist")).append(charSequence)
        }
        override fun append(charSequence: CharSequence, start: Int, end: Int): PrintStream {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).append(charSequence, start, end)
            return (confinedTask.printStreams[console] ?: error("console should exist")).append(charSequence, start, end)
        }
        override fun close() {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).close()
            (confinedTask.printStreams[console] ?: error("console should exist")).close()
        }
        override fun flush() {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).flush()
            (confinedTask.printStreams[console] ?: error("console should exist")).flush()
        }
        override fun format(locale: Locale, format: String, vararg args: Any): PrintStream {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).format(locale, format, args)
            return (confinedTask.printStreams[console] ?: error("console should exist")).format(locale, format, args)
        }
        override fun format(format: String, vararg args: Any): PrintStream {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).format(format, args)
            return (confinedTask.printStreams[console] ?: error("console should exist")).format(format, args)
        }
        override fun print(boolean: Boolean) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).print(boolean)
            (confinedTask.printStreams[console] ?: error("console should exist")).print(boolean)
        }
        override fun print(char: Char) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).print(char)
            (confinedTask.printStreams[console] ?: error("console should exist")).print(char)
        }
        override fun print(charArray: CharArray) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).print(charArray)
            (confinedTask.printStreams[console] ?: error("console should exist")).print(charArray)
        }
        override fun print(double: Double) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).print(double)
            (confinedTask.printStreams[console] ?: error("console should exist")).print(double)
        }
        override fun print(float: Float) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).print(float)
            (confinedTask.printStreams[console] ?: error("console should exist")).print(float)
        }
        override fun print(int: Int) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).print(int)
            (confinedTask.printStreams[console] ?: error("console should exist")).print(int)
        }
        override fun print(long: Long) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).print(long)
            (confinedTask.printStreams[console] ?: error("console should exist")).print(long)
        }
        override fun print(any: Any) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).print(any)
            (confinedTask.printStreams[console] ?: error("console should exist")).print(any)
        }
        override fun print(string: String) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).print(string)
            (confinedTask.printStreams[console] ?: error("console should exist")).print(string)
        }
        override fun printf(locale: Locale, format: String, vararg args: Any): PrintStream {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).printf(locale, format, args)
            return (confinedTask.printStreams[console] ?: error("console should exist")).printf(locale, format, args)
        }
        override fun printf(format: String, vararg args: Any): PrintStream {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).printf(format, args)
            return (confinedTask.printStreams[console] ?: error("console should exist")).printf(format, args)
        }
        override fun println() {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).println()
            (confinedTask.printStreams[console] ?: error("console should exist")).println()
        }
        override fun println(boolean: Boolean) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).println(boolean)
            (confinedTask.printStreams[console] ?: error("console should exist")).println(boolean)
        }
        override fun println(char: Char) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).println(char)
            (confinedTask.printStreams[console] ?: error("console should exist")).println(char)
        }
        override fun println(charArray: CharArray) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).println(charArray)
            (confinedTask.printStreams[console] ?: error("console should exist")).println(charArray)
        }
        override fun println(double: Double) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).println(double)
            (confinedTask.printStreams[console] ?: error("console should exist")).println(double)
        }
        override fun println(float: Float) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).println(float)
            (confinedTask.printStreams[console] ?: error("console should exist")).println(float)
        }
        override fun println(int: Int) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).println(int)
            (confinedTask.printStreams[console] ?: error("console should exist")).println(int)
        }
        override fun println(long: Long) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).println(long)
            (confinedTask.printStreams[console] ?: error("console should exist")).println(long)
        }
        override fun println(any: Any) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).println(any)
            (confinedTask.printStreams[console] ?: error("console should exist")).println(any)
        }
        override fun println(string: String) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).println(string)
            (confinedTask.printStreams[console] ?: error("console should exist")).println(string)
        }
        override fun write(byteArray: ByteArray, off: Int, len: Int) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).write(byteArray, off, len)
            (confinedTask.printStreams[console] ?: error("console should exist")).write(byteArray, off, len)
        }
        override fun write(int: Int) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).write(int)
            (confinedTask.printStreams[console] ?: error("console should exist")).write(int)
        }
        override fun write(byteArray: ByteArray) {
            val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console] ?: error("console should exist")).write(byteArray)
            (confinedTask.printStreams[console] ?: error("console should exist")).write(byteArray)
        }
    }
    init {
        System.setOut(RedirectingPrintStream(TaskResults.OutputLine.Console.STDOUT))
        System.setErr(RedirectingPrintStream(TaskResults.OutputLine.Console.STDERR))
        // Try to silence ThreadDeath error messages. Not sure this works but it can't hurt.
        Thread.setDefaultUncaughtExceptionHandler { _, _ ->  }
    }
}

fun Sandbox.SandboxableClassLoader.sandbox(classLoaderConfiguration: Sandbox.ClassLoaderConfiguration): Sandbox.SandboxedClassLoader {
    return Sandbox.SandboxedClassLoader(this, classLoaderConfiguration)
}
