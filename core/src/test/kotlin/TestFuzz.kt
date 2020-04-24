package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.shouldBe
import io.kotlintest.specs.StringSpec

class TestFuzz : StringSpec({
    // Conditional Boundary

    "conditional_boundary (all)" {
        val source = """
boolean a = 1 > 0; 
boolean b = 5 < 5; 
boolean c = 3 <= 2; 
boolean d = 4 >= 4;
""".trim()
        val expectedFuzzedSource = """
boolean a = 1 >= 0; 
boolean b = 5 <= 5; 
boolean c = 3 < 2; 
boolean d = 4 > 4; 
""".trim()
        val fuzzConfiguration = FuzzConfiguration()
        fuzzConfiguration.addTransformation(TransformationType.CONDITIONALS_BOUNDARY, rand = false)
        val fuzzedSource = fuzzBlock(source, fuzzConfiguration)
        println(fuzzedSource)
        fuzzedSource shouldBe expectedFuzzedSource
    }

    // Increment

    "increment (all)" {
        val source = """
int foo = 5;
for (int i = 0; i < 5; i++) {
    foo--;
}
++foo;
double bar = 7 + --foo;
""".trim()
        val expectedFuzzedSource = """
int foo = 5;
for (int i = 0; i < 5; i--) {
    foo++;
}
--foo;
double bar = 7 + ++foo;
""".trim()
        val fuzzConfiguration = FuzzConfiguration()
        fuzzConfiguration.addTransformation(TransformationType.INCREMENT, rand = false)
        val fuzzedSource = fuzzBlock(source, fuzzConfiguration)
        println(fuzzedSource)
        fuzzedSource shouldBe expectedFuzzedSource
    }

    // Invert Negatives

    "invert negatives (all)" {
        val source = """
int i = -(4 + 5);
int j = -i + 1;
int k = +j;
""".trim()
        val expectedFuzzedSource = """
int i = (4 + 5);
int j = i + 1;
int k = -j;
""".trim()
        val fuzzConfiguration = FuzzConfiguration()
        fuzzConfiguration.addTransformation(TransformationType.INVERT_NEGS, rand = false)
        val fuzzedSource = fuzzBlock(source, fuzzConfiguration)
        println(fuzzedSource)
        fuzzedSource shouldBe expectedFuzzedSource
    }

    // Math

    "math (all)" {
        val source = """
int i = -(4 - 5);
int j = -i + 1; // This should not be changed
int k = +j * i;
int l = j / 2;
int m = 3 % j;
int n = l & i;
int o = 7 | n;
int p = j ^ n;
int q = j << 2;
int r = 8 >> 2;
int s = 16 >>> 2;
""".trim()
        val expectedFuzzedSource = """
int i = -(4 + 5);
int j = -i + 1; // This should not be changed
int k = +j / i;
int l = j * 2;
int m = 3 * j;
int n = l | i;
int o = 7 & n;
int p = j & n;
int q = j >> 2;
int r = 8 << 2;
int s = 16 << 2;
""".trim()
        val fuzzConfiguration = FuzzConfiguration()
        fuzzConfiguration.addTransformation(TransformationType.MATH, rand = false)
        val fuzzedSource = fuzzBlock(source, fuzzConfiguration)
        println(fuzzedSource)
        fuzzedSource shouldBe expectedFuzzedSource
    }

    // Conditionals Negate

    "conditionals negate (all)" {
        val source = """
bool foo = 5 == 5;
for (int i = 0; i < 5; i++) {
    foo = 1 != i;
}
if (5 > 1) {
    foo = 3 < 2;
    foo = 2 >= 2;
}
double bar = foo && 4 <= 2;
""".trim()
        val expectedFuzzedSource = """
bool foo = 5 != 5;
for (int i = 0; i >= 5; i++) {
    foo = 1 == i;
}
if (5 <= 1) {
    foo = 3 >= 2;
    foo = 2 < 2;
}
double bar = foo && 4 > 2;
""".trim()
        val fuzzConfiguration = FuzzConfiguration()
        fuzzConfiguration.addTransformation(TransformationType.CONDITIONALS_NEG, rand = false)
        val fuzzedSource = fuzzBlock(source, fuzzConfiguration)
        println(fuzzedSource)
        fuzzedSource shouldBe expectedFuzzedSource
    }

    // Void Method Calls

    "void method calls (all)" {
        val source = """
Integer i = 123;
Double d = new Double(i);
System.out.println((new Boolean(i == 5)).toString());
foo();
String uniqueString = i.toString();
System.out.println(uniqueString);
uniqueString.toString();
uniqueString;
""".trim()
        val expectedFuzzedSource = """
Integer i = 123;
Double d = new Double(i);
System.out.println((new Boolean(i == 5)).toString());

String uniqueString = i.toString();


uniqueString;
""".trim()
        val fuzzConfiguration = FuzzConfiguration()
        fuzzConfiguration.addTransformation(TransformationType.VOID_METHOD_CALLS, rand = false)
        val fuzzedSource = fuzzBlock(source, fuzzConfiguration)
        println(fuzzedSource)
        fuzzedSource shouldBe expectedFuzzedSource
    }

    // Constructor Calls

    "constructor calls (all)" {
        val source = """
Integer i = 123;
Double d = new Double(i);
System.out.println((new Boolean(i == 5)).toString());
foo();
String uniqueString = i.toString();
System.out.println(uniqueString);
uniqueString.toString();
uniqueString;
""".trim()
        val expectedFuzzedSource = """
Integer i = 123;
Double d = null;
System.out.println((null).toString());
foo();
String uniqueString = i.toString();
System.out.println(uniqueString);
uniqueString.toString();
uniqueString;
""".trim()
        val fuzzConfiguration = FuzzConfiguration()
        fuzzConfiguration.addTransformation(TransformationType.CONSTRUCTOR_CALLS, rand = false)
        val fuzzedSource = fuzzBlock(source, fuzzConfiguration)
        println(fuzzedSource)
        fuzzedSource shouldBe expectedFuzzedSource
    }

    // Inline Constants

    "inline constants (all)" {
        val source = """
int i = 0;
int j = 1.0;
int k = 0;
int l = 0.0;
int m = 5.0;
int n = 4;
int o = 2.0;
int p = -2;
int q = 5;
int r = -5;
boolean s = true;
boolean t = false;
""".trim()
        val expectedFuzzedSource = """
int i = 1;
int j = 0.0;
int k = 1;
int l = 1.0;
int m = 1.0;
int n = 5;
int o = 1.0;
int p = -3;
int q = 1;
int r = -1;
boolean s = false;
boolean t = true;
""".trim()
        val fuzzConfiguration = FuzzConfiguration()
        fuzzConfiguration.addTransformation(TransformationType.INLINE_CONSTANT, rand = false)
        val fuzzedSource = fuzzBlock(source, fuzzConfiguration)
        println(fuzzedSource)
        fuzzedSource shouldBe expectedFuzzedSource
    }

    // Remove Increments

    "remove increments (all)" {
        val source = """
int i = 0;
int j = i++;
int k = j-- + i;
--k;
++j;
""".trim()
        val expectedFuzzedSource = """
int i = 0;
int j = i;
int k = j + i;
k;
j;
""".trim()
        val fuzzConfiguration = FuzzConfiguration()
        fuzzConfiguration.addTransformation(TransformationType.REMOVE_INCREMENTS, rand = false)
        val fuzzedSource = fuzzBlock(source, fuzzConfiguration)
        println(fuzzedSource)
        fuzzedSource shouldBe expectedFuzzedSource
    }

/* TODO: This mutator can currently not be done because of whitespace sensitivity issues

    // Remove Conditionals

    "remove conditionals (all)" {
        val source = """
if (1 > 0) {
    System.out.println("Hello world!");
}
else if (false) {
    System.out.println("Bye world!");
}
else {
    System.out.println("FizzBuzz");
}
""".trim()
        val expectedFuzzedSource = """
if (true) {
    System.out.println("Hello world!");
}
else if (true) {
    System.out.println("Bye world!");
}
else {
    System.out.println("FizzBuzz");
}
""".trim()
        val fuzzConfiguration = FuzzConfiguration()
        fuzzConfiguration.addTransformation(TransformationType.REMOVE_CONDITIONALS, rand = false)
        val fuzzedSource = fuzzBlock(source, fuzzConfiguration)
        println(fuzzedSource)
        fuzzedSource shouldBe expectedFuzzedSource
    } */
})



