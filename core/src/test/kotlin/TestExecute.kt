package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.*
import io.kotlintest.specs.StringSpec

class TestExecute : StringSpec({
    "should execute snippets" {
        val executeMainResult = Source.transformSnippet("""
int i = 0;
i++;
System.out.println(i);
            """.trim()).compile().execute()
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("1")
    }
    "should execute snippets that include class definitions" {
        val executeMainResult = Source.transformSnippet("""
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
        val compiledSource = Source.transformSnippet("""
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

        shouldThrow<ClassNotFoundException> {
            compiledSource.execute(SourceExecutionArguments(klass = "Baz"))
        }
    }
    "should execute the right method in snippets that include multiple method definitions" {
        val compiledSource = Source.transformSnippet("""
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
        val compiledSource = Source.transformSnippet("""
private static void foo() {
    System.out.println("foo");
}
System.out.println("main");
            """.trim()).compile()

        shouldThrow<MethodNotFoundException> {
            compiledSource.execute(SourceExecutionArguments(method = "foo()"))
        }

        val executeMainResult = compiledSource.execute(SourceExecutionArguments(method = "main()"))
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("main")
    }
    "should not execute non-static methods" {
        val compiledSource = Source.transformSnippet("""
public void foo() {
    System.out.println("foo");
}
System.out.println("main");
            """.trim()).compile()

        shouldThrow<MethodNotFoundException> {
            compiledSource.execute(SourceExecutionArguments(method = "foo()"))
        }

        val executeMainResult = compiledSource.execute(SourceExecutionArguments(method = "main()"))
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("main")
    }
    "should not execute methods that require arguments" {
        val compiledSource = Source.transformSnippet("""
public static void foo(int i) {
    System.out.println("foo");
}
System.out.println("main");
            """.trim()).compile()

        shouldThrow<MethodNotFoundException> {
            compiledSource.execute(SourceExecutionArguments(method = "foo()"))
        }

        val executeMainResult = compiledSource.execute(SourceExecutionArguments(method = "main()"))
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("main")
    }
    "should execute sources" {
        val executionMainResult = Source(mapOf(
                "Main" to """
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
    "should execute multiple sources with dependencies" {
        val executionMainResult = Source(mapOf(
                "Main" to """
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
        executionMainResult should haveCompleted()
        executionMainResult shouldNot haveTimedOut()
        executionMainResult should haveStdout("Foo")
    }
    "should throw missing class exceptions correctly" {
        shouldThrow<ClassNotFoundException> {
            Source.transformSnippet("""
int i = 0;
i++;
System.out.println(i);
            """.trim()).compile().execute(SourceExecutionArguments(klass = "Test"))
        }
    }
    "should throw missing method exceptions correctly" {
        shouldThrow<MethodNotFoundException> {
            Source.transformSnippet("""
int i = 0;
i++;
System.out.println(i);
            """.trim()).compile().execute(SourceExecutionArguments(method = "test"))
        }
    }
    "should import libraries properly" {
        val executionResult = Source.transformSnippet("""
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
                "Main" to """
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
})

fun haveCompleted() = object : Matcher<Sandbox.TaskResults<out Any?>> {
    override fun test(value: Sandbox.TaskResults<out Any?>): Result {
        return Result(
                value.completed,
                "Code should have run: ${value.threw}",
                "Code should not have run"
        )
    }
}
fun haveTimedOut() = object : Matcher<Sandbox.TaskResults<out Any?>> {
    override fun test(value: Sandbox.TaskResults<out Any?>): Result {
        return Result(
                value.timeout,
                "Code should have timed out",
                "Code should not have timed out"
        )
    }
}
fun haveOutput(output: String = "") = object : Matcher<Sandbox.TaskResults<out Any?>> {
    override fun test(value: Sandbox.TaskResults<out Any?>): Result {
        val actualOutput = value.output.trim()
        return Result(
                actualOutput == output,
                "Expected output $output, found $actualOutput",
                "Expected to not find output $actualOutput"
        )
    }
}
fun haveStdout(output: String) = object : Matcher<Sandbox.TaskResults<out Any?>> {
    override fun test(value: Sandbox.TaskResults<out Any?>): Result {
        val actualOutput = value.stdout.trim()
        return Result(
                actualOutput == output,
                "Expected stdout $output, found $actualOutput",
                "Expected to not find stdout $actualOutput"
        )
    }
}
fun haveStderr(output: String) = object : Matcher<Sandbox.TaskResults<out Any?>> {
    override fun test(value: Sandbox.TaskResults<out Any?>): Result {
        val actualOutput = value.stderr.trim()
        return Result(
                actualOutput == output,
                "Expected stderr $output, found $actualOutput",
                "Expected to not find stderr $actualOutput"
        )
    }
}

