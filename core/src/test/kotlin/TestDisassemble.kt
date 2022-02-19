package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class TestDisassemble : StringSpec({
    "should disassemble a Java class" {
        val result = Source.fromJava(
            """
public class Main {
    public static void main() {
        System.out.println("hi");
    }
}""".trim()
        ).compile().disassemble()
        result.disassemblies.keys shouldBe setOf("Main")
        result.disassemblies["Main"] shouldContain "GETSTATIC"
    }

    "should disassemble multiple Java classes" {
        val result = Source.fromJava(
            """
public class Main {
    public static void main() {
        var customObject = new Runnable() {
            public void run() {
                System.out.println("running");
            }
        };
        customObject.run();
    }
}""".trim()
        ).compile().disassemble()
        result.disassemblies shouldHaveSize 2
        result.disassemblies["Main"] shouldContain "NEW "
        result.disassemblies.keys.filter { '$' in it } shouldHaveSize 1
    }

    "should disassemble a Kotlin file" {
        val result = Source.fromKotlin(
            """
fun main() {
    println("hi")
}""".trim()
        ).kompile().disassemble()
        result.disassemblies.keys shouldBe setOf("MainKt")
        result.disassemblies["MainKt"] shouldContain "GETSTATIC"
    }

    "should disassemble multiple Kotlin classes" {
        val result = Source.fromKotlin(
            """
fun main() {
    val adder = ::addOne
    println(adder(5))
}
private fun addOne(x: Int): Int {
    return x + 1
}
""".trim()
        ).kompile().disassemble()
        result.disassemblies shouldHaveSize 2
        result.disassemblies["MainKt"] shouldContain "INVOKEINTERFACE"
        result.disassemblies.keys.filter { '$' in it } shouldHaveSize 1
    }
})
