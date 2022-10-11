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
            it.lookup("main(Int,String,Blah?):Int", "Main.kt").complexity shouldBe 1
        }
    }
    "should compute complexity for Kotlin literal method" {
        Source.fromKotlin(
            """
fun isOdd(arg: Int) = arg % 2 != 0
""".trim()
        ).complexity().also {
            it.lookup("isOdd(Int)", "Main.kt").complexity shouldBe 1
        }
    }
    "should compute complexity for Kotlin snippet" {
        Source.fromSnippet(
            """
class Dog(val name: String)

listOf(Dog("Shadow"), Dog("Chuchu"), Dog("Lulu"))
  .map { it.name }
  .sorted()
  .forEach { println(it) }
""".trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).complexity().also {
            it.lookup("", "").complexity shouldBe 1
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
            it.lookup("Test.add(Int,Int):Int", "Test.kt").complexity shouldBe 2
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
            it.lookup("Test.add(Int,Int):Int", "Test.kt").complexity shouldBe 1
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
            it.lookup("Test.conditional(Int):Int", "Test.kt").complexity shouldBe 2
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
            it.lookup("Test.conditional(Int):Int", "Test.kt").complexity shouldBe 6
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
            it.lookup("Test.conditional(Int):Int", "Test.kt").complexity shouldBe 5
        }
    }

    "should work for when ranges" {
        Source(
            mapOf(
                "TestKt.kt" to """
fun main() {
    val age = 40
    when (age) {
        in 0..14 -> println("children")
        in 15..24 -> println("youth")
        in 25..64 -> println("adults")
        in 65..120 -> println("seniors")
        in 120..130 -> println("unlikely age")
        else -> println("wrong age value")
    }
}
""".trim()
            )
        ).complexity().also {
            it.lookupFile("TestKt.kt") shouldBe 6
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
            it.lookup("Test.toTest(Tester,Tester):Tester", "Main.kt").complexity shouldBe 1
        }
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
            it.lookup("main(T):List<T>", "Main.kt").complexity shouldBe 1
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
            it.lookup("toTest():Testing", "Main.kt").complexity shouldBe 2
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
            it.lookup("Test.init0", "Test.kt").complexity shouldBe 6
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
            it.lookup("PingPonger", "Test.kt").complexity shouldBe 8
            it.lookup("PingPonger.pong():Boolean", "Test.kt").complexity shouldBe 2
            it.lookup("PingPonger.ping():Boolean", "Test.kt").complexity shouldBe 2
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

    "should work with nested function" {
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
            it.lookup("test(Int,String,Blah?):Int.main(Double,String):Int").complexity shouldBe 1
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

    "should work for if used as expression" {
        Source.fromSnippet(
            """
fun test(first: Int, second: String, third: Blah?): Int {
  val max = if (a > b) {
    a
  } else {
    b
  }
}""".trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).complexity().also {
            it.lookup(".").complexity shouldBe 1
            it.lookup("").complexity shouldBe 3
        }
    }

    "should work for when used as expression" {
        Source(
            mapOf(
                "TestKt.kt" to """
fun main() {
    val dayOfWeek: DayOfWeek = LocalDate.now().dayOfWeek
    val msg:String = when (dayOfWeek) {
        DayOfWeek.MONDAY -> "It is monday"
        DayOfWeek.TUESDAY -> "It is tuesday"
        DayOfWeek.WEDNESDAY -> "It is wednesday"
        DayOfWeek.THURSDAY -> "It is thursday"
        DayOfWeek.FRIDAY -> "It is friday"
        DayOfWeek.SATURDAY -> "It is saturday"
        DayOfWeek.SUNDAY -> "It is sunday"
        else -> "Invalid day of week"
    }
}""".trim()
            )
        ).complexity().also {
            it.lookupFile("TestKt.kt") shouldBe 8
        }
    }

    "should work for try used as expression" {
        Source(
            mapOf(
                "TestKt.kt" to """
fun main() {
    val example: String = try {
      "example"
    } catch (e: IllegalArgumentException) {
      "error"
    } catch (e: Exception) {
      "bad error"
    }
}""".trim()
            )
        ).complexity().also {
            it.lookupFile("TestKt.kt") shouldBe 3
        }
    }

    "should work for single null-coalescing" {
        Source(
            mapOf(
                "TestKt.kt" to """
fun main() {
  person?.department = managersPool.getRandomDepartment()
}""".trim()
            )
        ).complexity().also {
            it.lookupFile("TestKt.kt") shouldBe 2
        }
    }

    "should work for multiple null-coalescing" {
        Source(
            mapOf(
                "TestKt.kt" to """
fun main() {
  person?.department?.head = managersPool.getManager()
}""".trim()
            )
        ).complexity().also {
            it.lookupFile("TestKt.kt") shouldBe 3
        }
    }

    "should work for single null-asserting" {
        Source(
            mapOf(
                "TestKt.kt" to """
fun main() {
  person!!.department = departments.getRandomDepartment()
}""".trim()
            )
        ).complexity().also {
            it.lookupFile("TestKt.kt") shouldBe 2
        }
    }

    "should work for multiple null-asserting" {
        Source(
            mapOf(
                "TestKt.kt" to """
fun main() {
  person!!.department!!.head = managersPool.getManager()
}""".trim()
            )
        ).complexity().also {
            it.lookupFile("TestKt.kt") shouldBe 3
        }
    }

    "should work with scope functions - .let{}" {
        Source.fromKotlin(
            """
fun main() {
    val numbers = mutableListOf("one", "two", "three", "four", "five")
    numbers.map { it.length }.filter { it > 3 }.let { 
        if (it > 4) {
          println("More than 4")
        }
        println("Less than 4")
    } 
}
            """.trim()
        ).complexity().also {
            it.lookup("main()", "Main.kt").complexity shouldBe 2
        }
    }

    "should work with scope functions - .also{}" {
        Source.fromKotlin(
            """
fun main() {
    val numbers = mutableListOf("one", "two", "three")
    numbers
        .also { println("The list elements before adding new one: " + it) }
        .add("four")
}
            """.trim()
        ).complexity().also {
            it.lookup("main()", "Main.kt").complexity shouldBe 1
        }
    }

    "should calculate complexity for an if-expression" {
        Source(
            mapOf(
                "TestKt.kt" to """
fun areSameLength(first: String?, second: String?): Boolean {
  return if (first == null || second == null) {
    false
  } else {
    first.length == second.length
  }
}
""".trim()
            )
        ).complexity().also {
            it.lookup("areSameLength(String?,String?):Boolean", "TestKt.kt").complexity shouldBe 3
            it.lookupFile("TestKt.kt") shouldBe 3
        }
    }

    "should work for 2 top level methods" {
        Source.fromKotlin(
            """
fun main(first: Int, second: String, third: Blah?): Int {
  return
}
fun main2(first: Int, second: String, third: Blah?): Int {
  return
}
""".trim()
        ).complexity().also {
            it.lookup("main(Int,String,Blah?):Int", "Main.kt").complexity shouldBe 1
            it.lookup("main2(Int,String,Blah?):Int", "Main.kt").complexity shouldBe 1
            it.lookupFile("Main.kt") shouldBe 2
        }
    }
    "should handle multiple anonymous classes" {
        Source.fromKotlin(
            """
interface Adder {
  fun addTo(value: Int): Int
}
val addOne = object : Adder {
  override fun addTo(value: Int) = value + 1
}
val addEight = object : Adder {
  override fun addTo(value: Int) = value + 8
}""".trim()
        ).complexity()
    }
    "should not overflow on deep nesting" {
        Source.fromKotlin(
            """fun mystery(a: Int): Int {
    if (a == -1) {
      return 0
    } else if (a == 0) {
      return 0
    } else if (a == 1) {
      return 0
    } else if (a == -2147483648) {
      return 2
    } else if (a == 889510) {
      return 2
    } else if (a == 598806) {
      return 2
    } else if (a == 974889) {
      return 2
    } else if (a == 485818) {
      return 3
    } else if (a == 858845) {
      return 3
    } else if (a == 887182) {
      return 3
    } else if (a == 668881) {
      return 3
    } else if (a == 668881) {
      return 3
    } else if (a == 668881) {
      return 3
    } else if (a == 668881) {
      return 3
    } else if (a == 668881) {
      return 3
    } else if (a == 668881) {
      return 3
    } else if (a == 668881) {
      return 3
    } else if (a == 668881) {
      return 3
    } else if (a == 668881) {
      return 3
    }
    return 1
}
"""
        ).complexity().also {
            it.lookupFile("Main.kt") shouldBe 20
        }
    }
    "should measure when and if equivalently" {
        Source.fromKotlin(
            """
fun first(first: Int, second: Int): Int {
  return if (first > second && second > first) {
    1
  } else if (second < first && first < second) {
    -1
  } else {
    0
  }
}
fun second(first: Int, second: Int): Int {
  return when {
    first > second && second > first -> 1
    second < first && first < second -> -1
    else -> 0
  }
}
""".trim()
        ).complexity().also {
            it.lookup("first(Int,Int):Int", "Main.kt").complexity shouldBe 5
            it.lookup("second(Int,Int):Int", "Main.kt").complexity shouldBe 5
        }
    }
    "should measure if correctly" {
        Source.fromKotlin(
            """
fun first(first: Int, second: Int): Int {
  var it = first > second || second == first
  return if (first > second && second > first || second == first && second == second) {
    1
  } else if (second < first && first < second) {
    -1
  } else {
    0
  }
}
""".trim()
        ).complexity().also {
            it.lookup("first(Int,Int):Int", "Main.kt").complexity shouldBe 8
        }
    }
    "should measure modulus correctly" {
        Source.fromKotlin(
            """
class Main {
    companion object {
        fun sumIsOdd(first: Int, second: Int): Boolean {
          return (first + second) % 2 != 0
        }
    }
}
""".trim()
        ).complexity().also {
            it.lookup("Main", "Main.kt").complexity shouldBe 1
            it.lookupFile("Main.kt") shouldBe 1
        }
    }
    "should handle multiple init blocks" {
        Source.fromKotlin(
            """
class Main {
  init {
    if (test < 10) {
      println("Here")
    }
  }
  init {
    val test = 20
  }
}
""".trim()
        ).complexity().also {
            it.lookupFile("Main.kt") shouldBe 2
        }
    }
    "should handle secondary constructors" {
        Source.fromKotlin(
            """
class Main {
  private val map = mutableMapOf<String, Int>()
  constructor(list: List<String>) {
    require(!list.isEmpty())
    for (place in list) {
      map[place] = 0
    }
  }
}
""".trim()
        ).complexity().also {
            it.lookupFile("Main.kt") shouldBe 3
        }
    }
})
