package edu.illinois.cs.cs125.jeed.server.moshi

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonClass
import com.squareup.moshi.ToJson
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import edu.illinois.cs.cs125.jeed.core.*
import edu.illinois.cs.cs125.jeed.core.moshi.TemplatedSourceResult
import edu.illinois.cs.cs125.jeed.server.*

@JvmField
val Adapters = setOf(
        JobAdapter(),
        ResultAdapter(),
        TemplatedSourceResultAdapter()
)

@JsonClass(generateAdapter = true)
class JobJson(
        val sources: List<FlatSource>?,
        val templates: List<FlatSource>?,
        val snippet: String?,
        val tasks: Set<Task>,
        val arguments: TaskArguments?,
        val authToken: String?,
        val label: String,
        val waitForSave: Boolean = false
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

@JsonClass(generateAdapter = true)
data class ResultJson(
        val email: String?,
        val job: Job,
        val status: Status,
        val completed: CompletedTasks,
        val completedTasks: Set<Task>,
        val failed: FailedTasks,
        val failedTasks: Set<Task>,
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
        result.completed.kompilation = resultJson.completed.kompilation
        result.completed.template = resultJson.completed.template
        result.completed.execution = resultJson.completed.execution
        result.completedTasks.addAll(resultJson.completedTasks)

        result.failed.snippet = resultJson.failed.snippet
        result.failed.compilation = resultJson.failed.compilation
        result.failed.kompilation = resultJson.failed.kompilation
        result.failed.template = resultJson.failed.template
        result.failed.execution = resultJson.failed.execution
        result.failedTasks.addAll(resultJson.failedTasks)

        result.interval = resultJson.interval

        return result
    }

    @ToJson
    fun resultToJson(result: Result): ResultJson {
        return ResultJson(
                result.email,
                result.job,
                result.status,
                result.completed,
                result.completedTasks,
                result.failed,
                result.failedTasks,
                result.interval
        )
    }
}

@JsonClass(generateAdapter = true)
data class TemplatedSourceResultJson(val sources: List<FlatSource>, val originalSources: List<FlatSource>)

class TemplatedSourceResultAdapter {
    @FromJson
    fun templatedSourceResultFromJson(templatedSourceResultJson: TemplatedSourceResultJson): TemplatedSourceResult {
        return TemplatedSourceResult(
                templatedSourceResultJson.sources.toSource(),
                templatedSourceResultJson.originalSources.toSource()
        )
    }

    @ToJson
    fun templatedSourceResultToJson(templatedSourceResult: TemplatedSourceResult): TemplatedSourceResultJson {
        return TemplatedSourceResultJson(
                templatedSourceResult.sources.toFlatSources(),
                templatedSourceResult.originalSources.toFlatSources()
        )
    }
}