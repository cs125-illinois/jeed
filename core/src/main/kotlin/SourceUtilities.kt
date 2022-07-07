@file:Suppress("TooManyFunctions", "SpellCheckingInspection")

package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
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
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.misc.Interval
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.Tree
import org.antlr.v4.runtime.tree.Trees

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

fun String.fromCamelCase() = split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])".toRegex())

fun String.separateCamelCase() = split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])".toRegex())
    .joinToString(" ")

val okWords = setOf("something", "throwable")

@Suppress("ComplexCondition", "ComplexMethod", "NestedBlockDepth")
fun String.getBadWords(separateCamelCase: Boolean = false, whitelist: Set<String> = setOf()): Set<String> {
    val badWords = mutableSetOf<String>()

    val words = if (separateCamelCase) {
        separateCamelCase()
    } else {
        this
    }.lowercase().replace("""[^ a-zA-Z]""".toRegex(), "")

    if (words.isEmpty()) {
        return badWords
    }

    words.split(" ").forEach { input ->
        if (input in okWords) {
            return@forEach
        }
        for (start in input.indices) {
            var offset = 1
            while (offset < input.length + 1 - start && offset < LARGEST_WORD) {
                val wordToCheck = input.substring(start, start + offset)
                WORDS.filter { word ->
                    if (word in whitelist) {
                        false
                    } else if (word == "hell" && input.contains("hello")) {
                        false
                    } else if (wordToCheck.length <= 2 && input.length > 2) {
                        false
                    } else if (word == wordToCheck) {
                        !(
                            word.length < input.length &&
                                POS.values().any {
                                    DICTIONARY.getIndexWord(it, input) != null ||
                                        DICTIONARY.getIndexWord(it, input.removeSuffix("ed")) != null ||
                                        DICTIONARY.getIndexWord(it, input.removeSuffix("er")) != null
                                }
                            )
                    } else {
                        false
                    }
                }.forEach {
                    badWords += it
                }
                offset++
            }
        }
    }
    return badWords
}

@Suppress("ReturnCount")
fun Source.hasBadWords(whitelist: Set<String> = setOf()): String? {
    fun Set<String>.check(splitCamelCase: Boolean): String? {
        forEach { identifier ->
            identifier.trim().split(" ").forEach { string ->
                string.trim().getBadWords(splitCamelCase, whitelist).firstOrNull()?.also {
                    return it
                }
            }
        }
        return null
    }

    sources.entries.forEach { (filename, contents) ->
        contents.identifiers(type).check(true)?.also {
            return it
        }
        getParsed(filename).strings(type).check(false)?.also {
            return it
        }
    }
    return null
}

fun Source.getBadWords(whitelist: Set<String> = setOf()): Set<String> {
    val badWords = mutableSetOf<String>()
    fun Set<String>.check(splitCamelCase: Boolean) = forEach { identifier ->
        identifier.trim().split(" ").forEach { string ->
            badWords += string.trim().getBadWords(splitCamelCase, whitelist)
        }
    }
    sources.entries.forEach { (filename, contents) ->
        contents.identifiers(type).check(true)
        getParsed(filename).strings(type).check(false)
    }
    return badWords
}

@JsonClass(generateAdapter = true)
data class LineCounts(val source: Int, val comment: Int, val blank: Int) {
    operator fun plus(other: LineCounts) =
        LineCounts(source + other.source, comment + other.comment, blank + other.blank)

    operator fun minus(other: LineCounts) =
        LineCounts(source - other.source, comment - other.comment, blank - other.blank)
}

@Suppress("NestedBlockDepth")
fun String.countLines(type: Source.FileType): LineCounts {
    val source = mutableSetOf<Int>()
    val comment = mutableSetOf<Int>()

    val charStream = CharStreams.fromString(this)
    when (type) {
        Source.FileType.JAVA ->
            SnippetLexer(charStream).allTokens.forEach {
                when (it.channel) {
                    0 -> source.add(it.line)
                    1 -> comment.addAll(it.line..(it.line + it.text.lines().size).coerceAtMost(lines().size))
                }
            }
        Source.FileType.KOTLIN ->
            KotlinLexer(charStream).allTokens.forEach {
                if (it.text.isNotBlank()) {
                    when (it.channel) {
                        0 -> source.add(it.line)
                        1 -> comment.addAll(it.line..(it.line + it.text.lines().size).coerceAtMost(lines().size))
                    }
                }
            }
    }

    val blank = lines()
        .mapIndexed { index, s -> Pair(index, s) }
        .filter { (index, s) -> s.isBlank() && index + 1 !in comment }
        .map { (index, _) -> index + 1 }
        .toSet()

    // Remove end-of-line comments
    comment.removeAll(source)
    check(source.size + comment.size + blank.size == lines().size)
    return LineCounts(source.size, comment.size, blank.size)
}

@Suppress("unused")
fun Source.countLines() = sources.mapValues { (_, contents) -> contents.countLines(type) }

fun Tree.format(parser: Parser, indent: Int = 0): String = buildString {
    val tree = this@format
    val prefix = "  ".repeat(indent)
    append(prefix)
    append(Trees.getNodeText(tree, parser))
    if (tree.childCount != 0) {
        append(" (\n")
        for (i in 0 until tree.childCount) {
            append(tree.getChild(i).format(parser, indent + 1))
            append("\n")
        }
        append(prefix).append(")")
    }
}
