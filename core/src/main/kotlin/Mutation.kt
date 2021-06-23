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
    Mutation.Type.REMOVE_RUNTIME_CHECK,
    Mutation.Type.REMOVE_METHOD,
    Mutation.Type.NEGATE_IF,
    Mutation.Type.REMOVE_IF,
    Mutation.Type.REMOVE_LOOP,
    Mutation.Type.REMOVE_AND_OR,
    Mutation.Type.REMOVE_TRY,
    Mutation.Type.REMOVE_STATEMENT,
    Mutation.Type.REMOVE_PLUS,
    Mutation.Type.REMOVE_BINARY,
    Mutation.Type.CHANGE_EQUALS,
)
val ALL = PITEST + OTHER

fun Mutation.Type.suppressionComment() = "mutate-disable-" + mutationName()
fun Mutation.Type.mutationName() = name.lowercase().replace("_", "-")

sealed class Mutation(
    val mutationType: Type,
    var location: Location,
    val original: String,
    val fileType: Source.FileType
) {
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
        REMOVE_RUNTIME_CHECK, REMOVE_METHOD,
        NEGATE_IF, NEGATE_WHILE, REMOVE_IF, REMOVE_LOOP, REMOVE_AND_OR, REMOVE_TRY, REMOVE_STATEMENT,
        REMOVE_PLUS, REMOVE_BINARY, CHANGE_EQUALS
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
                if (RemoveMethod.matches(contents, currentReturnType!!, fileType)) {
                    mutations.add(RemoveMethod(location, contents, currentReturnType!!, fileType))
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
                    mutations.add(BooleanLiteral(location, parsedSource.contents(location), fileType))
                }
            }
            ctx.CHAR_LITERAL()?.also {
                ctx.toLocation().also { location ->
                    mutations.add(CharLiteral(location, parsedSource.contents(location), fileType))
                }
            }
            ctx.STRING_LITERAL()?.also {
                ctx.toLocation().also { location ->
                    mutations.add(StringLiteral(location, parsedSource.contents(location), fileType))
                }
            }
            ctx.integerLiteral()?.also { integerLiteral ->
                integerLiteral.DECIMAL_LITERAL()?.also {
                    ctx.toLocation().also { location ->
                        mutations.add(NumberLiteral(location, parsedSource.contents(location), fileType))
                    }
                }
            }
            ctx.floatLiteral()?.also { floatLiteral ->
                floatLiteral.FLOAT_LITERAL()?.also {
                    ctx.toLocation().also { location ->
                        mutations.add(NumberLiteral(location, parsedSource.contents(location), fileType))
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
                    mutations.add(IncrementDecrement(location, contents, fileType))
                }
                if (InvertNegation.matches(contents)) {
                    mutations.add(InvertNegation(location, contents, fileType))
                }
            }
            ctx.postfix?.toLocation()?.also { location ->
                val contents = parsedSource.contents(location)
                if (IncrementDecrement.matches(contents)) {
                    mutations.add(IncrementDecrement(location, contents, fileType))
                }
            }

            ctx.LT()?.also { tokens ->
                if (tokens.size == 2) {
                    tokens.toLocation().also { location ->
                        val contents = parsedSource.contents(location)
                        if (MutateMath.matches(contents)) {
                            mutations.add(MutateMath(location, contents, fileType))
                        }
                        if (RemoveBinary.matches(contents)) {
                            val (frontLocation, backLocation) = ctx.locationPair()
                            mutations.add(RemoveBinary(frontLocation, parsedSource.contents(frontLocation), fileType))
                            mutations.add(RemoveBinary(backLocation, parsedSource.contents(backLocation), fileType))
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
                    mutations.add(MutateMath(location, contents, fileType))
                }
                if (RemoveBinary.matches(contents)) {
                    val (frontLocation, backLocation) = ctx.locationPair()
                    mutations.add(RemoveBinary(frontLocation, parsedSource.contents(frontLocation), fileType))
                    mutations.add(RemoveBinary(backLocation, parsedSource.contents(backLocation), fileType))
                }
            }

            ctx.bop?.toLocation()?.also { location ->
                val contents = parsedSource.contents(location)
                if (ConditionalBoundary.matches(contents)) {
                    mutations.add(ConditionalBoundary(location, contents, fileType))
                }
                if (NegateConditional.matches(contents)) {
                    mutations.add(NegateConditional(location, contents, fileType))
                }
                if (MutateMath.matches(contents)) {
                    mutations.add(MutateMath(location, contents, fileType))
                }
                if (PlusToMinus.matches(contents)) {
                    mutations.add(PlusToMinus(location, contents, fileType))
                }
                if (SwapAndOr.matches(contents)) {
                    mutations.add(SwapAndOr(location, contents, fileType))
                }
                @Suppress("ComplexCondition")
                if (contents == "&&" || contents == "||") {
                    val (frontLocation, backLocation) = ctx.locationPair()
                    mutations.add(RemoveAndOr(frontLocation, parsedSource.contents(frontLocation), fileType))
                    mutations.add(RemoveAndOr(backLocation, parsedSource.contents(backLocation), fileType))
                }
                if (RemovePlus.matches(contents)) {
                    val (frontLocation, backLocation) = ctx.locationPair()
                    mutations.add(RemovePlus(frontLocation, parsedSource.contents(frontLocation), fileType))
                    mutations.add(RemovePlus(backLocation, parsedSource.contents(backLocation), fileType))
                }
                if (RemoveBinary.matches(contents)) {
                    val (frontLocation, backLocation) = ctx.locationPair()
                    mutations.add(RemoveBinary(frontLocation, parsedSource.contents(frontLocation), fileType))
                    mutations.add(RemoveBinary(backLocation, parsedSource.contents(backLocation), fileType))
                }
                if (contents == "==") {
                    mutations.add(
                        ChangeEquals(
                            ctx.toLocation(),
                            parsedSource.contents(ctx.toLocation()),
                            fileType,
                            "==",
                            ctx.expression(0).text,
                            ctx.expression(1).text
                        )
                    )
                }
                if (contents == "." &&
                    ctx.methodCall() != null &&
                    ctx.methodCall().IDENTIFIER().text == "equals" &&
                    ctx.methodCall().expressionList().expression().size == 1
                ) {
                    mutations.add(
                        ChangeEquals(
                            ctx.toLocation(),
                            parsedSource.contents(ctx.toLocation()),
                            fileType,
                            ".equals",
                            ctx.expression(0).text,
                            ctx.methodCall().expressionList().expression(0).text
                        )
                    )
                }
            }
        }

        private val seenIfStarts = mutableSetOf<Int>()

        override fun enterStatement(ctx: JavaParser.StatementContext) {
            ctx.IF()?.also {
                val outerLocation = ctx.toLocation()
                if (outerLocation.start !in seenIfStarts) {
                    // Add entire if
                    mutations.add(RemoveIf(outerLocation, parsedSource.contents(outerLocation), fileType))
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
                        mutations.add(RemoveIf(elseLocation, parsedSource.contents(elseLocation), fileType))
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
                            mutations.add(RemoveIf(currentLocation, parsedSource.contents(currentLocation), fileType))
                            previousMarker = statement.ELSE()
                            statement = statement.statement(1)
                        }
                    }
                }
                ctx.parExpression().toLocation().also { location ->
                    mutations.add(NegateIf(location, parsedSource.contents(location), fileType))
                }
            }
            ctx.ASSERT()?.also {
                ctx.toLocation().also { location ->
                    mutations.add(RemoveRuntimeCheck(location, parsedSource.contents(location), fileType))
                }
            }
            ctx.RETURN()?.also {
                ctx.expression()?.firstOrNull()?.toLocation()?.also { location ->
                    val contents = parsedSource.contents(location)
                    currentReturnType?.also { returnType ->
                        if (PrimitiveReturn.matches(contents, returnType, fileType)) {
                            mutations.add(PrimitiveReturn(location, parsedSource.contents(location), fileType))
                        }
                        if (TrueReturn.matches(contents, returnType)) {
                            mutations.add(TrueReturn(location, parsedSource.contents(location), fileType))
                        }
                        if (FalseReturn.matches(contents, returnType)) {
                            mutations.add(FalseReturn(location, parsedSource.contents(location), fileType))
                        }
                        if (NullReturn.matches(contents, returnType, fileType)) {
                            mutations.add(NullReturn(location, parsedSource.contents(location), fileType))
                        }
                    } ?: error("Should have recorded a return type at this point")
                }
            }
            ctx.WHILE()?.also {
                ctx.parExpression().toLocation().also { location ->
                    mutations.add(NegateWhile(location, parsedSource.contents(location), fileType))
                }
                if (ctx.DO() == null) {
                    mutations.add(RemoveLoop(ctx.toLocation(), parsedSource.contents(ctx.toLocation()), fileType))
                }
            }
            ctx.FOR()?.also {
                mutations.add(RemoveLoop(ctx.toLocation(), parsedSource.contents(ctx.toLocation()), fileType))
            }
            ctx.DO()?.also {
                mutations.add(RemoveLoop(ctx.toLocation(), parsedSource.contents(ctx.toLocation()), fileType))
            }
            ctx.TRY()?.also {
                mutations.add(RemoveTry(ctx.toLocation(), parsedSource.contents(ctx.toLocation()), fileType))
            }
            ctx.statementExpression?.also {
                mutations.add(RemoveStatement(ctx.toLocation(), parsedSource.contents(ctx.toLocation()), fileType))
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
            Location(
                start.startIndex,
                stop.stopIndex,
                currentPath,
                lines.filterIndexed { index, _ -> index >= start.line - 1 && index <= stop.line - 1 }
                    .joinToString("\n"),
                start.line
            )

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
                    val location = Location(
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
                    val location = Location(
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

        private fun <T : ParserRuleContext> locationPairHelper(front: T, back: T): Pair<Location, Location> {
            val frontLocation = Location(
                front.start.startIndex,
                back.start.startIndex - 1,
                currentPath,
                lines
                    .filterIndexed { index, _ -> index >= front.start.line - 1 && index <= back.start.line - 1 }
                    .joinToString("\n"),
                front.start.line
            )
            val backLocation = Location(
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

        private fun KotlinParser.AdditiveExpressionContext.locationPair(): Pair<Location, Location> {
            return locationPairHelper<KotlinParser.MultiplicativeExpressionContext>(
                multiplicativeExpression(0), multiplicativeExpression(1)
            )
        }

        private fun KotlinParser.ConjunctionContext.locationPair(): Pair<Location, Location> {
            return locationPairHelper<KotlinParser.EqualityComparisonContext>(
                equalityComparison(0), equalityComparison(1)
            )
        }

        private fun KotlinParser.DisjunctionContext.locationPair(): Pair<Location, Location> {
            return locationPairHelper<KotlinParser.ConjunctionContext>(conjunction(0), conjunction(1))
        }

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
