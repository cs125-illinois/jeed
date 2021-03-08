@file:Suppress("MagicNumber")

package edu.illinois.cs.cs125.jeed.core

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class TestKtLint : StringSpec({
    "it should check simple kotlin sources" {
        val results = Source.fromSnippet(
            """println("Hello, world!")""",
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).ktLint()

        results.errors.isEmpty() shouldBe true
    }
    "it should check kotlin sources with too long lines" {
        @Suppress("MaxLineLength")
        val ktLintFailed = shouldThrow<KtLintFailed> {
            Source.fromSnippet(
                """val test = "ttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttttt"""",
                SnippetArguments(fileType = Source.FileType.KOTLIN)
            ).ktLint(KtLintArguments(failOnError = true))
        }

        ktLintFailed.errors.filterIsInstance<KtLintError>().filter { it.ruleId == "max-line-length" } shouldHaveSize 1
    }
    "!it should fail when everything is on one line" {
        shouldThrow<KtLintFailed> {
            Source.fromSnippet(
                """class Course(var number: String) { fun changeNumber(newNumber: String) { number = newNumber } }""",
                SnippetArguments(fileType = Source.FileType.KOTLIN)
            ).ktLint(KtLintArguments(failOnError = true))
        }
    }
    "it should check simple kotlin sources with indentation errors" {
        val ktLintFailed = shouldThrow<KtLintFailed> {
            Source.fromSnippet(
                """println("Hello, world!")""".trim(),
                SnippetArguments(fileType = Source.FileType.KOTLIN, indent = 3)
            ).ktLint(KtLintArguments(failOnError = true))
        }

        ktLintFailed.errors shouldHaveSize 1
        ktLintFailed.errors.filterIsInstance<KtLintError>().filter { it.ruleId == "indent" } shouldHaveSize 1
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
