package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

@Suppress("LargeClass")
class TestKotlinFeatures : StringSpec({
    "should count variable declarations in snippets" {
        Source.fromSnippet(
            """
var i = 0
var j: Int? = null
i = 1
i += 1
i++
--j
""".trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.LOCAL_VARIABLE_DECLARATIONS] shouldBe 2
            it.lookup(".").features.featureMap[FeatureName.VARIABLE_ASSIGNMENTS] shouldBe 2
            it.lookup(".").features.featureMap[FeatureName.VARIABLE_REASSIGNMENTS] shouldBe 4
        }
    }
})
