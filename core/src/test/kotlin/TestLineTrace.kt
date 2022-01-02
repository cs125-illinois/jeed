package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldHaveAtMostSize
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
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

    "should trace a try-catch-finally block" {
        val result = Source.fromJava(
            """
public class Main {
  public static void main() {
    try {
      System.out.println("Try");
      String s = null;
      s.toString();
      System.out.println("Hmm");
    } catch (Exception e) {
      System.out.println("Catch");
    } finally {
      System.out.println("Finally");
    }
  }
}""".trim()
        ).compile().execute(SourceExecutionArguments(plugins = listOf(LineTrace)))
        result should haveCompleted()
        result should haveOutput("Try\nCatch\nFinally")
        val trace = result.pluginResult(LineTrace)
        trace.steps.filter { it.line == 4 }.size shouldBe 1
        trace.steps.filter { it.line == 7 }.size shouldBe 0
        trace.steps.filter { it.line == 9 }.size shouldBe 1
        trace.steps.filter { it.line == 11 }.size shouldBe 1
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

    "should trace multiple classes" {
        val result = Source.fromJava(
            """
public class Main {
  public static void main() {
    for (int i = 0; i < 10; i++) {
      ShowIfOdd.showIfOdd(i);
    }
    System.out.println("Done");
  }
}
public class ShowIfOdd {
  public static void showIfOdd(int i) {
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
        trace.steps.filter { it.line == 11 }.size shouldBe 10
        trace.steps.filter { it.line == 12 }.size shouldBe 5
    }

    "should trace multiple files" {
        val result = Source(
            mapOf(
                "Main.java" to """
public class Main {
  public static void main() {
    for (int i = 0; i < 10; i++) {
      ShowIfOdd.showIfOdd(i);
    }
    System.out.println("Done");
  }
}""".trim(),
                "ShowIfOdd.java" to """
public class ShowIfOdd {
  public static void showIfOdd(int i) {
    if (i % 2 != 0) {
      System.out.println(i);
    }
  }
}""".trim()
            )
        ).compile().execute(SourceExecutionArguments(plugins = listOf(LineTrace)))
        result should haveCompleted()
        result should haveOutput("1\n3\n5\n7\n9\nDone")
        val trace = result.pluginResult(LineTrace)
        trace.steps.filter { it.source == "Main.java" && it.line == 4 }.size shouldBe 10
        trace.steps.filter { it.source == "Main.java" && it.line == 6 }.size shouldBe 1
        trace.steps.filter { it.source == "ShowIfOdd.java" && it.line == 3 }.size shouldBe 10
        trace.steps.filter { it.source == "ShowIfOdd.java" && it.line == 4 }.size shouldBe 5
    }

    "should trace a simple snippet" {
        val source = Source.fromSnippet(
            """System.out.println("Hello");""".trim()
        )
        val result = source.compile().execute(SourceExecutionArguments(plugins = listOf(LineTrace)))
        result should haveCompleted()
        result should haveOutput("Hello")
        val trace = result.pluginResult(LineTrace).remap(source)
        trace.steps shouldHaveSize 1
        trace.steps[0].line shouldBe 1
    }

    "should trace a snippet" {
        val source = Source.fromSnippet(
            """
            printWithObject();
            
            void printWithObject() {
              new Printer().print();
            }
            
            class Printer {
              void print() {
                System.out.println("Hello");
              }
              void unused() {
                System.out.println("Unused");
              }
            }
            """.trimIndent().trim()
        )
        val result = source.compile().execute(SourceExecutionArguments(plugins = listOf(LineTrace)))
        result should haveCompleted()
        result should haveOutput("Hello")
        val trace = result.pluginResult(LineTrace).remap(source)
        trace.steps shouldHaveAtLeastSize 3
        trace.steps[0].line shouldBe 1
        trace.steps.filter { it.line == 4 }.size shouldBe 1
        trace.steps.filter { it.line == 9 }.size shouldBe 1
        trace.steps.filter { it.line == 12 }.size shouldBe 0
    }

    "should trace a Kotlin method" {
        val result = Source.fromKotlin(
            """
            fun main() {
                println("Hello")
            }
            """.trimIndent()
        ).kompile().execute(SourceExecutionArguments(plugins = listOf(LineTrace)))
        result should haveCompleted()
        result should haveOutput("Hello")
        val trace = result.pluginResult(LineTrace)
        trace.steps shouldHaveAtMostSize 2
        trace.steps[0].line shouldBe 2
    }

    "should trace a Kotlin forEach loop" {
        val source = Source.fromKotlin(
            """
            fun main() {
                (1..3).forEach {
                    println(it * it)
                }
            }
            """.trimIndent()
        )
        val result = source.kompile().execute(SourceExecutionArguments(plugins = listOf(LineTrace)))
        result should haveCompleted()
        result should haveOutput("1\n4\n9")
        val trace = result.pluginResult(LineTrace).remap(source)
        trace.steps shouldHaveAtLeastSize 4
        trace.steps[0].line shouldBe 2
        trace.steps.filter { it.line == 3 }.size shouldBe 3
        trace.steps.filter { it.line == 5 }.size shouldBe 1
        trace.steps.filter { it.line > 5 }.size shouldBe 0
    }

    "should trace across Kotlin files" {
        val source = Source(
            mapOf(
                "Main.kt" to """
fun main() {
  println(test())
}
                """.trim(),
                "Test.kt" to """
fun test(): List<String> {
  return listOf("test", "me")
}
                """.trim()
            )
        )
        val result = source.kompile().execute(SourceExecutionArguments(plugins = listOf(LineTrace)))
        result should haveCompleted()
        result should haveOutput("[test, me]")
        val trace = result.pluginResult(LineTrace).remap(source)
        trace.steps shouldHaveAtLeastSize 2
        trace.steps[0] shouldBe LineTraceResult.LineStep("Main.kt", 2)
        trace.steps[1] shouldBe LineTraceResult.LineStep("Test.kt", 2)
    }

    "should trace a simple Kotlin snippet" {
        val source = Source.fromSnippet(
            """println("Hi")""",
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
        val result = source.kompile().execute(SourceExecutionArguments(plugins = listOf(LineTrace)))
        result should haveCompleted()
        result should haveOutput("Hi")
        val trace = result.pluginResult(LineTrace).remap(source)
        trace.steps shouldHaveSize 1
        trace.steps[0].line shouldBe 1
    }

    "should trace a Kotlin snippet with a loop" {
        val source = Source.fromSnippet(
            """
                val fruits = listOf("apple", "banana")
                fruits.forEach {
                    println(it.uppercase())
                }
            """.trimIndent(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
        val result = source.kompile().execute(SourceExecutionArguments(plugins = listOf(LineTrace)))
        result should haveCompleted()
        result should haveOutput("APPLE\nBANANA")
        val trace = result.pluginResult(LineTrace).remap(source)
        trace.steps shouldHaveAtLeastSize 3
        trace.steps[0].line shouldBe 1
        // NOTE: The Kotlin compiler sometimes adds an extra line number entry, double-counting a line
        trace.steps.filter { it.line == 3 }.size shouldBeGreaterThanOrEqual 2
        trace.steps.filter { it.line == 4 }.size shouldBe 2
    }

    "should trace a Kotlin snippet with a method" {
        val source = Source.fromSnippet(
            """
                fun printLoud(text: String) {
                    println(text.uppercase())
                }
                
                val fruits = listOf("apple", "banana")
                fruits.forEach(::printLoud)
            """.trimIndent(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
        val result = source.kompile().execute(SourceExecutionArguments(plugins = listOf(LineTrace)))
        result should haveCompleted()
        result should haveOutput("APPLE\nBANANA")
        val trace = result.pluginResult(LineTrace).remap(source)
        trace.steps shouldHaveAtLeastSize 4
        trace.steps[0].line shouldBe 5
        trace.steps.filter { it.line == 2 }.size shouldBeGreaterThanOrEqual 2
        trace.steps.filter { it.line == 3 }.size shouldBe 2
        trace.steps.filter { it.line == 4 }.size shouldBe 0
    }
})
