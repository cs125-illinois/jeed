package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe

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
    "should compile simple lists" {
        val executionResult = Source.fromKotlin(
            """
 val list = listOf<String>()
        """.trim()
        ).kompile().execute()

        println(executionResult.sandboxedClassLoader!!.loadedClasses)
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
    "should load inner classes from a separate classloader" {
        val first = Source(
            mapOf(
                "SimpleLinkedList.java" to """
public class SimpleLinkedList {
  protected class Item {
    public Object value;
    public Item next;

    Item(Object setValue, Item setNext) {
      value = setValue;
      next = setNext;
    }
  }
  protected Item start;
}
""".trim()
            )
        ).compile()

        Source(
            mapOf(
                "CountLinkedList.kt" to """
class CountLinkedList : SimpleLinkedList() {
  fun count(value: Any): Int {
    var count = 0
    var current: SimpleLinkedList.Item? = start
    while (current != null) {
      if (current.value == value) {
        count++
      }
      current = current.next
    }
    return count
  }
}
""".trim()
            )
        ).kompile(
            kompilationArguments = KompilationArguments(
                parentFileManager = first.fileManager,
                parentClassLoader = first.classLoader
            )
        )
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
    "should compile with parameter names when requested" {
        val source = Source(
            mapOf(
                "Test.kt" to """
class Test {
  fun method(first: Int, second: Int) { }
}
            """.trim()
            )
        )
        source.kompile().also { compiledSource ->
            val klass = compiledSource.classLoader.loadClass("Test")
            klass.declaredMethods.find { it.name == "method" }?.parameters?.map { it.name }?.first() shouldBe "arg0"
        }
        source.kompile(KompilationArguments(parameters = true)).also { compiledSource ->
            val klass = compiledSource.classLoader.loadClass("Test")
            klass.declaredMethods.find { it.name == "method" }?.parameters?.map { it.name }?.first() shouldBe "first"
        }
    }
})
