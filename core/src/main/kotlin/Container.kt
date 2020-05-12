@file:Suppress("MatchingDeclarationName")

package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private val CONTAINER_TMP_DIR = System.getenv("JEED_CONTAINER_TMP_DIR")

@Suppress("TooGenericExceptionCaught")
private val MAX_CONCURRENT_CONTAINERS = try {
    System.getenv("JEED_MAX_CONCURRENT_CONTAINERS").toInt()
} catch (e: Exception) {
    Runtime.getRuntime().availableProcessors()
}
private val containerSemaphore = Semaphore(MAX_CONCURRENT_CONTAINERS)

@JsonClass(generateAdapter = true)
data class ContainerExecutionArguments(
    var klass: String? = null,
    val method: String = SourceExecutionArguments.DEFAULT_METHOD,
    val image: String = DEFAULT_IMAGE,
    val tmpDir: String? = CONTAINER_TMP_DIR,
    val timeout: Long = DEFAULT_TIMEOUT,
    val maxOutputLines: Int = Sandbox.ExecutionArguments.DEFAULT_MAX_OUTPUT_LINES,
    val containerArguments: String = """--network="none""""
) {
    companion object {
        const val DEFAULT_IMAGE = "cs125/jeed-containerrunner:latest"
        const val DEFAULT_TIMEOUT = 1000L
    }
}

private const val CONTAINER_SHUTDOWN_DELAY = 100L

@JsonClass(generateAdapter = true)
data class ContainerExecutionResults(
    val exitCode: Int?,
    val timeout: Boolean,
    val outputLines: List<Sandbox.TaskResults.OutputLine>,
    val interval: Interval,
    val executionInterval: Interval,
    val truncatedLines: Int
) {
    val completed: Boolean
        get() {
            return exitCode != null && exitCode == 0 && !timeout
        }
    val output: String
        get() {
            return outputLines.sortedBy { it.timestamp }.joinToString(separator = "\n") { it.line }
        }
}

@Suppress("LongMethod")
suspend fun CompiledSource.run(
    executionArguments: ContainerExecutionArguments = ContainerExecutionArguments()
): ContainerExecutionResults {
    val started = Instant.now()

    if (executionArguments.klass == null) {
        executionArguments.klass = when (this.source.type) {
            Source.FileType.JAVA -> "Main"
            Source.FileType.KOTLIN -> "MainKt"
        }
    }

    // Check that we can load the class before we start the container
    // Note that this check is different than what is used to load the method by the actual containerrunner,
    // but should be similar enough
    classLoader.findClassMethod(executionArguments.klass!!, executionArguments.method)

    val tempRoot = when {
        executionArguments.tmpDir != null -> File(executionArguments.tmpDir)
        CONTAINER_TMP_DIR != null -> File(CONTAINER_TMP_DIR)
        else -> null
    }

    return withTempDir(tempRoot) { tempDir ->
        eject(tempDir)

        val containerMethodName = executionArguments.method.split("(")[0]

        val dockerName = UUID.randomUUID().toString()
        val actualCommand = "docker run " +
            "--name $dockerName " +
            "-v $tempDir:/jeed/ " +
            executionArguments.containerArguments +
            " --rm ${executionArguments.image} " +
            "-- run ${executionArguments.klass!!} $containerMethodName"
        @Suppress("SpreadOperator")
        val processBuilder = ProcessBuilder(*listOf("/bin/sh", "-c", actualCommand).toTypedArray()).directory(tempDir)

        containerSemaphore.withPermit {
            val executionStarted = Instant.now()
            val process = processBuilder.start()
            val stdoutLines = StreamGobbler(
                Sandbox.TaskResults.OutputLine.Console.STDOUT,
                process.inputStream,
                executionArguments.maxOutputLines
            )
            val stderrLines = StreamGobbler(
                Sandbox.TaskResults.OutputLine.Console.STDERR,
                process.errorStream,
                executionArguments.maxOutputLines
            )
            val stderrThread = Thread(stdoutLines)
            val stdoutThread = Thread(stderrLines)
            stderrThread.start()
            stdoutThread.start()

            val timeout = !process.waitFor(executionArguments.timeout, TimeUnit.MILLISECONDS)
            if (timeout) {
                val dockerStopCommand = """docker kill ${"$"}(docker ps -q --filter="name=$dockerName")"""
                Runtime.getRuntime().exec(listOf("/bin/sh", "-c", dockerStopCommand).toTypedArray()).waitFor()
                process.waitFor(CONTAINER_SHUTDOWN_DELAY, TimeUnit.MILLISECONDS)
            }
            check(!process.isAlive) { "Docker container is still running" }
            val executionEnded = Instant.now()

            stderrThread.join()
            stdoutThread.join()

            var truncatedLines = stdoutLines.truncatedLines + stderrLines.truncatedLines
            val outputLines = listOf(stdoutLines.commandOutputLines, stderrLines.commandOutputLines)
                .flatten()
                .sortedBy { it.timestamp }
                .also {
                    if (it.size > executionArguments.maxOutputLines) {
                        truncatedLines += it.size - executionArguments.maxOutputLines
                    }
                }
                .take(executionArguments.maxOutputLines)

            ContainerExecutionResults(
                process.exitValue(),
                timeout,
                outputLines,
                Interval(started, Instant.now()),
                Interval(executionStarted, executionEnded),
                truncatedLines
            )
        }
    }
}

class StreamGobbler(
    private val console: Sandbox.TaskResults.OutputLine.Console,
    private val inputStream: InputStream,
    private val maxOutputLines: Int
) : Runnable {
    val commandOutputLines: MutableList<Sandbox.TaskResults.OutputLine> = mutableListOf()
    var truncatedLines = 0

    override fun run() {
        BufferedReader(InputStreamReader(inputStream)).lines().forEach { line ->
            if (line != null) {
                if (commandOutputLines.size < maxOutputLines) {
                    commandOutputLines.add(Sandbox.TaskResults.OutputLine(console, line, Instant.now()))
                } else {
                    truncatedLines++
                }
            }
        }
    }
}

suspend fun <T> withTempDir(root: File? = null, f: suspend (directory: File) -> T): T {
    val directory = createTempDir("containerrunner", null, root)
    return try {
        f(directory)
    } finally {
        check(directory.deleteRecursively())
    }
}

fun File.isInside(directory: File): Boolean = normalize().startsWith(directory.normalize())

fun CompiledSource.eject(directory: File) {
    require(directory.isDirectory) { "Must eject into a directory" }
    require(directory.exists()) { "Directory to eject into must exist" }
    require(directory.listFiles()?.isEmpty() ?: false) { "Directory to eject into must be empty" }

    fileManager.allClassFiles.forEach { (path, fileObject) ->
        val destination = File(directory, path)
        check(!destination.exists()) { "Duplicate file found during ejection: $path" }
        check(destination.isInside(directory)) { "Attempt to write file outside of destination directory" }
        destination.parentFile.mkdirs()
        destination.writeBytes(fileObject.openInputStream().readAllBytes())
        check(destination.exists()) { "File not written during ejection" }
        check(destination.length() > 0) { "Empty file written during ejection" }
    }
}
