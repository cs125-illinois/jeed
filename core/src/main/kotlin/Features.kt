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

sealed interface FeatureValue {
    var features: Features
    fun lookup(name: String): FeatureValue
}

@Suppress("LongParameterList")
@JsonClass(generateAdapter = true)
class ClassFeatures(
    name: String,
    range: SourceRange,
    methods: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    classes: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    override var features: Features = Features(),
    val isRecord: Boolean = false,
    val isInterface: Boolean = false
) : LocatedClassOrMethod(name, range, classes, methods), FeatureValue {
    override fun lookup(name: String): FeatureValue {
        check(name.isNotEmpty())
        @Suppress("TooGenericExceptionCaught")
        return try {
            if (name[0].isUpperCase()) {
                classes[name] as FeatureValue
            } else {
                methods[name] as FeatureValue
            }
        } catch (e: Exception) {
            if (name[0].isUpperCase()) {
                error("class $name not found: ${classes.keys}")
            } else {
                error("method $name not found: ${methods.keys}")
            }
        }
    }
}

@JsonClass(generateAdapter = true)
class MethodFeatures(
    name: String,
    range: SourceRange,
    methods: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    classes: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    override var features: Features = Features()
) : LocatedClassOrMethod(name, range, classes, methods), FeatureValue {
    override fun lookup(name: String): FeatureValue {
        check(name.isNotEmpty())
        @Suppress("TooGenericExceptionCaught")
        return try {
            if (name[0].isUpperCase()) {
                classes[name] as FeatureValue
            } else {
                methods[name] as FeatureValue
            }
        } catch (e: Exception) {
            if (name[0].isUpperCase()) {
                error("class $name not found: ${classes.keys}")
            } else {
                error("method $name not found: ${methods.keys}")
            }
        }
    }
}

@Suppress("TooManyFunctions")
private class FeatureListener(val source: Source, entry: Map.Entry<String, String>) : JavaParserBaseListener() {
    private val name = entry.key
    private var anonymousCounter = 0
    private var lambdaCounter = 0

    @Suppress("unused")
    private val contents = entry.value

    private var featureStack: MutableList<FeatureValue> = mutableListOf()
    var results: MutableMap<String, ClassFeatures> = mutableMapOf()

    private val currentFeatures
        get() = featureStack.first().features

    private fun enterClassOrInterface(
        classOrInterfaceName: String,
        start: Location,
        end: Location,
        isRecord: Boolean = false,
        isInterface: Boolean = false
    ) {
        val locatedClass = if (source is Snippet && classOrInterfaceName == source.wrappedClassName) {
            ClassFeatures("", source.snippetRange, isRecord = isRecord, isInterface = isInterface)
        } else {
            ClassFeatures(
                classOrInterfaceName,
                SourceRange(name, source.mapLocation(name, start), source.mapLocation(name, end)),
                isRecord = isRecord,
                isInterface = isInterface
            )
        }
        if (featureStack.isNotEmpty()) {
            when (val currentFeatures = featureStack[0]) {
                is ClassFeatures -> {
                    assert(!currentFeatures.classes.containsKey(locatedClass.name))
                    currentFeatures.classes[locatedClass.name] = locatedClass
                }
                is MethodFeatures -> {
                    assert(!currentFeatures.classes.containsKey(locatedClass.name))
                    currentFeatures.classes[locatedClass.name] = locatedClass
                }
            }
        }
        featureStack.add(0, locatedClass)
        currentMethodName = null
        currentMethodLocation = null
        currentMethodParameters = null
        currentMethodReturnType = null
    }

    private fun exitClassOrInterface() {
        assert(featureStack.isNotEmpty())
        val lastFeatures = featureStack.removeAt(0)
        assert(lastFeatures is ClassFeatures)
        if (featureStack.isNotEmpty()) {
            featureStack[0].features += lastFeatures.features
        } else {
            val topLevelClassFeatures = lastFeatures as ClassFeatures
            assert(!results.keys.contains(topLevelClassFeatures.name))
            results[topLevelClassFeatures.name] = topLevelClassFeatures
        }
    }

    override fun enterClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
        enterClassOrInterface(
            ctx.children[1].text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
    }

    override fun exitClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
        exitClassOrInterface()
    }

    override fun enterInterfaceDeclaration(ctx: JavaParser.InterfaceDeclarationContext) {
        enterClassOrInterface(
            ctx.children[1].text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine),
            isInterface = true
        )
    }

    override fun exitInterfaceDeclaration(ctx: JavaParser.InterfaceDeclarationContext?) {
        exitClassOrInterface()
    }

    override fun enterRecordDeclaration(ctx: JavaParser.RecordDeclarationContext) {
        enterClassOrInterface(
            ctx.children[1].text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine),
            true
        )
    }

    override fun exitRecordDeclaration(ctx: JavaParser.RecordDeclarationContext?) {
        exitClassOrInterface()
    }

    override fun enterClassCreatorRest(ctx: JavaParser.ClassCreatorRestContext) {
        val parent = ctx.parent as JavaParser.CreatorContext
        val name = if (parent.children[1] is JavaParser.CreatedNameContext) {
            parent.children[1].text
        } else {
            parent.children[0].text
        } + "_Anonymous${anonymousCounter++}"
        enterClassOrInterface(
            name,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
    }

    override fun exitClassCreatorRest(ctx: JavaParser.ClassCreatorRestContext) {
        exitClassOrInterface()
    }

    private var currentMethodName: String? = null
    private var currentMethodLocation: SourceRange? = null
    private var currentMethodReturnType: String? = null
    private var currentMethodParameters: MutableList<String>? = null

    private fun enterMethodOrConstructor(
        methodOrConstructorName: String,
        start: Location,
        end: Location,
        returnType: String?
    ) {
        assert(featureStack.isNotEmpty())
        if (featureStack[0] is ClassFeatures) {
            assert(currentMethodName == null)
            assert(currentMethodLocation == null)
            assert(currentMethodReturnType == null)
            assert(currentMethodParameters == null)
        }
        currentMethodName = methodOrConstructorName
        currentMethodLocation = SourceRange(name, start, end)
        currentMethodReturnType = returnType
        currentMethodParameters = mutableListOf()
    }

    private fun exitMethodOrConstructor() {
        assert(featureStack.isNotEmpty())
        val lastFeatures = featureStack.removeAt(0)
        assert(lastFeatures is MethodFeatures)
        assert(featureStack.isNotEmpty())
        featureStack[0].features += lastFeatures.features

        currentMethodName = null
        currentMethodLocation = null
        currentMethodReturnType = null
        currentMethodParameters = null
    }

    override fun enterMethodDeclaration(ctx: JavaParser.MethodDeclarationContext) {
        enterMethodOrConstructor(
            ctx.children[1].text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine),
            ctx.children[0].text
        )
    }

    override fun exitMethodDeclaration(ctx: JavaParser.MethodDeclarationContext) {
        exitMethodOrConstructor()
    }

    override fun enterConstructorDeclaration(ctx: JavaParser.ConstructorDeclarationContext) {
        assert(featureStack.isNotEmpty())
        val currentClass = featureStack[0] as ClassFeatures
        enterMethodOrConstructor(
            currentClass.name,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine),
            currentClass.name
        )
    }

    override fun exitConstructorDeclaration(ctx: JavaParser.ConstructorDeclarationContext?) {
        exitMethodOrConstructor()
    }

    override fun enterLambdaExpression(ctx: JavaParser.LambdaExpressionContext) {
        assert(featureStack.isNotEmpty())
        enterMethodOrConstructor(
            "Lambda${lambdaCounter++}",
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine),
            null
        )
    }

    override fun exitLambdaExpression(ctx: JavaParser.LambdaExpressionContext?) {
        exitMethodOrConstructor()
    }

    override fun enterFormalParameter(ctx: JavaParser.FormalParameterContext) {
        assert(ctx.children.size >= 2)
        currentMethodParameters?.add(ctx.children[ctx.children.lastIndex - 1].text)
    }

    override fun enterLastFormalParameter(ctx: JavaParser.LastFormalParameterContext) {
        assert(ctx.children.size >= 2)
        val type = ctx.children[ctx.children.lastIndex - 1].text
        if (type != "...") {
            currentMethodParameters?.add(type)
        } else {
            @Suppress("MagicNumber")
            assert(ctx.children.size > 3)
            currentMethodParameters?.add("...${ctx.children[ctx.children.lastIndex - 2].text}")
        }
    }

    private fun exitParameters() {
        assert(featureStack.isNotEmpty())

        if (featureStack[0] is ClassFeatures) {
            // Records have a parameter list but we can ignore it
            if ((featureStack[0] as ClassFeatures).isRecord && currentMethodName == null) {
                return
            }
            // Interface methods have a parameter list but we can ignore it
            if ((featureStack[0] as ClassFeatures).isInterface && currentMethodName == null) {
                return
            }
        }

        assert(currentMethodName != null)
        assert(currentMethodLocation != null)
        assert(currentMethodParameters != null)

        val fullName = "$currentMethodName(${currentMethodParameters?.joinToString(separator = ",")})".let {
            if (currentMethodReturnType != null) {
                "$currentMethodReturnType $it"
            } else {
                it
            }
        }
        val methodFeatures = if (source is Snippet && "void " + source.looseCodeMethodName == fullName) {
            MethodFeatures("", source.snippetRange)
        } else {
            MethodFeatures(
                fullName,
                SourceRange(
                    name,
                    source.mapLocation(name, currentMethodLocation!!.start),
                    source.mapLocation(name, currentMethodLocation!!.end)
                )
            )
        }
        if (featureStack[0] is ClassFeatures) {
            val currentFeatures = featureStack[0] as ClassFeatures
            assert(!currentFeatures.methods.containsKey(methodFeatures.name))
            currentFeatures.methods[methodFeatures.name] = methodFeatures
        } else if (featureStack[0] is MethodFeatures) {
            val currentFeatures = featureStack[0] as MethodFeatures
            assert(!currentFeatures.methods.containsKey(methodFeatures.name))
            currentFeatures.methods[methodFeatures.name] = methodFeatures
        }
        featureStack.add(0, methodFeatures)
    }

    override fun exitFormalParameters(ctx: JavaParser.FormalParametersContext) {
        exitParameters()
    }

    override fun exitLambdaParameters(ctx: JavaParser.LambdaParametersContext) {
        exitParameters()
    }

    override fun enterLocalVariableDeclaration(ctx: JavaParser.LocalVariableDeclarationContext) {
        currentFeatures.localVariableDeclarations += ctx.variableDeclarators().variableDeclarator().size
        currentFeatures.variableAssignments += ctx.variableDeclarators().variableDeclarator().filter {
            it.variableInitializer() != null
        }.size
    }

    override fun enterStatement(ctx: JavaParser.StatementContext) {
        if (ctx.statementExpression != null) {
            currentFeatures.variableReassignments++
            currentFeatures.variableAssignments++
        }
    }

    init {
        ParseTreeWalker.DEFAULT.walk(this, source.getParsed(name).tree)
    }
}

class FeaturesResults(val source: Source, val results: Map<String, Map<String, ClassFeatures>>) {
    @Suppress("ReturnCount")
    fun lookup(path: String, filename: String = ""): FeatureValue {
        @Suppress("TooGenericExceptionCaught")
        return try {
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
                rootFeatures
            } else {
                resultSource[components.removeAt(0)]
            } as FeatureValue

            for (component in components) {
                currentFeatures = currentFeatures.lookup(component)
            }
            currentFeatures
        } catch (e: Exception) {
            error("lookup failed: $e")
        }
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
