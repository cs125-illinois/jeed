package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.StringSpec

class TestSource : StringSpec({
    "should hash identical sources properly" {
        val first = Source(mapOf(
            "Main.java" to """public class Main {}""",
            "Test.java" to """public class Test {}"""
        ))
        val second = Source(mapOf(
            "Test.java" to """public class Test {}""",
            "Main.java" to """public class Main {}"""
        ))
        first.md5 shouldBe second.md5
        first.hashCode() shouldBe second.hashCode()
        first shouldBe second
    }
    "should hash non-identical sources properly" {
        val first = Source(mapOf(
            "Main.java" to """public class Main {}""",
            "Test.java" to """public class Test {}"""
        ))
        val second = Source(mapOf(
            "Test.java" to """public class Test { }""",
            "Main.java" to """public class Main {}"""
        ))
        first.md5 shouldNotBe second.md5
        first.hashCode() shouldNotBe second.hashCode()
        first shouldNotBe second
    }
})
