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
        ).getParsed("Example.java").also { parsedSource ->
            Mutation.find<BooleanLiteral>(parsedSource).let { mutations ->
                mutations shouldHaveSize 2
                mutations[0].check("true", "false")
                mutations[1].check("false", "true")
            }
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
        ).getParsed("Example.java").also { parsedSource ->
            Mutation.find<CharLiteral>(parsedSource).let { mutations ->
                mutations shouldHaveSize 2
                mutations[0].check("'a'")
                mutations[1].check("'!'")
            }
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
        ).getParsed("Example.java").also { parsedSource ->
            Mutation.find<StringLiteral>(parsedSource).let { mutations ->
                mutations shouldHaveSize 2
                mutations[0].check("\"Hello, world!\"")
                mutations[1].check("\"\"")
            }
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
        ).getParsed("Example.java").also { parsedSource ->
            Mutation.find<IncrementDecrement>(parsedSource).let { mutations ->
                mutations shouldHaveSize 2
                mutations[0].check("++", "--")
                mutations[1].check("--", "++")
            }
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
        ).getParsed("Example.java").also { parsedSource ->
            Mutation.find<InvertNegation>(parsedSource).let { mutations ->
                mutations shouldHaveSize 2
                mutations[0].check("-", "")
                mutations[1].check("-", "")
            }
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
        ).getParsed("Example.java").also { parsedSource ->
            Mutation.find<MutateMath>(parsedSource).let { mutations ->
                mutations shouldHaveSize 10
            }
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
        ).getParsed("Example.java").also {
            Mutation.find<ConditionalBoundary>(it).let { mutations ->
                mutations shouldHaveSize 2
                mutations[0].check("<", "<=")
                mutations[1].check(">=", ">")
            }
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
        ).getParsed("Example.java").also {
            Mutation.find<NegateConditional>(it).let { mutations ->
                mutations shouldHaveSize 3
                mutations[0].check("<", ">=")
                mutations[1].check(">=", "<")
                mutations[2].check("==", "!=")
            }
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
        ).getParsed("Example.java").also {
            Mutation.find<PrimitiveReturn>(it).let { mutations ->
                mutations shouldHaveSize 2
                mutations[0].check("1", "0")
                mutations[1].check("'A'", "0")
            }
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
        ).getParsed("Example.java").also {
            Mutation.find<TrueReturn>(it).let { mutations ->
                mutations shouldHaveSize 2
                mutations[0].check("it", "true")
                mutations[1].check("false", "true")
            }
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
        ).getParsed("Example.java").also {
            Mutation.find<FalseReturn>(it).let { mutations ->
                mutations shouldHaveSize 2
                mutations[0].check("it", "false")
                mutations[1].check("true", "false")
            }
        }
    }
})

fun Mutation.check(original: String, modified: String? = null) {
    original shouldNotBe modified
    applied shouldBe false
    this.original shouldBe original
    this.modified shouldBe null
    apply()
    applied shouldBe true
    this.original shouldBe original
    this.modified shouldNotBe original
    modified?.also { this.modified shouldBe modified }
}
