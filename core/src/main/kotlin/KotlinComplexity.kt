// ktlint-disable filename
@file:Suppress("MatchingDeclarationName")

package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParserBaseListener
import org.antlr.v4.runtime.tree.ParseTreeWalker

@Suppress("TooManyFunctions")
class KotlinComplexityListener(val source: Source, entry: Map.Entry<String, String>) :
    KotlinParserBaseListener() {
    private val fileName = entry.key
    private var currentClass = ""

    private val currentComplexity: ComplexityValue
        get() = complexityStack[0]

    var results: MutableMap<String, ComplexityValue> = mutableMapOf()

    private var complexityStack: MutableList<ComplexityValue> = mutableListOf()

    private fun enterClassOrInterface(name: String, start: Location, end: Location) {
        val locatedClass = if (source is Snippet && name == source.wrappedClassName) {
            ClassComplexity("", source.snippetRange)
        } else {
            ClassComplexity(
                name,
                SourceRange(name, source.mapLocation(name, start), source.mapLocation(name, end))
            )
        }
        if (complexityStack.isNotEmpty()) {
            require(!currentComplexity.classes.containsKey(locatedClass.name))
            currentComplexity.classes[locatedClass.name] = locatedClass
        }
        complexityStack.add(0, locatedClass)
    }

    private fun enterMethodOrConstructor(name: String, start: Location, end: Location) {
        val locatedMethod =
            if (source is Snippet &&
                source.looseCodeMethodName == name
            ) {
                MethodComplexity("", source.snippetRange)
            } else {
                MethodComplexity(
                    name,
                    SourceRange(fileName, source.mapLocation(fileName, start), source.mapLocation(fileName, end))
                )
            }
        if (complexityStack.isNotEmpty()) {
            require(!currentComplexity.methods.containsKey(locatedMethod.name))
            currentComplexity.methods[locatedMethod.name] = locatedMethod
        }
        complexityStack.add(0, locatedMethod)
    }

    private fun exitClassOrInterface() {
        require(complexityStack.isNotEmpty())
        val lastComplexity = complexityStack.removeAt(0)
        require(lastComplexity is ClassComplexity)
        if (complexityStack.isNotEmpty()) {
            currentComplexity.complexity += lastComplexity.complexity
        } else {
            require(!results.keys.contains(lastComplexity.name))
            results[lastComplexity.name] = lastComplexity
        }
    }

    private fun exitMethodOrConstructor() {
        require(complexityStack.isNotEmpty())
        val lastComplexity = complexityStack.removeAt(0)
        require(lastComplexity is MethodComplexity)
        if (complexityStack.isNotEmpty()) { // not top level methods
            currentComplexity.complexity += lastComplexity.complexity
        } else { // top level methods
            require(!results.keys.contains(lastComplexity.name))
            results[lastComplexity.name] = lastComplexity
        }
    }

    override fun enterClassDeclaration(ctx: KotlinParser.ClassDeclarationContext) {
        currentClass = ctx.simpleIdentifier().text
        enterClassOrInterface(
            currentClass,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
    }

    override fun exitClassDeclaration(ctx: KotlinParser.ClassDeclarationContext) {
        exitClassOrInterface()
    }

    override fun enterPrimaryConstructor(ctx: KotlinParser.PrimaryConstructorContext) {
        val parameters = ctx.classParameters().classParameter().joinToString(",") { it.type().text }
        val fullName = "$currentClass($parameters)"
        enterMethodOrConstructor(
            fullName,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
    }

    override fun exitPrimaryConstructor(ctx: KotlinParser.PrimaryConstructorContext) {
        exitMethodOrConstructor()
    }

    override fun enterSecondaryConstructor(ctx: KotlinParser.SecondaryConstructorContext) {
        val parameters =
            ctx.functionValueParameters().functionValueParameter().joinToString(",") { it.parameter().type().text }
        val fullName = "$currentClass($parameters)"
        enterMethodOrConstructor(
            fullName,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
    }

    override fun exitSecondaryConstructor(ctx: KotlinParser.SecondaryConstructorContext) {
        exitMethodOrConstructor()
    }

    override fun enterFunctionDeclaration(ctx: KotlinParser.FunctionDeclarationContext) {
        val name = ctx.identifier().text
        val parameters = ctx.functionValueParameters().functionValueParameter()?.joinToString(",") {
            it.parameter().type().text
        }
        val returnType = ctx.type().let {
            if (it.isEmpty()) {
                null
            } else {
                check(it.last().start.startIndex > ctx.identifier().start.startIndex) {
                    "Couldn't find method return type"
                }
                it.last().text
            }
        }
        val longName = "$name($parameters)${returnType?.let { ": $returnType" } ?: ""}"

        enterMethodOrConstructor(
            longName,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
    }

    override fun exitFunctionDeclaration(ctx: KotlinParser.FunctionDeclarationContext) {
        exitMethodOrConstructor()
    }

    // init
    override fun enterClassMemberDeclaration(ctx: KotlinParser.ClassMemberDeclarationContext) {
        if (ctx.start.text == "init") {
            enterMethodOrConstructor(
                "init",
                Location(ctx.start.line, ctx.start.charPositionInLine),
                Location(ctx.stop.line, ctx.stop.charPositionInLine)
            )
        }
    }

    override fun exitClassMemberDeclaration(ctx: KotlinParser.ClassMemberDeclarationContext) {
        if (ctx.start.text == "init") {
            exitMethodOrConstructor()
        }
    }

    // ||
    override fun enterDisjunction(ctx: KotlinParser.DisjunctionContext) {
        if (!ctx.text.contains("||")) return
        if (ctx.text.contains("(") || ctx.text.contains(")")) return
        require(complexityStack.isNotEmpty())
        currentComplexity.complexity++
    }

    // &&
    override fun enterConjunction(ctx: KotlinParser.ConjunctionContext) {
        if (!ctx.text.contains("&&")) return
        if (ctx.text.contains("(") || ctx.text.contains(")")) return
        require(complexityStack.isNotEmpty())
        currentComplexity.complexity++
    }

    // ?:
    override fun enterElvisExpression(ctx: KotlinParser.ElvisExpressionContext) {
        if (!ctx.text.contains("?:")) return
        if (ctx.text.contains("(") || ctx.text.contains(")")) return
        require(complexityStack.isNotEmpty())
        currentComplexity.complexity++
    }

    // lambdas and throws
    override fun enterFunctionLiteral(ctx: KotlinParser.FunctionLiteralContext) {
        require(complexityStack.isNotEmpty())
        currentComplexity.complexity++
    }

    // if & else if
    override fun enterIfExpression(ctx: KotlinParser.IfExpressionContext) {
        if (!ctx.text.contains("if")) return
        require(complexityStack.isNotEmpty())
        currentComplexity.complexity++
    }

    // when, only the individual conditions with the ->, called for each ->
    override fun enterWhenCondition(ctx: KotlinParser.WhenConditionContext) {
        require(complexityStack.isNotEmpty())
        currentComplexity.complexity++
    }

    // catch block
    override fun enterCatchBlock(ctx: KotlinParser.CatchBlockContext) {
        require(complexityStack.isNotEmpty())
        currentComplexity.complexity++
    }

    // do, while, and for
    override fun enterLoopExpression(ctx: KotlinParser.LoopExpressionContext) {
        require(complexityStack.isNotEmpty())
        currentComplexity.complexity++
    }

    init {
        ParseTreeWalker.DEFAULT.walk(this, source.getParsed(fileName).tree)
    }
}
