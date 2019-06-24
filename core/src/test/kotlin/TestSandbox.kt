package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.specs.StringSpec
import io.kotlintest.*
import io.kotlintest.matchers.collections.shouldContain
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.matchers.collections.shouldNotContain
import io.kotlintest.matchers.doubles.shouldBeLessThan
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.IllegalArgumentException
import java.util.*
import java.util.stream.Collectors
import kotlin.system.measureTimeMillis

class TestSandbox : StringSpec({
    "should capture stdout" {
        val executionResult = Source.fromSnippet("""
System.out.println("Here");
            """.trim()).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Here")
        executionResult should haveStderr("")
    }
    "should capture stderr" {
        val executionResult = Source.fromSnippet("""
System.err.println("Here");
            """.trim()).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("")
        executionResult should haveStderr("Here")
    }
    "should capture stderr and stdout" {
        val executionResult = Source.fromSnippet("""
System.out.println("Here");
System.err.println("There");
            """.trim()).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Here")
        executionResult should haveStderr("There")
        executionResult should haveOutput("Here\nThere")
    }
    "should capture incomplete stderr and stdout lines" {
        val executionResult = Source.fromSnippet("""
System.out.print("Here");
System.err.print("There");
            """.trim()).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveStdout("Here")
        executionResult should haveStderr("There")
        executionResult should haveOutput("Here\nThere")
    }
    "should not intermingle unrelated thread output" {
        val combinedOutputStream = ByteArrayOutputStream()
        val combinedPrintStream = PrintStream(combinedOutputStream)
        val originalStdout = System.out
        val originalStderr = System.err
        System.setOut(combinedPrintStream)
        System.setErr(combinedPrintStream)

        (0..8).toList().map {
            if (it % 2 == 0) {
                async {
                    Source.fromSnippet("""
for (int i = 0; i < 32; i++) {
    for (long j = 0; j < 1024 * 1024 * 1024; j++);
}
                        """.trim()).compile().execute(SourceExecutionArguments(timeout = 1000L))
                }
            } else {
                async {
                    for (j in 1..512) {
                        println("Bad")
                        System.err.println("Bad")
                        delay(1L)
                    }
                }
            }
        }.map { it.await() }.filterIsInstance<Sandbox.TaskResults<out Any?>>().forEach {executionResult ->
            executionResult should haveTimedOut()
            executionResult.outputLines.map { it.line } shouldNotContain "Bad"
            executionResult.stderrLines.map { it.line } shouldNotContain "Bad"
        }
        System.setOut(originalStdout)
        System.setErr(originalStderr)

        val unrelatedOutput = combinedOutputStream.toString()
        unrelatedOutput.lines().filter { it == "Bad" }.size shouldBe 4 * 2 * 512
    }
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
    "should execute correctly in parallel using streams" {
        (0..8).toList().parallelStream().map { value ->
            val result = runBlocking {
                Source.fromSnippet("""
for (int i = 0; i < 32; i++) {
    for (long j = 0; j < 1024 * 1024; j++);
    System.out.println($value);
}
                    """.trim()).compile().execute(SourceExecutionArguments(timeout = 1000L))
            }
            result should haveCompleted()
            result.stdoutLines shouldHaveSize 32
            result.stdoutLines.all { it.line.trim() == value.toString() } shouldBe true
        }
    }
    "should execute correctly in parallel using coroutines" {
        (0..8).toList().map { value ->
            async {
                Pair(Source.fromSnippet("""
for (int i = 0; i < 32; i++) {
    for (long j = 0; j < 1024 * 1024; j++);
    System.out.println($value);
}
                    """.trim()).compile().execute(SourceExecutionArguments(timeout = 1000L)), value)
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
                Source.fromSnippet("""
for (int i = 0; i < 32; i++) {
    for (long j = 0; j < 1024 * 1024 * 1024; j++);
}
                    """.trim()).compile()
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
                Source.fromSnippet("""
for (int i = 0; i < 32; i++) {
    for (long j = 0; j < 1024 * 1024 * 1024; j++);
}
                    """.trim()).compile()
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
    "should prevent threads from populating a new thread group" {
        val executionResult = Source.fromSnippet("""
public class Example implements Runnable {
    public void run() {
        System.out.println("Here");
        System.exit(1);
    }
}
ThreadGroup threadGroup = new ThreadGroup("test");
Thread thread = new Thread(new ThreadGroup("test"), new Example());
thread.start();
try {
    thread.join();
} catch (Exception e) { }
System.out.println("There");
        """.trim()).compile().execute(SourceExecutionArguments(maxExtraThreads = 7))

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should prevent snippets from exiting" {
        val executionResult = Source.fromSnippet("""
System.exit(2);
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should prevent snippets from reading files" {
        val executionResult = Source.fromSnippet("""
import java.io.*;
System.out.println(new File("/").listFiles().length);
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should prevent snippets from reading system properties" {
        val executionResult = Source.fromSnippet("""
System.out.println(System.getProperty("file.separator"));
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should allow snippets to read system properties if allowed" {
        val executionResult = Source.fromSnippet("""
System.out.println(System.getProperty("file.separator"));
        """.trim()).compile().execute(SourceExecutionArguments(permissions=listOf(PropertyPermission("*", "read"))))

        executionResult should haveCompleted()
        executionResult.permissionDenied shouldBe false
    }
    "should allow permissions to be changed between runs" {
        val compiledSource = Source.fromSnippet("""
System.out.println(System.getProperty("file.separator"));
        """.trim()).compile()

        val failedExecution = compiledSource.execute()
        failedExecution shouldNot haveCompleted()
        failedExecution.permissionDenied shouldBe true

        val successfulExecution = compiledSource.execute(
                SourceExecutionArguments(permissions=listOf(PropertyPermission("*", "read"))
                ))
        successfulExecution should haveCompleted()
        successfulExecution.permissionDenied shouldBe false
    }
    "should prevent snippets from starting threads by default" {
        val executionResult = Source.fromSnippet("""
public class Example implements Runnable {
    public void run() {
    }
}
Thread thread = new Thread(new Example());
thread.start();
System.out.println("Started");
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should allow snippets to start threads when configured" {
        val compiledSource = Source.fromSnippet("""
public class Example implements Runnable {
    public void run() {
        System.out.println("Ended");
    }
}
Thread thread = new Thread(new Example());
System.out.println("Started");
thread.start();
try {
    thread.join();
} catch (Exception e) {
    System.out.println(e);
}
        """.trim()).compile()
        val failedExecutionResult = compiledSource.execute()
        failedExecutionResult shouldNot haveCompleted()
        failedExecutionResult.permissionDenied shouldBe true

        val successfulExecutionResult = compiledSource.execute(SourceExecutionArguments(maxExtraThreads=1))
        successfulExecutionResult.permissionDenied shouldBe false
        successfulExecutionResult should haveCompleted()
        successfulExecutionResult should haveOutput("Started\nEnded")
    }
    "should shut down a runaway thread" {
        val executionResult = Source.fromSnippet("""
public class Example implements Runnable {
    public void run() {
        while (true) {
            try {
                for (long i = 0; i < Long.MAX_VALUE; i++);
            } catch (Exception e) {}
        }
    }
}
Thread thread = new Thread(new Example());
System.out.println("Started");
thread.start();
try {
    thread.join();
} catch (Exception e) { }
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
    } catch (Exception e) { }
}
        """.trim()).compile().execute(SourceExecutionArguments(maxExtraThreads=16, timeout=1000L))

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
        executionResult should haveTimedOut()
        executionResult.stdoutLines shouldHaveSize 16
        executionResult.stdoutLines.map { it.line } shouldContain "15"
    }
    "should not allow unsafe permissions to be provided" {
        shouldThrow<IllegalArgumentException> {
            Source.fromSnippet("""
System.exit(3);
            """.trim()).compile().execute(SourceExecutionArguments(permissions=listOf(RuntimePermission("exitVM"))))
        }
    }
    "should allow Java streams with default permissions" {
        val executionResult = Source.fromSnippet("""
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

List<String> strings = new ArrayList<>(Arrays.asList(new String[] { "test", "me", "another" }));
strings.stream()
    .filter(string -> string.length() <= 4)
    .map(String::toUpperCase)
    .sorted()
    .forEach(System.out::println);
        """.trim()).compile().execute()

        executionResult should haveCompleted()
        executionResult should haveOutput("ME\nTEST")
    }
    "should allow generic methods with the default permissions" {
        val executionResult = Source(mapOf(
                "A" to """
public class A implements Comparable<A> {
    public int compareTo(A other) {
        return 0;
    }
}
                """.trim(),
                "Main" to """
public class Main {
    public static <T extends Comparable<T>> int test(T[] values) {
        return 8;
    }
    public static void main() {
        System.out.println(test(new A[] { }));
    }
}
                """.trim())).compile().execute()

        executionResult should haveCompleted()
        executionResult should haveOutput("8")
    }
    "should shut down nasty thread bombs" {
        val executionResult = Source.fromSnippet("""
public class Example implements Runnable {
    public void run() {
        while (true) {
            try {
                Thread thread = new Thread(new Example());
                thread.start();
            } catch (Exception e) {}
        }
    }
}
while (true) {
    try {
        Thread thread = new Thread(new Example());
        thread.start();
    } catch (Exception e) { }
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
            } catch (Exception e) {}
        }
    }
}
while (true) {
    try {
        Thread thread = new Thread(new Example());
        thread.start();
    } catch (Exception e) { }
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
            } catch (Exception e) {}
        }
    }
}
while (true) {
    try {
        Thread thread = new Thread(new Example());
        thread.start();
    } catch (Exception e) { }
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
            } catch (Exception e) {}
        }
    }
}
while (true) {
    try {
        Thread thread = new Thread(new Example());
        thread.start();
    } catch (Exception e) { }
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
            } catch (Exception e) {}
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
} catch (Exception e) { }
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
            } catch (Exception t) {}
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
            } catch (Exception t) {}
        }
    }
}
Thread thread = new Thread(new Example());
thread.start();
// Give time for things to get NASTY
try {
    Thread.sleep(Long.MAX_VALUE);
} catch (Exception t) { }
        """.trim()).compile().execute(SourceExecutionArguments(maxExtraThreads = 256, timeout = 1000L))

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
        executionResult should haveTimedOut()
    }
    "should not allow ThreadDeath to be caught" {
        shouldThrow<JavaParsingException> {
            Source.fromSnippet("""
try {
    int i = 0;
} catch (Throwable e) { }
        """.trim()).compile()
        }
        shouldThrow<JavaParsingException> {
            Source.fromSnippet("""
try {
    int i = 0;
} catch (java.lang.Throwable e) { }
        """.trim()).compile()
        }
        shouldThrow<JavaParsingException> {
            Source.fromSnippet("""
try {
    int i = 0;
} catch (Error e) { }
        """.trim()).compile()
        }
        shouldThrow<JavaParsingException> {
            Source.fromSnippet("""
try {
    int i = 0;
} catch (java.lang.Error e) { }
        """.trim()).compile()
        }
        shouldThrow<JavaParsingException> {
            Source.fromSnippet("""
try {
    int i = 0;
} catch (ThreadDeath e) { }
        """.trim()).compile()
        }
        shouldThrow<JavaParsingException> {
            Source.fromSnippet("""
try {
    int i = 0;
} catch (java.lang.ThreadDeath e) { }
        """.trim()).compile()
        }
    }

    "it should not allow snippets to read from the internet" {
        val executionResult = Source.fromSnippet("""
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;

BufferedReader br = null;
URL url = new URL("http://cs125.cs.illinois.edu");
br = new BufferedReader(new InputStreamReader(url.openStream()));

String line;
StringBuilder sb = new StringBuilder();
while ((line = br.readLine()) != null) {
    sb.append(line);
    sb.append(System.lineSeparator());
}

System.out.println(sb);
if (br != null) {
    br.close();
}
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "it should not allow snippets to execute commands" {
        val executionResult = Source.fromSnippet("""
import java.io.*;

Process p = Runtime.getRuntime().exec("/bin/sh ls");
BufferedReader in = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
String line = null;

while ((line = in.readLine()) != null) {
    System.out.println(line);
}
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "it should not allow SecurityManager to be set again through reflection" {
        val executionResult = Source.fromSnippet("""
Class<System> c = System.class;
System s = c.newInstance();
            """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
    }
    "it should not allow SecurityManager to be created again through reflection" {
        val executionResult = Source.fromSnippet("""
Class<SecurityManager> c = SecurityManager.class;
SecurityManager s = c.newInstance();
            """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
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
            } catch (Exception e) {}
        }
    }
}
while (true) {
    try {
        Thread thread = new Thread(new Example());
        thread.start();
    } catch (Exception e) { }
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
    } catch (Exception e) {}
}
            """.trim()).compile().execute(SourceExecutionArguments(maxExtraThreads=256, timeout=1000L))
    }

    "should not allow access to the compiler" {
        val executionResult = Source.fromSnippet("""
import java.lang.reflect.*;

Class<?> sourceClass = Class.forName("edu.illinois.cs.cs125.jeed.core.Source");
Field sourceCompanion = sourceClass.getField("Companion");
Class<?> snippetKtClass = Class.forName("edu.illinois.cs.cs125.jeed.core.SnippetKt");
Method fromSnippet = snippetKtClass.getMethod("fromSnippet", sourceCompanion.getType(), String.class, int.class);
Object snippet = fromSnippet.invoke(null, sourceCompanion.get(null), "System.out.println(403);", 4);
Class<?> snippetClass = snippet.getClass();
Class<?> compileArgsClass = Class.forName("edu.illinois.cs.cs125.jeed.core.CompilationArguments");
Method compile = Class.forName("edu.illinois.cs.cs125.jeed.core.CompileKt").getMethod("compile", sourceClass, compileArgsClass);
Object compileArgs = compileArgsClass.newInstance();
Object compiledSource = compile.invoke(null, snippet, compileArgs);
            """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    // TODO: Ben please take another go at this one.
    "!should not allow reflection to disable sandboxing" {
        val executionResult = Source.fromSnippet("""
import java.net.*;
import java.util.Map;

Class<?> sandboxClass = Class.forName("edu.illinois.cs.cs125.jeed.core.Sandbox");
sandboxClass.getDeclaredField("confinedTasks").setAccessible(true);
Map confinedTasks = (Map) sandboxClass.getDeclaredField("confinedTasks").get(null);
confinedTasks.clear();
System.exit(-1);
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should prohibit default blacklisted imports" {
        val executionResult = Source.fromSnippet("""
import java.lang.reflect.*;

Method[] methods = Main.class.getMethods();
System.out.println(methods[0].getName());
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should prohibit configured blacklisted imports" {
        val successfulExecutionResult = Source.fromSnippet("""
import java.util.List;
import java.util.ArrayList;

List list = new ArrayList<String>();
        """.trim()).compile().execute()

        successfulExecutionResult should haveCompleted()

        val failedExecutionResult = Source.fromSnippet("""
import java.util.List;
import java.util.ArrayList;

List list = new ArrayList<String>();
        """.trim()).compile().execute(SourceExecutionArguments(blacklistedClasses = setOf("java.util.")))

        failedExecutionResult shouldNot haveCompleted()
        failedExecutionResult.permissionDenied shouldBe true
    }
    "should allow only whitelisted imports" {
        val successfulExecutionResult = Source.fromSnippet("""
String s = new String("test");
        """.trim()).compile().execute(SourceExecutionArguments(whitelistedClasses = setOf("java.lang.")))

        successfulExecutionResult should haveCompleted()

        val failedExecutionResult = Source.fromSnippet("""
import java.util.List;
import java.util.ArrayList;

List list = new ArrayList<String>();
        """.trim()).compile().execute(SourceExecutionArguments(whitelistedClasses = setOf("java.lang.")))

        failedExecutionResult shouldNot haveCompleted()
        failedExecutionResult.permissionDenied shouldBe true
    }
    "should not allow java.lang.reflect to be whitelisted" {
        shouldThrow<IllegalArgumentException> {
            Source.fromSnippet("""
System.out.println("Here");
            """.trim()).compile().execute(SourceExecutionArguments(whitelistedClasses = setOf("java.lang.reflect.")))
        }
        shouldThrow<IllegalArgumentException> {
            Source.fromSnippet("""
System.out.println("Here");
            """.trim()).compile().execute(SourceExecutionArguments(whitelistedClasses = setOf("java.lang.reflect.Method")))
        }
    }
    "f:should rewrite try-catch block properly" {
        val executionResult = Source.fromSnippet("""
try {
    System.out.println("Try");
    Object o = null;
    o.toString();
} catch (Throwable e) {
    System.out.println("Catch");
} finally {
    System.out.println("Here");
}
            """.trim()).compile().execute()
        executionResult should haveOutput("Try\nFinally")
    }
})
