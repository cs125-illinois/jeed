package edu.illinois.cs.cs125.jeed.core.sandbox

import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments
import edu.illinois.cs.cs125.jeed.core.execute
import edu.illinois.cs.cs125.jeed.core.haveCompleted
import edu.illinois.cs.cs125.jeed.core.haveOutput
import edu.illinois.cs.cs125.jeed.core.haveTimedOut
import edu.illinois.cs.cs125.jeed.core.kompile
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNot
import io.kotlintest.specs.StringSpec
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.PropertyPermission

class TestCoroutines : StringSpec({

    "should allow coroutines to run" {
        // Dummy task to force GlobalScope to be initialized before the test runs
        var i = 0
        GlobalScope.launch { i = 1 }
        val executionResult = Source(mapOf(
            "Main.kt" to """
import kotlinx.coroutines.*

fun main() {
    var i = 0
    val job = GlobalScope.launch {
        i = 1
    }
    Thread.sleep(50L)
    println(i)
}
""".trimIndent()
        )).kompile().execute(executionArguments = SourceExecutionArguments(
            //permissions = setOf(PropertyPermission("java.specification.version", "read"))
        ))
        executionResult shouldNot haveTimedOut()
        executionResult should haveOutput("1")
        executionResult should haveCompleted()
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
        )).kompile().execute(executionArguments = SourceExecutionArguments(
            maxExtraThreads = 4,
            permissions = setOf(PropertyPermission("java.specification.version", "read"))
        ))
        executionResult shouldNot haveTimedOut()
        executionResult should haveOutput("Hello\nWorld!")
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
        //System.exit(-1);
    }
    Thread.sleep(50L)
}
""".trimIndent()
        )).kompile().execute(executionArguments = SourceExecutionArguments(
            maxExtraThreads = 4,
            permissions = setOf(PropertyPermission("java.specification.version", "read"))
        ))
        // TODO: Uncomment the System.exit call
        executionResult.permissionDenied shouldBe true
    }

})
