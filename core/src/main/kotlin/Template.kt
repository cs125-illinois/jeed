package edu.illinois.cs.cs125.jeed.core

import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.HandlebarsException

class TemplatedSource(
        sources: Map<String, String>,
        val originalSources: Map<String, String>,
        @Transient private val remappedLineMapping: Map<Int, RemappedLine> = mapOf()
) : Source(sources) {
    data class RemappedLine(val source: String, val sourceLineNumber: Int, val rewrittenLineNumber: Int, val addedIndentation: Int = 0)
}

private val handlebars = Handlebars()

class TemplatingError(
        name: String,
        line: Int,
        column: Int,
        message: String
) : SourceError(SourceLocation(name, line, column), message)
class TemplatingFailed(errors: List<TemplatingError>) : JeedError(errors)

@Throws(TemplatingFailed::class)
fun Source.Companion.fromTemplates(sources: Map<String, String>, templates: Map<String, String>): TemplatedSource {
    require(templates.keys.all { it.endsWith(".hbs") }) { "template names in map should end with .hbs" }
    require(sources.keys.map { it.removeSuffix(".java") }.containsAll(templates.keys.map { it.removeSuffix(".hbs")})) { "templates map contains keys not present in source map" }

    val templatingErrors = mutableListOf<TemplatingError>()

    val templatedSources = sources.mapValues { (name, source) ->
        val templateName = "${name.removeSuffix(".java")}.hbs"
        if (!templates.keys.contains(templateName)) { return@mapValues source }

        val template = try {
            handlebars.compileInline(templates[templateName])
        } catch (e: HandlebarsException) {
            templatingErrors.add(TemplatingError(templateName, e.error.line, e.error.column, e.error.message))
            return@mapValues ""
        } ?: assert { "should have created a template" }

        try {
            template.apply(mapOf("contents" to source))
        } catch (e: HandlebarsException) {
            templatingErrors.add(TemplatingError(name, e.error.line, e.error.column, e.error.message))
            ""
        }
    }
    if (templatingErrors.isNotEmpty()) {
        throw TemplatingFailed(templatingErrors)
    }

    return TemplatedSource(templatedSources, sources)
}
