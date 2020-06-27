package edu.illinois.cs.cs125.jeed.core

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
})
