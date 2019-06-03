package edu.illinois.cs.cs125.janini

import java.io.PrintWriter
import java.io.StringWriter

class Source(
        val sources: Map<String, String>
) {
    constructor(source: String): this(sourceFromSnippet(source))
    init {
        require(sources.keys.isNotEmpty())
    }
}

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
