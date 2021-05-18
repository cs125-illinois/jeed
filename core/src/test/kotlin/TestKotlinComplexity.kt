package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec

class TestKotlinComplexity : StringSpec({
    "f: should compute complexity for Kotlin top-level method" {
        Source.fromKotlin(
            """
fun main(first: Int, second: String, third: Blah?): Int {
  return
}""".trim()
        ).complexity()
    }
})
