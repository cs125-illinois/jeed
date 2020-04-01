@file:Suppress("MagicNumber")

package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.matchers.beEmpty
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldThrow
import io.kotlintest.specs.StringSpec

class TestKtLint : StringSpec({
    "it should check simple kotlin sources" {
        val results = Source.fromSnippet(
            """println("Hello, world!")""",
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).ktLint()

        results.errors should beEmpty()
    }
    "!it should check simple kotlin sources with indentation errors" {
        val ktLintFailed = shouldThrow<KtLintFailed> {
            Source.fromSnippet(
                """println("Hello, world!")""".trim(),
                SnippetArguments(fileType = Source.FileType.KOTLIN, indent = 3)
            ).ktLint(KtLintArguments(failOnError = true))
        }

        ktLintFailed.errors shouldHaveSize 3
        ktLintFailed.errors.filterIsInstance<KtLintError>().filter { it.ruleId == "indent" } shouldHaveSize 3
    }
    "it should check kotlin snippets and get the line numbers right" {
        val ktLintFailed = shouldThrow<KtLintFailed> {
            Source.fromSnippet(
                """ println("Hello, world!")""",
                SnippetArguments(fileType = Source.FileType.KOTLIN)
            ).ktLint(KtLintArguments(failOnError = true))
        }

        ktLintFailed.errors shouldHaveSize 1
        ktLintFailed.errors.filterIsInstance<KtLintError>().filter { it.ruleId == "indent" } shouldHaveSize 1
        ktLintFailed.errors.first().location.line shouldBe 1
    }
})
