package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec

class TestBenchmark : StringSpec({
    "!should rapidly compile snippets" {
        val source = Source.fromJava(
            """
public class Main {
  public static void main(String[] unused) {
    System.out.println("Hello, world!");
  }
}
            """.trimIndent()
        )

        source.compile(CompilationArguments(useCache = false)).also { compiledSource ->
            println(compiledSource.interval.length)
        }
        source.compile(CompilationArguments(useCache = false)).also { compiledSource ->
            println(compiledSource.interval.length)
        }
    }
    "!should rapidly execute snippets" {
        val compiledSource = Source.fromJava(
            """
public class Main {
  public static void main(String[] unused) {
    System.out.println("Hello, world!");
  }
}
            """.trimIndent()
        ).compile()

        compiledSource.execute().also { executionResult ->
            println(executionResult.interval.length)
        }
        compiledSource.cexecute().also { executionResult ->
            println(executionResult.interval.length)
        }
    }
})
