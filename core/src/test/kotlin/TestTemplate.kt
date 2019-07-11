package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec

class TestTemplate : StringSpec({
    "should work with simple templates" {
        val templatedSource = Source.fromTemplates(mapOf(
                "Test" to "int i = 0;"
        ), mapOf(
                "Test" to "public class Question { {{{ contents }}} }"
        ))

        templatedSource.sources.keys shouldHaveSize 1
        templatedSource.originalSources.keys shouldHaveSize 1
        templatedSource.sources["Test"] shouldBe "public class Question { int i = 0; }"
        templatedSource.originalSources["Test"] shouldBe "int i = 0;"
    }
    "should work with indented templates" {
        val templatedSource = Source.fromTemplates(mapOf(
                "Test" to "int i = 0;"
        ), mapOf(
                "Test" to """
public class Question {
    public static void main() {
        {{{ contents }}}
    }
}""".trim()
        ))

        templatedSource.sources.keys shouldHaveSize 1
        templatedSource.originalSources.keys shouldHaveSize 1
        templatedSource.sources["Test"] shouldBe """
public class Question {
    public static void main() {
        int i = 0;
    }
}""".trim()
        templatedSource.originalSources["Test"] shouldBe "int i = 0;"
    }
})
