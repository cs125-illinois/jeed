package edu.illinois.cs.cs125.jeed.core.sandbox

import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments
import edu.illinois.cs.cs125.jeed.core.compile
import edu.illinois.cs.cs125.jeed.core.execute
import edu.illinois.cs.cs125.jeed.core.findClassMethod
import edu.illinois.cs.cs125.jeed.core.fromSnippet
import edu.illinois.cs.cs125.jeed.core.haveCompleted
import edu.illinois.cs.cs125.jeed.core.haveOutput
import edu.illinois.cs.cs125.jeed.core.haveStderr
import edu.illinois.cs.cs125.jeed.core.haveStdout
import edu.illinois.cs.cs125.jeed.core.haveTimedOut
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class TestOutputCapture : StringSpec({
    "should capture stdout" {
        val executionResult = Source.fromSnippet(
            """
System.out.println("Here");
            """.trim()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Here")
        executionResult should haveStderr("")
    }
    "should capture stderr" {
        val executionResult = Source.fromSnippet(
            """
System.err.println("Here");
            """.trim()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("")
        executionResult should haveStderr("Here")
    }
    "should capture stderr and stdout" {
        val executionResult = Source.fromSnippet(
            """
System.out.println("Here");
System.err.println("There");
            """.trim()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Here")
        executionResult should haveStderr("There")
        executionResult should haveOutput("Here\nThere")
    }
    "should capture incomplete stderr and stdout lines" {
        val executionResult = Source.fromSnippet(
            """
System.out.print("Here");
System.err.print("There");
            """.trim()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Here")
        executionResult should haveStderr("There")
        executionResult should haveOutput("Here\nThere")
    }
    "should not intermingle unrelated thread output" {
        val combinedOutputStream = ByteArrayOutputStream()
        val combinedPrintStream = PrintStream(combinedOutputStream)
        val originalStdout = System.out
        val originalStderr = System.err
        System.setOut(combinedPrintStream)
        System.setErr(combinedPrintStream)

        (0..8).toList().map {
            if (it % 2 == 0) {
                async {
                    Source.fromSnippet(
                        """
for (int i = 0; i < 32; i++) {
    for (long j = 0; j < 1024 * 1024 * 1024; j++);
}
                        """.trim()
                    ).compile().execute(SourceExecutionArguments(timeout = 1000L))
                }
            } else {
                async {
                    for (j in 1..512) {
                        println("Bad")
                        System.err.println("Bad")
                        delay(1L)
                    }
                }
            }
        }.map { it.await() }.filterIsInstance<Sandbox.TaskResults<out Any?>>().forEach { executionResult ->
            executionResult should haveTimedOut()
            executionResult.outputLines.map { it.line } shouldNotContain "Bad"
            executionResult.stderrLines.map { it.line } shouldNotContain "Bad"
        }
        System.setOut(originalStdout)
        System.setErr(originalStderr)

        val unrelatedOutput = combinedOutputStream.toString()
        unrelatedOutput.lines().filter { it == "Bad" }.size shouldBe 4 * 2 * 512
    }
    "should redirect output to trusted task properly" {
        val compiledSource = Source.fromSnippet(
            """
System.out.println("Here");
System.out.println("There");
System.err.println("There");
            """.trim()
        ).compile()
        val executionResult = Sandbox.execute(compiledSource.classLoader) { (classLoader, redirectOutput) ->
            redirectOutput {
                classLoader.findClassMethod().invoke(null)
            }.also {
                assert(it.stdout == "Here\nThere\n")
                assert(it.stderr == "There\n")
            }
        }
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Here\nThere")
        executionResult should haveStderr("There")
    }
    "should redirect output to trusted task properly with print" {
        val compiledSource = Source.fromSnippet(
            """
System.out.println("Here");
System.out.print("There");
System.err.print("There");
            """.trim()
        ).compile()
        val executionResult = Sandbox.execute(compiledSource.classLoader) { (classLoader, redirectOutput) ->
            redirectOutput {
                classLoader.findClassMethod().invoke(null)
            }.also {
                assert(it.stdout == "Here\nThere")
                assert(it.stderr == "There")
            }
            redirectOutput {
                classLoader.findClassMethod().invoke(null)
            }.also {
                assert(it.stdout == "Here\nThere")
                assert(it.stderr == "There")
            }
        }
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Here\nThereHere\nThere")
        executionResult should haveStderr("ThereThere")
    }
    "should handle null print arguments" {
        val executionResult = Source.fromSnippet(
            """
int[] output = null;
System.out.println(output);
            """.trim()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("null")
        executionResult should haveStderr("")
    }
    "should handle print without newline" {
        val executionResult = Source.fromSnippet(
            """
System.out.print("Hello");
            """.trim()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Hello")
    }
})
