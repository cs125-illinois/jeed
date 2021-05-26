package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParserBaseListener
import org.antlr.v4.runtime.tree.ParseTreeWalker

enum class FeatureName {
    LOCAL_VARIABLE_DECLARATIONS,
    VARIABLE_ASSIGNMENTS,
    VARIABLE_REASSIGNMENTS,
    FOR_LOOPS,
    NESTED_FOR,
    WHILE_LOOPS,
    NESTED_WHILE,
    DO_WHILE_LOOPS,
    NESTED_DO_WHILE,
    IF_STATEMENTS,
    ELSE_STATEMENTS,
    ELSE_IF,
    NESTED_IF,
    METHOD
}

data class Features(
    var forLoopCount: Int = 0,
    var whileLoopCount: Int = 0,
    var doWhileLoopCount: Int = 0,
    var ifCount: Int = 0,
    var elseCount: Int = 0,
    var elseIfCount: Int = 0,
    var nestedIfCount: Int = 0,
    var nestedForCount: Int = 0,
    var nestedWhileCount: Int = 0,
    var nestedDoWhileCount: Int = 0,
    var featureMap: MutableMap<FeatureName, Int> = FeatureName.values().associate { it to 0 }.toMutableMap()
) {
    operator fun plus(other: Features):Features {
        val map = mutableMapOf<FeatureName, Int>()
        for (key in FeatureName.values()) {
            map[key] = featureMap[key]!! + other.featureMap[key]!!
        }
        return Features(
        forLoopCount + other.forLoopCount,
        whileLoopCount + other.whileLoopCount,
        doWhileLoopCount + other.doWhileLoopCount,
        ifCount + other.ifCount,
        elseCount + other.elseCount,
        elseIfCount + other.elseIfCount,
        nestedIfCount + other.nestedIfCount,
        nestedForCount + other.nestedForCount,
        nestedWhileCount + other.nestedWhileCount,
        nestedDoWhileCount + other.nestedDoWhileCount,
            map
    )}
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
        count(FeatureName.METHOD, 1)
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
        count(FeatureName.LOCAL_VARIABLE_DECLARATIONS, ctx.variableDeclarators().variableDeclarator().size)
        count(FeatureName.VARIABLE_ASSIGNMENTS, ctx.variableDeclarators().variableDeclarator().filter {
            it.variableInitializer() != null
        }.size)
    }

    private val seenIfStarts = mutableSetOf<Int>()

    private val currentFeatureMap: MutableMap<FeatureName, Int>
        get() = currentFeatures.features.featureMap

    private fun count(feature: FeatureName, amount: Int) {
        currentFeatureMap[feature] = (currentFeatureMap[feature] ?: 0) + amount
    }

    override fun enterStatement(ctx: JavaParser.StatementContext) {
        ctx.statementExpression?.also {
            if (it.bop?.text == "=") {
                count(FeatureName.VARIABLE_ASSIGNMENTS, 1)
                count(FeatureName.VARIABLE_REASSIGNMENTS, 1)
            }
        }
        ctx.FOR()?.also {
            count(FeatureName.FOR_LOOPS, 1)
        }
        ctx.WHILE()?.also {
            // Only increment whileLoopCount if it's not a do-while loop
            if (ctx.DO() != null) {
                count(FeatureName.DO_WHILE_LOOPS, 1)
                if (ctx.statement(0) != null) {
                    val statement = ctx.statement(0).block().blockStatement()
                    for (block in statement) {
                        if (block.statement() == null) continue
                        if (block.statement().DO() != null && block.statement().WHILE() != null) {
                            // Count nested do-while loop
                            currentFeatures.features.nestedDoWhileCount++
                        }
                    }
                }
            } else {
                currentFeatures.features.whileLoopCount++
                if (ctx.statement(0) != null) {
                    val statement = ctx.statement(0).block().blockStatement()
                    for (block in statement) {
                        if (block.statement() == null) continue
                        if (block.statement().WHILE() != null) {
                            // Count nested while loop
                            currentFeatures.features.nestedWhileCount++
                        }
                    }
                }
            }
        }
        ctx.IF()?.also {
            // Check for else-if chains
            val outerIfStart = ctx.start.startIndex
            if (outerIfStart !in seenIfStarts) {
                // Count if block
                currentFeatures.features.ifCount++
                seenIfStarts += outerIfStart
                check(ctx.statement().isNotEmpty())

                if (ctx.statement().size == 2 && ctx.statement(1).block() != null) {
                    // Count else block
                    check(ctx.ELSE() != null)
                    currentFeatures.features.elseCount++

                } else if (ctx.statement().size >= 2) {
                    var statement = ctx.statement(1)
                    println(statement.text)
                    while (statement != null) {
                        if (statement.IF() != null) {
                            // If statement contains an IF, it is part of a chain
                            seenIfStarts += statement.start.startIndex
                            currentFeatures.features.elseIfCount++
                        } else {
                            currentFeatures.features.elseCount++
                        }
                        statement = statement.statement(1)
                    }
                }
            }
        }
        // Count nested statements
        if (ctx.statement(0) != null) {
            val statement = ctx.statement(0).block().blockStatement()
            for (block in statement) {
                block.statement()?.FOR()?.also {
                    count(FeatureName.NESTED_FOR, 1)
                }
                block.statement()?.IF()?.also {
                    seenIfStarts += block.statement().start.startIndex
                    count(FeatureName.IF_STATEMENTS, 1)
                    count(FeatureName.NESTED_IF, 1)
                }
                block.statement()?.WHILE()?.also {
                    if (block.statement().DO() != null) {
                        count(FeatureName.NESTED_DO_WHILE, 1)
                    } else {
                        count(FeatureName.NESTED_WHILE, 1)
                    }
                }
            }
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
