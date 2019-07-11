package edu.illinois.cs.cs125.jeed.core.sandbox

import edu.illinois.cs.cs125.jeed.core.*
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNot
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.lang.IllegalArgumentException
import java.util.*

class TestPermissions : StringSpec({
    "should prevent threads from populating a new thread group" {
        val executionResult = Source.transformSnippet("""
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
        val executionResult = Source.transformSnippet("""
System.exit(2);
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should prevent snippets from redirecting System.out" {
        val executionResult = Source.transformSnippet("""
import java.io.*;

ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
PrintStream printStream = new PrintStream(byteArrayOutputStream);
System.setOut(printStream);
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should prevent trusted task code from redirecting System.out" {
        val executionResult = Sandbox.execute<Any> {
            val byteArrayOutputStream = ByteArrayOutputStream()
            val printStream = PrintStream(byteArrayOutputStream)
            System.setOut(printStream)
        }

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should prevent snippets from reading files" {
        val executionResult = Source.transformSnippet("""
import java.io.*;
System.out.println(new File("/").listFiles().length);
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should prevent snippets from reading system properties" {
        val executionResult = Source.transformSnippet("""
System.out.println(System.getProperty("file.separator"));
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should allow snippets to read system properties if allowed" {
        val executionResult = Source.transformSnippet("""
System.out.println(System.getProperty("file.separator"));
        """.trim()).compile().execute(SourceExecutionArguments(permissions=setOf(PropertyPermission("*", "read"))))

        executionResult should haveCompleted()
        executionResult.permissionDenied shouldBe false
    }
    "should allow permissions to be changed between runs" {
        val compiledSource = Source.transformSnippet("""
System.out.println(System.getProperty("file.separator"));
        """.trim()).compile()

        val failedExecution = compiledSource.execute()
        failedExecution shouldNot haveCompleted()
        failedExecution.permissionDenied shouldBe true

        val successfulExecution = compiledSource.execute(
                SourceExecutionArguments(permissions=setOf(PropertyPermission("*", "read"))
                ))
        successfulExecution should haveCompleted()
        successfulExecution.permissionDenied shouldBe false
    }
    "should prevent snippets from starting threads by default" {
        val executionResult = Source.transformSnippet("""
public class Example implements Runnable {
    public void run() { }
}
Thread thread = new Thread(new Example());
thread.start();
System.out.println("Started");
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should allow snippets to start threads when configured" {
        val compiledSource = Source.transformSnippet("""
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
    "should not allow unsafe permissions to be provided" {
        shouldThrow<IllegalArgumentException> {
            Source.transformSnippet("""
System.exit(3);
            """.trim()).compile().execute(
                    SourceExecutionArguments(permissions=setOf(RuntimePermission("exitVM")))
            )
        }
    }
    "should allow Java streams with default permissions" {
        val executionResult = Source.transformSnippet("""
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
                "A.java" to """
public class A implements Comparable<A> {
    public int compareTo(A other) {
        return 0;
    }
}
                """.trim(),
                "Main.java" to """
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
    "it should not allow snippets to read from the internet" {
        val executionResult = Source.transformSnippet("""
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
        val executionResult = Source.transformSnippet("""
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
    "should not allow SecurityManager to be set again through reflection" {
        val executionResult = Source.transformSnippet("""
Class<System> c = System.class;
System s = c.newInstance();
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
    }
    "should not allow SecurityManager to be created again through reflection" {
        val executionResult = Source.transformSnippet("""
Class<SecurityManager> c = SecurityManager.class;
SecurityManager s = c.newInstance();
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should not allow access to the compiler" {
        val executionResult = Source.transformSnippet("""
import java.lang.reflect.*;

Class<?> sourceClass = Class.forName("edu.illinois.cs.cs125.jeed.core.Source");
Field sourceCompanion = sourceClass.getField("Companion");
Class<?> snippetKtClass = Class.forName("edu.illinois.cs.cs125.jeed.core.SnippetKt");
Method transformSnippet = snippetKtClass.getMethod("transformSnippet", sourceCompanion.getType(), String.class, int.class);
Object snippet = transformSnippet.invoke(null, sourceCompanion.get(null), "System.out.println(403);", 4);
Class<?> snippetClass = snippet.getClass();
Class<?> compileArgsClass = Class.forName("edu.illinois.cs.cs125.jeed.core.CompilationArguments");
Method compile = Class.forName("edu.illinois.cs.cs125.jeed.core.CompileKt").getMethod("compile", sourceClass, compileArgsClass);
Object compileArgs = compileArgsClass.newInstance();
Object compiledSource = compile.invoke(null, snippet, compileArgs);
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should not allow reflection to disable sandboxing" {
        val executionResult = Source.transformSnippet("""
import java.lang.reflect.*;
import java.util.Map;

Class<?> sandboxClass = Class.forName("edu.illinois.cs.cs125.jeed.core.Sandbox");
Field field = sandboxClass.getDeclaredField("confinedTasks");
field.setAccessible(true);
Map confinedTasks = (Map) field.get(null);
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should not prevent trusted code from using reflection" {
        val executionResult = Sandbox.execute {
            val sandboxClass = Class.forName("edu.illinois.cs.cs125.jeed.core.Sandbox")
            val field = sandboxClass.getDeclaredField("confinedTasks")
            field.isAccessible = true
        }

        executionResult should haveCompleted()
    }
    "should not allow static{} to escape the sandbox" {
        val executionResult = Source(mapOf(
                "Example.java" to """
public class Example {
    static {
        System.out.println("Static initializer");
        System.exit(-1);
    }
    public static void main() {
        System.out.println("Main");
    }
}
        """)).compile().execute(SourceExecutionArguments("Example"))

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should not allow finalizers to escape the sandbox" {
        val executionResult = Source(mapOf(
                "Example.java" to """
public class Example {
    public static void main() {
        Example ex = null;
        for (int i = 0; i < 10000; i++) {
            ex = new Example();
        }
    }
    public Example() {
        System.out.println("Example");
    }
    protected void finalize() {
        System.out.println("Finalizer");
        System.exit(-1);
    }
}
""".trim()
        )).compile().execute(SourceExecutionArguments("Example"))
        System.gc()
        executionResult.outputLines shouldHaveSize 10000
        executionResult.outputLines.all { (_, line) -> line.trim().equals("Example") } shouldBe true
    }
    "should not allow LambdaMetafactory to escape the sandbox" {
        val executionResult = Source.transformSnippet("""
import java.lang.invoke.*;
            
try {
    MethodHandle handle = MethodHandles.lookup().findStatic(System.class, "exit", MethodType.methodType(void.class, int.class));
    CallSite site = LambdaMetafactory.metafactory(MethodHandles.lookup(),
            "run",
            MethodType.methodType(Runnable.class, int.class),
            MethodType.methodType(void.class),
            handle,
            MethodType.methodType(void.class));
    Runnable runnable = (Runnable) site.dynamicInvoker().invoke(125);
    Thread thread = new Thread(runnable);
    thread.start();
    Thread.sleep(50);
} catch (Throwable t) {
    throw new RuntimeException(t);
}
        """.trim()).compile().execute(SourceExecutionArguments(maxExtraThreads = 1))
        executionResult.permissionDenied shouldBe true
    }
    "should not allow MethodHandle-based reflection to dodge the sandbox" {
        val executionResult = Source.transformSnippet("""
import java.lang.invoke.*;
import com.puppycrawl.tools.checkstyle.api.CheckstyleException;
            
try {
    MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(CheckstyleException.class, MethodHandles.lookup());
    Class<?> shutdownClass = Class.forName("java.lang.Shutdown");
    Class<?> methodClass = lookup.findClass("java.lang.reflect.Method");
    MethodHandle gdmHandle = lookup.findVirtual(Class.class, "getDeclaredMethod", MethodType.methodType(methodClass, String.class, Class[].class));
    Object exitMethod = gdmHandle.invokeWithArguments(shutdownClass, "exit", int.class);
    MethodHandle saHandle = lookup.findVirtual(methodClass, "trySetAccessible", MethodType.methodType(boolean.class));
    saHandle.invokeWithArguments(exitMethod);
    MethodHandle invokeHandle = lookup.findVirtual(methodClass, "invoke", MethodType.methodType(Object.class, Object.class, Object[].class));
    invokeHandle.invokeWithArguments(exitMethod, null, 125);
} catch (Throwable t) {
    throw new RuntimeException(t);
}
        """.trim()).compile().execute(SourceExecutionArguments(timeout = 10000))
        executionResult.permissionDenied shouldBe true
    }
    "should not allow MethodHandles to alter the security manager" {
        val executionResult = Source.transformSnippet("""
import java.lang.invoke.*;
import java.util.Map;
import com.puppycrawl.tools.checkstyle.api.CheckstyleException;
            
try {
    MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(CheckstyleException.class, MethodHandles.lookup());
    Class<?> sandboxClass = lookup.findClass("edu.illinois.cs.cs125.jeed.core.Sandbox");
    Class<?> fieldClass = lookup.findClass("java.lang.reflect.Field");
    MethodHandle gdfHandle = lookup.findVirtual(Class.class, "getDeclaredField", MethodType.methodType(fieldClass, String.class));
    Object confinedField = gdfHandle.invokeWithArguments(sandboxClass, "confinedTasks");
    MethodHandle saHandle = lookup.findVirtual(fieldClass, "setAccessible", MethodType.methodType(void.class, boolean.class));
    saHandle.invokeWithArguments(confinedField, true);
    MethodHandle getHandle = lookup.findVirtual(fieldClass, "get", MethodType.methodType(Object.class, Object.class));
    Map confined = (Map) getHandle.invokeWithArguments(confinedField, null);
    confined.clear();
} catch (Throwable t) {
    throw new RuntimeException(t);
}
        """.trim()).compile().execute(SourceExecutionArguments(timeout = 10000))
        executionResult.permissionDenied shouldBe true
    }
})
