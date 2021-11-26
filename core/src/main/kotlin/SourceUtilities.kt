@file:Suppress("TooManyFunctions")

package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.JavaLexer
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParserBaseListener
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinLexer
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParserBaseListener
import edu.illinois.cs.cs125.jeed.core.antlr.SnippetLexer
import net.sf.extjwnl.data.POS
import net.sf.extjwnl.dictionary.Dictionary
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.misc.Interval
import org.antlr.v4.runtime.tree.ParseTreeWalker

fun String.stripComments(type: Source.FileType): String {
    val charStream = CharStreams.fromString(this)
    return when (type) {
        Source.FileType.JAVA ->
            SnippetLexer(charStream).allTokens.filter { it.channel != 1 }.joinToString("") { it.text }
        Source.FileType.KOTLIN ->
            KotlinLexer(charStream).allTokens.filter { it.channel != 1 }.joinToString("") { it.text }
    }
}

fun Source.stripComments() = Source(sources.mapValues { (_, contents) -> contents.stripComments(type) })

internal fun String.identifiers(type: Source.FileType): Set<String> {
    val charStream = CharStreams.fromString(this)
    return when (type) {
        Source.FileType.JAVA ->
            SnippetLexer(charStream).allTokens.filter { it.type == SnippetLexer.IDENTIFIER }.map { it.text }.toSet()
        Source.FileType.KOTLIN ->
            KotlinLexer(charStream).allTokens.filter { it.type == KotlinLexer.Identifier }.map { it.text }.toSet()
    }
}

fun Source.identifiers() = mutableSetOf<String>().apply {
    sources.mapValues { (_, contents) -> addAll(contents.identifiers(type)) }
}.toSet()

internal fun Source.ParsedSource.strings(type: Source.FileType): Set<String> {
    return when (type) {
        Source.FileType.JAVA -> {
            val charStream = CharStreams.fromString(contents)
            JavaLexer(charStream).allTokens
                .filter { it.type == JavaLexer.STRING_LITERAL || it.type == JavaLexer.TEXT_BLOCK_LITERAL }
                .map {
                    when (it.type) {
                        JavaLexer.STRING_LITERAL -> it.text.removeSurrounding("\"").trim()
                        JavaLexer.TEXT_BLOCK_LITERAL -> it.text.removeSurrounding("\"\"\"").trim()
                        else -> error("Bad token type")
                    }
                }
                .toSet()
        }
        Source.FileType.KOTLIN -> {
            object : KotlinParserBaseListener() {
                val strings = mutableSetOf<String>()
                override fun enterStringLiteral(ctx: KotlinParser.StringLiteralContext) {
                    ctx.lineStringLiteral()?.also {
                        strings += it.text.removeSurrounding("\"").trim()
                    }
                    ctx.multiLineStringLiteral()?.also {
                        strings += it.text.removeSurrounding("\"\"\"").trim()
                    }
                }

                init {
                    ParseTreeWalker.DEFAULT.walk(this, tree)
                }
            }.strings
        }
    }
}

fun Source.strings() = mutableSetOf<String>().apply {
    sources.mapValues { (filename, _) -> addAll(getParsed(filename).strings(type)) }
}.toSet()

fun Source.ParsedSource.stripAssertionMessages(type: Source.FileType): String {
    val keep = when (type) {
        Source.FileType.JAVA -> object : JavaParserBaseListener() {
            val keep = mutableListOf<Any>()
            var currentStart = 0
            override fun enterStatement(ctx: JavaParser.StatementContext) {
                ctx.ASSERT()?.also {
                    ctx.expression(1)?.also {
                        val start = ctx.expression(0).stop.stopIndex + 1
                        keep += (currentStart until start) as Any
                        currentStart = ctx.expression(1).stop.stopIndex + 1
                    }
                }
            }

            init {
                ParseTreeWalker.DEFAULT.walk(this, tree)
                keep += (currentStart until stream.size()) as Any
            }
        }.keep
        Source.FileType.KOTLIN -> {
            object : KotlinParserBaseListener() {
                var currentStart = 0
                val keep = mutableListOf<Any>()
                override fun enterPostfixUnaryExpression(ctx: KotlinParser.PostfixUnaryExpressionContext) {
                    val identifier = ctx.primaryExpression()?.simpleIdentifier()?.text
                    if (identifier != null && listOf("assert", "check", "require").contains(identifier)) {
                        ctx.postfixUnarySuffix(0)?.callSuffix()?.annotatedLambda()?.also {
                            val start = it.start.startIndex
                            keep += (currentStart until start) as Any
                            currentStart = it.stop.stopIndex + 1
                        }
                    }
                    if (identifier == "error") {
                        ctx.postfixUnarySuffix(0)?.callSuffix()?.valueArguments()?.also {
                            val start = it.start.startIndex
                            keep += (currentStart until start + 1) as Any
                            keep += "\"error\""
                            currentStart = it.stop.stopIndex
                        }
                    }
                }

                init {
                    ParseTreeWalker.DEFAULT.walk(this, tree)
                    keep += (currentStart until stream.size()) as Any
                }
            }.keep
        }
    }
    return keep.joinToString("") {
        when (it) {
            is IntRange -> stream.getText(Interval(it.first, it.last))
            is String -> it
            else -> error("Bad type for $it")
        }
    }
}

fun Source.stripAssertionMessages() =
    Source(sources.mapValues { (filename, _) -> getParsed(filename).stripAssertionMessages(type) })

fun Source.trimLines() =
    Source(sources.mapValues { (_, contents) -> contents.lines().joinToString("\n") { it.trimEnd() } })

private fun levenshteinDistance(first: List<Int>, second: List<Int>): Int {
    val costs = IntArray(second.size + 1) { it }
    for (i in 1..first.size) {
        costs[0] = i
        var nw = i - 1
        for (j in 1..second.size) {
            val cj =
                (1 + costs[j].coerceAtMost(costs[j - 1])).coerceAtMost(
                    if (first[i - 1] == second[j - 1]) {
                        nw
                    } else {
                        nw + 1
                    }
                )
            nw = costs[j]
            costs[j] = cj
        }
    }
    return costs[second.size]
}

fun String.lineDifferenceCount(other: String) =
    levenshteinDistance(lines().map { it.trimEnd().hashCode() }, other.lines().map { it.trimEnd().hashCode() })

val WORDS by lazy {
    object {}::class.java.getResource("/bad.txt")!!.readText().lines().map { it.trim().lowercase() }.toSet()
}
val LARGEST_WORD = WORDS.maxOf { it.length }
val DICTIONARY = Dictionary.getDefaultResourceInstance()!!

fun String.hasBadWords(): String? {
    val input = lowercase().replace("""[^a-zA-Z]""".toRegex(), "")
    for (start in input.indices) {
        var offset = 1
        while (offset < input.length + 1 - start && offset < LARGEST_WORD) {
            val wordToCheck = input.substring(start, start + offset)
            WORDS.find { it ->
                if (it == "ass" && input.contains("pass")) {
                    false
                } else if (it == "meth" && input.contains("something")) {
                    false
                } else if (it == "joint" && input.contains("jointostring")) {
                    false
                } else if (wordToCheck.length <= 2 && input.length > 2) {
                    false
                } else if (it == wordToCheck) {
                    !(it.length < input.length && POS.values().any { DICTIONARY.getIndexWord(it, input) != null })
                } else {
                    false
                }
            }?.also {
                return it
            }
            offset++
        }
    }
    return null
}

fun Source.hasBadWords(): String? {
    sources.entries.forEach { (filename, contents) ->
        (contents.identifiers(type) + getParsed(filename).strings(type)).forEach { identifier ->
            identifier.trim().split(" ").forEach {
                it.trim().hasBadWords()?.also { badWord ->
                    return badWord
                }
            }
        }
    }
    return null
}
