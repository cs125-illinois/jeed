package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.matchers.numerics.shouldBeLessThan
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.StringSpec

class TestKompile : StringSpec({
    "should compile simple sources" {
        val compiledSource = Source(mapOf(
                "Test.kt" to """val test = "string""""
        )).kompile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("TestKt"))
        compiledSource should haveProvidedThisManyClasses(0)
    }
    "should compile simple classes" {
        val compiledSource = Source(mapOf(
                "Test.kt" to """
data class Person(val name: String)
fun main() {
  println("Here")
}
""".trim()
        )).kompile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("TestKt", "Person"))
        compiledSource should haveProvidedThisManyClasses(0)
    }
    "should compile sources with dependencies" {
        val compiledSource = Source(mapOf(
                "Test.kt" to "open class Test()",
                "Me.kt" to "class Me() : Test()"
        )).kompile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("Test", "Me"))
        compiledSource should haveProvidedThisManyClasses(0)
    }
    "should compile sources with dependencies in wrong order" {
        val compiledSource = Source(mapOf(
                "Me.kt" to "class Me() : Test()",
                "Test.kt" to "open class Test()"
        )).kompile()

        compiledSource should haveDefinedExactlyTheseClasses(setOf("Test", "Me"))
        compiledSource should haveProvidedThisManyClasses(0)
    }
    "should identify sources that use coroutines" {
        val compiledSource = Source(mapOf(
            "Me.kt" to """
import kotlinx.coroutines.*
fun main() {
  println("Hello, world!")
}
            """.trim(),
            "Test.kt" to "open class Test()"
        )).kompile()

        compiledSource.source.parsed shouldBe false
        compiledSource.usesCoroutines() shouldBe true
        compiledSource.source.parsed shouldBe true
    }
    "should cache compiled simple kotlin snippets" {
        val first = Source.fromSnippet(
            "val weirdKame = 8", SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).kompile(KompilationArguments(useCache = true))
        val second = Source.fromSnippet(
            "val weirdKame = 8", SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).kompile(KompilationArguments(useCache = true))

        first.cached shouldBe false
        second.cached shouldBe true
        second.interval.length shouldBeLessThan first.interval.length
        first.compiled shouldBe second.compiled
    }
    "should cache compiled kotlin sources" {
        val first = Source(
            mapOf(
                "Weird.kt" to "class Weird {}",
                "Name.kt" to "data class Name(val name: String)"
            )
        ).kompile(KompilationArguments(useCache = true))
        val second = Source(
            mapOf(
                "Name.kt" to "data class Name(val name: String)",
                "Weird.kt" to "class Weird {}"
            )
        ).kompile(KompilationArguments(useCache = true))

        first.cached shouldBe false
        second.cached shouldBe true
        second.interval.length shouldBeLessThan first.interval.length
        first.compiled shouldBe second.compiled
    }
    "should not cache compiled sources when told not to" {
        val first = Source(
            mapOf(
                "Testee.kt" to "class Testee {}",
                "Meee.kt" to "data class Meee(val whee: Int)"
            )
        ).kompile(KompilationArguments(useCache = true))
        val second = Source(
            mapOf(
                "Meee.kt" to "data class Meee(val whee: Int)",
                "Testee.kt" to "class Testee {}"
            )
        ).kompile(KompilationArguments(useCache = false))

        first.cached shouldBe false
        second.cached shouldBe false
        first.compiled shouldNotBe second.compiled
    }
})
