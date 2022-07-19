// ktlint-disable filename
@file:Suppress("MatchingDeclarationName")

package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser.AnonymousInitializerContext
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser.ClassBodyContext
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser.ControlStructureBodyContext
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser.FunctionBodyContext
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser.FunctionDeclarationContext
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser.StatementContext
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParserBaseListener
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.RuleContext
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.jetbrains.kotlin.backend.common.pop

private val BASIC_TYPES = setOf("Byte", "Short", "Int", "Long", "Float", "Double", "Char", "Boolean")
private val TYPE_CASTS = (BASIC_TYPES - setOf("Boolean")).map { "to$it" }.toSet()

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
        initCounter = 0
        featureStack.add(0, locatedClass)
    }

    private fun exitClassOrInterface(name: String, start: Location, end: Location) {
        assert(featureStack.isNotEmpty())
        val lastFeatures = featureStack.removeAt(0)
        assert(lastFeatures is ClassFeatures)
        lastFeatures.methods["init0"]?.also {
            require(!currentFeatures.methods.containsKey("init"))
            lastFeatures.methods["init"] = lastFeatures.methods
                .filterKeys { it.startsWith("init") }
                .values.map { (it as MethodFeatures).features }
                .reduce { first, second ->
                    first + second
                }.let { features ->
                    println(features.featureMap.map)
                    MethodFeatures(
                        name,
                        SourceRange(filename, source.mapLocation(filename, start), source.mapLocation(filename, end)),
                        features = features
                    )
                }
        }
        if (featureStack.isNotEmpty()) {
            currentFeatures.features += lastFeatures.features
        }
    }

    private fun KotlinParser.ClassDeclarationContext.isSnippetClass() = source is Snippet &&
        simpleIdentifier().text == source.wrappedClassName

    private fun FunctionDeclarationContext.isSnippetMethod() = source is Snippet &&
        fullName() == source.looseCodeMethodName

    private fun FunctionDeclarationContext.fullName(): String {
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
        exitClassOrInterface(
            ctx.simpleIdentifier().text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
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

    private var functionBlockDepths = mutableListOf<Int>()
    private val currentBlockDepth
        get() = functionBlockDepths.last()

    private var ifDepths = mutableListOf<Int>()
    private val ifDepth
        get() = ifDepths.last()

    override fun enterFunctionDeclaration(ctx: FunctionDeclarationContext) {
        if (!ctx.isSnippetMethod()) {
            count(FeatureName.METHOD)
        }
        enterMethodOrConstructor(
            ctx.fullName(),
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
        functionBlockDepths += 0
        ifDepths += 0

        if (ctx.parentType() == ParentType.FUNCTION) {
            count(FeatureName.NESTED_METHOD)
        }
    }

    override fun exitFunctionDeclaration(ctx: FunctionDeclarationContext) {
        exitMethodOrConstructor()
        val exitingBlockDepth = functionBlockDepths.pop()
        check(exitingBlockDepth == 0)
        val exitingIfDepth = ifDepths.pop()
        check(exitingIfDepth == 0)
    }

    private var initCounter = 0
    override fun enterAnonymousInitializer(ctx: KotlinParser.AnonymousInitializerContext) {
        ifDepths += 0
        functionBlockDepths += 0
        enterMethodOrConstructor(
            "init${initCounter++}",
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
    }

    override fun exitAnonymousInitializer(ctx: KotlinParser.AnonymousInitializerContext?) {
        exitMethodOrConstructor()
        val exitingBlockDepth = functionBlockDepths.pop()
        check(exitingBlockDepth == 0)
        val exitingIfDepth = ifDepths.pop()
        check(exitingIfDepth == 0)
    }

    override fun enterBlock(ctx: KotlinParser.BlockContext) {
        if (functionBlockDepths.isNotEmpty()) {
            functionBlockDepths[functionBlockDepths.size - 1]++
        }
    }

    override fun exitBlock(ctx: KotlinParser.BlockContext?) {
        if (functionBlockDepths.isNotEmpty()) {
            functionBlockDepths[functionBlockDepths.size - 1]--
        }
    }

    private enum class ParentType {
        FUNCTION, CLASS, NONE
    }

    private fun ParserRuleContext.parentContext(): RuleContext? {
        var currentParent = parent
        while (currentParent != null) {
            when (currentParent) {
                is FunctionBodyContext -> return currentParent
                is ClassBodyContext -> return currentParent
                is AnonymousInitializerContext -> return currentParent
            }
            currentParent = currentParent.parent
        }
        return null
    }

    private inline fun <reified T : RuleContext> ParserRuleContext.searchUp(): T? {
        var currentParent = parent
        while (currentParent != null) {
            currentParent = currentParent.parent
            if (currentParent is T) {
                return currentParent
            }
        }
        return null
    }

    private fun ParserRuleContext.parentType() = when (parentContext()) {
        is FunctionBodyContext -> ParentType.FUNCTION
        is AnonymousInitializerContext -> ParentType.FUNCTION
        is ClassBodyContext -> ParentType.CLASS
        else -> ParentType.NONE
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
            if (currentBlockDepth > 1) {
                count(FeatureName.NESTED_FOR)
            }
        }
        ctx.whileStatement()?.also {
            count(FeatureName.WHILE_LOOPS)
            if (currentBlockDepth > 1) {
                count(FeatureName.NESTED_WHILE)
            }
        }
        ctx.doWhileStatement()?.also {
            count(FeatureName.DO_WHILE_LOOPS)
            if (currentBlockDepth > 1) {
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

    private val topIfs = mutableSetOf<Int>()
    private val seenIfStarts = mutableSetOf<Int>()
    override fun enterIfExpression(ctx: KotlinParser.IfExpressionContext) {
        val parentStatement = ctx.searchUp<StatementContext>() ?: return // FIXME: Top-level if expressions
        val ifStart = ctx.start.startIndex
        if (parentStatement.assignment() == null && ifStart !in seenIfStarts) {
            count(FeatureName.IF_STATEMENTS)
            seenIfStarts += ifStart
            topIfs += ifStart
            if (ifDepth > 0) {
                count(FeatureName.NESTED_IF)
            }
            ifDepths[ifDepths.size - 1]++
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
            ifDepths[ifDepths.size - 1]--
            check(ifDepth >= 0)
        }
    }

    private val printStatements = setOf("println", "print")
    private val javaPrintStatements =
        setOf("System.out.println", "System.err.println", "System.out.print", "System.err.print")
    private val unnecessaryJavaPrintStatements = setOf("System.out.println", "System.out.print")
    override fun enterPostfixUnaryExpression(ctx: KotlinParser.PostfixUnaryExpressionContext) {
        for (i in 0 until ctx.postfixUnarySuffix().size) {
            val current = ctx.postfixUnarySuffix(i)
            if (current.navigationSuffix() == null) {
                continue
            }
            val next = if (i == ctx.postfixUnarySuffix().size - 1) {
                null
            } else {
                ctx.postfixUnarySuffix(i + 1)
            }
            if (current.navigationSuffix().memberAccessOperator()?.DOT() != null &&
                current.navigationSuffix().simpleIdentifier() != null
            ) {
                count(FeatureName.DOT_NOTATION)
                if (next?.callSuffix() != null) {
                    val identifier = current.navigationSuffix().simpleIdentifier().text
                    count(FeatureName.DOTTED_METHOD_CALL)
                    currentFeatures.features.dottedMethodList += identifier
                    if (identifier in TYPE_CASTS) {
                        count(FeatureName.PRIMITIVE_CASTING)
                    }
                } else {
                    count(FeatureName.DOTTED_VARIABLE_ACCESS)
                }
            }
        }
        if (printStatements.contains(ctx.primaryExpression()?.simpleIdentifier()?.text) &&
            ctx.postfixUnarySuffix().size == 1 &&
            ctx.postfixUnarySuffix(0).callSuffix() != null
        ) {
            count(FeatureName.PRINT_STATEMENTS)
        }
        if (ctx.primaryExpression().simpleIdentifier() != null &&
            ctx.postfixUnarySuffix().isNotEmpty() &&
            ctx.postfixUnarySuffix().last().callSuffix() != null &&
            ctx.postfixUnarySuffix().dropLast(1).all { it.navigationSuffix() != null }
        ) {
            val fullMethodCall = ctx.primaryExpression().simpleIdentifier().text +
                ctx.postfixUnarySuffix()
                    .dropLast(1)
                    .joinToString(".") {
                        it.navigationSuffix().simpleIdentifier().text
                    }.let {
                        if (it.isNotBlank()) {
                            ".$it"
                        } else {
                            ""
                        }
                    }
            if (javaPrintStatements.contains(fullMethodCall)) {
                count(FeatureName.PRINT_STATEMENTS)
                if (unnecessaryJavaPrintStatements.contains(fullMethodCall)) {
                    count(FeatureName.JAVA_PRINT_STATEMENTS)
                }
                count(FeatureName.DOTTED_VARIABLE_ACCESS, -1)
                count(FeatureName.DOTTED_METHOD_CALL, -1)
                count(FeatureName.DOT_NOTATION, -2)
            }
        }
    }

    override fun enterComparisonOperator(ctx: KotlinParser.ComparisonOperatorContext) {
        count(FeatureName.COMPARISON_OPERATORS)
    }

    override fun enterConjunction(ctx: KotlinParser.ConjunctionContext) {
        count(FeatureName.LOGICAL_OPERATORS, ctx.CONJ().size)
    }

    override fun enterDisjunction(ctx: KotlinParser.DisjunctionContext) {
        count(FeatureName.LOGICAL_OPERATORS, ctx.DISJ().size)
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

    override fun enterImportHeader(ctx: KotlinParser.ImportHeaderContext) {
        count(FeatureName.IMPORT)
        val importName = ctx.identifier().text + if (ctx.DOT() != null) {
            ".*"
        } else {
            ""
        }
        currentFeatures.features.importList += importName
    }

    override fun enterAsExpression(ctx: KotlinParser.AsExpressionContext) {
        ctx.type().forEach {
            if (it.text in BASIC_TYPES) {
                count(FeatureName.PRIMITIVE_CASTING)
            } else {
                count(FeatureName.CASTING)
            }
        }
    }

    override fun enterTypeTest(ctx: KotlinParser.TypeTestContext) {
        count(FeatureName.INSTANCEOF)
    }

    override fun enterInfixOperation(ctx: KotlinParser.InfixOperationContext) {
        count(FeatureName.INSTANCEOF, ctx.isOperator.size)
    }

    init {
        val parsedSource = source.getParsed(filename)
        // println(parsedSource.tree.format(parsedSource.parser))
        ParseTreeWalker.DEFAULT.walk(this, parsedSource.tree)
    }
}
