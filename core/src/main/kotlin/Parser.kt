package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.JavaLexer
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import edu.illinois.cs.cs125.jeed.core.antlr.KnippetLexer
import edu.illinois.cs.cs125.jeed.core.antlr.KnippetParser
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinLexer
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser
import edu.illinois.cs.cs125.jeed.core.antlr.SnippetLexer
import edu.illinois.cs.cs125.jeed.core.antlr.SnippetParser
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer

class JeedParseError(location: SourceLocation, message: String) : SourceError(location, message)
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
        errors.add(JeedParseError(source.mapLocation(SourceLocation(name, line, charPositionInLine)), msg))
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
    val parseTree = JavaLexer(charStream).let {
        it.removeErrorListeners()
        it.addErrorListener(errorListener)
        CommonTokenStream(it)
    }.also {
        errorListener.check()
    }.let {
        JavaParser(it)
    }.let {
        it.removeErrorListeners()
        it.addErrorListener(errorListener)
        it.compilationUnit()
    }.also {
        errorListener.check()
    }

    return Source.ParsedSource(parseTree, charStream)
}

fun Source.parseKotlinFile(entry: Map.Entry<String, String>): Source.ParsedSource {
    check(sourceFilenameToFileType(entry.key) == Source.FileType.KOTLIN) { "Must be called on a Kotlin file" }
    val errorListener = JeedErrorListener(this, entry)
    val charStream = CharStreams.fromString(entry.value)
    val parseTree = KotlinLexer(charStream).let {
        it.removeErrorListeners()
        it.addErrorListener(errorListener)
        CommonTokenStream(it)
    }.also {
        errorListener.check()
    }.let {
        KotlinParser(it)
    }.let {
        it.removeErrorListeners()
        it.addErrorListener(errorListener)
        it.kotlinFile()
    }.also {
        errorListener.check()
    }

    return Source.ParsedSource(parseTree, charStream)
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
            throw(e)
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
            KnippetLexer(charStream).let { lexer ->
                lexer.removeErrorListeners()
                lexer.addErrorListener(errorListener)
                CommonTokenStream(lexer)
            }
        }.also { tokenStream ->
            KnippetParser(tokenStream).let { parser ->
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
