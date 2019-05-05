import edu.illinois.cs.cs125.janini.*

import io.kotlintest.specs.StringSpec
import io.kotlintest.*

class TestExecute : StringSpec({
    "should execute snippets" {
        val executionResult = Source(
"""int i = 0;
i++;
""").compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveOutput()
    }

    "should execute sources" {
        val executionResult = Source(mapOf(
                "Test" to
                        """
public class Test {
    public static void main() {
        var i = 0;
    }
}
""")).compile().execute(ExecutionParameters("Test", "main()"))
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveOutput()
    }

    "should capture stdout" {
        val executionResult = Source(
"""System.out.println("Here");
""").compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Here")
        executionResult should haveStderr("")
    }

    "should capture stderr" {
        val executionResult = Source(
"""System.err.println("Here");
""").compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("")
        executionResult should haveStderr("Here")
    }
    "should capture stderr and stdout" {
        val executionResult = Source(
                """System.out.println("Here");
System.err.println("There");
""").compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Here")
        executionResult should haveStderr("There")
        executionResult should haveOutput("Here\nThere")
    }

    "should timeout correctly on snippet" {
        val executionResult = Source(
"""
int i = 0;
while (true) {
    i++;
}""").compile().execute()
        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput()
    }

    "should timeout correctly on sources" {
        val executionResult = Source(mapOf("Foo" to
"""
public class Foo {
    public static void blah() {
        int i = 0;
        while (true) {
            i++;
        }
    }
}""")).compile().execute(ExecutionParameters("Foo", "blah()"))
        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput()
    }

    "should return output after timeout" {
        val executionResult = Source(
                """
System.out.println("Here");
int i = 0;
while (true) {
    i++;
}""").compile().execute()
        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput("Here")
    }
})

fun haveCompleted() = object : Matcher<ExecutionResult> {
    override fun test(value: ExecutionResult): Result {
        return Result(
                value.completed,
                "Code should have run",
                "Code should not have run"
        )
    }
}
fun haveTimedOut() = object : Matcher<ExecutionResult> {
    override fun test(value: ExecutionResult): Result {
        return Result(
                value.timedOut,
                "Code should have timed out",
                "Code should not have timed out"
        )
    }
}
fun haveOutput(output: String = "") = object : Matcher<ExecutionResult> {
    override fun test(value: ExecutionResult): Result {
        val actualOutput = value.output().trim()
        return Result(
                actualOutput == output,
                "Expected output $output, found $actualOutput",
                "Expected to not find output $actualOutput"
        )
    }
}

fun haveStdout(output: String) = object : Matcher<ExecutionResult> {
    override fun test(value: ExecutionResult): Result {
        val actualOutput = value.stdout().trim()
        return Result(
                actualOutput == output,
                "Expected stdout $output, found $actualOutput",
                "Expected to not find stdout $actualOutput"
        )
    }
}
fun haveStderr(output: String) = object : Matcher<ExecutionResult> {
    override fun test(value: ExecutionResult): Result {
        val actualOutput = value.stderr().trim()
        return Result(
                actualOutput == output,
                "Expected stderr $output, found $actualOutput",
                "Expected to not find stderr $actualOutput"
        )
    }
}
