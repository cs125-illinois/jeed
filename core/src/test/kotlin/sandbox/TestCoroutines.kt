package edu.illinois.cs.cs125.jeed.core.sandbox

import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.execute
import edu.illinois.cs.cs125.jeed.core.haveCompleted
import edu.illinois.cs.cs125.jeed.core.kompile
import io.kotlintest.shouldNot
import io.kotlintest.specs.StringSpec

class TestCoroutines : StringSpec({
    "should prevent coroutines from escaping the sandbox" {
        val executionResult = Source(mapOf(
            "Main.kt" to """
import kotlinx.coroutines.*

fun main() {
    GlobalScope.launch {
        delay(10L)
        println("World!")
    }
    println("Hello")
    Thread.sleep(50L)
}
""".trimIndent()
        )).kompile().execute()

        executionResult shouldNot haveCompleted()
    }
})
