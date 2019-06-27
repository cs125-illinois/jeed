import com.ryanharter.ktor.moshi.moshi
import com.squareup.moshi.JsonClass
import edu.illinois.cs.cs125.jeed.core.CompilationArguments
import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments
import edu.illinois.cs.cs125.jeed.core.moshi.Adapters
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.ContentNegotiation
import io.ktor.request.receive
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

val portString = System.getenv("PORT") ?: "8080"

fun main(args: Array<String>) {
    val port = portString.toIntOrNull() ?: require { "$portString is not a valid port number " }
    val server = embeddedServer(Netty, port = port) {
        install(ContentNegotiation) {
            moshi {
                this.add(Adapters)
            }
        }
        routing {
            post("/") {
                val job = call.receive<Job>()
                println(job)
            }
        }
    }
}

enum class Task(val task: String) {
    COMPILE("compile"),
    EXECUTE("execute")
}
class TaskArguments(
        val compilation: CompilationArguments = CompilationArguments(),
        val execution: SourceExecutionArguments = SourceExecutionArguments()
)
abstract class Job(val tasks: Set<Task>, val arguments: TaskArguments)
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

fun check(block: () -> String): Nothing { throw IllegalStateException(block()) }
fun require(block: () -> String): Nothing { throw IllegalArgumentException(block()) }
