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
""".trim()
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.FOR_LOOPS] shouldBe 1
            it.lookup(".").features.featureMap[FeatureName.VARIABLE_ASSIGNMENTS] shouldBe 1
            it.lookup(".").features.featureMap[FeatureName.ARRAYS] shouldBe 1
        }
    }
})
