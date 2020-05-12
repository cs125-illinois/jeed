package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.Matcher
import io.kotlintest.MatcherResult
import io.kotlintest.matchers.file.exist
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNot
import io.kotlintest.specs.StringSpec
import java.io.File

class TestContainer : StringSpec() {
    init {
        "it should perform path inside calculations properly" {
            File("/test/me").isInside(File("/test")) shouldBe true
        }
        "it should eject into a temporary directory" {
            val compiledSource = Source.fromSnippet("""System.out.println("Hello, world!");""").compile()
            withTempDir {
                val expectedFile = "${(compiledSource.source as Snippet).wrappedClassName}.class"
                File(it, expectedFile) shouldNot exist()
                compiledSource.eject(it)
                File(it, expectedFile) should exist()
            }
        }
        "it should run a simple program in a container" {
            val runResults = Source.fromSnippet("""System.out.println("Hello, world!");""")
                .compile()
                .run()

            runResults should containerHaveCompleted()
            runResults should containerHaveOutput("Hello, world!")
        }
        "it should shut down a runaway container" {
            val runResults = Source.fromSnippet(
                """
while (true) {}
            """.trim()
            )
                .compile()
                .run()
            runResults should containerHaveTimedOut()
        }
        "it should run a simple Kotlin program in a container" {
            val runResults = Source.fromSnippet(
                """println("Hello, world!")""",
                SnippetArguments(fileType = Source.FileType.KOTLIN)
            )
                .kompile()
                .run()

            runResults should containerHaveCompleted()
            runResults should containerHaveOutput("Hello, world!")
        }
        "it should run a multiple class file program in a container" {
            val runResults = Source(
                mapOf(
                    "Main.java" to """
import example.Test;

public class Main {
  public static void entry() {
    Test test = new Test();
    System.out.println(test);
  }
}""".trim(),
                    "example/Test.java" to """
package example;

public class Test {
  public String toString() {
    return "Hello, world!";
  }
}""".trim()
                )
            )
                .compile()
                .run(executionArguments = ContainerExecutionArguments(method = "entry()"))

            runResults should containerHaveCompleted()
            runResults should containerHaveOutput("Hello, world!")
        }
    }
}

fun containerHaveCompleted() = object : Matcher<ContainerExecutionResults> {
    override fun test(value: ContainerExecutionResults): MatcherResult {
        return MatcherResult(
            value.completed,
            "Code should have run",
            "Code should not have run"
        )
    }
}

fun containerHaveTimedOut() = object : Matcher<ContainerExecutionResults> {
    override fun test(value: ContainerExecutionResults): MatcherResult {
        return MatcherResult(
            value.timeout,
            "Code should have timed out",
            "Code should not have timed out"
        )
    }
}

fun containerHaveOutput(output: String = "") = object : Matcher<ContainerExecutionResults> {
    override fun test(value: ContainerExecutionResults): MatcherResult {
        val actualOutput = value.output.trim()
        return MatcherResult(
            actualOutput == output,
            "Expected output $output, found $actualOutput",
            "Expected to not find output $actualOutput"
        )
    }
}
