package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParserBaseListener
import edu.illinois.cs.cs125.jeed.core.antlr.KnippetLexer
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParserBaseListener
import edu.illinois.cs.cs125.jeed.core.antlr.SnippetLexer
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.misc.Interval
import org.antlr.v4.runtime.tree.ParseTreeWalker

fun String.stripComments(type: Source.FileType): String {
    val charStream = CharStreams.fromString(this)
    return when (type) {
        Source.FileType.JAVA ->
            SnippetLexer(charStream).allTokens.filter { it.channel != 1 }.joinToString("") { it.text }
        Source.FileType.KOTLIN ->
            KnippetLexer(charStream).allTokens.filter { it.channel != 1 }.joinToString("") { it.text }
    }
}

fun Source.stripComments() = Source(sources.mapValues { (_, contents) -> contents.stripComments(type) })

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
                    val identifier = ctx.atomicExpression()?.simpleIdentifier()?.text
                    if (identifier != null && listOf("assert", "check", "require").contains(identifier)) {
                        ctx.postfixUnaryOperation(0)?.callSuffix()?.annotatedLambda(0)?.also {
                            val start = it.start.startIndex
                            keep += (currentStart until start) as Any
                            currentStart = it.stop.stopIndex + 1
                        }
                    }
                    if (identifier == "error") {
                        ctx.postfixUnaryOperation(0)?.callSuffix()?.valueArguments()?.also {
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
    return keep.joinToString("") { it ->
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
