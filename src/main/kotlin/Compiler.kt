package edu.illinois.cs.cs125.janini

import org.codehaus.commons.compiler.jdk.ScriptEvaluator

import mu.KotlinLogging
import org.codehaus.commons.compiler.jdk.SimpleCompiler
import org.codehaus.janino.util.resource.MapResourceFinder

private val logger = KotlinLogging.logger {}

fun Task.compile(): Task {
    if (compiled != null) {
        throw IllegalStateException("Task has already been compiled")
    }
    try {
        if (snippet) {
            val scriptEvaluator = ScriptEvaluator()
            scriptEvaluator.cook(sources.values.toTypedArray()[0])
            this.scriptEvaluator = scriptEvaluator
        } else {
            val simpleCompiler = SimpleCompiler()
            simpleCompiler.compile(sources)
        }
        compiled = true
    } catch (e: Exception) {
        logger.trace(e) { "compilation failed" }
        compilerError = TaskError(e)
        compiled = false
    }
    return this
}

fun Map<String, String>.toMapResourceFinder(): MapResourceFinder {
    val sourceFinder = MapResourceFinder()
    this.forEach { (path, source) ->
        sourceFinder.addResource(path, source)
    }
    return sourceFinder
}
