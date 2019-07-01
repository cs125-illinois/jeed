package edu.illinois.cs.cs125.jeed.server

import io.ktor.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.lang.AssertionError

fun main() {
    val port = (System.getenv("PORT") ?: "8080").toIntOrNull()
            ?: require { "${System.getenv("PORT")} is not a valid port number " }
    embeddedServer(Netty, port = port, module = Application::jeed).start(wait = true)
}

fun assert(block: () -> String): Nothing { throw AssertionError(block()) }
fun check(block: () -> String): Nothing { throw IllegalStateException(block()) }
fun require(block: () -> String): Nothing { throw IllegalArgumentException(block()) }
