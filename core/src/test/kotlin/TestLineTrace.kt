package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.beEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.collections.shouldHaveAtMostSize
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.beGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.endWith
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.beInstanceOf
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.InvocationTargetException

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
        ).compile().execute(SourceExecutionArguments().addPlugin(LineTrace))
        result should haveCompleted()
        result should haveOutput("5")
        val trace = result.pluginResult(LineTrace)
        trace.steps shouldHaveAtLeastSize 3
        trace.steps[0] shouldBe LineTraceResult.LineStep("Main.java", 3, 0)
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
        ).compile().execute(SourceExecutionArguments().addPlugin(LineTrace))
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
        ).compile().execute(SourceExecutionArguments().addPlugin(LineTrace))
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
        ).compile().execute(SourceExecutionArguments().addPlugin(LineTrace))
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
        ).compile().execute(SourceExecutionArguments().addPlugin(LineTrace))
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
        ).compile().execute(SourceExecutionArguments().addPlugin(LineTrace))
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
        ).compile().execute(SourceExecutionArguments().addPlugin(LineTrace))
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
        ).compile().execute(SourceExecutionArguments().addPlugin(LineTrace))
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
        ).compile().execute(SourceExecutionArguments().addPlugin(LineTrace))
        result should haveCompleted()
        result should haveOutput("1\n3\n5\n7\n9\nDone")
        val trace = result.pluginResult(LineTrace)
        trace.steps.filter { it.line == 4 }.size shouldBe 10
        trace.steps.filter { it.line == 6 }.size shouldBe 1
        trace.steps.filter { it.line == 11 }.size shouldBe 10
        trace.steps.filter { it.line == 12 }.size shouldBe 5
        trace.linesRun shouldBe trace.steps.size
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
        ).compile().execute(SourceExecutionArguments().addPlugin(LineTrace))
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
        val result = source.compile().execute(SourceExecutionArguments().addPlugin(LineTrace))
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
        val result = source.compile().execute(SourceExecutionArguments().addPlugin(LineTrace))
        result should haveCompleted()
        result should haveOutput("Hello")
        val trace = result.pluginResult(LineTrace).remap(source)
        trace.steps shouldHaveAtLeastSize 3
        trace.steps[0].line shouldBe 1
        trace.steps.filter { it.line == 4 }.size shouldBe 1
        trace.steps.filter { it.line == 9 }.size shouldBe 1
        trace.steps.filter { it.line == 12 }.size shouldBe 0
    }

    "should report multiple calls during external iteration" {
        val source = Source.fromSnippet(
            """
            import java.util.ArrayList;
            var list = new ArrayList<String>();
            list.add("a");
            list.add("b");
            list.add("c");
            list.forEach(s -> {
                System.out.println(s); }); // Crazy bracing to ensure only this line is called
            """.trimIndent()
        )
        val result = source.compile().execute(SourceExecutionArguments().addPlugin(LineTrace))
        result should haveCompleted()
        result should haveOutput("a\nb\nc")
        val trace = result.pluginResult(LineTrace).remap(source)
        trace.steps.filter { it.line == 6 }.size shouldBe 1
        trace.steps.filter { it.line == 7 }.size shouldBe 3
    }

    "should stop logging lines after reaching the recording limit" {
        val source = Source.fromSnippet(
            """
            long i = 0;
            while (true) {
                i += 2;
                i -= 1;
            }
            """.trimIndent()
        )
        val lineTraceArguments = LineTraceArguments(recordedLineLimit = 5000L) // Reduced because slow under debugger
        val result = source.compile().execute(SourceExecutionArguments().addPlugin(LineTrace, lineTraceArguments))
        result should haveTimedOut()
        val rawTrace = result.pluginResult(LineTrace)
        val trace = rawTrace.remap(source)
        trace.steps.filter { it.line == 3 }.size shouldBeGreaterThan 100
        rawTrace.steps.size shouldBe lineTraceArguments.recordedLineLimit
    }

    "should keep counting after reaching the recording limit" {
        val source = Source.fromSnippet(
            """
            long i = 0;
            while (true) {
                i += 2;
                i -= 1;
            }
            """.trimIndent()
        )
        val lineTraceArguments = LineTraceArguments(recordedLineLimit = 5000L)
        val result = source.compile().execute(SourceExecutionArguments().addPlugin(LineTrace, lineTraceArguments))
        result should haveTimedOut()
        val trace = result.pluginResult(LineTrace).remap(source)
        trace.linesRun.toInt() should beGreaterThan(trace.arguments.recordedLineLimit.toInt())
    }

    "should trace a Kotlin method" {
        val result = Source.fromKotlin(
            """
            fun main() {
                println("Hello")
            }
            """.trimIndent()
        ).kompile().execute(SourceExecutionArguments().addPlugin(LineTrace))
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
        val result = source.kompile().execute(SourceExecutionArguments().addPlugin(LineTrace))
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
        val result = source.kompile().execute(SourceExecutionArguments().addPlugin(LineTrace))
        result should haveCompleted()
        result should haveOutput("[test, me]")
        val trace = result.pluginResult(LineTrace).remap(source)
        trace.steps shouldHaveAtLeastSize 2
        trace.steps[0] shouldBe LineTraceResult.LineStep("Main.kt", 2, 0)
        trace.steps[1] shouldBe LineTraceResult.LineStep("Test.kt", 2, 0)
    }

    "should trace a simple Kotlin snippet" {
        val source = Source.fromSnippet(
            """println("Hi")""", SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
        val result = source.kompile().execute(SourceExecutionArguments().addPlugin(LineTrace))
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
                    println(it + ".")
                }
            """.trimIndent(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
        val result = source.kompile().execute(SourceExecutionArguments().addPlugin(LineTrace))
        result should haveCompleted()
        result should haveOutput("apple.\nbanana.")
        val trace = result.pluginResult(LineTrace).remap(source)
        trace.steps shouldHaveAtLeastSize 5
        trace.steps[0].line shouldBe 1
        trace.steps.filter { it.line == 3 }.size shouldBe 2
    }

    "should trace a Kotlin snippet with an if expression" {
        val source = Source.fromSnippet(
            """
                val i = System.currentTimeMillis() // Avoid compile-time evaluation
                val verdict = if (i > 0) {
                    "Positive"
                } else {
                    "Non-positive"
                }
                println(verdict)
            """.trimIndent(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
        val result = source.kompile().execute(SourceExecutionArguments().addPlugin(LineTrace))
        result should haveCompleted()
        result should haveOutput("Positive")
        val trace = result.pluginResult(LineTrace).remap(source)
        trace.steps shouldHaveAtLeastSize 4 // NOTE: Storing the if expression's result visits line 2 again
        trace.steps[0].line shouldBe 1
        trace.steps[1].line shouldBe 2
        trace.steps[2].line shouldBe 3
        trace.steps.last().line shouldBe 7
        trace.steps.filter { it.line == 5 }.size shouldBe 0
    }

    "should trace a Kotlin snippet with a loop and inlined method" {
        val source = Source.fromSnippet(
            """
                val fruits = listOf("apple", "banana")
                fruits.forEach {
                    println(it.uppercase())
                }
            """.trimIndent(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
        val result = source.kompile().execute(SourceExecutionArguments().addPlugin(LineTrace))
        result should haveCompleted()
        result should haveOutput("APPLE\nBANANA")
        val trace = result.pluginResult(LineTrace).remap(source)
        trace.steps shouldHaveAtLeastSize 3
        trace.steps[0].line shouldBe 1
        trace.steps.filter { it.line == 3 }.size shouldBe 2
    }

    "should skip Kotlin inline/loop duplicates" {
        val source = Source.fromSnippet(
            """
                val fruits = listOf("apple", "banana")
                fruits.forEach { println(it.uppercase()) }
            """.trimIndent(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
        val result = source.kompile().execute(SourceExecutionArguments().addPlugin(LineTrace))
        result should haveCompleted()
        result should haveOutput("APPLE\nBANANA")
        val trace = result.pluginResult(LineTrace).remap(source)
        trace.steps.filter { it.line == 2 }.size shouldBeLessThanOrEqual 3
    }

    "should allow recording duplicates" {
        val source = Source.fromSnippet(
            """
                val fruits = listOf("apple", "banana")
                fruits.forEach { println(it.uppercase()) }
            """.trimIndent(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
        val result = source.kompile().execute(
            SourceExecutionArguments().addPlugin(LineTrace, LineTraceArguments(coalesceDuplicates = false))
        )
        result should haveCompleted()
        result should haveOutput("APPLE\nBANANA")
        val trace = result.pluginResult(LineTrace).remap(source)
        trace.steps.filter { it.line == 2 }.size shouldBeGreaterThan 3
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
        val result = source.kompile().execute(SourceExecutionArguments().addPlugin(LineTrace))
        result should haveCompleted()
        result should haveOutput("APPLE\nBANANA")
        val trace = result.pluginResult(LineTrace).remap(source)
        trace.steps shouldHaveAtLeastSize 4
        trace.steps[0].line shouldBe 5
        trace.steps.filter { it.line == 2 }.size shouldBeGreaterThanOrEqual 2
        trace.steps.filter { it.line == 3 }.size shouldBe 2
        trace.steps.filter { it.line == 4 }.size shouldBe 0
    }

    "should trace multiple threads" {
        val source = Source.fromSnippet(
            """
public class Example implements Runnable {
    public void run() {
        System.out.println("Ended");
    }
}
Thread thread = new Thread(new Example());
thread.start();
System.out.println("Started");
try {
    thread.join();
} catch (Exception e) {
    throw new RuntimeException(e);
}
        """.trim()
        )
        val result = source.compile().execute(SourceExecutionArguments(maxExtraThreads = 1).addPlugin(LineTrace))
        result should haveCompleted()
        result should haveOutput("Started\nEnded")
        val rawTrace = result.pluginResult(LineTrace)
        rawTrace.linesRun shouldBe rawTrace.steps.size
        val trace = rawTrace.remap(source)
        val mainLines = trace.steps.filter { it.threadIndex == 0 }.map { it.line }
        mainLines shouldContain 6
        mainLines shouldContain 10
        mainLines shouldNotContain 3
        val extraLines = trace.steps.filter { it.threadIndex == 1 }.map { it.line }
        extraLines shouldContain 3
        extraLines shouldNotContain 6
        trace.steps.filter { it.threadIndex >= 2 }.size shouldBe 0
    }

    "should trace multiple threads using coroutines" {
        val source = Source.fromKotlin(
            """
            import kotlinx.coroutines.*
            fun main() {
                GlobalScope.launch {
                    delay(100)
                    println("Finished")
                }
                println("Started")
            }
            """.trimIndent()
        )
        val executionArgs = SourceExecutionArguments(waitForShutdown = true, timeout = 2000).addPlugin(LineTrace)
        val result = source.kompile().execute(executionArgs)
        result should haveCompleted()
        result should haveOutput("Started\nFinished")
        val rawTrace = result.pluginResult(LineTrace)
        rawTrace.linesRun shouldBe rawTrace.steps.size
        val trace = rawTrace.remap(source)
        val mainLines = trace.steps.filter { it.threadIndex == 0 }.map { it.line }
        mainLines shouldContain 3
        mainLines shouldContain 7
        mainLines shouldNotContain 5
        val extraLines = trace.steps.filter { it.threadIndex == 1 }.map { it.line }
        extraLines shouldContain 5
        extraLines shouldNotContain 7
        trace.steps.filter { it.threadIndex >= 2 }.size shouldBe 0
        trace.steps.filter { it.source != "Main.kt" }.size shouldBe 0
    }

    "should limit executed lines by killing the sandbox" {
        val source = Source.fromSnippet(
            """
            long i = 0;
            while (true) {
                i += 2;
                i -= 1;
            }
            """.trimIndent()
        )
        val result = source.compile().execute(
            SourceExecutionArguments().addPlugin(
                LineTrace, LineTraceArguments(recordedLineLimit = 0, runLineLimit = 100)
            )
        )
        result should haveBeenKilled()
        result.killReason shouldBe LineTrace.KILL_REASON
        result shouldNot haveCompleted()
        result shouldNot haveTimedOut()
        val trace = result.pluginResult(LineTrace)
        trace.linesRun shouldBe 100
        trace.steps should beEmpty()
    }

    "should limit executed lines by throwing an error" {
        val source = Source.fromSnippet(
            """
            try {
              long i = 0;
              while (true) {
                i += 2;
                i -= 1;
              }
            } catch (Throwable t) {}
            """.trimIndent()
        )
        val lineTraceArgs = LineTraceArguments(
            recordedLineLimit = 0,
            runLineLimit = 100,
            runLineLimitExceededAction = LineTraceArguments.RunLineLimitAction.THROW_ERROR
        )
        val result = source.compile().execute(SourceExecutionArguments().addPlugin(LineTrace, lineTraceArgs))
        result shouldNot haveBeenKilled()
        result shouldNot haveCompleted()
        result shouldNot haveTimedOut()
        result.threw should beInstanceOf<LineLimitExceeded>()
        val trace = result.pluginResult(LineTrace)
        trace.linesRun shouldBe 100
    }

    "should limit total lines from multiple threads" {
        val source = Source.fromSnippet(
            """
public class Example implements Runnable {
    public void run() {
        long e = 0;
        while (true) {
            e += 1;
        }
    }
}
Thread thread = new Thread(new Example());
thread.start();
long i = 0;
while (true) {
    i += 1;
}
        """.trim()
        )
        val lineTraceArgs = LineTraceArguments(runLineLimit = 10000)
        val result =
            source.compile().execute(SourceExecutionArguments(maxExtraThreads = 1).addPlugin(LineTrace, lineTraceArgs))
        result should haveBeenKilled()
        val rawTrace = result.pluginResult(LineTrace)
        rawTrace.linesRun.toInt() shouldBeGreaterThan rawTrace.steps.size - 3 // May be killed before incrementing
        rawTrace.linesRun.toInt() shouldBeLessThanOrEqual rawTrace.steps.size
        val trace = rawTrace.remap(source)
        val mainLines = trace.steps.filter { it.threadIndex == 0 }.map { it.line }
        mainLines shouldContain 10
        mainLines shouldNotContain 5
        val extraLines = trace.steps.filter { it.threadIndex == 1 }.map { it.line }
        extraLines shouldContain 5
        extraLines shouldNotContain 10
        trace.linesRun shouldBeLessThanOrEqual lineTraceArgs.runLineLimit!! + lineTraceArgs.maxUnsynchronizedLines
    }

    "should closely limit total lines if required" {
        val source = Source.fromSnippet(
            """
public class Example implements Runnable {
    public void run() {
        long e = 0;
        while (true) {
            e += 1;
        }
    }
}
Thread thread = new Thread(new Example());
thread.start();
long i = 0;
while (true) {
    i += 1;
}
        """.trim()
        )
        val lineTraceArgs = LineTraceArguments(
            runLineLimit = 10000, recordedLineLimit = 0, maxUnsynchronizedLines = 0
        )
        val compiledSource = source.compile()
        repeat(10) {
            val result = compiledSource.execute(
                SourceExecutionArguments(maxExtraThreads = 1).addPlugin(
                    LineTrace, lineTraceArgs
                )
            )
            result should haveBeenKilled()
            val rawTrace = result.pluginResult(LineTrace)
            rawTrace.linesRun shouldBeLessThanOrEqual lineTraceArgs.runLineLimit!! + 1
        }
    }

    "should not allow untrusted code to reset the line counter" {
        val source = Source.fromSnippet(
            """edu.illinois.cs.cs125.jeed.core.LineTrace.resetLineCounts()""",
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
        val result = source.kompile().execute(SourceExecutionArguments().addPlugin(LineTrace))
        result shouldNot haveCompleted()
        result.permissionDenied shouldBe true
    }

    "should allow trusted code to reset the line counter" {
        val compiledSource = Source.fromJava(
            """
public class Main {
  public static void print(String text, int times) {
    for (int i = 0; i < times; i++) {
      System.out.print(text);
    } // At least 2, probably 3 lines per iteration
    System.out.println("");
  }
}""".trim()
        ).compile()
        val lineTraceArgs = LineTraceArguments(
            recordedLineLimit = 0, runLineLimit = 25
        )
        val plugins = listOf(ConfiguredSandboxPlugin(LineTrace, lineTraceArgs))
        val subtaskLinesRun = mutableListOf<Long>()
        val result = Sandbox.execute(compiledSource.classLoader, configuredPlugins = plugins) { (loader, _) ->
            val method = loader.loadClass("Main").getMethod("print", String::class.java, Int::class.java)
            method(null, "A", 3)
            subtaskLinesRun.add(LineTrace.getCurrentReport().linesRun)
            LineTrace.resetLineCounts()
            method(null, "B", 4)
            subtaskLinesRun.add(LineTrace.getCurrentReport().linesRun)
            LineTrace.resetLineCounts()
            method(null, "C", 5)
            subtaskLinesRun.add(LineTrace.getCurrentReport().linesRun)
        }
        result should haveCompleted()
        @Suppress("SpellCheckingInspection")
        result should haveOutput("AAA\nBBBB\nCCCCC")
        subtaskLinesRun.size shouldBe 3
        subtaskLinesRun[0] shouldBeGreaterThan 7
        subtaskLinesRun[1] shouldBeGreaterThan 9
        subtaskLinesRun[2] shouldBeGreaterThan 11 // At least 27 lines run in total
    }

    "should allow trusted code to handle the limit exception" {
        val compiledSource = Source.fromJava(
            """
public class Main {
  public static void print(String text, int times) {
    try {
      for (int i = 0; i < times; i++) {
        System.out.print(text);
      }
      System.out.println("");
    } catch (Throwable t) {}
  }
}""".trim()
        ).compile()
        val lineTraceArgs = LineTraceArguments(
            recordedLineLimit = 0,
            runLineLimit = 15,
            runLineLimitExceededAction = LineTraceArguments.RunLineLimitAction.THROW_ERROR
        )
        val plugins = listOf(ConfiguredSandboxPlugin(LineTrace, lineTraceArgs))
        var hitLimit = false
        val result = Sandbox.execute(compiledSource.classLoader, configuredPlugins = plugins) { (loader, _) ->
            val method = loader.loadClass("Main").getMethod("print", String::class.java, Int::class.java)
            try {
                method(null, "A", 15)
            } catch (e: InvocationTargetException) {
                hitLimit = e.cause is LineLimitExceeded
            }
            LineTrace.resetLineCounts()
            method(null, "B", 2)
        }
        result should haveCompleted()
        result.output should endWith("BB")
        hitLimit shouldBe true
    }

    "should be compatible with Jacoco" {
        val result = Source.fromJava(
            """
public class Test {
  private int value;
  public Test() {
    value = 10;
  }
  public Test(int setValue) {
    value = setValue;
  }
}
public class Main {
  public static void main() {
    Test test = new Test(10);
    System.out.println("Done");
  }
}""".trim()
        ).compile().execute(SourceExecutionArguments().addPlugin(Jacoco).addPlugin(LineTrace))
        result.completed shouldBe true
        result.permissionDenied shouldNotBe true
        result should haveOutput("Done")
        val coverage = result.pluginResult(Jacoco)
        val testCoverage = coverage.classes.find { it.name == "Test" }!!
        testCoverage.lineCounter.missedCount shouldBeGreaterThanOrEqual 1
        testCoverage.lineCounter.coveredCount shouldBe 3
        val trace = result.pluginResult(LineTrace)
        trace.steps[0].line shouldBe 12
        trace.steps.map { it.line } shouldContain 7
        trace.steps.map { it.line } shouldNotContain 4
    }

    "should not be installable as a duplicate" {
        val source = Source.fromJava(
            """
public class Main {
  public static void main() {
    System.out.println("Done");
  }
}""".trim()
        )
        assertThrows<IllegalStateException> {
            source.compile().execute(SourceExecutionArguments().addPlugin(LineTrace).addPlugin(LineTrace))
        }.message shouldStartWith "Duplicate plugin: edu.illinois.cs.cs125.jeed.core.LineTrace"
    }
})
