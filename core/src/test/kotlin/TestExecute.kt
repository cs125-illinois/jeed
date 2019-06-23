package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.should
import io.kotlintest.shouldNot
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

        shouldThrow<ClassNotFoundException> {
            compiledSource.execute(SourceExecutionArguments(klass = "Baz"))
        }
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

        shouldThrow<MethodNotFoundException> {
            compiledSource.execute(SourceExecutionArguments(method = "foo()"))
        }

        val executeMainResult = compiledSource.execute(SourceExecutionArguments(method = "main()"))
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("main")
    }
    "should not execute non-static methods" {
        val compiledSource = Source.fromSnippet("""
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
        val compiledSource = Source.fromSnippet("""
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
            Source.fromSnippet("""
int i = 0;
i++;
System.out.println(i);
            """.trim()).compile().execute(SourceExecutionArguments(klass = "Test"))
        }
    }
    "should throw missing method exceptions correctly" {
        shouldThrow<MethodNotFoundException> {
            Source.fromSnippet("""
int i = 0;
i++;
System.out.println(i);
            """.trim()).compile().execute(SourceExecutionArguments(method = "test"))
        }
    }
})
