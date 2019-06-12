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
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
        val captureOutput: Boolean = true
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

val outputLock = ReentrantLock()

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
    } ?: throw ExecutionException("Cannot locate public static method with signature ${executionArguments.method} in ${executionArguments.className}")

    // We need to load this before we begin execution since the code won't be able to once it's in the sandbox
    classLoader.loadClass(OutputLine::class.qualifiedName)

    val futureTask = FutureTask {
        method.invoke(null)
    }
    val thread = Thread(futureTask)

    outputLock.withLock {
        var timedOut = false
        val permissionDenied: Boolean
        val permissionRequests: List<PermissionRequest>

        val stdoutStream = ConsoleOutputStream()
        val stderrStream = ConsoleOutputStream()
        System.out.flush()
        System.err.flush()
        val originalStdout = System.out
        val originalStderr = System.err

        if (executionArguments.captureOutput) {
            System.setOut(PrintStream(stdoutStream))
            System.setErr(PrintStream(stderrStream))
        }

        var error: Throwable? = null
        var key: Long? = null
        val completed = try {
            key = Sandbox.confine(classLoader, executionArguments.permissions.toPermission())
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
            permissionDenied = permissionRequests.any { !it.granted }

            if (executionArguments.captureOutput) {
                System.out.flush()
                System.err.flush()
                System.setOut(originalStdout)
                System.setErr(originalStderr)
            }
        }

        return ExecutionResult(
                completed = completed && error == null && !permissionDenied,
                timedOut = timedOut,
                failed = error == null,
                error = error,
                permissionDenied = permissionDenied,
                permissionRequests = permissionRequests,
                stdoutLines = stdoutStream.lines,
                stderrLines = stderrStream.lines
        )
    }
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
