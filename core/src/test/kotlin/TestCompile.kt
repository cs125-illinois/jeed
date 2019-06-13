package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.specs.StringSpec
import io.kotlintest.*
import io.kotlintest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.matchers.numerics.shouldBeGreaterThan

class TestCompile : StringSpec({
    "should compile simple snippets" {
        Source.fromSnippet("int i = 1;").compile()
    }
    "should compile snippets that include method definitions" {
        Source.fromSnippet("""
int i = 0;
private static int main() {
    return 0;
}
        """.trim()).compile()
    }
    "should compile snippets that include class definitions" {
        Source.fromSnippet("""
int i = 0;
public class Foo {
    int i;
}
Foo foo = new Foo();
        """.trim()).compile()
    }
    "should compile multiple sources" {
        Source(mapOf(
                "Test" to "public class Test {}",
                "Me" to "public class Me {}"
        )).compile()
    }
    "should compile sources with dependencies" {
        Source(mapOf(
                "Test" to "public class Test {}",
                "Me" to "public class Me extends Test {}"
        )).compile()
    }
    "should compile sources with dependencies in wrong order" {
        Source(mapOf(
                "Test" to "public class Test extends Me {}",
                "Me" to "public class Me {}"
        )).compile()
    }
    "should compile sources in multiple packages" {
        Source(mapOf(
                "test/Test" to """
package test;
public class Test {}
                """.trim(),
                "me/Me" to """
package me;
public class Me {}
                """.trim()
        )).compile()
    }
    "should compile sources in multiple packages with dependencies in wrong order" {
        Source(mapOf(
                "test/Test" to """
package test;
import me.Me;
public class Test extends Me {}
                """.trim(),
                "me/Me" to """
package me;
public class Me {}
                """.trim()
        )).compile()
    }
    "should compile sources that use Java 10 features" {
        Source(mapOf(
                "Test" to """
public class Test {
    public static void main() {
        var i = 0;
    }
}
                """.trim()
        )).compile()
    }
    "should compile sources that use inner classes" {
        Source(mapOf(
                "Test" to """
public class Test {
    class Inner { }
    Test() {
        Inner inner = new Inner();
    }
    public static void main() {
        Test test = new Test();
    }
}
                """.trim()
        )).compile()
    }
    "should identify compilation errors in simple snippets" {
        val exception = shouldThrow<CompilationFailed> {
            Source.fromSnippet("int i = a;").compile()
        }
        exception should haveCompilationErrorAt(line=1)
    }
    "should identify multiple compilation errors in simple snippets" {
        val exception = shouldThrow<CompilationFailed> {
            Source.fromSnippet("""
int i = a;
Foo f = new Foo();
            """.trim()).compile()
        }
        exception should haveCompilationErrorAt(line=1)
        exception should haveCompilationErrorAt(line=2)
    }
    "should identify warnings in snippets" {
        val compiledSource = Source.fromSnippet("""
import java.util.List;
import java.util.ArrayList;
List test = new ArrayList();
        """.trim()).compile()

        compiledSource.messages shouldHaveSize 2
        compiledSource should haveCompilationMessageAt(line=3)
    }
    "should not identify warnings in snippets when warnings are disabled" {
        val compiledSource = Source.fromSnippet("""
import java.util.List;
import java.util.ArrayList;
List test = new ArrayList();
        """.trim()).compile(CompilationArguments(Xlint = "none"))

        compiledSource.messages shouldHaveSize 0
    }
    "should fail when warnings are treated as errors" {
        val exception = shouldThrow<CompilationFailed> {
            Source.fromSnippet("""
import java.util.List;
import java.util.ArrayList;
List test = new ArrayList();
            """.trim()).compile(CompilationArguments(wError = true))
        }

        exception should haveCompilationErrorAt(line=3)
    }
    "custom classloader should enumerate and load classes correctly after execution" {
        val compiledSource = Source.fromSnippet("""
class Test {}
class Me {}
Test test = new Test();
Me me = new Me();
        """.trim()).compile()

        compiledSource.classLoader.loadedClasses shouldHaveSize 0
        compiledSource.execute()
        compiledSource.classLoader.loadedClasses shouldHaveSize 3
        compiledSource.classLoader.loadedClasses shouldContainExactlyInAnyOrder listOf("Test", "Me", "Main")
        compiledSource.classLoader.bytecodeForClass("Test").size shouldBeGreaterThan 0
    }
    "f:custom classloader should provide bytecode when requested even if the class hasn't been loaded" {
        val compiledSource = Source.fromSnippet("""
class Test {}
class Me {}
Test test = new Test();
        """.trim()).compile()

        compiledSource.classLoader.loadedClasses shouldHaveSize 0
        compiledSource.classLoader.bytecodeForClass("Test").size shouldBeGreaterThan 0
        compiledSource.classLoader.loadedClasses shouldHaveSize 1
        compiledSource.execute()
        compiledSource.classLoader.loadedClasses shouldHaveSize 2
        compiledSource.classLoader.loadedClasses shouldContainExactlyInAnyOrder listOf("Test", "Main")
        compiledSource.classLoader.bytecodeForClass("Me").size shouldBeGreaterThan 0
        compiledSource.classLoader.loadedClasses shouldHaveSize 3
    }
})

fun haveCompilationErrorAt(source: String = SNIPPET_SOURCE, line: Int) = object : Matcher<CompilationFailed> {
    override fun test(value: CompilationFailed): Result {
        return Result(value.errors.any { it.location.source == source && it.location.line == line },
                "should have compilation error on line $line",
                "should not have compilation error on line $line")
    }
}

fun haveCompilationMessageAt(source: String = SNIPPET_SOURCE, line: Int) = object : Matcher<CompiledSource> {
    override fun test(value: CompiledSource): Result {
        return Result(value.messages.any { it.location.source == source && it.location.line == line },
                "should have compilation message on line $line",
                "should not have compilation message on line $line")
    }
}

