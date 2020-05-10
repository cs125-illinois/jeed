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
import io.kotlintest.matchers.types.shouldBeTypeOf
import io.kotlintest.should
import io.kotlintest.shouldNot
import io.kotlintest.specs.StringSpec
import kotlinx.coroutines.async

class TestByteCodeRewriters : StringSpec({
    "should not intercept safe exceptions" {
        val executionResult = Source.fromSnippet(
            """
try {
    System.out.println("Try");
    Object o = null;
    o.toString();
} catch (NullPointerException e) {
    System.out.println("Catch");
} finally {
    System.out.println("Finally");
}
            """.trim()
        ).compile().execute()

        executionResult should haveCompleted()
        executionResult should haveOutput("Try\nCatch\nFinally")
    }
    "should intercept exceptions configured to be unsafe in catch blocks" {
        val executionResult = Source.fromSnippet(
            """
try {
    System.out.println("Try");
    Object o = null;
    o.toString();
} catch (NullPointerException e) {
    System.out.println("Catch");
} finally {
    System.out.println("Finally");
}
            """.trim()
        ).compile().execute(
            SourceExecutionArguments(
                classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                    unsafeExceptions = setOf(
                        "java.lang.NullPointerException"
                    )
                )
            )
        )

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
    "should intercept subclasses of exceptions configured to be unsafe in catch blocks" {
        val executionResult = Source.fromSnippet(
            """
try {
    System.out.println("Try");
    Object o = null;
    o.toString();
} catch (Exception e) {
    System.out.println("Catch");
} finally {
    System.out.println("Finally");
}
            """.trim()
        ).compile().execute(
            SourceExecutionArguments(
                classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                    unsafeExceptions = setOf(
                        "java.lang.NullPointerException"
                    )
                )
            )
        )

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
    "should intercept subclasses of exceptions configured to be unsafe in finally blocks" {
        val executionResult = Source.fromSnippet(
            """
try {
    System.out.println("Try");
    Object o = null;
    o.toString();
} finally {
    System.out.println("Finally");
}
            """.trim()
        ).compile().execute(
            SourceExecutionArguments(
                classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                    unsafeExceptions = setOf(
                        "java.lang.NullPointerException"
                    )
                )
            )
        )

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
    "should not intercept exceptions configured to be safe in finally blocks" {
        val executionResult = Source.fromSnippet(
            """
try {
    System.out.println("Try");
    Object o = null;
    o.toString();
} catch (ClassCastException e) {
    System.out.println("Catch");
} finally {
    System.out.println("Finally");
}
            """.trim()
        ).compile().execute(
            SourceExecutionArguments(
                classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                    unsafeExceptions = setOf(
                        "java.lang.ClassCastException"
                    )
                )
            )
        )

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try\nFinally")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
    "should handle nested try-catch blocks" {
        val executionResult = Source.fromSnippet(
            """
try {
    try {
        System.out.println("Try");
        String s = (String) new Object();
    } catch (ClassCastException e) {
        System.out.println("Catch");
        Object o = null;
        o.toString();
    } finally {
        System.out.println("Finally");
    }
} catch (NullPointerException e) {
    System.out.println("Broken");
} finally {
    System.out.println("Bah");
}
            """.trim()
        ).compile().execute(
            SourceExecutionArguments(
                classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                    unsafeExceptions = setOf(
                        "java.lang.NullPointerException"
                    )
                )
            )
        )

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try\nCatch")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
    "should handle try-catch blocks in loops" {
        val executionResult = Source.fromSnippet(
            """
while (true) {
    try {
        System.out.println("Try");
        String s = (String) new Object();
    } catch (ClassCastException e) {
        System.out.println("Catch");
        Object o = null;
        o.toString();
    } finally {
        System.out.println("Finally");
    }
}
            """.trim()
        ).compile().execute(
            SourceExecutionArguments(
                classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                    unsafeExceptions = setOf(
                        "java.lang.NullPointerException"
                    )
                )
            )
        )

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try\nCatch")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
    "should remove finalizers" {
        val executionResult = Source.fromSnippet(
            """
public class Example {
    public Example() {
        finalize();
    }
    protected void finalize() {
        System.out.println("Finalizer");
    }
}
Example ex = new Example();
            """.trim()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult shouldNot haveOutput("Finalizer")
    }
    "should not remove non-finalizer finalize methods" {
        val executionResult = Source.fromSnippet(
            """
public class Example {
    public Example() {
        finalize(0);
        finalize("", 0.0);
    }
    protected void finalize(int unused) {
        System.out.println("Finalizer 1");
    }
    public String finalize(String toReturn, double unused) {
        System.out.println("Finalizer 2");
        return toReturn;
    }
}
Example ex = new Example();
            """.trim()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("Finalizer 1\nFinalizer 2")
    }
    "should allow synchronization to work correctly" {
        val executionResult = Source(mapOf("Main.java" to """
public class Other implements Runnable {
    public void run() {
        for (int i = 0; i < 100; i++) {
            synchronized (Main.monitor) {
                int temp = Main.counter + 1;
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    System.out.println("Interrupted!");
                }
                Main.counter = temp;
            }
        }
    }
}
public class Main {
    public static Object monitor = new Object();
    public static int counter = 0;
    public static void main() throws InterruptedException {
        Thread other = new Thread(new Other());
        other.start();
        for (int i = 0; i < 100; i++) {
            synchronized (monitor) {
                int temp = counter + 1;
                Thread.sleep(1);
                counter = temp;
            }
        }
        other.join();
        System.out.println(counter);
    }
}""".trim())).compile().execute(SourceExecutionArguments(maxExtraThreads = 1, timeout = 500L))
        executionResult shouldNot haveTimedOut()
        executionResult should haveCompleted()
        executionResult should haveOutput("200")
    }
    "should allow synchronization with notification" {
        setOf("", "1000L", "999L, 999999").forEach { waitParamList ->
            val executionResult = Source(mapOf("Main.java" to """
public class Other implements Runnable {
    public void run() {
        synchronized (Main.monitor) {
            Main.monitor.notifyAll();
            System.out.println("Notified");
        }
    }
}
public class Main {
    public static Object monitor = new Object();
    public static void main() {
        new Thread(new Other()).start();
        synchronized (monitor) {
            try {
                monitor.wait([PARAM_LIST]);
                System.out.println("Finished wait");
            } catch (InterruptedException e) {
                System.out.println("Failed to wait");
            }
        }
    }
}""".trim().replace("[PARAM_LIST]", waitParamList)))
                .compile().execute(SourceExecutionArguments(maxExtraThreads = 1))
            executionResult should haveCompleted()
            executionResult should haveOutput("Notified\nFinished wait")
        }
    }
    "should prevent cross-task monitor interference" {
        val badCompileResult = Source(mapOf("Main.java" to """
public class LockHog {
    public static void main() {
        System.out.println("About to spin");
        synchronized (Object.class) {
            while (true) {}
        }
    }
}""".trim())).compile()
        val goodCompileResult = Source.fromSnippet("""
Thread.sleep(100);
synchronized (Object.class) {
    System.out.println("Synchronized");
}""".trim()).compile()
        val badTask = async {
            badCompileResult.execute(SourceExecutionArguments(timeout = 800L, klass = "LockHog"))
        }
        val goodTaskResult = goodCompileResult.execute(SourceExecutionArguments(timeout = 150L))
        goodTaskResult should haveCompleted()
        goodTaskResult should haveOutput("Synchronized")
        val badTaskResult = badTask.await()
        badTaskResult should haveTimedOut()
        badTaskResult should haveOutput("About to spin")
    }
})
