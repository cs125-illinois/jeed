package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotlintest.matchers.numerics.shouldBeLessThan
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec

class TestKompile : StringSpec({
    "should compile simple sources" {
        val compiledSource = Source(
            mapOf(
                "Test.kt" to """val test = "string""""
            )
        ).kompile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("TestKt"))
        compiledSource should haveProvidedThisManyClasses(0)
    }
    "should compile simple classes" {
        val compiledSource = Source(
            mapOf(
                "Test.kt" to """
data class Person(val name: String)
fun main() {
  println("Here")
}
""".trim()
            )
        ).kompile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("TestKt", "Person"))
        compiledSource should haveProvidedThisManyClasses(0)
    }
    "should compile sources with dependencies" {
        val compiledSource = Source(
            mapOf(
                "Test.kt" to "open class Test()",
                "Me.kt" to "class Me() : Test()"
            )
        ).kompile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("Test", "Me"))
        compiledSource should haveProvidedThisManyClasses(0)
    }
    "should compile sources with dependencies in wrong order" {
        val compiledSource = Source(
            mapOf(
                "Me.kt" to "class Me() : Test()",
                "Test.kt" to "open class Test()"
            )
        ).kompile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("Test", "Me"))
        compiledSource should haveProvidedThisManyClasses(0)
    }
    "should identify sources that use coroutines" {
        val compiledSource = Source(
            mapOf(
                "Me.kt" to """
import kotlinx.coroutines.*
fun main() {
  println("Hello, world!")
}
            """.trim(),
                "Test.kt" to "open class Test()"
            )
        ).kompile()

        compiledSource.source.parsed shouldBe false
        compiledSource.usesCoroutines() shouldBe true
        compiledSource.source.parsed shouldBe true
    }
    "should compile with predictable performance" {
        val source = Source(
            mapOf(
                "Test.kt" to """
data class Person(val name: String)
fun main() {
  println("Here")
}
""".trim()
            )
        )
        val kompilationArguments = KompilationArguments(useCache = false)
        source.kompile(kompilationArguments)

        @Suppress("MagicNumber")
        repeat(8) {
            val kompilationResult = source.kompile(kompilationArguments)
            kompilationResult.interval.length shouldBeLessThan 800L
        }
    }
    "should load classes from a separate classloader" {
        val first = Source(
            mapOf(
                "Test.java" to """
public class Test {
  public void print() {
    System.out.println("test");
  }
}
""".trim()
            )
        ).compile()

        val second = Source(
            mapOf(
                "Main.kt" to """
fun main() {
  val test = Test()
  test.print()
}
""".trim()
            )
        ).kompile(
            kompilationArguments = KompilationArguments(
                parentFileManager = first.fileManager,
                parentClassLoader = first.classLoader
            )
        )
            .execute()

        second should haveCompleted()
        second should haveOutput("test")
    }
    "should load classes from package in a separate classloader" {
        val first = Source(
            mapOf(
                "test/Test.java" to """
package test;

public class Test {
  public void print() {
    System.out.println("test");
  }
}
""".trim()
            )
        ).compile()

        val second = Source(
            mapOf(
                "Main.kt" to """
import test.Test

fun main() {
  val test = Test()
  test.print()
}
""".trim()
            )
        ).kompile(
            kompilationArguments = KompilationArguments(
                parentFileManager = first.fileManager,
                parentClassLoader = first.classLoader
            )
        )
            .execute()

        second should haveCompleted()
        second should haveOutput("test")
    }
    "should enumerate classes from multiple file managers" {
        val first = Source(
            mapOf(
                "test/Test.java" to """
package test;

public class Test {
  public void print() {
    System.out.println("test");
  }
}
""".trim()
            )
        ).compile()

        val second = Source(
            mapOf(
                "Main.kt" to """
package blah

import test.Test

data class AnotherTest(val name: String)

fun main() {
  val test = Test()
  test.print()
}
""".trim()
            )
        ).kompile(
            kompilationArguments = KompilationArguments(
                parentFileManager = first.fileManager,
                parentClassLoader = first.classLoader
            )
        )

        second.fileManager.allClassFiles.keys shouldContainExactlyInAnyOrder listOf(
            "blah/AnotherTest.class",
            "blah/MainKt.class",
            "test/Test.class"
        )
    }
})
