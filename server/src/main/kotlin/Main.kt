package edu.illinois.cs.cs125.jeed.server

import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.jackson2.JacksonFactory
import com.mongodb.MongoClient
import com.mongodb.MongoClientURI
import com.squareup.moshi.Moshi
import com.uchuhimo.konf.source.json.toJson
import edu.illinois.cs.cs125.jeed.core.moshi.Adapters as JeedAdapters
import edu.illinois.cs.cs125.jeed.server.moshi.Adapters as Adapters
import io.ktor.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.URI
import java.util.Collections
import java.util.Properties
import mu.KotlinLogging
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

fun main() {
    logger.info(configuration.toJson.toText())

    val httpUri = URI(configuration[TopLevel.http])
    assert(httpUri.scheme == "http")

    configuration[TopLevel.mongodb]?.let {
        val mongoUri = MongoClientURI(it)
        val database = mongoUri.database ?: require { "MONGO must specify database to use" }
        val collection = configuration[TopLevel.Mongo.collection]
        Job.mongoCollection = MongoClient(mongoUri)
            .getDatabase(database)
            .getCollection(collection, BsonDocument::class.java)
    }
    configuration[Auth.Google.clientID]?.let {
        Job.googleTokenVerifier = GoogleIdTokenVerifier.Builder(NetHttpTransport(), JacksonFactory())
                .setAudience(Collections.singletonList(it))
                .build()
    }

    embeddedServer(Netty, host = httpUri.host, port = httpUri.port, module = Application::jeed).start(wait = true)
}

fun assert(block: () -> String): Nothing { throw AssertionError(block()) }
fun check(block: () -> String): Nothing { throw IllegalStateException(block()) }
fun require(block: () -> String): Nothing { throw IllegalArgumentException(block()) }
