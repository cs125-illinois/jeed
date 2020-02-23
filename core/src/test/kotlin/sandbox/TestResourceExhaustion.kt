package edu.illinois.cs.cs125.jeed.core.sandbox

import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments
import edu.illinois.cs.cs125.jeed.core.compile
import edu.illinois.cs.cs125.jeed.core.execute
import edu.illinois.cs.cs125.jeed.core.haveCompleted
import edu.illinois.cs.cs125.jeed.core.haveOutput
import edu.illinois.cs.cs125.jeed.core.haveTimedOut
import edu.illinois.cs.cs125.jeed.core.transformSnippet
import io.kotlintest.matchers.collections.shouldContain
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.matchers.numerics.shouldBeGreaterThan
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNot
import io.kotlintest.specs.StringSpec
import kotlinx.coroutines.async

class TestResourceExhaustion : StringSpec({
    "should timeout correctly on snippet" {
        val executionResult = Source.transformSnippet(
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
        val executionResult = Source.transformSnippet(
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
    "should shut down a runaway thread" {
        val executionResult = Source.transformSnippet(
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
        val executionResult = Source.transformSnippet(
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
        val executionResult = Source.transformSnippet(
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
        val executionResult = Source.transformSnippet(
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
        val executionResult = Source.transformSnippet(
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
        val executionResult = Source.transformSnippet(
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
        val executionResult = Source.transformSnippet(
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
        val executionResult = Source.transformSnippet(
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
        val executionResult = Source.transformSnippet(
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
            } catch (Throwable e) {} finally {
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
        (0..16).toList().map {
            async {
                Source.transformSnippet(
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
                ).compile().execute(SourceExecutionArguments(maxExtraThreads = 256, timeout = 1000L))
            }
        }.map {
            val executionResult = it.await()

            executionResult shouldNot haveCompleted()
            executionResult.permissionDenied shouldBe true
            executionResult should haveTimedOut()
        }
    }
    "should shut down memory exhaustion bombs" {
        Source.transformSnippet(
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
        Source.transformSnippet(
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
        for (i in 0..32) {
            val result = Source.transformSnippet(
                """
    for (long i = 0; i < 10000000L; i++) {
        System.out.println(i);
    }
    """.trim()
            ).compile().execute(SourceExecutionArguments(timeout = 100L))

            result.outputLines[0].line shouldBe "0"
            result.outputLines[Sandbox.ExecutionArguments.DEFAULT_MAX_OUTPUT_LINES - 1].line shouldBe (Sandbox.ExecutionArguments.DEFAULT_MAX_OUTPUT_LINES - 1).toString()
            result.outputLines shouldHaveSize Sandbox.ExecutionArguments.DEFAULT_MAX_OUTPUT_LINES
            result.truncatedLines shouldBeGreaterThan 0
        }
    }
    "should print correctly in parallel using coroutines" {
        (0..8).toList().map { value ->
            async {
                Pair(
                    Source.transformSnippet(
                        """
for (int i = 0; i < (($value + 10) * 10000); i++) {
    System.out.println($value);
}
                    """.trim()
                    ).compile().execute(SourceExecutionArguments(timeout = 100L)), value
                )
            }
        }.map { it ->
            val (result, value) = it.await()
            result.stdoutLines shouldHaveSize Sandbox.ExecutionArguments.DEFAULT_MAX_OUTPUT_LINES
            result.stdoutLines.all { it.line.trim() == value.toString() } shouldBe true
        }
    }
    "should survive a very large class file" {
        // TODO: What's the right behavior here?
        // Throwing an exception during compilation/rewriting is probably OK
        // It's only a problem if it dies at a time that causes a ConfinedTask to be leaked

        // From https://stackoverflow.com/a/42301131/
        Source.transformSnippet(
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
        ).compile().execute()
    }
    "should terminate a parked thread" {
        val executionResult = Source.transformSnippet(
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
})
