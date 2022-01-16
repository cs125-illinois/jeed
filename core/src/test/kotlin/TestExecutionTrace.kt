package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain

class TestExecutionTrace : StringSpec({
    "should register for line trace events" {
        val result = Source.fromJava(
            """
public class Main {
  public static void main() {
    int i = 4;
    i += 1;
    System.out.println(i);
  }
}""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(LineTrace).addPlugin(ExecutionTrace))
        val executionTrace = result.pluginResult(ExecutionTrace)
        executionTrace.steps shouldContain ExecutionStep.Line("Main.java", 3)
    }
})
