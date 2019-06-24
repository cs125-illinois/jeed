package edu.illinois.cs.cs125.jeed.core.sandbox

import edu.illinois.cs.cs125.jeed.core.*
import io.kotlintest.matchers.collections.shouldContain
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNot
import io.kotlintest.specs.StringSpec

class TestResourceExhaustion : StringSpec({
    "should timeout correctly on snippet" {
        val executionResult = Source.fromSnippet("""
int i = 0;
while (true) {
    i++;
}
            """.trim()).compile().execute()
        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput()
    }
    "should timeout correctly on sources" {
        val executionResult = Source(mapOf(
                "Foo" to """
public class Main {
    public static void main() {
        int i = 0;
        while (true) {
            i++;
        }
    }
}
                """.trim())).compile().execute()
        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput()
    }
    "should return output after timeout" {
        val executionResult = Source.fromSnippet("""
System.out.println("Here");
int i = 0;
while (true) {
    i++;
}
            """.trim()).compile().execute()
        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput("Here")
    }
    "should shut down a runaway thread" {
        val executionResult = Source.fromSnippet("""
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
        """.trim()).compile().execute(SourceExecutionArguments(maxExtraThreads=1))

        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
        executionResult should haveOutput("Started")
    }
    "should shut down thread bombs" {
        val executionResult = Source.fromSnippet("""
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
        """.trim()).compile().execute(SourceExecutionArguments(maxExtraThreads=16, timeout=1000L))

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
        executionResult should haveTimedOut()
        executionResult.stdoutLines shouldHaveSize 16
        executionResult.stdoutLines.map { it.line } shouldContain "15"
    }
    "should shut down nasty thread bombs" {
        val executionResult = Source.fromSnippet("""
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
        """.trim()).compile().execute(SourceExecutionArguments(maxExtraThreads=256, timeout=1000L))

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
        executionResult should haveTimedOut()
    }
    "should shut down sleep bombs" {
        val executionResult = Source.fromSnippet("""
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
        """.trim()).compile().execute(SourceExecutionArguments(maxExtraThreads=256, timeout=1000L))

        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
    }
    "should shut down exit bombs" {
        val executionResult = Source.fromSnippet("""
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
        """.trim()).compile().execute(SourceExecutionArguments(maxExtraThreads=256, timeout=1000L))

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
        executionResult should haveTimedOut()
    }
    "should shut down spin bombs" {
        val executionResult = Source.fromSnippet("""
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
        """.trim()).compile().execute(SourceExecutionArguments(maxExtraThreads=256, timeout=1000L))

        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
    }
    "should shut down reflection-protected thread bombs" {
        val executionResult = Source.fromSnippet("""
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
        """.trim()).compile().execute(SourceExecutionArguments(maxExtraThreads=256, timeout=1000L))

        executionResult shouldNot haveCompleted()
        executionResult should haveTimedOut()
    }
    "should shut down recursive thread bombs" {
        val executionResult = Source.fromSnippet("""
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
        """.trim()).compile().execute(SourceExecutionArguments(maxExtraThreads = 256, timeout = 1000L))

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
        executionResult should haveTimedOut()
    }
    "should shut down memory exhaustion bombs" {
        Source.fromSnippet("""
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
        """.trim()).compile().execute(SourceExecutionArguments(maxExtraThreads=16, timeout=1000L))
    }
    "should recover from excessive memory usage" {
        Source.fromSnippet("""
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
            """.trim()).compile().execute(SourceExecutionArguments(maxExtraThreads=256, timeout=1000L))
    }

})
