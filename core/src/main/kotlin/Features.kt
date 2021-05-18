package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParserBaseListener
import org.antlr.v4.runtime.tree.ParseTreeWalker

data class Features(
    var localVariableDeclarations: Int = 0,
    var variableAssignments: Int = 0,
    var variableReassignments: Int = 0
) {
    operator fun plus(other: Features) = Features(
        localVariableDeclarations + other.localVariableDeclarations,
        variableAssignments + other.variableAssignments,
        variableReassignments + other.variableReassignments
    )
}

sealed class FeatureValue(
    name: String,
    range: SourceRange,
    methods: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    classes: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    var features: Features
) : LocatedClassOrMethod(name, range, methods, classes) {
    fun lookup(name: String): FeatureValue {
        check(name.isNotEmpty())
        return if (name[0].isUpperCase() && !name.endsWith(")")) {
            classes[name] ?: error("class $name not found ${classes.keys}")
        } else {
            methods[name] ?: error("method $name not found ${methods.keys}")
        } as FeatureValue
    }
}

@JsonClass(generateAdapter = true)
class ClassFeatures(
    name: String,
    range: SourceRange,
    methods: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    classes: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    features: Features = Features(),
) : FeatureValue(name, range, methods, classes, features)

@JsonClass(generateAdapter = true)
class MethodFeatures(
    name: String,
    range: SourceRange,
    methods: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    classes: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    features: Features = Features(),
) : FeatureValue(name, range, methods, classes, features)

@Suppress("TooManyFunctions")
private class FeatureListener(val source: Source, entry: Map.Entry<String, String>) : JavaParserBaseListener() {
    @Suppress("unused")
    private val contents = entry.value
    private val filename = entry.key

    private var featureStack: MutableList<FeatureValue> = mutableListOf()
    private val currentFeatures: FeatureValue
        get() = featureStack[0]
    var results: MutableMap<String, ClassFeatures> = mutableMapOf()

    private fun enterClassOrInterface(name: String, start: Location, end: Location) {
        val locatedClass = if (source is Snippet && name == source.wrappedClassName) {
            ClassFeatures("", source.snippetRange)
        } else {
            ClassFeatures(
                name,
                SourceRange(filename, source.mapLocation(filename, start), source.mapLocation(filename, end))
            )
        }
        if (featureStack.isNotEmpty()) {
            assert(!currentFeatures.classes.containsKey(locatedClass.name))
            currentFeatures.classes[locatedClass.name] = locatedClass
        }
        featureStack.add(0, locatedClass)
    }

    private fun enterMethodOrConstructor(name: String, start: Location, end: Location) {
        val locatedMethod =
            if (source is Snippet &&
                "void " + source.looseCodeMethodName == name &&
                (featureStack.getOrNull(0) as? ClassFeatures)?.name == ""
            ) {
                MethodFeatures("", source.snippetRange)
            } else {
                MethodFeatures(
                    name,
                    SourceRange(name, source.mapLocation(filename, start), source.mapLocation(filename, end))
                )
            }
        if (featureStack.isNotEmpty()) {
            assert(!currentFeatures.methods.containsKey(locatedMethod.name))
            currentFeatures.methods[locatedMethod.name] = locatedMethod
        }
        featureStack.add(0, locatedMethod)
    }

    private fun exitClassOrInterface() {
        assert(featureStack.isNotEmpty())
        val lastFeatures = featureStack.removeAt(0)
        assert(lastFeatures is ClassFeatures)
        if (featureStack.isNotEmpty()) {
            currentFeatures.features += lastFeatures.features
        } else {
            val topLevelFeatures = lastFeatures as ClassFeatures
            assert(!results.keys.contains(topLevelFeatures.name))
            results[topLevelFeatures.name] = topLevelFeatures
        }
    }

    private fun exitMethodOrConstructor() {
        assert(featureStack.isNotEmpty())
        val lastFeatures = featureStack.removeAt(0)
        assert(lastFeatures is MethodFeatures)
        assert(featureStack.isNotEmpty())
        currentFeatures.features += lastFeatures.features
    }

    override fun enterClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
        enterClassOrInterface(
            ctx.IDENTIFIER().text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
    }

    override fun exitClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
        exitClassOrInterface()
    }

    override fun enterInterfaceDeclaration(ctx: JavaParser.InterfaceDeclarationContext) {
        enterClassOrInterface(
            ctx.IDENTIFIER().text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
    }

    override fun exitInterfaceDeclaration(ctx: JavaParser.InterfaceDeclarationContext?) {
        exitClassOrInterface()
    }

    override fun enterRecordDeclaration(ctx: JavaParser.RecordDeclarationContext) {
        enterClassOrInterface(
            ctx.IDENTIFIER().text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
    }

    override fun exitRecordDeclaration(ctx: JavaParser.RecordDeclarationContext?) {
        exitClassOrInterface()
    }

    override fun enterMethodDeclaration(ctx: JavaParser.MethodDeclarationContext) {
        val parameters = ctx.formalParameters().formalParameterList()?.formalParameter()?.joinToString(",") {
            it.typeType().text
        } ?: ""
        enterMethodOrConstructor(
            "${ctx.typeTypeOrVoid().text} ${ctx.IDENTIFIER().text}($parameters)",
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
    }

    override fun exitMethodDeclaration(ctx: JavaParser.MethodDeclarationContext) {
        exitMethodOrConstructor()
    }

    override fun enterConstructorDeclaration(ctx: JavaParser.ConstructorDeclarationContext) {
        assert(featureStack.isNotEmpty())
        val currentClass = currentFeatures as ClassFeatures
        val parameters = ctx.formalParameters().formalParameterList().formalParameter().joinToString(",") {
            it.typeType().text
        }
        enterMethodOrConstructor(
            "${currentClass.name}($parameters)",
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine),
        )
    }

    override fun exitConstructorDeclaration(ctx: JavaParser.ConstructorDeclarationContext?) {
        exitMethodOrConstructor()
    }

    override fun enterLocalVariableDeclaration(ctx: JavaParser.LocalVariableDeclarationContext) {
        currentFeatures.features.localVariableDeclarations += ctx.variableDeclarators().variableDeclarator().size
        currentFeatures.features.variableAssignments += ctx.variableDeclarators().variableDeclarator().filter {
            it.variableInitializer() != null
        }.size
    }

    override fun enterStatement(ctx: JavaParser.StatementContext) {
        if (ctx.statementExpression != null) {
            currentFeatures.features.variableReassignments++
            currentFeatures.features.variableAssignments++
        }
    }

    init {
        ParseTreeWalker.DEFAULT.walk(this, source.getParsed(filename).tree)
    }
}

class FeaturesResults(val source: Source, val results: Map<String, Map<String, ClassFeatures>>) {
    @Suppress("ReturnCount")
    fun lookup(path: String, filename: String = ""): FeatureValue {
        val components = path.split(".").toMutableList()

        if (source is Snippet) {
            require(filename == "") { "filename cannot be set for snippet lookups" }
        }
        val resultSource = results[filename] ?: error("results does not contain key $filename")

        var currentFeatures = if (source is Snippet) {
            val rootFeatures = resultSource[""] ?: error("")
            if (path.isEmpty()) {
                return rootFeatures
            } else if (path == ".") {
                return rootFeatures.methods[""] as FeatureValue
            }
            if (resultSource[components[0]] != null) {
                resultSource[components.removeAt(0)]
            } else {
                rootFeatures
            }
        } else {
            resultSource[components.removeAt(0)]
        } as FeatureValue

        for (component in components) {
            currentFeatures = currentFeatures.lookup(component)
        }
        return currentFeatures
    }
}

class FeaturesFailed(errors: List<SourceError>) : JeedError(errors) {
    override fun toString(): String {
        return "errors were encountered while performing feature analysis: ${errors.joinToString(separator = ",")}"
    }
}

@Throws(FeaturesFailed::class)
fun Source.features(names: Set<String> = sources.keys.toSet()): FeaturesResults {
    require(type == Source.FileType.JAVA) { "Can't perform feature analysis yet for Kotlin sources" }
    try {
        return FeaturesResults(
            this,
            sources.filter {
                names.contains(it.key)
            }.mapValues {
                FeatureListener(this, it).results
            }
        )
    } catch (e: JeedParsingException) {
        throw FeaturesFailed(e.errors)
    }
}
