package edu.illinois.cs.cs125.jeed.server

import io.ktor.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.lang.AssertionError
import java.net.URI

fun main() {
    System.getenv("HTTP")?.run {
        val uri = URI(this)
        assert(uri.scheme == "http")
        embeddedServer(Netty, host=uri.host, port=uri.port, module=Application::jeed).start(wait = true)
    }
}

fun assert(block: () -> String): Nothing { throw AssertionError(block()) }
fun check(block: () -> String): Nothing { throw IllegalStateException(block()) }
fun require(block: () -> String): Nothing { throw IllegalArgumentException(block()) }
