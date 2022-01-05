package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.assertThrows
import java.io.IOException

class TestJacoco : StringSpec({
    "it should calculate full coverage properly" {
        Source.fromJava(
            """
public class Test {
  private int value;
  public Test() {
    value = 10;
  }
  public Test(int setValue) {
    value = setValue;
  }
}
public class Main {
  public static void main() {
    Test test = new Test(10);
    Test test2 = new Test();
    System.out.println("Yay");
  }
}""".trim()
        ).compile().jacoco().also { (taskResults, coverage) ->
            taskResults.completed shouldBe true
            taskResults.permissionDenied shouldNotBe true

            val testCoverage = coverage.classes.find { it.name == "Test" }!!
            testCoverage.lineCounter.missedCount shouldBe 0
            testCoverage.lineCounter.coveredCount shouldBe 6
        }
    }
    "it should detect uncovered code" {
        Source.fromJava(
            """
public class Test {
  private int value;
  public Test() {
    value = 10;
  }
  public Test(int setValue) {
    value = setValue;
  }
}
public class Main {
  public static void main() {
    Test test = new Test(10);
    System.out.println("Hmm");
  }
}""".trim()
        ).compile().jacoco().also { (taskResults, coverage) ->
            taskResults.completed shouldBe true
            taskResults.permissionDenied shouldNotBe true

            val testCoverage = coverage.classes.find { it.name == "Test" }!!
            testCoverage.lineCounter.missedCount shouldNotBe 0
            testCoverage.lineCounter.coveredCount shouldBe 3
        }
    }
    "it should allow class enumeration in the sandbox" {
        val source = Source.fromJava(
            """
public class Main {
  public static void main() {
    Main main = new Main();
    System.out.println(main.getClass().getDeclaredMethods().length);
  }
}""".trim()
        ).compile()
        source.execute().also {
            it should haveCompleted()
            it should haveOutput("1")
        }
        source.jacoco().also { (it) ->
            it should haveCompleted()
            it should haveOutput("2")
        }
    }
    "it should not allow hijacking jacocoInit" {
        val source = Source.fromJava(
            """
import java.lang.invoke.MethodHandles;
public class Main {
  private static void ${"$"}jacocoInit(MethodHandles.Lookup lookup, String name, Class type) {
    // do something with the lookup?
  }
  public static void main() { }
}""".trim()
        ).compile()
        assertThrows<IOException> {
            // Jacoco refuses to re-instrument, which is good
            source.jacoco()
        }
    }
})
