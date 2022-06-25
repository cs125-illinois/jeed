package edu.illinois.cs.cs125.jeed.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.shouldNotBe
import java.nio.charset.Charset

class TestExecute : StringSpec({
    "should execute snippets" {
        val executeMainResult = Source.fromSnippet(
            """
int i = 0;
i++;
System.out.println(i);
            """.trim()
        ).compile().execute()
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("1")
    }
    "should execute cached snippets" {
        Source.fromSnippet(
            """
int i = 0;
i++;
System.out.println(i);
            """.trim()
        ).compile(CompilationArguments(useCache = true)).execute()

        val executeMainResult = Source.fromSnippet(
            """
int i = 0;
i++;
System.out.println(i);
            """.trim()
        ).compile(CompilationArguments(useCache = true)).let {
            it.cached shouldBe true
            it.execute()
        }

        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("1")
    }
    "should execute snippets that include class definitions" {
        val executeMainResult = Source.fromSnippet(
            """
public class Foo {
    int i = 0;
}
Foo foo = new Foo();
foo.i = 4;
System.out.println(foo.i);
            """.trim()
        ).compile().execute()
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("4")
    }
    "should execute the right class in snippets that include multiple class definitions" {
        val compiledSource = Source.fromSnippet(
            """
public class Bar {
    public static void main() {
        System.out.println("Bar");
    }
}
public class Foo {
    public static void main() {
        System.out.println("Foo");
    }
}
System.out.println("Main");
            """.trim()
        ).compile()

        val executeBarResult = compiledSource.execute(SourceExecutionArguments(klass = "Bar"))
        executeBarResult should haveCompleted()
        executeBarResult should haveOutput("Bar")

        val executeFooResult = compiledSource.execute(SourceExecutionArguments(klass = "Foo"))
        executeFooResult should haveCompleted()
        executeFooResult should haveOutput("Foo")

        val executeMainResult = compiledSource.execute(SourceExecutionArguments(klass = "Main"))
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("Main")

        val executionFailed = shouldThrow<ExecutionFailed> {
            compiledSource.execute(SourceExecutionArguments(klass = "Baz"))
        }
        executionFailed.classNotFound shouldNotBe null
        executionFailed.methodNotFound shouldBe null
    }
    "should execute the right method in snippets that include multiple method definitions" {
        val compiledSource = Source.fromSnippet(
            """
public static void foo() {
    System.out.println("foo");
}
public static void bar() {
    System.out.println("bar");
}
System.out.println("main");
            """.trim()
        ).compile()

        val executeFooResult = compiledSource.execute(SourceExecutionArguments(method = "foo()"))
        executeFooResult should haveCompleted()
        executeFooResult should haveOutput("foo")

        val executeBarResult = compiledSource.execute(SourceExecutionArguments(method = "bar()"))
        executeBarResult should haveCompleted()
        executeBarResult should haveOutput("bar")

        val executeMainResult = compiledSource.execute(SourceExecutionArguments(method = "main()"))
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("main")
    }
    "should not execute private methods" {
        val compiledSource = Source.fromSnippet(
            """
private static void foo() {
    System.out.println("foo");
}
System.out.println("main");
            """.trim()
        ).compile()

        val executionFailed = shouldThrow<ExecutionFailed> {
            compiledSource.execute(SourceExecutionArguments(method = "foo()"))
        }
        executionFailed.methodNotFound shouldNotBe null
        executionFailed.classNotFound shouldBe null

        val executeMainResult = compiledSource.execute(SourceExecutionArguments(method = "main()"))
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("main")
    }
    "should not execute non-static methods" {
        val compiledSource = Source.fromSnippet(
            """
class Test {
    public void foo() {
        System.out.println("foo");
    }
}
System.out.println("main");
            """.trim()
        ).compile()

        val executionFailed = shouldThrow<ExecutionFailed> {
            compiledSource.execute(SourceExecutionArguments(klass = "Test", method = "foo()"))
        }
        executionFailed.methodNotFound shouldNotBe null
        executionFailed.classNotFound shouldBe null

        val executeMainResult = compiledSource.execute(SourceExecutionArguments(method = "main()"))
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("main")
    }
    "should not execute methods that require arguments" {
        val compiledSource = Source.fromSnippet(
            """
public static void foo(int i) {
    System.out.println("foo");
}
System.out.println("main");
            """.trim()
        ).compile()

        val executionFailed = shouldThrow<ExecutionFailed> {
            compiledSource.execute(SourceExecutionArguments(method = "foo()"))
        }
        executionFailed.methodNotFound shouldNotBe null
        executionFailed.classNotFound shouldBe null

        val executeMainResult = compiledSource.execute(SourceExecutionArguments(method = "main()"))
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("main")
    }
    "should execute sources" {
        val executionMainResult = Source(
            mapOf(
                "Main.java" to """
public class Main {
    public static void main() {
        var i = 0;
        System.out.println("Here");
    }
}
                """.trim()
            )
        ).compile().execute()
        executionMainResult should haveCompleted()
        executionMainResult shouldNot haveTimedOut()
        executionMainResult should haveStdout("Here")
    }
    "should special case main with string array" {
        val executionMainResult = Source(
            mapOf(
                "Main.java" to """
public class Main {
    public static void main(String[] unused) {
        var i = 0;
        System.out.println("Here");
    }
}
                """.trim()
            )
        ).compile().execute(SourceExecutionArguments(method = "main(String[])"))
        executionMainResult should haveCompleted()
        executionMainResult shouldNot haveTimedOut()
        executionMainResult should haveStdout("Here")
    }
    "should execute multiple sources with dependencies" {
        val executionMainResult = Source(
            mapOf(
                "Main.java" to """
public class Main {
    public static void main() {
        var i = 0;
        Foo.foo();
    }
}
                """.trim(),
                "Foo.java" to """
public class Foo {
    public static void foo() {
        System.out.println("Foo");
    }
}
                """.trim()
            )
        ).compile().execute()
        executionMainResult should haveCompleted()
        executionMainResult shouldNot haveTimedOut()
        executionMainResult should haveStdout("Foo")
    }
    "should throw missing class exceptions correctly" {
        val executionFailed = shouldThrow<ExecutionFailed> {
            Source.fromSnippet(
                """
int i = 0;
i++;
System.out.println(i);
            """.trim()
            ).compile().execute(SourceExecutionArguments(klass = "Test"))
        }
        executionFailed.classNotFound shouldNotBe null
        executionFailed.methodNotFound shouldBe null
    }
    "should throw missing method exceptions correctly" {
        val executionFailed = shouldThrow<ExecutionFailed> {
            Source.fromSnippet(
                """
int i = 0;
i++;
System.out.println(i);
            """.trim()
            ).compile().execute(SourceExecutionArguments(method = "test"))
        }
        executionFailed.methodNotFound shouldNotBe null
        executionFailed.classNotFound shouldBe null
    }
    "should import libraries properly" {
        val executionResult = Source.fromSnippet(
            """
import java.util.List;
import java.util.ArrayList;

List<Integer> list = new ArrayList<>();
list.add(8);
System.out.println(list.get(0));
            """.trim()
        ).compile().execute()

        executionResult should haveCompleted()
        executionResult should haveOutput("8")
    }
    "should execute sources that use inner classes" {
        val executionResult = Source(
            mapOf(
                "Main.java" to """
public class Main {
    class Inner {
        Inner() {
            System.out.println("Inner");
        }
    }
    Main() {
        Inner inner = new Inner();
    }
    public static void main() {
        Main main = new Main();
    }
}
                """.trim()
            )
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveStdout("Inner")
    }
    "should execute sources that use Java 12 features" {
        @Suppress("MagicNumber")
        if (systemCompilerVersion >= 12) {
            val executionResult = Source(
                mapOf(
                    "Main.java" to """
public class Main {
    public static String testYieldKeyword(int switchArg) {
        return switch (switchArg) {
            case 1, 2 -> "works";
            case 3 -> "oh boy";
            default -> "testing";
        };
    }
    public static void main() {
        System.out.println(testYieldKeyword(1));
    }
}
                """.trim()
                )
            ).compile().execute()

            executionResult should haveCompleted()
            executionResult should haveStdout("works")
        }
    }
    "should execute sources that use Java 13 features" {
        @Suppress("MagicNumber")
        if (systemCompilerVersion >= 13) {
            val executionResult = Source(
                mapOf(
                    "Main.java" to """
public class Main {
    public static String testYieldKeyword(int switchArg) {
        return switch (switchArg) {
            case 1, 2: yield "works";
            case 3: yield "oh boy";
            default: yield "testing";
        };
    }
    public static void main() {
        System.out.println(testYieldKeyword(0));
    }
}
                """.trim()
                )
            ).compile().execute()

            executionResult should haveCompleted()
            executionResult should haveStdout("testing")
        }
    }
    "should execute Kotlin snippets" {
        val executionMainResult = Source.fromSnippet(
            """
println("Here")
                """.trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).kompile().execute()
        executionMainResult should haveCompleted()
        executionMainResult shouldNot haveTimedOut()
        executionMainResult should haveStdout("Here")
    }
    "should execute kotlin snippets that include class definitions" {
        val executeMainResult = Source.fromSnippet(
            """
data class Foo(var i: Int)
val foo = Foo(5);
foo.i = 4;
println(foo.i);
            """.trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).kompile().execute()
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("4")
    }
    "should execute kotlin snippets that include method definitions" {
        val executeMainResult = Source.fromSnippet(
            """
fun test(): String {
    return "Hello, world!"
}
println(test())
            """.trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).kompile().execute()
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("Hello, world!")
    }
    "should execute cached kotlin snippets that include method definitions" {
        Source.fromSnippet(
            """
fun test(): String {
    return "Hello, world!"
}
println(test())
            """.trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).kompile(KompilationArguments(useCache = true)).execute()
        val executeMainResult = Source.fromSnippet(
            """
fun test(): String {
    return "Hello, world!"
}
println(test())
            """.trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
            .kompile(KompilationArguments(useCache = true)).let {
                it.cached shouldBe true
                it.execute()
            }
        executeMainResult should haveCompleted()
        executeMainResult should haveOutput("Hello, world!")
    }
    "should execute Kotlin snippets that use method references" {
        val executionMainResult = Source.fromSnippet(
            """
import java.io.PrintStream

val print1: (String) -> Unit = ::println
print1("Here")
val print2: (String) -> Unit = System.out::println
print2("There")
val print3: PrintStream.(String) -> Unit = PrintStream::println
print3(System.out, "Elsewhere")
                """.trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).kompile().execute()
        executionMainResult should haveCompleted()
        executionMainResult shouldNot haveTimedOut()
        executionMainResult should haveStdout("Here\nThere\nElsewhere")
    }
    "should execute simple Kotlin sources" {
        val executionMainResult = Source(
            mapOf(
                "Main.kt" to """
fun main() {
  println("Here")
}
                """.trim()
            )
        ).kompile().execute()
        executionMainResult should haveCompleted()
        executionMainResult shouldNot haveTimedOut()
        executionMainResult should haveStdout("Here")
    }
    "should execute simple Kotlin sources with cross-method calls" {
        val executionMainResult = Source(
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
        ).kompile().execute()
        executionMainResult should haveCompleted()
        executionMainResult shouldNot haveTimedOut()
        executionMainResult should haveStdout("""[test, me]""")
    }
    "should trim stack traces properly" {
        val source = Source.fromSnippet(
            """
Object o = null;
o.toString();
        """.trim()
        )

        val executionFailed = source.compile().execute()
        executionFailed.threw!!.getStackTraceForSource(source).lines() shouldHaveSize 2
    }
    "should trim deep stack traces properly" {
        val source = Source.fromSnippet(
            """
void test() {
  Object o = null;
  o.toString();
}
test();
        """.trim()
        )

        val executionFailed = source.compile().execute()
        val stacktrace = executionFailed.threw!!.getStackTraceForSource(source).lines()
        stacktrace shouldHaveSize 3
        stacktrace[1].trim() shouldBe "at test(:3)"
    }
    "should trim deep stack traces from classes properly" {
        val source = Source.fromSnippet(
            """
class Test {
  public static void test() {
    Object o = null;
    o.toString();
  }
}
Test.test();
        """.trim()
        )

        val executionFailed = source.compile().execute()
        val stacktrace = executionFailed.threw!!.getStackTraceForSource(source).lines()
        stacktrace shouldHaveSize 3
        stacktrace[1].trim() shouldBe "at Test.test(:4)"
    }
    "should trim Kotlin stack traces properly" {
        val source = Source.fromSnippet(
            """
var letters = CharArray(4)
letters[-1] = 'X'
        """.trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )

        val executionFailed = source.kompile().execute()
        executionFailed.threw!!.getStackTraceForSource(source).lines() shouldHaveSize 2
    }
    "should execute sources that use Java 14 features" {
        @Suppress("MagicNumber")
        if (systemCompilerVersion >= 14) {
            @Suppress("SpellCheckingInspection")
            val executionResult = Source(
                mapOf(
                    "Main.java" to """
record Range(int lo, int hi) {
    public Range {
        if (lo > hi) {
            throw new IllegalArgumentException(String.format("(%d,%d)", lo, hi));
        }
    }
}
public class Main {
    public static void main() {
        Object o = new Range(0, 10);
        if (o instanceof Range r) {
            System.out.println(r.hi());
        }
    }
}
                """.trim()
                )
            ).compile().execute()

            executionResult should haveCompleted()
            executionResult should haveStdout("10")
        }
    }
    "should print unicode" {
        val charset = Charset.defaultCharset()
        if (charset == Charsets.UTF_8 || charset == Charsets.UTF_16) {
            val executeMainResult = Source.fromSnippet(
                """
System.out.println("➡️👤 are ❌️ alone");
            """.trim()
            ).compile().execute()
            executeMainResult should haveCompleted()
            executeMainResult should haveOutput("""➡️👤 are ❌️ alone""")
        }
    }
    "should not fail on locales" {
        Source.fromSnippet(
            """
import java.util.Date;
long t = System.currentTimeMillis();
System.out.println(new Date(t).toString());
            """.trim()
        ).compile().execute().also {
            it should haveCompleted()
        }
    }
    "should print records" {
        Source.fromSnippet(
            """record Person(String name, int age) {}
                |System.out.println(new Person("Geoffrey", 42));
            """.trimMargin()
        ).compile().execute().also {
            it should haveCompleted()
        }
    }
    "should executeWith properly" {
        Source.fromSnippet(
            """record Person(String name, int age) {}
                |System.out.println(new Person("Geoffrey", 42));
            """.trimMargin()
        ).compile().executeWith {
            return@executeWith 10
        }.also {
            it should haveCompleted()
            it.returned shouldBe 10
        }
    }
    "should execute top-level main in Kotlin snippets" {
        val executionMainResult = Source.fromSnippet(
            """
fun main() {
  println("Here")
}
                """.trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN, noEmptyMain = true)
        ).kompile().execute()
        executionMainResult should haveCompleted()
        executionMainResult shouldNot haveTimedOut()
        executionMainResult should haveStdout("Here")
    }
})

fun haveCompleted() = object : Matcher<Sandbox.TaskResults<out Any?>> {
    override fun test(value: Sandbox.TaskResults<out Any?>): MatcherResult {
        return MatcherResult(
            value.completed,
            {
                "Code should have run: " + when {
                    value.timeout -> "timed out"
                    value.threw != null -> value.threw.toString()
                    else -> "failed, but no timeout or exception reported"
                }
            },
            { "Code should not have run" }
        )
    }
}

fun haveTimedOut() = object : Matcher<Sandbox.TaskResults<out Any?>> {
    override fun test(value: Sandbox.TaskResults<out Any?>): MatcherResult {
        return MatcherResult(
            value.timeout,
            { "Code should have timed out" },
            { "Code should not have timed out" }
        )
    }
}

fun haveBeenKilled() = object : Matcher<Sandbox.TaskResults<out Any?>> {
    override fun test(value: Sandbox.TaskResults<out Any?>): MatcherResult {
        return MatcherResult(
            value.killReason != null,
            { "Task should have been killed" },
            { "Task should not have been killed" }
        )
    }
}

fun haveOutput(output: String = "") = object : Matcher<Sandbox.TaskResults<out Any?>> {
    override fun test(value: Sandbox.TaskResults<out Any?>): MatcherResult {
        val actualOutput = value.output.trim()
        return MatcherResult(
            actualOutput == output,
            { "Expected output $output, found $actualOutput" },
            { "Expected to not find output $actualOutput" }
        )
    }
}

fun haveExactOutput(output: String = "") = object : Matcher<Sandbox.TaskResults<out Any?>> {
    override fun test(value: Sandbox.TaskResults<out Any?>): MatcherResult {
        val actualOutput = value.output
        return MatcherResult(
            actualOutput == output,
            { "Expected output ->$output<-, found ->$actualOutput<-" },
            { "Expected to not find output $actualOutput" }
        )
    }
}

fun haveStdout(output: String) = object : Matcher<Sandbox.TaskResults<out Any?>> {
    override fun test(value: Sandbox.TaskResults<out Any?>): MatcherResult {
        val actualOutput = value.stdout.trim()
        return MatcherResult(
            actualOutput == output,
            { "Expected stdout $output, found $actualOutput" },
            { "Expected to not find stdout $actualOutput" }
        )
    }
}

fun haveStderr(output: String) = object : Matcher<Sandbox.TaskResults<out Any?>> {
    override fun test(value: Sandbox.TaskResults<out Any?>): MatcherResult {
        val actualOutput = value.stderr.trim()
        return MatcherResult(
            actualOutput == output,
            { "Expected stderr $output, found $actualOutput" },
            { "Expected to not find stderr $actualOutput" }
        )
    }
}
