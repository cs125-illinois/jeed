@file:Suppress("MatchingDeclarationName")

package edu.illinois.cs.cs125.jeed.server

import com.ryanharter.ktor.moshi.moshi
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.uchuhimo.konf.source.json.toJson
import edu.illinois.cs.cs125.jeed.core.warm
import edu.illinois.cs.cs125.jeed.server.moshi.Adapters
import io.ktor.application.Application
import io.ktor.application.ApplicationCallPipeline
import io.ktor.application.call
import io.ktor.application.install
import io.ktor.features.CORS
import io.ktor.features.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.request.receive
import io.ktor.response.respond
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.time.delay
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.time.Duration
import java.time.Instant
import java.util.Properties
import kotlin.system.exitProcess
import edu.illinois.cs.cs125.jeed.core.moshi.Adapters as JeedAdapters

@Suppress("UNUSED")
val logger = KotlinLogging.logger {}
val moshi: Moshi = Moshi.Builder().let { builder ->
    Adapters.forEach { builder.add(it) }
    JeedAdapters.forEach { builder.add(it) }
    builder.build()
}
val VERSION: String = Properties().also {
    it.load((object {}).javaClass.getResourceAsStream("/edu.illinois.cs.cs125.jeed.server.version"))
}.getProperty("version")

val currentStatus = Status()

@JsonClass(generateAdapter = true)
data class PreAuthenticationRequest(val authToken: String)

@Suppress("ComplexMethod", "LongMethod")
fun Application.jeed() {
    install(CORS) {
        anyHost()
        allowNonSimpleContentTypes = true
    }
    install(ContentNegotiation) {
        moshi {
            JeedAdapters.forEach { this.add(it) }
            Adapters.forEach { this.add(it) }
        }
    }
    routing {
        get("/") {
            call.respond(currentStatus.update())
        }
        post("/") {
            withContext(Dispatchers.IO) {
                val job = try {
                    call.receive<Request>().check()
                } catch (e: Exception) {
                    logger.warn(e.toString())
                    call.respond(HttpStatusCode.BadRequest)
                    return@withContext
                }

                try {
                    val result = job.run()
                    currentStatus.lastRequest = Instant.now()
                    call.respond(result)
                } catch (e: Exception) {
                    logger.warn(e.toString())
                    call.respond(HttpStatusCode.BadRequest)
                }
            }
        }
    }
    intercept(ApplicationCallPipeline.Fallback) {
        if (call.response.status() == null) {
            call.respond(HttpStatusCode.NotFound)
        }
    }
}

private val backgroundScope = CoroutineScope(Dispatchers.IO)

fun main() = runBlocking<Unit> {
    logger.info(configuration.toJson.toText())

    backgroundScope.launch { warm(2) }
    backgroundScope.launch {
        delay(Duration.ofMinutes(configuration[TopLevel.sentinelDelay]))
        try {
            warm(2)
            logger.debug("Sentinel succeeded")
        } catch (e: CancellationException) {
            return@launch
        } catch (err: Throwable) {
            logger.error("Restarting due to sentinel failure")
            err.printStackTrace()
            exitProcess(-1)
        }
    }
    embeddedServer(Netty, port = 8888, module = Application::jeed).start(true)
}
