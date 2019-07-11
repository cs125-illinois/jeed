package edu.illinois.cs.cs125.jeed.core

import com.github.jknack.handlebars.Handlebars

class TemplatedSource(
        sources: Map<String, String>,
        val originalSources: Map<String, String>,
        @Transient private val remappedLineMapping: Map<Int, RemappedLine> = mapOf()
) : Source(sources)

private val handlebars = Handlebars()

fun Source.Companion.fromTemplates(sources: Map<String, String>, templates: Map<String, String>): TemplatedSource {
    require(sources.keys.containsAll(templates.keys)) { "templates map contains keys not present in sources" }

    val templatedSources = sources.mapValues { (name, source) ->
        if (!templates.keys.contains(name)) { return@mapValues source }

        val template = handlebars.compileInline(templates[name])
        template.apply(mapOf("contents" to source))
    }

    return TemplatedSource(templatedSources, sources)
}
