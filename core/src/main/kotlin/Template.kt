package edu.illinois.cs.cs125.jeed.core

import com.github.jknack.handlebars.Handlebars
import com.github.jknack.handlebars.HandlebarsException

class TemplatedSource(
    sources: Sources,
    val originalSources: Sources,
    @Transient private val remappedLineMapping: Map<String, RemappedLines>
) : Source(
    sources,
    sourceMappingFunction = { mapLocation(it, remappedLineMapping) },
    leadingIndentationFunction = { leadingIndentation(it, remappedLineMapping) }
) {
    data class RemappedLines(val start: Int, val end: Int, val addedIndentation: Int = 0)
    companion object {
        fun mapLocation(input: SourceLocation, remappedLineMapping: Map<String, RemappedLines>): SourceLocation {
            val remappedLineInfo = remappedLineMapping[input.source] ?: return input
            if (input.line !in remappedLineInfo.start..remappedLineInfo.end) {
                throw SourceMappingException("can't map line outside of template range: $input")
            }
            return SourceLocation(
                input.source,
                input.line - remappedLineInfo.start + 1,
                input.column - remappedLineInfo.addedIndentation
            )
        }

        fun leadingIndentation(input: SourceLocation, remappedLineMapping: Map<String, RemappedLines>): Int {
            val remappedLineInfo = remappedLineMapping[input.source] ?: return 0
            if (input.line !in remappedLineInfo.start..remappedLineInfo.end) {
                throw SourceMappingException("can't map line outside of template range: $input")
            }
            return remappedLineInfo.addedIndentation
        }
    }
}

private val handlebars = Handlebars()

private val TEMPLATE_START =
    """\{\{\{\s*contents\s*}}}""".toRegex()
private val LEADING_WHITESPACE =
    """^\s*""".toRegex()

class TemplatingError(
    name: String,
    line: Int,
    column: Int,
    message: String
) : AlwaysLocatedSourceError(SourceLocation(name, line, column), message)

class TemplatingFailed(errors: List<TemplatingError>) : AlwaysLocatedJeedError(errors)

@Throws(TemplatingFailed::class)
@Suppress("LongMethod")
fun Source.Companion.fromTemplates(sourceMap: Map<String, String>, templateMap: Map<String, String>): TemplatedSource {
    val sources = Sources(sourceMap)
    val templates = Sources(templateMap)

    require(templates.keys.all { it.endsWith(".hbs") }) { "template names in map should end with .hbs" }
    require(sources.keys.containsAll(templates.keys.map { it.removeSuffix(".hbs") })) {
        "templates map contains keys not present in source map"
    }

    val templatingErrors = mutableListOf<TemplatingError>()

    val remappedLineMapping = mutableMapOf<String, TemplatedSource.RemappedLines>()
    val templatedSources = sources.mapValues { (name, source) ->
        val templateName = "$name.hbs"
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
        } ?: error("should have created a template")

        try {
            val indentedSource = source.lines().mapIndexed { i, line ->
                if (i > 0) {
                    "$leadingWhitespaceContent$line"
                } else {
                    line
                }
            }.joinToString(separator = "\n") {
                when {
                    it.isBlank() -> ""
                    else -> it
                }
            }
            template.apply(mapOf("contents" to indentedSource))
        } catch (e: HandlebarsException) {
            templatingErrors.add(TemplatingError(name, e.error.line, e.error.column, e.error.message))
            ""
        }
    }.let { Sources(it) }

    if (templatingErrors.isNotEmpty()) {
        throw TemplatingFailed(templatingErrors)
    }

    return TemplatedSource(templatedSources, sources, remappedLineMapping)
}
