package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass

sealed class ComplexityValue(
    name: String,
    range: SourceRange,
    methods: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    classes: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    var complexity: Int
) : LocatedClassOrMethod(name, range, methods, classes) {
    fun lookup(name: String): ComplexityValue {
        check(name.isNotEmpty())
        return if (name[0].isUpperCase() && !name.endsWith(")")) {
            classes[name] ?: error("class $name not found ${classes.keys}")
        } else {
            methods[name] ?: error("method $name not found ${methods.keys}")
        } as ComplexityValue
    }
}

@Suppress("LongParameterList")
@JsonClass(generateAdapter = true)
class ClassComplexity(
    name: String,
    range: SourceRange,
    methods: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    classes: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    complexity: Int = 0
) : ComplexityValue(name, range, classes, methods, complexity)

@JsonClass(generateAdapter = true)
class MethodComplexity(
    name: String,
    range: SourceRange,
    methods: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    classes: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    complexity: Int = 1
) : ComplexityValue(name, range, classes, methods, complexity)

class ComplexityFailed(errors: List<SourceError>) : JeedError(errors) {
    override fun toString(): String {
        return "errors were encountered while computing complexity: ${errors.joinToString(separator = ",")}"
    }
}

class ComplexityResults(val source: Source, val results: Map<String, Map<String, ComplexityValue>>) {
    @Suppress("ReturnCount")
    fun lookup(path: String, filename: String = ""): ComplexityValue {
        val components = path.split(".").toMutableList()

        if (source is Snippet) {
            require(filename == "") { "filename cannot be set for snippet lookups" }
        }
        val resultSource = results[filename] ?: error("results does not contain filename \"$filename\"")

        var currentComplexity = if (source is Snippet) {
            val rootComplexity = resultSource[""] ?: error("")
            if (path.isEmpty()) {
                return rootComplexity
            } else if (path == ".") {
                return rootComplexity.methods[""] as ComplexityValue
            }
            if (resultSource[components[0]] != null) {
                resultSource[components.removeAt(0)]
            } else {
                rootComplexity
            }
        } else {
            val component = components.removeAt(0)
            resultSource[component] ?: error("Couldn't find path $component")
        } as ComplexityValue

        for (component in components) {
            currentComplexity = currentComplexity.lookup(component)
        }
        return currentComplexity
    }

    fun lookupFile(filename: String) =
        results[filename]?.values?.sumOf { it.complexity } ?: error("results does not contain filename \"$filename\"")
}

@Throws(ComplexityFailed::class)
fun Source.complexity(names: Set<String> = sources.keys.toSet()): ComplexityResults {
    @Suppress("SwallowedException")
    try {
        return ComplexityResults(
            this,
            sources.filter {
                names.contains(it.key)
            }.mapValues {
                when (type) {
                    Source.FileType.JAVA -> JavaComplexityListener(this, it).results
                    Source.FileType.KOTLIN -> KotlinComplexityListener(this, it).results
                }
            }
        )
    } catch (e: JeedParsingException) {
        throw ComplexityFailed(e.errors)
    }
}
