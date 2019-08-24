package edu.illinois.cs.cs125.jeed.server

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
import org.bson.BsonDocument
import java.time.Instant

class Job(
        val source: Map<String, String>?,
        val templates: Map<String, String>?,
        val snippet: String?,
        passedTasks: Set<Task>,
        arguments: TaskArguments?
) {
    val tasks: Set<Task>
    val arguments = arguments ?: TaskArguments()

    init {
        require(!(source != null && snippet != null)) { "can't create task with both source and snippet" }
        if (templates != null) { require(source != null) { "can't use both templates and snippet mode" }}

        val tasksToRun = passedTasks.toMutableSet()
        if (tasksToRun.contains(Task.execute)) { tasksToRun.add(Task.compile) }
        if (snippet != null) { tasksToRun.add(Task.snippet) }
        if (templates != null) { tasksToRun.add(Task.template) }
        tasks = tasksToRun.toSet()

        if (Task.execute in tasks) {
            if (arguments?.execution?.timeout != null) {
                require(arguments.execution.timeout <= config[Limits.Execution.timeout]) {
                    "timeout of ${arguments.execution.timeout} too long (> ${config[Limits.Execution.timeout]})"
                }
            }
            if (arguments?.execution?.maxExtraThreads != null) {
                require(arguments.execution.maxExtraThreads <= config[Limits.Execution.maxExtraThreads]) {
                    "maxExtraThreads of ${arguments.execution.maxExtraThreads} is too large (> ${config[Limits.Execution.maxExtraThreads]}"
                }
            }
            if (arguments?.execution?.permissions != null) {
                val allowedPermissions = config[Limits.Execution.permissions].map { PermissionAdapter().permissionFromJson(it) }.toSet()
                require(allowedPermissions.containsAll(arguments.execution.permissions)) {
                    "task is requesting unallowed permissions"
                }
            }
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
            }
        } catch (templatingFailed: TemplatingFailed) {
            result.failed.template = templatingFailed
        } catch (snippetFailed: SnippetTransformationFailed) {
            result.failed.snippet = snippetFailed
        } catch (compilationFailed: CompilationFailed) {
            result.failed.compilation = compilationFailed
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
            val arguments: TaskArguments?
    )
    class JobAdapter {
        @FromJson
        fun jobFromJson(jobJson: JobJson): Job {
            assert(!(jobJson.source != null && jobJson.snippet != null)) { "can't set both snippet and sources" }
            return Job(jobJson.source, jobJson.templates, jobJson.snippet, jobJson.tasks, jobJson.arguments)
        }
        @ToJson
        fun jobToJson(job: Job): JobJson {
            assert(!(job.source != null && job.snippet != null)) { "can't set both snippet and sources" }
            return JobJson(job.source, job.templates, job.snippet, job.tasks, job.arguments)
        }
    }
    companion object {
        val mongoCollection = System.getenv("MONGO")?.run {
            val uri = MongoClientURI(this)
            val database = uri.database ?: assert {"MONGO must specify database to use" }
            val collection = System.getenv("MONGO_COLLECTION") ?: "jeed"
            MongoClient(uri).getDatabase(database).getCollection(collection, BsonDocument::class.java)
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

class Result(job: Job) {
    val status = currentStatus
    val tasks = job.tasks
    val arguments = job.arguments
    val completed: CompletedTasks = CompletedTasks()
    val failed: FailedTasks = FailedTasks()
    lateinit var interval: Interval

    val json: String
        get() = resultAdapter.toJson(this)

    data class ResultJson(
            val status: Status,
            val tasks: Set<Task>,
            val arguments: TaskArguments,
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
            return ResultJson(result.status, result.tasks, result.arguments, result.completed, result.failed, result.interval)
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
        var compilation: CompilationFailed? = null
)
