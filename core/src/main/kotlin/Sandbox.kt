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
            val maxExtraThreads: Int = DEFAULT_MAX_EXTRA_THREADS
    ) {
        val blacklistedClasses = blacklistedClasses.union(PERMANENTLY_BLACKLISTED_CLASSES)
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
    val PERMANENTLY_BLACKLISTED_CLASSES = setOf("java.lang.reflect.")

    suspend fun <T> execute(
            byteCodeProvidingClassLoader: ByteCodeProvidingClassLoader,
            executionArguments: ExecutionArguments<T>,
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

        val sandboxedClassLoader = SandboxedClassLoader(
                byteCodeProvidingClassLoader,
                executionArguments.whitelistedClasses,
                executionArguments.blacklistedClasses
        )
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
        val threadGroup = ThreadGroup("Sandbox")
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

        (0..MAX_THREAD_SHUTDOWN_RETRIES).find {
            return@find if (threadGroup.activeCount() == 0) {
                true
            } else {
                @Suppress("DEPRECATION") threadGroup.stop()
                Thread.sleep(THREADGROUP_SHUTDOWN_DELAY)
                false
            }
        }

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
            val whitelistedClasses: Set<String> = setOf(),
            val blacklistedClasses: Set<String> = setOf()
    ) : ClassLoader() {
        val knownClasses: Map<String, Class<*>>
        init {
            knownClasses = byteCodeProvidingClassLoader.bytecodeForClasses.mapValues { (name, unsafeByteArray) ->
                val safeByteArray = sandboxClass(unsafeByteArray)
                defineClass(name, safeByteArray, 0, safeByteArray.size)
            }
        }
        override fun findClass(name: String): Class<*> {
            return knownClasses[name] ?: super.findClass(name)
        }

        val filter = whitelistedClasses.isNotEmpty() || blacklistedClasses.isNotEmpty()
        val isWhiteList = whitelistedClasses.isNotEmpty()
        override fun loadClass(name: String): Class<*> {
            if (!filter) { return super.loadClass(name) }
            val confinedTask = confinedTaskByThreadGroup() ?: return super.loadClass(name)

            if (knownClasses.containsKey(name)) { return super.loadClass(name) }
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

        private class SandboxingByteCodeRewriter(classWriter: ClassWriter) : ClassVisitor(Opcodes.ASM5, classWriter) {
            val labelsToRewrite: MutableMap<Label, String> = mutableMapOf()
            override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor {
                val visitor = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodVisitor(Opcodes.ASM5, visitor) {
                    private val waitForFrame: MutableMap<Label, Boolean> = mutableMapOf()
                    private val blockLabels: MutableSet<Label> = mutableSetOf()
                    override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
                        if (type == null) {
                            if (!labelsToRewrite.contains(start)) {
                                labelsToRewrite[start] = if (!blockLabels.contains(start)) { tryInterceptorMethodName } else { finallyInterceptorMethodName }
                                waitForFrame[start] = blockLabels.contains(start)
                            }
                            labelsToRewrite[handler] = finallyInterceptorMethodName
                            waitForFrame[handler] = true
                        } else {
                            blockLabels.add(handler)
                            if (!labelsToRewrite.contains(start)) {
                                labelsToRewrite[start] = tryInterceptorMethodName
                                waitForFrame[start] = blockLabels.contains(start)
                            }
                            if (threadDeathAndParents.contains(type)) {
                                labelsToRewrite[handler] = catchInterceptorMethodName
                                waitForFrame[handler] = true
                            }
                        }
                        super.visitTryCatchBlock(start, end, handler, type)
                    }
                    var aboutToInsert: String? = null
                    var sawFrame: Boolean = false
                    override fun visitLabel(label: Label) {
                        if (labelsToRewrite.contains(label)) {
                            assert(aboutToInsert == null)
                            assert(waitForFrame.containsKey(label))
                            aboutToInsert = labelsToRewrite.remove(label)
                            sawFrame = waitForFrame.remove(label) == false
                        }
                        super.visitLabel(label)
                        if (aboutToInsert == tryInterceptorMethodName && sawFrame) {
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    interceptorClassName,
                                    tryInterceptorMethodName,
                                    tryInterceptorDescription,
                                    false
                            )
                            aboutToInsert = null
                        } else if (aboutToInsert == finallyInterceptorMethodName && sawFrame) {
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    interceptorClassName,
                                    finallyInterceptorMethodName,
                                    finallyInterceptorDescription,
                                    false
                            )
                            aboutToInsert = null
                        }
                    }

                    override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any>?, numStack: Int, stack: Array<out Any>?) {
                        super.visitFrame(type, numLocal, local, numStack, stack)
                        if (aboutToInsert == tryInterceptorMethodName && !sawFrame) {
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    interceptorClassName,
                                    tryInterceptorMethodName,
                                    tryInterceptorDescription,
                                    false
                            )
                            aboutToInsert = null
                        } else if (aboutToInsert == finallyInterceptorMethodName && !sawFrame) {
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    interceptorClassName,
                                    finallyInterceptorMethodName,
                                    finallyInterceptorDescription,
                                    false
                            )
                            aboutToInsert = null
                        }
                    }

                    override fun visitVarInsn(opcode: Int, `var`: Int) {
                        super.visitVarInsn(opcode, `var`)
                        if (aboutToInsert != null) {
                            assert(aboutToInsert == catchInterceptorMethodName)
                            assert(opcode == Opcodes.ASTORE)
                            super.visitVarInsn(Opcodes.ALOAD, `var`)
                            super.visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    interceptorClassName,
                                    catchInterceptorMethodName,
                                    catchInterceptorDescription,
                                    false
                            )
                            aboutToInsert = null
                        }
                    }
                }
            }
            companion object {
                private val threadDeathAndParents = setOf("java/lang/ThreadDeath", "java/lang/Error", "java/lang/Throwable")
                private val interceptorClassName = classNameToPath(Sandbox::class.qualifiedName ?: error("Sandbox should have a qualified name"))

                private val tryInterceptorMethodName = Sandbox::checkTry.javaMethod?.name
                        ?: error("checkFinally should have a name")
                private val tryInterceptorDescription = Type.getMethodDescriptor(Sandbox::checkTry.javaMethod)
                        ?: error("should be able to retrieve method signature for checkException")
                private val catchInterceptorMethodName = Sandbox::checkCatch.javaMethod?.name
                        ?: error("checkCatch should have a name")
                private val catchInterceptorDescription = Type.getMethodDescriptor(Sandbox::checkCatch.javaMethod)
                        ?: error("should be able to retrieve method signature for checkCatch")
                private val finallyInterceptorMethodName = Sandbox::checkFinally.javaMethod?.name
                        ?: error("checkFinally should have a name")
                private val finallyInterceptorDescription = Type.getMethodDescriptor(Sandbox::checkFinally.javaMethod)
                        ?: error("should be able to retrieve method signature for checkException")


            }
        }
        companion object {
            fun sandboxClass(byteArray: ByteArray): ByteArray {
                originalStdout.println(printClass(byteArray))
                val classReader = ClassReader(byteArray)
                val classWriter = ClassWriter(classReader, 0)
                val sandboxingByteCodeRewriter = SandboxingByteCodeRewriter(classWriter)

                classReader.accept(sandboxingByteCodeRewriter, 0)
                assert(sandboxingByteCodeRewriter.labelsToRewrite.isEmpty())

                val safeByteArray = classWriter.toByteArray()
                originalStdout.println(printClass(safeByteArray))
                return safeByteArray
            }
            fun printClass(byteArray: ByteArray): String {
                val classReader = ClassReader(byteArray)
                val stringWriter = StringWriter()
                val printWriter = PrintWriter(stringWriter)
                val traceVisitor = TraceClassVisitor(printWriter)
                classReader.accept(traceVisitor, 0)
                return stringWriter.toString()
            }
        }
    }

    @JvmStatic
    fun checkTry() {
        originalStdout.println("Checking try")
        val confinedTask = confinedTaskByThreadGroup() ?: return
        if (confinedTask.shuttingDown) {
            @Suppress("DEPRECATION") Thread.currentThread().stop()
        }
    }
    @JvmStatic
    fun checkCatch(throwable: Throwable) {
        originalStdout.println("catch")
        val confinedTask = confinedTaskByThreadGroup() ?: return
        if (confinedTask.shuttingDown) {
            @Suppress("DEPRECATION") Thread.currentThread().stop()
        }
        if (throwable is ThreadDeath) {
            throw(throwable)
        }
    }
    @JvmStatic
    fun checkFinally() {
        originalStdout.println("Checking finally")
        val confinedTask = confinedTaskByThreadGroup() ?: return
        if (confinedTask.shuttingDown) {
            @Suppress("DEPRECATION") Thread.currentThread().stop()
        }
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
            val confinedTask = confinedTaskByClassLoader()
                    ?: return systemSecurityManager?.checkPermission(permission) ?: return
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
