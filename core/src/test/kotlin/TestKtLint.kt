package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.matchers.beEmpty
import io.kotlintest.matchers.collections.shouldHaveSize
import io.kotlintest.should
import io.kotlintest.specs.StringSpec

class TestKtLint : StringSpec({
    "it should check simple kotlin sources" {
        val results = Source.fromSnippet(
            """println("Hello, world!")""",
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).ktLint()

        results.errors should beEmpty()
    }
    "it should check simple kotlin sources with errors" {
        val results = Source.fromSnippet(
            """
import kotlinx.coroutines

println("Hello, world!")
""".trim(),
            SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).ktLint()

        results.errors shouldHaveSize 1
        results.errors.filter { it.ruleId == "no-unused-imports" } shouldHaveSize 1
    }
})