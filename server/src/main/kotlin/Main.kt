@file:Suppress("MatchingDeclarationName")

package edu.illinois.cs.cs125.jeed.server

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.mongodb.MongoClient
import com.mongodb.MongoClientURI
import com.mongodb.client.model.Filters
import com.ryanharter.ktor.moshi.moshi
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.uchuhimo.konf.source.json.toJson
import edu.illinois.cs.cs125.jeed.core.SnippetArguments
import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.checkstyle
import edu.illinois.cs.cs125.jeed.core.compile
import edu.illinois.cs.cs125.jeed.core.complexity
import edu.illinois.cs.cs125.jeed.core.execute
import edu.illinois.cs.cs125.jeed.core.fromSnippet
import edu.illinois.cs.cs125.jeed.core.kompile
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
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.URI
import java.time.Instant
import java.util.Collections
import java.util.Properties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.apache.http.auth.AuthenticationException
import org.bson.BsonDocument

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
        @Suppress("TooGenericExceptionCaught")
        post("/auth") {
            if (Request.googleTokenVerifier == null) {
                call.respond(HttpStatusCode.ExpectationFailed)
                return@post
            }
            val preAuthenticationRequest = try {
                call.receive<PreAuthenticationRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            try {
                withContext(Dispatchers.IO) { Request.googleTokenVerifier!!.verify(preAuthenticationRequest.authToken) }
            } catch (e: Exception) {
                call.respond(HttpStatusCode.Unauthorized)
            } finally {
                call.respond(HttpStatusCode.OK)
            }
        }
        post("/") {
            @Suppress("TooGenericExceptionCaught")
            val job = try {
                call.receive<Request>().check()
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
                currentStatus.lastRequest = Instant.now()
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

fun main() {
    logger.info(configuration.toJson.toText())

    val httpUri = URI(configuration[TopLevel.http])
    assert(httpUri.scheme == "http")

    configuration[TopLevel.mongodb]?.let {
        val mongoUri = MongoClientURI(it)
        val database = mongoUri.database ?: require { "MONGO must specify database to use" }
        val collection = configuration[TopLevel.Mongo.collection]
        Request.mongoCollection = MongoClient(mongoUri)
            .getDatabase(database)
            .getCollection(collection, BsonDocument::class.java)
    }
    configuration[Auth.Google.clientID]?.let {
        Request.googleTokenVerifier = GoogleIdTokenVerifier.Builder(NetHttpTransport(), JacksonFactory())
                .setAudience(Collections.singletonList(it))
                .build()
    }

    GlobalScope.launch {
        logger.info(
            Source.fromSnippet(
                """System.out.println("javac initialized");""",
                SnippetArguments(indent = 2)
            ).also {
                it.checkstyle()
                it.complexity()
            }.compile().execute().output
        )
        logger.info(
            Source.fromSnippet(
                """println("kotlinc initialized")""",
                SnippetArguments(fileType = Source.FileType.KOTLIN)
            ).kompile().execute().output
        )
        Request.mongoCollection?.find(Filters.eq("_id", ""))
    }

    embeddedServer(Netty, host = httpUri.host, port = httpUri.port, module = Application::jeed).start(wait = true)
}

fun assert(block: () -> String): Nothing { throw AssertionError(block()) }
fun check(block: () -> String): Nothing { throw IllegalStateException(block()) }
fun require(block: () -> String): Nothing { throw IllegalArgumentException(block()) }
