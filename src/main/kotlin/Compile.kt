package edu.illinois.cs.cs125.janini


import org.codehaus.commons.compiler.jdk.ScriptEvaluator
import org.codehaus.commons.compiler.jdk.SimpleCompiler

import mu.KotlinLogging
private val logger = KotlinLogging.logger {}

data class CompiledSource(
        val source: Source,
        val succeeded: Boolean,
        val error: TaskError?,
        @Transient val classLoader: ClassLoader? = null,
        @Transient val scriptEvaluator: ScriptEvaluator? = null
)

fun Source.compile(): CompiledSource {
    return try {
        if (snippet) {
            val localScriptEvaluator = ScriptEvaluator()
            localScriptEvaluator.cook(sources.values.toTypedArray()[0])
            CompiledSource(this,true, null, null, localScriptEvaluator)
        } else {
            val simpleCompiler = SimpleCompiler()
            simpleCompiler.compile(sources)
            assert(simpleCompiler.classLoader != null)
            CompiledSource(this,true, null, simpleCompiler.classLoader)
        }
    } catch (e: Exception) {
        logger.trace(e) { "compilation failed" }
        CompiledSource(this,false, TaskError(e))
    }
}
