// ktlint-disable filename
@file:Suppress("MatchingDeclarationName")

package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParserBaseListener
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.TerminalNode
import org.jetbrains.kotlin.backend.common.pop

@Suppress("TooManyFunctions")
class KotlinMutationListener(private val parsedSource: Source.ParsedSource) : KotlinParserBaseListener() {
    val lines = parsedSource.contents.lines()
    val mutations: MutableList<Mutation> = mutableListOf()

    private val returnTypeStack: MutableList<String> = mutableListOf()
    private val currentReturnType: String?
        get() = returnTypeStack.lastOrNull()

    override fun enterFunctionDeclaration(ctx: KotlinParser.FunctionDeclarationContext) {
        val returnType = ctx.type()?.text ?: ""
        returnTypeStack.add(returnType)
    }

    override fun exitFunctionDeclaration(ctx: KotlinParser.FunctionDeclarationContext) {
        check(returnTypeStack.isNotEmpty()) { "Return type stack should not be empty" }
        returnTypeStack.pop()
    }

    private var inSetterOrGetter = false
    override fun enterGetter(ctx: KotlinParser.GetterContext) {
        check(!inSetterOrGetter)
        inSetterOrGetter = true
    }

    override fun exitGetter(ctx: KotlinParser.GetterContext) {
        check(inSetterOrGetter)
        inSetterOrGetter = false
    }

    override fun enterSetter(ctx: KotlinParser.SetterContext) {
        check(!inSetterOrGetter)
        inSetterOrGetter = true
    }

    override fun exitSetter(ctx: KotlinParser.SetterContext) {
        check(inSetterOrGetter)
        inSetterOrGetter = false
    }

    override fun enterFunctionBody(ctx: KotlinParser.FunctionBodyContext) {
        if (inSetterOrGetter) {
            return
        }
        check(currentReturnType != null)
        val methodLocation = ctx.block()?.toLocation() ?: return
        val methodContents = parsedSource.contents(methodLocation)
        if (RemoveMethod.matches(methodContents, currentReturnType!!, Source.FileType.KOTLIN)) {
            mutations.add(RemoveMethod(methodLocation, methodContents, currentReturnType!!, Source.FileType.KOTLIN))
        }
    }

    override fun enterJumpExpression(ctx: KotlinParser.JumpExpressionContext) {
        if (ctx.RETURN() != null && ctx.expression() != null && !inSetterOrGetter) {
            val returnToken = ctx.expression()
            val returnLocation = returnToken.toLocation()
            if (PrimitiveReturn.matches(returnToken.text, currentReturnType!!, Source.FileType.KOTLIN)) {
                mutations.add(
                    PrimitiveReturn(
                        returnLocation,
                        parsedSource.contents(returnLocation),
                        Source.FileType.KOTLIN
                    )
                )
            }
            if (TrueReturn.matches(returnToken.text, currentReturnType!!)) {
                mutations.add(TrueReturn(returnLocation, parsedSource.contents(returnLocation), Source.FileType.KOTLIN))
            }
            if (FalseReturn.matches(returnToken.text, currentReturnType!!)) {
                mutations.add(
                    FalseReturn(
                        returnLocation,
                        parsedSource.contents(returnLocation),
                        Source.FileType.KOTLIN
                    )
                )
            }
            if (NullReturn.matches(returnToken.text, currentReturnType!!, Source.FileType.KOTLIN)) {
                mutations.add(NullReturn(returnLocation, parsedSource.contents(returnLocation), Source.FileType.KOTLIN))
            }
        }
        ctx.BREAK()?.also {
            mutations.add(
                SwapBreakContinue(
                    ctx.toLocation(),
                    parsedSource.contents(ctx.toLocation()),
                    Source.FileType.KOTLIN
                )
            )
        }
        ctx.CONTINUE()?.also {
            mutations.add(
                SwapBreakContinue(
                    ctx.toLocation(),
                    parsedSource.contents(ctx.toLocation()),
                    Source.FileType.KOTLIN
                )
            )
        }
    }

    override fun enterStatement(ctx: KotlinParser.StatementContext) {
        val statementLocation = ctx.toLocation()
        if (ctx.start.text == "assert" ||
            ctx.start.text == "check" ||
            ctx.start.text == "require"
        ) {
            mutations.add(
                RemoveRuntimeCheck(
                    statementLocation,
                    parsedSource.contents(statementLocation),
                    Source.FileType.KOTLIN
                )
            )
        }
        if (ctx.declaration() == null) {
            mutations.add(
                RemoveStatement(
                    statementLocation,
                    parsedSource.contents(statementLocation),
                    Source.FileType.KOTLIN
                )
            )
        }
    }

    private var insideAnnotation = false
    override fun enterAnnotation(ctx: KotlinParser.AnnotationContext?) {
        insideAnnotation = true
    }

    override fun exitAnnotation(ctx: KotlinParser.AnnotationContext?) {
        insideAnnotation = false
    }

    private fun ParserRuleContext.toLocation() =
        Mutation.Location(
            start.startIndex,
            stop.stopIndex,
            lines.filterIndexed { index, _ -> index >= start.line - 1 && index <= stop.line - 1 }
                .joinToString("\n"),
            start.line,
            stop.line
        )

    private fun List<TerminalNode>.toLocation() =
        Mutation.Location(
            first().symbol.startIndex,
            last().symbol.stopIndex,
            lines.filterIndexed { index, _ -> index >= first().symbol.line - 1 && index <= last().symbol.line - 1 }
                .joinToString("\n"),
            first().symbol.line,
            last().symbol.line
        )

    override fun enterLineStringContent(ctx: KotlinParser.LineStringContentContext) {
        if (insideAnnotation) {
            return
        }
        ctx.toLocation().also { location ->
            val contents = parsedSource.contents(location)
            mutations.addStringMutations(location, contents, Source.FileType.KOTLIN, false)
        }
    }

    override fun enterMultiLineStringContent(ctx: KotlinParser.MultiLineStringContentContext) {
        if (insideAnnotation) {
            return
        }
        ctx.toLocation().also { location ->
            val contents = parsedSource.contents(location)
            mutations.addStringMutations(location, contents, Source.FileType.KOTLIN, false)
        }
    }

    override fun enterLineStringLiteral(ctx: KotlinParser.LineStringLiteralContext) {
        if (insideAnnotation) {
            return
        }
        if (ctx.lineStringContent().isEmpty() && ctx.lineStringExpression().isEmpty()) {
            ctx.toLocation().also { location ->
                val contents = parsedSource.contents(location)
                mutations.addStringMutations(location, contents, Source.FileType.KOTLIN)
            }
        }
    }

    @Suppress("ComplexMethod")
    override fun enterLiteralConstant(ctx: KotlinParser.LiteralConstantContext) {
        if (insideAnnotation) {
            return
        }
        ctx.BooleanLiteral()?.also {
            ctx.toLocation().also { location ->
                mutations.add(BooleanLiteral(location, parsedSource.contents(location), Source.FileType.KOTLIN))
            }
        }
        ctx.IntegerLiteral()?.also {
            ctx.toLocation().also { location ->
                val content = parsedSource.contents(location)
                mutations.add(NumberLiteral(location, content, Source.FileType.KOTLIN))
                if (NumberLiteralTrim.matches(content)) {
                    mutations.add(NumberLiteralTrim(location, content, Source.FileType.KOTLIN))
                }
            }
        }
        @Suppress("MagicNumber")
        ctx.HexLiteral()?.also {
            ctx.toLocation().also { location ->
                val content = parsedSource.contents(location)
                mutations.add(NumberLiteral(location, content, Source.FileType.KOTLIN))
                if (NumberLiteralTrim.matches(content, 16)) {
                    mutations.add(NumberLiteralTrim(location, content, Source.FileType.KOTLIN, 16))
                }
            }
        }
        ctx.BinLiteral()?.also {
            ctx.toLocation().also { location ->
                val content = parsedSource.contents(location)
                mutations.add(NumberLiteral(location, content, Source.FileType.KOTLIN))
                if (NumberLiteralTrim.matches(content, 2)) {
                    mutations.add(NumberLiteralTrim(location, content, Source.FileType.KOTLIN, 2))
                }
            }
        }
        ctx.CharacterLiteral()?.also {
            ctx.toLocation().also { location ->
                mutations.add(CharLiteral(location, parsedSource.contents(location), Source.FileType.KOTLIN))
            }
        }
        // reals are doubles and floats
        ctx.RealLiteral()?.also {
            ctx.toLocation().also { location ->
                val content = parsedSource.contents(location)
                mutations.add(NumberLiteral(location, content, Source.FileType.KOTLIN))
                if (NumberLiteralTrim.matches(content)) {
                    mutations.add(NumberLiteralTrim(location, content, Source.FileType.KOTLIN))
                }
            }
        }
        ctx.LongLiteral()?.also {
            ctx.toLocation().also { location ->
                val content = parsedSource.contents(location)
                mutations.add(NumberLiteral(location, content, Source.FileType.KOTLIN))
                if (NumberLiteralTrim.matches(content)) {
                    mutations.add(NumberLiteralTrim(location, content, Source.FileType.KOTLIN))
                }
            }
        }
    }

    override fun enterIfExpression(ctx: KotlinParser.IfExpressionContext) {
        val conditionalLocation = ctx.expression().toLocation()
        mutations.add(NegateIf(conditionalLocation, parsedSource.contents(conditionalLocation), Source.FileType.KOTLIN))
        if (ctx.ELSE() == null) {
            mutations.add(RemoveIf(ctx.toLocation(), parsedSource.contents(ctx.toLocation()), Source.FileType.KOTLIN))
        } else {
            if (ctx.controlStructureBody(1) != null) {
                if (ctx.controlStructureBody(1).statement()?.expression() == null) { // not else if, just else
                    val location = Mutation.Location(
                        ctx.ELSE().symbol.startIndex,
                        ctx.controlStructureBody(1).stop.stopIndex,
                        lines.filterIndexed { index, _ ->
                            index >= ctx.ELSE().symbol.line - 1 && index <= ctx.controlStructureBody(1).stop.line - 1
                        }.joinToString("\n"),
                        ctx.ELSE().symbol.line,
                        ctx.controlStructureBody(1).stop.line
                    )
                    mutations.add(RemoveIf(location, parsedSource.contents(location), Source.FileType.KOTLIN))
                    mutations.add(
                        RemoveIf(
                            ctx.toLocation(),
                            parsedSource.contents(ctx.toLocation()),
                            Source.FileType.KOTLIN
                        )
                    )
                } else { // is an else if
                    val location = Mutation.Location(
                        ctx.start.startIndex,
                        ctx.ELSE().symbol.stopIndex,
                        lines.filterIndexed { index, _ ->
                            index >= ctx.start.line - 1 && index <= ctx.ELSE().symbol.line - 1
                        }.joinToString("\n"),
                        ctx.start.line,
                        ctx.ELSE().symbol.line
                    )
                    mutations.add(RemoveIf(location, parsedSource.contents(location), Source.FileType.KOTLIN))
                }
            }
        }
    }

    override fun enterLoopStatement(ctx: KotlinParser.LoopStatementContext) {
        val location = ctx.toLocation()
        ctx.doWhileStatement()?.also {
            val rightCurl = it.controlStructureBody().block()?.RCURL()
            if (rightCurl != null) {
                val rightCurlLocation = listOf(rightCurl, rightCurl).toLocation()
                mutations.add(
                    AddBreak(
                        rightCurlLocation,
                        parsedSource.contents(rightCurlLocation),
                        Source.FileType.KOTLIN
                    )
                )
            }
        }
        ctx.forStatement()?.also {
            val rightCurl = it.controlStructureBody().block()?.RCURL()
            if (rightCurl != null) {
                val rightCurlLocation = listOf(rightCurl, rightCurl).toLocation()
                mutations.add(
                    AddBreak(
                        rightCurlLocation,
                        parsedSource.contents(rightCurlLocation),
                        Source.FileType.KOTLIN
                    )
                )
            }
        }
        ctx.whileStatement()?.also {
            val rightCurl = it.controlStructureBody().block()?.RCURL()
            if (rightCurl != null) {
                val rightCurlLocation = listOf(rightCurl, rightCurl).toLocation()
                mutations.add(
                    AddBreak(
                        rightCurlLocation,
                        parsedSource.contents(rightCurlLocation),
                        Source.FileType.KOTLIN
                    )
                )
            }
        }
        mutations.add(RemoveLoop(location, parsedSource.contents(location), Source.FileType.KOTLIN))
    }

    override fun enterDoWhileStatement(ctx: KotlinParser.DoWhileStatementContext) {
        val location = ctx.expression().toLocation()
        mutations.add(NegateWhile(location, parsedSource.contents(location), Source.FileType.KOTLIN))
    }

    override fun enterWhileStatement(ctx: KotlinParser.WhileStatementContext) {
        val location = ctx.expression().toLocation()
        val rightCurl = ctx.controlStructureBody().block()?.RCURL()
        if (rightCurl != null) {
            val rightCurlLocation = listOf(rightCurl, rightCurl).toLocation()
            mutations.add(AddBreak(rightCurlLocation, parsedSource.contents(rightCurlLocation), Source.FileType.KOTLIN))
        }
        mutations.add(NegateWhile(location, parsedSource.contents(location), Source.FileType.KOTLIN))
    }

    override fun enterTryExpression(ctx: KotlinParser.TryExpressionContext) {
        val location = ctx.toLocation()
        mutations.add(RemoveTry(location, parsedSource.contents(location), Source.FileType.KOTLIN))
    }

    override fun enterPrefixUnaryOperator(ctx: KotlinParser.PrefixUnaryOperatorContext) {
        if (IncrementDecrement.matches(ctx.text)) {
            mutations.add(IncrementDecrement(ctx.toLocation(), ctx.text, Source.FileType.KOTLIN))
        }
        if (InvertNegation.matches(ctx.text)) {
            mutations.add(InvertNegation(ctx.toLocation(), ctx.text, Source.FileType.KOTLIN))
        }
    }

    override fun enterPostfixUnaryOperator(ctx: KotlinParser.PostfixUnaryOperatorContext) {
        if (IncrementDecrement.matches(ctx.text)) {
            mutations.add(IncrementDecrement(ctx.toLocation(), ctx.text, Source.FileType.KOTLIN))
        }
    }

    override fun enterComparisonOperator(ctx: KotlinParser.ComparisonOperatorContext) {
        if (ConditionalBoundary.matches(ctx.text)) {
            mutations.add(ConditionalBoundary(ctx.toLocation(), ctx.text, Source.FileType.KOTLIN))
        }
        if (NegateConditional.matches(ctx.text)) {
            mutations.add(NegateConditional(ctx.toLocation(), ctx.text, Source.FileType.KOTLIN))
        }
    }

    override fun enterMultiplicativeOperator(ctx: KotlinParser.MultiplicativeOperatorContext) {
        if (MutateMath.matches(ctx.text)) {
            mutations.add(MutateMath(ctx.toLocation(), ctx.text, Source.FileType.KOTLIN))
        }
    }

    override fun enterAdditiveOperator(ctx: KotlinParser.AdditiveOperatorContext) {
        if (PlusToMinus.matches(ctx.text)) {
            mutations.add(PlusToMinus(ctx.toLocation(), ctx.text, Source.FileType.KOTLIN))
        }
        if (MutateMath.matches(ctx.text)) {
            mutations.add(MutateMath(ctx.toLocation(), ctx.text, Source.FileType.KOTLIN))
        }
    }

    override fun enterEqualityOperator(ctx: KotlinParser.EqualityOperatorContext) {
        if (NegateConditional.matches(ctx.text)) {
            mutations.add(NegateConditional(ctx.toLocation(), ctx.text, Source.FileType.KOTLIN))
        }
        if (ctx.text == "==") {
            mutations.add(
                ChangeEquals(
                    ctx.toLocation(),
                    parsedSource.contents(ctx.toLocation()),
                    Source.FileType.KOTLIN,
                    "=="
                )
            )
        }
        if (ctx.text == "===") {
            mutations.add(
                ChangeEquals(
                    ctx.toLocation(),
                    parsedSource.contents(ctx.toLocation()),
                    Source.FileType.KOTLIN,
                    "==="
                )
            )
        }
    }

    override fun enterDisjunction(ctx: KotlinParser.DisjunctionContext) {
        if (SwapAndOr.matches(ctx.DISJ().joinToString())) {
            require(ctx.DISJ().size == 1) { "Disjunction list has an invalid size" }
            mutations.add(SwapAndOr(ctx.DISJ().toLocation(), ctx.DISJ().joinToString(), Source.FileType.KOTLIN))
            val (frontLocation, backLocation) = ctx.locationPair()
            mutations.add(RemoveAndOr(frontLocation, parsedSource.contents(frontLocation), Source.FileType.KOTLIN))
            mutations.add(RemoveAndOr(backLocation, parsedSource.contents(backLocation), Source.FileType.KOTLIN))
        }
    }

    override fun enterConjunction(ctx: KotlinParser.ConjunctionContext) {
        if (SwapAndOr.matches(ctx.CONJ().joinToString())) {
            require(ctx.CONJ().size == 1) { "Conjunction list has an invalid size" }
            mutations.add(SwapAndOr(ctx.CONJ().toLocation(), ctx.CONJ().joinToString(), Source.FileType.KOTLIN))
            val (frontLocation, backLocation) = ctx.locationPair()
            mutations.add(RemoveAndOr(frontLocation, parsedSource.contents(frontLocation), Source.FileType.KOTLIN))
            mutations.add(RemoveAndOr(backLocation, parsedSource.contents(backLocation), Source.FileType.KOTLIN))
        }
    }

    override fun enterAdditiveExpression(ctx: KotlinParser.AdditiveExpressionContext) {
        if (ctx.additiveOperator().size != 0) {
            val (frontLocation, backLocation) = ctx.locationPair()
            mutations.add(RemovePlus(frontLocation, parsedSource.contents(frontLocation), Source.FileType.KOTLIN))
            mutations.add(RemovePlus(backLocation, parsedSource.contents(backLocation), Source.FileType.KOTLIN))
            val text = parsedSource.contents(ctx.multiplicativeExpression()[1].toLocation())
            if (text == "1") {
                mutations.add(
                    PlusOrMinusOneToZero(
                        ctx.multiplicativeExpression()[1].toLocation(),
                        parsedSource.contents(ctx.multiplicativeExpression()[1].toLocation()),
                        Source.FileType.KOTLIN
                    )
                )
            }
        }
    }

    override fun enterNavigationSuffix(ctx: KotlinParser.NavigationSuffixContext) {
        if (ctx.memberAccessOperator()?.DOT() == null || ctx.simpleIdentifier() == null) {
            return
        }
        if (ctx.simpleIdentifier().text == "size" || ctx.simpleIdentifier().text == "length") {
            mutations.add(
                ChangeLengthAndSize(
                    ctx.simpleIdentifier().toLocation(),
                    parsedSource.contents(ctx.simpleIdentifier().toLocation()),
                    Source.FileType.KOTLIN
                )
            )
        }
    }

    override fun enterPostfixUnaryExpression(ctx: KotlinParser.PostfixUnaryExpressionContext) {
        val identifier = ctx.primaryExpression()?.simpleIdentifier()
        val arguments = ctx.postfixUnarySuffix()?.firstOrNull()?.callSuffix()?.valueArguments() ?: return
        if ((identifier?.text == "arrayOf" || identifier?.text?.endsWith("ArrayOf") == true) &&
            arguments.valueArgument().size > 1
        ) {
            val start = arguments.valueArgument().first().toLocation()
            val end = arguments.valueArgument().last().toLocation()
            val location = Mutation.Location(
                start.start,
                end.end,
                lines.filterIndexed { index, _ -> index >= start.startLine - 1 && index <= end.endLine - 1 }
                    .joinToString("\n"),
                start.startLine,
                end.endLine
            )
            val contents = parsedSource.contents(location)
            val parts = arguments.valueArgument().map {
                parsedSource.contents(it.toLocation())
            }
            mutations.add(ModifyArrayLiteral(location, contents, Source.FileType.KOTLIN, parts))
        }
    }

    private fun <T : ParserRuleContext> locationPairHelper(
        front: T,
        back: T
    ): Pair<Mutation.Location, Mutation.Location> {
        val frontLocation = Mutation.Location(
            front.start.startIndex,
            back.start.startIndex - 1,
            lines
                .filterIndexed { index, _ -> index >= front.start.line - 1 && index <= back.start.line - 1 }
                .joinToString("\n"),
            front.start.line,
            back.start.line
        )
        val backLocation = Mutation.Location(
            front.stop.stopIndex + 1,
            back.stop.stopIndex,
            lines
                .filterIndexed { index, _ -> index >= front.stop.line - 1 && index <= back.stop.line - 1 }
                .joinToString("\n"),
            front.start.line,
            back.stop.line
        )
        return Pair(frontLocation, backLocation)
    }

    private fun KotlinParser.AdditiveExpressionContext.locationPair(): Pair<Mutation.Location, Mutation.Location> {
        return locationPairHelper<KotlinParser.MultiplicativeExpressionContext>(
            multiplicativeExpression(0),
            multiplicativeExpression(1)
        )
    }

    private fun KotlinParser.ConjunctionContext.locationPair(): Pair<Mutation.Location, Mutation.Location> {
        return locationPairHelper<KotlinParser.EqualityContext>(
            equality(0),
            equality(1)
        )
    }

    private fun KotlinParser.DisjunctionContext.locationPair(): Pair<Mutation.Location, Mutation.Location> {
        return locationPairHelper<KotlinParser.ConjunctionContext>(conjunction(0), conjunction(1))
    }

    init {
        // println(parsedSource.tree.format(parsedSource.parser))
        ParseTreeWalker.DEFAULT.walk(this, parsedSource.tree)
    }
}
