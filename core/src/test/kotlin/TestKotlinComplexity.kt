package edu.illinois.cs.cs125.jeed.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TestKotlinComplexity : StringSpec({
    "should compute complexity for Kotlin top-level method" {
        Source.fromKotlin(
            """
fun main(first: Int, second: String, third: Blah?): Int {
  return
}
""".trim()
        ).complexity().also {
            it.lookup("main(Int,String,Blah?): Int", "Main.kt").complexity shouldBe 1
        }
    }

    "should compute complexity from sources map" {
        Source(
            mapOf(
                "Test.kt" to """
public class Test(var first: Int, var second: Int) {
  fun add(i: Int, j: Int): Int {
    if (true) {
      return i + j
    }
  }
}
                """.trim()
            )
        ).complexity().also {
            it.lookup("Test.add(Int,Int): Int", "Test.kt").complexity shouldBe 2
            it.lookup("Test.Test(Int,Int)", "Test.kt").complexity shouldBe 1
        }
    }

    "should compute complexity of kotlin top-level methods in top-level class declarations" {
        Source(
            mapOf(
                "Test.kt" to """
public class Test(var first: Int, var second: Int) {
  fun add(i: Int, j: Int): Int {
    return i + j
  }
}
                """.trim()
            )
        ).complexity().also {
            it.lookup("Test.add(Int,Int): Int", "Test.kt").complexity shouldBe 1
            it.lookup("Test.Test(Int,Int)", "Test.kt").complexity shouldBe 1
        }
    }
    "should be able to find complexity of simple conditionals" {
        Source(
            mapOf(
                "Test.kt" to """
public class Test() {
  fun conditional(i: Int): Int {
    if (i < 0) {
      return 1
    } else {
      return -1
    }
  }
}
                """.trim()
            )
        ).complexity().also {
            it.lookup("Test.conditional(Int): Int", "Test.kt").complexity shouldBe 2
        }
    }

    "should find complexity of complex if statements" {
        Source(
            mapOf(
                "Test.kt" to """
public class Test() {
  fun conditional(i: Int): Int {
    if (i < 0) {
      if (i < -10) {
        return -100
      } else if (i < -20) {
        return -1000
      } else {
        return -1
      }
    } else if (i == 0) {
      return 0
    } else {
      if (i > 10) {
        return 100
      } else {
        return 1
      }
    }
  }
}
                """.trim()
            )
        ).complexity().also {
            it.lookup("Test.conditional(Int): Int", "Test.kt").complexity shouldBe 6
        }
    }

    "should calculate complexity of when statements" {
        Source(
            mapOf(
                "Test.kt" to """
public class Test() {
  fun conditional(i: Int): Int {
    return when (i) {
      1 -> 100
      2 -> 200
      3 -> 300
      4 -> 400
      else -> 500
    }
  }
}
                """.trim()
            )
        ).complexity().also {
            it.lookup("Test.conditional(Int): Int", "Test.kt").complexity shouldBe 5
        }
    }

    "should compute complexity of kotlin constructors with init" {
        Source(
            mapOf(
                "Test.kt" to """
public class Test(var first: Int, var second: Int) {
  init {
    if (first > 0) {
      first = first + 1
    }
  }
}                   
                """.trim()
            )
        ).complexity().also {
            it.lookup("Test.Test(Int,Int)", "Test.kt").complexity shouldBe 1
        }
    }

    "should be able to calculate loops" {
        Source.fromKotlin(
            """
fun main() {
  var j = 0
  while (j < 8) {
    i++
  }
  for (i in 0 until 10) {
    println("loop")
  }
  do {
    val y = calculateSomething()
  } while (y != null)
}
""".trim()
        ).complexity().also {
            it.lookup("main()", "Main.kt").complexity shouldBe 4
        }
    }

    "should be able to calculate catch blocks" {
        Source.fromKotlin(
            """
fun main() {
    try {
      doSomething()
    } catch (e: IllegalArgumentException) {
      println("error")
    } catch (e: Exception) {
      println("bad error")
    }
    
}
""".trim()
        ).complexity().also {
            it.lookup("main()", "Main.kt").complexity shouldBe 3
        }
    }

    "should calculate class complexity" {
        Source.fromKotlin(
            """
class Test() {
  private val unused: Int = 1
}
""".trim()
        ).complexity().also {
            it.lookup("Test", "Main.kt").complexity shouldBe 1
        }
    }

    "should work on kotlin interfaces" {
        Source.fromKotlin(
            """
interface Testing {
  fun toTest(i: Tester, j: Tester): Tester
}
class Test : Testing {
  override fun toTest(a: Tester, b: Tester): Tester = a + b
}
""".trim()
        ).complexity().also {
            it.lookup("Test.toTest(Tester,Tester): Tester", "Main.kt").complexity shouldBe 1
        } // ask geoff about this
    }

    "should work with generics" {
        Source.fromKotlin(
            """
fun <T> main(j: T): List<T> {
  val list: MutableList<T> = mutableListOf()
  return list
}
""".trim()
        ).complexity().also {
            it.lookup("main(T): List<T>", "Main.kt").complexity shouldBe 1
        }
    }

    "should work for lambdas" {
        Source.fromKotlin(
            """
               interface Testing {
  fun interfaceMethod(i: Int): Int
}
fun toTest(): Testing {
  return object : Testing {
    override fun interfaceMethod(i: Int): Int { return i }
  }
}

""".trim()
        ).complexity().also {
            it.lookup("toTest(): Testing", "Main.kt").complexity shouldBe 2
        }
    }

    "should fail properly on parse errors" {
        shouldThrow<ComplexityFailed> {
            Source(
                mapOf(
                    "Test.kt" to """
class Test
    fun test(i: Int, j: Int): Int {
        return i + j
    }
}
""".trim()
                )
            ).complexity()
        }
    }
    "should work for secondary constructors" {
        Source(
            mapOf(
                "Test.kt" to """
                    open class Rating(val rating: Double, val id: String) {
    constructor(id: String) : this (NOT_RATED, id)
    constructor(id: String, rating: Double) : this (rating, id)
    companion object {
        const val NOT_RATED = -1.0
    }
}
                """.trim()
            )
        ).complexity().also {
            it.lookup("Rating.Rating(Double,String)", "Test.kt").complexity shouldBe 1
            it.lookup("Rating.Rating(String)", "Test.kt").complexity shouldBe 1
            it.lookup("Rating.Rating(String,Double)", "Test.kt").complexity shouldBe 1
        }
    }

    "should compute complexity of && || and ?:" {
        Source(
            mapOf(
                "Test.kt" to """
public class Test(var first: Int, var second: Int) {
  init {
    second = 10
    if (first > 0 && true) {
      first = first + 1
    }
    if (second == 0 || false) {
      first = 0
      second = 0
    }
    first = second ?: 100
  }
}                   
                """.trim()
            )
        ).complexity().also {
            it.lookup("Test.init", "Test.kt").complexity shouldBe 6
        }
    }

    "should calculate complexity for entire classes" {
        Source(
            mapOf(
                "Test.kt" to """
class PingPonger constructor(private var state: String) {
  init {
    require(state == "ping" || state == "pong")
  }

  fun pong(): Boolean {
    require(state == "ping") { throw IllegalStateException() }
    state = "pong"
    return false
  }

  fun ping(): Boolean {
    require(state == "pong") { throw IllegalStateException() }
    state = "ping"
    return true
  }
}                 
                """.trim()
            )
        ).complexity().also {
            it.lookup("PingPonger", "Test.kt").complexity shouldBe 7
        }
    }

    "should work for snippets" {
        Source.fromSnippet(
            """
fun test(first: Int, second: String, third: Blah?): Int {
  return
}""".trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).complexity().also {
            it.lookup(".").complexity shouldBe 1
            it.lookup("").complexity shouldBe 2
        }
    }

    "nested function" {
        Source.fromSnippet(
            """
fun test(first: Int, second: String, third: Blah?): Int {
  var i = 1
  fun main(first: Double, second: String): Int {
    return
  }
}""".trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).complexity().also {
            it.lookup(".").complexity shouldBe 1
            it.lookup("").complexity shouldBe 3
            it.lookup("test(Int,String,Blah?): Int.main(Double,String): Int").complexity shouldBe 1
        }
    }

    "should calculate complexity for an entire file" {
        Source(
            mapOf(
                "TestKt.kt" to """
fun test() {}
fun me(first: Int, second: Int) = if (first > second) {
  "me"
} else {
  "you"
}""".trim()
            )
        ).complexity().also {
            it.lookupFile("TestKt.kt") shouldBe 3
        }
    }

})
