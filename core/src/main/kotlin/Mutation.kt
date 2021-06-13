@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParserBaseListener
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParserBaseListener
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.TerminalNode
import org.jetbrains.kotlin.backend.common.pop
import java.util.Objects
import kotlin.random.Random

val PITEST = setOf(
    Mutation.Type.BOOLEAN_LITERAL,
    Mutation.Type.CHAR_LITERAL,
    Mutation.Type.STRING_LITERAL,
    Mutation.Type.NUMBER_LITERAL,
    Mutation.Type.CONDITIONAL_BOUNDARY,
    Mutation.Type.NEGATE_CONDITIONAL,
    Mutation.Type.SWAP_AND_OR,
    Mutation.Type.INCREMENT_DECREMENT,
    Mutation.Type.INVERT_NEGATION,
    Mutation.Type.MATH,
    Mutation.Type.PRIMITIVE_RETURN,
    Mutation.Type.TRUE_RETURN,
    Mutation.Type.FALSE_RETURN,
    Mutation.Type.NULL_RETURN,
    Mutation.Type.PLUS_TO_MINUS
)
val OTHER = setOf(
    Mutation.Type.REMOVE_ASSERT,
    Mutation.Type.REMOVE_METHOD,
    Mutation.Type.NEGATE_IF,
    Mutation.Type.REMOVE_IF,
    Mutation.Type.REMOVE_LOOP,
    Mutation.Type.REMOVE_AND_OR,
    Mutation.Type.REMOVE_TRY,
    Mutation.Type.REMOVE_STATEMENT,
    Mutation.Type.REMOVE_PLUS,
    Mutation.Type.REMOVE_BINARY
)
val ALL = PITEST + OTHER

fun Mutation.Type.suppressionComment() = "mutate-disable-" + mutationName()
fun Mutation.Type.mutationName() = name.lowercase().replace("_", "-")

sealed class Mutation(val mutationType: Type, var location: Location, val original: String) {
    data class Location(
        val start: Int,
        val end: Int,
        val path: List<SourcePath>,
        val line: String,
        val startLine: Int
    ) {
        init {
            check(end >= start) { "Invalid location: $end $start" }
        }

        data class SourcePath(val type: Type, val name: String) {
            enum class Type { CLASS, METHOD }
        }

        fun shift(amount: Int) = copy(start = start + amount, end = end + amount)

        override fun equals(other: Any?) = when {
            this === other -> true
            javaClass != other?.javaClass -> false
            else -> {
                other as Location
                start == other.start && end == other.end && line == other.line
            }
        }

        override fun hashCode() = Objects.hash(start, end, line)
    }

    fun overlaps(other: Mutation) =
        (other.location.start in location.start..location.end) ||
            (other.location.end in location.start..location.end) ||
            (other.location.start < location.start && location.end < other.location.end) ||
            (location.start < other.location.start && other.location.end < location.end)

    fun after(other: Mutation) = location.start > other.location.end

    fun shift(amount: Int) {
        location = location.shift(amount)
    }

    enum class Type {
        BOOLEAN_LITERAL, CHAR_LITERAL, STRING_LITERAL, NUMBER_LITERAL,
        CONDITIONAL_BOUNDARY, NEGATE_CONDITIONAL, SWAP_AND_OR,
        INCREMENT_DECREMENT, INVERT_NEGATION, MATH,
        PRIMITIVE_RETURN, TRUE_RETURN, FALSE_RETURN, NULL_RETURN, PLUS_TO_MINUS,
        REMOVE_ASSERT, REMOVE_METHOD,
        NEGATE_IF, NEGATE_WHILE, REMOVE_IF, REMOVE_LOOP, REMOVE_AND_OR, REMOVE_TRY, REMOVE_STATEMENT,
        REMOVE_PLUS, REMOVE_BINARY
    }

    var modified: String? = null
    val applied: Boolean
        get() = modified != null

    fun reset() {
        modified = null
    }

    fun apply(contents: String, random: Random = Random): String {
        val wasBlank = contents.lines()[location.startLine - 1].isBlank()
        val prefix = contents.substring(0 until location.start)
        val target = contents.substring(location.start..location.end)
        val postfix = contents.substring((location.end + 1) until contents.length)

        check(prefix + target + postfix == contents) { "Didn't split string properly" }
        check(target == original) { "Didn't find expected contents before mutation: $target != $original" }
        check(modified == null) { "Mutation already applied" }

        modified = applyMutation(random)

        check(modified != original) { "Mutation did not change the input: $mutationType" }
        return (prefix + modified + postfix).lines().filterIndexed { index, s ->
            if (index + 1 != location.startLine) {
                true
            } else {
                wasBlank || s.isNotBlank()
            }
        }.joinToString("\n")
    }

    abstract fun applyMutation(random: Random = Random): String
    abstract val preservesLength: Boolean
    abstract val estimatedCount: Int
    abstract val mightNotCompile: Boolean
    abstract val fixedCount: Boolean

    override fun toString(): String = "$mutationType: $location ($original)"

    override fun equals(other: Any?) = when {
        this === other -> true
        javaClass != other?.javaClass -> false
        else -> {
            other as Mutation
            mutationType == other.mutationType && location == other.location && original == other.original
        }
    }

    override fun hashCode() = Objects.hash(mutationType, location, original)

    @Suppress("ComplexMethod", "LongMethod", "TooManyFunctions")
    class JavaMutationListener(private val parsedSource: Source.ParsedSource) : JavaParserBaseListener() {
        val lines = parsedSource.contents.lines()
        val mutations: MutableList<Mutation> = mutableListOf()
        private val fileType = Source.FileType.JAVA
        private val currentPath: MutableList<Location.SourcePath> = mutableListOf()
        override fun enterClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
            currentPath.add(Location.SourcePath(Location.SourcePath.Type.CLASS, ctx.IDENTIFIER().text))
        }

        override fun exitClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
            currentPath.last().also {
                check(it.type == Location.SourcePath.Type.CLASS)
                check(it.name == ctx.IDENTIFIER()!!.text)
            }
            currentPath.pop()
        }

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
                if (RemoveMethod.matches(contents, currentReturnType!!)) {
                    mutations.add(RemoveMethod(location, contents, currentReturnType!!))
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
            Location(
                start.startIndex,
                stop.stopIndex,
                currentPath,
                lines.filterIndexed { index, _ -> index >= start.line - 1 && index <= stop.line - 1 }
                    .joinToString("\n"),
                start.line
            )

        private fun Token.toLocation() = Location(startIndex, stopIndex, currentPath, lines[line - 1], line)
        private fun List<TerminalNode>.toLocation() =
            Location(
                first().symbol.startIndex,
                last().symbol.stopIndex,
                currentPath,
                lines.filterIndexed { index, _ -> index >= first().symbol.line - 1 && index <= last().symbol.line - 1 }
                    .joinToString("\n"),
                first().symbol.line
            )

        override fun enterLiteral(ctx: JavaParser.LiteralContext) {
            if (insideAnnotation) {
                return
            }
            ctx.BOOL_LITERAL()?.also {
                ctx.toLocation().also { location ->
                    mutations.add(BooleanLiteral(location, parsedSource.contents(location)))
                }
            }
            ctx.CHAR_LITERAL()?.also {
                ctx.toLocation().also { location ->
                    mutations.add(CharLiteral(location, parsedSource.contents(location)))
                }
            }
            ctx.STRING_LITERAL()?.also {
                ctx.toLocation().also { location ->
                    mutations.add(StringLiteral(location, parsedSource.contents(location)))
                }
            }
            ctx.integerLiteral()?.also { integerLiteral ->
                integerLiteral.DECIMAL_LITERAL()?.also {
                    ctx.toLocation().also { location ->
                        mutations.add(NumberLiteral(location, parsedSource.contents(location)))
                    }
                }
            }
            ctx.floatLiteral()?.also { floatLiteral ->
                floatLiteral.FLOAT_LITERAL()?.also {
                    ctx.toLocation().also { location ->
                        mutations.add(NumberLiteral(location, parsedSource.contents(location)))
                    }
                }
            }
        }

        private fun JavaParser.ExpressionContext.locationPair(): Pair<Location, Location> {
            check(expression().size == 2)
            val front = expression(0)
            val back = expression(1)
            val frontLocation = Location(
                front.start.startIndex,
                back.start.startIndex - 1,
                currentPath,
                lines
                    .filterIndexed { index, _ ->
                        index >= front.start.line - 1 && index <= back.start.line - 1
                    }
                    .joinToString("\n"),
                front.start.line
            )
            val backLocation = Location(
                front.stop.stopIndex + 1,
                back.stop.stopIndex,
                currentPath,
                lines
                    .filterIndexed { index, _ ->
                        index >= front.stop.line - 1 && index <= back.stop.line - 1
                    }
                    .joinToString("\n"),
                front.start.line
            )
            return Pair(frontLocation, backLocation)
        }

        override fun enterExpression(ctx: JavaParser.ExpressionContext) {
            ctx.prefix?.toLocation()?.also { location ->
                val contents = parsedSource.contents(location)
                if (IncrementDecrement.matches(contents)) {
                    mutations.add(IncrementDecrement(location, contents))
                }
                if (InvertNegation.matches(contents)) {
                    mutations.add(InvertNegation(location, contents))
                }
            }
            ctx.postfix?.toLocation()?.also { location ->
                val contents = parsedSource.contents(location)
                if (IncrementDecrement.matches(contents)) {
                    mutations.add(IncrementDecrement(location, contents))
                }
            }

            ctx.LT()?.also { tokens ->
                if (tokens.size == 2) {
                    tokens.toLocation().also { location ->
                        val contents = parsedSource.contents(location)
                        if (MutateMath.matches(contents)) {
                            mutations.add(MutateMath(location, contents))
                        }
                        if (RemoveBinary.matches(contents)) {
                            val (frontLocation, backLocation) = ctx.locationPair()
                            mutations.add(RemoveBinary(frontLocation, parsedSource.contents(frontLocation)))
                            mutations.add(RemoveBinary(backLocation, parsedSource.contents(backLocation)))
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
                    mutations.add(MutateMath(location, contents))
                }
                if (RemoveBinary.matches(contents)) {
                    val (frontLocation, backLocation) = ctx.locationPair()
                    mutations.add(RemoveBinary(frontLocation, parsedSource.contents(frontLocation)))
                    mutations.add(RemoveBinary(backLocation, parsedSource.contents(backLocation)))
                }
            }

            ctx.bop?.toLocation()?.also { location ->
                val contents = parsedSource.contents(location)
                if (ConditionalBoundary.matches(contents)) {
                    mutations.add(ConditionalBoundary(location, contents))
                }
                if (NegateConditional.matches(contents)) {
                    mutations.add(NegateConditional(location, contents))
                }
                if (MutateMath.matches(contents)) {
                    mutations.add(MutateMath(location, contents))
                }
                if (PlusToMinus.matches(contents)) {
                    mutations.add(PlusToMinus(location, contents))
                }
                if (SwapAndOr.matches(contents)) {
                    mutations.add(SwapAndOr(location, contents))
                }
                @Suppress("ComplexCondition")
                if (contents == "&&" || contents == "||") {
                    val (frontLocation, backLocation) = ctx.locationPair()
                    mutations.add(RemoveAndOr(frontLocation, parsedSource.contents(frontLocation)))
                    mutations.add(RemoveAndOr(backLocation, parsedSource.contents(backLocation)))
                }
                if (RemovePlus.matches(contents)) {
                    val (frontLocation, backLocation) = ctx.locationPair()
                    mutations.add(RemovePlus(frontLocation, parsedSource.contents(frontLocation)))
                    mutations.add(RemovePlus(backLocation, parsedSource.contents(backLocation)))
                }
                if (RemoveBinary.matches(contents)) {
                    val (frontLocation, backLocation) = ctx.locationPair()
                    mutations.add(RemoveBinary(frontLocation, parsedSource.contents(frontLocation)))
                    mutations.add(RemoveBinary(backLocation, parsedSource.contents(backLocation)))
                }
            }
        }

        private val seenIfStarts = mutableSetOf<Int>()

        override fun enterStatement(ctx: JavaParser.StatementContext) {
            ctx.IF()?.also {
                val outerLocation = ctx.toLocation()
                if (outerLocation.start !in seenIfStarts) {
                    // Add entire if
                    mutations.add(RemoveIf(outerLocation, parsedSource.contents(outerLocation)))
                    seenIfStarts += outerLocation.start
                    check(ctx.statement().isNotEmpty())
                    if (ctx.statement().size == 2 && ctx.statement(1).block() != null) {
                        // Add else branch (2)
                        check(ctx.ELSE() != null)
                        val start = ctx.ELSE().symbol
                        val end = ctx.statement(1).block().stop
                        val elseLocation =
                            Location(
                                start.startIndex,
                                end.stopIndex,
                                currentPath,
                                lines.filterIndexed { index, _ -> index >= start.line - 1 && index <= end.line - 1 }
                                    .joinToString("\n"),
                                start.line
                            )
                        mutations.add(RemoveIf(elseLocation, parsedSource.contents(elseLocation)))
                    } else if (ctx.statement().size >= 2) {
                        var statement = ctx.statement(1)
                        var previousMarker = ctx.ELSE()
                        check(previousMarker != null)
                        while (statement != null) {
                            if (statement.IF() != null) {
                                seenIfStarts += statement.toLocation().start
                            }
                            val end = statement.statement(0) ?: statement.block()
                            val currentLocation =
                                Location(
                                    previousMarker.symbol.startIndex,
                                    end.stop.stopIndex,
                                    currentPath,
                                    lines
                                        .filterIndexed { index, _ ->
                                            index >= previousMarker.symbol.line - 1 && index <= end.stop.line - 1
                                        }
                                        .joinToString("\n"),
                                    previousMarker.symbol.line
                                )
                            mutations.add(RemoveIf(currentLocation, parsedSource.contents(currentLocation)))
                            previousMarker = statement.ELSE()
                            statement = statement.statement(1)
                        }
                    }
                }
                ctx.parExpression().toLocation().also { location ->
                    mutations.add(NegateIf(location, parsedSource.contents(location)))
                }
            }
            ctx.ASSERT()?.also {
                ctx.toLocation().also { location ->
                    mutations.add(RemoveAssert(location, parsedSource.contents(location)))
                }
            }
            ctx.RETURN()?.also {
                ctx.expression()?.firstOrNull()?.toLocation()?.also { location ->
                    val contents = parsedSource.contents(location)
                    currentReturnType?.also { returnType ->
                        if (PrimitiveReturn.matches(contents, returnType, fileType)) {
                            mutations.add(PrimitiveReturn(location, parsedSource.contents(location)))
                        }
                        if (TrueReturn.matches(contents, returnType)) {
                            mutations.add(TrueReturn(location, parsedSource.contents(location)))
                        }
                        if (FalseReturn.matches(contents, returnType)) {
                            mutations.add(FalseReturn(location, parsedSource.contents(location)))
                        }
                        if (NullReturn.matches(contents, returnType, fileType)) {
                            mutations.add(NullReturn(location, parsedSource.contents(location)))
                        }
                    } ?: error("Should have recorded a return type at this point")
                }
            }
            ctx.WHILE()?.also {
                ctx.parExpression().toLocation().also { location ->
                    mutations.add(NegateWhile(location, parsedSource.contents(location)))
                }
                if (ctx.DO() == null) {
                    mutations.add(RemoveLoop(ctx.toLocation(), parsedSource.contents(ctx.toLocation())))
                }
            }
            ctx.FOR()?.also {
                mutations.add(RemoveLoop(ctx.toLocation(), parsedSource.contents(ctx.toLocation())))
            }
            ctx.DO()?.also {
                mutations.add(RemoveLoop(ctx.toLocation(), parsedSource.contents(ctx.toLocation())))
            }
            ctx.TRY()?.also {
                mutations.add(RemoveTry(ctx.toLocation(), parsedSource.contents(ctx.toLocation())))
            }
            ctx.statementExpression?.also {
                mutations.add(RemoveStatement(ctx.toLocation(), parsedSource.contents(ctx.toLocation())))
            }
        }

        init {
            ParseTreeWalker.DEFAULT.walk(this, parsedSource.tree)
        }
    }

    @Suppress("ComplexMethod", "LongMethod", "TooManyFunctions")
    class KotlinMutationListener(private val parsedSource: Source.ParsedSource) : KotlinParserBaseListener() {
        val lines = parsedSource.contents.lines()
        val mutations: MutableList<Mutation> = mutableListOf()
        private val fileType = Source.FileType.KOTLIN

        private val currentPath: MutableList<Location.SourcePath> = mutableListOf()
        override fun enterClassDeclaration(ctx: KotlinParser.ClassDeclarationContext) {
            currentPath.add(Location.SourcePath(Location.SourcePath.Type.CLASS, ctx.simpleIdentifier().text))
        }

        override fun exitClassDeclaration(ctx: KotlinParser.ClassDeclarationContext) {
            currentPath.last().also {
                check(it.type == Location.SourcePath.Type.CLASS)
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
            println(currentReturnType)
            println("enterFunctionBody: " + ctx.text)
            val methodLocation = ctx.block().toLocation()
            val methodContents = parsedSource.contents(methodLocation)
            if (RemoveMethod.matches(methodContents, currentReturnType!!)) {
                mutations.add(RemoveMethod(methodLocation, methodContents, currentReturnType!!))
            }
            ctx.block().statements().statement().filter { it.start.text == "return" }.forEach {
                val returnValue = it.stop.text
                val returnLocation = it.stop.toLocation()

                if (PrimitiveReturn.matches(returnValue, currentReturnType!!, fileType)) {
                    mutations.add(PrimitiveReturn(returnLocation, returnValue))
                }
                if (TrueReturn.matches(returnValue, currentReturnType!!)) {
                    mutations.add(TrueReturn(returnLocation, returnValue))
                }
                if (FalseReturn.matches(returnValue, currentReturnType!!)) {
                    mutations.add(FalseReturn(returnLocation, returnValue))
                }
                if (NullReturn.matches(returnValue, currentReturnType!!, fileType)) {
                    mutations.add(NullReturn(returnLocation, returnValue)) // todo: fix this
                }
            }
        }

        override fun enterStatement(ctx: KotlinParser.StatementContext) {
            if (ctx.start.text == "assert" ||
                ctx.start.text == "check" ||
                ctx.start.text == "require"
            ) {
                mutations.add(RemoveAssert(ctx.toLocation(), ctx.text))
            }
        }

        override fun enterStatements(ctx: KotlinParser.StatementsContext) {
            println("enterStatements: " + ctx.text)
        }

        private var insideAnnotation = false
        override fun enterAnnotation(ctx: KotlinParser.AnnotationContext?) {
            insideAnnotation = true
        }

        override fun exitAnnotation(ctx: KotlinParser.AnnotationContext?) {
            insideAnnotation = false
        }

        private fun ParserRuleContext.toLocation() =
            Location(
                start.startIndex,
                stop.stopIndex,
                currentPath,
                lines.filterIndexed { index, _ -> index >= start.line - 1 && index <= stop.line - 1 }
                    .joinToString("\n"),
                start.line
            )

        private fun Token.toLocation() = Location(startIndex, stopIndex, currentPath, lines[line - 1], line)
        private fun List<TerminalNode>.toLocation() =
            Location(
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
                    mutations.add(BooleanLiteral(location, parsedSource.contents(location)))
                }
            }
            ctx.IntegerLiteral()?.also {
                ctx.toLocation().also { location ->
                    mutations.add(NumberLiteral(location, parsedSource.contents(location)))
                }
            }
            ctx.stringLiteral()?.also {
                ctx.toLocation().also { location ->
                    mutations.add(StringLiteral(location, parsedSource.contents(location)))
                }
            }
            ctx.HexLiteral()?.also {
                ctx.toLocation().also { location ->
                    mutations.add(NumberLiteral(location, parsedSource.contents(location), 16))
                }
            }
            ctx.BinLiteral()?.also {
                ctx.toLocation().also { location ->
                    mutations.add(NumberLiteral(location, parsedSource.contents(location), 2))
                }
            }
            ctx.CharacterLiteral()?.also {
                ctx.toLocation().also { location ->
                    mutations.add(CharLiteral(location, parsedSource.contents(location)))
                }
            }
            // reals are doubles and floats
            ctx.RealLiteral()?.also {
                ctx.toLocation().also { location ->
                    mutations.add(NumberLiteral(location, parsedSource.contents(location)))
                }
            }
            ctx.LongLiteral()?.also {
                ctx.toLocation().also { location ->
                    mutations.add(NumberLiteral(location, parsedSource.contents(location)))
                }
            }
            ctx.NullLiteral()?.also {
                // println("null literal: " + ctx.text)
                ctx.toLocation().also { location ->
                    // todo: null literals
                }
            }
        }

        /*
        private fun KotlinParser.ExpressionContext.locationPair(): Pair<Location, Location> {
            val front = start
            val back = stop
            val frontLocation = Location(
                start.startIndex,
                start.startIndex - 1,
                currentPath,
                lines
                    .filterIndexed { index, _ ->
                        index >= front.line - 1 && index <= back.line - 1
                    }
                    .joinToString("\n"),
                front.line
            )
            val backLocation = Location(
                front.stopIndex + 1,
                back.stopIndex,
                currentPath,
                lines
                    .filterIndexed { index, _ ->
                        index >= front.line - 1 && index <= back.line - 1
                    }
                    .joinToString("\n"),
                front.line
            )
            return Pair(frontLocation, backLocation)
        }
         */

        override fun enterExpression(ctx: KotlinParser.ExpressionContext) {

            //
            // // I'm not sure why you can't write this like the other ones, but it fails with a cast to kotlin.Unit
            // // exception
            // @Suppress("MagicNumber")
            // if (ctx.GT() != null && (ctx.GT().size == 2 || ctx.GT().size == 3)) {
            //     val location = ctx.GT().toLocation()
            //     val contents = parsedSource.contents(location)
            //     if (RemoveBinary.matches(contents)) {
            //         val (frontLocation, backLocation) = ctx.locationPair()
            //         mutations.add(RemoveBinary(frontLocation, parsedSource.contents(frontLocation)))
            //         mutations.add(RemoveBinary(backLocation, parsedSource.contents(backLocation)))
            //     }
            // }
            //
            //     @Suppress("ComplexCondition")
            //     if (contents == "&&" || contents == "||") {
            //         val (frontLocation, backLocation) = ctx.locationPair()
            //         mutations.add(RemoveAndOr(frontLocation, parsedSource.contents(frontLocation)))
            //         mutations.add(RemoveAndOr(backLocation, parsedSource.contents(backLocation)))
            //     }
            //     if (RemovePlus.matches(contents)) {
            //         val (frontLocation, backLocation) = ctx.locationPair()
            //         mutations.add(RemovePlus(frontLocation, parsedSource.contents(frontLocation)))
            //         mutations.add(RemovePlus(backLocation, parsedSource.contents(backLocation)))
            //     }
            // }
        }

        override fun enterConditionalExpression(ctx: KotlinParser.ConditionalExpressionContext) {
        }

        override fun enterPrefixUnaryOperation(ctx: KotlinParser.PrefixUnaryOperationContext) {
            if (IncrementDecrement.matches(ctx.text)) {
                mutations.add(IncrementDecrement(ctx.toLocation(), ctx.text))
            }
            if (InvertNegation.matches(ctx.text)) {
                mutations.add(InvertNegation(ctx.toLocation(), ctx.text))
            }
        }

        override fun enterPostfixUnaryOperation(ctx: KotlinParser.PostfixUnaryOperationContext) {
            if (IncrementDecrement.matches(ctx.text)) {
                mutations.add(IncrementDecrement(ctx.toLocation(), ctx.text))
            }
        }

        override fun enterComparisonOperator(ctx: KotlinParser.ComparisonOperatorContext) {
            if (ConditionalBoundary.matches(ctx.text)) {
                mutations.add(ConditionalBoundary(ctx.toLocation(), ctx.text))
            }
            if (NegateConditional.matches(ctx.text)) {
                mutations.add(NegateConditional(ctx.toLocation(), ctx.text))
            }
        }

        override fun enterMultiplicativeOperation(ctx: KotlinParser.MultiplicativeOperationContext) {
            if (MutateMath.matches(ctx.text)) {
                mutations.add(MutateMath(ctx.toLocation(), ctx.text))
            }
        }

        override fun enterAdditiveOperator(ctx: KotlinParser.AdditiveOperatorContext) {
            if (PlusToMinus.matches(ctx.text)) {
                mutations.add(PlusToMinus(ctx.toLocation(), ctx.text))
            }
            if (MutateMath.matches(ctx.text)) {
                mutations.add(MutateMath(ctx.toLocation(), ctx.text))
            }
        }

        override fun enterEqualityOperation(ctx: KotlinParser.EqualityOperationContext) {
            if (NegateConditional.matches(ctx.text)) {
                mutations.add(NegateConditional(ctx.toLocation(), ctx.text))
            }
        }

        override fun enterDisjunction(ctx: KotlinParser.DisjunctionContext) {
            if (SwapAndOr.matches(ctx.DISJ().joinToString())) {
                require(ctx.DISJ().size == 1) { "Disjunction list has an invalid size" }
                mutations.add(SwapAndOr(ctx.DISJ().toLocation(), ctx.DISJ().joinToString()))
            }
        }

        override fun enterConjunction(ctx: KotlinParser.ConjunctionContext) {
            if (SwapAndOr.matches(ctx.CONJ().joinToString())) {
                require(ctx.CONJ().size == 1) { "Conjunction list has an invalid size" }
                mutations.add(SwapAndOr(ctx.CONJ().toLocation(), ctx.CONJ().joinToString()))
            }
        }

        private val seenIfStarts = mutableSetOf<Int>()

        /*
        override fun enterStatement(ctx: KotlinParser.StatementContext) {
            ctx.StatementContext()?.also {
                val outerLocation = ctx.toLocation()
                if (outerLocation.start !in seenIfStarts) {
                    // Add entire if
                    mutations.add(RemoveIf(outerLocation, parsedSource.contents(outerLocation)))
                    seenIfStarts += outerLocation.start
                    check(ctx.statement().isNotEmpty())
                    if (ctx.statement().size == 2 && ctx.statement(1).block() != null) {
                        // Add else branch (2)
                        check(ctx.ELSE() != null)
                        val start = ctx.ELSE().symbol
                        val end = ctx.statement(1).block().stop
                        val elseLocation =
                            Location(
                                start.startIndex,
                                end.stopIndex,
                                currentPath,
                                lines.filterIndexed { index, _ -> index >= start.line - 1 && index <= end.line - 1 }
                                    .joinToString("\n"),
                                start.line
                            )
                        mutations.add(RemoveIf(elseLocation, parsedSource.contents(elseLocation)))
                    } else if (ctx.statement().size >= 2) {
                        var statement = ctx.statement(1)
                        var previousMarker = ctx.ELSE()
                        check(previousMarker != null)
                        while (statement != null) {
                            if (statement.IF() != null) {
                                seenIfStarts += statement.toLocation().start
                            }
                            val end = statement.statement(0) ?: statement.block()
                            val currentLocation =
                                Location(
                                    previousMarker.symbol.startIndex,
                                    end.stop.stopIndex,
                                    currentPath,
                                    lines
                                        .filterIndexed { index, _ ->
                                            index >= previousMarker.symbol.line - 1 && index <= end.stop.line - 1
                                        }
                                        .joinToString("\n"),
                                    previousMarker.symbol.line
                                )
                            mutations.add(RemoveIf(currentLocation, parsedSource.contents(currentLocation)))
                            previousMarker = statement.ELSE()
                            statement = statement.statement(1)
                        }
                    }
                }
                ctx.parExpression().toLocation().also { location ->
                    mutations.add(NegateIf(location, parsedSource.contents(location)))
                }
            }
            ctx.ASSERT()?.also {
                ctx.toLocation().also { location ->
                    mutations.add(RemoveAssert(location, parsedSource.contents(location)))
                }
            }
            ctx.RETURN()?.also {
                ctx.expression()?.firstOrNull()?.toLocation()?.also { location ->
                    val contents = parsedSource.contents(location)
                    currentReturnType?.also { returnType ->
                        if (PrimitiveReturn.matches(contents, returnType)) {
                            mutations.add(PrimitiveReturn(location, parsedSource.contents(location)))
                        }
                        if (TrueReturn.matches(contents, returnType)) {
                            mutations.add(TrueReturn(location, parsedSource.contents(location)))
                        }
                        if (FalseReturn.matches(contents, returnType)) {
                            mutations.add(FalseReturn(location, parsedSource.contents(location)))
                        }
                        if (NullReturn.matches(contents, returnType)) {
                            mutations.add(NullReturn(location, parsedSource.contents(location)))
                        }
                    } ?: error("Should have recorded a return type at this point")
                }
            }
            ctx.WHILE()?.also {
                ctx.parExpression().toLocation().also { location ->
                    mutations.add(NegateWhile(location, parsedSource.contents(location)))
                }
                if (ctx.DO() == null) {
                    mutations.add(RemoveLoop(ctx.toLocation(), parsedSource.contents(ctx.toLocation())))
                }
            }
            ctx.FOR()?.also {
                mutations.add(RemoveLoop(ctx.toLocation(), parsedSource.contents(ctx.toLocation())))
            }
            ctx.DO()?.also {
                mutations.add(RemoveLoop(ctx.toLocation(), parsedSource.contents(ctx.toLocation())))
            }
            ctx.TRY()?.also {
                mutations.add(RemoveTry(ctx.toLocation(), parsedSource.contents(ctx.toLocation())))
            }
            ctx.statementExpression?.also {
                mutations.add(RemoveStatement(ctx.toLocation(), parsedSource.contents(ctx.toLocation())))
            }
        }
        */

        init {
            ParseTreeWalker.DEFAULT.walk(this, parsedSource.tree)
        }
    }

    companion object {
        inline fun <reified T : Mutation> find(parsedSource: Source.ParsedSource, fileType: Source.FileType): List<T> =
            when (fileType) {
                Source.FileType.JAVA -> JavaMutationListener(parsedSource).mutations.filterIsInstance<T>()
                Source.FileType.KOTLIN -> KotlinMutationListener(parsedSource).mutations.filterIsInstance<T>()
            }
    }
}
