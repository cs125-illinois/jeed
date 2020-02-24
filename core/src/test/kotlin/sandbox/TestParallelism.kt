package edu.illinois.cs.cs125.jeed.core.sandbox

import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments
import edu.illinois.cs.cs125.jeed.core.compile
import edu.illinois.cs.cs125.jeed.core.execute
import edu.illinois.cs.cs125.jeed.core.fromSnippet
import edu.illinois.cs.cs125.jeed.core.haveCompleted
import edu.illinois.cs.cs125.jeed.core.haveTimedOut
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.matchers.doubles.shouldBeLessThan
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNot
import io.kotlintest.specs.StringSpec
import java.util.stream.Collectors
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class TestParallelism : StringSpec({
    "should execute correctly in parallel using streams" {
        (0..8).toList().parallelStream().map { value ->
            val result = runBlocking {
                Source.fromSnippet(
                    """
for (int i = 0; i < 32; i++) {
    for (long j = 0; j < 1024 * 1024; j++);
    System.out.println($value);
}
                    """.trim()
                ).compile().execute(SourceExecutionArguments(timeout = 1000L))
            }
            result should haveCompleted()
            result.stdoutLines shouldHaveSize 32
            result.stdoutLines.all { it.line.trim() == value.toString() } shouldBe true
        }
    }
    "should execute correctly in parallel using coroutines" {
        (0..8).toList().map { value ->
            async {
                Pair(
                    Source.fromSnippet(
                        """
for (int i = 0; i < 32; i++) {
    for (long j = 0; j < 1024 * 1024; j++);
    System.out.println($value);
}
                    """.trim()
                    ).compile().execute(SourceExecutionArguments(timeout = 1000L)), value
                )
            }
        }.map { it ->
            val (result, value) = it.await()
            result should haveCompleted()
            result.stdoutLines shouldHaveSize 32
            result.stdoutLines.all { it.line.trim() == value.toString() } shouldBe true
        }
    }
    "should execute efficiently in parallel using streams" {
        val compiledSources = (0..8).toList().map {
            async {
                Source.fromSnippet(
                    """
for (int i = 0; i < 32; i++) {
    for (long j = 0; j < 1024 * 1024 * 1024; j++);
}
                    """.trim()
                ).compile()
            }
        }.map { it.await() }

        lateinit var results: List<Sandbox.TaskResults<out Any?>>
        val totalTime = measureTimeMillis {
            results = compiledSources.parallelStream().map {
                runBlocking {
                    it.execute(SourceExecutionArguments(timeout = 1000L))
                }
            }.collect(Collectors.toList()).toList()
        }

        val individualTimeSum = results.map { result ->
            result shouldNot haveCompleted()
            result should haveTimedOut()
            result.totalDuration.toMillis()
        }.sum()

        totalTime.toDouble() shouldBeLessThan individualTimeSum * 0.8
    }
    "should execute efficiently in parallel using coroutines" {
        val compiledSources = (0..8).toList().map {
            async {
                Source.fromSnippet(
                    """
for (int i = 0; i < 32; i++) {
    for (long j = 0; j < 1024 * 1024 * 1024; j++);
}
                    """.trim()
                ).compile()
            }
        }.map { it.await() }

        lateinit var results: List<Sandbox.TaskResults<out Any?>>
        val totalTime = measureTimeMillis {
            results = compiledSources.map {
                async {
                    it.execute(SourceExecutionArguments(timeout = 1000L))
                }
            }.map { it.await() }
        }

        val individualTimeSum = results.map { result ->
            result shouldNot haveCompleted()
            result should haveTimedOut()
            result.totalDuration.toMillis()
        }.sum()

        totalTime.toDouble() shouldBeLessThan individualTimeSum * 0.8
    }
})
