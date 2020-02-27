package edu.illinois.cs.cs125.jeed.core.server

import com.squareup.moshi.JsonClass
import edu.illinois.cs.cs125.jeed.core.CheckstyleArguments
import edu.illinois.cs.cs125.jeed.core.CompilationArguments
import edu.illinois.cs.cs125.jeed.core.KompilationArguments
import edu.illinois.cs.cs125.jeed.core.KtLintArguments
import edu.illinois.cs.cs125.jeed.core.SnippetArguments
import edu.illinois.cs.cs125.jeed.core.SourceExecutionArguments

@Suppress("EnumEntryName", "EnumNaming")
enum class Task {
    template,
    snippet,
    compile,
    kompile,
    checkstyle,
    ktlint,
    complexity,
    execute,
}

@JsonClass(generateAdapter = true)
class TaskArguments(
    val snippet: SnippetArguments = SnippetArguments(),
    val compilation: CompilationArguments = CompilationArguments(),
    val kompilation: KompilationArguments = KompilationArguments(),
    val checkstyle: CheckstyleArguments = CheckstyleArguments(),
    val ktlint: KtLintArguments = KtLintArguments(),
    // val complexity: currently accepts no arguments
    val execution: SourceExecutionArguments = SourceExecutionArguments()
)
