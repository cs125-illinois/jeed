package edu.illinois.cs.cs125.jeed.server

import com.ryanharter.ktor.moshi.moshi
import com.squareup.moshi.JsonEncodingException
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

fun Application.jeed() {
    install(ContentNegotiation) {
        moshi {
            Adapters.forEach { this.add(it) }
            add(Job.JobAdapter())
            add(Result.ResultAdapter())
        }
    }
    routing {
        post("/") {
            try {
                val job = call.receive<Job>()
                call.respond(job.run())
            } catch (e: JsonEncodingException) {
                call.respond(HttpStatusCode.BadRequest)
            }
        }
    }
    intercept(ApplicationCallPipeline.Fallback) {
        if (call.response.status() == null) { call.respond(HttpStatusCode.NotFound) }
    }
}
