package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.specs.StringSpec
import io.kotlintest.*
import io.kotlintest.matchers.collections.shouldContain
import io.kotlintest.matchers.collections.shouldHaveSize
import java.util.*

class TestSandbox : StringSpec({
    "it should prevent snippets from exiting" {
        val executionResult = Source.fromSnippet("""
System.exit(-1);
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "it should prevent snippets from reading files" {
        val executionResult = Source.fromSnippet("""
import java.io.*;
System.out.println(new File("/").listFiles().length);
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "it should prevent snippets from reading system properties" {
        val executionResult = Source.fromSnippet("""
System.out.println(System.getProperty("file.separator"));
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "it should allow snippets to read system properties if allowed" {
        val executionResult = Source.fromSnippet("""
System.out.println(System.getProperty("file.separator"));
        """.trim()).compile().execute(
                ExecutionArguments(permissions=listOf(PropertyPermission("*", "read"))
                ))

        executionResult should haveCompleted()
        executionResult.permissionDenied shouldBe false
    }
    "it should allow permissions to be changed between runs" {
        val compiledSource = Source.fromSnippet("""
System.out.println(System.getProperty("file.separator"));
        """.trim()).compile()

        val failedExecution = compiledSource.execute()
        failedExecution shouldNot haveCompleted()
        failedExecution.permissionDenied shouldBe true

        val successfulExecution = compiledSource.execute(
                ExecutionArguments(permissions=listOf(PropertyPermission("*", "read"))
                ))
        successfulExecution should haveCompleted()
        successfulExecution.permissionDenied shouldBe false
    }
    "it should prevent snippets from starting threads by default" {
        val executionResult = Source.fromSnippet("""
public class Example implements Runnable {
    public void run() {
    }
}
Thread thread = new Thread(new Example());
thread.start();
System.out.println("Started");
        """.trim()).compile().execute(ExecutionArguments())

        executionResult shouldNot haveCompleted()
    }
    "it should allow snippets to start threads when configured" {
        val compiledSource = Source.fromSnippet("""
public class Example implements Runnable {
    public void run() {
        System.out.println("Ended");
    }
}
Thread thread = new Thread(new Example());
System.out.println("Started");
thread.start();
try {
    thread.join();
} catch (Exception e) { }
        """.trim()).compile()
        val failedExecutionResult = compiledSource.execute()
        failedExecutionResult shouldNot haveCompleted()

        val successfulExecutionResult = compiledSource.execute(ExecutionArguments(maxExtraThreadCount = 1))
        successfulExecutionResult should haveCompleted()
        successfulExecutionResult should haveOutput("Started\nEnded")
    }
    "it should shut down a runaway thread" {
        val executionResult = Source.fromSnippet("""
public class Example implements Runnable {
    public void run() {
        for (long j = 0; j < 512 * 1024 * 1024; j++);
        System.out.println("Ended");
    }
}
Thread thread = new Thread(new Example());
System.out.println("Started");
thread.start();
try {
    thread.join();
} catch (Exception e) { }
        """.trim()).compile().execute(ExecutionArguments(maxExtraThreadCount = 1))

        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput("Started")
    }
    "it should shut down thread bombs" {
        val executionResult = Source.fromSnippet("""
public class Example implements Runnable {
    public void run() {
        for (long j = 0; j < 512 * 1024 * 1024; j++);
        System.out.println("Ended");
    }
}
for (long i = 0;; i++) {
    Thread thread = new Thread(new Example());
    System.out.println(i);
    thread.start();
}
        """.trim()).compile().execute(ExecutionArguments(maxExtraThreadCount = 16, timeout=1000L))

        executionResult shouldNot haveCompleted()
        executionResult.stdoutLines shouldHaveSize 16
        executionResult.stdoutLines.map { it.line } shouldContain "15"
    }
    "it should not allow unsafe permissions to be provided" {
        shouldThrow<SandboxConfigurationError> {
            Source.fromSnippet("""
System.exit(-1);
            """.trim()).compile().execute(ExecutionArguments(permissions = listOf(RuntimePermission("exitVM"))))
        }
    }
    "f:it should shut down nasty thread bombs" {
        val executionResult = Source.fromSnippet("""
public class Example implements Runnable {
    public void run() {
        while (true) {
            try {
                Thread thread = new Thread(new Example());
                thread.start();
            } catch (Throwable e) {}
        }
    }
}
Thread thread = new Thread(new Example());
thread.start();
// Give time for things to get NASTY
try {
    Thread.sleep(Long.MAX_VALUE);
} catch (Exception e) { }
        """.trim()).compile().execute(ExecutionArguments(maxExtraThreadCount = 256, timeout = 1000L))
        executionResult should haveCompleted()
    }
})
