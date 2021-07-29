package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.KnippetLexer
import edu.illinois.cs.cs125.jeed.core.antlr.SnippetLexer
import org.antlr.v4.runtime.CharStreams

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
