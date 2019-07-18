package edu.illinois.cs.cs125.jeed.server

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import edu.illinois.cs.cs125.jeed.core.*
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

@JvmField
val Adapters = setOf(Job.JobAdapter(), Result.ResultAdapter())
