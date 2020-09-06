package edu.illinois.cs.cs125.jeed.server

import io.kotest.assertions.ktor.shouldHaveStatus
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.StringSpec
import io.ktor.application.Application
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.setBody
import io.ktor.server.testing.withTestApplication

class TestGoogleAuth : StringSpec() {
    override fun beforeSpec(spec: Spec) {
        configuration[Auth.none] = false
    }

    override fun afterSpec(spec: Spec) {
        configuration[Auth.none] = true
    }

    init {
        "should reject request without token" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
  "label": "test",
  "snippet": "System.out.println(\"Here\");",
  "tasks": [ "compile", "execute" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.Unauthorized.value)
                }
            }
        }
        "should reject request with bad token" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
  "label": "test",
  "authToken": "blahblah",
  "snippet": "System.out.println(\"Here\");",
  "tasks": [ "compile", "execute" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.Unauthorized.value)
                }
            }
        }
    }
}
