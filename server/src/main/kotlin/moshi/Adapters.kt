package edu.illinois.cs.cs125.jeed.server.moshi

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonClass
import com.squareup.moshi.ToJson
import edu.illinois.cs.cs125.jeed.core.Interval
import edu.illinois.cs.cs125.jeed.core.server.CompletedTasks
import edu.illinois.cs.cs125.jeed.core.server.FailedTasks
import edu.illinois.cs.cs125.jeed.core.server.FlatSource
import edu.illinois.cs.cs125.jeed.core.server.Task
import edu.illinois.cs.cs125.jeed.core.server.TaskArguments
import edu.illinois.cs.cs125.jeed.core.server.toFlatSources
import edu.illinois.cs.cs125.jeed.core.server.toSource
import edu.illinois.cs.cs125.jeed.server.Request
import edu.illinois.cs.cs125.jeed.server.Response
import edu.illinois.cs.cs125.jeed.server.Status

@JvmField
val Adapters = setOf(
    JobAdapter(),
    ResultAdapter()
)

@JsonClass(generateAdapter = true)
@Suppress("LongParameterList")
class RequestJson(
    val sources: List<FlatSource>?,
    val templates: List<FlatSource>?,
    val snippet: String?,
    val tasks: Set<Task>,
    val arguments: TaskArguments?,
    val label: String,
    val checkForSnippet: Boolean? = false
)

class JobAdapter {
    @FromJson
    fun jobFromJson(requestJson: RequestJson): Request {
        assert(!(requestJson.sources != null && requestJson.snippet != null)) { "can't set both snippet and sources" }
        assert(requestJson.sources != null || requestJson.snippet != null) { "must set either sources or snippet" }
        return Request(
            requestJson.sources?.toSource(),
            requestJson.templates?.toSource(),
            requestJson.snippet,
            requestJson.tasks,
            requestJson.arguments,
            requestJson.label,
            requestJson.checkForSnippet ?: false
        )
    }

    @ToJson
    fun jobToJson(request: Request): RequestJson {
        assert(!(request.source != null && request.snippet != null)) { "can't set both snippet and sources" }
        return RequestJson(
            request.source?.toFlatSources(),
            request.templates?.toFlatSources(),
            request.snippet,
            request.tasks,
            request.arguments,
            request.label,
            request.checkForSnippet
        )
    }
}

@JsonClass(generateAdapter = true)
data class ResponseJson(
    val email: String?,
    val audience: List<String>?,
    val request: Request,
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
    fun resultFromJson(responseJson: ResponseJson): Response {
        val result = Response(responseJson.request)

        result.completed.snippet = responseJson.completed.snippet
        result.completed.compilation = responseJson.completed.compilation
        result.completed.kompilation = responseJson.completed.kompilation
        result.completed.template = responseJson.completed.template
        result.completed.checkstyle = responseJson.completed.checkstyle
        result.completed.ktlint = responseJson.completed.ktlint
        result.completed.complexity = responseJson.completed.complexity
        result.completed.execution = responseJson.completed.execution
        result.completed.cexecution = responseJson.completed.cexecution
        result.completed.features = responseJson.completed.features
        result.completed.mutations = responseJson.completed.mutations
        result.completed.disassemble = responseJson.completed.disassemble
        result.completedTasks.addAll(responseJson.completedTasks)

        result.failed.snippet = responseJson.failed.snippet
        result.failed.compilation = responseJson.failed.compilation
        result.failed.kompilation = responseJson.failed.kompilation
        result.failed.template = responseJson.failed.template
        result.failed.checkstyle = responseJson.failed.checkstyle
        result.failed.ktlint = responseJson.failed.ktlint
        result.failed.complexity = responseJson.failed.complexity
        result.failed.execution = responseJson.failed.execution
        result.failed.cexecution = responseJson.failed.cexecution
        result.failed.features = responseJson.failed.features
        result.failed.mutations = responseJson.failed.mutations
        result.failed.disassemble = responseJson.failed.disassemble
        result.failedTasks.addAll(responseJson.failedTasks)

        result.interval = responseJson.interval

        return result
    }

    @ToJson
    fun resultToJson(response: Response): ResponseJson {
        return ResponseJson(
            response.email,
            response.audience,
            response.request,
            response.status,
            response.completed,
            response.completedTasks,
            response.failed,
            response.failedTasks,
            response.interval
        )
    }
}
