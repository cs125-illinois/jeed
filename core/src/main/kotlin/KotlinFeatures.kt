// ktlint-disable filename
@file:Suppress("MatchingDeclarationName")

package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParserBaseListener
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ParseTreeWalker

@Suppress("TooManyFunctions", "LargeClass", "MagicNumber", "LongMethod", "ComplexMethod")
class KotlinFeatureListener(val source: Source, entry: Map.Entry<String, String>) : KotlinParserBaseListener() {
    @Suppress("unused")
    private val contents = entry.value
    private val filename = entry.key

    private var anonymousClassDepth = 0
    private var objectLiteralCounter = 0

    private var featureStack: MutableList<FeatureValue> = mutableListOf()
    private val currentFeatures: FeatureValue
        get() = featureStack[0]
    var results: MutableMap<String, UnitFeatures> = mutableMapOf()

    private val currentFeatureMap: MutableMap<FeatureName, Int>
        get() = currentFeatures.features.featureMap

    private fun count(feature: FeatureName, amount: Int) {
        currentFeatureMap[feature] = (currentFeatureMap[feature] ?: 0) + amount
    }

    override fun enterKotlinFile(ctx: KotlinParser.KotlinFileContext) {
        val unitFeatures = UnitFeatures(
            filename,
            SourceRange(
                filename,
                Location(ctx.start.line, ctx.start.charPositionInLine),
                Location(ctx.stop.line, ctx.stop.charPositionInLine)
            )
        )
        assert(featureStack.isEmpty())
        featureStack.add(0, unitFeatures)
    }

    override fun exitKotlinFile(ctx: KotlinParser.KotlinFileContext?) {
        assert(featureStack.size == 1)
        val topLevelFeatures = featureStack.removeAt(0) as UnitFeatures
        assert(!results.keys.contains(topLevelFeatures.name))
        results[topLevelFeatures.name] = topLevelFeatures
    }

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

    private fun exitClassOrInterface() {
        assert(featureStack.isNotEmpty())
        val lastFeatures = featureStack.removeAt(0)
        assert(lastFeatures is ClassFeatures)
        if (featureStack.isNotEmpty()) {
            currentFeatures.features += lastFeatures.features
        }
    }

    private fun KotlinParser.ClassDeclarationContext.isSnippetClass() = source is Snippet &&
        simpleIdentifier().text == source.wrappedClassName

    private fun KotlinParser.FunctionDeclarationContext.isSnippetMethod() = source is Snippet &&
        fullName() == source.looseCodeMethodName &&
        (featureStack.getOrNull(0) as? ClassFeatures)?.name == ""

    private fun KotlinParser.FunctionDeclarationContext.fullName(): String {
        val name = simpleIdentifier().text
        val parameters = functionValueParameters().functionValueParameter()?.joinToString(",") {
            it.parameter().type().text
        }
        val returnType = type()?.text
        return ("$name($parameters)${returnType?.let { ":$returnType" } ?: ""}").let {
            if (anonymousClassDepth > 0) {
                "${it}${"$"}$objectLiteralCounter"
            } else {
                it
            }
        }
    }

    override fun enterClassDeclaration(ctx: KotlinParser.ClassDeclarationContext) {
        if (!ctx.isSnippetClass()) {
            count(FeatureName.CLASS, 1)
        }
        enterClassOrInterface(
            ctx.simpleIdentifier().text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
    }

    override fun exitClassDeclaration(ctx: KotlinParser.ClassDeclarationContext) {
        exitClassOrInterface()
    }

    private fun enterMethodOrConstructor(name: String, start: Location, end: Location) {
        val locatedMethod =
            if (source is Snippet &&
                source.looseCodeMethodName == name
            ) {
                MethodFeatures("", source.snippetRange)
            } else {
                MethodFeatures(
                    name,
                    SourceRange(filename, source.mapLocation(filename, start), source.mapLocation(filename, end))
                )
            }
        if (featureStack.isNotEmpty()) {
            require(!currentFeatures.methods.containsKey(locatedMethod.name))
            currentFeatures.methods[locatedMethod.name] = locatedMethod
        }
        featureStack.add(0, locatedMethod)
    }

    private fun exitMethodOrConstructor() {
        require(featureStack.isNotEmpty())
        val lastFeatures = featureStack.removeAt(0)
        require(lastFeatures is MethodFeatures)
        assert(featureStack.isNotEmpty())
        currentFeatures.features += lastFeatures.features
    }

    override fun enterFunctionDeclaration(ctx: KotlinParser.FunctionDeclarationContext) {
        enterMethodOrConstructor(
            ctx.fullName(),
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
    }

    override fun exitFunctionDeclaration(ctx: KotlinParser.FunctionDeclarationContext) {
        exitMethodOrConstructor()
    }

    private enum class ParentType {
        FUNCTION, CLASS, NONE
    }
    private fun ParserRuleContext.parentType(): ParentType {
        var currentParent = parent
        while (currentParent != null) {
            when (currentParent) {
                is KotlinParser.FunctionBodyContext -> return ParentType.FUNCTION
            }
            currentParent = currentParent.parent
        }
        return ParentType.NONE
    }

    override fun enterVariableDeclaration(ctx: KotlinParser.VariableDeclarationContext) {
        if (ctx.parent is KotlinParser.PropertyDeclarationContext && ctx.parentType() == ParentType.FUNCTION) {
            count(FeatureName.LOCAL_VARIABLE_DECLARATIONS, 1)
            count(FeatureName.VARIABLE_ASSIGNMENTS, 1)
        }
    }

    override fun enterObjectLiteral(ctx: KotlinParser.ObjectLiteralContext) {
        if (ctx.classBody() != null) {
            anonymousClassDepth++
            objectLiteralCounter++
        }
    }

    override fun exitObjectLiteral(ctx: KotlinParser.ObjectLiteralContext) {
        if (ctx.classBody() != null) {
            anonymousClassDepth--
        }
    }

    init {
        val parsedSource = source.getParsed(filename)
        // println(parsedSource.tree.format(parsedSource.parser))
        ParseTreeWalker.DEFAULT.walk(this, parsedSource.tree)
    }
}
