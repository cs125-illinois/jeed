package edu.illinois.cs.cs125.jeed.core.sandbox

import edu.illinois.cs.cs125.jeed.core.*
import io.kotlintest.matchers.types.shouldBeTypeOf
import io.kotlintest.should
import io.kotlintest.shouldNot
import io.kotlintest.specs.StringSpec

class TestByteCodeRewriters : StringSpec({
    "should not intercept safe exceptions" {
        val executionResult = Source.fromSnippet("""
try {
    System.out.println("Try");
    Object o = null;
    o.toString();
} catch (NullPointerException e) {
    System.out.println("Catch");
} finally {
    System.out.println("Finally");
}
            """.trim()).compile().execute()

        executionResult should haveCompleted()
        executionResult should haveOutput("Try\nCatch\nFinally")
    }
    "should intercept exceptions configured to be unsafe in catch blocks" {
        val executionResult = Source.fromSnippet("""
try {
    System.out.println("Try");
    Object o = null;
    o.toString();
} catch (NullPointerException e) {
    System.out.println("Catch");
} finally {
    System.out.println("Finally");
}
            """.trim()).compile().execute(SourceExecutionArguments(unsafeExceptions = setOf("java.lang.NullPointerException")))

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
    "should intercept subclasses of exceptions configured to be unsafe in catch blocks" {
        val executionResult = Source.fromSnippet("""
try {
    System.out.println("Try");
    Object o = null;
    o.toString();
} catch (Exception e) {
    System.out.println("Catch");
} finally {
    System.out.println("Finally");
}
            """.trim()).compile().execute(SourceExecutionArguments(unsafeExceptions = setOf("java.lang.NullPointerException")))

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
    "should intercept subclasses of exceptions configured to be unsafe in finally blocks" {
        val executionResult = Source.fromSnippet("""
try {
    System.out.println("Try");
    Object o = null;
    o.toString();
} finally {
    System.out.println("Finally");
}
            """.trim()).compile().execute(SourceExecutionArguments(unsafeExceptions = setOf("java.lang.NullPointerException")))

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
    "should not intercept exceptions configured to be safe in finally blocks" {
        val executionResult = Source.fromSnippet("""
try {
    System.out.println("Try");
    Object o = null;
    o.toString();
} catch (ClassCastException e) {
    System.out.println("Catch");
} finally {
    System.out.println("Finally");
}
            """.trim()).compile().execute(SourceExecutionArguments(unsafeExceptions = setOf("java.lang.ClassCastException")))

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try\nFinally")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
    "should handle nested try-catch blocks" {
        val executionResult = Source.fromSnippet("""
try {
    try {
        System.out.println("Try");
        String s = (String) new Object();
    } catch (ClassCastException e) {
        System.out.println("Catch");
        Object o = null;
        o.toString();
    } finally {
        System.out.println("Finally");
    }
} catch (NullPointerException e) {
    System.out.println("Broken");
} finally {
    System.out.println("Bah");
}
            """.trim()).compile().execute(SourceExecutionArguments(unsafeExceptions = setOf("java.lang.NullPointerException")))

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try\nCatch")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
    "should handle try-catch blocks in loops" {
        val executionResult = Source.fromSnippet("""
while (true) {
    try {
        System.out.println("Try");
        String s = (String) new Object();
    } catch (ClassCastException e) {
        System.out.println("Catch");
        Object o = null;
        o.toString();
    } finally {
        System.out.println("Finally");
    }
}
            """.trim()).compile().execute(SourceExecutionArguments(unsafeExceptions = setOf("java.lang.NullPointerException")))

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try\nCatch")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
})
