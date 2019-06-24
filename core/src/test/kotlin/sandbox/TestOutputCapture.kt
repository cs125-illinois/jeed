package edu.illinois.cs.cs125.jeed.core.sandbox

import edu.illinois.cs.cs125.jeed.core.*
import io.kotlintest.matchers.collections.shouldNotContain
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNot
import io.kotlintest.specs.StringSpec
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class TestOutputCapture : StringSpec({
    "should capture stdout" {
        val executionResult = Source.fromSnippet("""
System.out.println("Here");
            """.trim()).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Here")
        executionResult should haveStderr("")
    }
    "should capture stderr" {
        val executionResult = Source.fromSnippet("""
System.err.println("Here");
            """.trim()).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("")
        executionResult should haveStderr("Here")
    }
    "should capture stderr and stdout" {
        val executionResult = Source.fromSnippet("""
System.out.println("Here");
System.err.println("There");
            """.trim()).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Here")
        executionResult should haveStderr("There")
        executionResult should haveOutput("Here\nThere")
    }
    "should capture incomplete stderr and stdout lines" {
        val executionResult = Source.fromSnippet("""
System.out.print("Here");
System.err.print("There");
            """.trim()).compile().execute()
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
                    Source.fromSnippet("""
for (int i = 0; i < 32; i++) {
    for (long j = 0; j < 1024 * 1024 * 1024; j++);
}
                        """.trim()).compile().execute(SourceExecutionArguments(timeout = 1000L))
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
})
