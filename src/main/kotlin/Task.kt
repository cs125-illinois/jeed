package edu.illinois.cs.cs125.janini

import org.codehaus.commons.compiler.jdk.ScriptEvaluator
import java.io.PrintWriter
import java.io.StringWriter

class Task(
        val snippet: Boolean,
        val sources: Map<String, String>
) {
    constructor(snippet: String): this(true, mapOf("" to snippet))
    constructor(sources: Map<String, String>): this (false, sources)

    init {
        if (sources.keys.isEmpty()) {
            throw IllegalArgumentException("Must provide at least one source")
        }
        if (snippet) {
            if (sources.keys.size != 1) {
                throw IllegalArgumentException("Snippets must provide exactly one source")
            }
            val path = sources.keys.toTypedArray()[0]
            if (path != "") {
                throw IllegalArgumentException("Snippets should not provide a source path: $path")
            }
        }
    }

    var compiled: Boolean? = null
    var compilerError: TaskError? = null
    @Transient
    var scriptEvaluator: ScriptEvaluator? = null
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
