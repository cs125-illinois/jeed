// ktlint-disable filename
package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParserBaseListener
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.TerminalNode
import org.jetbrains.kotlin.backend.common.pop

class KotlinMutationListener(private val parsedSource: Source.ParsedSource) : KotlinParserBaseListener() {
    val lines = parsedSource.contents.lines()
    val mutations: MutableList<Mutation> = mutableListOf()
    private val fileType = Source.FileType.KOTLIN

    private val currentPath: MutableList<Mutation.Location.SourcePath> = mutableListOf()
    override fun enterClassDeclaration(ctx: KotlinParser.ClassDeclarationContext) {
        currentPath.add(
            Mutation.Location.SourcePath(
                Mutation.Location.SourcePath.Type.CLASS,
                ctx.simpleIdentifier().text
            )
        )
    }

    override fun exitClassDeclaration(ctx: KotlinParser.ClassDeclarationContext) {
        currentPath.last().also {
            check(it.type == Mutation.Location.SourcePath.Type.CLASS)
            check(it.name == ctx.simpleIdentifier()!!.text)
        }
        currentPath.pop()
    }

    private val returnTypeStack: MutableList<String> = mutableListOf()
    private val currentReturnType: String?
        get() = returnTypeStack.lastOrNull()

    override fun enterFunctionDeclaration(ctx: KotlinParser.FunctionDeclarationContext) {
        val returnType = ctx.type().let {
            if (it.isEmpty()) {
                ""
            } else {
                check(it.last().start.startIndex > ctx.identifier().start.startIndex) {
                    "Couldn't find method return type"
                }
                it.last().text
            }
        }
        returnTypeStack.add(returnType)
    }

    override fun exitFunctionDeclaration(ctx: KotlinParser.FunctionDeclarationContext) {
        check(returnTypeStack.isNotEmpty()) { "Return type stack should not be empty" }
        returnTypeStack.pop()
    }

    override fun enterFunctionBody(ctx: KotlinParser.FunctionBodyContext) {
        check(currentReturnType != null)
        val methodLocation = ctx.block().toLocation()
        val methodContents = parsedSource.contents(methodLocation)
        if (RemoveMethod.matches(methodContents, currentReturnType!!, fileType)) {
            mutations.add(RemoveMethod(methodLocation, methodContents, currentReturnType!!, fileType))
        }
    }

    override fun enterJumpExpression(ctx: KotlinParser.JumpExpressionContext) {
        if (ctx.RETURN() != null && ctx.expression() != null) {
            val returnToken = ctx.expression()
            val returnLocation = returnToken.toLocation()
            if (PrimitiveReturn.matches(returnToken.text, currentReturnType!!, fileType)) {
                mutations.add(PrimitiveReturn(returnLocation, parsedSource.contents(returnLocation), fileType))
            }
            if (TrueReturn.matches(returnToken.text, currentReturnType!!)) {
                mutations.add(TrueReturn(returnLocation, parsedSource.contents(returnLocation), fileType))
            }
            if (FalseReturn.matches(returnToken.text, currentReturnType!!)) {
                mutations.add(FalseReturn(returnLocation, parsedSource.contents(returnLocation), fileType))
            }
            if (NullReturn.matches(returnToken.text, currentReturnType!!, fileType)) {
                mutations.add(NullReturn(returnLocation, parsedSource.contents(returnLocation), fileType))
            }
        }
    }

    override fun enterStatement(ctx: KotlinParser.StatementContext) {
        val statementLocation = ctx.toLocation()
        if (ctx.start.text == "assert" ||
            ctx.start.text == "check" ||
            ctx.start.text == "require"
        ) {
            mutations.add(RemoveRuntimeCheck(statementLocation, parsedSource.contents(statementLocation), fileType))
        }
        if (ctx.declaration() == null) {
            mutations.add(RemoveStatement(statementLocation, parsedSource.contents(statementLocation), fileType))
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
            currentPath,
            lines.filterIndexed { index, _ -> index >= start.line - 1 && index <= stop.line - 1 }
                .joinToString("\n"),
            start.line
        )

    private fun List<TerminalNode>.toLocation() =
        Mutation.Location(
            first().symbol.startIndex,
            last().symbol.stopIndex,
            currentPath,
            lines.filterIndexed { index, _ -> index >= first().symbol.line - 1 && index <= last().symbol.line - 1 }
                .joinToString("\n"),
            first().symbol.line
        )

    override fun enterLiteralConstant(ctx: KotlinParser.LiteralConstantContext) {
        if (insideAnnotation) {
            return
        }
        ctx.BooleanLiteral()?.also {
            ctx.toLocation().also { location ->
                mutations.add(BooleanLiteral(location, parsedSource.contents(location), fileType))
            }
        }
        ctx.IntegerLiteral()?.also {
            ctx.toLocation().also { location ->
                mutations.add(NumberLiteral(location, parsedSource.contents(location), fileType))
            }
        }
        ctx.stringLiteral()?.also {
            ctx.toLocation().also { location ->
                mutations.add(StringLiteral(location, parsedSource.contents(location), fileType))
            }
        }
        ctx.HexLiteral()?.also {
            ctx.toLocation().also { location ->
                mutations.add(NumberLiteral(location, parsedSource.contents(location), fileType, 16))
            }
        }
        ctx.BinLiteral()?.also {
            ctx.toLocation().also { location ->
                mutations.add(NumberLiteral(location, parsedSource.contents(location), fileType, 2))
            }
        }
        ctx.CharacterLiteral()?.also {
            ctx.toLocation().also { location ->
                mutations.add(CharLiteral(location, parsedSource.contents(location), fileType))
            }
        }
        // reals are doubles and floats
        ctx.RealLiteral()?.also {
            ctx.toLocation().also { location ->
                mutations.add(NumberLiteral(location, parsedSource.contents(location), fileType))
            }
        }
        ctx.LongLiteral()?.also {
            ctx.toLocation().also { location ->
                mutations.add(NumberLiteral(location, parsedSource.contents(location), fileType))
            }
        }
    }

    override fun enterIfExpression(ctx: KotlinParser.IfExpressionContext) {
        val conditionalLocation = ctx.parenthesizedExpression().toLocation()
        mutations.add(NegateIf(conditionalLocation, parsedSource.contents(conditionalLocation), fileType))
        if (ctx.ELSE() == null) {
            mutations.add(RemoveIf(ctx.toLocation(), parsedSource.contents(ctx.toLocation()), fileType))
        } else {
            if (ctx.controlStructureBody(1).expression() == null) { // not else if, just else
                val location = Mutation.Location(
                    ctx.ELSE().symbol.startIndex,
                    ctx.controlStructureBody(1).stop.stopIndex,
                    currentPath,
                    lines.filterIndexed { index, _ ->
                        index >= ctx.ELSE().symbol.line - 1 && index <= ctx.controlStructureBody(1).stop.line - 1
                    }.joinToString("\n"),
                    ctx.ELSE().symbol.line
                )
                mutations.add(RemoveIf(location, parsedSource.contents(location), fileType))
                mutations.add(RemoveIf(ctx.toLocation(), parsedSource.contents(ctx.toLocation()), fileType))
            } else { // is an else if
                val location = Mutation.Location(
                    ctx.start.startIndex,
                    ctx.ELSE().symbol.stopIndex,
                    currentPath,
                    lines.filterIndexed { index, _ ->
                        index >= ctx.start.line - 1 && index <= ctx.ELSE().symbol.line - 1
                    }.joinToString("\n"),
                    ctx.start.line
                )
                mutations.add(RemoveIf(location, parsedSource.contents(location), fileType))
            }
        }
    }

    override fun enterLoopExpression(ctx: KotlinParser.LoopExpressionContext) {
        val location = ctx.toLocation()
        mutations.add(RemoveLoop(location, parsedSource.contents(location), fileType))
    }

    override fun enterDoWhileExpression(ctx: KotlinParser.DoWhileExpressionContext) {
        val location = ctx.parenthesizedExpression().toLocation()
        mutations.add(NegateWhile(location, parsedSource.contents(location), fileType))
    }

    override fun enterWhileExpression(ctx: KotlinParser.WhileExpressionContext) {
        val location = ctx.parenthesizedExpression().toLocation()
        mutations.add(NegateWhile(location, parsedSource.contents(location), fileType))
    }

    override fun enterTryExpression(ctx: KotlinParser.TryExpressionContext) {
        val location = ctx.toLocation()
        mutations.add(RemoveTry(location, parsedSource.contents(location), fileType))
    }

    override fun enterPrefixUnaryOperation(ctx: KotlinParser.PrefixUnaryOperationContext) {
        if (IncrementDecrement.matches(ctx.text)) {
            mutations.add(IncrementDecrement(ctx.toLocation(), ctx.text, fileType))
        }
        if (InvertNegation.matches(ctx.text)) {
            mutations.add(InvertNegation(ctx.toLocation(), ctx.text, fileType))
        }
    }

    override fun enterPostfixUnaryOperation(ctx: KotlinParser.PostfixUnaryOperationContext) {
        if (IncrementDecrement.matches(ctx.text)) {
            mutations.add(IncrementDecrement(ctx.toLocation(), ctx.text, fileType))
        }
    }

    override fun enterComparisonOperator(ctx: KotlinParser.ComparisonOperatorContext) {
        if (ConditionalBoundary.matches(ctx.text)) {
            mutations.add(ConditionalBoundary(ctx.toLocation(), ctx.text, fileType))
        }
        if (NegateConditional.matches(ctx.text)) {
            mutations.add(NegateConditional(ctx.toLocation(), ctx.text, fileType))
        }
    }

    override fun enterMultiplicativeOperation(ctx: KotlinParser.MultiplicativeOperationContext) {
        if (MutateMath.matches(ctx.text)) {
            mutations.add(MutateMath(ctx.toLocation(), ctx.text, fileType))
        }
    }

    override fun enterAdditiveOperator(ctx: KotlinParser.AdditiveOperatorContext) {
        if (PlusToMinus.matches(ctx.text)) {
            mutations.add(PlusToMinus(ctx.toLocation(), ctx.text, fileType))
        }
        if (MutateMath.matches(ctx.text)) {
            mutations.add(MutateMath(ctx.toLocation(), ctx.text, fileType))
        }
    }

    override fun enterEqualityOperation(ctx: KotlinParser.EqualityOperationContext) {
        if (NegateConditional.matches(ctx.text)) {
            mutations.add(NegateConditional(ctx.toLocation(), ctx.text, fileType))
        }
        if (ctx.text == "==") {
            mutations.add(
                ChangeEquals(
                    ctx.toLocation(),
                    parsedSource.contents(ctx.toLocation()),
                    fileType,
                    "=="
                )
            )
        }
        if (ctx.text == "===") {
            mutations.add(
                ChangeEquals(
                    ctx.toLocation(),
                    parsedSource.contents(ctx.toLocation()),
                    fileType,
                    "==="
                )
            )
        }
    }

    override fun enterDisjunction(ctx: KotlinParser.DisjunctionContext) {
        if (SwapAndOr.matches(ctx.DISJ().joinToString())) {
            require(ctx.DISJ().size == 1) { "Disjunction list has an invalid size" }
            mutations.add(SwapAndOr(ctx.DISJ().toLocation(), ctx.DISJ().joinToString(), fileType))
            val (frontLocation, backLocation) = ctx.locationPair()
            mutations.add(RemoveAndOr(frontLocation, parsedSource.contents(frontLocation), fileType))
            mutations.add(RemoveAndOr(backLocation, parsedSource.contents(backLocation), fileType))
        }
    }

    override fun enterConjunction(ctx: KotlinParser.ConjunctionContext) {
        if (SwapAndOr.matches(ctx.CONJ().joinToString())) {
            require(ctx.CONJ().size == 1) { "Conjunction list has an invalid size" }
            mutations.add(SwapAndOr(ctx.CONJ().toLocation(), ctx.CONJ().joinToString(), fileType))
            val (frontLocation, backLocation) = ctx.locationPair()
            mutations.add(RemoveAndOr(frontLocation, parsedSource.contents(frontLocation), fileType))
            mutations.add(RemoveAndOr(backLocation, parsedSource.contents(backLocation), fileType))
        }
    }

    override fun enterAdditiveExpression(ctx: KotlinParser.AdditiveExpressionContext) {
        if (ctx.additiveOperator().size != 0) {
            val (frontLocation, backLocation) = ctx.locationPair()
            mutations.add(RemovePlus(frontLocation, parsedSource.contents(frontLocation), fileType))
            mutations.add(RemovePlus(backLocation, parsedSource.contents(backLocation), fileType))
        }
    }

    private fun <T : ParserRuleContext> locationPairHelper(
        front: T,
        back: T
    ): Pair<Mutation.Location, Mutation.Location> {
        val frontLocation = Mutation.Location(
            front.start.startIndex,
            back.start.startIndex - 1,
            currentPath,
            lines
                .filterIndexed { index, _ -> index >= front.start.line - 1 && index <= back.start.line - 1 }
                .joinToString("\n"),
            front.start.line
        )
        val backLocation = Mutation.Location(
            front.stop.stopIndex + 1,
            back.stop.stopIndex,
            currentPath,
            lines
                .filterIndexed { index, _ -> index >= front.stop.line - 1 && index <= back.stop.line - 1 }
                .joinToString("\n"),
            front.start.line
        )
        return Pair(frontLocation, backLocation)
    }

    private fun KotlinParser.AdditiveExpressionContext.locationPair(): Pair<Mutation.Location, Mutation.Location> {
        return locationPairHelper<KotlinParser.MultiplicativeExpressionContext>(
            multiplicativeExpression(0), multiplicativeExpression(1)
        )
    }

    private fun KotlinParser.ConjunctionContext.locationPair(): Pair<Mutation.Location, Mutation.Location> {
        return locationPairHelper<KotlinParser.EqualityComparisonContext>(
            equalityComparison(0), equalityComparison(1)
        )
    }

    private fun KotlinParser.DisjunctionContext.locationPair(): Pair<Mutation.Location, Mutation.Location> {
        return locationPairHelper<KotlinParser.ConjunctionContext>(conjunction(0), conjunction(1))
    }

    init {
        ParseTreeWalker.DEFAULT.walk(this, parsedSource.tree)
    }
}
