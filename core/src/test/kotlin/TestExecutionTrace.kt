package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.beEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.beInstanceOf

class TestExecutionTrace : StringSpec({
    "should register for line trace events" {
        val result = Source.fromJava(
            """
public class Main {
  public static void main() {
    int i = 4;
    i += 1;
    System.out.println(i);
  }
}""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(LineTrace).addPlugin(ExecutionTrace))
        val executionTrace = result.pluginResult(ExecutionTrace)
        executionTrace.steps shouldContain ExecutionStep.Line("Main.java", 3)
    }

    "should record method entry" {
        val result = Source.fromJava(
            """
public class Main {
  public static void main() {
    int i = 4;
    i += 1;
    System.out.println(i);
  }
}""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        val executionTrace = result.pluginResult(ExecutionTrace)
        executionTrace.steps[0] should beInstanceOf<ExecutionStep.EnterMethod>()
        val methodStep = executionTrace.steps[0] as ExecutionStep.EnterMethod
        methodStep.method.method shouldBe "main"
        methodStep.receiver should beNull()
        methodStep.arguments should beEmpty()
    }

    "should record method entry with arguments" {
        val result = Source.fromSnippet(
            """
void printInt(int number) {
    System.out.println(number);
}
void printSum(short a, short b) {
    printInt(a + b);
}
printSum((short) 6, (short) 4);
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        result should haveOutput("10")
        val executionTrace = result.pluginResult(ExecutionTrace)
        val methodSteps = executionTrace.steps.filterIsInstance<ExecutionStep.EnterMethod>()
        methodSteps[1].method.method shouldBe "printSum"
        methodSteps[1].receiver should beNull()
        methodSteps[1].arguments.size shouldBe 2
        methodSteps[1].arguments[0].argumentName shouldBe "a"
        methodSteps[1].arguments[0].value shouldBe 6
        methodSteps[1].arguments[1].argumentName shouldBe "b"
        methodSteps[2].method.method shouldBe "printInt"
        methodSteps[2].receiver should beNull()
        methodSteps[2].arguments.size shouldBe 1
        methodSteps[2].arguments[0].argumentName shouldBe "number"
        methodSteps[2].arguments[0].value shouldBe 10
    }

    "should record method entry with receivers" {
        val result = Source.fromSnippet(
            """
class Adder {
    private int base;
    Adder(int setBase) {
        base = setBase;
    }
    int add(int plus) {
        return base + plus;
    }
}
Adder adder = new Adder(5);
System.out.println(adder.add(2));
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        result should haveOutput("7")
        val executionTrace = result.pluginResult(ExecutionTrace)
        val methodSteps = executionTrace.steps.filterIsInstance<ExecutionStep.EnterMethod>()
        val addStep = methodSteps.find { it.method.className == "Adder" && it.method.method == "add" }!!
        addStep.arguments[0].argumentName shouldBe "plus"
        addStep.arguments[0].value shouldBe 2
        addStep.receiver shouldNot beNull()
    }

    "should record successful completion of a void method" {
        val result = Source.fromSnippet(
            """
void printSum(int a, int b) {
    System.out.println(a + b);
}
printSum(10, 2);
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        result should haveOutput("12")
        val executionTrace = result.pluginResult(ExecutionTrace)
        val enterStep = executionTrace.steps.find { it is ExecutionStep.EnterMethod && it.method.method == "printSum" }
        enterStep shouldNot beNull()
        val exitSteps = executionTrace.steps.filterIsInstance<ExecutionStep.ExitMethodNormally>()
        exitSteps shouldHaveSize 2 // One for printSum, one for main
        exitSteps[0].returnValue should beNull()
    }

    "should record method return" {
        val result = Source.fromSnippet(
            """
int addOrSubtract(int a, int b, boolean subtract) {
    return subtract ? (a - b) : (a + b);
}
addOrSubtract(10, 5, false);
addOrSubtract(10, 5, true);
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        val executionTrace = result.pluginResult(ExecutionTrace)
        val enterSteps = executionTrace.steps.filter {
            it is ExecutionStep.EnterMethod && it.method.method == "addOrSubtract"
        }
        enterSteps shouldHaveSize 2
        val exitSteps = executionTrace.steps.filterIsInstance<ExecutionStep.ExitMethodNormally>()
        exitSteps shouldHaveSize 3
        exitSteps[0].returnValue shouldBe 15
        exitSteps[1].returnValue shouldBe 5
        exitSteps[2].returnValue should beNull()
    }

    "should record exceptional method exit" {
        val result = Source.fromSnippet(
            """
void crash() {
    int a = "x".hashCode(); // Pointless locals and flow control to test stackmap frame verification
    if (a != 0) {
        int b = 10;
        for (int i = 3; i >= 0; i--) {
            try {
                var divResult = 30 / i;
                System.out.println(divResult);
            } catch (NullPointerException npe) {}
        }
    }
}
try {
    crash();
} catch (Exception e) {
    System.out.println(e.getMessage());
}
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        result.output shouldContain "by zero"
        val executionTrace = result.pluginResult(ExecutionTrace)
        val enterStep = executionTrace.steps.find { it is ExecutionStep.EnterMethod && it.method.method == "crash" }
        enterStep shouldNot beNull()
        val exitStep = executionTrace.steps.find { it is ExecutionStep.ExitMethodExceptionally }
        exitStep shouldNot beNull()
    }
})
