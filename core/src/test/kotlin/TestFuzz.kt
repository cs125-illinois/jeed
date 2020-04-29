package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.shouldBe
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.StringSpec

class TestFuzz : StringSpec({
    // -- Single Mutation Testing --

    // Conditional Boundary

    "conditional_boundary (all)" {
        val source = """
boolean a = 1 > 0; 
boolean b = 5 < 5; 
boolean c = 3 <= 2; 
boolean d = 4 >= 4;
boolean e = foo("egesrg <") > foo(">    " + "");
""".trim()
        val expectedFuzzedSource = """
boolean a = 1 >= 0; 
boolean b = 5 <= 5; 
boolean c = 3 < 2; 
boolean d = 4 > 4;
boolean e = foo("egesrg <") >= foo(">    " + "");
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

    // -- Mutation Precedence Testing --

    // Increment + Remove Increments (increments has precedence)

    "increment (all) + remove_increments (all)" {
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
        fuzzConfiguration.addTransformation(TransformationType.REMOVE_INCREMENTS, rand = false)
        val fuzzedSource = fuzzBlock(source, fuzzConfiguration)
        println(fuzzedSource)
        fuzzedSource shouldBe expectedFuzzedSource
    }

    // -- Packaged Testing --

    // Stable Mutations

    "stable mutations (all)" {
        // NOTE: Source code example being fuzzed was originally written by Chaitanya Singh at https://beginnersbook.com/2017/09/java-program-to-reverse-words-in-a-string/
        val source = """
public class Example
{
   public void reverseWordInMyString(String str)
   {
	/* The split() method of String class splits
	 * a string in several strings based on the
	 * delimiter passed as an argument to it
	 */
	String[] words = str.split(" ");
	String reversedString = "";
	for (int i = 0; i < words.length; i++)
        {
           String word = words[i]; 
           String reverseWord = "";
           for (int j = word.length()-1; j >= 0; j--) 
	   {
		/* The charAt() function returns the character
		 * at the given position in a string
		 */
		reverseWord = reverseWord + word.charAt(j);
	   }
	   reversedString = reversedString + reverseWord + " ";
	}
	System.out.println(str);
	System.out.println(reversedString);
   }
   public static void main(String[] args) 
   {
	Example obj = new Example();
	obj.reverseWordInMyString("Welcome to BeginnersBook");
	obj.reverseWordInMyString("This is an easy Java Program");
   }
}
""".trim()
        val fuzzConfiguration = FuzzConfiguration()

        fuzzConfiguration.addTransformation(TransformationType.INCREMENT, rand = false) // We configure rand to false because we need to check that these mutations are, in fact, stable in all possible mutations in the example
        fuzzConfiguration.addTransformation(TransformationType.REMOVE_INCREMENTS, rand = false)
        fuzzConfiguration.addTransformation(TransformationType.MATH, rand = false)
        fuzzConfiguration.addTransformation(TransformationType.VOID_METHOD_CALLS, rand = false)
        fuzzConfiguration.addTransformation(TransformationType.INLINE_CONSTANT, rand = false)
        fuzzConfiguration.addTransformation(TransformationType.CONSTRUCTOR_CALLS, rand = false)
        fuzzConfiguration.addTransformation(TransformationType.INVERT_NEGS, rand = false)


        val fuzzedSource = fuzzCompilationUnit(source, fuzzConfiguration, compileCheck = true)
        println(fuzzedSource)
        fuzzedSource shouldNotBe source
    }

    // All Mutations (including unstable)

    "all mutations (rand)" {
        // NOTE: Source code example being fuzzed was originally written by Chaitanya Singh at https://beginnersbook.com/2017/09/java-program-to-reverse-words-in-a-string/
        val source = """
public class Example
{
   public void reverseWordInMyString(String str)
   {
	/* The split() method of String class splits
	 * a string in several strings based on the
	 * delimiter passed as an argument to it
	 */
	String[] words = str.split(" ");
	String reversedString = "";
	for (int i = 0; i < words.length; i++)
        {
           String word = words[i]; 
           String reverseWord = "";
           for (int j = word.length()-1; j >= 0; j--) 
	   {
		/* The charAt() function returns the character
		 * at the given position in a string
		 */
		reverseWord = reverseWord + word.charAt(j);
	   }
	   reversedString = reversedString + reverseWord + " ";
	}
	System.out.println(str);
	System.out.println(reversedString);
   }
   public static void main(String[] args) 
   {
	Example obj = new Example();
	obj.reverseWordInMyString("Welcome to BeginnersBook");
	obj.reverseWordInMyString("This is an easy Java Program");
   }
}
""".trim()
        val fuzzConfiguration = FuzzConfiguration()
        fuzzConfiguration.addTransformation(TransformationType.CONDITIONALS_BOUNDARY, rand = true)
        fuzzConfiguration.addTransformation(TransformationType.INCREMENT, rand = true)
        fuzzConfiguration.addTransformation(TransformationType.REMOVE_INCREMENTS, rand = true)
        fuzzConfiguration.addTransformation(TransformationType.INVERT_NEGS, rand = true)
        fuzzConfiguration.addTransformation(TransformationType.MATH, rand = true)
        fuzzConfiguration.addTransformation(TransformationType.CONDITIONALS_NEG, rand = true)
        fuzzConfiguration.addTransformation(TransformationType.VOID_METHOD_CALLS, rand = true)
        fuzzConfiguration.addTransformation(TransformationType.INLINE_CONSTANT, rand = true)
        fuzzConfiguration.addTransformation(TransformationType.CONSTRUCTOR_CALLS, rand = true)
        val fuzzedSource = fuzzCompilationUnit(source, fuzzConfiguration, compileCheck = false) // Some of these mutations are unstable and will cause compile-time errors, so we do not compile check
        println(fuzzedSource)
        fuzzedSource shouldNotBe source
    }
})



