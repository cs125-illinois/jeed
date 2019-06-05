import edu.illinois.cs.cs125.jeed.*

import io.kotlintest.specs.StringSpec
import io.kotlintest.*

class TestExecute : StringSpec({
    "should execute snippets" {
        val executionResult = Source.fromSnippet(
"""int i = 0;
i++;
""".trim()).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveOutput()
    }
    "should execute snippets that include class definitions" {
        val executionResult = Source.fromSnippet(
                """
public class Foo {
    int i = 0;
}
int i = 0;
i++;
Foo foo = new Foo();
foo.i = 4;
System.out.println("Done");
""".trim()).compile().execute(ExecutionArguments())

        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveOutput("Done")
    }
    "should execute snippets that include multiple class definitions" {
        val executionResult = Source.fromSnippet(
                """
public class Bar {
}
public class Foo {
    int i = 0;
}
int i = 0;
i++;
Foo foo = new Foo();
foo.i = 4;
Bar bar = new Bar();
System.out.println("Done");
""".trim()).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveOutput("Done")
    }
    "should execute the right class in snippets that include multiple class definitions" {
        val executionResult = Source.fromSnippet(
                """
public class Bar {
    public static void main() {
        System.out.println("Alternate");
    }
}
public class Foo {
    int i = 0;
}
int i = 0;
i++;
Foo foo = new Foo();
foo.i = 4;
Bar bar = new Bar();
System.out.println("Done");
""".trim()).compile().execute(ExecutionArguments(className = "Bar"))
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveOutput("Alternate")
    }
    "should execute sources" {
        val executionResult = Source(mapOf(
                "Test" to
                        """
public class Main {
    public static void main() {
        var i = 0;
        System.out.println("Here");
    }
}
""".trim())).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Here")
    }
    "should execute multiple sources with dependencies" {
        val executionResult = Source(mapOf(
                "Test" to
                        """
public class Main {
    public static void main() {
        var i = 0;
        Foo.foo();
    }
}
""".trim(),
                "Foo" to """
public class Foo {
    public static void foo() {
        System.out.println("Foo");
    }
}
""".trim())).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Foo")
    }
    "should capture stdout" {
        val executionResult = Source.fromSnippet(
"""System.out.println("Here");
""".trim()).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Here")
        executionResult should haveStderr("")
    }
    "should capture stderr" {
        val executionResult = Source.fromSnippet(
"""System.err.println("Here");
""".trim()).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("")
        executionResult should haveStderr("Here")
    }
    "should capture stderr and stdout" {
        val executionResult = Source.fromSnippet(
                """System.out.println("Here");
System.err.println("There");
""".trim()).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Here")
        executionResult should haveStderr("There")
        executionResult should haveOutput("Here\nThere")
    }
    "should timeout correctly on snippet" {
        val executionResult = Source.fromSnippet(
"""
int i = 0;
while (true) {
    i++;
}""".trim()).compile().execute()
        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput()
    }
    "should timeout correctly on sources" {
        val executionResult = Source(mapOf("Foo" to
"""
public class Main {
    public static void main() {
        int i = 0;
        while (true) {
            i++;
        }
    }
}""".trim())).compile().execute()
        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput()
    }
    "should return output after timeout" {
        val executionResult = Source.fromSnippet(
                """
System.out.println("Here");
int i = 0;
while (true) {
    i++;
}""".trim()).compile().execute()
        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput("Here")
    }
    "should import libraries properly" {
        val executionResult = Source.fromSnippet(
                """
import java.util.List;
import java.util.ArrayList;

List<Integer> list = new ArrayList<>();
list.add(8);
System.out.println(list.get(0));
""".trim()).compile().execute()

        executionResult should haveCompleted()
        executionResult should haveOutput("8")
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
