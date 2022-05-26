@file:Suppress("MagicNumber")

package edu.illinois.cs.cs125.jeed.server

import edu.illinois.cs.cs125.jeed.core.isWindows
import io.kotest.assertions.ktor.shouldHaveStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.beEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.shouldNotBe
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.setBody
import io.ktor.server.testing.withTestApplication

@Suppress("LargeClass", "DEPRECATION")
class TestHTTP : StringSpec() {
    init {
        "should accept good snippet request" {
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
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completed.execution?.klass shouldBe "Main"
                    jeedResponse.completedTasks.size shouldBe 3
                    jeedResponse.failedTasks.size shouldBe 0
                }
            }
        }
        "should accept good snippet cexecution request".config(enabled = !isWindows) {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"snippet": "System.out.println(\"Here\");",
"tasks": [ "compile", "cexecute" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completed.cexecution?.klass shouldBe "Main"
                    jeedResponse.completedTasks.size shouldBe 3
                    jeedResponse.failedTasks.size shouldBe 0
                }
            }
        }
        "should accept good kotlin snippet request" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"snippet": "println(\"Here\")",
"tasks": [ "kompile", "execute" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completed.execution?.klass shouldBe "MainKt"
                    jeedResponse.completedTasks.size shouldBe 3
                    jeedResponse.failedTasks.size shouldBe 0
                }
            }
        }
        "should accept good source request" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.java",
    "contents": " 
public class Main {
  public static void main() {
    System.out.println(\"Here\");
  }
}"
  }
],
"tasks": [ "compile", "execute" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 2
                    jeedResponse.failedTasks.size shouldBe 0
                }
            }
        }
        "should accept good kotlin source request" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.kt",
    "contents": "
fun main() {
  println(\"Here\")
}"
  }
],
"tasks": [ "kompile", "execute" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completed.execution?.klass shouldBe "MainKt"
                    jeedResponse.completed.execution?.outputLines?.joinToString(separator = "\n") {
                        it.line
                    }?.trim() shouldBe "Here"
                    jeedResponse.completedTasks.size shouldBe 2
                    jeedResponse.failedTasks.size shouldBe 0
                }
            }
        }
        "kotlin coroutines should work by default" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.kt",
    "contents": "
import kotlinx.coroutines.*
fun main() {
  val job = GlobalScope.launch {
    println(\"Here\")
  }
  runBlocking {
    job.join()
  }
}"
  }
],
"tasks": [ "kompile", "execute" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completed.execution?.klass shouldBe "MainKt"
                    jeedResponse.completed.execution?.outputLines?.joinToString(separator = "\n") {
                        it.line
                    }?.trim() shouldBe "Here"
                    jeedResponse.completedTasks.size shouldBe 2
                    jeedResponse.failedTasks.size shouldBe 0
                }
            }
        }
        "should accept good source checkstyle request" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.java",
    "contents": "
public class Main {
public static void main() {
    System.out.println(\"Here\");
}
}"
  }
],
"tasks": [ "checkstyle", "compile", "execute" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 3
                    jeedResponse.failedTasks.size shouldBe 0
                }
            }
        }
        "should accept good source ktlint request" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.kt",
    "contents": "
fun main() {
  println(\"Hello, world!\")
}"
  }
],
"tasks": [ "ktlint", "kompile", "execute" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 3
                    jeedResponse.failedTasks.size shouldBe 0
                }
            }
        }
        "should reject checkstyle request for non-Java sources" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.kt",
    "contents": "
fun main() {
  println(\"Here\")
}"
  }
],
"tasks": [ "checkstyle", "kompile", "execute" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.BadRequest.value)
                }
            }
        }
        "should accept good templated source request" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"templates": [
  {
    "path": "Main.java.hbs",
    "contents": "
public class Main {
public static void main() {
    {{{ contents }}}
}
}"
  }
],
"sources": [
  {
    "path": "Main.java",
    "contents": "System.out.println(\"Here\");"
  }
],
"tasks": [ "template", "compile", "execute" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 3
                    jeedResponse.failedTasks.size shouldBe 0
                }
            }
        }
        "should accept good source complexity request" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.java",
    "contents": "
public class Main {
    public static void main() {
        System.out.println(\"Here\");
    }
}"
  }
],
"tasks": [ "complexity" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 1
                    jeedResponse.failedTasks.size shouldBe 0
                }
            }
        }
        "should accept good source features request" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.java",
    "contents": "
public class Main {
    public static void main() {
        System.out.println(\"Here\");
    }
}"
  }
],
"tasks": [ "features" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 1
                    jeedResponse.failedTasks.size shouldBe 0
                }
            }
        }
        "should fail bad source features request" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.java",
    "contents": "
public class Main {
    public static void main() {
        System.out.println(\"Here\")
    }
}"
  }
],
"tasks": [ "features" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 0
                    jeedResponse.failedTasks.size shouldBe 1
                }
            }
        }
        "should accept good source mutations request" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.java",
    "contents": "
public class Main {
    public static void main() {
        System.out.println(\"Here\");
    }
}"
  }
],
"tasks": [ "mutations" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 1
                    jeedResponse.completed.mutations shouldNot beNull()
                    jeedResponse.completed.mutations!!.mutatedSources shouldNot beEmpty()
                    jeedResponse.failedTasks.size shouldBe 0
                }
            }
        }
        "should accept good Kotlin complexity request" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.kt",
    "contents": "
class Main {
    fun main() {
      println(\"Here\");
    }
}"
  }
],
"tasks": [ "complexity" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 1
                    jeedResponse.failedTasks.size shouldBe 0
                }
            }
        }
        "should handle snippet error" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"snippet": "System.out.println(\"Here\")",
"tasks": [ "snippet" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 0
                    jeedResponse.failedTasks.size shouldBe 1
                    (jeedResponse.failed.snippet?.errors?.size ?: 0) shouldBeGreaterThan 0
                }
            }
        }
        "should handle template error" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"templates": [
  {
    "path": "Main.java.hbs",
    "contents": "
public class Main {
public static void main() {
    {{ contents }}}
}
}"
  }
],
"sources": [
  {
    "path": "Main.java",
    "contents": "System.out.println(\"Here\");"
  }
],
"tasks": [ "template" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 0
                    jeedResponse.failedTasks.size shouldBe 1
                    (jeedResponse.failed.template?.errors?.size ?: 0) shouldBeGreaterThan 0
                }
            }
        }
        "should handle compilation error" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.java",
    "contents": " 
public class Main {
  public static void main() {
    System.out.println(\"Here\")
  }
}"
  }
],
"tasks": [ "compile" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 0
                    jeedResponse.failedTasks.size shouldBe 1
                    (jeedResponse.failed.compilation?.errors?.size ?: 0) shouldBeGreaterThan 0
                }
            }
        }
        "should handle kompilation error" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.kt",
    "contents": "
fun main() {
  printing(\"Here\")
}"
  }
],
"tasks": [ "kompile" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 0
                    jeedResponse.failedTasks.size shouldBe 1
                    (jeedResponse.failed.kompilation?.errors?.size ?: 0) shouldBeGreaterThan 0
                }
            }
        }
        "should handle checkstyle error" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"arguments": {
  "checkstyle": {
    "failOnError": true
  }
},
"sources": [
  {
    "path": "Main.java",
    "contents": "
public class Main {
public static void main() {
System.out.println(\"Here\");
}
}"
  }
],
"tasks": [ "checkstyle", "compile", "execute" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 1
                    jeedResponse.failedTasks.size shouldBe 1
                    (jeedResponse.failed.checkstyle?.errors?.size ?: 0) shouldBeGreaterThan 0
                }
            }
        }
        "should return checkstyle results when not configured to fail" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.java",
    "contents": "
public class Main {
public static void main() {
System.out.println(\"Here\");
}
}"
  }
],
"tasks": [ "checkstyle", "compile", "execute" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 3
                    jeedResponse.failedTasks.size shouldBe 0
                    (jeedResponse.completed.checkstyle?.errors?.size ?: 0) shouldBeGreaterThan 0
                }
            }
        }
        "should handle execution error" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.java",
    "contents": " 
public class Main {
  public static void main() {
    Object t = null;
    System.out.println(t.toString());
  }
}"
  }
],
"tasks": [ "compile", "execute" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 2
                    jeedResponse.failedTasks.size shouldBe 0
                    jeedResponse.completed.execution?.threw shouldNotBe ""
                }
            }
        }
        "should handle cexecution error" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.java",
    "contents": " 
public class Main {
  private static void min() {
    System.out.println(\"Here\");
  }
}"
  }
],
"tasks": [ "compile", "cexecute" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 1
                    jeedResponse.failedTasks.size shouldBe 1
                }
            }
        }
        "should reject both source and snippet request" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"snippet": "System.out.println(\"Hello, world!\");",
"sources": [
  {
    "path": "Main.java",
    "contents": " 
public class Main {
  public static void main() {
    Object t = null;
    System.out.println(t.toString());
  }
}"
  }
],
"tasks": [ "compile", "execute" ]
}""".trim()
                    )
                }
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.BadRequest.value)
            }
        }
        "should handle a java source that is actually a snippet" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.java",
    "contents": " 
System.out.println(\"Here\");
"
  }
],
"tasks": [ "compile", "execute" ],
"checkForSnippet": true
}""".trim()
                    )
                }
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.OK.value)

                val jeedResponse = Response.from(response.content)
                jeedResponse.completedTasks.size shouldBe 3
                jeedResponse.failedTasks.size shouldBe 0

                jeedResponse.completed.execution?.outputLines?.joinToString(separator = "\n") {
                    it.line
                }?.trim() shouldBe "Here"
            }
        }
        "should handle a kotlin source that is actually a snippet" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.kt",
    "contents": " 
println(\"Here\")
"
  }
],
"tasks": [ "kompile", "execute" ],
"checkForSnippet": true
}""".trim()
                    )
                }
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.OK.value)

                val jeedResponse = Response.from(response.content)
                jeedResponse.completedTasks.size shouldBe 3
                jeedResponse.failedTasks.size shouldBe 0

                jeedResponse.completed.execution?.outputLines?.joinToString(separator = "\n") {
                    it.line
                }?.trim() shouldBe "Here"
            }
        }
        "should reject neither source nor snippet request" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"tasks": [ "compile", "execute" ]
}""".trim()
                    )
                }
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.BadRequest.value)
            }
        }
        "should reject mapped source request" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"source": {
  "Main.java": " 
public class Main {
  public static void main() {
    System.out.println(\"Here\");
  }
}"
},
"tasks": [ "compile", "execute" ]
}""".trim()
                    )
                }
            }.apply {
                response.shouldHaveStatus(HttpStatusCode.BadRequest.value)
            }
        }
        "should accept good disassemble request" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.java",
    "contents": " 
public class Main {
  public static void main() {
    System.out.println(\"Hi\");
  }
}"
  }
],
"tasks": [ "compile", "disassemble" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 2
                    jeedResponse.completed.disassemble!!.disassemblies.keys shouldBe setOf("Main")
                    jeedResponse.failedTasks.size shouldBe 0
                }
            }
        }
        "should accept good kotlin disassemble request" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody(
                        """
{
"label": "test",
"sources": [
  {
    "path": "Main.kt",
    "contents": "
fun main() {
  println(\"Here\")
}"
  }
],
"tasks": [ "kompile", "disassemble" ]
}""".trim()
                    )
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 2
                    jeedResponse.completed.disassemble!!.disassemblies.keys shouldBe setOf("MainKt")
                    jeedResponse.failedTasks.size shouldBe 0
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
                    val status = Status.from(response.content)
                    status.versions.server shouldBe VERSION
                }
            }
        }
    }
}
