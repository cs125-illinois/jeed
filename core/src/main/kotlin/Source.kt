package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.JavaLexer
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import org.antlr.v4.runtime.*
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Method
import java.time.Instant


open class Source
@Throws(JavaParsingFailed::class)
constructor
(
        val sources: Map<String, String>,
        checkSources: (Map<String, String>) -> Boolean = { source ->
            source.keys.all { name -> name.isNotBlank() && name.split("/").last()[0].isUpperCase() }
        }
) {

    init {
        require(sources.keys.isNotEmpty())
        require(checkSources(sources)) { "problem validating sources: $sources" }
    }
    val parsedSources = sources.mapValues { (_, source) ->
        val errorListener = JavaErrorListener(source)

        val charStream = CharStreams.fromString(source)
        val javaLexer = JavaLexer(charStream)
        javaLexer.removeErrorListeners()
        javaLexer.addErrorListener(errorListener)

        val tokenStream = CommonTokenStream(javaLexer)
        errorListener.check()

        val javaParser = JavaParser(tokenStream)
        javaParser.removeErrorListeners()
        javaParser.addErrorListener(errorListener)

        val toReturn = javaParser.compilationUnit()
        errorListener.check()
        toReturn
    }

    open fun mapLocation(input: SourceLocation): SourceLocation {
        return input
    }
    open fun mapLocation(source: String, input: Location): Location {
        return input
    }
    companion object
}

class JavaParseError(source: String, line: Int, column: Int, message: String?) : SourceError(SourceLocation(source, line, column), message)
class JavaParsingFailed(errors: List<JavaParseError>) : JeepError(errors)

class JavaErrorListener(val source: String) : BaseErrorListener() {
    private val errors = mutableListOf<JavaParseError>()
    override fun syntaxError(recognizer: Recognizer<*, *>?, offendingSymbol: Any?, line: Int, charPositionInLine: Int, msg: String, e: RecognitionException?) {
        errors.add(JavaParseError(source, line, charPositionInLine, msg))
    }
    fun check() {
        if (errors.size > 0) {
            throw JavaParsingFailed(errors)
        }
    }
}

data class SourceLocation(
        val source: String,
        val line: Int,
        val column: Int
) {
    override fun toString(): String {
        return if (source != SNIPPET_SOURCE) {
            "$source ($line:$column)"
        } else {
            "($line:$column)"
        }
    }
}
data class Location(val line: Int, val column: Int)
data class SourceRange(
        val source: String?,
        val start: Location,
        val end: Location
)

open class LocatedClass(
        val name: String,
        val range: SourceRange,
        val classes: MutableMap<String, LocatedClass> = mutableMapOf(),
        val methods: MutableMap<String, LocatedMethod> = mutableMapOf()
)
open class LocatedMethod(
        val name: String,
        val range: SourceRange,
        var classes: MutableMap<String, LocatedClass> = mutableMapOf()
)

abstract class SourceError(
        val location: SourceLocation,
        val message: String?
) {
    override fun toString(): String {
        return "$location: $message"
    }
}
abstract class JeepError(val errors: List<SourceError>) : Exception()
data class Interval(val start: Instant, val end: Instant)

data class TaskError(val error: Throwable) {
    val stackTrace: String
    init {
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        error.printStackTrace(printWriter)
        stackTrace = stringWriter.toString()
    }

    override fun toString(): String {
        return error.toString()
    }
}

fun Exception.getStackTraceAsString(): String {
    val stringWriter = StringWriter()
    val printWriter = PrintWriter(stringWriter)
    this.printStackTrace(printWriter)
    return stringWriter.toString()
}
fun Method.getQualifiedName(): String { return "$name(${parameters.joinToString(separator = ", ")})" }
