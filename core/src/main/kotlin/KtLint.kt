package edu.illinois.cs.cs125.jeed.core

import com.pinterest.ktlint.core.KtLint
import com.pinterest.ktlint.ruleset.standard.StandardRuleSetProvider
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class KtLintArguments(
    val sources: Set<String>? = null,
    val failOnError: Boolean = false
)

@JsonClass(generateAdapter = true)
class KtLintError(
    val ruleId: String,
    val detail: String,
    location: SourceLocation
) : SourceError(location, "$ruleId: $detail")

class KtLintFailed(errors: List<KtLintError>) : JeedError(errors) {
    override fun toString(): String {
        return "ktlint errors were encountered: ${errors.joinToString(separator = ",")}"
    }
}

@JsonClass(generateAdapter = true)
data class KtLintResults(val errors: List<KtLintError>)

private val editorConfigPath = object {}::class.java.getResource("/ktlint/.editorconfig").path

fun Source.ktLint(ktLintArguments: KtLintArguments = KtLintArguments()): KtLintResults {
    require(this.type == Source.FileType.KOTLIN) { "Can't run ktlint on non-Kotlin sources" }

    val names = ktLintArguments.sources ?: sources.keys

    val errors: MutableList<KtLintError> = mutableListOf<KtLintError>().apply {
        sources.filter { (filename, _) ->
            filename in names
        }.forEach { (filename, contents) ->
            KtLint.lint(
                KtLint.Params(
                    filename,
                    contents,
                    listOf(StandardRuleSetProvider().get()),
                    cb = { e, _ -> add(KtLintError(e.ruleId, e.detail, SourceLocation(filename, e.line, e.col))) },
                    editorConfigPath = editorConfigPath
                )
            )
        }
    }
    if (errors.isNotEmpty() && ktLintArguments.failOnError) {
        throw KtLintFailed(errors)
    }
    return KtLintResults(errors)
}