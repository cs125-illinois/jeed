package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.beEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSingleElement
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.nulls.beNull
import io.kotest.matchers.should
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNot
import io.kotest.matchers.shouldNotBe
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
        result should haveCompleted()
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
        methodStep.receiverId should beNull()
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
        methodSteps[1].receiverId should beNull()
        methodSteps[1].arguments.size shouldBe 2
        methodSteps[1].arguments[0].argumentName shouldBe "a"
        methodSteps[1].arguments[0].value.value shouldBe 6
        methodSteps[1].arguments[1].argumentName shouldBe "b"
        methodSteps[2].method.method shouldBe "printInt"
        methodSteps[2].receiverId should beNull()
        methodSteps[2].arguments.size shouldBe 1
        methodSteps[2].arguments[0].argumentName shouldBe "number"
        methodSteps[2].arguments[0].value.value shouldBe 10
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
        addStep.arguments[0].value.value shouldBe 2
        addStep.receiverId shouldNot beNull()
    }

    "should trace constructors" {
        val result = Source.fromSnippet(
            """
class Adder {
    private int base;
    Adder(int setBase) {
        base = setBase;
    }
}
Adder adder = new Adder(5);
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        val executionTrace = result.pluginResult(ExecutionTrace)
        val createSteps = executionTrace.steps.filterIsInstance<ExecutionStep.CreateObject>()
        createSteps shouldHaveSize 1
        createSteps[0].type shouldBe "Adder"
        val methodSteps = executionTrace.steps.filterIsInstance<ExecutionStep.EnterMethod>()
        val addStep = methodSteps.find { it.method.className == "Adder" }!!
        addStep.arguments[0].argumentName shouldBe "setBase"
        addStep.arguments[0].value.value shouldBe 5
        addStep.receiverId shouldBe createSteps[0].id
        val exitSteps = executionTrace.steps.filterIsInstance<ExecutionStep.ExitMethodNormally>()
        exitSteps shouldHaveSize 2
        exitSteps[0].returnValue.type shouldBe ExecutionTraceResults.ValueType.VOID
    }

    "should trace chained constructors" {
        val result = Source.fromSnippet(
            """
class Parent {
    Parent(int x) {}
}
class Child extends Parent {
    Child(String s) {
        this(s.hashCode());
    }
    Child(int z) {
        super(z + 1);
    }
}
Child c = new Child("hi");
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        val executionTrace = result.pluginResult(ExecutionTrace)
        val createSteps = executionTrace.steps.filterIsInstance<ExecutionStep.CreateObject>()
        createSteps shouldHaveSize 1
        createSteps[0].type shouldBe "Child"
        val methodSteps = executionTrace.steps.filterIsInstance<ExecutionStep.EnterMethod>()
        methodSteps[0].receiverId should beNull()
        methodSteps[1].method.className shouldBe "Child"
        methodSteps[1].arguments[0].argumentName shouldBe "s"
        methodSteps[1].arguments[0].value.type shouldBe ExecutionTraceResults.ValueType.REFERENCE
        methodSteps[1].receiverId shouldBe createSteps[0].id
        methodSteps[2].method.className shouldBe "Child"
        methodSteps[2].arguments[0].argumentName shouldBe "z"
        methodSteps[2].arguments[0].value.value shouldBe "hi".hashCode()
        methodSteps[2].receiverId shouldBe createSteps[0].id
        methodSteps[3].method.className shouldBe "Parent"
        methodSteps[3].arguments[0].argumentName shouldBe "x"
        methodSteps[3].arguments[0].value.value shouldBe "hi".hashCode() + 1
        methodSteps[3].receiverId shouldBe createSteps[0].id
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
        exitSteps[0].returnValue.type shouldBe ExecutionTraceResults.ValueType.VOID
        exitSteps[0].returnValue.value should beNull()
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
        exitSteps[0].returnValue.value shouldBe 15
        exitSteps[1].returnValue.value shouldBe 5
        exitSteps[2].returnValue.value should beNull()
    }

    "should distinguish the chain constructor call" {
        val result = Source.fromSnippet(
            """
class Parent {
    private int x;
    Parent(int setX) { x = setX; }
    int getX() { return x; }
}
class Child extends Parent {
    Child() {
        this(new Child(5).getX() + 1);
    }
    Child(int x) {
        super(x);
    }
}
Child c = new Child();
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        val executionTrace = result.pluginResult(ExecutionTrace)
        val createSteps = executionTrace.steps.filterIsInstance<ExecutionStep.CreateObject>()
        createSteps shouldHaveSize 2
        createSteps[0].type shouldBe "Child"
        createSteps[1].type shouldBe "Child"
        val methodSteps = executionTrace.steps.filterIsInstance<ExecutionStep.EnterMethod>()
        // Child()
        methodSteps[1].method.className shouldBe "Child"
        methodSteps[1].receiverId shouldBe createSteps[0].id
        methodSteps[1].arguments shouldHaveSize 0
        // Child(5)
        methodSteps[2].method.className shouldBe "Child"
        methodSteps[2].receiverId shouldBe createSteps[1].id
        methodSteps[2].arguments[0].value shouldBe ExecutionTraceResults.Value(ExecutionTraceResults.ValueType.INT, 5)
        // Parent(5)
        methodSteps[3].method.className shouldBe "Parent"
        methodSteps[3].receiverId shouldBe createSteps[1].id
        methodSteps[3].arguments[0].value shouldBe methodSteps[2].arguments[0].value
        // Child(5).getX()
        methodSteps[4].method.className shouldBe "Parent"
        methodSteps[4].method.method shouldBe "getX"
        methodSteps[4].receiverId shouldBe createSteps[1].id
        // Child(6)
        methodSteps[5].method.className shouldBe "Child"
        methodSteps[5].receiverId shouldBe createSteps[0].id
        methodSteps[5].arguments[0].value shouldBe ExecutionTraceResults.Value(ExecutionTraceResults.ValueType.INT, 6)
        // Parent(6)
        methodSteps[6].method.className shouldBe "Parent"
        methodSteps[6].receiverId shouldBe createSteps[0].id
        methodSteps[6].arguments[0].value shouldBe methodSteps[5].arguments[0].value
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

    "should record pre-chain constructor failure" {
        val result = Source.fromSnippet(
            """
class Parent {
    Parent(int x) {}
}
class Child extends Parent {
    Child(String s) {
        super(crash());
    }
    private static int crash() {
        throw new RuntimeException("boom");
    }
}
Child c = new Child("hi");
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result.threw should beInstanceOf<RuntimeException>()
        val executionTrace = result.pluginResult(ExecutionTrace)
        val createSteps = executionTrace.steps.filterIsInstance<ExecutionStep.CreateObject>()
        createSteps shouldHaveSize 1
        createSteps[0].type shouldBe "Child"
        val methodSteps = executionTrace.steps.filterIsInstance<ExecutionStep.EnterMethod>()
        methodSteps shouldHaveSize 3 // main, Child("hi"), crash
        methodSteps.map { it.method.className } shouldNotContain "Parent"
        val exitSteps = executionTrace.steps.filterIsInstance<ExecutionStep.ExitMethodExceptionally>()
        exitSteps shouldHaveSize 3
        exitSteps.distinctBy { it.throwableObjectId } shouldHaveSize 1
    }

    "should record post-chain constructor failure" {
        val result = Source.fromSnippet(
            """
class Parent {
    Parent(int x) {}
}
class Child extends Parent {
    Child(String s) {
        super(s.hashCode());
        throw new RuntimeException("boom");
    }
}
Child c = new Child("hi");
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result.threw should beInstanceOf<RuntimeException>()
        val executionTrace = result.pluginResult(ExecutionTrace)
        val createSteps = executionTrace.steps.filterIsInstance<ExecutionStep.CreateObject>()
        createSteps shouldHaveSize 1
        createSteps[0].type shouldBe "Child"
        val methodSteps = executionTrace.steps.filterIsInstance<ExecutionStep.EnterMethod>()
        methodSteps shouldHaveSize 3 // main, Child("hi"), Parent("hi".hashCode());
        methodSteps.last().method.className shouldBe "Parent"
        val finishSteps = executionTrace.steps.filterIsInstance<ExecutionStep.ExitMethodNormally>()
        finishSteps shouldHaveSize 1
        val failSteps = executionTrace.steps.filterIsInstance<ExecutionStep.ExitMethodExceptionally>()
        failSteps shouldHaveSize 2
        failSteps.distinctBy { it.throwableObjectId } shouldHaveSize 1
        executionTrace.steps.indexOf(finishSteps[0]) shouldBeLessThan executionTrace.steps.indexOf(failSteps[0])
    }

    "should record chain constructor failure" {
        val result = Source.fromSnippet(
            """
class Parent {
    Parent() {
        throw new RuntimeException("boom");
    }
}
class Child extends Parent {
    Child() {
        System.out.println("hmm");
    }
}
Child c = new Child();
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result.threw!!.message shouldBe "boom"
        result.output shouldBe ""
        val executionTrace = result.pluginResult(ExecutionTrace)
        val methodSteps = executionTrace.steps.filterIsInstance<ExecutionStep.EnterMethod>()
        methodSteps shouldHaveSize 3 // main, Child(), Parent()
        methodSteps[2].method.className shouldBe "Parent"
        val exitSteps = executionTrace.steps.filterIsInstance<ExecutionStep.ExitMethodExceptionally>()
        exitSteps shouldHaveSize 3
        exitSteps.distinctBy { it.throwableObjectId } shouldHaveSize 1
    }

    "should record chain constructor failure in try-catch" {
        val result = Source.fromSnippet(
            """
class Parent {
    Parent() {
        String s = null;
        s.hashCode();
    }
}
class Child extends Parent {
    Child() {
        System.out.println("hmm");
    }
}
try {
    Child c = new Child();
} catch (NullPointerException e) {
    System.out.println("NPE");
}
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        result.output shouldBe "NPE"
        val executionTrace = result.pluginResult(ExecutionTrace)
        val methodSteps = executionTrace.steps.filterIsInstance<ExecutionStep.EnterMethod>()
        methodSteps shouldHaveSize 3 // main, Child(), Parent()
        methodSteps[2].method.className shouldBe "Parent"
        val finishSteps = executionTrace.steps.filterIsInstance<ExecutionStep.ExitMethodNormally>()
        finishSteps shouldHaveSize 1
        val failSteps = executionTrace.steps.filterIsInstance<ExecutionStep.ExitMethodExceptionally>()
        failSteps shouldHaveSize 2
        failSteps.distinctBy { it.throwableObjectId } shouldHaveSize 1
        executionTrace.steps.indexOf(finishSteps[0]) shouldBeGreaterThan executionTrace.steps.lastIndexOf(failSteps[1])
    }

    "should handle chain constructor failure in uninstrumented code" {
        val result = Source.fromSnippet(
            """
class Parent extends ClassLoader { // should not be possible to create a new classloader
    Parent() {
        System.out.println("hmm");
    }
}
class Child extends Parent {
    Child() {
        System.out.println("yikes");
    }
}
Child c = new Child();
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result.threw should beInstanceOf<SecurityException>()
        result.output shouldBe ""
        val executionTrace = result.pluginResult(ExecutionTrace)
        val methodSteps = executionTrace.steps.filterIsInstance<ExecutionStep.EnterMethod>()
        methodSteps shouldHaveSize 3 // main, Child(), Parent() -- ClassLoader() is not instrumented
        methodSteps[2].method.className shouldBe "Parent"
        val exitSteps = executionTrace.steps.filterIsInstance<ExecutionStep.ExitMethodExceptionally>()
        exitSteps shouldHaveSize 3
        exitSteps.distinctBy { it.throwableObjectId } shouldHaveSize 1
    }

    "should record local variable lifecycle events" {
        val result = Source.fromSnippet(
            """
for (int i = 0; i < 3; i++) {
    int iSquared = i * i;
    int iCubed = iSquared * i;
    System.out.println(iCubed);
}
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        result should haveOutput("0\n1\n8")
        val steps = result.pluginResult(ExecutionTrace).steps
        val scopeChanges = steps.filterIsInstance<ExecutionStep.ChangeScope>()
        scopeChanges[0].newLocals.keys shouldBe setOf("i")
        scopeChanges[0].deadLocals should beEmpty()
        scopeChanges[1].deadLocals should beEmpty()
        scopeChanges[1].newLocals shouldHaveSize 1
        scopeChanges[1].newLocals["iSquared"]!!.value shouldBe 0
        scopeChanges[2].deadLocals should beEmpty()
        scopeChanges[2].newLocals shouldHaveSize 1
        scopeChanges[2].newLocals["iCubed"]!!.value shouldBe 0
        scopeChanges[3].deadLocals.toSet() shouldBe setOf("iSquared", "iCubed")
        scopeChanges[4].newLocals["iSquared"]!!.value shouldBe 1
        scopeChanges shouldHaveSize 11
        scopeChanges[10].deadLocals shouldHaveSingleElement "i"
    }

    "should record local variable lifecycle events caused by exceptions" {
        val result = Source.fromSnippet(
            """
try {
    long a = 10;
    long b = 0;
    long quotient = a / b;
    System.out.println(quotient);
} catch (Exception e) {
    System.out.println("oops");
}
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        result should haveOutput("oops")
        val steps = result.pluginResult(ExecutionTrace).steps
        val scopeChanges = steps.filterIsInstance<ExecutionStep.ChangeScope>()
        scopeChanges[0].newLocals.keys shouldBe setOf("a")
        scopeChanges[0].newLocals["a"]!!.value shouldBe 10L
        scopeChanges[0].deadLocals should beEmpty()
        scopeChanges[1].deadLocals should beEmpty()
        scopeChanges[1].newLocals.keys shouldBe setOf("b")
        scopeChanges[2].deadLocals.toSet() shouldBe setOf("a", "b")
        scopeChanges[2].newLocals shouldHaveSize 1
        scopeChanges[2].newLocals["e"]!!.type shouldBe ExecutionTraceResults.ValueType.REFERENCE
        scopeChanges[3].deadLocals shouldHaveSingleElement "e"
    }

    "should distinguish primitives from their boxes" {
        val result = Source.fromSnippet(
            """
short raw = (short) 5;
Short box = new Short(raw);
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        val steps = result.pluginResult(ExecutionTrace).steps
        val scopeChanges = steps.filterIsInstance<ExecutionStep.ChangeScope>()
        scopeChanges[0].newLocals.keys shouldBe setOf("raw")
        scopeChanges[0].newLocals["raw"] shouldBe
            ExecutionTraceResults.Value(ExecutionTraceResults.ValueType.SHORT, (5).toShort())
        scopeChanges[1].newLocals.keys shouldBe setOf("box")
        scopeChanges[1].newLocals["box"]!!.type shouldBe ExecutionTraceResults.ValueType.REFERENCE
    }

    "should record changes to local variables" {
        val result = Source.fromSnippet(
            """
String text = null;
int number = 254;
number++;
text = Integer.toHexString(number);
System.out.println(text);
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        result should haveOutput("ff")
        val steps = result.pluginResult(ExecutionTrace).steps
        val scopeChanges = steps.filterIsInstance<ExecutionStep.ChangeScope>()
        scopeChanges[0].newLocals.keys shouldBe setOf("text")
        scopeChanges[0].newLocals["text"]!!.value should beNull()
        scopeChanges[0].deadLocals should beEmpty()
        scopeChanges[1].newLocals.keys shouldBe setOf("number")
        scopeChanges[1].newLocals["number"]!!.value shouldBe 254
        scopeChanges[1].deadLocals should beEmpty()
        val varChanges = steps.filterIsInstance<ExecutionStep.SetVariable>()
        varChanges shouldHaveSize 2
        varChanges[0].local shouldBe "number"
        varChanges[0].value.value shouldBe 255
        varChanges[1].local shouldBe "text"
        varChanges[1].value.value shouldNot beNull()
    }

    "should distinguish string equality from reference equality" {
        val result = Source.fromSnippet(
            """
String greeting = "hello";
String newGreeting = new String(greeting);
System.out.println(newGreeting);
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        result should haveOutput("hello")
        val steps = result.pluginResult(ExecutionTrace).steps
        val scopeChanges = steps.filterIsInstance<ExecutionStep.ChangeScope>()
        scopeChanges[0].newLocals.keys shouldBe setOf("greeting")
        scopeChanges[0].newLocals["greeting"]!!.type shouldBe ExecutionTraceResults.ValueType.REFERENCE
        val firstGreeting = scopeChanges[0].newLocals["greeting"]!!.value as Int
        scopeChanges[1].newLocals.keys shouldBe setOf("newGreeting")
        scopeChanges[1].newLocals["newGreeting"]!!.type shouldBe ExecutionTraceResults.ValueType.REFERENCE
        val secondGreeting = scopeChanges[1].newLocals["newGreeting"]!!.value as Int
        firstGreeting shouldNotBe secondGreeting
        val objects = steps.filterIsInstance<ExecutionStep.ObtainObject>()
        objects[0].id shouldBe firstGreeting
        objects[0].obj.stringRepresentation shouldBe "hello"
        objects[1].id shouldBe secondGreeting
        objects[1].obj.stringRepresentation shouldBe "hello"
    }

    "should record initial array values" {
        val result = Source.fromSnippet(
            """
var chars = "hi".toCharArray();
System.out.println(chars);
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        val steps = result.pluginResult(ExecutionTrace).steps
        val objects = steps.filterIsInstance<ExecutionStep.ObtainObject>()
        objects[0].obj.stringRepresentation should beNull()
        objects[0].obj.indexedComponents shouldNot beNull()
        val chars = objects[0].obj.indexedComponents!!
        chars shouldHaveSize 2
        chars[0].type shouldBe ExecutionTraceResults.ValueType.CHAR
        chars[0].value shouldBe 'h'
        chars[1].type shouldBe ExecutionTraceResults.ValueType.CHAR
        chars[1].value shouldBe 'i'
    }

    "should recognize multidimensional arrays" {
        val result = Source.fromSnippet(
            """
var array = new int[3][10];
System.out.println(array);
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        val steps = result.pluginResult(ExecutionTrace).steps
        val objects = steps.filterIsInstance<ExecutionStep.ObtainObject>()
        objects shouldHaveSize 4
        val outer = objects.find { it.obj.indexedComponents!!.size == 3 }!!
        outer.obj.indexedComponents!![0].type shouldBe ExecutionTraceResults.ValueType.REFERENCE
        val inners = objects.filter { it.obj.indexedComponents!!.size == 10 }
        inners shouldHaveSize 3
        inners[0].obj.indexedComponents!![0].type shouldBe ExecutionTraceResults.ValueType.INT
    }

    "should record array changes" {
        val result = Source.fromSnippet(
            """
var numbers = new int[] { 2, 5 };
numbers[1] = 10;
numbers[0] = 3;
System.out.println(numbers);
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        val steps = result.pluginResult(ExecutionTrace).steps
        val objects = steps.filterIsInstance<ExecutionStep.ObtainObject>()
        objects[0].obj.indexedComponents shouldNot beNull()
        val numbers = objects[0].obj.indexedComponents!!
        numbers shouldHaveSize 2
        numbers[0].type shouldBe ExecutionTraceResults.ValueType.INT
        numbers[0].value shouldBe 2
        numbers[1].type shouldBe ExecutionTraceResults.ValueType.INT
        numbers[1].value shouldBe 5
        val changes = steps.filterIsInstance<ExecutionStep.SetIndexedComponent>()
        changes shouldHaveSize 2
        changes[0].objectId shouldBe objects[0].id
        changes[0].component shouldBe 1
        changes[0].value.type shouldBe ExecutionTraceResults.ValueType.INT
        changes[0].value.value shouldBe 10
        changes[1].objectId shouldBe objects[0].id
        changes[1].component shouldBe 0
        changes[1].value.type shouldBe ExecutionTraceResults.ValueType.INT
        changes[1].value.value shouldBe 3
    }

    "should record array changes with large primitives" {
        val result = Source.fromSnippet(
            """
var numbers = new long[2];
numbers[1] = 100000000000L;
System.out.println(numbers);
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        val steps = result.pluginResult(ExecutionTrace).steps
        val changes = steps.filterIsInstance<ExecutionStep.SetIndexedComponent>()
        changes shouldHaveSize 1
        changes[0].component shouldBe 1
        changes[0].value.type shouldBe ExecutionTraceResults.ValueType.LONG
        changes[0].value.value shouldBe 100000000000L
    }

    "should not trace implicit array construction" {
        val result = Source.fromSnippet(
            """
void sink(Object... things) {}
sink("hi", 5, false);
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        val steps = result.pluginResult(ExecutionTrace).steps
        val arrays = steps.filterIsInstance<ExecutionStep.ObtainObject>().filter { it.obj.indexedComponents != null }
        arrays shouldHaveSize 1
        arrays[0].obj.indexedComponents!! shouldHaveSize 3
        val changes = steps.filterIsInstance<ExecutionStep.SetIndexedComponent>()
        changes should beEmpty()
    }

    "should record object array changes" {
        val result = Source.fromSnippet(
            """
var words = new String[2];
words[0] = "hello";
System.out.println(words);
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        val steps = result.pluginResult(ExecutionTrace).steps
        val objects = steps.filterIsInstance<ExecutionStep.ObtainObject>()
        objects[0].obj.indexedComponents shouldNot beNull()
        objects[0].obj.indexedComponents!![0].value should beNull()
        objects[1].obj.stringRepresentation shouldBe "hello"
        val changes = steps.filterIsInstance<ExecutionStep.SetIndexedComponent>()
        changes shouldHaveSize 1
        changes[0].objectId shouldBe objects[0].id
        changes[0].component shouldBe 0
        changes[0].value.type shouldBe ExecutionTraceResults.ValueType.REFERENCE
        changes[0].value.value shouldBe objects[1].id
    }

    "should record boolean and byte array changes" {
        val result = Source.fromSnippet(
            """
var bytes = new byte[3];
bytes[2] = (byte) 20;
var bools = new boolean[2];
bools[1] = true;
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        val steps = result.pluginResult(ExecutionTrace).steps
        val objects = steps.filterIsInstance<ExecutionStep.ObtainObject>()
        objects shouldHaveSize 2
        val changes = steps.filterIsInstance<ExecutionStep.SetIndexedComponent>()
        changes shouldHaveSize 2
        changes[0].objectId shouldBe objects[0].id
        changes[0].component shouldBe 2
        changes[0].value.type shouldBe ExecutionTraceResults.ValueType.BYTE
        changes[0].value.value shouldBe 20.toByte()
        changes[1].objectId shouldBe objects[1].id
        changes[1].component shouldBe 1
        changes[1].value.type shouldBe ExecutionTraceResults.ValueType.BOOLEAN
        changes[1].value.value shouldBe true
    }

    "should not record failed array stores" {
        val result = Source.fromSnippet(
            """
try {
  var longs = new long[1];
  longs[3] = 10L;
} catch (Exception e) {
  System.out.println("oops");
}

try {
  var bytes = new byte[1];
  bytes[-1] = (byte) 4;
} catch (Exception e) {
  System.out.println("oops");
}

try {
  ((boolean[]) null)[1] = true;
} catch (Exception e) {
  System.out.println("oops");
}

try {
  Object[] strings = new String[1];
  strings[0] = 5;
} catch (Exception e) {
  System.out.println("oops");
}
""".trim()
        ).compile(CompilationArguments(debugInfo = true))
            .execute(SourceExecutionArguments().addPlugin(ExecutionTrace))
        result should haveCompleted()
        val steps = result.pluginResult(ExecutionTrace).steps
        val objects = steps.filterIsInstance<ExecutionStep.ObtainObject>().filter { it.obj.indexedComponents != null }
        objects shouldHaveSize 3
        val changes = steps.filterIsInstance<ExecutionStep.SetIndexedComponent>()
        changes shouldHaveSize 0
        val exceptions = steps.filterIsInstance<ExecutionStep.ChangeScope>().filter { "e" in it.newLocals }
        exceptions shouldHaveSize 4
    }
})
