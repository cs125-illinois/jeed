package edu.illinois.cs.cs125.jeed.server

import io.kotlintest.assertions.ktor.shouldHaveStatus
import io.kotlintest.matchers.beOfType
import io.kotlintest.should
import io.kotlintest.specs.StringSpec
import io.ktor.application.Application
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.setBody
import io.ktor.server.testing.withTestApplication

class TestConfig : StringSpec({
    "should load the configuration correctly" {
        config[Limits.Execution.timeout] should  beOfType<Long>()
    }
    "should reject snippet request with too long timeout" {
        withTestApplication(Application::jeed) {
            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody("""
{
  "snippet": "System.out.println(\"Here\");",
  "tasks": [ "execute" ],
  "arguments": {
    "execution": {
      "timeout": "${ config[Limits.Execution.timeout] }"
    }
  }
}""".trim())
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.OK.value)
            }

            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody("""
{
  "snippet": "System.out.println(\"Here\");",
  "tasks": [ "execute" ],
  "arguments": {
    "execution": {
      "timeout": "${ config[Limits.Execution.timeout] + 1 }"
    }
  }
}""".trim())
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.BadRequest.value)
            }
        }
    }
    "should reject snippet request with too many extra threads" {
        withTestApplication(Application::jeed) {
            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody("""
{
  "snippet": "System.out.println(\"Here\");",
  "tasks": [ "execute" ],
  "arguments": {
    "execution": {
      "maxExtraThreads": "${ config[Limits.Execution.maxExtraThreads] }"
    }
  }
}""".trim())
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.OK.value)
            }

            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody("""
{
  "snippet": "System.out.println(\"Here\");",
  "tasks": [ "execute" ],
  "arguments": {
    "execution": {
      "maxExtraThreads": "${ config[Limits.Execution.maxExtraThreads] + 1 }"
    }
  }
}""".trim())
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.BadRequest.value)
            }
        }
    }
})
