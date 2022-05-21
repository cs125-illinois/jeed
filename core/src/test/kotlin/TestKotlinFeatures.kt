package edu.illinois.cs.cs125.jeed.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

@Suppress("LargeClass")
class TestKotlinFeatures : StringSpec({
    "should count variable declarations in snippets" {
        Source.fromSnippet(
            """
var i = 0
""".trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).features().also {
            it.lookup(".").features.featureMap[FeatureName.LOCAL_VARIABLE_DECLARATIONS] shouldBe 1
            it.lookup(".").features.featureMap[FeatureName.VARIABLE_ASSIGNMENTS] shouldBe 1
        }
    }
})
