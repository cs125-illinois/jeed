package edu.illinois.cs.cs125.janini

import mu.KotlinLogging
import org.codehaus.commons.compiler.jdk.ScriptEvaluator
private val logger = KotlinLogging.logger {}

fun compile(task: Task): Task {
    if (task.compiled != null) {
        throw IllegalStateException("Task has already been compiled")
    }
    try {
        if (task.snippet) {
            val scriptEvaluator = ScriptEvaluator()
            scriptEvaluator.cook(task.sources.values.toTypedArray()[0])
            task.scriptEvaluator = scriptEvaluator
        }
        task.compiled = true
    } catch (e: Exception) {
        logger.trace(e) { "compilation failed" }
        task.compilerError = TaskError(e)
        task.compiled = false
    }
    return task
}
