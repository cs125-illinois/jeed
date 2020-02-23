package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Method
import java.time.Instant
import mu.KotlinLogging
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.tree.ParseTree

@Suppress("UNUSED")
val logger = KotlinLogging.logger {}

open class Source(
    val sources: Map<String, String>,
    checkSourceNames: (Map<String, String>) -> FileType = ::defaultCheckSourceNames,
    @Transient val sourceMappingFunction: (SourceLocation) -> SourceLocation = { it }
) {
    enum class FileType(val type: String) {
        JAVA("Java"),
        KOTLIN("Kotlin")
    }

    val type: FileType

    init {
        require(sources.keys.isNotEmpty())
        type = checkSourceNames(sources)
    }

    fun mapLocation(input: SourceLocation): SourceLocation {
        return sourceMappingFunction(input)
    }

    fun mapLocation(source: String, input: Location): Location {
        val resultSourceLocation = sourceMappingFunction(SourceLocation(source, input.line, input.column))
        return Location(resultSourceLocation.line, resultSourceLocation.column)
    }

    var parsed = false
    var parseInterval: Interval? = null
    val parseTree: Map<String, Pair<ParseTree, CharStream>> by lazy {
        parsed = true
        val parseStart = Instant.now()
        sources.mapValues { entry ->
                    val (filename, _) = entry
                    when (sourceFilenameToFileType(filename)) {
                        FileType.JAVA -> parseJavaFile(entry)
                        FileType.KOTLIN -> parseKotlinFile(entry)
                    }
                }.also {
            parseInterval = Interval(parseStart, Instant.now())
        }
    }

    fun sourceFilenameToFileType(filename: String): FileType {
        if (this is Snippet) {
            check(filename.isEmpty()) { "Snippets should not have a filename" }
            return type
        }
        return filenameToFileType(filename)
    }

    companion object {
        private fun filenameToFileType(filename: String): FileType {
            return when (val extension = filename.split("/").last().split(".").last()) {
                "java" -> FileType.JAVA
                "kt" -> FileType.KOTLIN
                else -> require { "invalid extension: $extension" }
            }
        }

        fun filenamesToFileTypes(filenames: Set<String>): List<FileType> {
            return filenames.map { filename ->
                filenameToFileType(filename)
            }.distinct()
        }

        private fun defaultCheckSourceNames(sources: Map<String, String>): FileType {
            sources.keys.forEach { name ->
                require(name.isNotBlank()) { "filename cannot be blank" }
            }
            val fileTypes = filenamesToFileTypes(sources.keys)
            require(fileTypes.size == 1) {
                "mixed sources are not supported: found ${fileTypes.joinToString()}"
            }
            if (fileTypes.contains(FileType.JAVA)) {
                sources.keys.filter { filenameToFileType(it) == FileType.JAVA }.forEach { name ->
                    require(name.split("/").last()[0].isUpperCase()) {
                        "Java filenames must begin with an uppercase character"
                    }
                }
            }
            return fileTypes.first()
        }
    }
}

@JsonClass(generateAdapter = true)
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

@JsonClass(generateAdapter = true)
data class Location(val line: Int, val column: Int)

@JsonClass(generateAdapter = true)
data class SourceRange(
    val source: String?,
    val start: Location,
    val end: Location
)

@JsonClass(generateAdapter = true)
open class LocatedClass(
    val name: String,
    @Suppress("unused") val range: SourceRange,
    val classes: MutableMap<String, LocatedClass> = mutableMapOf(),
    val methods: MutableMap<String, LocatedMethod> = mutableMapOf()
)

@JsonClass(generateAdapter = true)
open class LocatedMethod(
    val name: String,
    @Suppress("unused") val range: SourceRange,
    var classes: MutableMap<String, LocatedClass> = mutableMapOf()
)

@JsonClass(generateAdapter = true)
open class SourceError(
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

@JsonClass(generateAdapter = true)
data class Interval(val start: Instant, val end: Instant)

fun Throwable.getStackTraceAsString(): String {
    val stringWriter = StringWriter()
    val printWriter = PrintWriter(stringWriter)
    this.printStackTrace(printWriter)
    return stringWriter.toString()
}

val stackTraceLineRegex = Regex("""^at (\w+)\.(\w+)\((\w*):(\d+)\)$""")
@Suppress("unused")
fun Throwable.getStackTraceForSource(source: Source): String {
    val originalStackTrace = this.getStackTraceAsString().lines().toMutableList()
    val firstLine = originalStackTrace.removeAt(0)

    val betterStackTrace = mutableListOf("""Exception in thread "main" $firstLine""")
    @Suppress("LoopWithTooManyJumpStatements")
    for (line in originalStackTrace) {
        if (line.trim()
                .startsWith("""at java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)""")) {
            break
        }
        if (source !is Snippet) {
            betterStackTrace.add(line)
            continue
        }
        val parsedLine = stackTraceLineRegex.find(line.trim())
        if (parsedLine == null) {
            betterStackTrace.add(line)
            continue
        }
        val (_, _, name, correctLine) = parsedLine.destructured
        val originalLocation = SourceLocation(name, correctLine.toInt(), 0)
        val correctLocation = source.mapLocation(originalLocation)
        betterStackTrace.add("  at line ${correctLocation.line}")
    }
    return betterStackTrace.joinToString(separator = "\n")
}

fun Method.getQualifiedName(): String {
    return "$name(${parameters.joinToString(separator = ", ")})"
}

// Overloads of built-in functions that can be used to the right of Elvis operators
fun assert(block: () -> String): Nothing {
    throw AssertionError(block())
}

fun check(block: () -> String): Nothing {
    throw IllegalStateException(block())
}

fun require(block: () -> String): Nothing {
    throw IllegalArgumentException(block())
}
