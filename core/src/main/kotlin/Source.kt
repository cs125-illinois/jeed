package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
import edu.illinois.cs.cs125.jeed.core.antlr.JavaLexer
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import org.antlr.v4.runtime.*
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Method
import java.time.Instant


@JsonClass(generateAdapter = true)
open class Source
@Throws(JavaParsingException::class)
constructor
(
        val sources: Map<String, String>,
        checkSourceNames: (Map<String, String>) -> Boolean = ::defaultCheckSourceNames,
        @Transient val sourceMappingFunction: (SourceLocation) -> SourceLocation = { it }
) {

    init {
        require(sources.keys.isNotEmpty())
        require(checkSourceNames(sources))
    }

    fun mapLocation(input: SourceLocation): SourceLocation {
        return sourceMappingFunction(input)
    }

    fun mapLocation(source: String, input: Location): Location {
        val resultSourceLocation = sourceMappingFunction(SourceLocation(source, input.line, input.column))
        return Location(resultSourceLocation.line, resultSourceLocation.column)
    }

    @Transient private lateinit var _parsed: Map<String, JavaParser.CompilationUnitContext>
    val parsed: Map<String, JavaParser.CompilationUnitContext>
        get() {
            if (!this::_parsed.isInitialized) {
                _parsed = sources.mapValues { entry ->
                    val errorListener = JavaErrorListener(this, entry)

                    val charStream = CharStreams.fromString(entry.value)
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
            }
            return _parsed
        }

    companion object {
        private fun defaultCheckSourceNames(sources: Map<String, String>): Boolean {
            return sources.keys.all { name -> name.isNotBlank() && name.split("/").last()[0].isUpperCase() }
        }
    }
}

class JavaParseError(location: SourceLocation, message: String) : SourceError(location, message)
class UnsafeCatchError(location: SourceLocation, message: String) : SourceError(location, message)
class JavaParsingException(errors: List<SourceError>) : JeedError(errors)

class JavaErrorListener(val source: Source, entry: Map.Entry<String, String>) : BaseErrorListener() {
    private val name = entry.key
    private val contents = entry.value

    private val errors = mutableListOf<JavaParseError>()
    override fun syntaxError(recognizer: Recognizer<*, *>?, offendingSymbol: Any?, line: Int, charPositionInLine: Int, msg: String, e: RecognitionException?) {
        errors.add(JavaParseError(source.mapLocation(SourceLocation(name, line, charPositionInLine)), msg))
    }
    fun check() {
        if (errors.size > 0) {
            throw JavaParsingException(errors)
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
        val message: String
) {
    override fun toString(): String {
        return "$location: $message"
    }
}

abstract class JeedError(val errors: List<SourceError>) : Exception() {
    override fun toString(): String {
        return javaClass.name + ":\n" + errors.joinToString(separator = "\n")
    }
}
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

// Overloads of built-in functions that can be used to the right of Elvis operators
fun check(block: () -> String): Nothing { throw IllegalStateException(block()) }
fun require(block: () -> String): Nothing { throw IllegalArgumentException(block()) }
