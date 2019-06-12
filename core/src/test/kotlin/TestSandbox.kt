package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.specs.StringSpec
import io.kotlintest.*
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
})
