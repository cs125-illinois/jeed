package edu.illinois.cs.cs125.jeed.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.Matcher
import io.kotest.matchers.MatcherResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class TestSnippet : StringSpec({
    "should parse snippets" {
        Source.fromSnippet(
            """
import java.util.List;

class Test {
    int me = 0;
    int anotherTest() {
        return 8;
    }
}
int testing() {
    int j = 0;
    return 10;
}
class AnotherTest { }
int i = 0;
i++;""".trim()
        )
    }
    "should parse Kotlin snippets" {
        Source.fromSnippet(
            """
import java.util.List

class Test(var me: Int = 0) {
  fun anotherTest() = 8
}
fun testing(): Int {
    var j = 0
    return 10
}
class AnotherTest
var i = 0
i++""".trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
    }
    "should not fail with new switch statements in snippets" {
        Source.fromSnippet(
            """
public boolean shouldMakeCoffee(String situation) {
        return switch (situation) {
          case "Morning", "Cramming" -> true;
          case "Midnight" -> true;
          default -> false;
        };
}""".trim()
        )
    }
    "should identify a parse errors in a broken snippet" {
        val exception = shouldThrow<SnippetTransformationFailed> {
            Source.fromSnippet(
                """
class Test {
    int me = 0;
    int anotherTest() {
      return 8;
    }
}
int testing() {
    int j = 0;
    return 10;
}
int i = 0;
i++
""".trim()
            )
        }
        exception.errors shouldHaveSize 1
        exception should haveParseErrorOnLine(12)
    }
    "should not allow top-level return in snippet" {
        val exception = shouldThrow<SnippetTransformationFailed> {
            Source.fromSnippet(
                """
class Test {
    int me = 0;
    int anotherTest() {
      return 8;
    }
}
int testing() {
    int j = 0;
    return 10;
}
return;
""".trim()
            )
        }
        exception.errors shouldHaveSize 1
        exception should haveParseErrorOnLine(11)
    }
    "should not allow package declarations in snippets" {
        val exception = shouldThrow<SnippetTransformationFailed> {
            Source.fromSnippet(
                """
package test.me;
int i = 0;
System.out.println(i);
""".trim()
            )
        }
        exception.errors shouldHaveSize 1
        exception should haveParseErrorOnLine(1)
    }
    "should identify multiple parse errors in a broken snippet" {
        val exception = shouldThrow<SnippetTransformationFailed> {
            Source.fromSnippet(
                """
class;
class Test {
    int me = 0;
    int anotherTest() {
      return 8;
    }
}
int testing() {
    int j = 0;
    return 10;
}
int i = 0;
i++
""".trim()
            )
        }
        exception.errors shouldHaveSize 3
        exception should haveParseErrorOnLine(1)
        exception should haveParseErrorOnLine(13)
        exception should haveParseErrorOnLine(14)
    }
    "should be able to reconstruct original sources using entry map" {
        val snippet =
            """
int i = 0;
i++;
public class Test {}
int adder(int first, int second) {
    return first + second;
}
        """.trim()
        val source = Source.fromSnippet(snippet)

        source.originalSource shouldBe (snippet)
        source.rewrittenSource shouldNotBe (snippet)
        source.originalSourceFromMap() shouldBe (snippet)
    }
    "should be able to reconstruct original Kotlin sources using entry map" {
        val snippet =
            """
fun first() = Test(3)
data class Test(val first: Int)
fun second(): Test {
  return first()
}
println(second())
        """.trim()
        val source = Source.fromSnippet(snippet, SnippetArguments(fileType = Source.FileType.KOTLIN))

        source.originalSource shouldBe (snippet)
        source.rewrittenSource shouldNotBe (snippet)
        source.originalSourceFromMap() shouldBe (snippet)
    }
    "should not allow return statements in loose code" {
        shouldThrow<SnippetTransformationFailed> {
            Source.fromSnippet(
                """
return;
        """.trim()
            )
        }
    }
    "should not allow return statements in loose code even under if statements" {
        shouldThrow<SnippetTransformationFailed> {
            Source.fromSnippet(
                """
int i = 0;
if (i > 2) {
    return;
}
        """.trim()
            )
        }
    }
    "should add static to methods that lack static" {
        Source.fromSnippet(
            """
void test0() {
  System.out.println("Hello, world!");
}
public void test1() {
  System.out.println("Hello, world!");
}
private void test2() {
  System.out.println("Hello, world!");
}
protected void test3() {
  System.out.println("Hello, world!");
}
  public void test4() {
  System.out.println("Hello, world!");
}
        """.trim()
        ).compile()
    }
    "should parse Java 13 constructs in snippets" {
        val executionResult = Source.fromSnippet(
            """
static String test(int arg) {
  return switch (arg) {
      case 0 -> "test";
      case 1 -> {
        yield "interesting";
      }
      default -> "whatever";
  };
}
System.out.println(test(0));
        """.trim()
        ).compile().execute()
        executionResult should haveCompleted()
        executionResult should haveOutput("test")
    }
    "should reject imports not at top of snippet" {
        val exception = shouldThrow<SnippetTransformationFailed> {
            Source.fromSnippet(
                """
public class Foo { }
System.out.println("Hello, world!");
import java.util.List;
        """.trim()
            )
        }
        exception.errors shouldHaveSize 1
        exception should haveParseErrorOnLine(3)
    }
    "should allow class declarations in blocks" {
        Source.fromSnippet(
            """
boolean value = true;
if (value) {
  class Foo { }
}
System.out.println("Hello, world!");
        """.trim()
        ).compile()
    }
    "should allow top-level lambda expression returns" {
        Source.fromSnippet(
            """
interface Test {
  int test();
}
void tester(Test test) {
  System.out.println(test.test());
}
tester(() -> {
  return 0;
});
        """.trim()
        ).compile()
    }
    "should allow method declarations in anonymous classes in snippets" {
        Source.fromSnippet(
            """
interface IncludeValue {
  boolean include(int value);
}
int countArray(int[] values, IncludeValue includeValue) {
  int count = 0;
  for (int value : values) {
    if (includeValue.include(value)) {
      count++;
    }
  }
  return count;
}

int[] array = {1, 2, 5, -1};
System.out.println(countArray(array, new IncludeValue() {
  @Override
  public boolean include(int value) {
    return value < 5 && value > 10;
  }
}));
        """.trim()
        ).compile()
    }
    "should handle warnings from outside the snippet" {
        Source.fromSnippet(
            """
import net.bytebuddy.agent.ByteBuddyAgent;
ByteBuddyAgent.install(ByteBuddyAgent.AttachmentProvider.ForEmulatedAttachment.INSTANCE);
        """.trim()
        ).compile()
    }
    "should parse kotlin snippets" {
        Source.fromSnippet(
            """
data class Person(val name: String)
fun test() {
  println("Here")
}
val i = 0
println(i)
test()
""".trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
    }
    "should parse kotlin snippets containing only comments" {
        Source.fromSnippet(
            """
// Test me
""".trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
    }
    "should identify parse errors in broken kotlin snippets" {
        val exception = shouldThrow<SnippetTransformationFailed> {
            Source.fromSnippet(
                """
import kotlinx.coroutines.*

data class Person(val name: String)
fun test() {
  println("Here")
}}
val i = 0
println(i)
test()
""".trim(),
                SnippetArguments(fileType = Source.FileType.KOTLIN)
            )
        }
        exception.errors shouldHaveSize 1
        exception should haveParseErrorOnLine(6)
    }
    "should be able to reconstruct original kotlin sources using entry map" {
        val snippet =
            """
import kotlinx.coroutines.*

data class Person(val name: String)
fun test() {
  println("Here")
}
i = 0
println(i)
test()
""".trim()
        val source = Source.fromSnippet(snippet, SnippetArguments(fileType = Source.FileType.KOTLIN))

        source.originalSource shouldBe (snippet)
        source.rewrittenSource shouldNotBe (snippet)
        source.originalSourceFromMap() shouldBe (snippet)
    }
    "should not allow return statements in loose kotlin code" {
        val exception = shouldThrow<SnippetTransformationFailed> {
            Source.fromSnippet(
                """
return
        """.trim(),
                SnippetArguments(fileType = Source.FileType.KOTLIN)
            )
        }
        exception.errors shouldHaveSize 1
        exception should haveParseErrorOnLine(1)
    }
    "should not allow return statements in loose kotlin code even under if statements" {
        val exception = shouldThrow<SnippetTransformationFailed> {
            Source.fromSnippet(
                """
val i = 0
if (i < 1) {
    return
}
        """.trim(),
                SnippetArguments(fileType = Source.FileType.KOTLIN)
            )
        }
        exception.errors shouldHaveSize 1
        exception should haveParseErrorOnLine(3)
    }
    "should allow return statements in loose kotlin code in methods" {
        Source.fromSnippet(
            """
println("Here")
fun add(a: Int, b: Int): Int {
  return a + b
}
println(add(2, 3))
        """.trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
    }
    "should not allow package declarations in kotlin snippets" {
        val exception = shouldThrow<SnippetTransformationFailed> {
            Source.fromSnippet(
                """
package test.me

println("Hello, world!")
        """.trim(),
                SnippetArguments(fileType = Source.FileType.KOTLIN)
            )
        }
        exception.errors shouldHaveSize 1
        exception should haveParseErrorOnLine(1)
    }
    "should not allow a class named MainKt in kotlin snippets" {
        val exception = shouldThrow<SnippetTransformationFailed> {
            Source.fromSnippet(
                """
class MainKt() { }

println("Hello, world!")
        """.trim(),
                SnippetArguments(fileType = Source.FileType.KOTLIN)
            )
        }
        exception.errors shouldHaveSize 1
        exception should haveParseErrorOnLine(1)
    }
    "should remap errors properly in kotlin snippets" {
        val exception = shouldThrow<CompilationFailed> {
            Source.fromSnippet(
                """
data class Person(name: String)
println("Hello, world!")
        """.trim(),
                SnippetArguments(fileType = Source.FileType.KOTLIN)
            ).kompile()
        }
        exception.errors shouldHaveSize 1
        exception.errors[0].location?.line shouldBe 1
    }
    "should parse instanceof pattern matching properly" {
        Source.fromSnippet(
            """
Object o = new String("");
if (o instanceof String s) {
  System.out.println(s.length());
}
            """.trim()
        ).compile()
    }
    "should parse records properly" {
        Source.fromSnippet(
            """
record Range(int lo, int hi) {
    public Range {
        if (lo > hi) {
            throw new IllegalArgumentException(String.format("(%d,%d)", lo, hi));
        }
    }
}            """.trim()
        ).compile()
    }
    "should parse text blocks properly" {
        val input = "String data = \"\"\"\nHere\n\"\"\";\n" + "System.out.println(data);".trim()
        Source.fromSnippet(input).compile().execute().also {
            it should haveOutput("Here")
        }
    }
    "should parse text blocks properly in Kotlin snippets" {
        val input = "val data = \"\"\"Here\nMe\"\"\"\n" + "println(data)".trim()
        Source.fromSnippet(input, SnippetArguments(fileType = Source.FileType.KOTLIN)).kompile().execute().also {
            it should haveExactOutput("Here\nMe")
        }
    }
    "should not fail on unmapped compiler errors" {
        shouldThrow<CompilationFailed> {
            Source.fromSnippet(
                """
int count(int[] v) {
  return 0;
}}
            """.trim()
            ).compile()
        }
    }
    "should allow interfaces in snippets" {
        Source.fromSnippet(
            """
interface Test {
  void test();
}
class Tester implements Test {
  public void test() { }
}
            """.trim()
        ).compile()
    }
    "should allow generic methods in Java snippets" {
        Source.fromSnippet(
            """
<T> T max(T[] array) {
  return null;
}
            """.trim()
        ).compile()
    }
    "should allow generic methods in Kotlin snippets" {
        Source.fromSnippet(
            """
class Test<T>
fun <T> Test<T>.requireIndexIsNotNegative(index: Int): Unit = require(index >= 0)
            """.trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).kompile()
    }
    "should allow anonymous classes in snippets" {
        Source.fromSnippet(
            """
interface Adder {
  int addTo(int value);
}
Adder addOne = new Adder() {
  @Override
  public int addTo(int value) {
    return value + 1;
  }
};
            """.trim()
        ).compile()
    }
    "should hoist functions in Kotlin snippets" {
        Source.fromSnippet(
            """
fun first() = Test(3)
data class Test(val first: Int)
fun second(): Test {

    return first()
}
println(second())
            """.trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN, indent = 4)
        ).also {
            it.ktLint(KtLintArguments(failOnError = true))
        }.kompile().execute().also {
            it should haveOutput("Test(first=3)")
        }
    }
    "should rewrite stack traces for Kotlin snippets" {
        val source = Source.fromSnippet(
            """
fun first(test: Int) {
  require(test % 2 == 0) { "Test is not even" }
}
fun second(test: Int) {
  return first(test)
}
second(3)
            """.trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
        source.kompile().execute().also { results ->
            results.threw!!.getStackTraceForSource(source).lines().also {
                it shouldHaveSize 4
                it[2].trim() shouldBe "at second(:5)"
                it[3].trim() shouldBe "at main(:7)"
            }
        }
    }
    "should rewrite compilation errors for Kotlin snippets" {
        val exception = shouldThrow<CompilationFailed> {
            Source.fromSnippet(
                """
fun reversePrint(values: CharArray): Int {
  var tempsize = values.size
  var temp: CharArray = values.reverse()
  for (i in 0..tempsize) {
    println(temp[i])
  }
  return temp.size
}
            """.trim(),
                SnippetArguments(fileType = Source.FileType.KOTLIN)
            ).kompile()
        }
        exception.errors.first().location!!.line shouldBe 3
    }
    "should parse Kotlin property getters and setters" {
        Source.fromSnippet(
            """
class Dog(val name: String?) {
  var age: Double
    set(value) {
      field = value
    }
    get() = field
}
            """.trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
        Source.fromSnippet(
            """
class Dog(val name: String?) {
  var age: Double
    get() = field
    set(value) {
      field = value
    }
}
            """.trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
    }
    "should parse Kotlin secondary constructors" {
        Source.fromSnippet(
            """
class Person(val name: String, var age: Double) {
  constructor(name: String) : this(name, 0.0)
  init {
    require(age >= 0.0) { "People can't have negative ages" }
  }
}
            """.trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
    }
    "should parse Kotlin functional interfaces" {
        Source.fromSnippet(
            """
fun interface It {
  fun it(value: Int): Boolean
}
val first = It { value -> value % 2 == 0 }
            """.trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        )
    }
    "should parse Java 15 case syntax" {
        if (systemCompilerVersion >= 14) {
            Source.fromSnippet(
                """
int foo = 3;
boolean boo = switch (foo) {
  case 1, 2, 3 -> true;
  default -> false;
};
System.out.println(boo);
            """.trim()
            )
        }
    }
    "should parse another Java 15 case syntax" {
        if (systemCompilerVersion >= 14) {
            Source.fromSnippet(
                """
int foo = 3;
boolean boo = switch (foo) {
  case 1:
  case 2:
  case 3:
    yield false;
  default:
    yield true;
};
            """.trim()
            )
        }
    }
    "should use Example.main when no loose code is provided" {
        Source.fromSnippet(
            """
class Another {}
public class Example {
  public static void main(String[] unused) {
    System.out.println("Here");
  }
}""".trim()
        ).compile().execute().also { executionResult ->
            executionResult should haveCompleted()
            executionResult should haveOutput("Here")
        }
        Source.fromSnippet(
            """
public class Example {
  public static void main() {
    int[] array = new int[] {1, 2, 4};
    System.out.println("ran");
  }
}""".trim()
        ).compile().execute().also { executionResult ->
            executionResult should haveCompleted()
            executionResult should haveOutput("ran")
        }
    }
    "should not use Example.main when a top-level method exists" {
        Source.fromSnippet(
            """
int another() {
 return 0;
}
public class Example {
  public static void main(String[] unused) {
    System.out.println("Here");
  }
}""".trim()
        ).compile().execute().also { executionResult ->
            executionResult should haveCompleted()
            executionResult should haveOutput("")
        }
    }
    "should not use Example.main when Example is not public" {
        Source.fromSnippet(
            """
class Example {
  public static void main(String[] unused) {
    System.out.println("Here");
  }
}""".trim()
        ).compile().execute().also { executionResult ->
            executionResult should haveCompleted()
            executionResult should haveOutput("")
        }
    }
    "should parse kotlin snippets without empty main when requested" {
        Source.fromSnippet(
            """
fun test() {
  i = 0
}
""".trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).also {
            it.rewrittenSource.lines() shouldHaveSize 9
            val compilerError = shouldThrow<CompilationFailed> {
                it.kompile()
            }
            compilerError.errors shouldHaveSize 1
            compilerError.errors[0].location?.line shouldBe 2
        }
        Source.fromSnippet(
            """
fun test() {
  i = 0
}
""".trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN, noEmptyMain = true)
        ).also {
            it.rewrittenSource.lines() shouldHaveSize 7
            val compilerError = shouldThrow<CompilationFailed> {
                it.kompile()
            }
            compilerError.errors shouldHaveSize 1
            compilerError.errors[0].location?.line shouldBe 2
        }
    }
})

fun haveParseErrorOnLine(line: Int) = object : Matcher<SnippetTransformationFailed> {
    override fun test(value: SnippetTransformationFailed): MatcherResult {
        return MatcherResult(
            value.errors.any { it.location.line == line },
            { "should have parse error on line $line" },
            { "should not have parse error on line $line" }
        )
    }
}
