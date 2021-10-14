package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.JavaLexer
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParserBaseListener
import org.antlr.v4.runtime.tree.ParseTreeWalker

private val basicComplexityTokens = listOf(JavaLexer.FOR, JavaLexer.WHILE, JavaLexer.DO, JavaLexer.THROW)
private val complexityExpressionBOPs = listOf(JavaLexer.AND, JavaLexer.OR, JavaLexer.QUESTION)

@Suppress("TooManyFunctions")
class JavaComplexityListener(val source: Source, entry: Map.Entry<String, String>) : JavaParserBaseListener() {
    @Suppress("unused")
    private val contents = entry.value
    private val filename = entry.key

    private var complexityStack: MutableList<ComplexityValue> = mutableListOf()
    private val currentComplexity: ComplexityValue
        get() = complexityStack[0]
    var results: MutableMap<String, ClassComplexity> = mutableMapOf()

    private var anonymousClassDepth = 0

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
            assert(!currentComplexity.classes.containsKey(locatedClass.name))
            currentComplexity.classes[locatedClass.name] = locatedClass
        }
        complexityStack.add(0, locatedClass)
    }

    private fun enterMethodOrConstructor(name: String, start: Location, end: Location) {
        val locatedMethod =
            if (source is Snippet &&
                "void " + source.looseCodeMethodName == name &&
                (complexityStack.getOrNull(0) as? ClassComplexity)?.name == ""
            ) {
                MethodComplexity("", source.snippetRange).also {
                    it.complexity = 0
                }
            } else {
                MethodComplexity(
                    name,
                    SourceRange(name, source.mapLocation(filename, start), source.mapLocation(filename, end))
                )
            }
        if (complexityStack.isNotEmpty()) {
            assert(!currentComplexity.methods.containsKey(locatedMethod.name)) {
                "Already saw ${locatedMethod.name}"
            }
            currentComplexity.methods[locatedMethod.name] = locatedMethod
        }
        complexityStack.add(0, locatedMethod)
    }

    private fun exitClassOrInterface() {
        assert(complexityStack.isNotEmpty())
        val lastComplexity = complexityStack.removeAt(0)
        assert(lastComplexity is ClassComplexity)
        if (complexityStack.isNotEmpty()) {
            currentComplexity.complexity += lastComplexity.complexity
        } else {
            val topLevelClassComplexity = lastComplexity as ClassComplexity
            assert(!results.keys.contains(topLevelClassComplexity.name))
            results[topLevelClassComplexity.name] = topLevelClassComplexity
        }
    }

    private fun exitMethodOrConstructor() {
        assert(complexityStack.isNotEmpty())
        val lastComplexity = complexityStack.removeAt(0)
        assert(lastComplexity is MethodComplexity)
        assert(complexityStack.isNotEmpty())
        currentComplexity.complexity += lastComplexity.complexity
    }

    override fun enterClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
        enterClassOrInterface(
            ctx.identifier().text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
    }

    override fun exitClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
        exitClassOrInterface()
    }

    override fun enterInterfaceDeclaration(ctx: JavaParser.InterfaceDeclarationContext) {
        enterClassOrInterface(
            ctx.identifier().text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
    }

    override fun exitInterfaceDeclaration(ctx: JavaParser.InterfaceDeclarationContext?) {
        exitClassOrInterface()
    }

    override fun enterRecordDeclaration(ctx: JavaParser.RecordDeclarationContext) {
        enterClassOrInterface(
            ctx.identifier().text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
    }

    override fun exitRecordDeclaration(ctx: JavaParser.RecordDeclarationContext?) {
        exitClassOrInterface()
    }

    override fun enterMethodDeclaration(ctx: JavaParser.MethodDeclarationContext) {
        if (anonymousClassDepth > 0) {
            currentComplexity.complexity++
            return
        }
        val parameters = ctx.formalParameters().formalParameterList()?.formalParameter()?.joinToString(",") {
            it.typeType().text
        } ?: ""
        enterMethodOrConstructor(
            "${ctx.typeTypeOrVoid().text} ${ctx.identifier().text}($parameters)",
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
    }

    override fun exitMethodDeclaration(ctx: JavaParser.MethodDeclarationContext) {
        if (anonymousClassDepth > 0) {
            return
        }
        exitMethodOrConstructor()
    }

    override fun enterConstructorDeclaration(ctx: JavaParser.ConstructorDeclarationContext) {
        assert(complexityStack.isNotEmpty())
        val currentClass = currentComplexity as ClassComplexity
        val parameters = ctx.formalParameters().formalParameterList()?.formalParameter()?.joinToString(",") {
            it.typeType().text
        } ?: ""
        enterMethodOrConstructor(
            "${currentClass.name}($parameters)",
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine),
        )
    }

    override fun exitConstructorDeclaration(ctx: JavaParser.ConstructorDeclarationContext?) {
        exitMethodOrConstructor()
    }

    override fun enterLambdaExpression(ctx: JavaParser.LambdaExpressionContext) {
        assert(complexityStack.isNotEmpty())
        (currentComplexity as MethodComplexity).complexity++
    }

    override fun enterStatement(ctx: JavaParser.StatementContext) {
        assert(complexityStack.isNotEmpty())
        val firstToken = ctx.getStart() ?: error("can't get first token in statement")
        if (basicComplexityTokens.contains(firstToken.type)) {
            // for, while, do and throw each represent one new path
            currentComplexity.complexity++
        } else if (firstToken.type == JavaLexer.IF) {
            /*
             * if statements only ever add one unit of complexity. If no else is present then we either enter
             * the condition or not, adding one path.
             * If else is present then we either take the condition or the else, adding one path.
             */
            currentComplexity.complexity++
        }
    }

    override fun enterExpression(ctx: JavaParser.ExpressionContext) {
        assert(complexityStack.isNotEmpty())
        // Ignore expressions in class declarations
        if (currentComplexity is ClassComplexity) {
            return
        }
        val bop = ctx.bop?.type ?: return
        // &&, ||, and ? each represent one new path
        if (complexityExpressionBOPs.contains(bop)) {
            currentComplexity.complexity++
        }
    }

    // Each switch label that is not default represents one new path
    override fun enterSwitchLabel(ctx: JavaParser.SwitchLabelContext) {
        assert(complexityStack.isNotEmpty())
        val currentMethod = currentComplexity as MethodComplexity
        if (ctx.DEFAULT() == null) {
            currentMethod.complexity++
        }
    }

    override fun enterSwitchExpressionLabel(ctx: JavaParser.SwitchExpressionLabelContext) {
        assert(complexityStack.isNotEmpty())
        val currentMethod = currentComplexity as MethodComplexity
        if (ctx.DEFAULT() == null) {
            currentMethod.complexity++
        }
    }

    // Each throws clause in the method declaration indicates one new path
    override fun enterQualifiedNameList(ctx: JavaParser.QualifiedNameListContext) {
        assert(complexityStack.isNotEmpty())
        val currentMethod = currentComplexity as MethodComplexity
        currentMethod.complexity += ctx.children.size
    }

    // Each catch clause represents one new path
    override fun enterCatchClause(ctx: JavaParser.CatchClauseContext) {
        assert(complexityStack.isNotEmpty())
        val currentMethod = currentComplexity as MethodComplexity
        currentMethod.complexity++
    }

    override fun enterClassCreatorRest(ctx: JavaParser.ClassCreatorRestContext) {
        if (ctx.classBody() != null) {
            anonymousClassDepth++
        }
    }

    override fun exitClassCreatorRest(ctx: JavaParser.ClassCreatorRestContext) {
        if (ctx.classBody() != null) {
            anonymousClassDepth--
        }
    }

    init {
        ParseTreeWalker.DEFAULT.walk(this, source.getParsed(filename).tree)
    }
}
