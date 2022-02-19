package edu.illinois.cs.cs125.jeed.core.server

import com.squareup.moshi.JsonClass
import edu.illinois.cs.cs125.jeed.core.ALL_FEATURES
import edu.illinois.cs.cs125.jeed.core.CheckstyleFailed
import edu.illinois.cs.cs125.jeed.core.CheckstyleResults
import edu.illinois.cs.cs125.jeed.core.ClassComplexity
import edu.illinois.cs.cs125.jeed.core.ClassFeatures
import edu.illinois.cs.cs125.jeed.core.CompilationFailed
import edu.illinois.cs.cs125.jeed.core.ComplexityFailed
import edu.illinois.cs.cs125.jeed.core.ComplexityResults
import edu.illinois.cs.cs125.jeed.core.ComplexityValue
import edu.illinois.cs.cs125.jeed.core.ContainerExecutionResults
import edu.illinois.cs.cs125.jeed.core.DisassembleFailedResult
import edu.illinois.cs.cs125.jeed.core.DisassembleResults
import edu.illinois.cs.cs125.jeed.core.FeatureValue
import edu.illinois.cs.cs125.jeed.core.Features
import edu.illinois.cs.cs125.jeed.core.FeaturesFailed
import edu.illinois.cs.cs125.jeed.core.FeaturesResults
import edu.illinois.cs.cs125.jeed.core.KtLintFailed
import edu.illinois.cs.cs125.jeed.core.KtLintResults
import edu.illinois.cs.cs125.jeed.core.MethodComplexity
import edu.illinois.cs.cs125.jeed.core.MethodFeatures
import edu.illinois.cs.cs125.jeed.core.MutationsFailed
import edu.illinois.cs.cs125.jeed.core.MutationsResults
import edu.illinois.cs.cs125.jeed.core.Snippet
import edu.illinois.cs.cs125.jeed.core.SnippetTransformationFailed
import edu.illinois.cs.cs125.jeed.core.SourceRange
import edu.illinois.cs.cs125.jeed.core.TemplatingFailed
import edu.illinois.cs.cs125.jeed.core.moshi.CompiledSourceResult
import edu.illinois.cs.cs125.jeed.core.moshi.ExecutionFailedResult
import edu.illinois.cs.cs125.jeed.core.moshi.SourceTaskResults
import edu.illinois.cs.cs125.jeed.core.moshi.TemplatedSourceResult

@JsonClass(generateAdapter = true)
@Suppress("LongParameterList")
class CompletedTasks(
    var template: TemplatedSourceResult? = null,
    var snippet: Snippet? = null,
    var compilation: CompiledSourceResult? = null,
    var kompilation: CompiledSourceResult? = null,
    var checkstyle: CheckstyleResults? = null,
    var ktlint: KtLintResults? = null,
    var complexity: FlatComplexityResults? = null,
    var execution: SourceTaskResults? = null,
    var cexecution: ContainerExecutionResults? = null,
    var features: FlatFeaturesResults? = null,
    var mutations: MutationsResults? = null,
    var disassemble: DisassembleResults? = null
)

@JsonClass(generateAdapter = true)
@Suppress("LongParameterList")
class FailedTasks(
    var template: TemplatingFailed? = null,
    var snippet: SnippetTransformationFailed? = null,
    var compilation: CompilationFailed? = null,
    var kompilation: CompilationFailed? = null,
    var checkstyle: CheckstyleFailed? = null,
    var ktlint: KtLintFailed? = null,
    var complexity: ComplexityFailed? = null,
    var execution: ExecutionFailedResult? = null,
    var cexecution: ExecutionFailedResult? = null,
    var features: FeaturesFailed? = null,
    var mutations: MutationsFailed? = null,
    var disassemble: DisassembleFailedResult? = null
)

@JsonClass(generateAdapter = true)
data class FlatSource(val path: String, val contents: String)

fun List<FlatSource>.toSource(): Map<String, String> {
    require(this.map { it.path }.distinct().size == this.size) { "duplicate paths in source list" }
    return this.associate { it.path to it.contents }
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
        fun from(source: String, complexityResults: Map<String, ComplexityValue>): FlatComplexityResult {
            val classes: MutableList<FlatClassComplexity> = mutableListOf()
            val methods: MutableList<FlatMethodComplexity> = mutableListOf()
            complexityResults.forEach { (_, complexityValue) -> add(complexityValue, "", classes, methods) }
            return FlatComplexityResult(source, classes, methods)
        }

        private fun add(
            complexityValue: ComplexityValue,
            prefix: String,
            classes: MutableList<FlatClassComplexity>,
            methods: MutableList<FlatMethodComplexity>
        ) {
            if (complexityValue is MethodComplexity) {
                methods.add(FlatMethodComplexity(complexityValue, prefix))
            } else if (complexityValue is ClassComplexity) {
                classes.add(FlatClassComplexity(complexityValue, prefix))
            }
            val nextPrefix = if (prefix.isBlank()) {
                complexityValue.name
            } else {
                "$prefix.${complexityValue.name}"
            }
            complexityValue.methods.forEach { add(it.value as ComplexityValue, nextPrefix, classes, methods) }
            complexityValue.classes.forEach { add(it.value as ComplexityValue, nextPrefix, classes, methods) }
        }
    }
}

@JsonClass(generateAdapter = true)
data class FlatComplexityResults(val results: List<FlatComplexityResult>) {
    constructor(complexityResults: ComplexityResults) : this(
        complexityResults.results.map { (source, results) ->
            FlatComplexityResult.from(source, results)
        }
    )
}

@JsonClass(generateAdapter = true)
data class FlatClassFeatures(val name: String, val path: String, val range: SourceRange, val features: Features) {
    constructor(classFeatures: ClassFeatures, prefix: String) : this(
        classFeatures.name,
        "$prefix.${classFeatures.name}",
        classFeatures.range,
        classFeatures.features
    )
}

@JsonClass(generateAdapter = true)
data class FlatMethodFeatures(val name: String, val path: String, val range: SourceRange, val features: Features) {
    constructor(methodFeatures: MethodFeatures, prefix: String) : this(
        methodFeatures.name,
        "$prefix.${methodFeatures.name}",
        methodFeatures.range,
        methodFeatures.features
    )
}

@JsonClass(generateAdapter = true)
data class FlatFeaturesResult(
    val source: String,
    val classes: List<FlatClassFeatures>,
    val methods: List<FlatMethodFeatures>
) {
    companion object {
        fun from(source: String, featureResults: Map<String, FeatureValue>): FlatFeaturesResult {
            val classes: MutableList<FlatClassFeatures> = mutableListOf()
            val methods: MutableList<FlatMethodFeatures> = mutableListOf()
            featureResults.forEach { (_, featureValue) -> add(featureValue, "", classes, methods) }
            return FlatFeaturesResult(source, classes, methods)
        }

        private fun add(
            featureValue: FeatureValue,
            prefix: String,
            classes: MutableList<FlatClassFeatures>,
            methods: MutableList<FlatMethodFeatures>
        ) {
            if (featureValue is MethodFeatures) {
                methods.add(FlatMethodFeatures(featureValue, prefix))
            } else if (featureValue is ClassFeatures) {
                classes.add(FlatClassFeatures(featureValue, prefix))
            }
            val nextPrefix = if (prefix.isBlank()) {
                featureValue.name
            } else {
                "$prefix.${featureValue.name}"
            }
            featureValue.methods.forEach { add(it.value as FeatureValue, nextPrefix, classes, methods) }
            featureValue.classes.forEach { add(it.value as FeatureValue, nextPrefix, classes, methods) }
        }
    }
}

@JsonClass(generateAdapter = true)
data class FlatFeaturesResults(val results: List<FlatFeaturesResult>, val allFeatures: Map<String, String> = ALL_FEATURES) {
    constructor(featureResults: FeaturesResults) : this(
        featureResults.results.map { (source, results) ->
            FlatFeaturesResult.from(source, results)
        }
    )
}
