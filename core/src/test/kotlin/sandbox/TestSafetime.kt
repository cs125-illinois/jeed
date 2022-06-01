package edu.illinois.cs.cs125.jeed.core.sandbox

import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments
import edu.illinois.cs.cs125.jeed.core.compile
import edu.illinois.cs.cs125.jeed.core.execute
import edu.illinois.cs.cs125.jeed.core.fromSnippet
import edu.illinois.cs.cs125.jeed.core.haveCompleted
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.util.stream.Collectors

class TestSafetime : StringSpec({
    "should execute correctly in parallel using streams" {
        (0..8).toList().parallelStream().map { value ->
            runBlocking {
                repeat(8) {
                    val result =
                        Source.fromSnippet(
                            """
for (int i = 0; i < 2; i++) {
    for (long j = 0; j < 1024 * 1024; j++);
    System.out.println($value);
}
                    """.trim()
                        ).compile().execute(SourceExecutionArguments(timeout = 128L))

                    result should haveCompleted()
                    result.stdoutLines shouldHaveSize 2
                    result.stdoutLines.all { it.line.trim() == value.toString() } shouldBe true
                    System.gc()
                    System.gc()
                }
            }
        }.collect(Collectors.toList())
    }
})
