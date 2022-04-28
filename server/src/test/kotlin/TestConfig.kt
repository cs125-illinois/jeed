package edu.illinois.cs.cs125.jeed.server

import com.uchuhimo.konf.Config
import com.uchuhimo.konf.source.yaml
import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.moshi.PermissionJson
import io.kotest.assertions.ktor.shouldHaveStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeExactly
import io.kotest.matchers.shouldBe
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.setBody
import io.ktor.server.testing.withTestApplication

@Suppress("DEPRECATION")
class TestConfig : StringSpec({
    "should load defaults correctly" {
        configuration[Limits.Execution.timeout] shouldBeExactly Sandbox.ExecutionArguments.DEFAULT_TIMEOUT
    }
    "should load simple configuration from a file" {
        val config = Config { addSpec(Limits) }.from.yaml.string(
            """
limits:
  execution:
    timeout: 10000
        """.trim()
        )
        config[Limits.Execution.timeout] shouldBeExactly 10000L
    }
    "should load complex configuration from a file" {
        val config = Config { addSpec(Limits) }.from.yaml.string(
            """
limits:
  execution:
    permissions:
      - klass: java.lang.RuntimePermission
        name: createClassLoader
    timeout: 10000
        """.trim()
        )

        config[Limits.Execution.timeout] shouldBeExactly 10000L
        config[Limits.Execution.permissions] shouldHaveSize 1
        config[Limits.Execution.permissions][0] shouldBe PermissionJson(
            "java.lang.RuntimePermission",
            "createClassLoader",
            null
        )
    }
    "should reject snippet request with too long timeout" {
        withTestApplication(Application::jeed) {
            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody(
                    """
{
  "label": "test",
  "snippet": "System.out.println(\"Here\");",
  "tasks": [ "compile", "execute" ],
  "arguments": {
    "execution": {
      "timeout": "${configuration[Limits.Execution.timeout] + 1}"
    }
  }
}""".trim()
                )
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.BadRequest.value)
            }

            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody(
                    """
{
  "label": "test",
  "snippet": "System.out.println(\"Here\");",
  "tasks": [ "compile", "execute" ],
  "arguments": {
    "execution": {
      "timeout": "${configuration[Limits.Execution.timeout] + 1}"
    }
  }
}""".trim()
                )
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.BadRequest.value)
            }
        }
    }
    "should reject snippet request with too many extra threads" {
        withTestApplication(Application::jeed) {
            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody(
                    """
{
  "label": "test",
  "snippet": "System.out.println(\"Here\");",
  "tasks": [ "compile", "execute" ],
  "arguments": {
    "execution": {
      "maxExtraThreads": "${configuration[Limits.Execution.maxExtraThreads] + 1}"
    }
  }
}""".trim()
                )
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.BadRequest.value)
            }

            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody(
                    """
{
  "label": "test",
  "snippet": "System.out.println(\"Here\");",
  "tasks": [ "compile", "execute" ],
  "arguments": {
    "execution": {
      "maxExtraThreads": "${configuration[Limits.Execution.maxExtraThreads] + 1}"
    }
  }
}""".trim()
                )
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.BadRequest.value)
            }
        }
    }
    "should reject snippet request with too many permissions" {
        withTestApplication(Application::jeed) {
            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody(
                    """
{
  "label": "test",
  "snippet": "System.out.println(\"Here\");",
  "tasks": [ "compile", "execute" ],
  "arguments": {
    "execution": {
      "permissions": [{
        "klass": "java.lang.RuntimePermission",
        "name": "accessDeclaredMembers"
      }, {
        "klass": "java.lang.reflect.ReflectPermission",
        "name": "suppressAccessChecks"
      }]
    }
  }
}""".trim()
                )
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.OK.value)
            }

            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody(
                    """
{
  "label": "test",
  "snippet": "System.out.println(\"Here\");",
  "tasks": [ "compile", "execute" ],
  "arguments": {
    "execution": {
      "permissions": [{
        "klass": "java.lang.RuntimePermission",
        "name": "createClassLoader"
      }, {
        "klass": "java.lang.reflect.ReflectPermission",
        "name": "suppressAccessChecks"
      }]
    }
  }
}""".trim()
                )
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.BadRequest.value)
            }
        }
    }
    "should reject snippet request attempting to remove blacklisted classes" {
        withTestApplication(Application::jeed) {
            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody(
                    """
{
  "label": "test",
  "snippet": "System.out.println(\"Here\");",
  "tasks": [ "compile", "execute" ],
  "arguments": {
    "execution": {
      "classLoaderConfiguration": {
        "blacklistedClasses": [
          "java.lang.reflect."
        ]
      }
    }
  }
}""".trim()
                )
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.OK.value)
            }

            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody(
                    """
{
  "label": "test",
  "snippet": "System.out.println(\"Here\");",
  "tasks": [ "compile", "execute" ],
  "arguments": {
    "execution": {
      "classLoaderConfiguration": {
        "blacklistedClasses": []
      }
    }
  }
}""".trim()
                )
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.BadRequest.value)
            }
        }
    }
    "should reject snippet request attempting to remove forbidden methods" {
        withTestApplication(Application::jeed) {
            handleRequest(HttpMethod.Post, "/") {
                addHeader("content-type", "application/json")
                setBody(
                    """
{
  "label": "test",
  "snippet": "System.out.println(\"Here\");",
  "tasks": [ "compile", "execute" ],
  "arguments": {
    "execution": {
      "classLoaderConfiguration": {
        "blacklistedMethods": [
          {
            "ownerClassPrefix": "java.lang.Class",
            "methodPrefix": "forName",
            "allowInReload": false
          }
        ]
      }
    }
  }
}""".trim()
                )
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.BadRequest.value)
            }
        }
    }
})
