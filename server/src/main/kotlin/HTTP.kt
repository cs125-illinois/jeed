package edu.illinois.cs.cs125.jeed.server

import com.ryanharter.ktor.moshi.moshi
import edu.illinois.cs.cs125.jeed.core.moshi.Adapters as JeedAdapters
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
import java.time.Instant
import org.apache.http.auth.AuthenticationException

@Suppress("ComplexMethod")
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
            call.respond(currentStatus)
        }
        post("/") {
            @Suppress("TooGenericExceptionCaught")
            val job = try {
                call.receive<Job>()
            } catch (e: Exception) {
                logger.warn(e.toString())
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }

            try {
                job.authenticate()
            } catch (e: AuthenticationException) {
                logger.warn(e.toString())
                call.respond(HttpStatusCode.Unauthorized)
                return@post
            }

            @Suppress("TooGenericExceptionCaught")
            try {
                val result = job.run()
                currentStatus.lastJob = Instant.now()
                call.respond(result)
            } catch (e: Exception) {
                logger.warn(e.toString())
                call.respond(HttpStatusCode.BadRequest)
            }
        }
    }
    intercept(ApplicationCallPipeline.Fallback) {
        if (call.response.status() == null) { call.respond(HttpStatusCode.NotFound) }
    }
}
