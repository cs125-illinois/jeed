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
""".trim()
        val expectedFuzzedSource = """
int foo = 5;
for (int i = 0; i < 5; i--) {
    foo++;
}
--foo;
""".trim()
        val fuzzConfiguration = FuzzConfiguration()
        fuzzConfiguration.increment = true
        fuzzConfiguration.increment_rand = false
        val fuzzedSource = fuzzBlock(source, fuzzConfiguration)
        println(fuzzedSource)
        fuzzedSource shouldBe expectedFuzzedSource
    }
})

