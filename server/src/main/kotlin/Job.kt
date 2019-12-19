package edu.illinois.cs.cs125.jeed.server

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.mongodb.client.MongoCollection
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import edu.illinois.cs.cs125.jeed.core.CheckstyleArguments
import edu.illinois.cs.cs125.jeed.core.CheckstyleFailed
import edu.illinois.cs.cs125.jeed.core.CheckstyleResults
import edu.illinois.cs.cs125.jeed.core.CompilationArguments
import edu.illinois.cs.cs125.jeed.core.CompilationFailed
import edu.illinois.cs.cs125.jeed.core.ExecutionFailed
import edu.illinois.cs.cs125.jeed.core.Interval
import edu.illinois.cs.cs125.jeed.core.KompilationArguments
import edu.illinois.cs.cs125.jeed.core.Snippet
import edu.illinois.cs.cs125.jeed.core.SnippetArguments
import edu.illinois.cs.cs125.jeed.core.SnippetTransformationFailed
import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments
import edu.illinois.cs.cs125.jeed.core.TemplatingFailed
import edu.illinois.cs.cs125.jeed.core.checkstyle
import edu.illinois.cs.cs125.jeed.core.compile
import edu.illinois.cs.cs125.jeed.core.execute
import edu.illinois.cs.cs125.jeed.core.fromTemplates
import edu.illinois.cs.cs125.jeed.core.kompile
import edu.illinois.cs.cs125.jeed.core.moshi.CompiledSourceResult
import edu.illinois.cs.cs125.jeed.core.moshi.ExecutionFailedResult
import edu.illinois.cs.cs125.jeed.core.moshi.PermissionAdapter
import edu.illinois.cs.cs125.jeed.core.moshi.TaskResults
import edu.illinois.cs.cs125.jeed.core.moshi.TemplatedSourceResult
import edu.illinois.cs.cs125.jeed.core.transformSnippet
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import java.lang.IllegalArgumentException
import java.time.Instant
import org.apache.http.auth.AuthenticationException
import org.bson.BsonDocument

@Suppress("EnumEntryName", "EnumNaming")
enum class Task {
    template,
    snippet,
    compile,
    kompile,
    checkstyle,
    execute
}

@JsonClass(generateAdapter = true)
class TaskArguments(
    val snippet: SnippetArguments = SnippetArguments(),
    val compilation: CompilationArguments = CompilationArguments(),
    val kompilation: KompilationArguments = KompilationArguments(),
    val checkstyle: CheckstyleArguments = CheckstyleArguments(),
    val execution: SourceExecutionArguments = SourceExecutionArguments()
)

class Job(
    val source: Map<String, String>?,
    val templates: Map<String, String>?,
    val snippet: String?,
    passedTasks: Set<Task>,
    arguments: TaskArguments?,
    @Suppress("MemberVisibilityCanBePrivate") val authToken: String?,
    val label: String,
    val waitForSave: Boolean = false
) {
    val tasks: Set<Task>
    val arguments = arguments ?: TaskArguments()

    var email: String? = null

    init {
        val tasksToRun = passedTasks.toMutableSet()

        require(!(source != null && snippet != null)) { "can't create task with both sources and snippet" }
        if (templates != null) {
            require(source != null) { "can't use both templates and snippet mode" }
        }
        if (source != null) {
            val fileTypes = Source.filenamesToFileTypes(source.keys)
            require(fileTypes.size == 1) { "can't compile mixed Java and Kotlin sources" }
            if (tasksToRun.contains(Task.checkstyle)) {
                require(fileTypes[0] == Source.FileType.JAVA) { "can't run checkstyle on Kotlin sources" }
            }
        }

        if (tasksToRun.contains(Task.execute)) {
            require(tasksToRun.contains(Task.compile) || tasksToRun.contains(Task.kompile)) {
                "must compile code before execution"
            }
        }
        require(!(tasksToRun.containsAll(setOf(Task.compile, Task.kompile)))) {
            "can't compile code as both Java and Kotlin"
        }
        if (snippet != null) {
            tasksToRun.add(Task.snippet)
        }
        if (templates != null) {
            tasksToRun.add(Task.template)
        }
        tasks = tasksToRun.toSet()

        @Suppress("MaxLineLength")
        if (Task.execute in tasks) {
            if (arguments?.execution?.timeout != null) {
                require(arguments.execution.timeout <= configuration[Limits.Execution.timeout]) {
                    "job timeout of ${arguments.execution.timeout} too long (> ${configuration[Limits.Execution.timeout]})"
                }
            }
            if (arguments?.execution?.maxExtraThreads != null) {
                require(arguments.execution.maxExtraThreads <= configuration[Limits.Execution.maxExtraThreads]) {
                    "job maxExtraThreads of ${arguments.execution.maxExtraThreads} is too large (> ${configuration[Limits.Execution.maxExtraThreads]}"
                }
            }
            if (arguments?.execution?.maxOutputLines != null) {
                require(arguments.execution.maxOutputLines <= configuration[Limits.Execution.maxOutputLines]) {
                    "job maxOutputLines of ${arguments.execution.maxOutputLines} is too large (> ${configuration[Limits.Execution.maxOutputLines]}"
                }
            }
            if (arguments?.execution?.permissions != null) {
                val allowedPermissions = configuration[Limits.Execution.permissions].map { PermissionAdapter().permissionFromJson(it) }.toSet()
                require(allowedPermissions.containsAll(arguments.execution.permissions)) {
                    "job is requesting unavailable permissions"
                }
            }
            if (arguments?.execution?.classLoaderConfiguration != null) {
                val blacklistedClasses = configuration[Limits.Execution.ClassLoaderConfiguration.blacklistedClasses]

                require(arguments.execution.classLoaderConfiguration.blacklistedClasses.containsAll(blacklistedClasses)) {
                    "job is trying to remove blacklisted classes"
                }
                val whitelistedClasses = configuration[Limits.Execution.ClassLoaderConfiguration.whitelistedClasses]
                require(arguments.execution.classLoaderConfiguration.whitelistedClasses.containsAll(whitelistedClasses)) {
                    "job is trying to add whitelisted classes"
                }
                val unsafeExceptions = configuration[Limits.Execution.ClassLoaderConfiguration.unsafeExceptions]
                require(arguments.execution.classLoaderConfiguration.unsafeExceptions.containsAll(unsafeExceptions)) {
                    "job is trying to remove unsafe exceptions"
                }
            }
        }
    }

    @Suppress("ComplexMethod", "NestedBlockDepth")
    fun authenticate() {
        @Suppress("EmptyCatchBlock", "TooGenericExceptionCaught")
        try {
            if (googleTokenVerifier != null && authToken != null) {
                googleTokenVerifier?.verify(authToken)?.let {
                    if (configuration[Auth.Google.hostedDomain] != null) {
                        require(it.payload.hostedDomain == configuration[Auth.Google.hostedDomain])
                    }
                    email = it.payload.email
                }
            }
        } catch (e: IllegalArgumentException) {
        } catch (e: Exception) {
            logger.warn(e.toString())
        }

        if (email == null && !configuration[Auth.none]) {
            val message = if (authToken == null) {
                "authentication required by authentication token missing"
            } else {
                "authentication failure"
            }
            throw AuthenticationException(message)
        }
    }

    @Suppress("ComplexMethod", "LongMethod")
    suspend fun run(): Result {
        currentStatus.counts.submittedJobs++

        val started = Instant.now()

        val result = Result(this)
        @Suppress("TooGenericExceptionCaught")
        try {
            val actualSource = if (source != null) {
                if (templates == null) {
                    Source(source)
                } else {
                    Source.fromTemplates(source, templates).also {
                        result.completedTasks.add(Task.template)
                        result.completed.template = TemplatedSourceResult(it)
                    }
                }
            } else {
                Source.transformSnippet(snippet ?: assert { "should have a snippet" }, arguments.snippet).also {
                    result.completedTasks.add(Task.snippet)
                    result.completed.snippet = it
                }
            }

            val compiledSource = when {
                tasks.contains(Task.compile) -> {
                    actualSource.compile(arguments.compilation).also {
                        result.completed.compilation = CompiledSourceResult(it)
                        result.completedTasks.add(Task.compile)
                    }
                }
                tasks.contains(Task.kompile) -> {
                    actualSource.kompile(arguments.kompilation).also {
                        result.completed.kompilation = CompiledSourceResult(it)
                        result.completedTasks.add(Task.kompile)
                    }
                }
                else -> {
                    null
                }
            }

            if (tasks.contains(Task.checkstyle)) {
                check(actualSource.type == Source.FileType.JAVA) { "can't run checkstyle on non-Java sources" }
                result.completed.checkstyle = actualSource.checkstyle(arguments.checkstyle)
                result.completedTasks.add(Task.checkstyle)
            }

            if (tasks.contains(Task.execute)) {
                check(compiledSource != null) { "should have compiled source before executing" }
                val executionResult = compiledSource.execute(arguments.execution)
                if (executionResult.threw != null) {
                    result.failed.execution = ExecutionFailedResult(ExecutionFailed(executionResult.threw!!))
                    result.failedTasks.add(Task.execute)
                } else {
                    result.completed.execution = TaskResults(executionResult)
                    result.completedTasks.add(Task.execute)
                }
            }
        } catch (templatingFailed: TemplatingFailed) {
            result.failed.template = templatingFailed
            result.failedTasks.add(Task.template)
        } catch (snippetFailed: SnippetTransformationFailed) {
            result.failed.snippet = snippetFailed
            result.failedTasks.add(Task.snippet)
        } catch (compilationFailed: CompilationFailed) {
            if (tasks.contains(Task.compile)) {
                result.failed.compilation = compilationFailed
                result.failedTasks.add(Task.compile)
            } else if (tasks.contains(Task.kompile)) {
                result.failed.kompilation = compilationFailed
                result.failedTasks.add(Task.kompile)
            }
        } catch (checkstyleFailed: CheckstyleFailed) {
            result.failed.checkstyle = checkstyleFailed
            result.failedTasks.add(Task.checkstyle)
        } catch (executionFailed: ExecutionFailed) {
            result.failed.execution = ExecutionFailedResult(executionFailed)
            result.failedTasks.add(Task.execute)
        } finally {
            currentStatus.counts.completedJobs++
            result.interval = Interval(started, Instant.now())
            if (mongoCollection != null) {
                val resultSave = GlobalScope.async {
                    try {
                        mongoCollection?.insertOne(BsonDocument.parse(result.json))
                        currentStatus.counts.savedJobs++
                    } catch (e: Exception) {
                        logger.error("Saving job failed: $e")
                    }
                }
                if (waitForSave) {
                    resultSave.await()
                }
            }
            return result
        }
    }

    companion object {
        var mongoCollection: MongoCollection<BsonDocument>? = null
        var googleTokenVerifier: GoogleIdTokenVerifier? = null
    }
}

class Result(val job: Job) {
    val email = job.email
    val status = currentStatus

    val completedTasks: MutableSet<Task> = mutableSetOf()
    val completed: CompletedTasks = CompletedTasks()

    val failedTasks: MutableSet<Task> = mutableSetOf()
    val failed: FailedTasks = FailedTasks()

    lateinit var interval: Interval

    val json: String
        get() = resultAdapter.toJson(this)

    companion object {
        val resultAdapter: JsonAdapter<Result> = moshi.adapter(Result::class.java)
        fun from(response: String?): Result {
            check(response != null) { "can't deserialize null string" }
            return resultAdapter.fromJson(response) ?: check { "failed to deserialize result" }
        }
    }
}

@JsonClass(generateAdapter = true)
class CompletedTasks(
    var snippet: Snippet? = null,
    var template: TemplatedSourceResult? = null,
    var compilation: CompiledSourceResult? = null,
    var kompilation: CompiledSourceResult? = null,
    var checkstyle: CheckstyleResults? = null,
    var execution: TaskResults? = null
)

@JsonClass(generateAdapter = true)
class FailedTasks(
    var template: TemplatingFailed? = null,
    var snippet: SnippetTransformationFailed? = null,
    var compilation: CompilationFailed? = null,
    var kompilation: CompilationFailed? = null,
    var checkstyle: CheckstyleFailed? = null,
    var execution: ExecutionFailedResult? = null
)

@JsonClass(generateAdapter = true)
data class FlatSource(val path: String, val contents: String)

fun List<FlatSource>.toSource(): Map<String, String> {
    require(this.map { it.path }.distinct().size == this.size) { "duplicate paths in source list" }
    return this.map { it.path to it.contents }.toMap()
}

fun Map<String, String>.toFlatSources(): List<FlatSource> {
    return this.map { FlatSource(it.key, it.value) }
}
