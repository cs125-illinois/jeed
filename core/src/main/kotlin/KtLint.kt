package edu.illinois.cs.cs125.jeed.core

import com.pinterest.ktlint.core.KtLint
import com.pinterest.ktlint.core.RuleSet
import com.pinterest.ktlint.ruleset.standard.ChainWrappingRule
import com.pinterest.ktlint.ruleset.standard.CommentSpacingRule
import com.pinterest.ktlint.ruleset.standard.IndentationRule
import com.pinterest.ktlint.ruleset.standard.MaxLineLengthRule
import com.pinterest.ktlint.ruleset.standard.ModifierOrderRule
import com.pinterest.ktlint.ruleset.standard.NoEmptyClassBodyRule
import com.pinterest.ktlint.ruleset.standard.NoLineBreakAfterElseRule
import com.pinterest.ktlint.ruleset.standard.NoLineBreakBeforeAssignmentRule
import com.pinterest.ktlint.ruleset.standard.NoMultipleSpacesRule
import com.pinterest.ktlint.ruleset.standard.NoSemicolonsRule
import com.pinterest.ktlint.ruleset.standard.NoTrailingSpacesRule
import com.pinterest.ktlint.ruleset.standard.NoUnitReturnRule
import com.pinterest.ktlint.ruleset.standard.ParameterListWrappingRule
import com.pinterest.ktlint.ruleset.standard.SpacingAroundColonRule
import com.pinterest.ktlint.ruleset.standard.SpacingAroundCommaRule
import com.pinterest.ktlint.ruleset.standard.SpacingAroundCurlyRule
import com.pinterest.ktlint.ruleset.standard.SpacingAroundDotRule
import com.pinterest.ktlint.ruleset.standard.SpacingAroundKeywordRule
import com.pinterest.ktlint.ruleset.standard.SpacingAroundOperatorsRule
import com.pinterest.ktlint.ruleset.standard.SpacingAroundParensRule
import com.pinterest.ktlint.ruleset.standard.SpacingAroundRangeOperatorRule
import com.pinterest.ktlint.ruleset.standard.StringTemplateRule
import com.squareup.moshi.JsonClass
import java.io.File
import java.io.FileOutputStream

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
) : AlwaysLocatedSourceError(location, "$ruleId: $detail")

class KtLintFailed(errors: List<KtLintError>) : AlwaysLocatedJeedError(errors) {
    override fun toString(): String {
        return "ktlint errors were encountered: ${errors.joinToString(separator = ",")}"
    }
}

@JsonClass(generateAdapter = true)
data class KtLintResults(val errors: List<KtLintError>)

@Suppress("Deprecation")
val editorConfigPath: String = run {
    val tempFile = File(createTempDir("ktlint"), ".editorconfig")
    object {}::class.java.getResourceAsStream("/ktlint/.editorconfig").let { input ->
        FileOutputStream(tempFile).also { output ->
            input!!.copyTo(output)
        }.close()
    }
    tempFile.path
}

@Suppress("SpellCheckingInspection")
val jeedRuleSet = RuleSet(
    "standard",
    ChainWrappingRule(),
    CommentSpacingRule(),
    IndentationRule(),
    MaxLineLengthRule(),
    ModifierOrderRule(),
    // NoBlankLineBeforeRbraceRule(),
    // NoConsecutiveBlankLinesRule(),
    NoEmptyClassBodyRule(),
    NoLineBreakAfterElseRule(),
    NoLineBreakBeforeAssignmentRule(),
    NoMultipleSpacesRule(),
    NoSemicolonsRule(),
    NoTrailingSpacesRule(),
    NoUnitReturnRule(),
    ParameterListWrappingRule(),
    SpacingAroundColonRule(),
    SpacingAroundCommaRule(),
    SpacingAroundCurlyRule(),
    SpacingAroundDotRule(),
    SpacingAroundKeywordRule(),
    SpacingAroundOperatorsRule(),
    SpacingAroundParensRule(),
    SpacingAroundRangeOperatorRule(),
    StringTemplateRule()
)

@Suppress("DEPRECATION")
fun Source.ktFormat(ktLintArguments: KtLintArguments = KtLintArguments()): Source {
    require(type == Source.FileType.KOTLIN) { "Can't run ktlint on non-Kotlin sources" }

    val names = ktLintArguments.sources ?: sources.keys
    val source = this
    val formattedSources = sources
        .filter { (filename, _) -> filename !in names }
        .map { (filename, contents) -> filename to contents }
        .toMap().toMutableMap()

    sources.filter { (filename, _) ->
        filename in names
    }.forEach { (filename, contents) ->
        formattedSources[
            if (source is Snippet) {
                "MainKt.kt"
            } else {
                filename
            }
        ] = KtLint.format(
            KtLint.Params(
                if (source is Snippet) {
                    "MainKt.kt"
                } else {
                    filename
                },
                contents,
                listOf(jeedRuleSet),
                cb = { e, corrected ->
                    if (!corrected && ktLintArguments.failOnError) {
                        throw KtLintFailed(
                            listOf(
                                KtLintError(
                                    e.ruleId,
                                    e.detail,
                                    mapLocation(SourceLocation(filename, e.line, e.col))
                                )
                            )
                        )
                    }
                },
                editorConfigPath = editorConfigPath
            )
        )
    }

    return Source(formattedSources)
}

private val unexpectedRegex = """Unexpected indentation \((\d+)\)""".toRegex()
private val shouldBeRegex = """should be (\d+)""".toRegex()

@Suppress("LongMethod", "DEPRECATION")
fun Source.ktLint(ktLintArguments: KtLintArguments = KtLintArguments()): KtLintResults {
    require(type == Source.FileType.KOTLIN) { "Can't run ktlint on non-Kotlin sources" }

    val names = ktLintArguments.sources ?: sources.keys
    val source = this

    val errors: MutableList<KtLintError> = mutableListOf<KtLintError>().apply {
        sources.filter { (filename, _) ->
            filename in names
        }.forEach { (filename, contents) ->
            KtLint.lint(
                KtLint.Params(
                    if (source is Snippet) {
                        "MainKt.kt"
                    } else {
                        filename
                    },
                    contents,
                    listOf(jeedRuleSet),
                    cb = { e, _ ->
                        @Suppress("EmptyCatchBlock")
                        try {
                            val originalLocation = SourceLocation(filename, e.line, e.col)
                            val mappedLocation = mapLocation(originalLocation)

                            val detail = if (e.ruleId == "indent") {
                                @Suppress("TooGenericExceptionCaught")
                                try {
                                    val addedIndent = leadingIndentation(originalLocation)
                                    val (incorrectMessage, incorrectAmount) =
                                        unexpectedRegex.find(e.detail)?.groups?.let { match ->
                                            Pair(match[0]?.value, match[1]?.value?.toInt())
                                        } ?: error("Couldn't parse indentation error")
                                    val (expectedMessage, expectedAmount) =
                                        shouldBeRegex.find(e.detail)?.groups?.let { match ->
                                            Pair(match[0]?.value, match[1]?.value?.toInt())
                                        } ?: error("Couldn't parse indentation error")

                                    e.detail
                                        .replace(
                                            incorrectMessage!!,
                                            "Unexpected indentation (${incorrectAmount!! - addedIndent})"
                                        )
                                        .replace(expectedMessage!!, "should be ${expectedAmount!! - addedIndent}")
                                } catch (_: Exception) {
                                    e.detail
                                }
                            } else {
                                e.detail
                            }
                            add(
                                KtLintError(
                                    e.ruleId,
                                    detail,
                                    mappedLocation
                                )
                            )
                        } catch (_: Exception) {
                        }
                    },
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
