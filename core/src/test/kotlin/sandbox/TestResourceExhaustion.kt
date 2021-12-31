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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import kotlinx.coroutines.async
import java.lang.IllegalArgumentException

@Suppress("LargeClass")
class TestResourceExhaustion : StringSpec({
    "should timeout correctly on snippet" {
        val executionResult = Source.fromSnippet(
            """
int i = 0;
while (true) {
    i++;
}
        """.trim()
        ).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput()
    }
    "should timeout correctly on sources" {
        val executionResult = Source(
            mapOf(
                "Foo.java" to """
public class Main {
    public static void main() {
        int i = 0;
        while (true) {
            i++;
        }
    }
}
        """.trim()
            )
        ).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput()
    }
    "should return output after timeout" {
        val executionResult = Source.fromSnippet(
            """
System.out.println("Here");
int i = 0;
while (true) {
    i++;
}
            """.trim()
        ).compile().execute()
        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput("Here")
    }
    "should return value after timeout if code is prepared" {
        val executionResult = Source.fromJava(
            """
public class Main {
  public static int main() {
    int i = 0;
    while (i >= 0 && !Thread.currentThread().isInterrupted()) {
      i++;
    }
    return 1;
  }
}
            """.trim()
        ).compile().execute()
        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult.returned shouldBe 1
    }
    "should shut down a runaway thread" {
        val executionResult = Source.fromSnippet(
            """
public class Example implements Runnable {
    public void run() {
        while (true) {
            try {
                for (long i = 0; i < Long.MAX_VALUE; i++);
            } catch (Throwable e) {}
        }
    }
}
Thread thread = new Thread(new Example());
System.out.println("Started");
thread.start();
try {
    thread.join();
} catch (Throwable e) { }
        """.trim()
        ).compile().execute(SourceExecutionArguments(maxExtraThreads = 1))

        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput("Started")
    }
    "should shut down small thread bombs" {
        val executionResult = Source.fromSnippet(
            """
public class Example implements Runnable {
    public void run() {
        for (long j = 0; j < 512 * 1024 * 1024; j++);
        System.out.println("Ended");
    }
}
for (long i = 0;; i++) {
    try {
        Thread thread = new Thread(new Example());
        System.out.println(i);
        thread.start();
    } catch (Throwable e) { }
}
        """.trim()
        ).compile().execute(SourceExecutionArguments(maxExtraThreads = 16, timeout = 1000L))

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
        executionResult should haveTimedOut()
        // FIXME? If some threads complete their 512M loop new ones can start
        // executionResult.stdoutLines shouldHaveSize 16
        executionResult.stdoutLines.map { it.line } shouldContain "15"
    }
    "should shut down nasty thread bombs" {
        val executionResult = Source.fromSnippet(
            """
public class Example implements Runnable {
    public void run() {
        while (true) {
            try {
                Thread thread = new Thread(new Example());
                thread.start();
            } catch (Throwable e) {}
        }
    }
}
while (true) {
    try {
        Thread thread = new Thread(new Example());
        thread.start();
    } catch (Throwable e) { }
}
        """.trim()
        ).compile().execute(SourceExecutionArguments(maxExtraThreads = 256, timeout = 1000L))

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
        executionResult should haveTimedOut()
    }
    "should shut down sleep bombs" {
        val executionResult = Source.fromSnippet(
            """
public class Example implements Runnable {
    public void run() {
        while (true) {
            try {
                Thread.sleep(Long.MAX_VALUE);
            } catch (Throwable e) {}
        }
    }
}
while (true) {
    try {
        Thread thread = new Thread(new Example());
        thread.start();
    } catch (Throwable e) { }
}
        """.trim()
        ).compile().execute(SourceExecutionArguments(maxExtraThreads = 256, timeout = 1000L))

        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
    }
    "should shut down exit bombs" {
        val executionResult = Source.fromSnippet(
            """
public class Example implements Runnable {
    public void run() {
        while (true) {
            try {
                System.exit(4);
            } catch (Throwable e) {}
        }
    }
}
while (true) {
    try {
        Thread thread = new Thread(new Example());
        thread.start();
    } catch (Throwable e) { }
}
        """.trim()
        ).compile().execute(SourceExecutionArguments(maxExtraThreads = 256, timeout = 1000L))

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
        executionResult should haveTimedOut()
    }
    "should shut down spin bombs" {
        val executionResult = Source.fromSnippet(
            """
public class Example implements Runnable {
    public void run() {
        while (true) {
            try {
                for (long i = 0; i < Long.MAX_VALUE; i++);
            } catch (Throwable e) {}
        }
    }
}
while (true) {
    try {
        Thread thread = new Thread(new Example());
        thread.start();
    } catch (Throwable e) { }
}
        """.trim()
        ).compile().execute(SourceExecutionArguments(maxExtraThreads = 256, timeout = 1000L))

        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
    }
    "should shut down reflection-protected thread bombs" {
        val executionResult = Source.fromSnippet(
            """
public class Example implements Runnable {
    public void run() {
        while (true) {
            try {
                java.util.stream.Stream.of(this).forEach(this::createThreadImpl);
            } catch (Throwable e) {}
        }
    }
    public void createThreadImpl(Object unused) {
        Thread thread = new Thread(new Example());
        thread.start();
    }
}
Thread thread = new Thread(new Example());
thread.start();
// Give time for things to get NASTY
try {
    Thread.sleep(Long.MAX_VALUE);
} catch (Throwable e) { }
        """.trim()
        ).compile().execute(SourceExecutionArguments(maxExtraThreads = 256, timeout = 1000L))

        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
    }
    "should shut down recursive thread bombs" {
        val executionResult = Source.fromSnippet(
            """
public class Example implements Runnable {
    public void run() {
        while (true) {
            try {
                recursive(8);
            } catch (Throwable t) {}
        }
    }
    private void recursive(int depthToGo) {
        while (true) {
            try {
                Thread thread = new Thread(new Example());
                thread.start();
                if (depthToGo > 0) recursive(depthToGo - 1);
                thread = new Thread(new Example());
                thread.start();
            } catch (Throwable t) {}
        }
    }
}
Thread thread = new Thread(new Example());
thread.start();
// Give time for things to get NASTY
try {
    Thread.sleep(Long.MAX_VALUE);
} catch (Throwable t) { }
        """.trim()
        ).compile().execute(SourceExecutionArguments(maxExtraThreads = 256, timeout = 1000L))

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
        executionResult should haveTimedOut()
    }
    "should shut down finally-protected thread bombs" {
        val executionResult = Source.fromSnippet(
            """
public class Example implements Runnable {
    public void run() {
        while (true) {
            try {
                recursive(1000);
            } catch (Throwable e) {}
        }
    }
    private void recursive(int depthToGo) {
        while (true) {
            try {
                Thread thread = new Thread(new Example());
                thread.start();
                if (depthToGo > 0) recursive(depthToGo - 1);
                thread = new Thread(new Example());
                thread.start();
            } finally {
                recursive(depthToGo - 1);
            }
        }
    }
}
Thread thread = new Thread(new Example());
thread.start();
try {
    Thread.sleep(Long.MAX_VALUE);
} catch (Throwable e) { }
        """.trim()
        ).compile().execute(SourceExecutionArguments(maxExtraThreads = 256, timeout = 1000L))

        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
    }
    "should shut down parallel recursive thread bombs" {
        @Suppress("MagicNumber")
        (0..16).toList().map {
            async {
                Source.fromSnippet(
                    """
public class Example implements Runnable {
    public void run() {
        while (true) {
            try {
                recursive(1000);
            } catch (Throwable t) {}
        }
    }
    private void recursive(int depthToGo) {
        while (true) {
            try {
                Thread thread = new Thread(new Example());
                thread.start();
                if (depthToGo > 0) recursive(depthToGo - 1);
                thread = new Thread(new Example());
                thread.start();
            } catch (Throwable t) {}
        }
    }
}
Thread thread = new Thread(new Example());
thread.start();
// Give time for things to get NASTY
try {
    Thread.sleep(Long.MAX_VALUE);
} catch (Throwable t) { }
        """.trim()
                ).compile().execute(SourceExecutionArguments(maxExtraThreads = 256, timeout = 2000L))
            }
        }.map {
            val executionResult = it.await()

            executionResult shouldNot haveCompleted()
            executionResult.permissionDenied shouldBe true
            executionResult should haveTimedOut()
        }
    }
    "should shut down memory exhaustion bombs" {
        Source.fromSnippet(
            """
import java.util.List;
import java.util.ArrayList;

public class Example implements Runnable {
    public void run() {
        while (true) {
            try {
                List<Object> list = new ArrayList<Object>();
                for (int i = 0; i < Integer.MAX_VALUE; i++) {
                    list.add(new ArrayList<Object>(10000000));
                }
            } catch (Throwable e) {}
        }
    }
}
while (true) {
    try {
        Thread thread = new Thread(new Example());
        thread.start();
    } catch (Throwable e) { }
}
        """.trim()
        ).compile().execute(SourceExecutionArguments(maxExtraThreads = 16, timeout = 1000L))
    }
    "should recover from excessive memory usage" {
        Source.fromSnippet(
            """
import java.util.List;
import java.util.ArrayList;

List<Object> list = new ArrayList<Object>();
while (true) {
    try {
        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            list.add(new ArrayList<Object>(10000000));
        }
    } catch (Throwable e) {}
}
            """.trim()
        ).compile().execute(SourceExecutionArguments(maxExtraThreads = 256, timeout = 1000L))
    }
    "should recover from excessive console printing" {
        @Suppress("MagicNumber")
        for (i in 0..32) {
            val result = Source.fromSnippet(
                """
for (long i = 0; i < 10000000L; i++) {
    System.out.println(i);
}
    """.trim()
            ).compile().execute(SourceExecutionArguments(timeout = 100L))

            result.outputLines[0].line shouldBe "0"
            result.outputLines[Sandbox.ExecutionArguments.DEFAULT_MAX_OUTPUT_LINES - 1]
                .line shouldBe (Sandbox.ExecutionArguments.DEFAULT_MAX_OUTPUT_LINES - 1).toString()
            result.outputLines shouldHaveSize Sandbox.ExecutionArguments.DEFAULT_MAX_OUTPUT_LINES
            result.truncatedLines shouldBeGreaterThan 0
        }
    }
    "should print correctly in parallel using coroutines" {
        @Suppress("MagicNumber")
        (0..8).toList().map { value ->
            async {
                Pair(
                    Source.fromSnippet(
                        """
for (int i = 0; i < (($value + 10) * 10000); i++) {
    System.out.println($value);
}
                    """.trim()
                    ).compile().execute(SourceExecutionArguments(timeout = 100L)),
                    value
                )
            }
        }.map { it ->
            val (result, value) = it.await()
            result.stdoutLines shouldHaveSize Sandbox.ExecutionArguments.DEFAULT_MAX_OUTPUT_LINES
            result.stdoutLines.all { it.line.trim() == value.toString() } shouldBe true
        }
    }
    "should survive a very large class file" {
        // What's the right behavior here?
        // Throwing an exception during compilation/rewriting is probably OK
        // It's only a problem if it dies at a time that causes a ConfinedTask to be leaked

        // From https://stackoverflow.com/a/42301131/
        val compileResult = Source.fromSnippet(
            """
class A {
    {
        int a;
        try {a=0;} finally {
        try {a=0;} finally {
        try {a=0;} finally {
        try {a=0;} finally {
        try {a=0;} finally {
        try {a=0;} finally {
        try {a=0;} finally {
        try {a=0;} finally {
        try {a=0;} finally {
        try {a=0;} finally {
        try {a=0;} finally {
        a=0;
        }}}}}}}}}}}
        try {a=0;} finally {
        try {a=0;} finally {
        try {a=0;} finally {
        try {a=0;} finally {
        try {a=0;} finally {
        try {a=0;} finally {
        try {a=0;} finally {
        try {a=0;} finally {
        try {a=0;} finally {
        a=0;
        }}}}}}}}}
    }
    A() { }
    A(int a) { }
    A(int a, int b) { }
    A(int a, int b, int c) { }
    A(char a) { }
    A(char a, char b) { }
    A(char a, char b, char c) { }
    A(double a) { }
    A(double a, double b) { }
    A(double a, double b, double c) { }
    A(float a) { }
    A(float a, float b) { }
    A(float a, float b, float c) { }
    A(long a) { }
    A(long a, long b) { }
    A(long a, long b, long c) { }
    A(short a) { }
    A(short a, short b) { }
    A(short a, short b, short c) { }
    A(boolean a) { }
    A(boolean a, boolean b) { }
    A(boolean a, boolean b, boolean c) { }
    A(String a) { }
    A(String a, String b) { }
    A(Integer a) { }
    A(Integer a, Integer b) { }
    A(Float a) { }
    A(Float a, Float b) { }
    A(Short a) { }
    A(Short a, Short b) { }
    A(Long a) { }
    A(Long a, Long b) { }
    A(Double a) { }
    A(Double a, Double b) { }
    A(Boolean a) { }
    A(Boolean a, Boolean b) { }
    A(Character a) { }
    A(Character a, Character b) { }
}
new A();
            """.trimIndent()
        ).compile()
        try {
            compileResult.execute()
        } catch (e: IllegalArgumentException) {
            e.message shouldBe "bytecode is over 1 MB"
        }
    }
    "should terminate a parked thread" {
        val executionResult = Source.fromSnippet(
            """
import java.util.concurrent.locks.LockSupport;
public class Example implements Runnable {
    public void run() {
        while (true) {
            Thread.interrupted();
            LockSupport.park(Thread.currentThread());
        }
    }
}
Thread thread = new Thread(new Example());
System.out.println("Started");
thread.start();
try {
    thread.join();
} catch (Throwable e) { }
        """.trim()
        ).compile().execute(SourceExecutionArguments(maxExtraThreads = 1))

        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput("Started")
    }
    "should terminate a waiting thread" {
        val compileResult = Source(
            mapOf(
                "Main.java" to """
public class Main {
    public static void main() {
        Object monitor = new Object();
        synchronized (monitor) {
            try {
                monitor.wait();
            } catch (Exception e) {
                System.out.println("Failed to wait");
            }
        }
    }
}""".trim()
            )
        ).compile()

        (1..8).forEach { _ -> // Flaky
            val executionResult = compileResult.execute()
            executionResult shouldNot haveCompleted()
            executionResult should haveTimedOut()
        }
    }
    "should terminate an infinite synchronization wait" {
        val compileResult = Source(
            mapOf(
                "Main.java" to """
public class Sync {
    public static synchronized void deadlock() {
        while (true);
    }
}
public class Other implements Runnable {
    public void run() {
        System.out.println("Other");
        Sync.deadlock();
    }
}
public class Main {
    public static void main() throws InterruptedException {
        new Thread(new Other()).start();
        Sync.deadlock();
    }
}""".trim()
            )
        ).compile()
        val executionResult = compileResult.execute(SourceExecutionArguments(maxExtraThreads = 1, timeout = 200L))
        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput("Other")
    }
    "should terminate a synchronization deadlock" {
        val compileResult = Source(
            mapOf(
                "Main.java" to """
public class Other implements Runnable {
    public void run() {
        System.out.println("Other");
        synchronized (Main.root2) {
            synchronized (Main.root1) {
                while (true) {}
            }
        }
    }
}
public class Main {
    public static Object root1 = new Object();
    public static Object root2 = new Object();
    public static void main() throws InterruptedException {
        new Thread(new Other()).start();
        synchronized (root1) {
            synchronized (root2) {
                while (true) {}
            }
        }
    }
}""".trim()
            )
        ).compile()
        val executionResult = compileResult.execute(SourceExecutionArguments(maxExtraThreads = 1, timeout = 200L))
        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput("Other")
    }
    "should stop long counted loops" {
        val executionResult = Source.fromSnippet(
            """
void countedLoop(int times) {
    int counter = 0;
    for (int i = 0; i < times; i++) {
        for (int j = 0; j < times; j++) {
            for (int k = 0; k < times; k++) {
                counter++;
            }
        }
    }
}
for (int n = 0; n < 10000; n++) {
    countedLoop(1); // trigger JIT
}
System.out.println("Warmed up");
countedLoop(1000000);
""".trim()
        ).compile().execute()
        executionResult should haveTimedOut()
        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Warmed up")
    }
    "should terminate a console-scanning thread" {
        val compileResult = Source(
            mapOf(
                "Main.java" to """
import java.util.Scanner;
public class Main {
    public static void main() {
        var scanner = new Scanner(System.in);
        scanner.nextLine();
    }
}""".trim()
            )
        ).compile()

        (1..8).forEach { _ ->
            val executionResult = compileResult.execute()
            executionResult shouldNot haveCompleted()
        }
    }
    "should terminate a console-reading thread" {
        val compileResult = Source(
            mapOf(
                "Main.java" to """
public class Main {
    public static void main() throws Exception {
        while (true) {
            int b = -1;
            while ((b = System.in.read()) < 0) {}
            System.out.println(b);
        }
    }
}""".trim()
            )
        ).compile()

        (1..8).forEach { _ ->
            val executionResult = compileResult.execute()
            executionResult shouldNot haveCompleted()
        }
    }
})
