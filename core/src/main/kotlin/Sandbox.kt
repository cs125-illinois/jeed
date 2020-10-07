package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
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
import java.io.FilePermission
import java.io.OutputStream
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.security.AccessControlContext
import java.security.Permission
import java.security.Permissions
import java.security.ProtectionDomain
import java.security.SecurityPermission
import java.time.Duration
import java.time.Instant
import java.util.Collections
import java.util.Locale
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min
import kotlin.reflect.KProperty
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.isAccessible
import kotlin.reflect.jvm.javaMethod

private typealias SandboxCallableArguments<T> = (Pair<ClassLoader, (() -> Any?) -> JeedOutputCapture>) -> T

object Sandbox {
    @JsonClass(generateAdapter = true)
    class ClassLoaderConfiguration(
        val whitelistedClasses: Set<String> = DEFAULT_WHITELISTED_CLASSES,
        blacklistedClasses: Set<String> = DEFAULT_BLACKLISTED_CLASSES,
        unsafeExceptions: Set<String> = DEFAULT_UNSAFE_EXCEPTIONS,
        isolatedClasses: Set<String> = DEFAULT_ISOLATED_CLASSES,
        val isWhiteList: Boolean? = null
    ) {
        val blacklistedClasses = blacklistedClasses.union(PERMANENTLY_BLACKLISTED_CLASSES)
        val unsafeExceptions = unsafeExceptions.union(ALWAYS_UNSAFE_EXCEPTIONS)
        val isolatedClasses = isolatedClasses.union(ALWAYS_ISOLATED_CLASSES)

        init {
            require(
                !whitelistedClasses.any { whitelistedClass ->
                    PERMANENTLY_BLACKLISTED_CLASSES.any { blacklistedClass ->
                        whitelistedClass.startsWith(blacklistedClass)
                    }
                }
            ) {
                "attempt to allow access to unsafe classes"
            }
            require(
                !(
                    whitelistedClasses.isNotEmpty() &&
                        blacklistedClasses.minus(
                            PERMANENTLY_BLACKLISTED_CLASSES.union(DEFAULT_BLACKLISTED_CLASSES)
                        ).isNotEmpty()
                    )
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
            val COROUTINE_REQUIRED_CLASSES = setOf("java.lang.reflect.Constructor")
        }
    }

    @Suppress("LongParameterList")
    @JsonClass(generateAdapter = true)
    open class ExecutionArguments(
        // var because may be increased in the presence of coroutines
        var timeout: Long = DEFAULT_TIMEOUT,
        val permissions: Set<Permission> = setOf(),
        // var because may be increased in the presence of coroutines
        var maxExtraThreads: Int = DEFAULT_MAX_EXTRA_THREADS,
        val maxOutputLines: Int = DEFAULT_MAX_OUTPUT_LINES,
        val classLoaderConfiguration: ClassLoaderConfiguration = ClassLoaderConfiguration(),
        val waitForShutdown: Boolean = DEFAULT_WAIT_FOR_SHUTDOWN,
        val returnTimeout: Int = DEFAULT_RETURN_TIMEOUT
    ) {
        companion object {
            const val DEFAULT_TIMEOUT = 100L
            const val DEFAULT_MAX_EXTRA_THREADS = 0
            const val DEFAULT_MAX_OUTPUT_LINES = 1024
            const val DEFAULT_WAIT_FOR_SHUTDOWN = false
            const val DEFAULT_RETURN_TIMEOUT = 1
        }
    }

    @Suppress("LongParameterList")
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
        data class OutputLine(
            val console: Console,
            val line: String,
            val timestamp: Instant,
            val thread: Long? = null
        ) {
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

        if (!running) {
            require(autoStart) { "Sandbox not running and autoStart not enabled" }
            start()
            require(running) { "Sandbox not running even after being started" }
        }

        return coroutineScope {
            val resultsChannel = Channel<ExecutorResult<T>>()
            val executor = Executor(callable, sandboxedClassLoader, executionArguments, resultsChannel)
            threadPool.submit(executor)
            val result = executor.resultChannel.receive()
            result.taskResults ?: throw result.executionException
        }
    }

    suspend fun <T> execute(
        sandboxableClassLoader: SandboxableClassLoader = EmptyClassLoader,
        executionArguments: ExecutionArguments = ExecutionArguments(),
        callable: SandboxCallableArguments<T>
    ): TaskResults<out T?> {
        val sandboxedClassLoader = try {
            SandboxedClassLoader(sandboxableClassLoader, executionArguments.classLoaderConfiguration)
        } catch (e: OutOfMemoryError) {
            throw SandboxStartFailed("Out of memory while transforming bytecode", e)
        }
        return execute(sandboxedClassLoader, executionArguments, callable)
    }

    private const val MAX_THREAD_SHUTDOWN_RETRIES = 256
    private const val THREAD_SHUTDOWN_DELAY = 20L
    private const val MAX_COROUTINE_SHUTDOWN_RETRIES = 3

    private data class ExecutorResult<T>(val taskResults: TaskResults<T>?, val executionException: Throwable)
    private class Executor<T>(
        val callable: SandboxCallableArguments<T>,
        val sandboxedClassLoader: SandboxedClassLoader,
        val executionArguments: ExecutionArguments,
        val resultChannel: Channel<ExecutorResult<T>>
    ) : Callable<Any> {
        private data class TaskResult<T>(val returned: T, val threw: Throwable? = null, val timeout: Boolean = false)

        @Suppress("ComplexMethod", "ReturnCount")
        override fun call() {
            @Suppress("TooGenericExceptionCaught")
            try {
                val confinedTask = confine(callable, sandboxedClassLoader, executionArguments)
                val executionStarted = Instant.now()
                val taskResult = try {
                    confinedTask.thread.start()
                    TaskResult(confinedTask.task.get(executionArguments.timeout, TimeUnit.MILLISECONDS))
                } catch (e: TimeoutException) {
                    confinedTask.thread.interrupt()
                    val (returnValue, threw) = try {
                        Pair(
                            confinedTask.task.get(executionArguments.returnTimeout.toLong(), TimeUnit.MILLISECONDS),
                            null
                        )
                    } catch (e: TimeoutException) {
                        confinedTask.task.cancel(true)
                        Pair(null, null)
                    } catch (e: Throwable) {
                        confinedTask.task.cancel(true)
                        Pair(null, e)
                    }
                    TaskResult(returnValue, threw, true)
                } catch (e: Throwable) {
                    TaskResult(null, e.cause ?: e)
                }

                fun threadGroupActive(): Boolean {
                    val threads = Array<Thread?>(confinedTask.threadGroup.activeCount() * 2) { null }
                    confinedTask.threadGroup.enumerate(threads)
                    return threads.filterNotNull().any {
                        it.state !in setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING)
                    }
                }

                val coroutinesUsed = sandboxedClassLoader.loadedClasses.any { it.startsWith("kotlinx.coroutines.") }
                fun anyActiveCoroutines(): Boolean {
                    try {
                        if (!coroutinesUsed) return false
                        val defaultExecutorName = "kotlinx.coroutines.DefaultExecutor"
                        val defaultExecutorClass = sandboxedClassLoader.loadClass(defaultExecutorName)
                        if (!sandboxedClassLoader.isClassReloaded(defaultExecutorClass)) return false // Shenanigans
                        val defaultExecutor = defaultExecutorClass.kotlin.objectInstance
                        val emptyProp = defaultExecutorClass.kotlin.memberProperties
                            .first { it.name == "isEmpty" }.also { it.isAccessible = true } as KProperty<*>
                        return emptyProp.getter.call(defaultExecutor) == false
                    } catch (e: Exception) {
                        return false
                    }
                }

                fun workPending(): Boolean {
                    if (threadGroupActive() || anyActiveCoroutines()) return true
                    if (coroutinesUsed) {
                        /*
                         * Our checks might happen right in the time between a coroutine continuation being taken off
                         * the queue and actually getting started running, in which case we would miss it in both
                         * places, shutting down the thread pool before it had a chance to run. Check a few times to
                         * increase the chance of noticing it.
                         */
                        repeat(MAX_COROUTINE_SHUTDOWN_RETRIES) {
                            Thread.yield()
                            if (threadGroupActive() || anyActiveCoroutines()) return true
                        }
                    }
                    return false
                }
                if (executionArguments.waitForShutdown) {
                    while (Instant.now().isBefore(executionStarted.plusMillis(executionArguments.timeout)) &&
                        workPending()
                    ) {
                        // Give non-main tasks like coroutines a chance to finish
                        Thread.yield()
                    }
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

        var currentRedirectedLines: MutableMap<TaskResults.OutputLine.Console, CurrentLine>? = null
        val redirectedOutputLines: MutableMap<TaskResults.OutputLine.Console, StringBuilder> = mutableMapOf(
            TaskResults.OutputLine.Console.STDOUT to StringBuilder(),
            TaskResults.OutputLine.Console.STDERR to StringBuilder()
        )
        var redirectingOutput: Boolean = false

        val permissionRequests: MutableList<TaskResults.PermissionRequest> = mutableListOf()

        val started: Instant = Instant.now()

        private val isolatedLocksSyncRoot = Object()
        private val isolatedLocks = mutableMapOf<Any, ReentrantLock>()
        private val isolatedConditions = mutableMapOf<Any, Condition>()

        data class CurrentLine(
            var started: Instant = Instant.now(),
            val bytes: MutableList<Byte> = mutableListOf(),
            val startedThread: Long = Thread.currentThread().id
        ) {
            override fun toString() = bytes.toByteArray().decodeToString()
        }

        private class IdentityHolder(val item: Any) {
            override fun equals(other: Any?): Boolean {
                return other is IdentityHolder && other.item === item
            }

            override fun hashCode(): Int {
                return System.identityHashCode(item)
            }
        }

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
            val currentRedirectingLine = currentRedirectedLines?.getOrPut(console, { CurrentLine() })
            when (int.toChar()) {
                '\n' -> {
                    if (outputLines.size < maxOutputLines) {
                        outputLines.add(
                            TaskResults.OutputLine(
                                console,
                                currentLine.toString(),
                                currentLine.started,
                                currentLine.startedThread
                            )
                        )
                    } else {
                        truncatedLines += 1
                    }
                    currentLines.remove(console)

                    if (redirectingOutput && currentRedirectingLine!!.bytes.size > 0) {
                        redirectedOutputLines[console]?.append(currentRedirectingLine.toString() + "\n")
                        currentRedirectedLines?.remove(console)
                    }
                }
                '\r' -> {
                    // Ignore - results will contain Unix line endings only
                }
                else -> {
                    if (truncatedLines == 0) {
                        currentLine.bytes.add(int.toByte())
                    }
                    currentRedirectingLine?.bytes?.add(int.toByte())
                }
            }
        }

        fun getIsolatedLock(monitor: Any): ReentrantLock {
            synchronized(isolatedLocksSyncRoot) {
                return isolatedLocks.getOrPut(IdentityHolder(monitor)) {
                    ReentrantLock()
                }
            }
        }

        fun getIsolatedCondition(monitor: Any): Condition {
            synchronized(isolatedLocksSyncRoot) {
                return isolatedConditions.getOrPut(IdentityHolder(monitor)) {
                    getIsolatedLock(monitor).newCondition()
                }
            }
        }
    }

    private val confinedTasks: MutableMap<ThreadGroup, ConfinedTask<*>> = mutableMapOf()
    private val confinedClassLoaders: MutableSet<SandboxedClassLoader> = mutableSetOf()

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
        require(!confinedClassLoaders.contains(sandboxedClassLoader)) {
            "Duplicate class loader for confined task"
        }
        require(!confinedTasks.containsKey(threadGroup)) {
            "Duplicate thread group in confined tasks map"
        }
        threadGroup.maxPriority = Thread.MIN_PRIORITY
        val task = FutureTask(SandboxedCallable<T>(callable, sandboxedClassLoader))
        val thread = Thread(threadGroup, task, "main")
        val confinedTask = ConfinedTask(sandboxedClassLoader, task, thread, executionArguments)
        confinedTasks[threadGroup] = confinedTask
        confinedClassLoaders.add(sandboxedClassLoader)
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

        @Suppress("FoldInitializerAndIfToElvisOperator")
        if (threadGroupShutdownRetries == null) {
            throw SandboxContainmentFailure("failed to shut down thread group ($threadGroup)")
        }

        threadGroup.destroy()
        assert(threadGroup.isDestroyed)

        if (confinedTask.truncatedLines == 0) {
            for (console in TaskResults.OutputLine.Console.values()) {
                val currentLine = confinedTask.currentLines[console] ?: continue
                if (currentLine.bytes.isNotEmpty()) {
                    confinedTask.outputLines.add(
                        TaskResults.OutputLine(
                            console,
                            currentLine.toString(),
                            currentLine.started,
                            currentLine.startedThread
                        )
                    )
                }
            }
        }

        confinedTasks.remove(threadGroup)
        confinedClassLoaders.remove(confinedTask.classLoader)
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
        override val loadedClasses: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

        private val reloadedClasses: MutableMap<String, Class<*>> = mutableMapOf()
        private val reloader = TrustedReloader()

        @Suppress("MemberVisibilityCanBePrivate")
        val knownClasses: Map<String, ByteArray>

        init {
            knownClasses = sandboxableClassLoader.bytecodeForClasses.mapValues { (_, unsafeByteArray) ->
                RewriteBytecode.rewrite(unsafeByteArray, unsafeExceptionClasses)
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

        private val isWhiteList = classLoaderConfiguration.isWhiteList ?: whitelistedClasses.isNotEmpty()

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
                        throwException = false
                    )
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
            if (name in ClassLoaderConfiguration.COROUTINE_REQUIRED_CLASSES) {
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

        internal fun isClassReloaded(clazz: Class<*>): Boolean {
            return reloadedClasses[clazz.name] == clazz
        }

        companion object {
            private val ALWAYS_ALLOWED_CLASS_NAMES =
                setOf(RewriteBytecode::class.java.name, InvocationTargetException::class.java.name)
            private val reloadedBytecodeCache = mutableMapOf<String, ByteArray>()
        }

        internal inner class TrustedReloader {
            fun reload(name: String): Class<*> {
                val classBytes = reloadedBytecodeCache.getOrPut(name) {
                    val originalBytes = sandboxableClassLoader.classLoader.parent
                        .getResourceAsStream("${name.replace('.', '/')}.class")?.readAllBytes()
                        ?: throw ClassNotFoundException("failed to reload $name")
                    RewriteBytecode.rewrite(originalBytes, unsafeExceptionClasses)
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

    @Suppress("TooManyFunctions")
    object RewriteBytecode {
        val rewriterClassName =
            classNameToPath(RewriteBytecode::class.java.name ?: error("should have a class name"))
        private val checkMethodName = RewriteBytecode::checkException.javaMethod?.name
            ?: error("should have a method name")
        private val checkMethodDescription = Type.getMethodDescriptor(RewriteBytecode::checkException.javaMethod)
            ?: error("should be able to retrieve method signature")
        private val syncNotifyMethods = mapOf(
            "wait:()V" to RewriteBytecode::conditionWait,
            "wait:(J)V" to RewriteBytecode::conditionWaitMs,
            "wait:(JI)V" to RewriteBytecode::conditionWaitMsNs,
            "notify:()V" to RewriteBytecode::conditionNotify,
            "notifyAll:()V" to RewriteBytecode::conditionNotifyAll
        )
        private const val NS_PER_MS = 1000000L
        private const val MAX_CLASS_FILE_SIZE = 1000000
        private const val SYNC_WRAPPER_STACK_ITEMS = 2

        @JvmStatic
        fun checkException(throwable: Throwable) {
            val confinedTask = confinedTaskByThreadGroup() ?: error("only confined tasks should call this method")
            if (confinedTask.shuttingDown) {
                throw SandboxDeath()
            }
            // This check is required because of how we handle finally blocks
            if (confinedTask.classLoader.unsafeExceptionClasses.any { it.isAssignableFrom(throwable.javaClass) }) {
                throw throwable
            }
        }

        @JvmStatic
        fun enterMonitor(monitor: Any) {
            val confinedTask = confinedTaskByThreadGroup() ?: error("only confined tasks should call this method")
            confinedTask.getIsolatedLock(monitor).lockInterruptibly()
        }

        @JvmStatic
        fun exitMonitor(monitor: Any) {
            val confinedTask = confinedTaskByThreadGroup() ?: error("only confined tasks should call this method")
            confinedTask.getIsolatedLock(monitor).unlock()
        }

        @JvmStatic
        fun conditionWait(monitor: Any) {
            val confinedTask = confinedTaskByThreadGroup() ?: error("only confined tasks should call this method")
            confinedTask.getIsolatedCondition(monitor).await()
        }

        @JvmStatic
        fun conditionWaitMs(monitor: Any, timeout: Long) {
            require(timeout >= 0) { "timeout cannot be negative" }
            val confinedTask = confinedTaskByThreadGroup() ?: error("only confined tasks should call this method")
            confinedTask.getIsolatedCondition(monitor).await(timeout, TimeUnit.MILLISECONDS)
        }

        @JvmStatic
        fun conditionWaitMsNs(monitor: Any, timeout: Long, plusNanos: Int) {
            require(plusNanos >= 0) { "nanos cannot be negative" }
            require(plusNanos < NS_PER_MS) { "nanos cannot specify another full ms" }
            val confinedTask = confinedTaskByThreadGroup() ?: error("only confined tasks should call this method")
            confinedTask.getIsolatedCondition(monitor).await(timeout * NS_PER_MS + plusNanos, TimeUnit.NANOSECONDS)
        }

        @JvmStatic
        fun conditionNotify(monitor: Any) {
            val confinedTask = confinedTaskByThreadGroup() ?: error("only confined tasks should call this method")
            confinedTask.getIsolatedCondition(monitor).signal()
        }

        @JvmStatic
        fun conditionNotifyAll(monitor: Any) {
            val confinedTask = confinedTaskByThreadGroup() ?: error("only confined tasks should call this method")
            confinedTask.getIsolatedCondition(monitor).signalAll()
        }

        fun rewrite(originalByteArray: ByteArray, unsafeExceptionClasses: Set<Class<*>>): ByteArray {
            require(originalByteArray.size <= MAX_CLASS_FILE_SIZE) { "bytecode is over 1 MB" }
            val classReader = ClassReader(originalByteArray)
            val allPreinspections = preInspectMethods(classReader)
            val classWriter = ClassWriter(classReader, 0)
            var className: String? = null
            val sandboxingVisitor = object : ClassVisitor(Opcodes.ASM8, classWriter) {
                override fun visit(
                    version: Int,
                    access: Int,
                    name: String,
                    signature: String?,
                    superName: String?,
                    interfaces: Array<out String>?
                ) {
                    super.visit(version, access, name, signature, superName, interfaces)
                    className = name
                }

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
                        val preinspection = allPreinspections[VisitedMethod(name, descriptor)]
                            ?: error("missing pre-inspection for $name:$descriptor")
                        val transformedMethodName = if (Modifier.isSynchronized(access)) {
                            val transformedNameOfOriginal = "$name\$syncbody"
                            val nonSynchronizedModifiers = access and Modifier.SYNCHRONIZED.inv()
                            emitSynchronizedBridge(
                                super.visitMethod(nonSynchronizedModifiers, name, descriptor, signature, exceptions),
                                className ?: error("should have visited the class"),
                                transformedNameOfOriginal,
                                preinspection.parameters,
                                access,
                                descriptor
                            )
                            transformedNameOfOriginal
                        } else {
                            name
                        }
                        val transformedModifiers = if (Modifier.isSynchronized(access)) {
                            (
                                access
                                    and Modifier.PUBLIC.inv()
                                    and Modifier.PROTECTED.inv()
                                    and Modifier.SYNCHRONIZED.inv()
                                ) or Modifier.PRIVATE
                        } else {
                            access
                        }
                        SandboxingMethodVisitor(
                            unsafeExceptionClasses,
                            preinspection.badTryCatchBlockPositions,
                            super.visitMethod(
                                transformedModifiers,
                                transformedMethodName,
                                descriptor,
                                signature,
                                exceptions
                            )
                        )
                    }
                }
            }
            classReader.accept(sandboxingVisitor, 0)
            return classWriter.toByteArray()
        }

        @Suppress("LongParameterList", "ComplexMethod")
        private fun emitSynchronizedBridge(
            template: MethodVisitor,
            className: String,
            bridgeTo: String,
            parameters: List<VisitedParameter>,
            modifiers: Int,
            descriptor: String
        ) {
            val methodVisitor = MonitorIsolatingMethodVisitor(template)
            fun loadSelf() {
                if (Modifier.isStatic(modifiers)) {
                    methodVisitor.visitLdcInsn(Type.getType("L$className;")) // the class object
                } else {
                    methodVisitor.visitVarInsn(Opcodes.ALOAD, 0) // this
                }
            }
            parameters.forEach { methodVisitor.visitParameter(it.name, it.modifiers) }
            methodVisitor.visitCode()
            val callStartLabel = Label()
            val callEndLabel = Label()
            val finallyLabel = Label()
            methodVisitor.visitTryCatchBlock(callStartLabel, callEndLabel, finallyLabel, null) // try-finally
            loadSelf()
            methodVisitor.visitInsn(Opcodes.MONITORENTER) // will be transformed by MonitorIsolatingMethodVisitor
            var localIndex = 0
            if (!Modifier.isStatic(modifiers)) {
                loadSelf()
                localIndex++
            }
            Type.getArgumentTypes(descriptor).forEach {
                methodVisitor.visitVarInsn(it.getOpcode(Opcodes.ILOAD), localIndex)
                localIndex += it.size
            }
            methodVisitor.visitLabel(callStartLabel)
            methodVisitor.visitMethodInsn(
                if (Modifier.isStatic(modifiers)) Opcodes.INVOKESTATIC else Opcodes.INVOKESPECIAL,
                className,
                bridgeTo,
                descriptor,
                false
            )
            methodVisitor.visitLabel(callEndLabel)
            loadSelf()
            methodVisitor.visitInsn(Opcodes.MONITOREXIT)
            methodVisitor.visitInsn(Type.getReturnType(descriptor).getOpcode(Opcodes.IRETURN))
            methodVisitor.visitLabel(finallyLabel)
            val onlyThrowableOnStack = arrayOf<Any>(classNameToPath(Throwable::class.java.name))
            methodVisitor.visitFrame(Opcodes.F_SAME1, 0, emptyArray(), 1, onlyThrowableOnStack)
            loadSelf()
            methodVisitor.visitInsn(Opcodes.MONITOREXIT)
            methodVisitor.visitInsn(Opcodes.ATHROW)
            val returnSize = Type.getReturnType(descriptor).size
            methodVisitor.visitMaxs(localIndex + returnSize + SYNC_WRAPPER_STACK_ITEMS, localIndex)
            methodVisitor.visitEnd()
        }

        private open class MonitorIsolatingMethodVisitor(
            downstream: MethodVisitor
        ) : MethodVisitor(Opcodes.ASM8, downstream) {
            override fun visitInsn(opcode: Int) {
                when (opcode) {
                    Opcodes.MONITORENTER -> {
                        super.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            rewriterClassName,
                            RewriteBytecode::enterMonitor.javaMethod?.name ?: error("missing enter-monitor name"),
                            Type.getMethodDescriptor(RewriteBytecode::enterMonitor.javaMethod),
                            false
                        )
                    }
                    Opcodes.MONITOREXIT -> {
                        super.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            rewriterClassName,
                            RewriteBytecode::exitMonitor.javaMethod?.name ?: error("missing exit-monitor name"),
                            Type.getMethodDescriptor(RewriteBytecode::exitMonitor.javaMethod),
                            false
                        )
                    }
                    else -> super.visitInsn(opcode)
                }
            }
        }

        private class SandboxingMethodVisitor(
            val unsafeExceptionClasses: Set<Class<*>>,
            val badTryCatchBlockPositions: Set<Int>,
            methodVisitor: MethodVisitor
        ) : MonitorIsolatingMethodVisitor(methodVisitor) {
            private val labelsToRewrite: MutableSet<Label> = mutableSetOf()
            private var rewroteLabel = false
            private var currentTryCatchBlockPosition = -1

            override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
                currentTryCatchBlockPosition++
                if (currentTryCatchBlockPosition in badTryCatchBlockPositions) {
                    /*
                     * For unclear reasons, the Java compiler sometimes emits exception table entries that catch any
                     * exception and transfer control to the inside of the same block. This produces an infinite loop
                     * if an exception is thrown, e.g. by our checkException function. Since any exception during
                     * non-sandboxed execution would also cause this infinite loop, the table entry must not serve any
                     * purpose. Drop it to avoid the infinite loop.
                     */
                    return
                }
                val exceptionClass = type?.let {
                    try {
                        Class.forName(pathToClassName(type))
                    } catch (_: ClassNotFoundException) {
                        null
                    }
                }
                if (exceptionClass == null) {
                    labelsToRewrite.add(handler)
                } else {
                    if (unsafeExceptionClasses.any {
                        exceptionClass.isAssignableFrom(it) || it.isAssignableFrom(exceptionClass)
                    }
                    ) {
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
                        rewriterClassName,
                        checkMethodName,
                        checkMethodDescription,
                        false
                    )
                    rewroteLabel = true
                    labelsToRewrite.remove(nextLabel ?: error("nextLabel changed"))
                    nextLabel = null
                }
            }

            override fun visitMethodInsn(
                opcode: Int,
                owner: String,
                name: String,
                descriptor: String,
                isInterface: Boolean
            ) {
                val rewriteTarget = if (!isInterface && opcode == Opcodes.INVOKEVIRTUAL &&
                    owner == classNameToPath(Object::class.java.name)
                ) {
                    syncNotifyMethods["$name:$descriptor"]
                } else {
                    null
                }
                if (rewriteTarget == null) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                } else {
                    super.visitMethodInsn(
                        Opcodes.INVOKESTATIC,
                        rewriterClassName,
                        rewriteTarget.javaMethod?.name ?: error("missing notification method name"),
                        Type.getMethodDescriptor(rewriteTarget.javaMethod),
                        false
                    )
                }
            }

            override fun visitMaxs(maxStack: Int, maxLocals: Int) {
                // The DUP instruction for checkException calls makes the stack one item taller
                super.visitMaxs(maxStack + 1, maxLocals)
            }

            override fun visitEnd() {
                assert(labelsToRewrite.isEmpty()) { "failed to write all flagged labels" }
                super.visitEnd()
            }
        }

        private data class VisitedMethod(val name: String, val descriptor: String)
        private data class VisitedParameter(val name: String?, val modifiers: Int)
        private data class MethodPreinspection(
            val badTryCatchBlockPositions: Set<Int>,
            val parameters: List<VisitedParameter>
        )

        private fun preInspectMethods(reader: ClassReader): Map<VisitedMethod, MethodPreinspection> {
            /*
             * ASM doesn't provide a way to get the bytecode positions of a try-catch block's labels while the code
             * is being visited, so we have to go through all the methods to figure out the positions of the labels
             * with respect to each other to determine which try-catch blocks are bad (i.e. will loop forever) before
             * doing the real visit in SandboxingMethodVisitor.
             */
            val methodVisitors = mutableMapOf<VisitedMethod, PreviewingMethodVisitor>()
            reader.accept(
                object : ClassVisitor(Opcodes.ASM8) {
                    override fun visitMethod(
                        access: Int,
                        name: String,
                        descriptor: String,
                        signature: String?,
                        exceptions: Array<out String>?
                    ): MethodVisitor {
                        return PreviewingMethodVisitor()
                            .also { methodVisitors[VisitedMethod(name, descriptor)] = it }
                    }
                },
                0
            )
            return methodVisitors.mapValues {
                MethodPreinspection(it.value.getBadTryCatchBlockPositions(), it.value.getParameters())
            }
        }

        private class PreviewingMethodVisitor : MethodVisitor(Opcodes.ASM8) {

            private val labelPositions = mutableMapOf<Label, Int>()
            private val tryCatchBlocks = mutableListOf<Triple<Label, Label, Label>>()
            private val parameters = mutableListOf<VisitedParameter>()

            override fun visitLabel(label: Label) {
                super.visitLabel(label)
                labelPositions[label] = labelPositions.size
            }

            override fun visitTryCatchBlock(start: Label, end: Label, handler: Label, type: String?) {
                super.visitTryCatchBlock(start, end, handler, type)
                tryCatchBlocks.add(Triple(start, end, handler))
            }

            override fun visitParameter(name: String?, access: Int) {
                super.visitParameter(name, access)
                parameters.add(VisitedParameter(name, access))
            }

            fun getBadTryCatchBlockPositions(): Set<Int> {
                // Called after this visitor has accepted the entire method, so all positioning information is ready
                val badPositions = mutableSetOf<Int>()
                tryCatchBlocks.forEachIndexed { i, (startLabel, endLabel, handlerLabel) ->
                    val startPos = labelPositions[startLabel] ?: error("start $startLabel not visited")
                    val endPos = labelPositions[endLabel] ?: error("end $endLabel not visited")
                    val handlerPos = labelPositions[handlerLabel] ?: error("handler $handlerLabel not visited")
                    if (handlerPos in startPos until endPos) badPositions.add(i)
                }
                return badPositions
            }

            fun getParameters(): List<VisitedParameter> {
                return parameters
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
                if (confinedTask.shuttingDown) throw SandboxDeath()
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
                throw SandboxDeath()
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

    private class SandboxedProperties(properties: Properties) : Properties(properties) {
        @Suppress("ReturnCount")
        override fun getProperty(key: String?): String? {
            val confinedTask = confinedTaskByThreadGroup() ?: return super.getProperty(key)
            if (key == "kotlinx.coroutines.scheduler.max.pool.size") {
                return confinedTask.maxExtraThreads.toString()
            } else if (key == "kotlinx.coroutines.scheduler.core.pool.size") {
                return (confinedTask.maxExtraThreads - 1).toString()
            }
            return super.getProperty(key)
        }
    }

    @JvmStatic
    fun redirectOutput(block: () -> Any?): JeedOutputCapture {
        val confinedTask = confinedTaskByThreadGroup() ?: check { "should only be used from a confined task" }
        check(!confinedTask.redirectingOutput) { "can't nest calls to redirectOutput" }

        confinedTask.redirectingOutput = true
        confinedTask.currentRedirectedLines = mutableMapOf()
        @Suppress("TooGenericExceptionCaught")
        val result = try {
            Pair(block(), null)
        } catch (e: Throwable) {
            Pair(null, e)
        }
        confinedTask.redirectingOutput = false

        val flushedStdout =
            confinedTask.currentRedirectedLines!![TaskResults.OutputLine.Console.STDOUT]?.toString() ?: ""
        val flushedStderr =
            confinedTask.currentRedirectedLines!![TaskResults.OutputLine.Console.STDERR]?.toString() ?: ""
        confinedTask.currentRedirectedLines = null

        return JeedOutputCapture(
            result.first,
            result.second,
            confinedTask.redirectedOutputLines[TaskResults.OutputLine.Console.STDOUT].toString() + flushedStdout,
            confinedTask.redirectedOutputLines[TaskResults.OutputLine.Console.STDERR].toString() + flushedStderr
        ).also {
            confinedTask.redirectedOutputLines[TaskResults.OutputLine.Console.STDOUT] = StringBuilder()
            confinedTask.redirectedOutputLines[TaskResults.OutputLine.Console.STDERR] = StringBuilder()
        }
    }

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
                val confinedTask = confinedTaskByThreadGroup() ?: return (
                    originalPrintStreams[console]
                        ?: error("original console should exist")
                    )
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

    // Save a bit of time by not filling in the stack trace
    private class SandboxDeath : ThreadDeath() {
        override fun fillInStackTrace() = this
    }

    class SandboxStartFailed(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
    class SandboxContainmentFailure(message: String) : Throwable(message)

    private lateinit var originalStdout: PrintStream
    private lateinit var originalStderr: PrintStream

    private var originalSecurityManager: SecurityManager? = null
    private lateinit var originalProperties: Properties

    @Suppress("TooGenericExceptionCaught")
    private val MAX_THREAD_POOL_SIZE = try {
        System.getenv("JEED_MAX_THREAD_POOL_SIZE").toInt()
    } catch (e: Exception) {
        Runtime.getRuntime().availableProcessors()
    }

    private lateinit var threadPool: ExecutorService

    @Suppress("MemberVisibilityCanBePrivate")
    var autoStart = true
    var running = false

    private lateinit var originalPrintStreams: Map<TaskResults.OutputLine.Console, PrintStream>

    @JvmStatic
    @Synchronized
    fun start(size: Int = min(Runtime.getRuntime().availableProcessors(), MAX_THREAD_POOL_SIZE)) {
        if (running) {
            return
        }
        originalStdout = System.out
        originalStderr = System.err

        System.setOut(RedirectingPrintStream(TaskResults.OutputLine.Console.STDOUT))
        System.setErr(RedirectingPrintStream(TaskResults.OutputLine.Console.STDERR))
        // Try to silence ThreadDeath error messages. Not sure this works but it can't hurt.
        // Thread.setDefaultUncaughtExceptionHandler { _, _ -> }

        threadPool = Executors.newFixedThreadPool(size)

        originalSecurityManager = System.getSecurityManager()
        System.setSecurityManager(SandboxSecurityManager)
        originalProperties = System.getProperties()
        System.setProperties(SandboxedProperties(System.getProperties()))

        originalPrintStreams = mapOf(
            TaskResults.OutputLine.Console.STDOUT to originalStdout,
            TaskResults.OutputLine.Console.STDERR to originalStderr
        )

        running = true
    }

    @JvmStatic
    @Synchronized
    fun stop(timeout: Long = 10) {
        if (!running) {
            return
        }
        threadPool.shutdown()
        if (!threadPool.awaitTermination(timeout / 2, TimeUnit.SECONDS)) {
            threadPool.shutdownNow()
            require(threadPool.awaitTermination(timeout / 2, TimeUnit.SECONDS))
        }

        System.setOut(originalStdout)
        System.setErr(originalStderr)

        System.setSecurityManager(originalSecurityManager)
        System.setProperties(originalProperties)

        running = false
    }
}

@Suppress("UNUSED")
fun <T> withSandbox(run: () -> T): T {
    try {
        Sandbox.start()
        return run()
    } finally {
        Sandbox.stop()
    }
}

fun Sandbox.SandboxableClassLoader.sandbox(
    classLoaderConfiguration: Sandbox.ClassLoaderConfiguration
): Sandbox.SandboxedClassLoader {
    return Sandbox.SandboxedClassLoader(this, classLoaderConfiguration)
}

data class JeedOutputCapture(val returned: Any?, val threw: Throwable?, val stdout: String, val stderr: String)
