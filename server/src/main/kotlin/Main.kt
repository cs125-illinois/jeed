package edu.illinois.cs.cs125.jeed.server

import com.ryanharter.ktor.moshi.moshi
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonEncodingException
import com.squareup.moshi.adapters.Rfc3339DateJsonAdapter
import edu.illinois.cs.cs125.jeed.core.*
import edu.illinois.cs.cs125.jeed.core.moshi.Adapters
import io.ktor.application.Application
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun Application.jeed() {
    install(ContentNegotiation) {
        moshi {
            Adapters.forEach { this.add(it) }
        }
    }
    routing {
        post("/") {
            try {
                val job = call.receive<Job>()
                call.respond(Result())
            } catch (e: JsonEncodingException) {
                call.respond(HttpStatusCode.BadRequest)
            }
        }
    }
    intercept(ApplicationCallPipeline.Fallback) {
        if (call.response.status() == null) { call.respond(HttpStatusCode.NotFound) }
    }
}

fun main(args: Array<String>) {
    val port = (System.getenv("PORT") ?: "8080").toIntOrNull()
            ?: require { "${System.getenv("PORT")} is not a valid port number " }
    embeddedServer(Netty, port = port, module = Application::jeed).start(wait = true)
}

enum class Task(val task: String) {
    compile("compile"),
    execute("execute")
}
class TaskArguments(
        val compilation: CompilationArguments = CompilationArguments(),
        val execution: SourceExecutionArguments = SourceExecutionArguments()
)
open class Job(val tasks: Set<Task>, val arguments: TaskArguments)
@JsonClass(generateAdapter = true)
class SourceJob(
        val source: Source,
        tasks: Set<Task>,
        arguments: TaskArguments = TaskArguments()
) : Job(tasks, arguments)
@JsonClass(generateAdapter = true)
class SnippetJob(
        val source: String,
        tasks: Set<Task>,
        arguments: TaskArguments = TaskArguments()
) : Job(tasks, arguments)

class CompletedTasks(
        val compilation: CompiledSource? = null,
        val execution: Sandbox.TaskResults<Any>? = null
)
class FailedTasks(
        val compilation: CompilationFailed? = null
)
@JsonClass(generateAdapter = true)
class Result(val completed: CompletedTasks = CompletedTasks(), val failed: FailedTasks = FailedTasks())

fun check(block: () -> String): Nothing { throw IllegalStateException(block()) }
fun require(block: () -> String): Nothing { throw IllegalArgumentException(block()) }
