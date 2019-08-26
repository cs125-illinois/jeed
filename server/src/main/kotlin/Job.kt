package edu.illinois.cs.cs125.jeed.server

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.mongodb.MongoClient
import com.mongodb.MongoClientURI
import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import edu.illinois.cs.cs125.jeed.core.*
import edu.illinois.cs.cs125.jeed.core.moshi.PermissionAdapter
import edu.illinois.cs.cs125.jeed.server.moshi.Adapters
import edu.illinois.cs.cs125.jeed.core.moshi.Adapters as JeedAdapters
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.apache.http.auth.AuthenticationException
import org.bson.BsonDocument
import java.lang.IllegalArgumentException
import java.time.Instant
import java.util.*

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

class Job(
        val source: Map<String, String>?,
        val templates: Map<String, String>?,
        val snippet: String?,
        passedTasks: Set<Task>,
        arguments: TaskArguments?,
        val authToken: String?,
        val label: String
) {
    val tasks: Set<Task>
    val arguments = arguments ?: TaskArguments()

    var email: String? = null

    init {
        require(!(source != null && snippet != null)) { "can't create task with both source and snippet" }
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
                val idToken = googleTokenVerifier.verify(authToken)
                if (idToken != null) {
                    if (configuration[Auth.Google.hostedDomain] != "") {
                        require(idToken.payload.hostedDomain == configuration[Auth.Google.hostedDomain])
                    }
                    email = idToken.payload.email
                }
            }
        } catch (e: IllegalArgumentException) { }

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
                    result.completed.template = Source.fromTemplates(source, templates)
                    result.completed.template
                }
            } else {
                result.completed.snippet = Source.transformSnippet(snippet ?: assert { "should have a snippet" })
                result.completed.snippet
            } ?: check { "should have a source" }

            result.completed.compilation = actualSource.compile(arguments.compilation)

            if (tasks.contains(Task.execute)) {
                result.completed.execution = result.completed.compilation?.execute(arguments.execution)
                if (result.completed.execution?.threw != null) {
                    result.failed.execution = result.completed.execution!!.threw!!.getStackTraceForSource(actualSource)
                }
            }
        } catch (templatingFailed: TemplatingFailed) {
            result.failed.template = templatingFailed
        } catch (snippetFailed: SnippetTransformationFailed) {
            result.failed.snippet = snippetFailed
        } catch (compilationFailed: CompilationFailed) {
            result.failed.compilation = compilationFailed
        } catch (e: Exception) {
            logger.error(e.toString())
        } finally {
            currentStatus.counts.completedJobs++
            result.interval = Interval(started, Instant.now())
            if (mongoCollection != null) {
                GlobalScope.launch { mongoCollection.insertOne(BsonDocument.parse(result.json)) }
            }
            return result
        }
    }

    class JobJson(
            val source: Map<String, String>?,
            val templates: Map<String, String>?,
            val snippet: String?,
            val tasks: Set<Task>,
            val arguments: TaskArguments?,
            val authToken: String?,
            val label: String
    )
    class JobAdapter {
        @FromJson
        fun jobFromJson(jobJson: JobJson): Job {
            assert(!(jobJson.source != null && jobJson.snippet != null)) { "can't set both snippet and sources" }
            return Job(jobJson.source, jobJson.templates, jobJson.snippet, jobJson.tasks, jobJson.arguments, jobJson.authToken, jobJson.label)
        }
        @ToJson
        fun jobToJson(job: Job): JobJson {
            assert(!(job.source != null && job.snippet != null)) { "can't set both snippet and sources" }
            return JobJson(job.source, job.templates, job.snippet, job.tasks, job.arguments, job.authToken, job.label)
        }
    }
    companion object {
        val mongoCollection = System.getenv("MONGO")?.run {
            val uri = MongoClientURI(this)
            val database = uri.database ?: assert {"MONGO must specify database to use" }
            val collection = System.getenv("MONGO_COLLECTION") ?: "jeed"
            MongoClient(uri).getDatabase(database).getCollection(collection, BsonDocument::class.java)
        }

        val googleTokenVerifier = System.getenv("GOOGLE_CLIENT_ID")?.run {
            GoogleIdTokenVerifier.Builder(NetHttpTransport(), JacksonFactory())
                    .setAudience(Collections.singletonList(System.getenv("GOOGLE_CLIENT_ID")))
                    .build()
        }
    }
}

@Suppress("EnumEntryName")
enum class Task(val task: String) {
    snippet("snippet"),
    compile("compile"),
    execute("execute"),
    template("template")
}
class TaskArguments(
        val compilation: CompilationArguments = CompilationArguments(),
        val execution: SourceExecutionArguments = SourceExecutionArguments()
)

class Result(val job: Job) {
    val status = currentStatus
    val completed: CompletedTasks = CompletedTasks()
    val failed: FailedTasks = FailedTasks()
    lateinit var interval: Interval

    val json: String
        get() = resultAdapter.toJson(this)

    data class ResultJson(
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
            throw Exception("Can't convert JSON to Result")
        }
        @ToJson
        fun resultToJson(result: Result): ResultJson {
            return ResultJson(result.job, result.status, result.completed, result.failed, result.interval)
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
        var execution: Sandbox.TaskResults<out Any?>? = null
)
class FailedTasks(
        var snippet: SnippetTransformationFailed? = null,
        var template: TemplatingFailed? = null,
        var compilation: CompilationFailed? = null,
        var execution: String? = null
)
