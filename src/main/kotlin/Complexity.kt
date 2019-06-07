package edu.illinois.cs.cs125.jeed

import edu.illinois.cs.cs125.jeed.antlr.*
import mu.KotlinLogging
import org.antlr.v4.runtime.tree.ParseTreeWalker

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

val basicComplexityTokens = listOf(JavaLexer.FOR, JavaLexer.WHILE, JavaLexer.DO, JavaLexer.THROW)
val complexityExpressionBOPs = listOf(JavaLexer.AND, JavaLexer.OR, JavaLexer.QUESTION)

class ComplexityResult(val source: Map.Entry<String, String>) : JavaParserBaseListener() {

    data class LocatedClass(val name: String, val range: SourceRange)
    data class LocatedMethod(var name: String, val range: SourceRange, val klass: LocatedClass)

    data class Result(val method: LocatedMethod, val complexity: Int)
    val results: MutableList<Result> = mutableListOf()

    var currentClassOrInterface: MutableList<LocatedClass> = mutableListOf()
    private fun enterClassOrInterface(locatedClass: LocatedClass) {
        currentClassOrInterface.add(locatedClass)
    }
    private fun exitClassOrInterface() {
        assert(currentClassOrInterface.isNotEmpty())
        currentClassOrInterface.dropLast(1)
    }

    override fun enterClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
        enterClassOrInterface(
                LocatedClass(ctx.children[1].text,
                        SourceRange(source.key,
                                Location(ctx.start.line, ctx.start.charPositionInLine),
                                Location(ctx.stop.line, ctx.stop.charPositionInLine
                                )
                        )
                )
        )
    }
    override fun exitClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
        exitClassOrInterface()
    }
    override fun enterInterfaceDeclaration(ctx: JavaParser.InterfaceDeclarationContext) {
        enterClassOrInterface(LocatedClass(ctx.children[1].text,
                SourceRange(source.key, Location(ctx.start.line, ctx.start.charPositionInLine), Location(ctx.stop.line, ctx.stop.charPositionInLine)))
        )
    }
    override fun exitInterfaceDeclaration(ctx: JavaParser.InterfaceDeclarationContext?) {
        exitClassOrInterface()
    }

    var currentMethod: LocatedMethod? = null
    var currentMethodReturnType: String? = null
    var currentMethodParameters: MutableList<String> = mutableListOf()
    var currentMethodComplexity = 0

    private fun enterMethodOrConstructor(name: String, start: Location, end: Location, returnType: String?) {
        assert(currentMethod == null)
        assert(currentClassOrInterface.isNotEmpty())

        val klass = LocatedClass(
                currentClassOrInterface.joinToString(separator = ".") { it.name },
                currentClassOrInterface.last().range
        )

        currentMethod = LocatedMethod(name, SourceRange(source.key, start, end), klass)
        currentMethodReturnType = returnType
        currentMethodParameters = mutableListOf()
        // Methods start at complexity 1
        currentMethodComplexity = 1
    }
    private fun exitMethodOrConstructor() {
        assert(currentMethod != null)

        val fullMethodName = "${currentMethod?.name}(${currentMethodParameters.joinToString(separator = ", ")})"
        currentMethod?.name = fullMethodName
        results.add(Result(
                currentMethod ?: error("should have a located method here"),
                currentMethodComplexity
        ))

        currentMethod = null
        currentMethodReturnType = null
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
        enterMethodOrConstructor(
                currentClassOrInterface.joinToString(separator = ".") { it.name },
                Location(ctx.start.line, ctx.start.charPositionInLine),
                Location(ctx.stop.line, ctx.stop.charPositionInLine),
                null
        )
    }
    override fun exitConstructorDeclaration(ctx: JavaParser.ConstructorDeclarationContext?) {
        exitMethodOrConstructor()
    }
    override fun enterFormalParameter(ctx: JavaParser.FormalParameterContext) {
        if (!insideLambda) {
            assert(ctx.children.size >= 2)
            currentMethodParameters.add(ctx.children[ctx.children.lastIndex - 1].text)
        }
    }
    override fun enterLastFormalParameter(ctx: JavaParser.LastFormalParameterContext) {
        if (!insideLambda) {
            assert(ctx.children.size >= 2)
            val type = ctx.children[ctx.children.lastIndex - 1].text
            if (type != "...") {
                currentMethodParameters.add(type)
            } else {
                assert(ctx.children.size > 3)
                currentMethodParameters.add("...${ctx.children[ctx.children.lastIndex - 2].text}")
            }
        }
    }

    override fun enterStatement(ctx: JavaParser.StatementContext) {
        assert(currentMethod != null)

        val firstToken = ctx.getStart() ?: error("can't get first token in statement")

        // for, while, do and throw each represent one new path
        if (basicComplexityTokens.contains(firstToken.type)) {
            currentMethodComplexity++
        }

        // if statements add a number of paths equal to the number of arms
        if (firstToken.type == JavaLexer.IF) {
            val statementLength = ctx.childCount
            assert(statementLength % 2 == 0)
            currentMethodComplexity += statementLength / 2
        }

    }
    override fun enterExpression(ctx: JavaParser.ExpressionContext) {
        assert(currentMethod != null)

        val bop = ctx.bop?.type ?: return

        // &&, ||, and ? each represent one new path
        if (complexityExpressionBOPs.contains(bop)) {
            currentMethodComplexity++
        }
    }

    // Each switch label represents one new path
    override fun enterSwitchLabel(ctx: JavaParser.SwitchLabelContext) {
        assert(currentMethod != null)
        currentMethodComplexity++
    }

    // Each throws clause in the method declaration indicates one new path
    override fun enterQualifiedNameList(ctx: JavaParser.QualifiedNameListContext) {
        assert(currentMethod != null)
        currentMethodComplexity += ctx.children.size
    }
    // Each catch clause represents one new path
    override fun enterCatchClause(ctx: JavaParser.CatchClauseContext) {
        assert(currentMethod != null)
        currentMethodComplexity++
    }

    // Ignore argument lists that are part of lambda expressions
    private var insideLambda = false
    override fun enterLambdaExpression(ctx: JavaParser.LambdaExpressionContext?) {
        insideLambda = true
    }
    override fun exitLambdaExpression(ctx: JavaParser.LambdaExpressionContext?) {
        insideLambda = false
    }

    private var insideSwitch = false
    override fun enterSwitchBlockStatementGroup(ctx: JavaParser.SwitchBlockStatementGroupContext) {
        insideSwitch = true
    }
    override fun exitSwitchBlockStatementGroup(ctx: JavaParser.SwitchBlockStatementGroupContext) {
        insideSwitch = false
    }

    init {
        val parseTree = parseCompilationUnit(source.value)
        ParseTreeWalker.DEFAULT.walk(this, parseTree)
        println(results.joinToString(separator = "\n"))
    }
}

data class ComplexityResults(val results: Map<String, ComplexityResult>)

@Throws(JavaParsingFailed::class)
fun Source.complexity(names: Set<String> = this.sources.keys.toSet()): ComplexityResults {
    return ComplexityResults(this.sources.filter {
        names.contains(it.key)
    }.mapValues {
        ComplexityResult(it)
    })
}
