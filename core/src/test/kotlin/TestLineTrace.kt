package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldHaveAtMostSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe

class TestLineTrace : StringSpec({
    "should trace a main method" {
        val result = Source.fromJava(
            """
public class Main {
  public static void main() {
    int i = 4;
    i += 1;
    System.out.println(i);
  }
}""".trim()
        ).compile().execute(SourceExecutionArguments(plugins = listOf(LineTrace)))
        result should haveCompleted()
        result should haveOutput("5")
        val trace = result.pluginResult(LineTrace)
        trace.steps shouldHaveAtLeastSize 3
        trace.steps[0] shouldBe LineTraceResult.LineStep("Main.java", 3)
    }

    "should trace an if statement" {
        val result = Source.fromJava(
            """
public class Main {
  public static void main() {
    int i = 4;
    if (i > 1) {
      System.out.println("yes");
    }
  }
}""".trim()
        ).compile().execute(SourceExecutionArguments(plugins = listOf(LineTrace)))
        result should haveCompleted()
        result should haveOutput("yes")
        val trace = result.pluginResult(LineTrace)
        trace.steps shouldHaveAtLeastSize 3
        trace.steps.map { it.line } shouldContain 5
    }

    "should show that an if statement wasn't entered" {
        val result = Source.fromJava(
            """
public class Main {
  public static void main() {
    int i = 4;
    if (i > 9) {
      System.out.println("yes");
      System.out.println("the universe has fractured");
      System.out.println("all is possible");
    }
  }
}""".trim()
        ).compile().execute(SourceExecutionArguments(plugins = listOf(LineTrace)))
        result should haveCompleted()
        result should haveOutput("")
        val trace = result.pluginResult(LineTrace)
        trace.steps shouldHaveAtMostSize 3
        trace.steps.map { it.line } shouldNotContain 5
    }

    "should trace an if-else statement" {
        val result = Source.fromJava(
            """
public class Main {
  public static void main() {
    int i = 0;
    if (i > 1) {
      System.out.println("yes");
    } else {
      System.out.println("no");
    }
  }
}""".trim()
        ).compile().execute(SourceExecutionArguments(plugins = listOf(LineTrace)))
        result should haveCompleted()
        result should haveOutput("no")
        val trace = result.pluginResult(LineTrace)
        trace.steps.map { it.line } shouldNotContain 5
        trace.steps.map { it.line } shouldContain 7
    }

    "should trace a for loop" {
        val result = Source.fromJava(
            """
public class Main {
  public static void main() {
    for (int i = 0; i < 3; i++) {
      System.out.println(i);
    }
  }
}""".trim()
        ).compile().execute(SourceExecutionArguments(plugins = listOf(LineTrace)))
        result should haveCompleted()
        result should haveOutput("0\n1\n2")
        val trace = result.pluginResult(LineTrace)
        trace.steps.filter { it.line == 4 }.size shouldBe 3
        trace.steps.filter { it.line == 3 }.size shouldBe 4
    }

    "should trace a while loop" {
        val result = Source.fromJava(
            """
public class Main {
  public static void main() {
    int i = 20;
    while (i > 0) {
      System.out.println(i);
      i /= 2;
      if (i % 2 != 0) {
        i -= 1;
      }
    }
  }
}""".trim()
        ).compile().execute(SourceExecutionArguments(plugins = listOf(LineTrace)))
        result should haveCompleted()
        result should haveOutput("20\n10\n4\n2")
        val trace = result.pluginResult(LineTrace)
        trace.steps.filter { it.line == 5 }.size shouldBe 4
        trace.steps.filter { it.line == 8 }.size shouldBe 2
    }

    "should trace multiple functions" {
        val result = Source.fromJava(
            """
public class Main {
  public static void main() {
    for (int i = 0; i < 10; i++) {
      showIfOdd(i);
    }
    System.out.println("Done");
  }
  private static void showIfOdd(int i) {
    if (i % 2 != 0) {
      System.out.println(i);
    }
  }
}""".trim()
        ).compile().execute(SourceExecutionArguments(plugins = listOf(LineTrace)))
        result should haveCompleted()
        result should haveOutput("1\n3\n5\n7\n9\nDone")
        val trace = result.pluginResult(LineTrace)
        trace.steps.filter { it.line == 4 }.size shouldBe 10
        trace.steps.filter { it.line == 6 }.size shouldBe 1
        trace.steps.filter { it.line == 9 }.size shouldBe 10
        trace.steps.filter { it.line == 10 }.size shouldBe 5
    }
})
