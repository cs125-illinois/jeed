package edu.illinois.cs.cs125.jeed.core.sandbox

import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments
import edu.illinois.cs.cs125.jeed.core.execute
import edu.illinois.cs.cs125.jeed.core.haveCompleted
import edu.illinois.cs.cs125.jeed.core.haveOutput
import edu.illinois.cs.cs125.jeed.core.haveTimedOut
import edu.illinois.cs.cs125.jeed.core.kompile
import io.kotlintest.should
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
        i = 1
    }
    runBlocking {
        job.join()
    }
    println(i)
}
""".trimIndent()
        )).kompile().execute(executionArguments = SourceExecutionArguments(
            timeout = 200,
            maxExtraThreads = 2
        ))
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
        )).kompile().execute(executionArguments = SourceExecutionArguments(
            maxExtraThreads = 2
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
        System.exit(-1)
    }
    runBlocking {
        job.join()
    }
}
""".trimIndent()
        )).kompile().execute(executionArguments = SourceExecutionArguments(
            maxExtraThreads = 2
        ))
        assert(executionResult.permissionRequests.any { it.permission.name == "exitVM.-1" && !it.granted })
    }

})
