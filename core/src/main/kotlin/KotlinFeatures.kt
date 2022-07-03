// ktlint-disable filename
@file:Suppress("MatchingDeclarationName")

package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser.ControlStructureBodyContext
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser.FunctionBodyContext
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser.StatementContext
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

    private fun count(feature: FeatureName, amount: Int = 1) {
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
            count(FeatureName.CLASS)
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

    private var functionBlockDepth = 0
    override fun enterFunctionDeclaration(ctx: KotlinParser.FunctionDeclarationContext) {
        enterMethodOrConstructor(
            ctx.fullName(),
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
        functionBlockDepth = 0
    }

    override fun exitFunctionDeclaration(ctx: KotlinParser.FunctionDeclarationContext) {
        exitMethodOrConstructor()
        check(functionBlockDepth == 0)
        functionBlockDepth = -1
    }

    override fun enterBlock(ctx: KotlinParser.BlockContext) {
        if (functionBlockDepth != -1) {
            functionBlockDepth++
        }
    }

    override fun exitBlock(ctx: KotlinParser.BlockContext?) {
        if (functionBlockDepth != -1) {
            functionBlockDepth--
        }
    }

    private enum class ParentType {
        FUNCTION, CLASS, NONE
    }

    private fun ParserRuleContext.parentType(): ParentType {
        var currentParent = parent
        while (currentParent != null) {
            when (currentParent) {
                is FunctionBodyContext -> return ParentType.FUNCTION
            }
            currentParent = currentParent.parent
        }
        return ParentType.NONE
    }

    private fun ParserRuleContext.parentStatement(): StatementContext? {
        var currentParent = parent
        while (currentParent != null) {
            when (currentParent) {
                is StatementContext -> return currentParent
            }
            currentParent = currentParent.parent
        }
        return null
    }

    override fun enterVariableDeclaration(ctx: KotlinParser.VariableDeclarationContext) {
        if (ctx.parent is KotlinParser.PropertyDeclarationContext && ctx.parentType() == ParentType.FUNCTION) {
            count(FeatureName.LOCAL_VARIABLE_DECLARATIONS)
            count(FeatureName.VARIABLE_ASSIGNMENTS)
        }
    }

    override fun enterAssignment(ctx: KotlinParser.AssignmentContext) {
        if (ctx.parentType() == ParentType.FUNCTION) {
            count(FeatureName.VARIABLE_REASSIGNMENTS)
        }
    }

    override fun enterPrefixUnaryOperator(ctx: KotlinParser.PrefixUnaryOperatorContext) {
        if (ctx.parentType() == ParentType.FUNCTION && (ctx.INCR() != null || ctx.DECR() != null)) {
            count(FeatureName.VARIABLE_REASSIGNMENTS)
        }
    }

    override fun enterPostfixUnaryOperator(ctx: KotlinParser.PostfixUnaryOperatorContext) {
        if (ctx.parentType() == ParentType.FUNCTION && (ctx.INCR() != null || ctx.DECR() != null)) {
            count(FeatureName.VARIABLE_REASSIGNMENTS)
        }
    }

    override fun enterLoopStatement(ctx: KotlinParser.LoopStatementContext) {
        if (ctx.parentType() != ParentType.FUNCTION) {
            return
        }
        ctx.forStatement()?.also {
            count(FeatureName.FOR_LOOPS)
            if (functionBlockDepth > 1) {
                count(FeatureName.NESTED_FOR)
            }
        }
        ctx.whileStatement()?.also {
            count(FeatureName.WHILE_LOOPS)
            if (functionBlockDepth > 1) {
                count(FeatureName.NESTED_WHILE)
            }
        }
        ctx.doWhileStatement()?.also {
            count(FeatureName.DO_WHILE_LOOPS)
            if (functionBlockDepth > 1) {
                count(FeatureName.NESTED_DO_WHILE)
            }
        }
    }

    override fun enterSimpleIdentifier(ctx: KotlinParser.SimpleIdentifierContext) {
        if (ctx.Identifier()?.text == "arrayOf" ||
            ctx.Identifier()?.text == "Array" ||
            ctx.Identifier()?.text?.endsWith("ArrayOf") == true
        ) {
            count(FeatureName.ARRAYS)
        }
    }

    // Gotta love this grammar
    private fun ControlStructureBodyContext.isIf() = statement()
        ?.expression()
        ?.disjunction()
        ?.conjunction()?.first()
        ?.equality()?.first()
        ?.comparison()?.first()
        ?.genericCallLikeComparison()?.first()
        ?.infixOperation()
        ?.elvisExpression()?.first()
        ?.infixFunctionCall()?.first()
        ?.rangeExpression()?.first()
        ?.additiveExpression()?.first()
        ?.multiplicativeExpression()?.first()
        ?.asExpression()?.first()
        ?.prefixUnaryExpression()
        ?.postfixUnaryExpression()
        ?.primaryExpression()
        ?.ifExpression() != null

    private var ifDepth = 0
    private val topIfs = mutableSetOf<Int>()
    private val seenIfStarts = mutableSetOf<Int>()
    override fun enterIfExpression(ctx: KotlinParser.IfExpressionContext) {
        val parentStatement = ctx.parentStatement() ?: return
        val ifStart = ctx.start.startIndex
        if (parentStatement.assignment() == null && ifStart !in seenIfStarts) {
            count(FeatureName.IF_STATEMENTS)
            seenIfStarts += ifStart
            topIfs += ifStart
            if (ifDepth > 0) {
                count(FeatureName.NESTED_IF)
            }
            ifDepth++
        }
        ctx.controlStructureBody().forEach {
            if (it.isIf()) {
                count(FeatureName.ELSE_IF)
                seenIfStarts += it.start.startIndex
            }
        }
        if (ctx.ELSE() != null && ctx.controlStructureBody()!!.last().block() != null) {
            count(FeatureName.ELSE_STATEMENTS)
        }
    }

    override fun exitIfExpression(ctx: KotlinParser.IfExpressionContext) {
        if (ctx.start.startIndex in topIfs) {
            ifDepth--
            check(ifDepth >= 0)
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
