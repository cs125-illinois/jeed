package edu.illinois.cs.cs125.janini

import java.io.PrintWriter
import java.io.StringWriter

class Task(
        val snippet: Boolean,
        val sources: Map<String, String>
) {
    constructor(snippet: String): this(true, mapOf("" to snippet))
    constructor(sources: Map<String, String>): this (false, sources)

    init {
        require(sources.keys.isNotEmpty())
        if (snippet) {
            require(sources.keys.size == 1)
            val path = sources.keys.toTypedArray()[0]
            require(path == "")
        }
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
