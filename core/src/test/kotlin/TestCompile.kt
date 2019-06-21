package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.specs.StringSpec
import io.kotlintest.*
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.matchers.numerics.shouldBeGreaterThan

class TestCompile : StringSpec({
    "should compile simple snippets" {
        val compiledSource = Source.fromSnippet("int i = 1;").compile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("Main"))
        compiledSource should haveLoadedThisManyClasses(0)
    }
    "should compile snippets that include method definitions" {
        val compiledSource = Source.fromSnippet("""
int i = 0;
private static int main() {
    return 0;
}
        """.trim()).compile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("Main"))
        compiledSource should haveLoadedThisManyClasses(0)
    }
    "should compile snippets that include class definitions" {
        val compiledSource = Source.fromSnippet("""
int i = 0;
public class Foo {
    int i;
}
Foo foo = new Foo();
        """.trim()).compile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("Main", "Foo"))
        compiledSource should haveLoadedThisManyClasses(0)
    }
    "should compile multiple sources" {
        val compiledSource = Source(mapOf(
                "Test" to "public class Test {}",
                "Me" to "public class Me {}"
        )).compile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("Test", "Me"))
        compiledSource should haveLoadedThisManyClasses(0)
    }
    "should compile sources with dependencies" {
        val compiledSource = Source(mapOf(
                "Test" to "public class Test {}",
                "Me" to "public class Me extends Test {}"
        )).compile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("Test", "Me"))
        compiledSource should haveLoadedThisManyClasses(0)
    }
    "should compile sources with dependencies in wrong order" {
        val compiledSource = Source(mapOf(
                "Test" to "public class Test extends Me {}",
                "Me" to "public class Me {}"
        )).compile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("Test", "Me"))
        compiledSource should haveLoadedThisManyClasses(0)
    }
    "should compile sources in multiple packages" {
        val compiledSource = Source(mapOf(
                "test/Test" to """
package test;
public class Test {}
                """.trim(),
                "me/Me" to """
package me;
public class Me {}
                """.trim()
        )).compile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("test.Test", "me.Me"))
        compiledSource should haveLoadedThisManyClasses(0)
    }
    "should compile sources in multiple packages with dependencies in wrong order" {
        val compiledSource = Source(mapOf(
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

        compiledSource should haveDefinedExactlyTheseClasses(setOf("test.Test", "me.Me"))
        compiledSource should haveLoadedThisManyClasses(0)
    }
    "should compile sources that use Java 10 features" {
        val compiledSource = Source(mapOf(
                "Test" to """
public class Test {
    public static void main() {
        var i = 0;
    }
}
                """.trim()
        )).compile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("Test"))
        compiledSource should haveLoadedThisManyClasses(0)
    }
    "should compile sources that use inner classes" {
        val compiledSource = Source(mapOf(
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

        compiledSource should haveDefinedExactlyTheseClasses(setOf("Test", "Test\$Inner"))
        compiledSource should haveLoadedThisManyClasses(0)
    }
    "should identify compilation errors in simple snippets" {
        val failedCompilation = shouldThrow<CompilationFailed> { Source.fromSnippet("int i = a;").compile() }

        failedCompilation should haveCompilationErrorAt(line=1)
    }
    "should identify multiple compilation errors in simple snippets" {
        val failedCompilation = shouldThrow<CompilationFailed> {
            Source.fromSnippet("""
int i = a;
Foo f = new Foo();
            """.trim()).compile()
        }

        failedCompilation should haveCompilationErrorAt(line=1)
        failedCompilation should haveCompilationErrorAt(line=2)
    }
    "should identify multiple compilation errors in reordered simple snippets" {
        val failedCompilation = shouldThrow<CompilationFailed> {
            Source.fromSnippet("""
public void foo() {
    return;
}
public class Bar { }
int i = a;
Foo f = new Foo();
            """.trim()).compile()
        }

        failedCompilation should haveCompilationErrorAt(line=5)
        failedCompilation should haveCompilationErrorAt(line=6)
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
    "should enumerate and load classes correctly after execution" {
        val compiledSource = Source.fromSnippet("""
class Test {}
class Me {}
Test test = new Test();
Me me = new Me();
        """.trim()).compile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("Test", "Me", "Main"))
        compiledSource should haveLoadedThisManyClasses(0)
        compiledSource.execute()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("Test", "Me", "Main"))
        compiledSource should haveLoadedExactlyTheseClasses(setOf("Test", "Me", "Main"))
        compiledSource.classLoader.bytecodeForClass("Test").size shouldBeGreaterThan 0
    }
    "should provide bytecode when requested even if the class hasn't been loaded" {
        val compiledSource = Source.fromSnippet("""
class Test {}
class Me {}
Test test = new Test();
        """.trim()).compile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("Test", "Me", "Main"))
        compiledSource should haveLoadedThisManyClasses(0)
        compiledSource.classLoader.bytecodeForClass("Test").size shouldBeGreaterThan 0
        compiledSource.execute()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("Test", "Me", "Main"))
        compiledSource should haveLoadedExactlyTheseClasses(setOf("Test", "Main"))
        compiledSource.classLoader.bytecodeForClass("Me").size shouldBeGreaterThan 0
    }
    "should correctly accept previously compiled source argument" {
        val compiledTestSource = Source(
                mapOf("Test" to """
public class Test {}
            """.trim())).compile()
        val compiledFooSource = Source(
                    mapOf("Foo" to """
    public class Foo extends Test { }
            """.trim())).compileWith(compiledTestSource)

        compiledFooSource should haveDefinedExactlyTheseClasses(setOf("Foo"))
        compiledFooSource should haveLoadedThisManyClasses(0)
    }
    "should correctly accept previously compiled source argument in another package" {
        val compiledMeSource = Source(
                mapOf("test/Me" to """
package test;
public class Me {}
            """.trim())).compile()

        val compiledFooSource = Source(
                mapOf("another/Foo" to """
package another;
import test.Me;
public class Foo extends Me { }
            """.trim())).compileWith(compiledMeSource)

        compiledFooSource should haveDefinedExactlyTheseClasses(setOf("another.Foo"))
        compiledFooSource should haveLoadedThisManyClasses(0)
    }
    "should compile with classes from Java standard libraries" {
        val compiledSource = Source.fromSnippet("""
import java.util.List;
import java.util.ArrayList;

List list = new ArrayList();
        """.trim()).compile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("Main"))
        compiledSource should haveLoadedThisManyClasses(0)
    }
})

fun haveCompilationErrorAt(source: String = SNIPPET_SOURCE, line: Int) = object : Matcher<CompilationFailed> {
    override fun test(value: CompilationFailed): Result {
        return Result(
                value.errors.any { it.location.source == source && it.location.line == line },
                "should have compilation error on line $line",
                "should not have compilation error on line $line"
        )
    }
}
fun haveCompilationMessageAt(source: String = SNIPPET_SOURCE, line: Int) = object : Matcher<CompiledSource> {
    override fun test(value: CompiledSource): Result {
        return Result(
                value.messages.any { it.location.source == source && it.location.line == line },
                "should have compilation message on line $line",
                "should not have compilation message on line $line"
        )
    }
}
fun haveDefinedThisManyClasses(count: Int) = object : Matcher<CompiledSource> {
    override fun test(value: CompiledSource): Result {
        return Result(
                value.classLoader.definedClasses.size == count,
                "should have defined $count classes",
                "should not have defined $count classes"
        )
    }
}
fun haveDefinedExactlyTheseClasses(classes: Set<String>) = object : Matcher<CompiledSource> {
    override fun test(value: CompiledSource): Result {
        return Result(
                value.classLoader.definedClasses.toSet() == classes,
                "should have defined exactly these classes: ${ classes.joinToString(separator = ", ")}",
                "should nothave defined exactly these classes: ${ classes.joinToString(separator = ", ")}"
        )
    }
}
fun haveLoadedThisManyClasses(count: Int) = object : Matcher<CompiledSource> {
    override fun test(value: CompiledSource): Result {
        return Result(
                value.classLoader.loadedClasses.size == count,
                "should have defined $count classes",
                "should not have defined $count classes"
        )
    }
}
fun haveLoadedExactlyTheseClasses(classes: Set<String>) = object : Matcher<CompiledSource> {
    override fun test(value: CompiledSource): Result {
        return Result(
                value.classLoader.loadedClasses.toSet() == classes,
                "should have defined exactly these classes: ${ classes.joinToString(separator = ", ")}",
                "should nothave defined exactly these classes: ${ classes.joinToString(separator = ", ")}"
        )
    }
}
