package edu.illinois.cs.cs125.jeed.core.sandbox

import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments
import edu.illinois.cs.cs125.jeed.core.compile
import edu.illinois.cs.cs125.jeed.core.execute
import edu.illinois.cs.cs125.jeed.core.fromSnippet
import edu.illinois.cs.cs125.jeed.core.haveCompleted
import edu.illinois.cs.cs125.jeed.core.haveOutput
import edu.illinois.cs.cs125.jeed.core.haveTimedOut
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import java.util.stream.Collectors
import kotlin.system.measureTimeMillis

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
        }.collect(Collectors.toList())
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
                    ).compile().execute(SourceExecutionArguments(timeout = 1000L)),
                    value
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
    "!parallelStream should work in sandbox" {
        // Untrusted code must not be allowed to specify the code that runs in a trusted thread
        // TODO? Give confined tasks their own ForkJoinPool so they can use parallel streams
        Source.fromSnippet(
            """
import java.util.List;
import java.util.Arrays;
List<Integer> listOfNumbers = Arrays.asList(1, 2, 3, 4, 5);
int sum = listOfNumbers.parallelStream().reduce(0, Integer::sum);
System.out.println(sum);
            """.trimIndent()
        ).compile().execute().also {
            it should haveCompleted()
            it.deniedPermissions shouldHaveSize 0
            it should haveOutput("15")
        }
    }
})
