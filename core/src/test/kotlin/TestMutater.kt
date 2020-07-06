package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.StringSpec

class TestMutater : StringSpec({
    "it should find boolean literals to mutate" {
        Source(
            mapOf(
                "Example.java" to """
public class Example {
  public static void example() {
    boolean first = true;
    boolean second = false;
  }
}""".trim()
            )
        ).checkMutations<BooleanLiteral>() { mutations, contents ->
            mutations shouldHaveSize 2
            mutations[0].check(contents, "true", "false")
            mutations[1].check(contents, "false", "true")
        }
    }
    "it should find char literals to mutate" {
        Source(
            mapOf(
                "Example.java" to """
public class Example {
  public static void example() {
    char first = 'a';
    char second = '!';
  }
}""".trim()
            )
        ).checkMutations<CharLiteral> { mutations, contents ->
            mutations shouldHaveSize 2
            mutations[0].check(contents, "'a'")
            mutations[1].check(contents, "'!'")
        }
    }
    "it should find string literals to mutate" {
        Source(
            mapOf(
                "Example.java" to """
public class Example {
  public static void example() {
    System.out.println("Hello, world!");
    String s = "";
  }
}""".trim()
            )
        ).checkMutations<StringLiteral> { mutations, contents ->
            mutations shouldHaveSize 2
            mutations[0].check(contents, "\"Hello, world!\"")
            mutations[1].check(contents, "\"\"")
        }
    }
    "it should find number literals to mutate" {
        Source(
            mapOf(
                "Example.java" to """
public class Example {
  public static void example() {
    System.out.println(1234);
    float f = 1.01f;
  }
}""".trim()
            )
        ).checkMutations<NumberLiteral> { mutations, contents ->
            mutations shouldHaveSize 2
            mutations[0].check(contents, "1234")
            mutations[1].check(contents, "1.01f")
        }
    }
    "it should find increments and decrements to mutate" {
        Source(
            mapOf(
                "Example.java" to """
public class Example {
  public static void example() {
    int i = 0;
    int j = 1;
    i++;
    --j;
  }
}""".trim()
            )
        ).checkMutations<IncrementDecrement> { mutations, contents ->
            mutations shouldHaveSize 2
            mutations[0].check(contents, "++", "--")
            mutations[1].check(contents, "--", "++")
        }
    }
    "it should find negatives to invert" {
        Source(
            mapOf(
                "Example.java" to """
public class Example {
  public static void example() {
    int i = 0;
    int j = -1;
    int k = -j;
  }
}""".trim()
            )
        ).checkMutations<InvertNegation> { mutations, contents ->
            mutations shouldHaveSize 2
            mutations[0].check(contents, "-", "")
            mutations[1].check(contents, "-", "")
        }
    }
    "it should find math to mutate" {
        Source(
            mapOf(
                "Example.java" to """
public class Example {
  public static void example() {
    int i = 0;
    int j = 1;
    int k = i + j;
    k = i - j;
    k = i * j;
    k = i / j;
    int l = i % 10;
    l = i & j;
    l = j | i;
    l = j ^ i;
    l = i << 2;
    l = i >> 2;
    k = i >>> j;
  }
}""".trim()
            )
        ).checkMutations<MutateMath> { mutations, contents ->
            mutations shouldHaveSize 10
        }
    }
    "it should find conditional boundaries to mutate" {
        Source(
            mapOf(
                "Example.java" to """
public class Example {
  public static void example() {
    int i = 0;
    if (i < 10) {
      System.out.println("Here");
    } else if (i >= 20) {
      System.out.println("There");
    }
  }
}""".trim()
            )
        ).checkMutations<ConditionalBoundary> { mutations, contents ->
            mutations shouldHaveSize 2
            mutations[0].check(contents, "<", "<=")
            mutations[1].check(contents, ">=", ">")
        }
    }
    "it should find conditionals to negate" {
        Source(
            mapOf(
                "Example.java" to """
public class Example {
  public static void example() {
    int i = 0;
    if (i < 10) {
      System.out.println("Here");
    } else if (i >= 20) {
      System.out.println("There");
    } else if (i == 10) {
      System.out.println("Again");
    }
  }
}""".trim()
            )
        ).checkMutations<NegateConditional> { mutations, contents ->
            mutations shouldHaveSize 3
            mutations[0].check(contents, "<", ">=")
            mutations[1].check(contents, ">=", "<")
            mutations[2].check(contents, "==", "!=")
        }
    }
    "it should find primitive returns to mutate" {
        Source(
            mapOf(
                "Example.java" to """
                    public class Example {
  public static void first() {}
  public static int second() {
    return 1;
  }
  public static char third() {
    return 'A';
  }
  public static boolean fourth() {
    return true;
  }
  public static int fifth() {
    return 0;
  }
  public static long sixth() {
    return 0L;
  }
  public static double seventh() {
    return 0.0;
  }
  public static double eighth() {
    return 0.0f;
  }
}""".trim()
            )
        ).checkMutations<PrimitiveReturn> { mutations, contents ->
            mutations shouldHaveSize 2
            mutations[0].check(contents, "1", "0")
            mutations[1].check(contents, "'A'", "0")
        }
    }
    "it should find true returns to mutate" {
        Source(
            mapOf(
                "Example.java" to """
public class Example {
  public static void first() {}
  public static boolean second() {
    it = false;
    return it;
  }
  public static boolean third() {
    return false;
  }
  public static boolean fourth() {
    return true;
  }
}""".trim()
            )
        ).checkMutations<TrueReturn> { mutations, contents ->
            mutations shouldHaveSize 2
            mutations[0].check(contents, "it", "true")
            mutations[1].check(contents, "false", "true")
        }
    }
    "it should find false returns to mutate" {
        Source(
            mapOf(
                "Example.java" to """
public class Example {
  public static void first() {}
  public static boolean second() {
    it = false;
    return it;
  }
  public static boolean third() {
    return false;
  }
  public static boolean fourth() {
    return true;
  }
}""".trim()
            )
        ).checkMutations<FalseReturn> { mutations, contents ->
            mutations shouldHaveSize 2
            mutations[0].check(contents, "it", "false")
            mutations[1].check(contents, "true", "false")
        }
    }
    "it should find null returns to mutate" {
        Source(
            mapOf(
                "Example.java" to """
public class Example {
  public static void first() {}
  public static boolean second() {
    it = false;
    return it;
  }
  public static boolean third() {
    return false;
  }
  public static Object fourth() {
    return new Object();
  }
}""".trim()
            )
        ).checkMutations<NullReturn> { mutations, contents ->
            mutations shouldHaveSize 1
            mutations[0].check(contents, "new Object()", "null")
        }
    }
    "it should apply multiple mutations" {
        Source(
            mapOf(
                "Example.java" to """
public class Example {
  public static void greeting() {
    int i = 0;
    System.out.println("Hello, world!");
  }
}""".trim()
            )
        ).also { source ->
            source.mutater(8).also { mutater ->
                mutater.appliedMutations shouldHaveSize 0
                val modifiedSource = mutater.apply()
                source.contents shouldNotBe modifiedSource.contents
                mutater.appliedMutations shouldHaveSize 1
                mutater.size shouldBe 1
                val anotherModifiedSource = mutater.apply()
                setOf(source.contents, modifiedSource.contents, anotherModifiedSource.contents) shouldHaveSize 3
                mutater.size shouldBe 0
            }
        }
    }
})

inline fun <reified T : Mutation> Source.checkMutations(checker: (mutations: List<Mutation>, contents: String) -> Unit) =
    getParsed(name).also { parsedSource ->
        Mutation.find<T>(parsedSource).let { mutations ->
            checker(mutations, contents)
        }
    }

fun Mutation.check(contents: String, original: String, modified: String? = null) {
    original shouldNotBe modified
    applied shouldBe false
    this.original shouldBe original
    this.modified shouldBe null
    apply(contents)
    applied shouldBe true
    this.original shouldBe original
    this.modified shouldNotBe original
    modified?.also { this.modified shouldBe modified }
}
