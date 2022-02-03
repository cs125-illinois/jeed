package edu.illinois.cs.cs125.jeed.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.string.shouldContain

class TestCheckstyle : StringSpec({
    "should retrieve the indentation level properly" {
        defaultChecker!!.indentation shouldBe 4
    }
    "should check strings without errors" {
        val checkstyleResult = Source.fromSnippet(
            """
int i = 0;
""".trim()
        ).checkstyle()

        checkstyleResult shouldNot haveCheckstyleErrors()
    }
    "it should identify checkstyle errors in strings" {
        val checkstyleErrors = Source.fromSnippet(
            """
int i = 0;
int y =1;
""".trim()
        ).checkstyle()

        checkstyleErrors should haveCheckstyleErrors()
        checkstyleErrors should haveCheckstyleErrorAt(line = 2)
    }
    "should identify checkstyle errors in snippet results" {
        val checkstyleErrors = Source.fromSnippet(
            """
int i = 0;
int y = 1;
int add(int a, int b) {
    return a+ b;
}
add(i, y);
""".trim()
        ).checkstyle()

        checkstyleErrors should haveCheckstyleErrors()
        checkstyleErrors should haveCheckstyleErrorAt(line = 4)
    }
    "should identify checkstyle errors in snippet static results" {
        val checkstyleErrors = Source.fromSnippet(
            """
int i = 0;
int y = 1;
static int add(int a, int b) {
    return a+ b;
}
add(i, y);
""".trim()
        ).checkstyle()

        checkstyleErrors should haveCheckstyleErrors()
        checkstyleErrors should haveCheckstyleErrorAt(line = 4)
    }
    "should identify checkstyle errors in snippet results with modifiers" {
        val checkstyleErrors = Source.fromSnippet(
            """
int i = 0;
int y = 1;
public int add(int a, int b) {
    return a+ b;
}
add(i, y);
""".trim()
        ).checkstyle()

        checkstyleErrors should haveCheckstyleErrors()
        checkstyleErrors should haveCheckstyleErrorAt(line = 4)
    }
    "should check all sources by default" {
        val checkstyleErrors = Source(
            mapOf(
                "First.java" to """
public class First{
}
                """.trim(),
                "Second.java" to """
public class Second {
}
                """.trim()
            )
        ).checkstyle()

        checkstyleErrors should haveCheckstyleErrors()
        checkstyleErrors.errors shouldHaveSize 1
        checkstyleErrors should haveCheckstyleErrorAt(source = "First.java", line = 1)
    }
    "should ignore sources not configured to check" {
        val checkstyleErrors = Source(
            mapOf(
                "First.java" to """
public class First{
}
                """.trim(),
                "Second.java" to """
public class Second {
}
                """.trim()
            )
        ).checkstyle(CheckstyleArguments(sources = setOf("Second.java")))

        checkstyleErrors shouldNot haveCheckstyleErrors()
    }
    "should check indentation properly" {
        val checkstyleErrors = Source.fromSnippet(
            """
public int add(int a, int b) {
   return a + b;
 }
""".trim()
        ).checkstyle()
        checkstyleErrors should haveCheckstyleErrors()
        checkstyleErrors.errors shouldHaveSize 2
        checkstyleErrors should haveCheckstyleErrorAt(line = 2)
        checkstyleErrors should haveCheckstyleErrorAt(line = 3)
    }
    "should adjust indentation properly for snippets" {
        val checkstyleErrors = Source.fromSnippet(
            """
public int add(int a, int b) {
   return a + b;
}
""".trim()
        ).checkstyle()
        checkstyleErrors should haveCheckstyleErrors()
        checkstyleErrors.errors shouldHaveSize 1
        checkstyleErrors should haveCheckstyleErrorAt(line = 2)
        checkstyleErrors.errors.first().let {
            it.message shouldContain "indentation level 3"
            it.message shouldContain "should be 4"
        }
    }
    "should throw when configured" {
        val checkstyleError = shouldThrow<CheckstyleFailed> {
            Source.fromSnippet(
                """
public int add(int a,int b) {
    return a+ b;
}
""".trim()
            ).checkstyle(CheckstyleArguments(failOnError = true))
        }
        checkstyleError.errors shouldHaveSize 2
        checkstyleError.errors[0].location.line shouldBe 1
        checkstyleError.errors[1].location.line shouldBe 2
    }
    "should not fail on Java switch" {
        val checkstyleResult = Source(
            mapOf(
                "Test.java" to """
public class Test {
    public static String testYieldKeyword(int switchArg) {
        return switch (switchArg) {
            case 1, 2: yield "works";
            case 3: yield "oh boy";
            default: yield "testing";
        };
    }
    public static void main() {
        System.out.println(testYieldKeyword(1));
    }
}
                """.trim()
            )
        ).checkstyle()

        checkstyleResult shouldNot haveCheckstyleErrors()
    }
    "should not fail on Java instanceof pattern" {
        val checkstyleResult = Source(
            mapOf(
                "Test.java" to """
public class Test {
    public static void main() {
        Object o = new String("");
        if (o instanceof String s) {
            System.out.println(s.length());
        }
    }
}
                """.trim()
            )
        ).checkstyle()

        checkstyleResult shouldNot haveCheckstyleErrors()
    }
    "should not fail on Java records" {
        val checkstyleResult = Source(
            mapOf(
                "Test.java" to """
record Range(int lo, int hi) {
    public Range {
        if (lo > hi) {
            throw new IllegalArgumentException(String.format("(%d,%d)", lo, hi));
        }
    }
}
public class Test {
    public static void main() {
        Object o = new Range(0, 10);
    }
}
                """.trim()
            )
        ).checkstyle()

        checkstyleResult shouldNot haveCheckstyleErrors()
    }
    "should ignore errors on unmapped lines when configured" {
        val templatedSource = Source.fromTemplates(
            mapOf(
                "Test.java" to "    int i = 0;"
            ),
            mapOf(
                "Test.java.hbs" to """
  public class Question {
{{{ contents }}}
}
"""
            )
        )
        val checkstyleResult = templatedSource.checkstyle()
        checkstyleResult shouldNot haveCheckstyleErrors()
        shouldThrow<SourceMappingException> {
            templatedSource.checkstyle(CheckstyleArguments(skipUnmapped = false))
        }
    }
    "should load checkers from files" {
        val otherChecker = ConfiguredChecker(object {}::class.java.getResource("/checkstyle/indent2.xml")!!.readText())
        otherChecker.indentation shouldBe 2
    }
    "should work with alternate checkers" {
        val otherChecker = ConfiguredChecker(object {}::class.java.getResource("/checkstyle/indent2.xml")!!.readText())
        Source.fromSnippet(
            """
public int add(int a, int b) {
  return a + b;
}
""".trim(),
            SnippetArguments(indent = 2)
        ).checkstyle(checker = otherChecker).also {
            it shouldNot haveCheckstyleErrors()
        }
    }
})

fun haveCheckstyleErrors() = object : Matcher<CheckstyleResults> {
    override fun test(value: CheckstyleResults): MatcherResult {
        return MatcherResult(
            value.errors.isNotEmpty(),
            { "should have checkstyle errors" },
            { "should not have checkstyle errors" }
        )
    }
}

fun haveCheckstyleErrorAt(source: String = SNIPPET_SOURCE, line: Int) = object : Matcher<CheckstyleResults> {
    override fun test(value: CheckstyleResults): MatcherResult {
        return MatcherResult(
            value.errors.any { it.location.source == source && it.location.line == line },
            { "should have checkstyle error on line $line" },
            { "should not have checkstyle error on line $line" }
        )
    }
}
