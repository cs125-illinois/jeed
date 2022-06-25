// ktlint-disable filename
@file:Suppress("MatchingDeclarationName")

package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParserBaseListener
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.TerminalNode
import org.jetbrains.kotlin.backend.common.pop

@Suppress("TooManyFunctions", "ComplexMethod", "LongMethod")
class JavaMutationListener(private val parsedSource: Source.ParsedSource) : JavaParserBaseListener() {
    val lines = parsedSource.contents.lines()
    val mutations: MutableList<Mutation> = mutableListOf()

    private val returnTypeStack: MutableList<String> = mutableListOf()
    private val currentReturnType: String?
        get() = returnTypeStack.lastOrNull()

    override fun enterMethodDeclaration(ctx: JavaParser.MethodDeclarationContext) {
        returnTypeStack.add(ctx.typeTypeOrVoid().text)
    }

    override fun exitMethodDeclaration(ctx: JavaParser.MethodDeclarationContext) {
        check(returnTypeStack.isNotEmpty()) { "Return type stack should not be empty" }
        returnTypeStack.pop()
    }

    override fun enterMethodBody(ctx: JavaParser.MethodBodyContext) {
        if (ctx.block() != null) {
            check(currentReturnType != null)
            val location = ctx.block().toLocation()
            val contents = parsedSource.contents(location)
            if (RemoveMethod.matches(contents, currentReturnType!!, Source.FileType.JAVA)) {
                mutations.add(RemoveMethod(location, contents, currentReturnType!!, Source.FileType.JAVA))
            }
        }
    }

    private var insideAnnotation = false
    override fun enterAnnotation(ctx: JavaParser.AnnotationContext?) {
        insideAnnotation = true
    }

    override fun exitAnnotation(ctx: JavaParser.AnnotationContext?) {
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

    private fun Token.toLocation() = Mutation.Location(startIndex, stopIndex, lines[line - 1], line, line)
    private fun List<TerminalNode>.toLocation() =
        Mutation.Location(
            first().symbol.startIndex,
            last().symbol.stopIndex,
            lines.filterIndexed { index, _ -> index >= first().symbol.line - 1 && index <= last().symbol.line - 1 }
                .joinToString("\n"),
            first().symbol.line,
            last().symbol.line
        )

    override fun enterLiteral(ctx: JavaParser.LiteralContext) {
        if (insideAnnotation) {
            return
        }
        ctx.BOOL_LITERAL()?.also {
            ctx.toLocation().also { location ->
                mutations.add(BooleanLiteral(location, parsedSource.contents(location), Source.FileType.JAVA))
            }
        }
        ctx.CHAR_LITERAL()?.also {
            ctx.toLocation().also { location ->
                mutations.add(CharLiteral(location, parsedSource.contents(location), Source.FileType.JAVA))
            }
        }
        ctx.STRING_LITERAL()?.also {
            ctx.toLocation().also { location ->
                val contents = parsedSource.contents(location)
                mutations.addStringMutations(location, contents, Source.FileType.JAVA)
            }
        }
        ctx.integerLiteral()?.also { integerLiteral ->
            integerLiteral.DECIMAL_LITERAL()?.also {
                ctx.toLocation().also { location ->
                    val contents = parsedSource.contents(location)
                    mutations.add(NumberLiteral(location, contents, Source.FileType.JAVA))
                    if (NumberLiteralTrim.matches(contents)) {
                        mutations.add(NumberLiteralTrim(location, contents, Source.FileType.JAVA))
                    }
                }
            }
            integerLiteral.BINARY_LITERAL()?.also {
                ctx.toLocation().also { location ->
                    val contents = parsedSource.contents(location)
                    mutations.add(NumberLiteral(location, contents, Source.FileType.JAVA, 2))
                    if (NumberLiteralTrim.matches(contents, 2)) {
                        mutations.add(NumberLiteralTrim(location, contents, Source.FileType.JAVA, 2))
                    }
                }
            }
            integerLiteral.HEX_LITERAL()?.also {
                ctx.toLocation().also { location ->
                    val contents = parsedSource.contents(location)
                    mutations.add(NumberLiteral(location, contents, Source.FileType.JAVA, 16))
                    if (NumberLiteralTrim.matches(contents, 16)) {
                        mutations.add(NumberLiteralTrim(location, contents, Source.FileType.JAVA, 16))
                    }
                }
            }
        }
        ctx.floatLiteral()?.also { floatLiteral ->
            floatLiteral.FLOAT_LITERAL()?.also {
                ctx.toLocation().also { location ->
                    val contents = parsedSource.contents(location)
                    mutations.add(NumberLiteral(location, contents, Source.FileType.JAVA))
                    if (NumberLiteralTrim.matches(contents)) {
                        mutations.add(NumberLiteralTrim(location, contents, Source.FileType.JAVA))
                    }
                }
            }
        }
    }

    private fun JavaParser.ExpressionContext.locationPair(): Pair<Mutation.Location, Mutation.Location> {
        check(expression().size == 2)
        val front = expression(0)
        val back = expression(1)
        val frontLocation = Mutation.Location(
            front.start.startIndex,
            back.start.startIndex - 1,
            lines
                .filterIndexed { index, _ ->
                    index >= front.start.line - 1 && index <= back.start.line - 1
                }
                .joinToString("\n"),
            front.start.line,
            back.start.line
        )
        val backLocation = Mutation.Location(
            front.stop.stopIndex + 1,
            back.stop.stopIndex,
            lines
                .filterIndexed { index, _ ->
                    index >= front.stop.line - 1 && index <= back.stop.line - 1
                }
                .joinToString("\n"),
            front.start.line,
            back.stop.line
        )
        return Pair(frontLocation, backLocation)
    }

    override fun enterExpression(ctx: JavaParser.ExpressionContext) {
        ctx.prefix?.toLocation()?.also { location ->
            val contents = parsedSource.contents(location)
            if (IncrementDecrement.matches(contents)) {
                mutations.add(IncrementDecrement(location, contents, Source.FileType.JAVA))
            }
            if (InvertNegation.matches(contents)) {
                mutations.add(InvertNegation(location, contents, Source.FileType.JAVA))
            }
        }
        ctx.postfix?.toLocation()?.also { location ->
            val contents = parsedSource.contents(location)
            if (IncrementDecrement.matches(contents)) {
                mutations.add(IncrementDecrement(location, contents, Source.FileType.JAVA))
            }
        }

        ctx.LT()?.also { tokens ->
            if (tokens.size == 2) {
                tokens.toLocation().also { location ->
                    val contents = parsedSource.contents(location)
                    if (MutateMath.matches(contents)) {
                        mutations.add(MutateMath(location, contents, Source.FileType.JAVA))
                    }
                    if (RemoveBinary.matches(contents)) {
                        val (frontLocation, backLocation) = ctx.locationPair()
                        mutations.add(
                            RemoveBinary(
                                frontLocation,
                                parsedSource.contents(frontLocation),
                                Source.FileType.JAVA
                            )
                        )
                        mutations.add(
                            RemoveBinary(
                                backLocation,
                                parsedSource.contents(backLocation),
                                Source.FileType.JAVA
                            )
                        )
                    }
                }
            }
        }

        // I'm not sure why you can't write this like the other ones, but it fails with a cast to kotlin.Unit
        // exception
        @Suppress("MagicNumber")
        if (ctx.GT() != null && (ctx.GT().size == 2 || ctx.GT().size == 3)) {
            val location = ctx.GT().toLocation()
            val contents = parsedSource.contents(location)
            if (MutateMath.matches(contents)) {
                mutations.add(MutateMath(location, contents, Source.FileType.JAVA))
            }
            if (RemoveBinary.matches(contents)) {
                val (frontLocation, backLocation) = ctx.locationPair()
                mutations.add(RemoveBinary(frontLocation, parsedSource.contents(frontLocation), Source.FileType.JAVA))
                mutations.add(RemoveBinary(backLocation, parsedSource.contents(backLocation), Source.FileType.JAVA))
            }
        }

        ctx.bop?.toLocation()?.also { location ->
            val contents = parsedSource.contents(location)
            if (ConditionalBoundary.matches(contents)) {
                mutations.add(ConditionalBoundary(location, contents, Source.FileType.JAVA))
            }
            if (NegateConditional.matches(contents)) {
                mutations.add(NegateConditional(location, contents, Source.FileType.JAVA))
            }
            if (MutateMath.matches(contents)) {
                mutations.add(MutateMath(location, contents, Source.FileType.JAVA))
            }
            if (PlusToMinus.matches(contents)) {
                mutations.add(PlusToMinus(location, contents, Source.FileType.JAVA))
            }
            if (SwapAndOr.matches(contents)) {
                mutations.add(SwapAndOr(location, contents, Source.FileType.JAVA))
            }
            @Suppress("ComplexCondition")
            if (contents == "&&" || contents == "||") {
                val (frontLocation, backLocation) = ctx.locationPair()
                mutations.add(RemoveAndOr(frontLocation, parsedSource.contents(frontLocation), Source.FileType.JAVA))
                mutations.add(RemoveAndOr(backLocation, parsedSource.contents(backLocation), Source.FileType.JAVA))
            }
            if (RemovePlus.matches(contents)) {
                val (frontLocation, backLocation) = ctx.locationPair()
                mutations.add(RemovePlus(frontLocation, parsedSource.contents(frontLocation), Source.FileType.JAVA))
                mutations.add(RemovePlus(backLocation, parsedSource.contents(backLocation), Source.FileType.JAVA))
            }
            if (RemoveBinary.matches(contents)) {
                val (frontLocation, backLocation) = ctx.locationPair()
                mutations.add(RemoveBinary(frontLocation, parsedSource.contents(frontLocation), Source.FileType.JAVA))
                mutations.add(RemoveBinary(backLocation, parsedSource.contents(backLocation), Source.FileType.JAVA))
            }
            if (contents == "==") {
                mutations.add(
                    ChangeEquals(
                        ctx.toLocation(),
                        parsedSource.contents(ctx.toLocation()),
                        Source.FileType.JAVA,
                        "==",
                        ctx.expression(0).text,
                        ctx.expression(1).text
                    )
                )
            }
            @Suppress("ComplexCondition")
            if (contents == "." &&
                ctx.methodCall()?.identifier()?.text == "equals" &&
                ctx.methodCall()?.expressionList()?.expression()?.size == 1
            ) {
                mutations.add(
                    ChangeEquals(
                        ctx.toLocation(),
                        parsedSource.contents(ctx.toLocation()),
                        Source.FileType.JAVA,
                        ".equals",
                        ctx.expression(0).text,
                        ctx.methodCall().expressionList().expression(0).text
                    )
                )
            }
            if (contents == "." && ctx.identifier()?.text == "length") {
                mutations.add(
                    ChangeLengthAndSize(
                        ctx.identifier().toLocation(),
                        parsedSource.contents(ctx.identifier().toLocation()),
                        Source.FileType.JAVA
                    )
                )
            }
            if (contents == "." &&
                (ctx.methodCall()?.identifier()?.text == "length" || ctx.methodCall()?.identifier()?.text == "size") &&
                ctx.methodCall()?.expressionList()?.isEmpty != false
            ) {
                mutations.add(
                    ChangeLengthAndSize(
                        ctx.methodCall().toLocation(),
                        parsedSource.contents(ctx.methodCall().toLocation()),
                        Source.FileType.JAVA
                    )
                )
            }
            if (contents == "+" || contents == "-") {
                val text = parsedSource.contents(ctx.expression(1).toLocation())
                if (text == "1") {
                    mutations.add(
                        PlusOrMinusOneToZero(
                            ctx.expression(1).toLocation(),
                            parsedSource.contents(ctx.expression(1).toLocation()),
                            Source.FileType.JAVA
                        )
                    )
                }
            }
        }
    }

    private val seenIfStarts = mutableSetOf<Int>()

    override fun enterStatement(ctx: JavaParser.StatementContext) {
        ctx.IF()?.also {
            val outerLocation = ctx.toLocation()
            if (outerLocation.start !in seenIfStarts) {
                // Add entire if
                mutations.add(RemoveIf(outerLocation, parsedSource.contents(outerLocation), Source.FileType.JAVA))
                seenIfStarts += outerLocation.start
                check(ctx.statement().isNotEmpty())
                if (ctx.statement().size == 2 && ctx.statement(1).block() != null) {
                    // Add else branch (2)
                    check(ctx.ELSE() != null)
                    val start = ctx.ELSE().symbol
                    val end = ctx.statement(1).block().stop
                    val elseLocation =
                        Mutation.Location(
                            start.startIndex,
                            end.stopIndex,
                            lines.filterIndexed { index, _ -> index >= start.line - 1 && index <= end.line - 1 }
                                .joinToString("\n"),
                            start.line,
                            end.line
                        )
                    mutations.add(RemoveIf(elseLocation, parsedSource.contents(elseLocation), Source.FileType.JAVA))
                } else if (ctx.statement().size >= 2) {
                    var statement = ctx.statement(1)
                    var previousMarker = ctx.ELSE()
                    check(previousMarker != null)
                    while (statement != null) {
                        if (statement.IF() != null) {
                            seenIfStarts += statement.toLocation().start
                        }
                        val end = statement.statement(0) ?: statement.block()
                        if (end != null) {
                            val currentLocation =
                                Mutation.Location(
                                    previousMarker.symbol.startIndex,
                                    end.stop.stopIndex,
                                    lines
                                        .filterIndexed { index, _ ->
                                            index >= previousMarker.symbol.line - 1 && index <= end.stop.line - 1
                                        }
                                        .joinToString("\n"),
                                    previousMarker.symbol.line,
                                    end.stop.line
                                )
                            mutations.add(
                                RemoveIf(
                                    currentLocation,
                                    parsedSource.contents(currentLocation),
                                    Source.FileType.JAVA
                                )
                            )
                        }
                        previousMarker = statement.ELSE()
                        statement = statement.statement(1)
                    }
                }
            }
            ctx.parExpression().toLocation().also { location ->
                mutations.add(NegateIf(location, parsedSource.contents(location), Source.FileType.JAVA))
            }
        }
        ctx.ASSERT()?.also {
            ctx.toLocation().also { location ->
                mutations.add(RemoveRuntimeCheck(location, parsedSource.contents(location), Source.FileType.JAVA))
            }
        }
        ctx.RETURN()?.also {
            ctx.expression()?.firstOrNull()?.toLocation()?.also { location ->
                val contents = parsedSource.contents(location)
                currentReturnType?.also { returnType ->
                    if (PrimitiveReturn.matches(contents, returnType, Source.FileType.JAVA)) {
                        mutations.add(PrimitiveReturn(location, parsedSource.contents(location), Source.FileType.JAVA))
                    }
                    if (TrueReturn.matches(contents, returnType)) {
                        mutations.add(TrueReturn(location, parsedSource.contents(location), Source.FileType.JAVA))
                    }
                    if (FalseReturn.matches(contents, returnType)) {
                        mutations.add(FalseReturn(location, parsedSource.contents(location), Source.FileType.JAVA))
                    }
                    if (NullReturn.matches(contents, returnType, Source.FileType.JAVA)) {
                        mutations.add(NullReturn(location, parsedSource.contents(location), Source.FileType.JAVA))
                    }
                } ?: error("Should have recorded a return type at this point")
            }
        }
        ctx.WHILE()?.also {
            ctx.parExpression().toLocation().also { location ->
                mutations.add(NegateWhile(location, parsedSource.contents(location), Source.FileType.JAVA))
                val rbrace = ctx.statement().last()?.block()?.RBRACE()
                if (rbrace != null) {
                    val endBraceLocation = listOf(rbrace, rbrace).toLocation()
                    mutations.add(
                        AddBreak(
                            endBraceLocation,
                            parsedSource.contents(endBraceLocation),
                            Source.FileType.JAVA
                        )
                    )
                }
            }
            if (ctx.DO() == null) {
                mutations.add(
                    RemoveLoop(
                        ctx.toLocation(),
                        parsedSource.contents(ctx.toLocation()),
                        Source.FileType.JAVA
                    )
                )
            }
        }
        ctx.FOR()?.also {
            mutations.add(RemoveLoop(ctx.toLocation(), parsedSource.contents(ctx.toLocation()), Source.FileType.JAVA))
            val rbrace = ctx.statement().last()?.block()?.RBRACE()
            if (rbrace != null) {
                val endBraceLocation = listOf(rbrace, rbrace).toLocation()
                mutations.add(AddBreak(endBraceLocation, parsedSource.contents(endBraceLocation), Source.FileType.JAVA))
            }
        }
        ctx.DO()?.also {
            mutations.add(RemoveLoop(ctx.toLocation(), parsedSource.contents(ctx.toLocation()), Source.FileType.JAVA))
        }
        ctx.TRY()?.also {
            mutations.add(RemoveTry(ctx.toLocation(), parsedSource.contents(ctx.toLocation()), Source.FileType.JAVA))
        }
        ctx.statementExpression?.also {
            mutations.add(
                RemoveStatement(
                    ctx.toLocation(),
                    parsedSource.contents(ctx.toLocation()),
                    Source.FileType.JAVA
                )
            )
        }
        ctx.BREAK()?.symbol?.also {
            mutations.add(
                SwapBreakContinue(
                    it.toLocation(),
                    parsedSource.contents(it.toLocation()),
                    Source.FileType.JAVA
                )
            )
        }
        ctx.CONTINUE()?.symbol?.also {
            mutations.add(
                SwapBreakContinue(
                    it.toLocation(),
                    parsedSource.contents(it.toLocation()),
                    Source.FileType.JAVA
                )
            )
        }
    }

    override fun enterArrayInitializer(ctx: JavaParser.ArrayInitializerContext) {
        if ((ctx.variableInitializer()?.size ?: 0) < 2) {
            return
        }
        val start = ctx.variableInitializer().first().toLocation()
        val end = ctx.variableInitializer().last().toLocation()
        val location = Mutation.Location(
            start.start,
            end.end,
            lines.filterIndexed { index, _ -> index >= start.startLine - 1 && index <= end.endLine - 1 }
                .joinToString("\n"),
            start.startLine,
            end.endLine
        )
        val contents = parsedSource.contents(location)
        val parts = ctx.variableInitializer().map {
            parsedSource.contents(it.toLocation())
        }
        mutations.add(ModifyArrayLiteral(location, contents, Source.FileType.JAVA, parts))
    }

    init {
        // println(parsedSource.tree.format(parsedSource.parser))
        ParseTreeWalker.DEFAULT.walk(this, parsedSource.tree)
    }
}
