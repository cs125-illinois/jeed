package edu.illinois.cs.cs125.jeed.core.sandbox

import edu.illinois.cs.cs125.jeed.core.*
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNot
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import java.lang.IllegalArgumentException

class TestClassLoader : StringSpec({
    "should prohibit default blacklisted imports" {
        val executionResult = Source.fromSnippet("""
import edu.illinois.cs.cs125.jeed.core.*;

System.out.println(Sandbox.class.getMethods().length);
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should blacklist java.lang.reflect.* by default" {
        val compiledSource = Source.fromSnippet("""
import java.lang.reflect.*;

Method[] methods = Main.class.getMethods();
System.out.println(methods[0].getName());
        """.trim()).compile()

        val failedExecutionResult = compiledSource.execute()
        failedExecutionResult shouldNot haveCompleted()
        failedExecutionResult.permissionDenied shouldBe true

        val successfulExecutionResult =
                compiledSource.execute(
                        SourceExecutionArguments(classLoaderConfiguration=Sandbox.ClassLoaderConfiguration(blacklistedClasses = setOf()))
                )

        successfulExecutionResult should haveCompleted()
    }
    "should prohibit configured blacklisted imports" {
        val successfulExecutionResult = Source.fromSnippet("""
import java.util.List;
import java.util.ArrayList;

List list = new ArrayList<String>();
        """.trim()).compile().execute()

        successfulExecutionResult should haveCompleted()

        val failedExecutionResult = Source.fromSnippet("""
import java.util.List;
import java.util.ArrayList;

List list = new ArrayList<String>();
        """.trim()).compile().execute(
                SourceExecutionArguments(classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(blacklistedClasses = setOf("java.util.")))
        )

        failedExecutionResult shouldNot haveCompleted()
        failedExecutionResult.permissionDenied shouldBe true
    }
    "should allow only whitelisted imports" {
        val successfulExecutionResult = Source.fromSnippet("""
String s = new String("test");
        """.trim()).compile().execute(SourceExecutionArguments(classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                whitelistedClasses = setOf("java.lang."),
                blacklistedClasses = setOf()
        )))

        successfulExecutionResult should haveCompleted()

        val failedExecutionResult = Source.fromSnippet("""
import java.util.List;
import java.util.ArrayList;

List list = new ArrayList<String>();
        """.trim()).compile().execute(SourceExecutionArguments(classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                whitelistedClasses = setOf("java.lang."),
                blacklistedClasses = setOf()
        )))

        failedExecutionResult shouldNot haveCompleted()
        failedExecutionResult.permissionDenied shouldBe true
    }
    "should not allow edu.illinois.cs.cs125.jeed. to be whitelisted" {
        shouldThrow<IllegalArgumentException> {
            Source.fromSnippet("""
System.out.println("Here");
            """.trim()).compile().execute(SourceExecutionArguments(classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                    whitelistedClasses = setOf("edu.illinois.cs.cs125.jeed.")
            )))
        }
        shouldThrow<IllegalArgumentException> {
            Source.fromSnippet("""
System.out.println("Here");
            """.trim()).compile().execute(SourceExecutionArguments(classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                    whitelistedClasses = setOf("edu.illinois.cs.cs125.jeed.core.Sandbox")
            )))
        }
    }
})
