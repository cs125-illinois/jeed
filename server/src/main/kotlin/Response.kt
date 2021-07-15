package edu.illinois.cs.cs125.jeed.server

import com.squareup.moshi.JsonAdapter
import edu.illinois.cs.cs125.jeed.core.Interval
import edu.illinois.cs.cs125.jeed.core.server.CompletedTasks
import edu.illinois.cs.cs125.jeed.core.server.FailedTasks
import edu.illinois.cs.cs125.jeed.core.server.Task

class Response(val request: Request) {
    val email = request.email
    val audience = request.audience

    val status = currentStatus

    val completedTasks: MutableSet<Task> = mutableSetOf()
    val completed: CompletedTasks = CompletedTasks()

    val failedTasks: MutableSet<Task> = mutableSetOf()
    val failed: FailedTasks = FailedTasks()

    lateinit var interval: Interval

    @Suppress("unused")
    val json: String
        get() = RESPONSE_ADAPTER.toJson(this)

    companion object {
        val RESPONSE_ADAPTER: JsonAdapter<Response> = moshi.adapter(Response::class.java)
        fun from(response: String?): Response {
            check(response != null) { "can't deserialize null string" }
            return RESPONSE_ADAPTER.fromJson(response) ?: error("failed to deserialize result")
        }
    }
}
