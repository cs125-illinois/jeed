package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

@Suppress("LargeClass")
class TestKotlinFeatures : StringSpec({
    "should count variable declarations in snippets" {
        Source.fromKotlinSnippet(
            """
var i = 0
var j: Int? = null
i = 1
i += 1
i++
--j
""".trim()
        ).features().check {
            featureMap[FeatureName.LOCAL_VARIABLE_DECLARATIONS] shouldBe 2
            featureMap[FeatureName.VARIABLE_ASSIGNMENTS] shouldBe 2
            featureMap[FeatureName.VARIABLE_REASSIGNMENTS] shouldBe 4
        }
    }
    "should count for loops in snippets" {
        Source.fromKotlinSnippet(
            """
for (i in 0 until 10) {
    println(i)
}
val first = arrayOf(1, 2, 4)
val second = intArrayOf(2, 4, 8)
val third = Array<Int>(8) { 0 }
val test = "arrayOf"
for (value in first) {
  println(value)
}
""".trim()
        ).features().check {
            featureMap[FeatureName.FOR_LOOPS] shouldBe 2
            featureMap[FeatureName.VARIABLE_ASSIGNMENTS] shouldBe 4
            featureMap[FeatureName.ARRAYS] shouldBe 3
        }
    }
    "should count nested for loops in snippets" {
        Source.fromKotlinSnippet(
            """
for (i in 0 until 10) {
    for (i in 0 until 10) {
        println(i + j)
    }
}
""".trim()
        ).features().check {
            featureMap[FeatureName.FOR_LOOPS] shouldBe 2
            featureMap[FeatureName.NESTED_FOR] shouldBe 1
        }
    }
    "should count while loops in snippets" {
        Source.fromKotlinSnippet(
            """
var i = 0
while (i < 32) {
  while (i < 16) {
    i++
  }
  i++
}
""".trim()
        ).features().check {
            featureMap[FeatureName.WHILE_LOOPS] shouldBe 2
            featureMap[FeatureName.NESTED_WHILE] shouldBe 1
            featureMap[FeatureName.DO_WHILE_LOOPS] shouldBe 0
            featureMap[FeatureName.VARIABLE_REASSIGNMENTS] shouldBe 2
        }
    }
    "should count do-while loops in snippets" {
        Source.fromKotlinSnippet(
            """
var i = 0
do {
    println(i)
    i++
    var j = 0
    do {
        j++
    } while (j < 10)
} while (i < 10)
""".trim()
        ).features().check {
            featureMap[FeatureName.DO_WHILE_LOOPS] shouldBe 2
            featureMap[FeatureName.NESTED_DO_WHILE] shouldBe 1
            featureMap[FeatureName.WHILE_LOOPS] shouldBe 0
            featureMap[FeatureName.LOCAL_VARIABLE_DECLARATIONS] shouldBe 2
            featureMap[FeatureName.VARIABLE_REASSIGNMENTS] shouldBe 2
        }
    }
    "should count simple if-else statements in snippets" {
        Source.fromKotlinSnippet(
            """
var i = 0
if (i < 5) {
    i++
} else {
    i--
}
""".trim()
        ).features().check {
            featureMap[FeatureName.IF_STATEMENTS] shouldBe 1
            featureMap[FeatureName.ELSE_STATEMENTS] shouldBe 1
        }
    }
    "should count a chain of if-else statements in snippets" {
        Source.fromKotlinSnippet(
            """
var i = 0
if (i < 5) {
    i++
} else if (i < 10) {
    i--
} else if (i < 15) {
    i++
} else {
    i--
}
""".trim()
        ).features().check {
            featureMap[FeatureName.IF_STATEMENTS] shouldBe 1
            featureMap[FeatureName.ELSE_STATEMENTS] shouldBe 1
            featureMap[FeatureName.ELSE_IF] shouldBe 2
        }
    }
    "should count nested if statements in snippets" {
        Source.fromKotlinSnippet(
            """
var i = 0
if (i < 15) {
    if (i < 10) {
        i--
        if (i < 5) {
            i++
        }
    } else {
        if (i > 10) {
            i--
        }
    }
}
""".trim()
        ).features().check {
            featureMap[FeatureName.IF_STATEMENTS] shouldBe 4
            featureMap[FeatureName.NESTED_IF] shouldBe 3
        }
    }
})

fun FeaturesResults.check(path: String = ".", block: Features.() -> Any) = with(lookup(path).features, block)
