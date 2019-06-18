package edu.illinois.cs.cs125.jeed.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.io.FilePermission
import java.io.OutputStream
import java.io.PrintStream
import java.lang.StringBuilder
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ReflectPermission
import java.security.*
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.*
import kotlin.random.Random

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

open class ExecutionArguments(
        val timeout: Long = DEFAULT_TIMEOUT,
        val permissions: List<Permission> = DEFAULT_PERMISSIONS,
        val maxExtraThreads: Int = DEFAULT_MAX_EXTRA_THREADS
) {
    companion object {
        const val DEFAULT_TIMEOUT = 100L
        val DEFAULT_PERMISSIONS = listOf(
                RuntimePermission("accessDeclaredMembers"),
                ReflectPermission("suppressAccessChecks")
        )
        const val DEFAULT_MAX_EXTRA_THREADS = 0
    }
}

class SourceExecutionArguments(
        val klass: String = DEFAULT_KLASS,
        val method: String = DEFAULT_METHOD,
        timeout: Long = DEFAULT_TIMEOUT,
        permissions: List<Permission> = DEFAULT_PERMISSIONS,
        maxExtraThreads: Int = DEFAULT_MAX_EXTRA_THREADS
): ExecutionArguments(timeout, permissions, maxExtraThreads) {
    companion object {
        const val DEFAULT_KLASS = "Main"
        const val DEFAULT_METHOD = "main()"
    }
}

class ExecutionResult<T>(
        private val taskResult: TaskResult<T>,
        val outputLines: List<OutputLine> = listOf(),
        val permissionRequests: List<PermissionRequest> = listOf(),
        val totalInterval: Interval,
        val executionInterval: Interval,
        val threadShutdownRetries: Int
) {
    data class TaskResult<T>(val returned: T, val threw: Throwable? = null, val timeout: Boolean = false)
    data class OutputLine (val console: Console, val line: String, val timestamp: Instant, val thread: Long) {
        enum class Console(val fd: Int) { STDOUT(1), STDERR(2) }
    }
    data class PermissionRequest(val permission: Permission, val granted: Boolean)

    val returned: Any?
        get() { return taskResult.returned }
    val threw: Throwable?
        get() { return taskResult.threw }
    val timeout: Boolean
        get() { return taskResult.timeout }
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
        get() { return Duration.between(totalInterval.start, totalInterval.end) }
}


class MethodNotFoundException(message: String) : Exception(message)
class SandboxConfigurationException(message: String) : Exception(message)

@Throws(ClassNotFoundException::class, MethodNotFoundException:: class, SandboxConfigurationException::class)
suspend fun CompilationResult.execute(
        executionArguments: SourceExecutionArguments = SourceExecutionArguments()
): ExecutionResult<out Any?> {

    val method = classLoader.loadClass(executionArguments.klass).declaredMethods.find { method ->
    if (!Modifier.isStatic(method.modifiers)
            || !Modifier.isPublic(method.modifiers)
            || method.parameterTypes.isNotEmpty()) {
        return@find false
    }
    method.getQualifiedName() == executionArguments.method
    } ?: throw MethodNotFoundException(
            "Cannot locate public static no-argument method ${executionArguments.method} in ${executionArguments.klass}"
    )

    return JeedExecutor.execute(FutureTask<Any> { method.invoke(null) }, executionArguments)
}

suspend fun <T> FutureTask<T>.execute(executionArguments: ExecutionArguments): ExecutionResult<out T?> {
    return JeedExecutor.execute(this, executionArguments)
}

class JeedExecutor<T>(
        val executionArguments: ExecutionArguments,
        val futureTask: FutureTask<T>
) : Callable<Any> {
    private data class ExecutorResult<T>(val returned: ExecutionResult<out T?>?, val threw: Throwable?)

    private val resultChannel = Channel<ExecutorResult<T>>()

    override fun call() {
        try {
            val started = Instant.now()

            val threadGroup = ThreadGroup("execute")
            val thread = Thread(threadGroup, futureTask)

            val key = Sandbox.confine(threadGroup, executionArguments.permissions, executionArguments.maxExtraThreads)
            OutputInterceptor.intercept(threadGroup)

            val executionStarted = Instant.now()
            val taskResult = try {
                thread.start()
                ExecutionResult.TaskResult(futureTask.get(executionArguments.timeout, TimeUnit.MILLISECONDS))
            } catch (e: TimeoutException) {
                futureTask.cancel(true)
                @Suppress("DEPRECATION")
                thread.stop()
                ExecutionResult.TaskResult(null, null, true)
            } catch (e: Throwable) {
                ExecutionResult.TaskResult(null, e)
            }
            val executionEnded = Instant.now()

            // Kill off any remaining threads.
            Sandbox.shutdown(key, threadGroup)
            assert(threadGroup.activeGroupCount() == 0)
            val threadShutdownRetries = if (threadGroup.activeCount() == 0) {
                0
            } else {
                (0..MAX_THREAD_SHUTDOWN_RETRIES).find {
                    val threadGroupThreads = Array<Thread?>(threadGroup.activeCount()) { null }
                    threadGroup.enumerate(threadGroupThreads, true)
                    val runningThreads = threadGroupThreads.toList().filterNotNull()
                    if (runningThreads.isEmpty()) {
                        return@find true
                    }

                    for (runningThread in runningThreads) {
                        if (!runningThread.isInterrupted) {
                            try {
                                runningThread.interrupt()
                            } catch (e: Throwable) {
                            }
                        }
                        @Suppress("DEPRECATION")
                        try {
                            runningThread.stop()
                        } catch (e: Throwable) {
                        }
                    }
                    // The delay here may need some tuning on certain platforms. Too fast and the threads we are trying
                    // to kill don't have time to get stuck. Too slow and it takes forever.
                    Thread.sleep(threadShutdownDelay)
                    return@find false
                } ?: error("couldn't shut down thread group after $MAX_THREAD_SHUTDOWN_RETRIES retries")
            }
            threadGroup.destroy()
            assert(threadGroup.isDestroyed)

            val permissionRequests = Sandbox.release(key, threadGroup)
            val outputLines = OutputInterceptor.release(threadGroup)

            val executionResult = ExecutionResult(
                    taskResult,
                    outputLines,
                    permissionRequests,
                    Interval(started, Instant.now()),
                    Interval(executionStarted, executionEnded),
                    threadShutdownRetries
            )
            runBlocking { resultChannel.send(ExecutorResult(executionResult, null)) }
        } catch (e: Throwable) {
            runBlocking { resultChannel.send(ExecutorResult(null, e)) }
        } finally {
            resultChannel.close()
        }
    }

    companion object {
        const val MAX_THREAD_SHUTDOWN_RETRIES = 10240
        const val DEFAULT_THREAD_SHUTDOWN_DELAY = 10L
        var threadShutdownDelay = DEFAULT_THREAD_SHUTDOWN_DELAY
        var threadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
                ?: error("thread pool should be available")

        suspend fun <T> execute(futureTask: FutureTask<T>, executionArguments: ExecutionArguments): ExecutionResult<out T?> {
            return coroutineScope {
                val sourceExecutor = JeedExecutor(executionArguments, futureTask)
                threadPool.submit(sourceExecutor)
                val result = sourceExecutor.resultChannel.receive()
                result.returned ?: throw result.threw ?: error("incorrect executor result")
            }
        }
    }

    private object OutputInterceptor {
        init {
            System.setOut(PrintStream(object : OutputStream() {
                override fun write(int: Int) {
                    write(int, ExecutionResult.OutputLine.Console.STDOUT)
                }
            }))
            System.setErr(PrintStream(object : OutputStream() {
                override fun write(int: Int) {
                    write(int, ExecutionResult.OutputLine.Console.STDERR)
                }
            }))
        }

        private data class CurrentLine(
                var started: Instant = Instant.now(),
                val line: StringBuilder = StringBuilder(),
                val startedThread: Long = Thread.currentThread().id
        )

        private data class ThreadGroupConsoleOutput(
                val lines: MutableList<ExecutionResult.OutputLine> = mutableListOf(),
                val currentLines: MutableMap<ExecutionResult.OutputLine.Console, CurrentLine> = mutableMapOf()
        )

        private val originalStdout = System.out ?: error("System.out should exist")
        private val originalStderr = System.err ?: error("System.err should exist")
        private val confinedThreadGroups: MutableMap<ThreadGroup, ThreadGroupConsoleOutput> =
                Collections.synchronizedMap(WeakHashMap<ThreadGroup, ThreadGroupConsoleOutput>())

        @Synchronized
        fun intercept(threadGroup: ThreadGroup) {
            check(!confinedThreadGroups.containsKey(threadGroup)) { "thread group is already intercepted" }
            confinedThreadGroups[threadGroup] = ThreadGroupConsoleOutput()
        }

        @Synchronized
        fun release(threadGroup: ThreadGroup): List<ExecutionResult.OutputLine> {
            val confinedThreadGroupConsoleOutput =
                    confinedThreadGroups.remove(threadGroup) ?: error("thread group is not intercepted")

            for (console in ExecutionResult.OutputLine.Console.values()) {
                val currentLine = confinedThreadGroupConsoleOutput.currentLines[console] ?: continue
                if (currentLine.line.isNotEmpty()) {
                    confinedThreadGroupConsoleOutput.lines.add(
                            ExecutionResult.OutputLine(
                                    console,
                                    currentLine.line.toString(),
                                    currentLine.started,
                                    currentLine.startedThread
                            )
                    )
                }
            }

            return confinedThreadGroupConsoleOutput.lines.toList()
        }

        @Synchronized
        fun write(int: Int, console: ExecutionResult.OutputLine.Console) {
            val confinedThreadGroupConsoleOutput = confinedThreadGroups[Thread.currentThread().threadGroup]
                    ?: return defaultWrite(int, console)

            val currentLine = confinedThreadGroupConsoleOutput.currentLines.getOrPut(console, { CurrentLine() })
            when (val char = int.toChar()) {
                '\n' -> {
                    confinedThreadGroupConsoleOutput.lines.add(
                            ExecutionResult.OutputLine(console, currentLine.line.toString(), currentLine.started, currentLine.startedThread)
                    )
                    confinedThreadGroupConsoleOutput.currentLines.remove(console)
                }
                else -> {
                    currentLine.line.append(char)
                }
            }
        }

        fun defaultWrite(int: Int, console: ExecutionResult.OutputLine.Console) {
            when (console) {
                ExecutionResult.OutputLine.Console.STDOUT -> originalStdout.write(int)
                ExecutionResult.OutputLine.Console.STDERR -> originalStderr.write(int)
            }
        }
    }

    private object Sandbox : SecurityManager() {
        private val systemSecurityManager: SecurityManager? = System.getSecurityManager()
        val blacklistedPermissions = listOf(
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
                // These are particularly important to prevent untrusted code from escaping the sandbox which is based on thread groups
                RuntimePermission("modifyThread"),
                RuntimePermission("modifyThreadGroup")
        )

        private data class ConfinedThreadGroup(
                val key: Long,
                val accessControlContext: AccessControlContext,
                val maxExtraThreadCount: Int,
                val loggedRequests: MutableList<ExecutionResult.PermissionRequest> = mutableListOf(),
                var shuttingDown: Boolean = false
        )
        private val confinedThreadGroups: MutableMap<ThreadGroup, ConfinedThreadGroup> =
                Collections.synchronizedMap(WeakHashMap<ThreadGroup, ConfinedThreadGroup>())

        fun sleepForever() {
            while (true) { Thread.sleep(Long.MAX_VALUE) }
        }
        private var inReadCheck = false
        override fun checkRead(file: String) {
            if (inReadCheck) {
                return
            }
            try {
                inReadCheck = true
                val confinedThreadGroup = confinedThreadGroups[Thread.currentThread().threadGroup] ?: return
                if (confinedThreadGroup.shuttingDown) {
                    sleepForever()
                }
                if (!file.endsWith(".class")) {
                    confinedThreadGroup.loggedRequests.add(ExecutionResult.PermissionRequest(FilePermission(file, "read"), false))
                    throw SecurityException()
                }
            } finally {
                inReadCheck = false
            }
        }
        override fun getThreadGroup(): ThreadGroup {
            val confinedThreadGroup = confinedThreadGroups[Thread.currentThread().threadGroup] ?: return super.getThreadGroup()
            if (confinedThreadGroup.shuttingDown) {
                sleepForever()
            }
            if (Thread.currentThread().threadGroup.activeCount() >= confinedThreadGroup.maxExtraThreadCount + 1) {
                throw SecurityException()
            } else {
                return super.getThreadGroup()
            }
        }
        private var inPermissionCheck = false
        override fun checkPermission(permission: Permission) {
            if (inPermissionCheck) {
                return
            }
            val confinedThreadGroup = confinedThreadGroups[Thread.currentThread().threadGroup] ?: return
            try {
                inPermissionCheck = true
                if (confinedThreadGroup.shuttingDown) {
                    sleepForever()
                }
                systemSecurityManager?.checkPermission(permission)
                confinedThreadGroup.accessControlContext.checkPermission(permission)
                confinedThreadGroup.loggedRequests.add(ExecutionResult.PermissionRequest(permission, true))
            } catch (e: SecurityException) {
                confinedThreadGroup.loggedRequests.add(ExecutionResult.PermissionRequest(permission, false))
                throw e
            } finally {
                inPermissionCheck = false
            }
        }

        @Synchronized
        fun confine(threadGroup: ThreadGroup, permissionList: List<Permission>, maxExtraThreadCount: Int = 0): Long {
            check(!confinedThreadGroups.containsKey(threadGroup)) { "thread group is already confined" }
            permissionList.intersect(blacklistedPermissions).isEmpty() || throw SandboxConfigurationException("attempt to allow unsafe permissions")

            val permissions = Permissions()
            permissionList.forEach { permissions.add(it) }

            val key = Random.nextLong()
            confinedThreadGroups[threadGroup] = ConfinedThreadGroup(
                    key,
                    AccessControlContext(arrayOf(ProtectionDomain(null, permissions))),
                    maxExtraThreadCount
            )
            if (confinedThreadGroups.keys.size == 1) {
                System.setSecurityManager(this)
            }
            return key
        }
        @Synchronized
        fun shutdown(key: Long, threadGroup: ThreadGroup) {
            val confinedThreadGroup = confinedThreadGroups[threadGroup] ?: error("thread group is not confined")
            check(key == confinedThreadGroup.key) { "invalid key" }
            confinedThreadGroup.shuttingDown = true
        }
        @Synchronized
        fun release(key: Long, threadGroup: ThreadGroup): List<ExecutionResult.PermissionRequest> {
            val confinedThreadGroup = confinedThreadGroups.remove(threadGroup) ?: error("thread group is not confined")
            check(key == confinedThreadGroup.key) { "invalid key" }
            if (confinedThreadGroups.keys.isEmpty()) {
                System.setSecurityManager(systemSecurityManager)
            }
            return confinedThreadGroup.loggedRequests
        }
    }
}
fun Method.getQualifiedName(): String { return "$name(${parameters.joinToString(separator = ", ")})" }
