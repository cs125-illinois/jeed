package edu.illinois.cs.cs125.jeed.server

import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.assertions.ktor.shouldHaveStatus
import io.kotlintest.specs.StringSpec
import io.ktor.application.Application
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.setBody
import io.ktor.server.testing.withTestApplication

class TestGoogleAuth : StringSpec() {
    override fun beforeSpec(spec: Spec) {
        configuration[TopLevel.auth] = setOf("google")
    }
    override fun afterSpec(spec: Spec) {
        configuration[TopLevel.auth] = setOf("none", "google")
    }

    init {
        "should reject request without token" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody("""
{
  "snippet": "System.out.println(\"Here\");",
  "tasks": [ "execute" ]
}""".trim())
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.Unauthorized.value)
                }
            }
        }
        "should reject request with bad token" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody("""
{
  "authToken": "blahblah",
  "snippet": "System.out.println(\"Here\");",
  "tasks": [ "execute" ]
}""".trim())
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.Unauthorized.value)
                }
            }
        }
    }
}
