package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Suppress("ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE")
class TestCoroutines : StringSpec({
    "should allow coroutines to run" {
        val executionResult = Source(
            mapOf(
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
            )
        ).kompile().execute(SourceExecutionArguments(timeout = 10000)) // cache rewritten isolated classes
        executionResult shouldNot haveTimedOut()
        executionResult should haveCompleted()
        executionResult should haveOutput("1")
    }
    "should capture output from coroutines" {
        // Dummy task to force GlobalScope to be initialized before the test runs
        var i = 0
        GlobalScope.launch { i = 1 }
        val executionResult = Source(
            mapOf(
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
            )
        ).kompile().execute()
        i shouldBe 1
        executionResult shouldNot haveTimedOut()
        executionResult should haveOutput("Hello\nWorld!")
    }
    "should support multiple unscoped coroutines" {
        val executionResult = Source(
            mapOf(
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
            )
        ).kompile().execute()

        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
    }
    "should support multiple coroutines joined automatically" {
        val executionResult = Source(
            mapOf(
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
            )
        ).kompile().execute(SourceExecutionArguments(maxOutputLines = 1500))

        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult.outputLines.size shouldBe 1025
        executionResult.outputLines[1024].line shouldBe "Last"
    }
    "should prevent coroutines from escaping the sandbox" {
        // Dummy task to force GlobalScope to be initialized before the test runs
        var i = 0
        GlobalScope.launch { i = 1 }
        val executionResult = Source(
            mapOf(
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
            )
        ).kompile().execute()
        assert(executionResult.permissionRequests.any { it.permission.name == "exitVM.-1" && !it.granted })
    }
    "should allow an unscoped coroutine to try to finish in time" {
        val kompileResult = Source(
            mapOf(
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
            )
        ).kompile()
        val executionArguments = SourceExecutionArguments(waitForShutdown = true)
        repeat(16) { // Flaky
            val executionResult = kompileResult.execute(executionArguments = executionArguments)
            executionResult shouldNot haveTimedOut()
            executionResult should haveCompleted()
            executionResult should haveOutput("Started\nFinished")
        }
    }
    "should allow multiple unscoped coroutines to try to finish in time" {
        val kompileResult = Source(
            mapOf(
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
            )
        ).kompile()
        val executionArguments = SourceExecutionArguments(waitForShutdown = true)
        repeat(8) { // Flaky
            val executionResult = kompileResult.execute(executionArguments = executionArguments)
            executionResult shouldNot haveTimedOut()
            executionResult should haveCompleted()
            executionResult.outputLines.size shouldBe 257
            executionResult.outputLines[0].line shouldBe "Started"
        }
    }
    "should not give coroutines more time than they need" {
        val executionResult = Source(
            mapOf(
                "Main.kt" to """
import kotlinx.coroutines.*

fun main() {
    GlobalScope.launch {
        println("Finished")
    }
}
                """.trimIndent()
            )
        ).kompile().execute(SourceExecutionArguments(timeout = 9000L, waitForShutdown = true))
        executionResult should haveCompleted()
        executionResult should haveOutput("Finished")
        executionResult.executionInterval.length shouldBeLessThan (5000L)
    }
    "should terminate runaway unscoped coroutines" {
        val executionResult = Source(
            mapOf(
                "Main.kt" to """
import kotlinx.coroutines.*

fun main() {
    val job = GlobalScope.launch {
        println("Coroutine")
        while (true) {}
    }
}
                """.trimIndent()
            )
        ).kompile().execute(SourceExecutionArguments(waitForShutdown = true))
        // execute will throw if the sandbox couldn't be shut down
        executionResult should haveOutput("Coroutine")
    }
    "should terminate runaway child coroutines" {
        val executionResult = Source(
            mapOf(
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
            )
        ).kompile().execute(SourceExecutionArguments(waitForShutdown = true))
        executionResult should haveTimedOut()
        executionResult.outputLines.size shouldBe 16
    }
    "should run suspending main" {
        val executionResult = Source(
            mapOf(
                "Main.kt" to """
import kotlinx.coroutines.*

suspend fun getUser(id: Int): String? {
  val users = listOf("Harsh", "Amirtha", "Geoff")
  delay(40) // simulated load time
  return users.elementAtOrNull(id);
}

suspend fun main() = coroutineScope {
  val first =  async { getUser(1) } 
  val second = async { getUser(2) }
  println("Hello '$'{first.await()}")
  println("Hello '$'{second.await()}")
}
                """.trimIndent()
            )
        ).kompile().execute()

        executionResult shouldNot haveTimedOut()
        executionResult should haveCompleted()
        executionResult.outputLines.size shouldBe 2
    }
    "coroutines should run concurrently" {
        val executionResult = Source(
            mapOf(
                "Main.kt" to """
import kotlinx.coroutines.*
import java.util.concurrent.atomic.*

var counter = 0
var atomicCounter = AtomicInteger()
fun main() {
  runBlocking {
    withContext(Dispatchers.Default) {
      coroutineScope {
        repeat(100) {
          launch {
            repeat(1000) {
              counter++
              atomicCounter.incrementAndGet()
            }
          }
        }
      }
    }
  }
  println(counter)
  println(atomicCounter)
}
                """.trimIndent()
            )
        ).kompile().execute()

        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult.outputLines.size shouldBe 2
        executionResult.outputLines[0].line.toInt() shouldNotBe 100000
        executionResult.outputLines[1].line.toInt() shouldBe 100000
    }
    "coroutines started on GlobalScope should not produce timeouts" {
        val executionResult = Source(
            mapOf(
                "Main.kt" to """
import kotlinx.coroutines.*

fun main() = runBlocking {
    GlobalScope.launch {
      repeat(1000) {
        delay(100)
      }
    }
    delay(50)
}
                """.trimIndent()
            )
        ).kompile().execute()

        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
    }
})
