package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec

class TestTemplate : StringSpec({
    "should work with simple templates" {
        val templatedSource = Source.fromTemplates(mapOf(
                "Test.java" to "int i = 0;"
        ), mapOf(
                "Test.hbs" to "public class Question { {{{ contents }}} }"
        ))

        templatedSource.sources.keys shouldHaveSize 1
        templatedSource.originalSources.keys shouldHaveSize 1
        templatedSource.sources["Test.java"] shouldBe "public class Question { int i = 0; }"
        templatedSource.originalSources["Test.java"] shouldBe "int i = 0;"
    }
    "should work multiple times" {
        val templatedSource1 = Source.fromTemplates(mapOf(
                "Test.java" to "int i = 0;"
        ), mapOf(
                "Test.hbs" to "public class Question { {{{ contents }}} }"
        ))

        templatedSource1.sources.keys shouldHaveSize 1
        templatedSource1.originalSources.keys shouldHaveSize 1
        templatedSource1.sources["Test.java"] shouldBe "public class Question { int i = 0; }"
        templatedSource1.originalSources["Test.java"] shouldBe "int i = 0;"

        val templatedSource2 = Source.fromTemplates(mapOf(
                "Test.java" to "int i = 1;"
        ), mapOf(
                "Test.hbs" to "public class Question { {{{ contents }}} }"
        ))

        templatedSource2.sources.keys shouldHaveSize 1
        templatedSource2.originalSources.keys shouldHaveSize 1
        templatedSource2.sources["Test.java"] shouldBe "public class Question { int i = 1; }"
        templatedSource2.originalSources["Test.java"] shouldBe "int i = 1;"
    }
    "should work with indented templates" {
        val templatedSource = Source.fromTemplates(mapOf(
                "Test.java" to "int i = 0;"
        ), mapOf(
                "Test.hbs" to """
public class Question {
    public static void main() {
        {{{ contents }}}
    }
}""".trim()
        ))

        templatedSource.sources.keys shouldHaveSize 1
        templatedSource.originalSources.keys shouldHaveSize 1
        templatedSource.sources["Test.java"] shouldBe """
public class Question {
    public static void main() {
        int i = 0;
    }
}""".trim()
        templatedSource.originalSources["Test.java"] shouldBe "int i = 0;"
    }
    "should fail with broken templates" {
        val templatingFailed = shouldThrow<TemplatingFailed> {
            Source.fromTemplates(mapOf(
                    "Test" to "int i = 0;"
            ), mapOf(
                    "Test.hbs" to """
public class Question {
    public static void main() {
        {{{ contents }}
    }
}""".trim()
            ))
        }
        templatingFailed.errors shouldHaveSize 1
        templatingFailed.errors[0].location.source shouldBe "Test.hbs"
        templatingFailed.errors[0].location.line shouldBe 3
    }
})
