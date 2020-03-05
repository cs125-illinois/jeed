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
        fuzzConfiguration.conditionals_boundary = true
        fuzzConfiguration.conditionals_boundary_rand = false
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
        fuzzConfiguration.increment = true
        fuzzConfiguration.increment_rand = false
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
        fuzzConfiguration.invert_negs = true
        fuzzConfiguration.invert_negs_rand = false
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
        fuzzConfiguration.math = true
        fuzzConfiguration.math_rand = false
        val fuzzedSource = fuzzBlock(source, fuzzConfiguration)
        println(fuzzedSource)
        fuzzedSource shouldBe expectedFuzzedSource
    }

})

