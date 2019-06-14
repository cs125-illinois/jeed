package edu.illinois.cs.cs125.jeed.core

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import java.io.OutputStream
import java.io.PrintStream
import java.lang.StringBuilder
import java.lang.reflect.Modifier
import java.security.Permission
import java.security.Permissions
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.concurrent.*

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

fun List<Permission>.toPermission(): Permissions {
    val permissions = Permissions()
    this.forEach { permissions.add(it) }
    return permissions
}
data class ExecutionArguments(
        val className: String = "Main",
        val method: String = "main()",
        val timeout: Long = 100L,
        val permissions: List<Permission> = listOf(),
        val captureOutput: Boolean = true,
        val maxExtraThreadCount: Int = 0,
        val ignoredPermissions: List<Permission> = listOf(RuntimePermission("modifyThreadGroup"))
)
class ExecutionResult(
        val arguments: ExecutionArguments,
        val error: Throwable? = null,
        val outputLines: List<OutputLine> = listOf(),
        val permissionRequests: List<PermissionRequest> = mutableListOf(),
        val totalInterval: Interval,
        val executionInterval: Interval
) {
    val completed: Boolean
        get() { return error == null }
    val timedOut: Boolean
        get() { return error is TimeoutException }
    val permissionDenied: Boolean
        get() {
            return permissionRequests.filter {
                !arguments.ignoredPermissions.contains(it.permission)
            }.any {
                !it.granted
            }
        }
    val stdoutLines: List<OutputLine>
        get() {
            return outputLines.filter { it.console == Console.STDOUT }
        }
    val stderrLines: List<OutputLine>
        get() {
            return outputLines.filter { it.console == Console.STDERR }
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
            return Duration.between(totalInterval.start, totalInterval.end)
        }
}

class ExecutionException(message: String) : Exception(message)

val threadPool = Executors.newFixedThreadPool(8) ?: error("thread pool should be available")

class SourceExecutor(
        val executionArguments: ExecutionArguments,
        val classLoader: ClassLoader,
        val resultChannel: Channel<Any>
) : Callable<Any> {
    override fun call() {
        try {
            val started = Instant.now()

            val klass = classLoader.loadClass(executionArguments.className)
                    ?: throw ExecutionException("Could not load ${executionArguments.className}")
            val method = klass.declaredMethods.find { method ->
                val fullName = method.name + method.parameterTypes.joinToString(prefix = "(", separator = ", ", postfix = ")") { parameter ->
                    parameter.name
                }
                fullName == executionArguments.method && Modifier.isStatic(method.modifiers) && Modifier.isPublic(method.modifiers)
            }
                    ?: throw ExecutionException("Cannot locate public static method with signature ${executionArguments.method} in ${executionArguments.className}")

            // We need to load this before we begin execution since the code won't be able to once it's in the sandbox
            classLoader.loadClass(OutputLine::class.qualifiedName)

            val futureTask = FutureTask {
                method.invoke(null)
            }
            val threadGroup = ThreadGroup("execute")
            val thread = Thread(threadGroup, futureTask)
            val key = Sandbox.confine(threadGroup, executionArguments.permissions.toPermission(), executionArguments.maxExtraThreadCount)

            if (executionArguments.captureOutput) {
                OutputInterceptor.intercept(threadGroup)
            }

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
            Sandbox.shutdown(key, threadGroup)

            assert(threadGroup.activeGroupCount() == 0)
            val activeThreadCount = threadGroup.activeCount()
            if (activeThreadCount > 0) {
                while (true) {
                    val threadGroupThreads = Array<Thread?>(activeThreadCount) { null }
                    threadGroup.enumerate(threadGroupThreads, true)
                    val runningThreads = threadGroupThreads.toList().filterNotNull()
                    if (runningThreads.isEmpty()) {
                        break
                    }
                    for (runningThread in runningThreads) {
                        if (!runningThread.isInterrupted) {
                            try { runningThread.interrupt() } catch (e: Throwable) { }
                        }
                        @Suppress("DEPRECATION")
                        try { runningThread.stop() } catch (e: Throwable) { }
                    }
                    Thread.sleep(10L)
                }
            }
            threadGroup.destroy()
            assert(threadGroup.isDestroyed)

            val permissionRequests = Sandbox.release(key, threadGroup)

            val outputLines = if (executionArguments.captureOutput) {
                OutputInterceptor.release(threadGroup)
            } else {
                emptyList()
            }

            val executionResult = ExecutionResult(
                    executionArguments,
                    error,
                    outputLines,
                    permissionRequests,
                    Interval(started, Instant.now()),
                    Interval(executionStarted, executionEnded)
            )
            runBlocking {
                resultChannel.send(executionResult)
            }
        } catch (e: Throwable) {
            runBlocking {
                resultChannel.send(e)
            }
        } finally {
            resultChannel.close()
        }

    }
}

suspend fun CompiledSource.execute(
        executionArguments: ExecutionArguments = ExecutionArguments()
): ExecutionResult {
    return coroutineScope {
        val resultChannel = Channel<Any>()
        val sourceExecutor = SourceExecutor(executionArguments, classLoader, resultChannel)
        threadPool.submit(sourceExecutor)
        when (val result = resultChannel.receive()) {
            is ExecutionResult -> result
            is Throwable -> throw(result)
            else -> error("received unexpected type on result channel")
        }
    }
}

enum class Console(val fd: Int) { STDOUT(1), STDERR(2) }
class OutputLine (
        val console: Console,
        val line: String,
        val timestamp: Instant,
        val startedThread: Long
)
object OutputInterceptor {
    private data class CurrentLine(
            var started: Instant = Instant.now(),
            val line: StringBuilder = StringBuilder(),
            val startedThread: Long = Thread.currentThread().id
    )
    private data class ThreadGroupConsoleOutput(
            val lines: MutableList<OutputLine> = mutableListOf(),
            val currentLines: MutableMap<Console, CurrentLine> = mutableMapOf()
    )
    val originalStdout = System.out ?: error("System.out should exist")
    val originalStderr = System.err ?: error("System.err should exist")
    private val confinedThreadGroups: MutableMap<ThreadGroup, ThreadGroupConsoleOutput> =
            Collections.synchronizedMap(WeakHashMap<ThreadGroup, ThreadGroupConsoleOutput>())

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

    fun defaultWrite(int: Int, console: Console) {
        when (console) {
            Console.STDOUT -> originalStdout.write(int)
            Console.STDERR -> originalStderr.write(int)
        }
    }
    @Synchronized
    fun write(int: Int, console: Console) {
        val confinedGroupConsoleOutput = confinedThreadGroups[Thread.currentThread().threadGroup] ?: return defaultWrite(int, console)

        val char = int.toChar()
        val currentLine = confinedGroupConsoleOutput.currentLines.getOrPut(console, { CurrentLine() })

        if (char == '\n') {
            confinedGroupConsoleOutput.lines.add(OutputLine(console, currentLine.line.toString(), currentLine.started, currentLine.startedThread))
            confinedGroupConsoleOutput.currentLines.remove(console)
        } else {
            currentLine.line.append(char)
        }
    }
    init {
        System.setOut(PrintStream(object : OutputStream() {
            override fun write(int: Int) {
                write(int, Console.STDOUT)
            }
        }))
        System.setErr(PrintStream(object : OutputStream() {
            override fun write(int: Int) {
                write(int, Console.STDERR)
            }
        }))
    }
}
