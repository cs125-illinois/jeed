package edu.illinois.cs.cs125.jeed.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.io.OutputStream
import java.io.PrintStream
import java.lang.StringBuilder
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.ReflectPermission
import java.security.Permission
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.*

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

data class ExecutionArguments(
        val klass: String = DEFAULT_KLASS,
        val method: String = DEFAULT_METHOD,
        val timeout: Long = DEFAULT_TIMEOUT,
        val permissions: List<Permission> = DEFAULT_PERMISSIONS,
        val maxExtraThreads: Int = DEFAULT_MAX_EXTRA_THREADS
) {
    companion object {
        const val DEFAULT_KLASS = "Main"
        const val DEFAULT_METHOD = "main()"
        const val DEFAULT_TIMEOUT = 100L
        val DEFAULT_PERMISSIONS = listOf(
                RuntimePermission("accessDeclaredMembers"),
                ReflectPermission("suppressAccessChecks")
        )
        const val DEFAULT_MAX_EXTRA_THREADS = 0
    }
}

class ExecutionResult(
        val error: Throwable? = null,
        val outputLines: List<OutputLine> = listOf(),
        val permissionRequests: List<PermissionRequest> = listOf(),
        val totalInterval: Interval,
        val executionInterval: Interval,
        val threadShutdownRetries: Int
) {
    val completed: Boolean
        get() { return error == null }
    val timedOut: Boolean
        get() { return error is TimeoutException }
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

class ExecutionError(message: String) : Exception(message)

@Throws(ExecutionError::class, SandboxConfigurationError::class)
suspend fun CompiledSource.execute(
        executionArguments: ExecutionArguments = ExecutionArguments()
): ExecutionResult {

    val klass = classLoader.loadClass(executionArguments.klass)
            ?: throw ExecutionError("Could not load ${executionArguments.klass}")
    val method = klass.declaredMethods.find { method ->
        if (!Modifier.isStatic(method.modifiers)
                || !Modifier.isPublic(method.modifiers)
                || method.parameterTypes.isNotEmpty()) {
            return@find false
        }
        method.getQualifiedName() == executionArguments.method
    } ?: throw ExecutionError(
            "Cannot locate public static no-argument method ${executionArguments.method} in ${executionArguments.klass}"
    )

    return coroutineScope {
        val resultChannel = Channel<Any>()
        val sourceExecutor = ExecutionEngine(executionArguments, method, resultChannel)
        ExecutionEngine.threadPool.submit(sourceExecutor)
        when (val result = resultChannel.receive()) {
            is ExecutionResult -> result
            is Throwable -> throw(result)
            else -> error("received unexpected type on result channel")
        }
    }
}

class ExecutionEngine(
        val executionArguments: ExecutionArguments,
        val method: Method,
        val resultChannel: Channel<Any>
) : Callable<Any> {
    override fun call() {
        try {
            val started = Instant.now()

            val futureTask = FutureTask { method.invoke(null) }
            val threadGroup = ThreadGroup("execute")
            val thread = Thread(threadGroup, futureTask)

            val key = Sandbox.confine(threadGroup, executionArguments.permissions, executionArguments.maxExtraThreads)
            OutputInterceptor.intercept(threadGroup)

            val executionStarted = Instant.now()
            val error = try {
                thread.start()
                futureTask.get(executionArguments.timeout, TimeUnit.MILLISECONDS)
                null
            } catch (e: TimeoutException) {
                futureTask.cancel(true)
                @Suppress("DEPRECATION")
                thread.stop()
                e
            } catch (e: Throwable) {
                e
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
                    if (runningThreads.isEmpty()) { return@find true }

                    for (runningThread in runningThreads) {
                        if (!runningThread.isInterrupted) {
                            try { runningThread.interrupt() } catch (e: Throwable) { }
                        }
                        @Suppress("DEPRECATION")
                        try { runningThread.stop() } catch (e: Throwable) { }
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
                    error,
                    outputLines,
                    permissionRequests,
                    Interval(started, Instant.now()),
                    Interval(executionStarted, executionEnded),
                    threadShutdownRetries
            )
            runBlocking { resultChannel.send(executionResult) }
        } catch (e: Throwable) {
            runBlocking { resultChannel.send(e) }
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
    }
}


data class OutputLine (val console: Console, val line: String, val timestamp: Instant, val thread: Long) {
    enum class Console(val fd: Int) { STDOUT(1), STDERR(2) }
}
private object OutputInterceptor {
    private data class CurrentLine(
            var started: Instant = Instant.now(),
            val line: StringBuilder = StringBuilder(),
            val startedThread: Long = Thread.currentThread().id
    )
    private data class ThreadGroupConsoleOutput(
            val lines: MutableList<OutputLine> = mutableListOf(),
            val currentLines: MutableMap<OutputLine.Console, CurrentLine> = mutableMapOf()
    )

    private val originalStdout = System.out ?: error("System.out should exist")
    private val originalStderr = System.err ?: error("System.err should exist")
    private val confinedThreadGroups: MutableMap<ThreadGroup, ThreadGroupConsoleOutput> =
            Collections.synchronizedMap(WeakHashMap<ThreadGroup, ThreadGroupConsoleOutput>())

    init {
        System.setOut(PrintStream(object : OutputStream() {
            override fun write(int: Int) {
                write(int, OutputLine.Console.STDOUT)
            }
        }))
        System.setErr(PrintStream(object : OutputStream() {
            override fun write(int: Int) {
                write(int, OutputLine.Console.STDERR)
            }
        }))
    }

    @Synchronized
    fun intercept(threadGroup: ThreadGroup) {
        check(!confinedThreadGroups.containsKey(threadGroup)) { "thread group is already intercepted" }
        confinedThreadGroups[threadGroup] = ThreadGroupConsoleOutput()
    }
    @Synchronized
    fun release(threadGroup: ThreadGroup): List<OutputLine> {
        val confinedThreadGroupConsoleOutput =
                confinedThreadGroups.remove(threadGroup) ?: error("thread group is not intercepted")
        return confinedThreadGroupConsoleOutput.lines.toList()
    }
    @Synchronized
    fun write(int: Int, console: OutputLine.Console) {
        val confinedGroupConsoleOutput = confinedThreadGroups[Thread.currentThread().threadGroup] ?: return defaultWrite(int, console)

        val currentLine = confinedGroupConsoleOutput.currentLines.getOrPut(console, { CurrentLine() })
        when (val char = int.toChar()) {
            '\n' -> {
                confinedGroupConsoleOutput.lines.add(
                        OutputLine(console, currentLine.line.toString(), currentLine.started, currentLine.startedThread)
                )
                confinedGroupConsoleOutput.currentLines.remove(console)
            }
            else -> {
                currentLine.line.append(char)
            }
        }
    }
    fun defaultWrite(int: Int, console: OutputLine.Console) {
        when (console) {
            OutputLine.Console.STDOUT -> originalStdout.write(int)
            OutputLine.Console.STDERR -> originalStderr.write(int)
        }
    }
}

fun Method.getQualifiedName(): String { return "$name(${parameters.joinToString(separator = ", ")})" }
