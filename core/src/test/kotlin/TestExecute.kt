package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.Matcher
import io.kotlintest.MatcherResult
import io.kotlintest.SkipTestException
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNot
import io.kotlintest.shouldNotBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec

class TestExecute : StringSpec({
    "should execute snippets" {
        val executeMainResult = Source.fromSnippet("""
int i = 0;
i++;
System.out.println(i);
            """.trim()).compile().execute()
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("1")
    }
    "f:should execute cached snippets" {
        Source.fromSnippet("""
int i = 0;
i++;
System.out.println(i);
            """.trim()).compile(CompilationArguments(useCache = true)).execute()

        val executeMainResult = Source.fromSnippet("""
int i = 0;
i++;
System.out.println(i);
            """.trim()).compile(CompilationArguments(useCache = true)).let {
            it.cached shouldBe true
            it.execute()
        }

        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("1")
    }
    "should execute snippets that include class definitions" {
        val executeMainResult = Source.fromSnippet("""
public class Foo {
    int i = 0;
}
Foo foo = new Foo();
foo.i = 4;
System.out.println(foo.i);
            """.trim()).compile().execute()
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("4")
    }
    "should execute the right class in snippets that include multiple class definitions" {
        val compiledSource = Source.fromSnippet("""
public class Bar {
    public static void main() {
        System.out.println("Bar");
    }
}
public class Foo {
    public static void main() {
        System.out.println("Foo");
    }
}
System.out.println("Main");
            """.trim()).compile()

        val executeBarResult = compiledSource.execute(SourceExecutionArguments(klass = "Bar"))
        executeBarResult should haveCompleted()
        executeBarResult should haveOutput("Bar")

        val executeFooResult = compiledSource.execute(SourceExecutionArguments(klass = "Foo"))
        executeFooResult should haveCompleted()
        executeFooResult should haveOutput("Foo")

        val executeMainResult = compiledSource.execute(SourceExecutionArguments(klass = "Main"))
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("Main")

        val executionFailed = shouldThrow<ExecutionFailed> {
            compiledSource.execute(SourceExecutionArguments(klass = "Baz"))
        }
        executionFailed.classNotFound shouldNotBe null
        executionFailed.methodNotFound shouldBe null
    }
    "should execute the right method in snippets that include multiple method definitions" {
        val compiledSource = Source.fromSnippet("""
public static void foo() {
    System.out.println("foo");
}
public static void bar() {
    System.out.println("bar");
}
System.out.println("main");
            """.trim()).compile()

        val executeFooResult = compiledSource.execute(SourceExecutionArguments(method = "foo()"))
        executeFooResult should haveCompleted()
        executeFooResult should haveOutput("foo")

        val executeBarResult = compiledSource.execute(SourceExecutionArguments(method = "bar()"))
        executeBarResult should haveCompleted()
        executeBarResult should haveOutput("bar")

        val executeMainResult = compiledSource.execute(SourceExecutionArguments(method = "main()"))
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("main")
    }
    "should not execute private methods" {
        val compiledSource = Source.fromSnippet("""
private static void foo() {
    System.out.println("foo");
}
System.out.println("main");
            """.trim()).compile()

        val executionFailed = shouldThrow<ExecutionFailed> {
            compiledSource.execute(SourceExecutionArguments(method = "foo()"))
        }
        executionFailed.methodNotFound shouldNotBe null
        executionFailed.classNotFound shouldBe null

        val executeMainResult = compiledSource.execute(SourceExecutionArguments(method = "main()"))
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("main")
    }
    "should not execute non-static methods" {
        val compiledSource = Source.fromSnippet("""
class Test {
    public void foo() {
        System.out.println("foo");
    }
}
System.out.println("main");
            """.trim()).compile()

        val executionFailed = shouldThrow<ExecutionFailed> {
            compiledSource.execute(SourceExecutionArguments(klass = "Test", method = "foo()"))
        }
        executionFailed.methodNotFound shouldNotBe null
        executionFailed.classNotFound shouldBe null

        val executeMainResult = compiledSource.execute(SourceExecutionArguments(method = "main()"))
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("main")
    }
    "should not execute methods that require arguments" {
        val compiledSource = Source.fromSnippet("""
public static void foo(int i) {
    System.out.println("foo");
}
System.out.println("main");
            """.trim()).compile()

        val executionFailed = shouldThrow<ExecutionFailed> {
            compiledSource.execute(SourceExecutionArguments(method = "foo()"))
        }
        executionFailed.methodNotFound shouldNotBe null
        executionFailed.classNotFound shouldBe null

        val executeMainResult = compiledSource.execute(SourceExecutionArguments(method = "main()"))
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("main")
    }
    "should execute sources" {
        val executionMainResult = Source(mapOf(
                "Main.java" to """
public class Main {
    public static void main() {
        var i = 0;
        System.out.println("Here");
    }
}
                """.trim())).compile().execute()
        executionMainResult should haveCompleted()
        executionMainResult shouldNot haveTimedOut()
        executionMainResult should haveStdout("Here")
    }
    "should special case main with string array" {
        val executionMainResult = Source(mapOf(
                "Main.java" to """
public class Main {
    public static void main(String[] unused) {
        var i = 0;
        System.out.println("Here");
    }
}
                """.trim())).compile().execute(SourceExecutionArguments(method = "main(String[])"))
        executionMainResult should haveCompleted()
        executionMainResult shouldNot haveTimedOut()
        executionMainResult should haveStdout("Here")
    }
    "should execute multiple sources with dependencies" {
        val executionMainResult = Source(mapOf(
                "Main.java" to """
public class Main {
    public static void main() {
        var i = 0;
        Foo.foo();
    }
}
                """.trim(),
                "Foo.java" to """
public class Foo {
    public static void foo() {
        System.out.println("Foo");
    }
}
                """.trim())).compile().execute()
        executionMainResult should haveCompleted()
        executionMainResult shouldNot haveTimedOut()
        executionMainResult should haveStdout("Foo")
    }
    "should throw missing class exceptions correctly" {
        val executionFailed = shouldThrow<ExecutionFailed> {
            Source.fromSnippet("""
int i = 0;
i++;
System.out.println(i);
            """.trim()).compile().execute(SourceExecutionArguments(klass = "Test"))
        }
        executionFailed.classNotFound shouldNotBe null
        executionFailed.methodNotFound shouldBe null
    }
    "should throw missing method exceptions correctly" {
        val executionFailed = shouldThrow<ExecutionFailed> {
            Source.fromSnippet("""
int i = 0;
i++;
System.out.println(i);
            """.trim()).compile().execute(SourceExecutionArguments(method = "test"))
        }
        executionFailed.methodNotFound shouldNotBe null
        executionFailed.classNotFound shouldBe null
    }
    "should import libraries properly" {
        val executionResult = Source.fromSnippet("""
import java.util.List;
import java.util.ArrayList;

List<Integer> list = new ArrayList<>();
list.add(8);
System.out.println(list.get(0));
            """.trim()).compile().execute()

        executionResult should haveCompleted()
        executionResult should haveOutput("8")
    }
    "should execute sources that use inner classes" {
        val executionResult = Source(mapOf(
                "Main.java" to """
public class Main {
    class Inner {
        Inner() {
            System.out.println("Inner");
        }
    }
    Main() {
        Inner inner = new Inner();
    }
    public static void main() {
        Main main = new Main();
    }
}
                """.trim())).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveStdout("Inner")
    }
    "should execute sources that use Java 12 features" {
        if (systemCompilerVersion < 12) {
            throw SkipTestException("Cannot run this test until Java 12")
        } else {
            val executionResult = Source(mapOf(
                    "Main.java" to """
public class Main {
    public static String testYieldKeyword(int switchArg) {
        return switch (switchArg) {
            case 1, 2 -> "works";
            case 3 -> "oh boy";
            default -> "testing";
        };
    }
    public static void main() {
        System.out.println(testYieldKeyword(1));
    }
}
                """.trim()
            )).compile().execute()

            executionResult should haveCompleted()
            executionResult should haveStdout("works")
        }
    }
    "should execute sources that use Java 13 features" {
        if (systemCompilerVersion < 13) {
            throw SkipTestException("Cannot run this test until Java 13")
        } else {
            val executionResult = Source(mapOf(
                    "Main.java" to """
public class Main {
    public static String testYieldKeyword(int switchArg) {
        return switch (switchArg) {
            case 1, 2: yield "works";
            case 3: yield "oh boy";
            default: yield "testing";
        };
    }
    public static void main() {
        System.out.println(testYieldKeyword(0));
    }
}
                """.trim()
            )).compile().execute()

            executionResult should haveCompleted()
            executionResult should haveStdout("testing")
        }
    }
    "should execute Kotlin snippets" {
        val executionMainResult = Source.fromSnippet("""
println("Here")
                """.trim(), SnippetArguments(fileType = Source.FileType.KOTLIN)).kompile().execute()
        executionMainResult should haveCompleted()
        executionMainResult shouldNot haveTimedOut()
        executionMainResult should haveStdout("Here")
    }
    "should execute kotlin snippets that include class definitions" {
        val executeMainResult = Source.fromSnippet("""
data class Foo(var i: Int)
val foo = Foo(5);
foo.i = 4;
println(foo.i);
            """.trim(), SnippetArguments(fileType = Source.FileType.KOTLIN)).kompile().execute()
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("4")
    }
    "should execute kotlin snippets that include method definitions" {
        val executeMainResult = Source.fromSnippet("""
fun test(): String {
    return "Hello, world!"
}
println(test())
            """.trim(), SnippetArguments(fileType = Source.FileType.KOTLIN)).kompile().execute()
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("Hello, world!")
    }
    "f:should execute cached kotlin snippets that include method definitions" {
        Source.fromSnippet(
            """
fun test(): String {
    return "Hello, world!"
}
println(test())
            """.trim(), SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).kompile(KompilationArguments(useCache = true)).execute()
        val executeMainResult = Source.fromSnippet(
            """
fun test(): String {
    return "Hello, world!"
}
println(test())
            """.trim(), SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
            .kompile(KompilationArguments(useCache = true)).let {
                it.cached shouldBe true
                it.execute()
        }
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("Hello, world!")
    }
    "should execute simple Kotlin sources" {
        val executionMainResult = Source(mapOf(
                "Main.kt" to """
fun main() {
  println("Here")
}
                """.trim())).kompile().execute()
        executionMainResult should haveCompleted()
        executionMainResult shouldNot haveTimedOut()
        executionMainResult should haveStdout("Here")
    }
    "should execute simple Kotlin sources with cross-method calls" {
        val executionMainResult = Source(mapOf(
                "Main.kt" to """
fun main() {
  println(test())
}
                """.trim(),
                "Test.kt" to """
fun test(): List<String> {
  return listOf("test", "me")
}
                """.trimIndent())).kompile().execute()
        executionMainResult should haveCompleted()
        executionMainResult shouldNot haveTimedOut()
        executionMainResult should haveStdout("""[test, me]""")
    }
})

fun haveCompleted() = object : Matcher<Sandbox.TaskResults<out Any?>> {
    override fun test(value: Sandbox.TaskResults<out Any?>): MatcherResult {
        return MatcherResult(
                value.completed,
                "Code should have run: ${value.threw}",
                "Code should not have run"
        )
    }
}
fun haveTimedOut() = object : Matcher<Sandbox.TaskResults<out Any?>> {
    override fun test(value: Sandbox.TaskResults<out Any?>): MatcherResult {
        return MatcherResult(
                value.timeout,
                "Code should have timed out",
                "Code should not have timed out"
        )
    }
}
fun haveOutput(output: String = "") = object : Matcher<Sandbox.TaskResults<out Any?>> {
    override fun test(value: Sandbox.TaskResults<out Any?>): MatcherResult {
        val actualOutput = value.output.trim()
        return MatcherResult(
                actualOutput == output,
                "Expected output $output, found $actualOutput",
                "Expected to not find output $actualOutput"
        )
    }
}
fun haveStdout(output: String) = object : Matcher<Sandbox.TaskResults<out Any?>> {
    override fun test(value: Sandbox.TaskResults<out Any?>): MatcherResult {
        val actualOutput = value.stdout.trim()
        return MatcherResult(
                actualOutput == output,
                "Expected stdout $output, found $actualOutput",
                "Expected to not find stdout $actualOutput"
        )
    }
}
fun haveStderr(output: String) = object : Matcher<Sandbox.TaskResults<out Any?>> {
    override fun test(value: Sandbox.TaskResults<out Any?>): MatcherResult {
        val actualOutput = value.stderr.trim()
        return MatcherResult(
                actualOutput == output,
                "Expected stderr $output, found $actualOutput",
                "Expected to not find stderr $actualOutput"
        )
    }
}
