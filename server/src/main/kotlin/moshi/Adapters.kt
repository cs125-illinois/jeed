package edu.illinois.cs.cs125.jeed.server.moshi

import com.squareup.moshi.FromJson
import com.squareup.moshi.ToJson
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import edu.illinois.cs.cs125.jeed.core.Interval
import edu.illinois.cs.cs125.jeed.core.TemplatedSource
import edu.illinois.cs.cs125.jeed.server.*

@JvmField
val Adapters = setOf(
        JobAdapter(),
        ResultAdapter(),
        TemplatedSourceAdapter()
)

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

data class TemplatedSourceJson(val originalSources: List<FlatSource>)
class TemplatedSourceAdapter {
    @Throws(Exception::class)
    @Suppress("UNUSED_PARAMETER")
    @FromJson
    fun templatedSourceFromJson(templatedSourceJson: TemplatedSourceJson): TemplatedSource {
        throw Exception("Can't convert JSON to TemplatedSource")
    }
    @ToJson
    fun templatedSourceToJson(templatedSource: TemplatedSource): TemplatedSourceJson {
        return TemplatedSourceJson(templatedSource.originalSources.toFlatSources())
    }
}
