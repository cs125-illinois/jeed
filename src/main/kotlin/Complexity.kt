package edu.illinois.cs.cs125.jeed

import edu.illinois.cs.cs125.jeed.antlr.*
import mu.KotlinLogging
import org.antlr.v4.runtime.tree.ParseTreeWalker

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

val complexityTokens = listOf(
    "if", "else", "for", "while", "do", "case", "catch", "return", // control flow
    "break", "continue", // only when in loops, not switch blocks
    "&&", "||", "?" // operators
)

class ComplexityListener(val source: String) : JavaParserBaseListener() {
    var totalComplexity = 0

    var currentClassOrInterface: String? = null
    var currentClassOrInterfaceLocation: SourceLocation? = null
    private fun enterClassOrInterface(name: String, line: Int, column: Int) {
        currentClassOrInterface = name
        currentClassOrInterfaceLocation = SourceLocation(source, line, column)
    }
    private fun exitClassOrInterface() {
        currentClassOrInterface = null
    }

    override fun enterClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
        enterClassOrInterface(ctx.children[1].text, ctx.start.line, ctx.start.charPositionInLine)
    }
    override fun exitClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
        exitClassOrInterface()
    }
    override fun enterInterfaceDeclaration(ctx: JavaParser.InterfaceDeclarationContext) {
        enterClassOrInterface(ctx.children[1].text, ctx.start.line, ctx.start.charPositionInLine)
    }
    override fun exitInterfaceDeclaration(ctx: JavaParser.InterfaceDeclarationContext?) {
        exitClassOrInterface()
    }

    var currentMethod: String? = null
    var currentMethodLocation: SourceLocation? = null
    var currentMethodReturnType: String? = null
    var currentMethodParameters: MutableList<String>? = null
    var currentMethodComplexity = 0

    private fun enterMethodOrConstructor(name: String, returnType: String?, line: Int, column: Int) {
        assert(currentMethod == null)
        currentMethod = name
        currentMethodReturnType = returnType
        currentMethodLocation = SourceLocation(source, line, column)
        currentMethodParameters = mutableListOf()
    }
    private fun exitMethodOrConstructor() {
        val fullMethodName = "$currentClassOrInterface.$currentMethod(${currentMethodParameters?.joinToString(separator = ", ")})"
        logger.debug(fullMethodName)
        assert(currentMethod != null)
        currentMethod = null
        currentMethodReturnType = null
        currentMethodLocation = null
        currentMethodParameters = null
    }

    override fun enterMethodDeclaration(ctx: JavaParser.MethodDeclarationContext) {
        enterMethodOrConstructor(ctx.children[1].text, ctx.children[0].text, ctx.start.line, ctx.start.charPositionInLine)
    }
    override fun exitMethodDeclaration(ctx: JavaParser.MethodDeclarationContext) {
        exitMethodOrConstructor()
    }
    override fun enterConstructorDeclaration(ctx: JavaParser.ConstructorDeclarationContext) {
        enterMethodOrConstructor(currentClassOrInterface ?: error(""), null, ctx.start.line, ctx.start.charPositionInLine)
    }
    override fun exitConstructorDeclaration(ctx: JavaParser.ConstructorDeclarationContext?) {
        exitMethodOrConstructor()
    }
    override fun enterFormalParameter(ctx: JavaParser.FormalParameterContext) {
        if (!insideLambda) {
            assert(ctx.children.size >= 2)
            currentMethodParameters?.add(ctx.children[ctx.children.lastIndex - 1].text)
        }
    }
    override fun enterLastFormalParameter(ctx: JavaParser.LastFormalParameterContext) {
        if (!insideLambda) {
            assert(ctx.children.size >= 2)
            val type = ctx.children[ctx.children.lastIndex - 1].text
            if (type != "...") {
                currentMethodParameters?.add(type)
            } else {
                assert(ctx.children.size > 3)
                currentMethodParameters?.add("...${ctx.children[ctx.children.lastIndex - 2].text}")
            }
        }
    }

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
}

class ComplexityResult(source: String) {
    val complexity = 1

    data class MethodComplexity(val name: String, val complexity: Int)
    val methods: MutableList<MethodComplexity> = mutableListOf()

    init {
        val parseTree = parseCompilationUnit(source)
        ParseTreeWalker.DEFAULT.walk(ComplexityListener(source), parseTree)
    }
}
data class ComplexityResults(val results: Map<String, ComplexityResult>)

@Throws(JavaParsingFailed::class)
fun Source.complexity(names: Set<String> = this.sources.keys.toSet()): ComplexityResults {
    return ComplexityResults(this.sources.filter {
        names.contains(it.key)
    }.mapValues {
        ComplexityResult(it.value)
    })
}
