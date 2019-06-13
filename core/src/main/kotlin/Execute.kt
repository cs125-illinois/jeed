package edu.illinois.cs.cs125.jeed.core

import mu.KotlinLogging
import java.io.OutputStream
import java.io.PrintStream
import java.lang.StringBuilder
import java.lang.reflect.Modifier
import java.security.Permission
import java.security.Permissions
import java.time.Duration
import java.time.Instant
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
        val completed: Boolean,
        val timedOut: Boolean,
        val failed: Boolean,
        val error: Throwable? = null,
        val permissionDenied: Boolean,
        val stdoutLines: List<OutputLine> = mutableListOf(),
        val stderrLines: List<OutputLine> = mutableListOf(),
        val permissionRequests: List<PermissionRequest> = mutableListOf()
        ) {
    fun stdout(): String {
        return stdoutLines.joinToString(separator = "\n") { it.line }
    }
    fun stderr(): String {
        return stderrLines.joinToString(separator = "\n") { it.line }
    }
    fun output(): String {
        return stdoutLines.union(stderrLines)
                .sortedBy { it.timestamp }
                .joinToString(separator = "\n") { it.line }
    }
}

class ExecutionException(message: String) : Exception(message)

fun CompiledSource.execute(
        executionArguments: ExecutionArguments = ExecutionArguments()
): ExecutionResult {
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

    var timedOut = false
    val permissionDenied: Boolean
    val permissionRequests: List<PermissionRequest>

    if (executionArguments.captureOutput) {
        OutputInterceptor.intercept(threadGroup)
    }

    var error: Throwable? = null
    var key: Long? = null
    var stdoutLines: List<OutputLine> = listOf()
    var stderrLines: List<OutputLine> = listOf()

    val completed = try {
        key = Sandbox.confine(classLoader, executionArguments.permissions.toPermission(), executionArguments.maxExtraThreadCount)
        thread.start()
        futureTask.get(executionArguments.timeout, TimeUnit.MILLISECONDS)
        true
    } catch (e: TimeoutException) {
        futureTask.cancel(true)
        @Suppress("DEPRECATION")
        thread.stop()
        timedOut = true
        false
    } catch (e: Throwable) {
        error = e
        false
    } finally {
        permissionRequests = Sandbox.release(key, classLoader)
        permissionDenied = permissionRequests.filter {
            !executionArguments.ignoredPermissions.contains(it.permission)
        }.any {
            !it.granted
        }

        if (executionArguments.captureOutput) {
            val output = OutputInterceptor.release(threadGroup)
            stdoutLines = output[Console.STDOUT] ?: error("output should have STDOUT")
            stderrLines = output[Console.STDERR] ?: error("output should have STDERR")
        }

        val activeThreadCount = threadGroup.activeCount()
        assert(activeThreadCount <= executionArguments.maxExtraThreadCount + 1)
        assert(threadGroup.activeGroupCount() == 0)

        if (activeThreadCount > 0) {
            for (unused in 0..32) {
                val threadGroupThreads = Array<Thread?>(activeThreadCount) { null }
                threadGroup.enumerate(threadGroupThreads, true)
                val runningThreads = threadGroupThreads.toList().filter { it != null }
                if (runningThreads.isEmpty()) {
                    break
                }
                for (runningThread in runningThreads) {
                    @Suppress("DEPRECATION")
                    runningThread?.stop()
                }
                Thread.sleep(100L)
            }
        }
        threadGroup.destroy()
        assert(threadGroup.isDestroyed)
    }
    return ExecutionResult(
            completed = completed && error == null && !permissionDenied,
            timedOut = timedOut,
            failed = error == null,
            error = error,
            permissionDenied = permissionDenied,
            permissionRequests = permissionRequests,
            stdoutLines = stdoutLines,
            stderrLines = stderrLines
    )
}

data class OutputLine (
        val line: String,
        val timestamp: Instant,
        val delta: Duration
)
private class ConsoleOutputStream : OutputStream() {
    val started: Instant = Instant.now()
    val lines = mutableListOf<OutputLine>()

    var currentLineStarted: Instant = Instant.now()
    var currentLine = StringBuilder()

    override fun write(int: Int) {
        val char = int.toChar()
        if (char == '\n') {
            lines.add(OutputLine(
                    currentLine.toString(),
                    currentLineStarted,
                    Duration.between(started, currentLineStarted
                    )))
            currentLine = StringBuilder()
            currentLineStarted = Instant.now()
        } else {
            currentLine.append(char)
        }
    }
}
data class ThreadGroupConsoleOutput(
        val started: Instant = Instant.now(),
        val lines: MutableList<OutputLine> = mutableListOf(),
        var currentLineStarted: Instant? = null,
        var currentLine: StringBuilder = StringBuilder()
)

enum class Console(val fd: Int) {
    STDIN(0), STDOUT(1), STDERR(2)
}

object OutputInterceptor {
    val originalStdout = System.out ?: error("System.out should exist")
    val originalStderr = System.err ?: error("System.err should exist")
    val confinedThreadGroups: MutableMap<ThreadGroup, Map<Console, ThreadGroupConsoleOutput>> = mutableMapOf()

    @Synchronized
    fun intercept(threadGroup: ThreadGroup) {
        check(!confinedThreadGroups.containsKey(threadGroup))
        confinedThreadGroups[threadGroup] = mapOf(Console.STDOUT to ThreadGroupConsoleOutput(), Console.STDERR to ThreadGroupConsoleOutput())
    }
    @Synchronized
    fun release(threadGroup: ThreadGroup): Map<Console, List<OutputLine>> {
        val confinedThreadGroupConsoleOutput =
                confinedThreadGroups.remove(threadGroup) ?: error("should contain this ThreadGroup")
        return mapOf(
                Console.STDOUT to confinedThreadGroupConsoleOutput[Console.STDOUT]?.lines!!.toList(),
                Console.STDERR to confinedThreadGroupConsoleOutput[Console.STDERR]?.lines!!.toList()
        )
    }

    fun defaultWrite(int: Int, console: Console) {
        when (console) {
            Console.STDOUT -> originalStdout.write(int)
            Console.STDERR -> originalStderr.write(int)
            else -> error("can't write to STDIN")
        }
    }
    @Synchronized
    fun write(int: Int, console: Console) {
        if (confinedThreadGroups.keys.isEmpty()) {
            return defaultWrite(int, console)
        }

        var currentGroup = Thread.currentThread().threadGroup

        // Optimistically check whether the current thread's group is confined before checking ancestors. This avoids
        // unnecessary permission requests (and potential denials) associated with retrieving the parent thread group.
        val confinedGroups = if (confinedThreadGroups.containsKey(currentGroup)) {
            listOf(currentGroup)
        } else {
            val threadGroups: MutableList<ThreadGroup> = mutableListOf()
            while (currentGroup != null) {
                threadGroups.add(currentGroup)
                currentGroup = try {
                    currentGroup.parent
                } catch (e: SecurityException) {
                    null
                }
            }
            confinedThreadGroups.keys.intersect(threadGroups)
        }
        if (confinedGroups.isEmpty()) {
            return defaultWrite(int, console)
        }

        assert(confinedGroups.size == 1)
        val confinedGroup = confinedGroups.first()
        val confinedGroupConsoleOutput =
                confinedThreadGroups[confinedGroup]?.get(console) ?: error("should contain this thread group")

        val char = int.toChar()
        if (confinedGroupConsoleOutput.currentLineStarted == null) {
            confinedGroupConsoleOutput.currentLineStarted = Instant.now()
        }
        if (char == '\n') {
            val lastLineStarted = confinedGroupConsoleOutput.currentLineStarted ?: Instant.now()
            confinedGroupConsoleOutput.lines.add(OutputLine(
                    confinedGroupConsoleOutput.currentLine.toString(),
                    lastLineStarted,
                    Duration.between(confinedGroupConsoleOutput.started, lastLineStarted)
            ))
            confinedGroupConsoleOutput.currentLine = StringBuilder()
            confinedGroupConsoleOutput.currentLineStarted = Instant.now()
        } else {
            confinedGroupConsoleOutput.currentLine.append(char)
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
