package edu.illinois.cs.cs125.jeed.core

import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.HandlebarsException

class TemplatedSource(
        sources: Map<String, String>,
        val originalSources: Map<String, String>,
        @Transient private val remappedLineMapping: Map<String, RemappedLines> = mapOf()
) : Source(sources) {
    data class RemappedLines(val start: Int, val end: Int, val addedIndentation: Int = 0)
}

private val handlebars = Handlebars()
private val TEMPLATE_START = """\{\{\{\s*contents\s*}}}""".toRegex()
private val LEADING_WHITESPACE = """^\s*""".toRegex()

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

    val remappedLineMapping = sources.mapValues { (name, source) ->
        val templateName = "${name.removeSuffix(".java")}.hbs"
        val sourceLength = source.lines().size

        val templateSource = templates[templateName]
                ?: return@mapValues TemplatedSource.RemappedLines(1, sourceLength)

        val contentsLines = templateSource.lines().mapIndexed{ lineNumber, line ->
            Pair(lineNumber, line)
        }.filter { (_, line) ->
            line.contains(TEMPLATE_START)
        }
        if (contentsLines.size != 1) {
            return@mapValues TemplatedSource.RemappedLines(1, sourceLength)
        }

        val templateLine = contentsLines.first()
        val leadingWhitespace = (LEADING_WHITESPACE.find(templateLine.second)?.range?.endInclusive ?: -1) + 1
        TemplatedSource.RemappedLines(templateLine.first + 1, templateLine.first + sourceLength, leadingWhitespace)
    }

    return TemplatedSource(templatedSources, sources)
}
