package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

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
    "it should identify a snippet" {
        """
class Flip {
  boolean state;
  Flip(boolean start) {
    state = start;
  }
  boolean flop() {
    state = !state;
    return state;
  }
}
Flip f = new Flip(true);
System.out.println(f.flop());
System.out.println(f.flop());""".trim().distinguish("java") shouldBe SourceType.JAVA_SNIPPET
    }
})
