package edu.illinois.cs.cs125.jeed

import java.io.PrintWriter
import java.io.StringWriter

open class Source(
        val sources: Map<String, String>
) {
    init {
        require(sources.keys.isNotEmpty())
    }
    open fun mapLocation(input: SourceLocation): SourceLocation {
        return input
    }
    companion object
}

data class SourceLocation(
        val source: String?,
        val line: Int,
        val char: Int
)
data class SourceError(
        val location: SourceLocation,
        val message: String?
) {
    constructor(source: String?, line: Int, char: Int, message: String?) : this(SourceLocation(source, line, char), message)
}
abstract class JeepError(val errors: List<SourceError>) : Exception()

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
