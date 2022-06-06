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
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.beInstanceOf
import io.kotest.matchers.types.shouldBeTypeOf
import kotlinx.coroutines.async
import org.junit.jupiter.api.assertThrows

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

    "should allow safe custom exceptions" {
        val executionResult = Source.fromSnippet(
            """
public class ExampleException extends RuntimeException {}
try {
    throw new ExampleException();
} catch (ExampleException e) {
    System.out.println("Catch");
}
            """.trim()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("Catch")
    }

    "should handle dangerous custom exceptions" {
        val executionResult = Source.fromSnippet(
            """
public class ExampleError extends Error {}
try {
    throw new ExampleError();
} catch (ExampleError e) {
    System.out.println("Catch");
}
            """.trim()
        ).compile().execute()
        executionResult shouldNot haveCompleted()
        executionResult.threw should beInstanceOf(Error::class)
    }

    "should correctly handle throw inside try-catch" {
        val executionResult = Source.fromSnippet(
            """
try {
    System.out.println("Try");
    throw new RuntimeException("Boom");
} catch (Exception e) {
    System.out.println(e.getMessage());
}
            """.trim()
        ).compile().execute()

        executionResult should haveCompleted()
        executionResult should haveOutput("Try\nBoom")
    }

    "should correctly handle throw inside try-finally" {
        val executionResult = Source.fromSnippet(
            """
try {
    System.out.println("Try");
    throw new RuntimeException("Boom");
} finally {
    System.out.println("Finally");
}
            """.trim()
        ).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try\nFinally")
        executionResult.threw should beInstanceOf<RuntimeException>()
    }

    "should correctly handle throw inside try-catch-finally" {
        val executionResult = Source.fromSnippet(
            """
try {
    System.out.println("Try");
    throw new RuntimeException("Boom");
} catch (Exception e) {
    System.out.println("Catch");
    throw new RuntimeException("Bang");
} finally {
    System.out.println("Finally");
}
            """.trim()
        ).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try\nCatch\nFinally")
        executionResult.threw should beInstanceOf<RuntimeException>()
        executionResult.threw!!.message shouldBe "Bang"
    }

    "should correctly handle throwing a dangerous exception inside try-finally" {
        val executionResult = Source.fromSnippet(
            """
try {
    System.out.println("Try");
    throw new Error("Boom");
} finally {
    System.out.println("Finally");
}
            """.trim()
        ).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try")
        executionResult.threw should beInstanceOf<Error>()
    }

    "should correctly handle throwing a dangerous exception inside try-catch-finally" {
        val executionResult = Source.fromSnippet(
            """
try {
    System.out.println("Try");
    throw new RuntimeException("Boom");
} catch (Exception e) {
    System.out.println("Catch");
    throw new Error("Bang");
} finally {
    System.out.println("Finally");
}
            """.trim()
        ).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult should haveOutput("Try\nCatch")
        executionResult.threw should beInstanceOf<Error>()
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
        val executionResult = Source(
            mapOf(
                "Main.java" to """
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
}""".trim()
            )
        ).compile().execute(SourceExecutionArguments(maxExtraThreads = 1, timeout = 1000L))
        executionResult shouldNot haveTimedOut()
        executionResult should haveCompleted()
        executionResult should haveOutput("200")
    }

    "should allow synchronization with notification" {
        setOf("", "1000L", "999L, 999999").forEach { waitParamList ->
            val executionResult = Source(
                mapOf(
                    "Main.java" to """
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
}""".trim().replace("[PARAM_LIST]", waitParamList)
                )
            )
                .compile().execute(SourceExecutionArguments(maxExtraThreads = 1))
            executionResult should haveCompleted()
            executionResult should haveOutput("Notified\nFinished wait")
        }
    }

    "should prevent cross-task monitor interference" {
        val badCompileResult = Source(
            mapOf(
                "Main.java" to """
public class LockHog {
    public static void main() {
        System.out.println("About to spin");
        synchronized (Object.class) {
            while (true) {}
        }
    }
}""".trim()
            )
        ).compile()
        val goodCompileResult = Source.fromSnippet(
            """
Thread.sleep(100);
synchronized (Object.class) {
    System.out.println("Synchronized");
}""".trim()
        ).compile()
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

    "should allow synchronized methods to run" {
        val executionResult = Source.fromSnippet(
            """
            synchronized int getFive() {
                return 5;
            }
            System.out.println(getFive());
            """.trimIndent()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("5")
    }

    "should correctly handle try-catch blocks inside synchronized methods" {
        val executionResult = Source.fromSnippet(
            """
            synchronized int getFive() {
                try {
                    Object obj = null;
                    return obj.hashCode();
                } catch (NullPointerException e) {
                    return 5;
                } finally {
                    System.out.println("Finally");
                }
            }
            System.out.println(getFive());
            """.trimIndent()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("Finally\n5")
    }

    "should correctly handle throw statements inside synchronized methods" {
        val executionResult = Source.fromSnippet(
            """
            synchronized int getFive() {
                System.out.println("Synchronized");
                try {
                    throw new Exception("Boom!");
                } catch (Exception e) {
                    System.out.println(e.getMessage());
                    return 5;
                } finally {
                    System.out.println("Finally");
                }
            }
            System.out.println(getFive());
            """.trimIndent()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("Synchronized\nBoom!\nFinally\n5")
    }

    "should correctly handle synchronized methods that always throw" {
        val executionResult = Source.fromSnippet(
            """
            synchronized int throwFive() throws Exception {
                System.out.println("Synchronized");
                throw new Exception("5");
            }
            try {
                throwFive();
            } catch (Exception e) {
                System.out.println(e.getMessage());
            }
            """.trimIndent()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("Synchronized\n5")
    }

    "should correctly handle synchronized methods that return references" {
        val executionResult = Source.fromSnippet(
            """
            synchronized String getFive() {
                return "5";
            }
            System.out.println(getFive());
            """.trimIndent()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("5")
    }

    "should correctly handle synchronized methods that return large primitives" {
        val executionResult = Source.fromSnippet(
            """
            synchronized long getFive() {
                return 5L;
            }
            synchronized double getPi() {
                return 3.14159;
            }
            System.out.println((int) (getFive() * getFive() * getPi()));
            """.trimIndent()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("78")
    }

    "should correctly handle synchronized methods that take parameters" {
        val executionResult = Source.fromSnippet(
            """
            synchronized void printSum(String prefix, byte a, long c, double factor) {
                double sum = (double) a + c * factor;
                System.out.println(prefix + sum);
            }
            printSum("Sum: ", (byte) 10, 100, 3.13);
            """.trimIndent()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("Sum: 323.0")
    }

    "should correctly handle synchronized methods involving multiple large primitives" {
        val executionResult = Source.fromSnippet(
            """
            synchronized double sum(long a, long b, long c, long d, double factor) {
                return (a + b + c + d) * factor;
            }
            System.out.println(sum(10, 20, 30, 40, 1.5));
            """.trimIndent()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("150.0")
    }

    "should correctly handle synchronized methods involving small primitives" {
        val executionResult = Source.fromSnippet(
            """
            synchronized byte addToByte(float a, short b) {
                return (byte) (a + b);
            }
            System.out.println(addToByte(2.0f, (short) 3));
            """.trimIndent()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("5")
    }

    "should correctly handle recursive synchronized methods" {
        val executionResult = Source.fromSnippet(
            """
            synchronized long factorial(int n) {
                if (n <= 1) {
                    return 1;
                } else {
                    return n * factorial(n - 1);
                }
            }
            System.out.println(factorial(14));
            """.trimIndent()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("87178291200")
    }

    "should correctly handle synchronized instance methods" {
        val executionResult = Source.fromSnippet(
            """
            class Example {
                synchronized int getFivePlus(short value) {
                    return 5 + value;
                }
            }
            System.out.println(new Example().getFivePlus((short) 10));
            """.trimIndent()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("15")
    }

    "should correctly handle synchronized methods that involve arrays" {
        val executionResult = Source.fromSnippet(
            """
            synchronized int[] parse(String[] numbers) {
                int[] values = new int[numbers.length];
                for (int i = 0; i < numbers.length; i++) {
                    values[i] = Integer.parseInt(numbers[i]);
                }
                return values;
            }
            int[] parsed = parse(new String[] {"5"});
            System.out.println(parsed[0]);
            """.trimIndent()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("5")
    }

    "should unlock the monitor on successful exit from synchronized methods" {
        val executionResult = Source.fromSnippet(
            """
            class Example implements Runnable {
                public void run() {
                    Util.printExcitedly("Bye");
                }
            }
            class Util {
                synchronized static void printExcitedly(String text) {
                    try {
                        Object obj = null;
                        obj.hashCode();
                    } catch (NullPointerException e) {
                        // Wow this is pointless!
                    }
                    System.out.println(text + "!");
                }
            }
            Util.printExcitedly("Hi");
            Thread t = new Thread(new Example());
            t.start();
            t.join();
            """.trimIndent()
        ).compile().execute(SourceExecutionArguments(maxExtraThreads = 1))
        executionResult should haveCompleted()
        executionResult should haveOutput("Hi!\nBye!")
    }

    "should unlock the monitor on exceptional exit from synchronized methods" {
        val executionResult = Source.fromSnippet(
            """
            class Example implements Runnable {
                public void run() {
                    try {
                        Util.throwExcitedly("Bye");
                    } catch (Exception e) {
                        System.out.println(e.getMessage());
                    }
                }
            }
            class Util {
                synchronized static void throwExcitedly(String text) throws Exception {
                    if (System.currentTimeMillis() != 0) {
                        throw new Exception(text + "!");
                    }
                }
            }
            try {
                Util.throwExcitedly("Hi");
            } catch (Exception e) {
                System.out.println(e.getMessage());
            }
            Thread t = new Thread(new Example());
            t.start();
            t.join();
            """.trimIndent()
        ).compile().execute(SourceExecutionArguments(maxExtraThreads = 1))
        executionResult should haveCompleted()
        executionResult should haveOutput("Hi!\nBye!")
    }

    "should allow exclusion to work correctly with synchronized methods" {
        val executionResult = Source(
            mapOf(
                "Main.java" to """
public class Counter {
    public static int counter;
    public static synchronized void increment() throws InterruptedException {
        int tmp = counter + 1;
        Thread.sleep(1);
        counter = tmp;
    }
}
public class Other implements Runnable {
    public void run() {
        try {
            for (int i = 0; i < 100; i++) {
                Counter.increment();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
public class Main {
    public static void main() throws InterruptedException {
        Thread other = new Thread(new Other());
        other.start();
        for (int i = 0; i < 100; i++) {
            Counter.increment();
        }
        other.join();
        System.out.println(Counter.counter);
    }
}""".trim()
            )
        ).compile().execute(SourceExecutionArguments(maxExtraThreads = 1, timeout = 500L))
        executionResult shouldNot haveTimedOut()
        executionResult should haveCompleted()
        executionResult should haveOutput("200")
    }

    "should prevent untrusted code from running after the task ends" {
        val executionResult = Source(
            mapOf(
                "Main.java" to """
public class Main {
    public static Object main() {
        return new Object() {
            @Override
            public String toString() {
                System.exit(125);
                return "unreachable";
            }
        };
    }
}""".trim()
            )
        ).compile().execute()
        executionResult should haveCompleted()
        assertThrows<SecurityException> { executionResult.returned!!.toString() }
    }

    "should prevent custom exceptions from escaping the sandbox" {
        val executionResult = Source(
            mapOf(
                "Main.java" to """
public class Main {
    public static void main() {
        throw new RuntimeException() {
            @Override
            public String toString() {
                System.exit(125);
                return "unreachable";
            }
        };
    }
}""".trim()
            )
        ).compile().execute()
        executionResult shouldNot haveCompleted()
        executionResult.threw!!.javaClass shouldBe SecurityException::class.java
    }

    "should not choke on abstract methods" {
        val executionResult = Source(
            mapOf(
                "Main.java" to """
public abstract class Describable {
    public void printDescription() {
        System.out.println(describe());
    }
    public abstract String describe();
}
public class Main {
    public static void main() {
        new Describable() {
            public String describe() {
                return "Implementation";
            }
        }.printDescription();
    }
}""".trim()
            )
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("Implementation")
    }

    "should cache transformed reloaded bytecode" {
        val compileResult = Source.fromSnippet(
            """
import kotlinx.coroutines.*;
GlobalScope.class.toString();
            """.trim()
        ).compile()
        val executeArgs = SourceExecutionArguments(
            classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                unsafeExceptions = setOf("java.lang.ArithmeticException", "java.lang.StackOverflowError")
            )
        )
        val firstExecution = compileResult.execute(executeArgs)
        firstExecution.sandboxedClassLoader!!.transformedReloadedClasses shouldNotBe 0
        val secondExecution = compileResult.execute(executeArgs)
        secondExecution.sandboxedClassLoader!!.transformedReloadedClasses shouldBe 0
    }

    "should key the reloaded bytecode cache by rewriting configuration" {
        val compileResult = Source.fromSnippet(
            """
import kotlinx.coroutines.*;
GlobalScope.class.toString();
            """.trim()
        ).compile()
        val firstExecution = compileResult.execute(
            SourceExecutionArguments(
                classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                    unsafeExceptions = setOf("java.lang.StackOverflowError", "java.lang.InternalError")
                )
            )
        )
        firstExecution.sandboxedClassLoader!!.transformedReloadedClasses shouldNotBe 0
        val secondExecution = compileResult.execute(
            SourceExecutionArguments(
                classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                    unsafeExceptions = setOf("java.lang.InternalError")
                )
            )
        )
        secondExecution.sandboxedClassLoader!!.transformedReloadedClasses shouldNotBe 0
    }

    "should handle NEW instructions at the start of handlers" {
        val executionResult = Source.fromSnippet(
            """
try {
    System.out.println("Try");
    Object o = null;
    o.toString();
} catch (Exception e) {
    String s = new String("x".hashCode() > 0 ? "a" : "b");
    System.out.println(s);
} finally {
    String s = new String("x".hashCode() > 0 ? "a" : "b");
    System.out.println(s);
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

    "should support references to submission methods" {
        val executionResult = Source.fromSnippet(
            """
                import java.util.function.*;
                class Widget {
                    int instanceGet() {
                        return 10;
                    }
                    static double staticGet() {
                        return 2.25;
                    }
                    Supplier<Object> getInnerFactory() {
                        return Inner::new;
                    }
                    Supplier<String> getSuperStringify() {
                        return super::toString;
                    }
                    public String toString() {
                        return "Overridden";
                    }
                    private class Inner {
                        public String toString() {
                            return "Inner";
                        }
                    }
                }
                DoubleSupplier staticGet = Widget::staticGet;
                System.out.println(staticGet.getAsDouble());
                Supplier<Widget> ctor = Widget::new;
                var widget = ctor.get();
                ToIntFunction<Widget> unboundInstanceGet = Widget::instanceGet;
                System.out.println(unboundInstanceGet.applyAsInt(widget));
                IntSupplier boundInstanceGet = widget::instanceGet;
                System.out.println(boundInstanceGet.getAsInt());
                System.out.println(widget.getInnerFactory().get());
                System.out.println(widget.getSuperStringify().get());
            """.trimIndent()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult.output shouldStartWith "2.25\n10\n10\nInner\nWidget@"
    }

    "should support references to library instance methods" {
        val executionResult = Source.fromSnippet(
            """
                import java.util.function.*;
                ToIntFunction<Integer> unboundInt = Integer::intValue;
                Integer i = 5;
                System.out.println(unboundInt.applyAsInt(i));
                IntSupplier boundInt = i::intValue;
                System.out.println(boundInt.getAsInt());
                Supplier<String> boundIntStringify = i::toString;
                System.out.println(boundIntStringify.get());
                ToLongFunction<Long> unboundLong = Long::longValue;
                Long l = 1L << 33;
                System.out.println(unboundLong.applyAsLong(l));
                LongSupplier boundLong = l::longValue;
                System.out.println(boundLong.getAsLong());
            """.trimIndent()
        ).compile().execute()
        executionResult should haveCompleted()
        val longResult = 1L shl 33
        executionResult should haveOutput("5\n5\n5\n$longResult\n$longResult")
    }

    "should support references to library methods with inexact types" {
        val executionResult = Source.fromSnippet(
            """
                import java.util.function.*;
                Function<Integer, Object> unboundInt = Integer::intValue;
                Integer i = 5;
                System.out.println(unboundInt.apply(i));
                Supplier<Integer> boundInt = i::intValue;
                System.out.println(boundInt.get());
                Supplier<Object> boundIntStringify = i::toString;
                System.out.println(boundIntStringify.get());
                Function<Long, Object> unboundLong = Long::longValue;
                Long l = 1L << 33;
                System.out.println(unboundLong.apply(l));
                Supplier<Long> boundLong = l::longValue;
                System.out.println(boundLong.get());
            """.trimIndent()
        ).compile().execute()
        executionResult should haveCompleted()
        val longResult = 1L shl 33
        executionResult should haveOutput("5\n5\n5\n$longResult\n$longResult")
    }

    "should support references to library interface methods" {
        val executionResult = Source.fromSnippet(
            """
                import java.util.function.*;
                import java.util.stream.IntStream;
                ToIntFunction<CharSequence> unboundCsLength = CharSequence::length;
                System.out.println(unboundCsLength.applyAsInt("abc"));
                CharSequence c = "abcde";
                IntSupplier boundCsLength = c::length;
                System.out.println(boundCsLength.getAsInt());
                Function<CharSequence, IntStream> csChars = CharSequence::chars;
                System.out.println(csChars.apply(c).count());
                
            """.trimIndent()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("3\n5\n5")
    }

    "should support references to library static methods" {
        val executionResult = Source.fromSnippet(
            """
                import java.util.function.*;
                import java.util.List;
                interface StringIntToLongBiFunction {
                    long apply(String s, int i);
                }
                LongFunction<Long> longValueOf = Long::valueOf;
                System.out.println(longValueOf.apply(10L));
                StringIntToLongBiFunction parseLongRadix = Long::parseLong;
                System.out.println(parseLongRadix.apply("FF", 16));
                Function<String, List<String>> singletonize = List::of; // Interface static method!
                System.out.println(singletonize.apply("hi"));
            """.trimIndent()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("10\n255\n[hi]")
    }

    "should support references to library constructors" {
        val executionResult = Source.fromSnippet(
            """
                import java.util.function.*;
                Function<char[], String> strFromChars = String::new;
                System.out.println(strFromChars.apply(new char[] {'h', 'i'}));
                LongFunction<Long> boxLong = Long::new;
                System.out.println(boxLong.apply(2L));
            """.trimIndent()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("hi\n2")
    }
})
