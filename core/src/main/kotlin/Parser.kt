@file:Suppress("unused")

package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.JavaLexer
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinLexer
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser
import edu.illinois.cs.cs125.jeed.core.antlr.SnippetLexer
import edu.illinois.cs.cs125.jeed.core.antlr.SnippetParser
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.atn.ParserATNSimulator
import org.antlr.v4.runtime.atn.PredictionContextCache
import org.antlr.v4.runtime.misc.Utils
import org.antlr.v4.runtime.tree.Tree
import org.antlr.v4.runtime.tree.Trees

class JeedParseError(location: SourceLocation?, message: String) : SourceError(location, message)
class JeedParsingException(errors: List<SourceError>) : JeedError(errors)

class JeedErrorListener(val source: Source, entry: Map.Entry<String, String>) : BaseErrorListener() {
    private val name = entry.key

    @Suppress("unused")
    private val contents = entry.value

    private val errors = mutableListOf<JeedParseError>()
    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String,
        e: RecognitionException?
    ) {
        val location = try {
            source.mapLocation(SourceLocation(name, line, charPositionInLine))
        } catch (e: Exception) {
            null
        }
        errors.add(JeedParseError(location, msg))
    }

    fun check() {
        if (errors.size > 0) {
            throw JeedParsingException(errors)
        }
    }
}

fun Source.parseJavaFile(entry: Map.Entry<String, String>): Source.ParsedSource {
    check(sourceFilenameToFileType(entry.key) == Source.FileType.JAVA) { "Must be called on a Java file" }
    val errorListener = JeedErrorListener(this, entry)
    val charStream = CharStreams.fromString(entry.value)
    val (parseTree, parser) = JavaLexer(charStream).let {
        it.removeErrorListeners()
        it.addErrorListener(errorListener)
        CommonTokenStream(it)
    }.also {
        errorListener.check()
    }.let {
        val parser = JavaParser(it)
        parser.interpreter.decisionToDFA.also { dfa ->
            parser.interpreter = ParserATNSimulator(parser, parser.atn, dfa, PredictionContextCache())
        }
        parser.removeErrorListeners()
        parser.addErrorListener(errorListener)
        Pair(parser.compilationUnit(), parser)
    }.also {
        errorListener.check()
    }

    return Source.ParsedSource(parseTree, charStream, entry.value, parser)
}

fun Source.parseKotlinFile(entry: Map.Entry<String, String>): Source.ParsedSource {
    check(sourceFilenameToFileType(entry.key) == Source.FileType.KOTLIN) { "Must be called on a Kotlin file" }
    val errorListener = JeedErrorListener(this, entry)
    val charStream = CharStreams.fromString(entry.value)
    val (parseTree, parser) = KotlinLexer(charStream).let {
        it.removeErrorListeners()
        it.addErrorListener(errorListener)
        CommonTokenStream(it)
    }.also {
        errorListener.check()
    }.let {
        val parser = KotlinParser(it)
        parser.interpreter.decisionToDFA.also { dfa ->
            parser.interpreter = ParserATNSimulator(parser, parser.atn, dfa, PredictionContextCache())
        }
        parser.removeErrorListeners()
        parser.addErrorListener(errorListener)
        try {
            Pair(parser.kotlinFile(), parser)
        } catch (e: StackOverflowError) {
            throw JeedParsingException(listOf(SourceError(null, "Code is too complicated to determine complexity")))
        }
    }.also {
        errorListener.check()
    }

    return Source.ParsedSource(parseTree, charStream, entry.value, parser)
}

class DistinguishErrorListener : BaseErrorListener() {
    override fun syntaxError(
        recognizer: Recognizer<*, *>?,
        offendingSymbol: Any?,
        line: Int,
        charPositionInLine: Int,
        msg: String,
        e: RecognitionException?
    ) {
        check(!msg.trim().startsWith("extraneous input"))
        if (e != null) {
            throw (e)
        }
    }
}

fun String.isJavaSource(): Boolean {
    val errorListener = DistinguishErrorListener()
    @Suppress("TooGenericExceptionCaught")
    return try {
        CharStreams.fromString(this).let { charStream ->
            JavaLexer(charStream).let { lexer ->
                lexer.removeErrorListeners()
                lexer.addErrorListener(errorListener)
                CommonTokenStream(lexer)
            }
        }.also { tokenStream ->
            JavaParser(tokenStream).let { parser ->
                parser.interpreter.decisionToDFA.also { dfa ->
                    parser.interpreter = ParserATNSimulator(parser, parser.atn, dfa, PredictionContextCache())
                }
                parser.removeErrorListeners()
                parser.addErrorListener(errorListener)
                parser.compilationUnit()
            }
        }
        true
    } catch (e: Exception) {
        false
    }
}

fun String.isJavaSnippet(): Boolean {
    val errorListener = DistinguishErrorListener()
    @Suppress("TooGenericExceptionCaught")
    return try {
        CharStreams.fromString("{$this}").let { charStream ->
            SnippetLexer(charStream).let { lexer ->
                lexer.removeErrorListeners()
                lexer.addErrorListener(errorListener)
                CommonTokenStream(lexer)
            }
        }.also { tokenStream ->
            SnippetParser(tokenStream).let { parser ->
                parser.interpreter.decisionToDFA.also { dfa ->
                    parser.interpreter = ParserATNSimulator(parser, parser.atn, dfa, PredictionContextCache())
                }
                parser.removeErrorListeners()
                parser.addErrorListener(errorListener)
                parser.block()
            }
        }
        true
    } catch (e: Exception) {
        false
    }
}

fun String.isKotlinSource(): Boolean {
    val errorListener = DistinguishErrorListener()
    @Suppress("TooGenericExceptionCaught")
    return try {
        CharStreams.fromString(this).let { charStream ->
            KotlinLexer(charStream).let { lexer ->
                lexer.removeErrorListeners()
                lexer.addErrorListener(errorListener)
                CommonTokenStream(lexer)
            }
        }.also { tokenStream ->
            KotlinParser(tokenStream).let { parser ->
                parser.interpreter.decisionToDFA.also { dfa ->
                    parser.interpreter = ParserATNSimulator(parser, parser.atn, dfa, PredictionContextCache())
                }
                parser.removeErrorListeners()
                parser.addErrorListener(errorListener)
                parser.kotlinFile()
            }
        }
        true
    } catch (e: Exception) {
        false
    }
}

fun String.isKotlinSnippet(): Boolean {
    val errorListener = DistinguishErrorListener()
    @Suppress("TooGenericExceptionCaught")
    return try {
        CharStreams.fromString(this).let { charStream ->
            KotlinLexer(charStream).let { lexer ->
                lexer.removeErrorListeners()
                lexer.addErrorListener(errorListener)
                CommonTokenStream(lexer)
            }
        }.also { tokenStream ->
            KotlinParser(tokenStream).let { parser ->
                parser.interpreter.decisionToDFA.also { dfa ->
                    parser.interpreter = ParserATNSimulator(parser, parser.atn, dfa, PredictionContextCache())
                }
                parser.removeErrorListeners()
                parser.addErrorListener(errorListener)
                parser.script()
            }
        }
        true
    } catch (e: Exception) {
        false
    }
}

fun String.parseKnippet(): Source.ParsedSource {
    val errorListener = DistinguishErrorListener()
    val charStream = CharStreams.fromString(this)
    val (parseTree, parser) = KotlinLexer(charStream).let {
        it.removeErrorListeners()
        it.addErrorListener(errorListener)
        CommonTokenStream(it)
    }.let {
        val parser = KotlinParser(it)
        parser.removeErrorListeners()
        parser.addErrorListener(errorListener)
        Pair(parser.script(), parser)
    }
    return Source.ParsedSource(parseTree, charStream, this, parser)
}

fun String.parseSnippet(): Source.ParsedSource {
    val errorListener = DistinguishErrorListener()
    val charStream = CharStreams.fromString(this)
    val (parseTree, parser) = SnippetLexer(charStream).let {
        it.removeErrorListeners()
        it.addErrorListener(errorListener)
        CommonTokenStream(it)
    }.let {
        val parser = SnippetParser(it)
        parser.removeErrorListeners()
        parser.addErrorListener(errorListener)
        Pair(parser.block(), parser)
    }
    return Source.ParsedSource(parseTree, charStream, this, parser)
}

enum class SourceType {
    JAVA_SOURCE, JAVA_SNIPPET, KOTLIN_SOURCE, KOTLIN_SNIPPET
}

fun String.distinguish(language: String) = when {
    language == "java" && isJavaSource() -> SourceType.JAVA_SOURCE
    language == "java" && isJavaSnippet() -> SourceType.JAVA_SNIPPET
    language == "kotlin" && isKotlinSource() -> SourceType.KOTLIN_SOURCE
    language == "kotlin" && isKotlinSnippet() -> SourceType.KOTLIN_SNIPPET
    else -> null
}

fun Tree.toPrettyTree(ruleNames: List<String>): String {
    var level = 0
    fun lead(level: Int): String {
        val sb = StringBuilder()
        if (level > 0) {
            sb.append(System.lineSeparator())
            repeat(level) {
                sb.append("  ")
            }
        }
        return sb.toString()
    }

    fun process(t: Tree, ruleNames: List<String>): String {
        if (t.childCount == 0) {
            return Utils.escapeWhitespace(Trees.getNodeText(t, ruleNames), false)
        }
        val sb = StringBuilder()
        sb.append(lead(level))
        level++
        val s: String = Utils.escapeWhitespace(Trees.getNodeText(t, ruleNames), false)
        sb.append("$s ")
        for (i in 0 until t.childCount) {
            sb.append(process(t.getChild(i), ruleNames))
        }
        level--
        sb.append(lead(level))
        return sb.toString()
    }

    return process(this, ruleNames).replace("(?m)^\\s+$", "").replace("\\r?\\n\\r?\\n", System.lineSeparator())
}
