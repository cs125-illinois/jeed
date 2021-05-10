package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TestFeatures : StringSpec({
    "should count variable declarations in snippets" {
        Source.fromSnippet(
            """
int i = 0;
int j;
i = 4;
""".trim()
        ).features().also {
            it.lookup("").features.localVariableDeclarations shouldBe 2
            it.lookup("").features.variableAssignments shouldBe 2
            it.lookup("").features.variableReassignments shouldBe 1
        }
    }
})
