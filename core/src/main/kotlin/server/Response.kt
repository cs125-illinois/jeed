package edu.illinois.cs.cs125.jeed.core.server

import com.squareup.moshi.JsonClass
import edu.illinois.cs.cs125.jeed.core.CheckstyleFailed
import edu.illinois.cs.cs125.jeed.core.CheckstyleResults
import edu.illinois.cs.cs125.jeed.core.ClassComplexity
import edu.illinois.cs.cs125.jeed.core.CompilationFailed
import edu.illinois.cs.cs125.jeed.core.ComplexityFailed
import edu.illinois.cs.cs125.jeed.core.ComplexityResults
import edu.illinois.cs.cs125.jeed.core.ContainerExecutionResults
import edu.illinois.cs.cs125.jeed.core.KtLintFailed
import edu.illinois.cs.cs125.jeed.core.KtLintResults
import edu.illinois.cs.cs125.jeed.core.MethodComplexity
import edu.illinois.cs.cs125.jeed.core.Snippet
import edu.illinois.cs.cs125.jeed.core.SnippetTransformationFailed
import edu.illinois.cs.cs125.jeed.core.SourceRange
import edu.illinois.cs.cs125.jeed.core.TemplatingFailed
import edu.illinois.cs.cs125.jeed.core.moshi.CompiledSourceResult
import edu.illinois.cs.cs125.jeed.core.moshi.ExecutionFailedResult
import edu.illinois.cs.cs125.jeed.core.moshi.SourceTaskResults
import edu.illinois.cs.cs125.jeed.core.moshi.TemplatedSourceResult

@JsonClass(generateAdapter = true)
class CompletedTasks(
    var template: TemplatedSourceResult? = null,
    var snippet: Snippet? = null,
    var compilation: CompiledSourceResult? = null,
    var kompilation: CompiledSourceResult? = null,
    var checkstyle: CheckstyleResults? = null,
    var ktlint: KtLintResults? = null,
    var complexity: FlatComplexityResults? = null,
    var execution: SourceTaskResults? = null,
    var cexecution: ContainerExecutionResults? = null
)

@JsonClass(generateAdapter = true)
class FailedTasks(
    var template: TemplatingFailed? = null,
    var snippet: SnippetTransformationFailed? = null,
    var compilation: CompilationFailed? = null,
    var kompilation: CompilationFailed? = null,
    var checkstyle: CheckstyleFailed? = null,
    var ktlint: KtLintFailed? = null,
    var complexity: ComplexityFailed? = null,
    var execution: ExecutionFailedResult? = null,
    var cexecution: ExecutionFailedResult? = null
)

@JsonClass(generateAdapter = true)
data class FlatSource(val path: String, val contents: String)

fun List<FlatSource>.toSource(): Map<String, String> {
    require(this.map { it.path }.distinct().size == this.size) { "duplicate paths in source list" }
    return this.map { it.path to it.contents }.toMap()
}

fun Map<String, String>.toFlatSources(): List<FlatSource> {
    return this.map { FlatSource(it.key, it.value) }
}

@JsonClass(generateAdapter = true)
data class FlatClassComplexity(val name: String, val path: String, val range: SourceRange, val complexity: Int) {
    constructor(classComplexity: ClassComplexity, prefix: String) : this(
        classComplexity.name,
        "$prefix.${classComplexity.name}",
        classComplexity.range,
        classComplexity.complexity
    )
}

@JsonClass(generateAdapter = true)
data class FlatMethodComplexity(val name: String, val path: String, val range: SourceRange, val complexity: Int) {
    constructor(methodComplexity: MethodComplexity, prefix: String) : this(
        methodComplexity.name,
        "$prefix.${methodComplexity.name}",
        methodComplexity.range,
        methodComplexity.complexity
    )
}

@JsonClass(generateAdapter = true)
data class FlatComplexityResult(
    val source: String,
    val classes: List<FlatClassComplexity>,
    val methods: List<FlatMethodComplexity>
) {
    companion object {
        fun from(source: String, complexityResults: Map<String, ClassComplexity>): FlatComplexityResult {
            val classes: MutableList<FlatClassComplexity> = mutableListOf()
            val methods: MutableList<FlatMethodComplexity> = mutableListOf()
            complexityResults.forEach { (_, classComplexity) ->
                addFromClass(classComplexity, "", classes, methods)
            }
            return FlatComplexityResult(source, classes, methods)
        }

        private fun addFromMethod(
            methodComplexity: MethodComplexity,
            prefix: String,
            classes: MutableList<FlatClassComplexity>,
            methods: MutableList<FlatMethodComplexity>
        ) {
            methods.add(FlatMethodComplexity(methodComplexity, prefix))
            val nextPrefix = if (prefix.isBlank()) {
                methodComplexity.name
            } else {
                "$prefix.${methodComplexity.name}"
            }
            methodComplexity.classes.forEach {
                addFromClass(it.value as ClassComplexity, nextPrefix, classes, methods)
            }
        }

        private fun addFromClass(
            classComplexity: ClassComplexity,
            prefix: String,
            classes: MutableList<FlatClassComplexity>,
            methods: MutableList<FlatMethodComplexity>
        ) {
            classes.add(FlatClassComplexity(classComplexity, prefix))
            val nextPrefix = if (prefix.isBlank()) {
                classComplexity.name
            } else {
                "$prefix.${classComplexity.name}"
            }
            classComplexity.classes.values.forEach {
                addFromClass(it as ClassComplexity, nextPrefix, classes, methods)
            }
            classComplexity.methods.values.forEach {
                addFromMethod(it as MethodComplexity, nextPrefix, classes, methods)
            }
        }
    }
}

@JsonClass(generateAdapter = true)
data class FlatComplexityResults(val results: List<FlatComplexityResult>) {
    constructor(complexityResults: ComplexityResults) : this(complexityResults.results.map { (source, results) ->
        FlatComplexityResult.from(source, results)
    })
}
