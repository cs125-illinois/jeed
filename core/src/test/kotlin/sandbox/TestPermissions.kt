@file:Suppress("MagicNumber")

package edu.illinois.cs.cs125.jeed.core.sandbox

import edu.illinois.cs.cs125.jeed.core.Sandbox
import edu.illinois.cs.cs125.jeed.core.SnippetArguments
import edu.illinois.cs.cs125.jeed.core.Source
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments
import edu.illinois.cs.cs125.jeed.core.compile
import edu.illinois.cs.cs125.jeed.core.execute
import edu.illinois.cs.cs125.jeed.core.fromSnippet
import edu.illinois.cs.cs125.jeed.core.haveCompleted
import edu.illinois.cs.cs125.jeed.core.haveOutput
import edu.illinois.cs.cs125.jeed.core.kompile
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.types.instanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.lang.IllegalArgumentException
import java.util.PropertyPermission

class TestPermissions : StringSpec({
    "should prevent threads from populating a new thread group" {
        val executionResult = Source.fromSnippet(
            """
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
        """.trim()
        ).compile().execute(SourceExecutionArguments(maxExtraThreads = 7))

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should prevent snippets from exiting" {
        val executionResult = Source.fromSnippet(
            """
System.exit(2);
        """.trim()
        ).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should prevent snippets from redirecting System.out" {
        val executionResult = Source.fromSnippet(
            """
import java.io.*;

ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
PrintStream printStream = new PrintStream(byteArrayOutputStream);
System.setOut(printStream);
        """.trim()
        ).compile().execute()

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
        val executionResult = Source.fromSnippet(
            """
import java.io.*;
System.out.println(new File("/").listFiles().length);
        """.trim()
        ).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should prevent snippets from writing files" {
        val executionResult = Source.fromSnippet(
            """
import java.io.*;
var writer = new PrintWriter("test.txt", "UTF-8");
writer.println("Uh oh");
writer.close();
        """.trim()
        ).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should prevent snippets from writing files through the Files API" {
        val executionResult = Source.fromSnippet(
            """
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
Path file = Paths.get("test.txt");
Files.write(file, Arrays.asList("oh", "no"), StandardCharsets.UTF_8);
        """.trim()
        ).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should prevent writing files through the Kotlin stdlib" {
        val executionResult = Source.fromSnippet(
            """
import java.io.File
File("test.txt").writeText("uh oh")
                """.trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).kompile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should allow snippets to read system properties if allowed" {
        val executionResult = Source.fromSnippet(
            """
System.out.println(System.getProperty("file.separator"));
        """.trim()
        ).compile().execute(
            SourceExecutionArguments(permissions = setOf(PropertyPermission("*", "read")))
        )

        executionResult should haveCompleted()
        executionResult.permissionDenied shouldBe false
    }
    "should prevent snippets from reading system properties" {
        val executionResult = Source.fromSnippet(
            """
System.out.println(System.getProperty("file.separator"));
        """.trim()
        ).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should allow permissions to be changed between runs" {
        val compiledSource = Source.fromSnippet(
            """
System.out.println(System.getProperty("file.separator"));
        """.trim()
        ).compile()

        val failedExecution = compiledSource.execute()
        failedExecution shouldNot haveCompleted()
        failedExecution.permissionDenied shouldBe true

        val successfulExecution = compiledSource.execute(
            SourceExecutionArguments(
                permissions = setOf(PropertyPermission("*", "read"))
            )
        )
        successfulExecution should haveCompleted()
        successfulExecution.permissionDenied shouldBe false
    }
    "should prevent snippets from starting threads by default" {
        val executionResult = Source.fromSnippet(
            """
public class Example implements Runnable {
    public void run() { }
}
Thread thread = new Thread(new Example());
thread.start();
System.out.println("Started");
        """.trim()
        ).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should allow snippets to start threads when configured" {
        val compiledSource = Source.fromSnippet(
            """
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
        """.trim()
        ).compile()

        val failedExecutionResult = compiledSource.execute()
        failedExecutionResult shouldNot haveCompleted()
        failedExecutionResult.permissionDenied shouldBe true

        val successfulExecutionResult = compiledSource.execute(SourceExecutionArguments(maxExtraThreads = 1))
        successfulExecutionResult.permissionDenied shouldBe false
        successfulExecutionResult should haveCompleted()
        successfulExecutionResult should haveOutput("Started\nEnded")
    }
    "should not allow unsafe permissions to be provided" {
        shouldThrow<IllegalArgumentException> {
            Source.fromSnippet(
                """
System.exit(3);
            """.trim()
            ).compile().execute(
                SourceExecutionArguments(permissions = setOf(RuntimePermission("exitVM")))
            )
        }
    }
    "should allow Java streams with default permissions" {
        val executionResult = Source.fromSnippet(
            """
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

List<String> strings = new ArrayList<>(Arrays.asList(new String[] { "test", "me", "another" }));
strings.stream()
    .filter(string -> string.length() <= 4)
    .map(String::toUpperCase)
    .sorted()
    .forEach(System.out::println);
        """.trim()
        ).compile().execute()

        executionResult should haveCompleted()
        executionResult should haveOutput("ME\nTEST")
    }
    "should allow generic methods with the default permissions" {
        val executionResult = Source(
            mapOf(
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
        """.trim()
            )
        ).compile().execute()

        executionResult should haveCompleted()
        executionResult should haveOutput("8")
    }
    "it should not allow snippets to read from the internet" {
        val executionResult = Source.fromSnippet(
            """
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
        """.trim()
        ).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "it should not allow snippets to execute commands" {
        val executionResult = Source.fromSnippet(
            """
import java.io.*;

Process p = Runtime.getRuntime().exec("/bin/sh ls");
BufferedReader in = new BufferedReader(
                    new InputStreamReader(p.getInputStream()));
String line = null;

while ((line = in.readLine()) != null) {
    System.out.println(line);
}
        """.trim()
        ).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "it should not allow snippets to examine other processes" {
        val executionResult = Source.fromSnippet(
            """
ProcessHandle.allProcesses().forEach(p -> System.out.println(p.info()));
        """.trim()
        ).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
        executionResult should haveOutput("")
    }
    "should not allow SecurityManager to be created again through reflection" {
        val executionResult = Source.fromSnippet(
            """
Class<SecurityManager> c = SecurityManager.class;
SecurityManager s = c.newInstance();
        """.trim()
        ).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should not allow access to the compiler" {
        val executionResult = Source.fromSnippet(
            """
import java.lang.reflect.*;

Class<?> sourceClass = Class.forName("edu.illinois.cs.cs125.jeed.core.Source");
Field sourceCompanion = sourceClass.getField("Companion");
Class<?> snippetKtClass = Class.forName("edu.illinois.cs.cs125.jeed.core.SnippetKt");
Method transformSnippet = snippetKtClass.getMethod(
    "transformSnippet", sourceCompanion.getType(), String.class, int.class
);
Object snippet = transformSnippet.invoke(null, sourceCompanion.get(null), "System.out.println(403);", 4);
Class<?> snippetClass = snippet.getClass();
Class<?> compileArgsClass = Class.forName("edu.illinois.cs.cs125.jeed.core.CompilationArguments");
Method compile = Class.forName(
    "edu.illinois.cs.cs125.jeed.core.CompileKt").getMethod("compile", sourceClass, compileArgsClass
);
Object compileArgs = compileArgsClass.newInstance();
Object compiledSource = compile.invoke(null, snippet, compileArgs);
        """.trim()
        ).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should not allow reflection to disable the sandbox" {
        val executionResult = Source.fromSnippet(
            """
import java.lang.reflect.*;
import java.util.Map;

Class<?> sandboxClass = Class.forName("edu.illinois.cs.cs125.jeed.core.Sandbox");
Field field = sandboxClass.getDeclaredField("confinedTasks");
field.setAccessible(true);
Map confinedTasks = (Map) field.get(null);
        """.trim()
        ).compile().execute()

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
    "should not prevent trusted code from accessing files" {
        val executionResult = Sandbox.execute {
            File("test.txt").exists()
        }

        executionResult should haveCompleted()
        executionResult.permissionDenied shouldBe false
    }
    "should not allow static{} to escape the sandbox" {
        val executionResult = Source(
            mapOf(
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
        """
            )
        ).compile().execute(SourceExecutionArguments("Example"))

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should not allow finalizers to escape the sandbox" {
        val executionResult = Source(
            mapOf(
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
            )
        ).compile().execute(SourceExecutionArguments("Example", maxOutputLines = 10240))
        System.gc()
        executionResult.outputLines shouldHaveSize 10000
        executionResult.outputLines.all { (_, line) -> line.trim() == "Example" } shouldBe true
    }
    "should not allow LambdaMetafactory to escape the sandbox" {
        val executionResult = Source.fromSnippet(
            """
import java.lang.invoke.*;
            
try {
    MethodHandle handle =
        MethodHandles.lookup().findStatic(System.class, "exit", MethodType.methodType(void.class, int.class));
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
        """.trim()
        ).compile().execute(SourceExecutionArguments(maxExtraThreads = 1))
        executionResult.permissionDenied shouldBe true
    }
    "should not allow MethodHandle-based reflection to dodge the sandbox" {
        val executionResult = Source.fromSnippet(
            """
import java.lang.invoke.*;
import com.puppycrawl.tools.checkstyle.api.CheckstyleException;
            
try {
    MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(CheckstyleException.class, MethodHandles.lookup());
    Class<?> shutdownClass = Class.forName("java.lang.Shutdown");
    Class<?> methodClass = lookup.findClass("java.lang.reflect.Method");
    MethodHandle gdmHandle = lookup.findVirtual(
        Class.class, "getDeclaredMethod", MethodType.methodType(methodClass, String.class, Class[].class)
    );
    Object exitMethod = gdmHandle.invokeWithArguments(shutdownClass, "exit", int.class);
    MethodHandle saHandle = lookup.findVirtual(methodClass, "trySetAccessible", MethodType.methodType(boolean.class));
    saHandle.invokeWithArguments(exitMethod);
    MethodHandle invokeHandle = lookup.findVirtual(
        methodClass, "invoke", MethodType.methodType(Object.class, Object.class, Object[].class)
    );
    invokeHandle.invokeWithArguments(exitMethod, null, 125);
} catch (Throwable t) {
    throw new RuntimeException(t);
}
        """.trim()
        ).compile().execute(SourceExecutionArguments(timeout = 10000))
        executionResult.permissionDenied shouldBe true
    }
    "should not allow MethodHandles to alter the security manager" {
        val executionResult = Source.fromSnippet(
            """
import java.lang.invoke.*;
import java.util.Map;
import com.puppycrawl.tools.checkstyle.api.CheckstyleException;
            
try {
    MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(CheckstyleException.class, MethodHandles.lookup());
    Class<?> sandboxClass = lookup.findClass("edu.illinois.cs.cs125.jeed.core.Sandbox");
    Class<?> fieldClass = lookup.findClass("java.lang.reflect.Field");
    MethodHandle gdfHandle = lookup.findVirtual(
        Class.class, "getDeclaredField", MethodType.methodType(fieldClass, String.class)
    );
    Object confinedField = gdfHandle.invokeWithArguments(sandboxClass, "confinedTasks");
    MethodHandle saHandle = lookup.findVirtual(
        fieldClass, "setAccessible", MethodType.methodType(void.class, boolean.class)
    );
    saHandle.invokeWithArguments(confinedField, true);
    MethodHandle getHandle = lookup.findVirtual(fieldClass, "get", MethodType.methodType(Object.class, Object.class));
    Map confined = (Map) getHandle.invokeWithArguments(confinedField, null);
    confined.clear();
} catch (Throwable t) {
    throw new RuntimeException(t);
}
        """.trim()
        ).compile().execute(SourceExecutionArguments(timeout = 10000))
        executionResult.permissionDenied shouldBe true
    }
    "should not allow calling forbidden methods" {
        val executionResult = Source.fromSnippet(
            """
import java.lang.invoke.MethodHandles;

MethodHandles.Lookup lookup = null;
var clazz = lookup.findClass("edu.illinois.cs.cs125.jeed.core.Sandbox");
System.out.println(clazz);
        """.trim()
        ).compile().execute(SourceExecutionArguments(timeout = 10000))
        executionResult.permissionDenied shouldBe true
        executionResult.threw shouldBe instanceOf<SecurityException>()
        executionResult.threw!!.message shouldBe "invocation of forbidden method"
    }
    "should not allow installing an agent through ByteBuddy, coroutine-style" {
        val executionResult = Source.fromSnippet(
            """
import net.bytebuddy.agent.ByteBuddyAgent;

ByteBuddyAgent.install(ByteBuddyAgent.AttachmentProvider.ForEmulatedAttachment.INSTANCE);
        """.trim()
        ).compile().execute(SourceExecutionArguments(timeout = 10000))
        executionResult.permissionDenied shouldBe true
        executionResult.completed shouldBe false
        executionResult.threw shouldNot beNull()
    }
    "should not allow installing an agent through ByteBuddy's default provider" {
        val executionResult = Source.fromSnippet(
            """
import net.bytebuddy.agent.ByteBuddyAgent;

ByteBuddyAgent.install();
        """.trim()
        ).compile().execute(SourceExecutionArguments(timeout = 10000))
        executionResult.completed shouldBe false
        executionResult.threw shouldNot beNull()
    }
    "should not allow using the attachment/VM API directly" {
        val executionResult = Source.fromSnippet(
            """
import com.sun.tools.attach.VirtualMachine;

var vms = VirtualMachine.list();
var vmid = vms.get(0).id();
var vm = VirtualMachine.attach(vmid);
        """.trim()
        ).compile().execute(SourceExecutionArguments(timeout = 10000))
        executionResult.permissionDenied shouldBe true
        executionResult.completed shouldBe false
        executionResult.threw shouldNot beNull()
    }
    "should not allow access to private constructors" {
        val executionResult = Source.fromSnippet(
            """
import java.lang.reflect.*;

class Example {
    private Example() { }
}

Constructor<?> cons = Example.class.getDeclaredConstructors()[0];
cons.setAccessible(true);
System.out.println(cons.newInstance());
        """.trim()
        ).compile().execute(SourceExecutionArguments(timeout = 10000))
        executionResult.permissionDenied shouldBe true
        executionResult.completed shouldBe false
    }
    "should not allow loading sun.misc classes" {
        val executionResult = Source.fromSnippet(
            """
import sun.misc.Unsafe;

Unsafe unsafe = null;
unsafe.getInt(null, 0); // obvious NPE, but should fail in classloading first
        """.trim()
        ).compile().execute(SourceExecutionArguments(timeout = 10000))
        executionResult.permissionDenied shouldBe true
        executionResult.permissionRequests.find {
            it.permission.name.startsWith("accessClassInPackage.sun")
        } shouldNot beNull()
        executionResult.completed shouldBe false
    }
    "should not allow Class.forName by default" {
        val executionResult = Source.fromSnippet(
            """
class X {}
var cl = X.class.getClassLoader().getParent();
System.out.println(Class.forName("edu.illinois.cs.cs125.jeed.core.Sandbox", true, cl));
        """.trim()
        ).compile().execute(SourceExecutionArguments(timeout = 10000))
        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should not allow using classloaders by default" {
        val executionResult = Source.fromSnippet(
            """
class X {}
var cl = X.class.getClassLoader().getParent();
System.out.println(cl.loadClass("edu.illinois.cs.cs125.jeed.core.Sandbox"));
        """.trim()
        ).compile().execute(SourceExecutionArguments(timeout = 10000))
        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "should not allow using classloaders through a cast to SecureClassLoader" {
        val executionResult = Source.fromSnippet(
            """
import com.puppycrawl.tools.checkstyle.api.CheckstyleException;
import java.security.SecureClassLoader;
var cl = (SecureClassLoader) CheckstyleException.class.getClassLoader();
System.out.println(cl.loadClass("edu.illinois.cs.cs125.jeed.core.Sandbox"));
        """.trim()
        ).compile().execute(SourceExecutionArguments(timeout = 10000))
        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
})
