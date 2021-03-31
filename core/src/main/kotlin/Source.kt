package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import edu.illinois.cs.cs125.jeed.core.server.FlatSource
import edu.illinois.cs.cs125.jeed.core.server.toFlatSources
import mu.KotlinLogging
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.tree.ParseTree
import java.io.PrintWriter
import java.io.StringWriter
import java.lang.reflect.Method
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale

@Suppress("UNUSED")
val logger = KotlinLogging.logger {}

data class Sources(val sources: Map<String, String>) : Map<String, String> by sources {
    val contents: String
        get() = sources.values.let {
            check(it.size == 1) { "Can only retrieve contents for sources with single file" }
            it.first()
        }
    val name: String
        get() = sources.keys.let {
            check(it.size == 1) { "Can only retrieve name for sources with single file" }
            it.first()
        }
}

open class Source(
    sourceMap: Map<String, String>,
    checkSourceNames: (Sources) -> FileType = ::defaultCheckSourceNames,
    @Transient val sourceMappingFunction: (SourceLocation) -> SourceLocation = { it }
) {
    val sources = Sources(sourceMap)

    val contents: String
        get() = sources.contents

    val name: String
        get() = sources.name

    operator fun get(filename: String) = sources[filename]
    override fun toString() = if (sources.keys.size == 1 && name == "") {
        contents
    } else {
        sources.toString()
    }

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

    data class ParsedSource(val tree: ParseTree, val stream: CharStream, val contents: String)

    var parsed = false

    @Suppress("MemberVisibilityCanBePrivate")
    var parseInterval: Interval? = null
    private val parsedSources: Map<String, ParsedSource> by lazy {
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

    fun getParsed(filename: String): ParsedSource = parsedSources[filename] ?: error("$filename not in sources")

    fun sourceFilenameToFileType(filename: String): FileType {
        if (this is Snippet) {
            check(filename.isEmpty()) { "Snippets should not have a filename" }
            return type
        }
        return filenameToFileType(filename)
    }

    @JsonClass(generateAdapter = true)
    data class FlattenedSources(val sources: List<FlatSource>)

    val md5: String by lazy {
        MessageDigest.getInstance("MD5")?.let { message ->
            message.digest(
                moshi.adapter(FlattenedSources::class.java).toJson(
                    FlattenedSources(sources.toFlatSources().sortedBy { it.path })
                ).toByteArray()
            )
        }?.joinToString(separator = "") {
            String.format(Locale.US, "%02x", it)
        } ?: require { "Problem computing hash" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Source
        return md5 == other.md5
    }

    override fun hashCode(): Int {
        return md5.toBigInteger(radix = 16).toInt()
    }

    companion object {
        private val moshi by lazy {
            Moshi.Builder().build()
        }

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

        private fun defaultCheckSourceNames(sources: Sources): FileType {
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

        fun fromJava(contents: String) = Source(mapOf("Main.java" to contents))
        @Suppress("unused")
        fun fromKotlin(contents: String) = Source(mapOf("Main.kt" to contents))
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
    var classes: MutableMap<String, LocatedClass> = mutableMapOf(),
    val methods: MutableMap<String, LocatedMethod> = mutableMapOf()
)

@JsonClass(generateAdapter = true)
open class SourceError(
    open val location: SourceLocation?,
    val message: String
) {
    override fun toString(): String {
        return if (location == null) message else "$location: $message"
    }
}

@JsonClass(generateAdapter = true)
open class AlwaysLocatedSourceError(
    final override val location: SourceLocation,
    message: String
) : SourceError(location, message)

abstract class JeedError(open val errors: List<SourceError>) : Exception() {
    override fun toString(): String {
        return javaClass.name + ":\n" + errors.joinToString(separator = "\n")
    }
}

abstract class AlwaysLocatedJeedError(final override val errors: List<AlwaysLocatedSourceError>) : JeedError(errors)

@JsonClass(generateAdapter = true)
data class Interval(val start: Instant, val end: Instant) {
    val length by lazy {
        end.toEpochMilli() - start.toEpochMilli()
    }
}

fun Throwable.getStackTraceAsString(): String {
    val stringWriter = StringWriter()
    val printWriter = PrintWriter(stringWriter)
    this.printStackTrace(printWriter)
    return stringWriter.toString()
}

val stackTraceLineRegex = Regex("""^at ([\w$]+)\.(\w+)\(([\w.]*):(\d+)\)$""")

@Suppress("unused", "ComplexMethod")
fun Throwable.getStackTraceForSource(
    source: Source,
    boundaries: List<String> = listOf(
        """at java.base/jdk.internal""",
        """at MainKt.main()"""
    )
): String {
    val originalStackTrace = this.getStackTraceAsString().lines().toMutableList()
    val firstLine = originalStackTrace.removeAt(0)

    val betterStackTrace = mutableListOf(firstLine)
    @Suppress("LoopWithTooManyJumpStatements")
    for (line in originalStackTrace) {
        val l = line.trim()
        if (boundaries.any { l.startsWith(it) }) {
            break
        }
        if (!(source is Snippet || source is TemplatedSource)) {
            betterStackTrace.add(line)
            continue
        }
        val parsedLine = stackTraceLineRegex.find(l)
        if (parsedLine == null) {
            betterStackTrace.add(line)
            continue
        }
        val (klass, method, name, correctLine) = parsedLine.destructured
        val fixedKlass = if (source is Snippet &&
            (klass == source.wrappedClassName || klass == "${source.wrappedClassName}${"$"}Companion")
        ) {
            ""
        } else {
            "$klass."
        }
        val fixedMethod = if (source is Snippet && method == source.looseCodeMethodName) {
            ""
        } else {
            method
        }
        val correctLocation = source.mapLocation(SourceLocation(name, correctLine.toInt(), 0))
        betterStackTrace.add("  at $fixedKlass$fixedMethod(:${correctLocation.line})")
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

class SourceMappingException(message: String) : Exception(message)
