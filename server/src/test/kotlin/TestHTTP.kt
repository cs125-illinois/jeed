@file:Suppress("MagicNumber")

package edu.illinois.cs.cs125.jeed.server

import com.mongodb.MongoClient
import com.mongodb.MongoClientURI
import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.TestResult
import io.kotlintest.assertions.ktor.shouldHaveStatus
import io.kotlintest.matchers.numerics.shouldBeGreaterThan
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.StringSpec
import io.ktor.application.Application
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.setBody
import io.ktor.server.testing.withTestApplication
import org.bson.BsonDocument

@Suppress("LargeClass")
class TestHTTP : StringSpec() {
    override fun beforeSpec(spec: Spec) {
        configuration[TopLevel.mongodb]?.let {
            val mongoUri = MongoClientURI(it)
            val database = mongoUri.database ?: require { "MONGO must specify database to use" }
            val collection = "${configuration[TopLevel.Mongo.collection]}-test"
            Request.mongoCollection = MongoClient(mongoUri)
                .getDatabase(database)
                .getCollection(collection, BsonDocument::class.java)
        }
    }

    override fun beforeTest(testCase: TestCase) {
        Request.mongoCollection?.drop()
        Request.mongoCollection?.countDocuments() shouldBe 0
    }

    override fun afterTest(testCase: TestCase, result: TestResult) {
        Request.mongoCollection?.drop()
        Request.mongoCollection?.countDocuments() shouldBe 0
    }

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
                    Request.mongoCollection?.countDocuments() shouldBe 1

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completed.execution?.klass shouldBe "Main"
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
                    Request.mongoCollection?.countDocuments() shouldBe 1

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
                    Request.mongoCollection?.countDocuments() shouldBe 1

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
                    Request.mongoCollection?.countDocuments() shouldBe 1

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completed.execution?.klass shouldBe "MainKt"
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
                    Request.mongoCollection?.countDocuments() shouldBe 1

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
                    Request.mongoCollection?.countDocuments() shouldBe 1

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
                    Request.mongoCollection?.countDocuments() shouldBe 1

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
                    Request.mongoCollection?.countDocuments() shouldBe 0
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
    "path": "Main.hbs",
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
                    Request.mongoCollection?.countDocuments() shouldBe 1

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
                    Request.mongoCollection?.countDocuments() shouldBe 1

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 1
                    jeedResponse.failedTasks.size shouldBe 0
                    println(jeedResponse.completed.complexity)
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
                    Request.mongoCollection?.countDocuments() shouldBe 1

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 0
                    jeedResponse.failedTasks.size shouldBe 1
                    jeedResponse.failed.snippet?.errors?.size ?: 0 shouldBeGreaterThan 0
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
    "path": "Main.hbs",
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
                    Request.mongoCollection?.countDocuments() shouldBe 1

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 0
                    jeedResponse.failedTasks.size shouldBe 1
                    jeedResponse.failed.template?.errors?.size ?: 0 shouldBeGreaterThan 0
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
                    Request.mongoCollection?.countDocuments() shouldBe 1

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 0
                    jeedResponse.failedTasks.size shouldBe 1
                    jeedResponse.failed.compilation?.errors?.size ?: 0 shouldBeGreaterThan 0
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
                    Request.mongoCollection?.countDocuments() shouldBe 1

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 0
                    jeedResponse.failedTasks.size shouldBe 1
                    jeedResponse.failed.kompilation?.errors?.size ?: 0 shouldBeGreaterThan 0
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
                    Request.mongoCollection?.countDocuments() shouldBe 1

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 1
                    jeedResponse.failedTasks.size shouldBe 1
                    jeedResponse.failed.checkstyle?.errors?.size ?: 0 shouldBeGreaterThan 0
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
                    Request.mongoCollection?.countDocuments() shouldBe 1

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 3
                    jeedResponse.failedTasks.size shouldBe 0
                    jeedResponse.completed.checkstyle?.errors?.size ?: 0 shouldBeGreaterThan 0
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
                    Request.mongoCollection?.countDocuments() shouldBe 1

                    val jeedResponse = Response.from(response.content)
                    jeedResponse.completedTasks.size shouldBe 1
                    jeedResponse.failedTasks.size shouldBe 1
                    jeedResponse.failed.execution?.threw shouldNotBe ""
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
                Request.mongoCollection?.countDocuments() shouldBe 0
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
                Request.mongoCollection?.countDocuments() shouldBe 0
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
                Request.mongoCollection?.countDocuments() shouldBe 0
            }
        }
        "should reject unauthorized request" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody("broken")
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.BadRequest.value)
                    Request.mongoCollection?.countDocuments() shouldBe 0
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
                    Request.mongoCollection?.countDocuments() shouldBe 0
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
