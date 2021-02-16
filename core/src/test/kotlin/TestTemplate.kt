package edu.illinois.cs.cs125.jeed.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot

class TestTemplate : StringSpec({
    "should work with simple templates" {
        val templatedSource = Source.fromTemplates(
            mapOf(
                "Test.java" to "int i = 0;"
            ),
            mapOf(
                "Test.java.hbs" to "public class Question { {{{ contents }}} }"
            )
        )

        templatedSource.sources.keys shouldHaveSize 1
        templatedSource.originalSources.keys shouldHaveSize 1
        templatedSource.sources["Test.java"] shouldBe "public class Question { int i = 0; }"
        templatedSource.originalSources["Test.java"] shouldBe "int i = 0;"
    }
    "should work multiple times" {
        val templatedSource1 = Source.fromTemplates(
            mapOf(
                "Test.java" to "int i = 0;"
            ),
            mapOf(
                "Test.java.hbs" to "public class Question { {{{ contents }}} }"
            )
        )

        templatedSource1.sources.keys shouldHaveSize 1
        templatedSource1.originalSources.keys shouldHaveSize 1
        templatedSource1.sources["Test.java"] shouldBe "public class Question { int i = 0; }"
        templatedSource1.originalSources["Test.java"] shouldBe "int i = 0;"

        val templatedSource2 = Source.fromTemplates(
            mapOf(
                "Test.java" to "int i = 1;"
            ),
            mapOf(
                "Test.java.hbs" to "public class Question { {{{ contents }}} }"
            )
        )

        templatedSource2.sources.keys shouldHaveSize 1
        templatedSource2.originalSources.keys shouldHaveSize 1
        templatedSource2.sources["Test.java"] shouldBe "public class Question { int i = 1; }"
        templatedSource2.originalSources["Test.java"] shouldBe "int i = 1;"
    }
    "should work with indented templates" {
        val templatedSource = Source.fromTemplates(
            mapOf(
                "Test.java" to "int i = 0;"
            ),
            mapOf(
                "Test.java.hbs" to """
public class Question {
    public static void main() {
        {{{ contents }}}
    }
}""".trim()
            )
        )

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
    "should work with multi-line indented templates" {
        val templatedSource = Source.fromTemplates(
            mapOf(
                "Test.java" to """
int i = 0;
i += 4;
""".trim()
            ),
            mapOf(
                "Test.java.hbs" to """
public class Question {
    public static void main() {
        {{{ contents }}}
    }
}""".trim()
            )
        )

        templatedSource.sources.keys shouldHaveSize 1
        templatedSource.originalSources.keys shouldHaveSize 1
        templatedSource.sources["Test.java"] shouldBe """
public class Question {
    public static void main() {
        int i = 0;
        i += 4;
    }
}""".trim()
        templatedSource.originalSources["Test.java"] shouldBe """
int i = 0;
i += 4;
""".trim()
    }
    "should fail with broken templates" {
        val templatingFailed = shouldThrow<TemplatingFailed> {
            Source.fromTemplates(
                mapOf(
                    "Test.java" to "int i = 0;"
                ),
                mapOf(
                    "Test.java.hbs" to """
public class Question {
    public static void main() {
        {{{ contents }}
    }
}""".trim()
                )
            )
        }
        templatingFailed.errors shouldHaveSize 1
        templatingFailed.errors[0].location.source shouldBe "Test.java.hbs"
        templatingFailed.errors[0].location.line shouldBe 3
    }
    "should remap line numbers properly" {
        val templatedSource = Source.fromTemplates(
            mapOf(
                "Test.java" to "int i = ;"
            ),
            mapOf(
                "Test.java.hbs" to """
public class Question {
    public static void main() {
        {{{ contents }}}
    }
}""".trim()
            )
        )

        templatedSource.sources.keys shouldHaveSize 1
        templatedSource.originalSources.keys shouldHaveSize 1
        templatedSource.sources["Test.java"] shouldBe """
public class Question {
    public static void main() {
        int i = ;
    }
}""".trim()
        templatedSource.originalSources["Test.java"] shouldBe "int i = ;"

        val compilationFailed = shouldThrow<CompilationFailed> {
            templatedSource.compile()
        }
        compilationFailed.errors shouldHaveSize 1
        compilationFailed.errors[0].location!!.line shouldBe 1
        compilationFailed.errors[0].location!!.column shouldBe 9
    }
    "should remap Kotlin line numbers properly" {
        val templatedSource = Source.fromTemplates(
            mapOf(
                "Test.kt" to "val i = "
            ),
            mapOf(
                "Test.kt.hbs" to """
class Question {
    companion object {
        fun main() {
            {{{ contents }}}
        }
    }
}""".trim()
            )
        )

        templatedSource.sources.keys shouldHaveSize 1
        templatedSource.originalSources.keys shouldHaveSize 1
        templatedSource.sources["Test.kt"] shouldBe """
class Question {
    companion object {
        fun main() {
            val i = 
        }
    }
}""".trim()
        templatedSource.originalSources["Test.kt"] shouldBe "val i = "

        val compilationFailed = shouldThrow<CompilationFailed> {
            templatedSource.kompile()
        }
        compilationFailed.errors shouldHaveSize 1
        compilationFailed.errors[0].location!!.line shouldBe 1
        compilationFailed.errors[0].location!!.column shouldBe 8
    }
    "should remap exception line numbers properly" {
        val source = Source.fromTemplates(
            mapOf(
                "Test.java" to "String s = null;\ns.length();"
            ),
            mapOf(
                "Test.java.hbs" to """
public class Question {
    public static void main() {
        {{{ contents }}}
    }
}""".trim()
            )
        )
        val executionResult = source.compile().execute()
        executionResult shouldNot haveCompleted()
        executionResult.threw?.getStackTraceForSource(source)!!.lines()[1].trim() shouldBe "at Question.main(:2)"
    }
})
