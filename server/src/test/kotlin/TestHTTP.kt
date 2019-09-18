package edu.illinois.cs.cs125.jeed.server

import io.kotlintest.assertions.ktor.shouldHaveStatus
import io.kotlintest.specs.StringSpec
import io.ktor.application.Application
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.withTestApplication
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.setBody

class TestHTTP : StringSpec({
    "should accept good snippet request" {
        withTestApplication(Application::jeed) {
            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody("""
{
  "label": "test",
  "snippet": "System.out.println(\"Here\");",
  "tasks": [ "execute" ]
}""".trim())
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.OK.value)
            }
        }
    }
    "should accept good source request" {
        withTestApplication(Application::jeed) {
            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody("""
{
  "label": "test",
  "source": {
    "Main.java": "
public class Main {
    public static void main() {
        System.out.println(\"Here\");
    }
}"},
  "tasks": [ "execute" ]
}""".trim())
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.OK.value)
            }
        }
    }
    "should accept good source checkstyle request" {
        withTestApplication(Application::jeed) {
            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody("""
{
  "label": "test",
  "source": {
    "Main.java": "
public class Main {
    public static void main() {
        System.out.println(\"Here\");
    }
}"},
  "tasks": [ "checkstyle", "execute" ]
}""".trim())
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.OK.value)
            }
        }
    }
    "should accept good templated source request" {
        withTestApplication(Application::jeed) {
            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody("""
{
  "label": "test",
  "templates": {
    "Main.hbs": "
public class Main {
    public static void main() {
        {{{ contents }}}
    }
}"},
  "source": {
    "Main.java": "System.out.println(\"Here\");"
  },
  "tasks": [ "template", "execute" ]
}""".trim())
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.OK.value)
            }
        }
    }
    "should reject unauthorized request" {
        withTestApplication(Application::jeed) {
            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody("broken")
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.BadRequest.value)
            }
        }
    }
    "should reject bad request" {
        withTestApplication(Application::jeed) {
            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody("broken")
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.BadRequest.value)
            }
        }
    }
    "should provide info in response to GET" {
        withTestApplication(Application::jeed) {
            handleRequest(HttpMethod.Get, "/") {
                addHeader("content-type", "application/json")
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.OK.value)
                println(response.content.toString())
            }
        }
    }
})
