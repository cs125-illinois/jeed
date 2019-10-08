package edu.illinois.cs.cs125.jeed.server

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.mongodb.client.MongoCollection
import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import edu.illinois.cs.cs125.jeed.core.*
import edu.illinois.cs.cs125.jeed.core.moshi.PermissionAdapter
import edu.illinois.cs.cs125.jeed.server.moshi.Adapters
import edu.illinois.cs.cs125.jeed.core.moshi.Adapters as JeedAdapters
import kotlinx.coroutines.*
import org.apache.http.auth.AuthenticationException
import org.bson.BsonDocument
import java.lang.IllegalArgumentException
import java.time.Instant

class Job(
        val source: Map<String, String>?,
        val templates: Map<String, String>?,
        val snippet: String?,
        passedTasks: Set<Task>,
        arguments: TaskArguments?,
        val authToken: String?,
        val label: String,
        val waitForSave: Boolean = false
) {
    val tasks: Set<Task>
    val arguments = arguments ?: TaskArguments()

    var email: String? = null

    init {
        require(!(source != null && snippet != null)) { "can't create task with both sources and snippet" }
        if (templates != null) {
            require(source != null) { "can't use both templates and snippet mode" }
        }

        val tasksToRun = passedTasks.toMutableSet()
        if (tasksToRun.contains(Task.execute)) {
            tasksToRun.add(Task.compile)
        }
        if (snippet != null) {
            tasksToRun.add(Task.snippet)
        }
        if (templates != null) {
            tasksToRun.add(Task.template)
        }
        tasks = tasksToRun.toSet()

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
                    "job is requesting unallowed permissions"
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

    fun authenticate() {
        try {
            if (googleTokenVerifier != null && authToken != null) {
                googleTokenVerifier?.verify(authToken)?.let {
                    if (configuration[Auth.Google.hostedDomain] != "") {
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

    suspend fun run(): Result {
        currentStatus.counts.submittedJobs++

        val started = Instant.now()

        val result = Result(this)
        try {
            val actualSource = if (source != null) {
                if (templates == null) {
                    Source(source)
                } else {
                    Source.fromTemplates(source, templates).also { result.completed.template = it }
                }
            } else {
                result.completed.snippet = Source.transformSnippet(snippet ?: assert { "should have a snippet" }, arguments.snippet)
                result.completed.snippet
            } ?: check { "should have a source" }

            result.completed.compilation = actualSource.compile(arguments.compilation)

            if (tasks.contains(Task.checkstyle)) {
                result.completed.checkstyle = actualSource.checkstyle(arguments.checkstyle)
            }

            if (tasks.contains(Task.execute)) {
                result.completed.execution = result.completed.compilation?.execute(arguments.execution)
                if (result.completed.execution?.threw != null) {
                    result.failed.execution = ExecutionFailed(result.completed.execution!!.threw!!)
                }
            }
        } catch (templatingFailed: TemplatingFailed) {
            result.failed.template = templatingFailed
        } catch (snippetFailed: SnippetTransformationFailed) {
            result.failed.snippet = snippetFailed
        } catch (compilationFailed: CompilationFailed) {
            result.failed.compilation = compilationFailed
        } catch (checkstyleFailed: CheckstyleFailed) {
            result.failed.checkstyle = checkstyleFailed
        } catch (executionFailed: ExecutionFailed) {
            result.failed.execution = executionFailed
        } catch (e: Exception) {
            logger.error(e.toString())
            e.printStackTrace()
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

    class JobJson(
            val sources: List<FlatSource>?,
            val templates: List<FlatSource>?,
            val snippet: String?,
            val tasks: Set<Task>,
            val arguments: TaskArguments?,
            val authToken: String?,
            val label: String,
            val waitForSave: Boolean
    )
    class JobAdapter {
        @FromJson
        fun jobFromJson(jobJson: JobJson): Job {
            assert(!(jobJson.sources != null && jobJson.snippet != null)) { "can't set both snippet and sources" }
            assert(jobJson.sources != null || jobJson.snippet != null) { "must set either sources or snippet" }
            return Job(jobJson.sources?.toSource(), jobJson.templates?.toSource(), jobJson.snippet, jobJson.tasks, jobJson.arguments, jobJson.authToken, jobJson.label, jobJson.waitForSave)
        }
        @ToJson
        fun jobToJson(job: Job): JobJson {
            assert(!(job.source != null && job.snippet != null)) { "can't set both snippet and sources" }
            return JobJson(job.source?.toFlatSources(), job.templates?.toFlatSources(), job.snippet, job.tasks, job.arguments, null, job.label, job.waitForSave)
        }
    }
    companion object {
        var mongoCollection: MongoCollection<BsonDocument>? = null
        var googleTokenVerifier: GoogleIdTokenVerifier? = null
    }
}

@Suppress("EnumEntryName")
enum class Task(val task: String) {
    template("template"),
    snippet("snippet"),
    compile("compile"),
    checkstyle("checkstyle"),
    execute("execute")
}
class TaskArguments(
        val snippet: SnippetArguments = SnippetArguments(),
        val compilation: CompilationArguments = CompilationArguments(),
        val checkstyle: CheckstyleArguments = CheckstyleArguments(),
        val execution: SourceExecutionArguments = SourceExecutionArguments()
)

class Result(val job: Job) {
    val email = job.email
    val status = currentStatus
    val completed: CompletedTasks = CompletedTasks()
    val failed: FailedTasks = FailedTasks()
    lateinit var interval: Interval

    val json: String
        get() = resultAdapter.toJson(this)

    data class ResultJson(
            val email: String?,
            val job: Job,
            val status: Status,
            val completed: CompletedTasks,
            val failed: FailedTasks,
            val interval: Interval
    )
    class ResultAdapter {
        @Throws(Exception::class)
        @Suppress("UNUSED_PARAMETER")
        @FromJson
        fun resultFromJson(resultJson: ResultJson): Result {
            val result = Result(resultJson.job)

            result.completed.snippet = resultJson.completed.snippet
            result.completed.compilation = resultJson.completed.compilation
            result.completed.template = resultJson.completed.template
            result.completed.execution = resultJson.completed.execution

            result.failed.snippet = resultJson.failed.snippet
            result.failed.compilation = resultJson.failed.compilation
            result.failed.template = resultJson.failed.template
            result.failed.execution = resultJson.failed.execution

            result.interval = resultJson.interval

            return result
        }
        @ToJson
        fun resultToJson(result: Result): ResultJson {
            return ResultJson(result.email, result.job, result.status, result.completed, result.failed, result.interval)
        }
    }

    companion object {
        val resultAdapter: JsonAdapter<Result> = Moshi.Builder().let { builder ->
            Adapters.forEach { builder.add(it) }
            JeedAdapters.forEach { builder.add(it) }
            builder.build().adapter(Result::class.java)
        }
    }
}
class CompletedTasks(
        var snippet: Snippet? = null,
        var template: TemplatedSource? = null,
        var compilation: CompiledSource? = null,
        var checkstyle: CheckstyleResults? = null,
        var execution: Sandbox.TaskResults<out Any?>? = null
)
class FailedTasks(
        var template: TemplatingFailed? = null,
        var snippet: SnippetTransformationFailed? = null,
        var compilation: CompilationFailed? = null,
        var checkstyle: CheckstyleFailed? = null,
        var execution: ExecutionFailed? = null
)

data class FlatSource(val path: String, val contents: String)
fun List<FlatSource>.toSource(): Map<String, String> {
    require(this.map { it.path }.distinct().size == this.size) { "duplicate paths in source list" }
    return this.map { it.path to it.contents }.toMap()
}
fun Map<String, String>.toFlatSources(): List<FlatSource> {
    return this.map { FlatSource(it.key, it.value) }
}
