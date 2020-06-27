package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.StringSpec

class TestMutater : StringSpec({
    "it should find string literals to mutate" {
        Source(
            mapOf(
                "Example.java" to """
public class Example {
  public static void example() {
    System.out.println("Hello, world!");
  }
}""".trim()
            )
        ).getParsed("Example.java").also {
            StringLiteral.find(it).let {
                it shouldHaveSize 1
                it.first()
            }.also {
                it.apply()
                it.original shouldBe "\"Hello, world!\""
                it.applied shouldBe true
                it.modified shouldNotBe "\"Hello, world!\""
            }
            StringLiteral.find(it).let {
                it shouldHaveSize 1
                it.first()
            }.also {
                it.apply(
                    Mutation.Config(
                        stringLiteral = StringLiteral.Config(
                            random = false,
                            replaceWith = null
                        )
                    )
                )
                it.original shouldBe "\"Hello, world!\""
                it.applied shouldBe true
                it.modified shouldBe "null"
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
        ).getParsed("Example.java").also {
            IncrementDecrement.find(it).let { mutations ->
                mutations shouldHaveSize 2
                mutations.get(0).also {
                    it.apply()
                    it.original shouldBe "++"
                    it.modified shouldBe "--"
                    it.applied shouldBe true
                }
                mutations.get(1).also {
                    it.apply()
                    it.original shouldBe "--"
                    it.modified shouldBe "++"
                    it.applied shouldBe true
                }
            }
        }
    }
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
        ).getParsed("Example.java").also {
            BooleanLiteral.find(it).let { mutations ->
                mutations shouldHaveSize 2
                mutations.get(0).also {
                    it.apply()
                    it.original shouldBe "true"
                    it.modified shouldBe "false"
                    it.applied shouldBe true
                }
                mutations.get(1).also {
                    it.apply()
                    it.original shouldBe "false"
                    it.modified shouldBe "true"
                    it.applied shouldBe true
                }
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
            ConditionalBoundary.find(it).let { mutations ->
                mutations shouldHaveSize 2
                mutations.get(0).also {
                    it.apply()
                    it.original shouldBe "<"
                    it.modified shouldBe "<="
                    it.applied shouldBe true
                }
                mutations.get(1).also {
                    it.apply()
                    it.original shouldBe ">="
                    it.modified shouldBe ">"
                    it.applied shouldBe true
                }
            }
        }
    }
})