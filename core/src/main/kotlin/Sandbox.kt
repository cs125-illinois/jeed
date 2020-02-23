package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
import java.io.FilePermission
import java.io.OutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.security.AccessControlContext
import java.security.Permission
import java.security.Permissions
import java.security.ProtectionDomain
import java.security.SecurityPermission
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.reflect.jvm.javaMethod
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

private typealias SandboxCallableArguments<T> = (Pair<ClassLoader, (() -> Unit) -> Pair<String, String>>) -> T

object Sandbox {
    @JsonClass(generateAdapter = true)
    class ClassLoaderConfiguration(
        val whitelistedClasses: Set<String> = DEFAULT_WHITELISTED_CLASSES,
        blacklistedClasses: Set<String> = DEFAULT_BLACKLISTED_CLASSES,
        unsafeExceptions: Set<String> = DEFAULT_UNSAFE_EXCEPTIONS,
        isolatedClasses: Set<String> = DEFAULT_ISOLATED_CLASSES
    ) {
        val blacklistedClasses = blacklistedClasses.union(PERMANENTLY_BLACKLISTED_CLASSES)
        val unsafeExceptions = unsafeExceptions.union(ALWAYS_UNSAFE_EXCEPTIONS)
        val isolatedClasses = isolatedClasses.union(ALWAYS_ISOLATED_CLASSES)

        init {
            require(!whitelistedClasses.any { whitelistedClass ->
                PERMANENTLY_BLACKLISTED_CLASSES.any { blacklistedClass ->
                    whitelistedClass.startsWith(blacklistedClass)
                }
            }) {
                "attempt to allow access to unsafe classes"
            }
            require(
                !(whitelistedClasses.isNotEmpty() &&
                    blacklistedClasses.minus(
                        PERMANENTLY_BLACKLISTED_CLASSES.union(DEFAULT_BLACKLISTED_CLASSES)
                    ).isNotEmpty())
            ) {
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
            val DEFAULT_ISOLATED_CLASSES = setOf<String>()
            val PERMANENTLY_BLACKLISTED_CLASSES =
                setOf(
                    "edu.illinois.cs.cs125.jeed.",
                    "org.objectweb.asm.",
                    "java.lang.invoke.MethodHandles"
                )
            val ALWAYS_UNSAFE_EXCEPTIONS = setOf("java.lang.Error")
            val ALWAYS_ISOLATED_CLASSES = setOf("kotlin.coroutines.", "kotlinx.coroutines.")
        }
    }

    @JsonClass(generateAdapter = true)
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
        val truncatedLines: Int,
        @Suppress("unused")
        val executionArguments: ExecutionArguments
    ) {
        @JsonClass(generateAdapter = true)
        data class OutputLine(val console: Console, val line: String, val timestamp: Instant, val thread: Long) {
            enum class Console { STDOUT, STDERR }
        }

        @JsonClass(generateAdapter = true)
        data class PermissionRequest(val permission: Permission, val granted: Boolean)

        val completed: Boolean
            get() {
                return threw == null && !timeout
            }
        val permissionDenied: Boolean
            get() {
                return permissionRequests.any { !it.granted }
            }
        val stdoutLines: List<OutputLine>
            get() {
                return outputLines.filter { it.console == OutputLine.Console.STDOUT }
            }
        val stderrLines: List<OutputLine>
            get() {
                return outputLines.filter { it.console == OutputLine.Console.STDERR }
            }
        val stdout: String
            get() {
                return stdoutLines.joinToString(separator = "\n") { it.line }
            }
        val stderr: String
            get() {
                return stderrLines.joinToString(separator = "\n") { it.line }
            }
        val output: String
            get() {
                return outputLines.sortedBy { it.timestamp }.joinToString(separator = "\n") { it.line }
            }
        val totalDuration: Duration
            get() {
                return Duration.between(interval.start, interval.end)
            }
    }

    private val BLACKLISTED_PERMISSIONS = setOf(
        // Suggestions from here: https://github.com/pro-grade/pro-grade/issues/31.
        RuntimePermission("createClassLoader"),
        RuntimePermission("accessClassInPackage.sun"),
        RuntimePermission("setSecurityManager"),
        // Required for Java Streams to work...
        // ReflectPermission("suppressAccessChecks")
        SecurityPermission("setPolicy"),
        SecurityPermission("setProperty.package.access"),
        @Suppress("MaxLineLength")
        // Other additions from here: https://docs.oracle.com/javase/7/docs/technotes/guides/security/permissions.html
        SecurityPermission("createAccessControlContext"),
        SecurityPermission("getDomainCombiner"),
        RuntimePermission("createSecurityManager"),
        RuntimePermission("exitVM"),
        RuntimePermission("shutdownHooks"),
        RuntimePermission("setIO"),
        RuntimePermission("queuePrintJob"),
        RuntimePermission("setDefaultUncaughtExceptionHandler"),
        // These are particularly important to prevent untrusted code from escaping the sandbox
        // which is based on thread groups
        RuntimePermission("modifyThread"),
        RuntimePermission("modifyThreadGroup")
    )

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
        val sandboxedClassLoader =
            SandboxedClassLoader(sandboxableClassLoader, executionArguments.classLoaderConfiguration)
        return execute(sandboxedClassLoader, executionArguments, callable)
    }

    private const val MAX_THREAD_SHUTDOWN_RETRIES = 256
    private const val THREAD_SHUTDOWN_DELAY = 20L

    private var threadPool: ExecutorService? = null
    private val threadPoolSynclock = Object()
    private fun submitToThreadPool(task: Executor<*>) {
        synchronized(threadPoolSynclock) {
            if (threadPool == null) {
                threadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
                    ?: error("thread pool should be available")
            }
            threadPool!!.submit(task)
        }
    }

    @Suppress("unused")
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
    ) : Callable<Any> {
        private data class TaskResult<T>(val returned: T, val threw: Throwable? = null, val timeout: Boolean = false)

        override fun call() {
            @Suppress("TooGenericExceptionCaught")
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
                    confinedTask.truncatedLines,
                    executionArguments
                )
                runBlocking { resultChannel.send(ExecutorResult(executionResult, Exception())) }
            } catch (e: Throwable) {
                runBlocking { resultChannel.send(ExecutorResult(null, e)) }
            } finally {
                resultChannel.close()
            }
        }
    }

    private class ConfinedTask<T>(
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

        var truncatedLines: Int = 0
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
                        truncatedLines += 1
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
                    if (truncatedLines == 0) {
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
            @Suppress("EmptyFunctionBlock")
            override fun uncaughtException(t: Thread?, e: Throwable?) {
            }
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

        val stoppedThreads: MutableSet<Thread> = mutableSetOf()
        val threadGroupShutdownRetries = (0..MAX_THREAD_SHUTDOWN_RETRIES).find {
            if (threadGroup.activeCount() == 0) {
                return@find true
            }
            val activeThreads = arrayOfNulls<Thread>(threadGroup.activeCount() * 2)
            threadGroup.enumerate(activeThreads)
            activeThreads.filterNotNull().filter { !stoppedThreads.contains(it) }.forEach {
                stoppedThreads.add(it)
                it.setUncaughtExceptionHandler { _, _ -> throw ThreadDeath() }
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

        if (confinedTask.truncatedLines == 0) {
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
    ) : Callable<T> {
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
        private val sandboxableClassLoader: SandboxableClassLoader,
        classLoaderConfiguration: ClassLoaderConfiguration
    ) : ClassLoader(sandboxableClassLoader.classLoader.parent), EnumerableClassLoader {
        private val whitelistedClasses = classLoaderConfiguration.whitelistedClasses
        private val blacklistedClasses = classLoaderConfiguration.blacklistedClasses
        private val isolatedClasses = classLoaderConfiguration.isolatedClasses
        val unsafeExceptionClasses: Set<Class<*>> = classLoaderConfiguration.unsafeExceptions.map { name ->
            val klass = Class.forName(name) ?: error("$name does not refer to a Java class")
            require(Throwable::class.java.isAssignableFrom(klass)) { "$name does not refer to a Java Throwable" }
            klass
        }.toSet()

        override val definedClasses: Set<String> get() = knownClasses.keys.toSet()
        override val providedClasses: MutableSet<String> = mutableSetOf()
        override val loadedClasses: MutableSet<String> = mutableSetOf()

        private val reloadedClasses: MutableMap<String, Class<*>> = mutableMapOf()
        private val reloader = TrustedReloader()

        @Suppress("MemberVisibilityCanBePrivate")
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

        private val isWhiteList = whitelistedClasses.isNotEmpty()
        private fun delegateClass(name: String): Class<*> {
            val klass = super.loadClass(name)
            loadedClasses.add(name)
            return klass
        }

        @Suppress("ReturnCount")
        override fun loadClass(name: String): Class<*> {
            val confinedTask = confinedTaskByThreadGroup() ?: return super.loadClass(name)

            if (isolatedClasses.any { name.startsWith(it) }) {
                if (!isWhiteList && blacklistedClasses.any { name.startsWith(it) }) {
                    confinedTask.addPermissionRequest(
                        RuntimePermission("loadIsolatedClass $name"),
                        granted = false,
                        throwException = false)
                    throw ClassNotFoundException(name)
                }
                return reloadedClasses.getOrPut(name) {
                    reloader.reload(name).also { loadedClasses.add(name) }
                }
            }
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
                    confinedTask.addPermissionRequest(
                        RuntimePermission("loadClass $name"),
                        granted = false,
                        throwException = false
                    )
                    throw ClassNotFoundException(name)
                }
            } else {
                if (blacklistedClasses.any { name.startsWith(it) }) {
                    confinedTask.addPermissionRequest(
                        RuntimePermission("loadClass $name"),
                        granted = false,
                        throwException = false
                    )
                    throw ClassNotFoundException(name)
                } else {
                    delegateClass(name)
                }
            }
        }

        companion object {
            private val ALWAYS_ALLOWED_CLASS_NAMES =
                setOf(RewriteTryCatchFinally::class.java.name, InvocationTargetException::class.java.name)
            private val reloadedBytecodeCache = mutableMapOf<String, ByteArray>()
        }

        internal inner class TrustedReloader {
            fun reload(name: String): Class<*> {
                val classBytes = reloadedBytecodeCache.getOrPut(name) {
                    sandboxableClassLoader.classLoader.parent
                        .getResourceAsStream("${name.replace('.', '/')}.class")?.readAllBytes()
                        ?: throw ClassNotFoundException("failed to reload $name")
                }
                loadedClasses.add(name)
                return defineClass(name, classBytes, 0, classBytes.size)
            }
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
        val checkClassName =
            classNameToPath(RewriteTryCatchFinally::class.java.name ?: error("should have a class name"))
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
            val ourVisitor = object : ClassVisitor(Opcodes.ASM7, classWriter) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    descriptor: String,
                    signature: String?,
                    exceptions: Array<out String>?
                ): MethodVisitor? {
                    return if (name == "finalize" && descriptor == "()V") {
                        null // Drop the finalizer
                    } else {
                        OurMethodVisitor(
                            unsafeExceptionClasses,
                            super.visitMethod(access, name, descriptor, signature, exceptions)
                        )
                    }
                }
            }

            classReader.accept(ourVisitor, 0)
            return classWriter.toByteArray()
        }

        private class OurMethodVisitor(
            val unsafeExceptionClasses: Set<Class<*>>,
            methodVisitor: MethodVisitor
        ) : MethodVisitor(Opcodes.ASM7, methodVisitor) {
            private val labelsToRewrite: MutableSet<Label> = mutableSetOf()
            private var rewroteLabel = false

            override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
                if (type == null) {
                    labelsToRewrite.add(handler)
                } else {
                    val exceptionClass = Class.forName(pathToClassName(type))
                        ?: error("no class for type $type")

                    if (unsafeExceptionClasses.any {
                            exceptionClass.isAssignableFrom(it) || it.isAssignableFrom(
                                exceptionClass
                            )
                        }) {
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

            override fun visitFrame(
                type: Int,
                numLocal: Int,
                local: Array<out Any>?,
                numStack: Int,
                stack: Array<out Any>?
            ) {
                super.visitFrame(type, numLocal, local, numStack, stack)
                if (nextLabel != null) {
                    super.visitInsn(Opcodes.DUP)
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        checkClassName,
                        checkMethodName,
                        checkMethodDescription,
                        false
                    )
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

    val systemSecurityManager: SecurityManager? = System.getSecurityManager()

    private object SandboxSecurityManager : SecurityManager() {
        @Suppress("ReturnCount")
        private fun confinedTaskByClassLoader(): ConfinedTask<*>? {
            val confinedTask = confinedTaskByThreadGroup() ?: return null
            var classIsConfined = false
            classContext.forEach { klass ->
                if (klass.classLoader == confinedTask.classLoader) {
                    classIsConfined = true
                    return@forEach
                } else if (klass == SandboxedClassLoader.TrustedReloader::class.java) {
                    return null
                }
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

        @Suppress("ReturnCount")
        override fun getThreadGroup(): ThreadGroup {
            val threadGroup = Thread.currentThread().threadGroup
            val confinedTask = confinedTaskByThreadGroup()
                ?: return systemSecurityManager?.threadGroup ?: return threadGroup
            if (confinedTask.shuttingDown) {
                confinedTask.permissionRequests.add(
                    TaskResults.PermissionRequest(
                        RuntimePermission("createThreadAfterTimeout"),
                        false
                    )
                )
                throw ThreadDeath()
            }
            if (Thread.currentThread().threadGroup.activeCount() >= confinedTask.maxExtraThreads + 1) {
                confinedTask.permissionRequests.add(
                    TaskResults.PermissionRequest(
                        RuntimePermission("exceedThreadLimit"),
                        false
                    )
                )
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
     * you don't know whose output stream might be polluted by the garbage left over if you share a PrintStream.
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
    @Suppress("EmptyFunctionBlock")
    private val nullOutputStream = object : OutputStream() {
        override fun write(b: Int) {}
    }

    @Suppress("TooManyFunctions", "SpreadOperator")
    private class RedirectingPrintStream(val console: TaskResults.OutputLine.Console) : PrintStream(nullOutputStream) {
        private val taskPrintStream: PrintStream
            get() {
                val confinedTask = confinedTaskByThreadGroup() ?: return (originalPrintStreams[console]
                    ?: error("original console should exist"))
                return confinedTask.printStreams[console] ?: error("confined console should exist")
            }

        override fun append(char: Char): PrintStream {
            return taskPrintStream.append(char)
        }

        override fun append(charSequence: CharSequence?): PrintStream {
            return taskPrintStream.append(charSequence)
        }

        override fun append(charSequence: CharSequence?, start: Int, end: Int): PrintStream {
            return taskPrintStream.append(charSequence, start, end)
        }

        override fun close() {
            taskPrintStream.close()
        }

        override fun flush() {
            taskPrintStream.flush()
        }

        override fun format(locale: Locale?, format: String, vararg args: Any?): PrintStream {
            return taskPrintStream.format(locale, format, *args)
        }

        override fun format(format: String, vararg args: Any?): PrintStream {
            return taskPrintStream.format(format, *args)
        }

        override fun print(boolean: Boolean) {
            taskPrintStream.print(boolean)
        }

        override fun print(char: Char) {
            taskPrintStream.print(char)
        }

        override fun print(charArray: CharArray) {
            taskPrintStream.print(charArray)
        }

        override fun print(double: Double) {
            taskPrintStream.print(double)
        }

        override fun print(float: Float) {
            taskPrintStream.print(float)
        }

        override fun print(int: Int) {
            taskPrintStream.print(int)
        }

        override fun print(long: Long) {
            taskPrintStream.print(long)
        }

        override fun print(any: Any?) {
            taskPrintStream.print(any)
        }

        override fun print(string: String?) {
            taskPrintStream.print(string)
        }

        override fun printf(locale: Locale?, format: String, vararg args: Any?): PrintStream {
            return taskPrintStream.printf(locale, format, *args)
        }

        override fun printf(format: String, vararg args: Any?): PrintStream {
            return taskPrintStream.printf(format, *args)
        }

        override fun println() {
            taskPrintStream.println()
        }

        override fun println(boolean: Boolean) {
            taskPrintStream.println(boolean)
        }

        override fun println(char: Char) {
            taskPrintStream.println(char)
        }

        override fun println(charArray: CharArray) {
            taskPrintStream.println(charArray)
        }

        override fun println(double: Double) {
            taskPrintStream.println(double)
        }

        override fun println(float: Float) {
            taskPrintStream.println(float)
        }

        override fun println(int: Int) {
            taskPrintStream.println(int)
        }

        override fun println(long: Long) {
            taskPrintStream.println(long)
        }

        override fun println(any: Any?) {
            taskPrintStream.println(any)
        }

        override fun println(string: String?) {
            taskPrintStream.println(string)
        }

        override fun write(byteArray: ByteArray, off: Int, len: Int) {
            taskPrintStream.write(byteArray, off, len)
        }

        override fun write(int: Int) {
            taskPrintStream.write(int)
        }

        override fun write(byteArray: ByteArray) {
            taskPrintStream.write(byteArray)
        }
    }

    init {
        System.setOut(RedirectingPrintStream(TaskResults.OutputLine.Console.STDOUT))
        System.setErr(RedirectingPrintStream(TaskResults.OutputLine.Console.STDERR))
        // Try to silence ThreadDeath error messages. Not sure this works but it can't hurt.
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
    }
}

fun Sandbox.SandboxableClassLoader.sandbox(
    classLoaderConfiguration: Sandbox.ClassLoaderConfiguration
): Sandbox.SandboxedClassLoader {
    return Sandbox.SandboxedClassLoader(this, classLoaderConfiguration)
}
