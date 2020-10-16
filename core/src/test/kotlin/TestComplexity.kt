package edu.illinois.cs.cs125.jeed.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TestComplexity : StringSpec({
    "should calculate complexity for snippets" {
        val complexityResults = Source.fromSnippet(
            """
int add(int i, int j) {
    return i + j;
}
int i = 0;
""".trim()
        ).complexity()
        complexityResults.lookup("").complexity shouldBe 2
    }
    "should calculate complexity for sources" {
        val complexityResults = Source(
            mapOf(
                "Test.java" to """
public class Test {
    int add(int i, int j) {
        return i + j;
    }
}
""".trim()
            )
        ).complexity()
        complexityResults.lookup("Test.int add(int,int)", "Test.java").complexity shouldBe 1
    }
    "should fail properly on parse errors" {
        shouldThrow<ComplexityFailed> {
            Source(
                mapOf(
                    "Test.java" to """
public class Test
    int add(int i, int j) {
        return i + j;
    }
}
""".trim()
                )
            ).complexity()
        }
    }
    "should calculate complexity for simple conditional statements" {
        val complexityResults = Source(
            mapOf(
                "Test.java" to """
public class Test {
    int chooser(int i, int j) {
        if (i > j) {
            return i;
        } else {
            return i;
        }
    }
}
""".trim()
            )
        ).complexity()
        complexityResults.lookup("Test.int chooser(int,int)", "Test.java").complexity shouldBe 2
    }
    "should calculate complexity for complex conditional statements" {
        val complexityResults = Source(
            mapOf(
                "Test.java" to """
public class Test {
    int chooser(int i, int j) {
        if (i > j) {
            return i;
        } else if (i < j) {
            return j;
        } else if (i == j) {
            return i + j;
        } else {
            return i;
        }
    }
}
""".trim()
            )
        ).complexity()
        complexityResults.lookup("Test.int chooser(int,int)", "Test.java").complexity shouldBe 4
    }
    "should calculate complexity for classes in snippets" {
        Source.fromSnippet(
            """
class Example {
  int value = 0;
}
            """.trim()
        ).complexity()
    }
    "should not fail on records in snippets" {
        Source.fromSnippet(
            """
record Example(int value) { };
            """.trim()
        ).complexity()
    }
    "should not fail on records with contents" {
        Source.fromSnippet(
            """
record Example(int value) {
  public int it() {
    return value;
  }
};
            """.trim()
        ).complexity()
    }
    "should not fail on interfaces" {
        Source.fromSnippet(
            """
interface Simple {
  int simple(int first);
}
            """.trim()
        ).complexity()
    }
    "should not fail on anonymous classes" {
        Source.fromSnippet(
            """
interface Test {
  void test();
}
Test test = new Test() {
  @Override
  public void test() { }
};
            """.trim()
        ).complexity()
    }
    "should not fail on generic methods" {
        Source.fromSnippet(
            """
<T> T max(T[] array) {
  return null;
}
            """.trim()
        ).complexity()
    }
    "should not fail on lambda expressions with body" {
        Source.fromSnippet(
            """
Thread thread = new Thread(() -> {
  System.out.println("Blah");
});
            """.trim()
        ).complexity()
    }
    "should not fail on lambda expressions without body" {
        Source.fromSnippet(
            """
interface Modify {
  int modify(int value);
}
Modify first = (v) -> v + 1;
System.out.println(first.getClass());
            """.trim()
        ).complexity()
    }
})
