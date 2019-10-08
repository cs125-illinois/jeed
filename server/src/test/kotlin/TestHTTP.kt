package edu.illinois.cs.cs125.jeed.server

import com.mongodb.MongoClient
import com.mongodb.MongoClientURI
import io.kotlintest.Spec
import io.kotlintest.TestCase
import io.kotlintest.TestResult
import io.kotlintest.assertions.ktor.shouldHaveStatus
import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec
import io.ktor.application.Application
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.withTestApplication
import io.ktor.server.testing.handleRequest
import io.ktor.server.testing.setBody
import org.bson.BsonDocument

class TestHTTP : StringSpec() {
    override fun beforeSpec(spec: Spec) {
        configuration[TopLevel.mongodb]?.let {
            val mongoUri = MongoClientURI(it)
            val database = mongoUri.database ?: require {"MONGO must specify database to use" }
            val collection = "${configuration[TopLevel.Mongo.collection]}-test"
            Job.mongoCollection = MongoClient(mongoUri).getDatabase(database).getCollection(collection, BsonDocument::class.java)
        }
    }

    override fun beforeTest(testCase: TestCase) {
        Job.mongoCollection?.drop()
        Job.mongoCollection?.countDocuments() shouldBe 0
    }
    override fun afterTest(testCase: TestCase, result: TestResult) {
        Job.mongoCollection?.drop()
        Job.mongoCollection?.countDocuments() shouldBe 0
    }

    init {
        "should accept good snippet request" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Post, "/") {
                    addHeader("content-type", "application/json")
                    setBody("""
{
"label": "test",
"snippet": "System.out.println(\"Here\");",
"tasks": [ "execute" ],
"waitForSave": true
}""".trim())
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)
                    Job.mongoCollection?.countDocuments() shouldBe 1
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
"tasks": [ "execute" ],
"waitForSave": true
}""".trim())
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)
                    Job.mongoCollection?.countDocuments() shouldBe 1
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
"tasks": [ "checkstyle", "execute" ],
"waitForSave": true
}""".trim())
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)
                    Job.mongoCollection?.countDocuments() shouldBe 1
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
"tasks": [ "template", "execute" ],
"waitForSave": true
}""".trim())
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)
                    Job.mongoCollection?.countDocuments() shouldBe 1
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
                    Job.mongoCollection?.countDocuments() shouldBe 0
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
                    Job.mongoCollection?.countDocuments() shouldBe 0
                }
            }
        }
        "should provide info in response to GET" {
            withTestApplication(Application::jeed) {
                handleRequest(HttpMethod.Get, "/") {
                    addHeader("content-type", "application/json")
                }.apply {
                    response.shouldHaveStatus(HttpStatusCode.OK.value)
                }
            }
        }
    }
}
