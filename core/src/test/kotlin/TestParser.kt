package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec

class TestParser : StringSpec({
    "it should parse kotlin code" {
        Source(
            mapOf(
                "Main.kt" to """
class Person {
  val name: String = ""
  val age: Double
  init {
    age = 0.0
  }
}
""".trim()
            )
        ).getParsed("Main.kt").tree
    }
    "it should distinguish between different kinds of sources" {
        val javaSnippet =
            """System.out.println("Hello, world!");"""
        val javaSource =
            """
public class Example {}
            """.trimIndent()
        val kotlinSnippet =
            """println("Hello, world!")"""
        val kotlinSource =
            """
fun main() {
  println("Hello, world!")
}
            """.trimIndent()
        javaSnippet.distinguish("java") shouldBe SourceType.JAVA_SNIPPET
        javaSource.distinguish("java") shouldBe SourceType.JAVA_SOURCE
        kotlinSnippet.distinguish("kotlin") shouldBe SourceType.KOTLIN_SNIPPET
        kotlinSource.distinguish("kotlin") shouldBe SourceType.KOTLIN_SOURCE
    }
})
