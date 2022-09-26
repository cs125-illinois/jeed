// ktlint-disable filename
@file:Suppress("MatchingDeclarationName")

package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParserBaseListener
import org.antlr.v4.runtime.tree.ParseTreeWalker

@Suppress("TooManyFunctions")
class KotlinComplexityListener(val source: Source, entry: Map.Entry<String, String>) :
    KotlinParserBaseListener() {
    private val filename = entry.key
    private var currentClass = ""

    private var anonymousClassDepth = 0
    private var objectLiteralCounter = 0

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
                SourceRange(filename, source.mapLocation(filename, start), source.mapLocation(filename, end))
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
                    SourceRange(filename, source.mapLocation(filename, start), source.mapLocation(filename, end))
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
        val name = ctx.simpleIdentifier().text
        val parameters = ctx.functionValueParameters().functionValueParameter()?.joinToString(",") {
            it.parameter().type().text
        }
        val returnType = ctx.type()?.text
        val longName = ("$name($parameters)${returnType?.let { ":$returnType" } ?: ""}").let {
            if (anonymousClassDepth > 0) {
                "${it}${"$"}$objectLiteralCounter"
            } else {
                it
            }
        }

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

    // ||, &&, ?:
    private var requireDepth = 0

    override fun enterExpression(ctx: KotlinParser.ExpressionContext) {
        ctx.DISJ()?.also {
            val conjunction = ctx.expression()[0].text
            if (conjunction.startsWith("require(") ||
                conjunction.startsWith("check(") ||
                conjunction.startsWith("assert(")
            ) {
                if (requireDepth == 0) {
                    currentComplexity.complexity++
                }
                requireDepth++
            }
            require(complexityStack.isNotEmpty())
            currentComplexity.complexity += 1
        }
        ctx.CONJ()?.also {
            require(complexityStack.isNotEmpty())
            currentComplexity.complexity += 1
        }
        ctx.elvis()?.also {
            require(complexityStack.isNotEmpty())
            currentComplexity.complexity += 1
        }
    }

    override fun exitExpression(ctx: KotlinParser.ExpressionContext) {
        ctx.DISJ()?.also {
            val conjunction = ctx.expression()[0].text
            if (conjunction.startsWith("require(") ||
                conjunction.startsWith("check(") ||
                conjunction.startsWith("assert(")
            ) {
                requireDepth--
            }
        }
    }

    /*
    override fun enterDisjunction(ctx: KotlinParser.DisjunctionContext) {
        val conjunction = ctx.conjunction()[0].text
        if (conjunction.startsWith("require(") ||
            conjunction.startsWith("check(") ||
            conjunction.startsWith("assert(")
        ) {
            if (requireDepth == 0) {
                currentComplexity.complexity++
            }
            requireDepth++
        }
        if (ctx.DISJ().isEmpty()) {
            return
        }
        require(complexityStack.isNotEmpty())
        currentComplexity.complexity += 1
    }
    */

    /*
    override fun exitDisjunction(ctx: KotlinParser.DisjunctionContext) {
        val conjunction = ctx.conjunction()[0].text
        if (conjunction.startsWith("require(") ||
            conjunction.startsWith("check(") ||
            conjunction.startsWith("assert(")
        ) {
            requireDepth--
        }
    }
    // &&
    override fun enterConjunction(ctx: KotlinParser.ConjunctionContext) {
        if (ctx.CONJ().isEmpty()) {
            return
        }
        require(complexityStack.isNotEmpty())
        currentComplexity.complexity += 1
    }
    */

    /*
    // ?:
    override fun enterElvisExpression(ctx: KotlinParser.ElvisExpressionContext) {
        if (ctx.elvis().isEmpty()) {
            return
        }
        require(complexityStack.isNotEmpty())
        currentComplexity.complexity++
    }
    */

    // lambdas and throws
    override fun enterFunctionLiteral(ctx: KotlinParser.FunctionLiteralContext) {
        require(complexityStack.isNotEmpty())
        // TODO: For Ben to rethink...
        // currentComplexity.complexity++
    }

    private var inIfExpression = false

    // if & else if
    override fun enterIfExpression(ctx: KotlinParser.IfExpressionContext) {
        if (!ctx.text.contains("if")) {
            return
        }
        require(complexityStack.isNotEmpty())
        currentComplexity.complexity++
        inIfExpression = true
    }

    override fun exitIfExpression(ctx: KotlinParser.IfExpressionContext) {
        inIfExpression = false
    }

    // when, called for each ->, except the default one
    override fun enterWhenCondition(ctx: KotlinParser.WhenConditionContext) {
        require(complexityStack.isNotEmpty())
        currentComplexity.complexity++
        inIfExpression = true
    }

    override fun exitWhenCondition(ctx: KotlinParser.WhenConditionContext) {
        inIfExpression = false
    }

    // catch block
    override fun enterCatchBlock(ctx: KotlinParser.CatchBlockContext) {
        require(complexityStack.isNotEmpty())
        currentComplexity.complexity++
    }

    // do, while, and for
    override fun enterLoopStatement(ctx: KotlinParser.LoopStatementContext) {
        require(complexityStack.isNotEmpty())
        currentComplexity.complexity++
    }

    override fun enterJumpExpression(ctx: KotlinParser.JumpExpressionContext) {
        if (ctx.THROW() != null && requireDepth == 0) {
            require(complexityStack.isNotEmpty())
            currentComplexity.complexity++
        }
    }

    // ?.
    override fun enterMemberAccessOperator(ctx: KotlinParser.MemberAccessOperatorContext) {
        if (ctx.text != "?.") {
            return
        }
        require(complexityStack.isNotEmpty())
        currentComplexity.complexity++
    }

    // !!.
    override fun enterPostfixUnaryOperator(ctx: KotlinParser.PostfixUnaryOperatorContext) {
        if (ctx.text != "!!") {
            return
        }
        require(complexityStack.isNotEmpty())
        currentComplexity.complexity++
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
        ParseTreeWalker.DEFAULT.walk(this, source.getParsed(filename).tree)
    }
}
