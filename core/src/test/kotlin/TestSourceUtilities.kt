package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TestSourceUtilities : StringSpec({
    "should strip line comments" {
        """System.out.println("Hello, world!"); // test me"""
            .stripComments(Source.FileType.JAVA) shouldBe """System.out.println("Hello, world!"); """
    }
    "should strip comments from multiline source" {
        """System.out.println("Hello, world!"); // test me
          |System.out.println("Hello, world!");/* more testing */
        """.trimMargin()
            .stripComments(Source.FileType.JAVA) shouldBe """System.out.println("Hello, world!"); 
          |System.out.println("Hello, world!");
        """.trimMargin()
    }
    "should strip comments from a source" {
        Source.fromJava("""System.out.println("Hello, world!"); // test me""").stripComments().contents shouldBe
            """System.out.println("Hello, world!"); """
    }
    "should strip kotlin line comments" {
        """println("Hello, world!") // test me"""
            .stripComments(Source.FileType.KOTLIN) shouldBe """println("Hello, world!") """
    }
    "should strip kotlin comments from multiline source" {
        """println("Hello, world!") // test me
          |println("Hello, world!");/* more testing */
        """.trimMargin()
            .stripComments(Source.FileType.KOTLIN) shouldBe """println("Hello, world!") 
          |println("Hello, world!");
        """.trimMargin()
    }
    "should strip kotlin comments from a source" {
        Source.fromKotlin("""println("Hello, world!") // test me""").stripComments().contents shouldBe
            """println("Hello, world!") """
    }
    "should strip kotlin comments without breaking whitespace" {
        Source.fromKotlin(
            """
            |for (i in test) {
            |  println(i) // test me
            |}
        """.trimMargin()
        ).stripComments().contents shouldBe
            """
            |for (i in test) {
            |  println(i) 
            |}
        """.trimMargin()
    }
    "should diff text with same line count" {
        val first = """
            |test me
            |another test
        """.trimMargin()
        val second = """
            |test me
            |another testing
        """.trimMargin()
        first.lineDifferenceCount(first) shouldBe 0
        first.lineDifferenceCount(second) shouldBe 1
        second.lineDifferenceCount(first) shouldBe 1
        second.lineDifferenceCount(second) shouldBe 0
    }
    "should diff text with extra lines" {
        val first = """
            |test me
            |another test
        """.trimMargin()
        val second = """
            |test me
            |more text
            |another test
        """.trimMargin()
        first.lineDifferenceCount(first) shouldBe 0
        first.lineDifferenceCount(second) shouldBe 1
        second.lineDifferenceCount(first) shouldBe 1
        second.lineDifferenceCount(second) shouldBe 0
    }
    "should remove java assertion messages" {
        Source.fromJava(
            """public class Test {
            |  public static void main() {
            |    assert false :"Testing";
            |    assert true: "Here" ;
            |    assert false;
            |  }
            |}
            |""".trimMargin()).stripAssertionMessages().googleFormat().contents shouldBe
            """public class Test {
            |  public static void main() {
            |    assert false;
            |    assert true;
            |    assert false;
            |  }
            |}
            |""".trimMargin()
    }
    "f: should remove kotlin assertion messages" {
        Source.fromKotlin(
            """fun main() {
            |  assert(false) { "Testing" }
            |  check(true)
            |  require(false) {
            |    val i = 0
            |    "${"$"}i"
            |  }
            |  error("Bad")
            |  error ( "Whoops" )
            |}
            |""".trimMargin()).stripAssertionMessages().trimLines().contents shouldBe
            """fun main() {
            |  assert(false)
            |  check(true)
            |  require(false)
            |  error("error")
            |  error ("error")
            |}
            |""".trimMargin()
    }
})
