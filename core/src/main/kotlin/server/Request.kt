package edu.illinois.cs.cs125.jeed.core.server

import com.squareup.moshi.JsonClass
import edu.illinois.cs.cs125.jeed.core.CheckstyleArguments
import edu.illinois.cs.cs125.jeed.core.CompilationArguments
import edu.illinois.cs.cs125.jeed.core.ContainerExecutionArguments
import edu.illinois.cs.cs125.jeed.core.KompilationArguments
import edu.illinois.cs.cs125.jeed.core.KtLintArguments
import edu.illinois.cs.cs125.jeed.core.MutationsArguments
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
    cexecute,
    features,
    mutations,
    disassemble
}

@JsonClass(generateAdapter = true)
@Suppress("LongParameterList")
class TaskArguments(
    val snippet: SnippetArguments = SnippetArguments(),
    val compilation: CompilationArguments = CompilationArguments(),
    val kompilation: KompilationArguments = KompilationArguments(),
    val checkstyle: CheckstyleArguments = CheckstyleArguments(),
    val ktlint: KtLintArguments = KtLintArguments(),
    // val complexity: currently accepts no arguments
    val execution: SourceExecutionArguments = SourceExecutionArguments(),
    val cexecution: ContainerExecutionArguments = ContainerExecutionArguments(),
    // val features: currently accepts no arguments
    val mutations: MutationsArguments = MutationsArguments(),
    // val disassemble: currently accepts no arguments,
    val plugins: PluginArguments = PluginArguments()
)

@JsonClass(generateAdapter = true)
data class PluginArguments(
    val lineCountLimit: Long = DEFAULT_LINE_COUNT_LIMIT,
    val memoryTotalLimit: Long = DEFAULT_MEMORY_TOTAL_LIMIT,
    val memoryAllocationLimit: Long = DEFAULT_MEMORY_ALLOCATION_LIMIT
) {
    companion object {
        const val DEFAULT_LINE_COUNT_LIMIT = 4 * 1024 * 1024L
        const val DEFAULT_MEMORY_TOTAL_LIMIT = 32 * 1024 * 1024L
        const val DEFAULT_MEMORY_ALLOCATION_LIMIT = 512 * 1024L
    }
}
