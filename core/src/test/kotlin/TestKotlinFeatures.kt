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
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.LOCAL_VARIABLE_DECLARATIONS] shouldBe 2
            it.lookup(".").features.featureMap[FeatureName.VARIABLE_ASSIGNMENTS] shouldBe 2
            it.lookup(".").features.featureMap[FeatureName.VARIABLE_REASSIGNMENTS] shouldBe 4
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
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.FOR_LOOPS] shouldBe 2
            it.lookup(".").features.featureMap[FeatureName.VARIABLE_ASSIGNMENTS] shouldBe 4
            it.lookup(".").features.featureMap[FeatureName.ARRAYS] shouldBe 3
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
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.FOR_LOOPS] shouldBe 2
            it.lookup(".").features.featureMap[FeatureName.NESTED_FOR] shouldBe 1
        }
    }
})
