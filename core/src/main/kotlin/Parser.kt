package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.JavaLexer
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinLexer
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.tree.ParseTree

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

fun Source.parseJavaFile(entry: Map.Entry<String, String>): Pair<ParseTree, CharStream> {
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

    return Pair(parseTree, charStream)
}

fun Source.parseKotlinFile(entry: Map.Entry<String, String>): Pair<ParseTree, CharStream> {
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

    return Pair(parseTree, charStream)
}