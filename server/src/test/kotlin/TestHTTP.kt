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
    "should accept good templated source request" {
        withTestApplication(Application::jeed) {
            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody("""
{
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
  "tasks": [ "execute" ]
}""".trim())
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.OK.value)
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
})
