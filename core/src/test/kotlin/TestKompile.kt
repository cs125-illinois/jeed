package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.should
import io.kotlintest.shouldBe
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
})
