package edu.illinois.cs.cs125.jeed.core.sandbox

import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments
import edu.illinois.cs.cs125.jeed.core.execute
import edu.illinois.cs.cs125.jeed.core.haveCompleted
import edu.illinois.cs.cs125.jeed.core.haveOutput
import edu.illinois.cs.cs125.jeed.core.haveTimedOut
import edu.illinois.cs.cs125.jeed.core.kompile
import io.kotlintest.matchers.beLessThan
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNot
import io.kotlintest.specs.StringSpec
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Suppress("ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE")
class TestCoroutines : StringSpec({
    "should allow coroutines to run" {
        val executionResult = Source(mapOf(
            "Main.kt" to """
import kotlinx.coroutines.*

fun main() {
    var i = 0
    val job = GlobalScope.launch {
        delay(1)
        i = 1
    }
    runBlocking {
        job.join()
    }
    println(i)
}
""".trimIndent()
        )).kompile().execute()
        executionResult shouldNot haveTimedOut()
        executionResult should haveCompleted()
        executionResult should haveOutput("1")
    }
    "should capture output from coroutines" {
        // Dummy task to force GlobalScope to be initialized before the test runs
        var i = 0
        GlobalScope.launch { i = 1 }
        val executionResult = Source(mapOf(
            "Main.kt" to """
import kotlinx.coroutines.*

fun main() {
    val job = GlobalScope.launch {
        println("Hello")
    }
    Thread.sleep(50L)
    println("World!")
}
""".trimIndent()
        )).kompile().execute()
        i shouldBe 1
        executionResult shouldNot haveTimedOut()
        executionResult should haveOutput("Hello\nWorld!")
    }
    "should support multiple unscoped coroutines" {
        val executionResult = Source(mapOf(
            "Main.kt" to """
import kotlinx.coroutines.*

suspend fun work(value: Int) {
  delay(10)
}

fun main() {
  runBlocking {
    (0..1024).map {
      GlobalScope.launch {
        work(it)
      }
    }.forEach {
      it.join()
    }
  }
}
""".trimIndent()
        )).kompile().execute()

        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
    }
    "should support multiple coroutines joined automatically" {
        val executionResult = Source(mapOf(
            "Main.kt" to """
import kotlinx.coroutines.*

suspend fun work(value: Int) {
  delay(10)
  println(value)
}

fun main() {
  runBlocking {
    (1..1024).map {
      launch {
        work(it)
      }
    }
  }
  println("Last")
}
""".trimIndent()
        )).kompile().execute(SourceExecutionArguments(maxOutputLines = 1500))

        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult.outputLines.size shouldBe 1025
        executionResult.outputLines[1024].line shouldBe "Last"
    }
    "should prevent coroutines from escaping the sandbox" {
        // Dummy task to force GlobalScope to be initialized before the test runs
        var i = 0
        GlobalScope.launch { i = 1 }
        val executionResult = Source(mapOf(
            "Main.kt" to """
import kotlinx.coroutines.*

fun main() {
    val job = GlobalScope.launch {
        System.exit(-1)
    }
    runBlocking {
        job.join()
    }
}
""".trimIndent()
        )).kompile().execute()
        assert(executionResult.permissionRequests.any { it.permission.name == "exitVM.-1" && !it.granted })
    }
    "should allow an unscoped coroutine to try to finish in time" {
        val kompileResult = Source(mapOf(
            "Main.kt" to """
import kotlinx.coroutines.*

fun main() {
    GlobalScope.launch {
        delay(100)
        println("Finished")
    }
    println("Started")
}
""".trimIndent()
        )).kompile()
        repeat(16) { // Flaky
            val executionResult = kompileResult.execute()
            executionResult shouldNot haveTimedOut()
            executionResult should haveCompleted()
            executionResult should haveOutput("Started\nFinished")
        }
    }
    "should allow multiple unscoped coroutines to try to finish in time" {
        val kompileResult = Source(mapOf(
            "Main.kt" to """
import kotlinx.coroutines.*

fun main() {
    (1..256).forEach {
        GlobalScope.launch {
            delay(100)
            println(it)
        }
    }
    println("Started")
}
""".trimIndent()
        )).kompile()
        repeat(8) { // Flaky
            val executionResult = kompileResult.execute()
            executionResult shouldNot haveTimedOut()
            executionResult should haveCompleted()
            executionResult.outputLines.size shouldBe 257
            executionResult.outputLines[0].line shouldBe "Started"
        }
    }
    "should not give coroutines more time than they need" {
        val executionResult = Source(mapOf(
            "Main.kt" to """
import kotlinx.coroutines.*

fun main() {
    GlobalScope.launch {
        println("Finished")
    }
}
""".trimIndent()
        )).kompile().execute(SourceExecutionArguments(timeout = 9000L))
        executionResult should haveCompleted()
        executionResult should haveOutput("Finished")
        executionResult.executionInterval.length should beLessThan(5000L)
    }
    "should terminate runaway unscoped coroutines" {
        val executionResult = Source(mapOf(
            "Main.kt" to """
import kotlinx.coroutines.*

fun main() {
    val job = GlobalScope.launch {
        println("Coroutine")
        while (true) {}
    }
}
""".trimIndent()
        )).kompile().execute()
        // execute will throw if the sandbox couldn't be shut down
        executionResult should haveOutput("Coroutine")
    }
    "should terminate runaway child coroutines" {
        val executionResult = Source(mapOf(
            "Main.kt" to """
import kotlinx.coroutines.*

fun main() = runBlocking {
    (1..16).forEach {
        println(it)
        launch {
            while (true) {}
        }
    }
}
""".trimIndent()
        )).kompile().execute()
        executionResult should haveTimedOut()
        executionResult.outputLines.size shouldBe 16
    }
})
