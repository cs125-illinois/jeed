package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.specs.StringSpec
import io.kotlintest.*

class TestComplexity : StringSpec({
    "it should calculate complexity for snippets" {
        val complexityResult = Source.fromSnippet("""
int add(int i, int j) {
    return i + j;
}
int i = 0;
""".trim()).complexity()
        complexityResult.lookup("").complexity shouldBe 2
        println("Here")
    }
})
