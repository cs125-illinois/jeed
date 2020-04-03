package edu.illinois.cs.cs125.jeed.core

import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.HandlebarsException

class TemplatedSource(
    sources: Map<String, String>,
    val originalSources: Map<String, String>,
    @Transient private val remappedLineMapping: Map<String, RemappedLines>
) : Source(sources, sourceMappingFunction = { mapLocation(it, remappedLineMapping) }) {
    data class RemappedLines(val start: Int, val end: Int, val addedIndentation: Int = 0)
    companion object {
        fun mapLocation(input: SourceLocation, remappedLineMapping: Map<String, RemappedLines>): SourceLocation {
            val remappedLineInfo = remappedLineMapping[input.source] ?: return input
            assert(remappedLineInfo.start <= input.line) { "can't map line before template range" }
            assert(input.line >= remappedLineInfo.start) { "can't map line after template range" }
            return SourceLocation(
                input.source,
                input.line - remappedLineInfo.start + 1,
                input.column - remappedLineInfo.addedIndentation
            )
        }
    }
}

private val handlebars = Handlebars()

private val TEMPLATE_START = """\{\{\{\s*contents\s*}}}""".toRegex()
private val LEADING_WHITESPACE = """^\s*""".toRegex()

class TemplatingError(
    name: String,
    line: Int,
    column: Int,
    message: String
) : AlwaysLocatedSourceError(SourceLocation(name, line, column), message)

class TemplatingFailed(errors: List<TemplatingError>) : AlwaysLocatedJeedError(errors)

@Throws(TemplatingFailed::class)
@Suppress("LongMethod")
fun Source.Companion.fromTemplates(sources: Map<String, String>, templates: Map<String, String>): TemplatedSource {
    require(templates.keys.all { it.endsWith(".hbs") }) { "template names in map should end with .hbs" }
    require(sources.keys.map { it.removeSuffix(".java") }
        .containsAll(templates.keys.map { it.removeSuffix(".hbs") })) {
        "templates map contains keys not present in source map"
    }

    val templatingErrors = mutableListOf<TemplatingError>()

    val remappedLineMapping = mutableMapOf<String, TemplatedSource.RemappedLines>()
    val templatedSources = sources.mapValues { (name, source) ->
        val templateName = "${name.removeSuffix(".java")}.hbs"
        val templateSource = templates[templateName] ?: return@mapValues source

        val contentsLines = templateSource.lines().mapIndexed { lineNumber, line ->
            Pair(lineNumber, line)
        }.filter { (_, line) ->
            line.contains(TEMPLATE_START)
        }

        val leadingWhitespaceContent = if (contentsLines.size == 1) {
            val sourceLength = source.lines().size
            val templateLine = contentsLines.first()
            val leadingWhitespace = LEADING_WHITESPACE.find(templateLine.second)
            val (whitespaceContent, leadingWhitespaceAmount) = if (leadingWhitespace == null) {
                Pair("", 0)
            } else {
                Pair(leadingWhitespace.value, leadingWhitespace.range.last + 1)
            }
            remappedLineMapping[name] = TemplatedSource.RemappedLines(
                templateLine.first + 1,
                templateLine.first + sourceLength,
                leadingWhitespaceAmount
            )
            whitespaceContent
        } else {
            ""
        }

        val template = try {
            handlebars.compileInline(templates[templateName])
        } catch (e: HandlebarsException) {
            templatingErrors.add(TemplatingError(templateName, e.error.line, e.error.column, e.error.message))
            return@mapValues ""
        } ?: assert { "should have created a template" }

        try {
            val indentedSource = source.lines().mapIndexed { i, line ->
                if (i > 0) {
                    "$leadingWhitespaceContent$line"
                } else {
                    line
                }
            }.joinToString(separator = "\n")
            template.apply(mapOf("contents" to indentedSource))
        } catch (e: HandlebarsException) {
            templatingErrors.add(TemplatingError(name, e.error.line, e.error.column, e.error.message))
            ""
        }
    }
    if (templatingErrors.isNotEmpty()) {
        throw TemplatingFailed(templatingErrors)
    }

    return TemplatedSource(templatedSources, sources, remappedLineMapping)
}
