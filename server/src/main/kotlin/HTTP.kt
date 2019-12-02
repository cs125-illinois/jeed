package edu.illinois.cs.cs125.jeed.server

import com.ryanharter.ktor.moshi.moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import edu.illinois.cs.cs125.jeed.server.moshi.Adapters
import edu.illinois.cs.cs125.jeed.core.moshi.Adapters as JeedAdapters
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
import org.apache.http.auth.AuthenticationException
import java.time.Instant

fun Application.jeed() {
    install(CORS) {
        anyHost()
        allowNonSimpleContentTypes = true
    }
    install(ContentNegotiation) {
        moshi {
            JeedAdapters.forEach { this.add(it) }
            Adapters.forEach { this.add(it) }
            this.add(KotlinJsonAdapterFactory())
        }
    }
    routing {
        get("/") {
            call.respond(currentStatus)
        }
        post("/") {
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
