@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package edu.illinois.cs.cs125.jeed.core

import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParserBaseListener
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.misc.Interval
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.TerminalNode
import org.jetbrains.kotlin.backend.common.pop
import java.util.Objects
import kotlin.math.pow
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
    Mutation.Type.NULL_RETURN
)
val OTHER = setOf(
    Mutation.Type.REMOVE_ASSERT,
    Mutation.Type.REMOVE_METHOD,
    Mutation.Type.NEGATE_IF,
    Mutation.Type.REMOVE_IF,
    Mutation.Type.REMOVE_LOOP,
    Mutation.Type.REMOVE_AND_OR
)
val ALL = PITEST + OTHER

fun Mutation.Type.suppressionComment() = "mutate-disable-" + mutationName()
fun Mutation.Type.mutationName() = name.lowercase().replace("_", "-")

sealed class Mutation(val type: Type, var location: Location, val original: String) {
    data class Location(val start: Int, val end: Int, val path: List<SourcePath>, val line: String) {
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
        PRIMITIVE_RETURN, TRUE_RETURN, FALSE_RETURN, NULL_RETURN,
        REMOVE_ASSERT, REMOVE_METHOD,
        NEGATE_IF, NEGATE_WHILE, REMOVE_IF, REMOVE_LOOP, REMOVE_AND_OR
    }

    var modified: String? = null
    val applied: Boolean
        get() = modified != null

    fun reset() {
        modified = null
    }

    fun apply(contents: String, random: Random = Random): String {
        val prefix = contents.substring(0 until location.start)
        val target = contents.substring(location.start..location.end)
        val postfix = contents.substring((location.end + 1) until contents.length)

        check(prefix + target + postfix == contents) { "Didn't split string properly" }
        check(target == original) { "Didn't find expected contents before mutation: $target != $original" }
        check(modified == null) { "Mutation already applied" }

        modified = applyMutation(random)

        check(modified != original) { "Mutation did not change the input: $type" }

        return prefix + modified + postfix
    }

    abstract fun applyMutation(random: Random = Random): String
    abstract val preservesLength: Boolean
    abstract val estimatedCount: Int

    override fun toString(): String = "$type: $location ($original)"

    override fun equals(other: Any?) = when {
        this === other -> true
        javaClass != other?.javaClass -> false
        else -> {
            other as Mutation
            type == other.type && location == other.location && original == other.original
        }
    }

    override fun hashCode() = Objects.hash(type, location, original)

    @Suppress("ComplexMethod", "LongMethod", "TooManyFunctions")
    class Listener(private val parsedSource: Source.ParsedSource) : JavaParserBaseListener() {
        val lines = parsedSource.contents.lines()
        val mutations: MutableList<Mutation> = mutableListOf()

        private val currentPath: MutableList<Location.SourcePath> = mutableListOf()
        override fun enterClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
            currentPath.add(Location.SourcePath(Location.SourcePath.Type.CLASS, ctx.IDENTIFIER()!!.text))
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
                lines.filterIndexed { index, _ -> index >= start.line - 1 && index <= stop.line - 1 }.joinToString("\n")
            )

        private fun Token.toLocation() = Location(startIndex, stopIndex, currentPath, lines[line - 1])
        private fun List<TerminalNode>.toLocation() =
            Location(
                first().symbol.startIndex,
                last().symbol.stopIndex,
                currentPath,
                lines.filterIndexed { index, _ -> index >= first().symbol.line - 1 && index <= last().symbol.line - 1 }
                    .joinToString("\n")
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
                if (SwapAndOr.matches(contents)) {
                    mutations.add(SwapAndOr(location, contents))
                }
                @Suppress("ComplexCondition")
                if (contents == "&&" || contents == "||") {
                    check(ctx.expression().size == 2)
                    val front = ctx.expression(0)
                    val back = ctx.expression(1)
                    val frontLocation = Location(
                        front.start.startIndex,
                        back.start.startIndex - 1,
                        currentPath,
                        lines
                            .filterIndexed { index, _ ->
                                index >= front.start.line - 1 && index <= back.start.line - 1
                            }
                            .joinToString("\n")
                    )
                    val backLocation = Location(
                        front.stop.stopIndex + 1,
                        back.stop.stopIndex,
                        currentPath,
                        lines
                            .filterIndexed { index, _ ->
                                index >= front.stop.line - 1 && index <= back.stop.line - 1
                            }
                            .joinToString("\n")
                    )
                    mutations.add(RemoveAndOr(frontLocation, parsedSource.contents(frontLocation)))
                    mutations.add(RemoveAndOr(backLocation, parsedSource.contents(backLocation)))
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
                                    .joinToString("\n")
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
                                        .joinToString("\n")
                                )
                            mutations.add(RemoveIf(currentLocation, parsedSource.contents(currentLocation)))
                            previousMarker = statement.ELSE()
                            statement = statement.statement(1)
                        }
                    }
                }
            }
            ctx.IF()?.also {
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
                mutations.add(RemoveLoop(ctx.toLocation(), parsedSource.contents(ctx.toLocation())))
            }
            ctx.FOR()?.also {
                mutations.add(RemoveLoop(ctx.toLocation(), parsedSource.contents(ctx.toLocation())))
            }
        }

        init {
            ParseTreeWalker.DEFAULT.walk(this, parsedSource.tree)
        }
    }

    companion object {
        inline fun <reified T : Mutation> find(parsedSource: Source.ParsedSource): List<T> =
            Listener(parsedSource).mutations.filterIsInstance<T>()
    }
}

class BooleanLiteral(
    location: Location,
    original: String
) : Mutation(Type.BOOLEAN_LITERAL, location, original) {
    override val preservesLength = false
    override val estimatedCount = 1

    override fun applyMutation(random: Random): String {
        return when (original) {
            "true" -> "false"
            "false" -> "true"
            else -> error("${this.javaClass.name} didn't find expected text")
        }
    }
}

val ALPHANUMERIC_CHARS = (('a'..'z') + ('A'..'Z') + ('0'..'9')).toSet()

class CharLiteral(
    location: Location,
    original: String
) : Mutation(Type.CHAR_LITERAL, location, original) {
    override val preservesLength = true
    override val estimatedCount = ALPHANUMERIC_CHARS.size - 1

    private val character = original.removeSurrounding("'").also {
        check(it.length == 1) { "Character didn't have the correct length: $original" }
    }.first()

    override fun applyMutation(random: Random): String =
        ALPHANUMERIC_CHARS.filter { it != character }.shuffled(random).first().let { "'$it'" }
}

val NUMERIC_CHARS = ('0'..'9').toSet()

val ALPHANUMERIC_CHARS_AND_SPACE = (('a'..'z') + ('A'..'Z') + ('0'..'9') + (' ')).toSet()

class StringLiteral(location: Location, original: String) : Mutation(Type.STRING_LITERAL, location, original) {
    override val preservesLength = true
    private val string = original.removeSurrounding("\"")
    override val estimatedCount = ALPHANUMERIC_CHARS_AND_SPACE.size.toDouble().pow(string.length).toInt() - 1

    @Suppress("NestedBlockDepth")
    override fun applyMutation(random: Random): String {
        return if (string.isEmpty()) {
            " "
        } else {
            string.toCharArray().let { characters ->
                val position = random.nextInt(characters.size).let {
                    // Avoid adding invalid escapes
                    if (it > 0 && characters[it - 1] == '\\') {
                        it - 1
                    } else {
                        it
                    }
                }
                characters[position] =
                    (ALPHANUMERIC_CHARS_AND_SPACE.filter { it != characters[position] }).shuffled(random).first()
                characters.joinToString("")
            }
        }.let {
            "\"$it\""
        }
    }
}

class NumberLiteral(
    location: Location,
    original: String,
    private val base: Int = 10
) : Mutation(Type.NUMBER_LITERAL, location, original) {
    override val preservesLength = true

    private val numberPositions = original
        .toCharArray()
        .mapIndexed { index, c -> Pair(index, c) }
        .filter { it.second in NUMERIC_CHARS }
        .map { it.first }.also {
            check(it.isNotEmpty()) { "No numeric characters in numeric literal" }
        }
    override val estimatedCount = numberPositions.size * 2

    override fun applyMutation(random: Random): String {
        val position = numberPositions.shuffled(random).first()
        return original.toCharArray().also { characters ->
            val direction = random.nextBoolean()
            val randomValue = if (direction) {
                Math.floorMod(characters[position].toString().toInt() + 1, base)
            } else {
                Math.floorMod(characters[position].toString().toInt() - 1 + base, base)
            }.let {
                @Suppress("MagicNumber")
                // Avoid adding leading zeros
                if (position == 0 && it == 0) {
                    if (direction) {
                        1
                    } else {
                        9
                    }
                } else {
                    it
                }
            }
            // Sadder than it needs to be, since int <-> char conversions in Kotlin use ASCII values
            characters[position] = randomValue.toString().toCharArray()[0]
        }.let { String(it) }
    }
}

class IncrementDecrement(
    location: Location,
    original: String
) : Mutation(Type.INCREMENT_DECREMENT, location, original) {
    override val preservesLength = true
    override val estimatedCount = 1

    override fun applyMutation(random: Random): String {
        return when (original) {
            INC -> DEC
            DEC -> INC
            else -> error("${javaClass.name} didn't find the expected text")
        }
    }

    companion object {
        fun matches(contents: String) = contents in setOf(INC, DEC)

        private const val INC = "++"
        private const val DEC = "--"
    }
}

class InvertNegation(
    location: Location,
    original: String
) : Mutation(Type.INVERT_NEGATION, location, original) {
    override val preservesLength = false
    override val estimatedCount = 1

    override fun applyMutation(random: Random): String = ""

    companion object {
        fun matches(contents: String) = contents == "-"
    }
}

class MutateMath(
    location: Location,
    original: String
) : Mutation(Type.MATH, location, original) {
    override val preservesLength = false
    override val estimatedCount = 1

    override fun applyMutation(random: Random): String = when (original) {
        SUBTRACT -> ADD
        MULTIPLY -> DIVIDE
        DIVIDE -> MULTIPLY
        REMAINDER -> MULTIPLY
        BITWISE_AND -> BITWISE_OR
        BITWISE_OR -> BITWISE_AND
        BITWISE_XOR -> BITWISE_AND
        LEFT_SHIFT -> RIGHT_SHIFT
        RIGHT_SHIFT -> LEFT_SHIFT
        UNSIGNED_RIGHT_SHIFT -> LEFT_SHIFT
        else -> error("${javaClass.name} didn't find the expected text")
    }

    companion object {
        const val ADD = "+"
        const val SUBTRACT = "-"
        const val MULTIPLY = "*"
        const val DIVIDE = "/"
        const val REMAINDER = "%"
        const val BITWISE_AND = "&"
        const val BITWISE_OR = "|"
        const val BITWISE_XOR = "^"
        const val LEFT_SHIFT = "<<"
        const val RIGHT_SHIFT = ">>"
        const val UNSIGNED_RIGHT_SHIFT = ">>>"

        fun matches(contents: String) = contents in setOf(
            SUBTRACT, MULTIPLY, DIVIDE, REMAINDER,
            BITWISE_AND, BITWISE_OR, BITWISE_XOR,
            LEFT_SHIFT, RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT
        )
    }
}

object Conditionals {
    const val EQ = "=="
    const val NE = "!="
    const val LT = "<"
    const val LTE = "<="
    const val GT = ">"
    const val GTE = ">="
}

class ConditionalBoundary(
    location: Location,
    original: String
) : Mutation(Type.CONDITIONAL_BOUNDARY, location, original) {
    override val preservesLength = false
    override val estimatedCount = 1

    override fun applyMutation(random: Random): String {
        return when (original) {
            Conditionals.LT -> Conditionals.LTE
            Conditionals.LTE -> Conditionals.LT
            Conditionals.GT -> Conditionals.GTE
            Conditionals.GTE -> Conditionals.GT
            else -> error("${javaClass.name} didn't find the expected text")
        }
    }

    companion object {
        fun matches(contents: String) = contents in setOf(
            Conditionals.LT,
            Conditionals.LTE,
            Conditionals.GT,
            Conditionals.GTE
        )
    }
}

class NegateConditional(
    location: Location,
    original: String
) : Mutation(Type.NEGATE_CONDITIONAL, location, original) {
    override val preservesLength = false
    override val estimatedCount = 1

    override fun applyMutation(random: Random): String {
        return when (original) {
            Conditionals.EQ -> Conditionals.NE
            Conditionals.NE -> Conditionals.EQ
            Conditionals.LTE -> Conditionals.GT
            Conditionals.GT -> Conditionals.LTE
            Conditionals.GTE -> Conditionals.LT
            Conditionals.LT -> Conditionals.GTE
            else -> error("${javaClass.name} didn't find the expected text")
        }
    }

    companion object {
        fun matches(contents: String) = contents in setOf(
            Conditionals.EQ,
            Conditionals.NE,
            Conditionals.LT,
            Conditionals.LTE,
            Conditionals.GT,
            Conditionals.GTE
        )
    }
}

class SwapAndOr(
    location: Location,
    original: String
) : Mutation(Type.SWAP_AND_OR, location, original) {
    override val preservesLength = true
    override val estimatedCount = 1

    override fun applyMutation(random: Random): String {
        return when (original) {
            "&&" -> "||"
            "||" -> "&&"
            else -> error("${javaClass.name} didn't find the expected text")
        }
    }

    companion object {
        fun matches(contents: String) = contents in setOf("&&", "||")
    }
}

private val primitiveTypes = setOf("byte", "short", "int", "long", "float", "double", "char", "boolean")

class PrimitiveReturn(
    location: Location,
    original: String
) : Mutation(Type.PRIMITIVE_RETURN, location, original) {
    override val preservesLength = false
    override val estimatedCount = 1

    override fun applyMutation(random: Random): String = "0"

    companion object {
        private val zeros = setOf("0", "0L", "0.0", "0.0f")
        fun matches(contents: String, returnType: String) =
            contents !in zeros && returnType in (primitiveTypes - "boolean")
    }
}

class TrueReturn(
    location: Location,
    original: String
) : Mutation(Type.TRUE_RETURN, location, original) {
    override val preservesLength = false
    override val estimatedCount = 1

    override fun applyMutation(random: Random): String = "true"

    companion object {
        fun matches(contents: String, returnType: String) =
            contents != "true" && returnType in setOf("boolean", "Boolean")
    }
}

class FalseReturn(
    location: Location,
    original: String
) : Mutation(Type.FALSE_RETURN, location, original) {
    override val preservesLength = false
    override val estimatedCount = 1

    override fun applyMutation(random: Random): String = "false"

    companion object {
        fun matches(contents: String, returnType: String) =
            contents != "false" && returnType in setOf("boolean", "Boolean")
    }
}

class NullReturn(
    location: Location,
    original: String
) : Mutation(Type.NULL_RETURN, location, original) {
    override val preservesLength = false
    override val estimatedCount = 1

    override fun applyMutation(random: Random): String = "null"

    companion object {
        @Suppress("DEPRECATION")
        fun matches(contents: String, returnType: String) = contents != "null" &&
            (returnType == returnType.capitalize() || returnType.endsWith("[]"))
    }
}

class RemoveAssert(
    location: Location,
    original: String
) : Mutation(Type.REMOVE_ASSERT, location, original) {
    override val preservesLength = false
    override val estimatedCount = 1

    override fun applyMutation(random: Random): String = ""
}

class RemoveMethod(
    location: Location,
    original: String,
    private val returnType: String
) : Mutation(Type.REMOVE_METHOD, location, original) {
    override val preservesLength = false
    override val estimatedCount = 1

    private val prefix = getPrefix(original)
    private val postfix = getPostfix(original)

    override fun applyMutation(random: Random) = forReturnType(returnType).let {
        "$prefix$it;$postfix"
    }

    companion object {
        @Suppress("DEPRECATION")
        private fun forReturnType(returnType: String) = when {
            returnType == "String" -> "return \"\""
            returnType == returnType.capitalize() || returnType.endsWith("[]") -> "return null"
            returnType == "void" -> "return"
            returnType == "byte" -> "return 0"
            returnType == "short" -> "return 0"
            returnType == "int" -> "return 0"
            returnType == "long" -> "return 0L"
            returnType == "char" -> "return '0'"
            returnType == "boolean" -> "return false"
            returnType == "float" -> "return 0.0f"
            returnType == "double" -> "return 0.0"
            else -> error("Bad return type: $returnType")
        }

        private fun getPrefix(content: String): String {
            var prefix = ""
            var seenBrace = false
            for (char in content.toCharArray()) {
                if (seenBrace && !char.isWhitespace()) {
                    break
                }
                if (char == '{') {
                    seenBrace = true
                }
                prefix += char
            }
            return prefix
        }

        private fun getPostfix(content: String): String {
            var postfix = ""
            var seenBrace = false
            for (char in content.toCharArray().reversed()) {
                if (seenBrace && !char.isWhitespace()) {
                    break
                }
                if (char == '}') {
                    seenBrace = true
                }
                postfix += char
            }
            return postfix.reversed()
        }

        fun matches(contents: String, returnType: String) = contents.removePrefix(getPrefix(contents)).let {
            it.removeSuffix(getPostfix(it))
        }.let {
            it.isNotBlank() && it.trim().removeSuffix(";").trim() != forReturnType(returnType)
        }
    }
}

class NegateIf(location: Location, original: String) : Mutation(Type.NEGATE_IF, location, original) {
    override val preservesLength = false
    override val estimatedCount = 1
    override fun applyMutation(random: Random) = "(!$original)"
}

class NegateWhile(location: Location, original: String) : Mutation(Type.NEGATE_WHILE, location, original) {
    override val preservesLength = false
    override val estimatedCount = 1
    override fun applyMutation(random: Random) = "(!$original)"
}

class RemoveIf(
    location: Location,
    original: String
) : Mutation(Type.REMOVE_IF, location, original) {
    override val preservesLength = false
    override val estimatedCount = 1

    override fun applyMutation(random: Random): String = ""
}

class RemoveLoop(
    location: Location,
    original: String
) : Mutation(Type.REMOVE_LOOP, location, original) {
    override val preservesLength = false
    override val estimatedCount = 1

    override fun applyMutation(random: Random): String = ""
}

class RemoveAndOr(
    location: Location,
    original: String
) : Mutation(Type.REMOVE_AND_OR, location, original) {
    override val preservesLength = false
    override val estimatedCount = 1

    override fun applyMutation(random: Random): String = ""
}

fun Source.ParsedSource.contents(location: Mutation.Location): String =
    stream.getText(Interval(location.start, location.end))

fun MutableList<Mutation.Location.SourcePath>.klass(): String =
    findLast { it.type == Mutation.Location.SourcePath.Type.CLASS }?.name ?: error("No current class in path")

fun MutableList<Mutation.Location.SourcePath>.method(): String =
    findLast { it.type == Mutation.Location.SourcePath.Type.METHOD }?.name ?: error("No current method in path")

data class SourceMutation(val name: String, val mutation: Mutation)

@Suppress("unused", "MemberVisibilityCanBePrivate")
class MutatedSource(
    sources: Sources,
    val originalSources: Sources,
    val mutations: List<SourceMutation>,
    val appliedMutations: Int,
    val unappliedMutations: Int
) : Source(sources) {
    fun cleaned() = Source(sources.mapValues { removeMutationSuppressions(it.value) })
    fun marked(): Source {
        require(mutations.size == 1) { "Can only mark sources that have been mutated once" }
        val mutation = mutations.first()
        return Source(
            sources.mapValues { (name, modified) ->
                if (name != mutation.name) {
                    return@mapValues modified
                }
                val original = originalSources[name] ?: error("Didn't find original sources")
                check(original != modified) { "Didn't find mutation" }
                val originalLines = original.lines()
                val modifiedLines = modified.lines()
                val delta = DiffUtils.diff(originalLines, modifiedLines).deltas.let {
                    check(it.size == 1)
                    it.first()
                }
                check(delta.type == DeltaType.CHANGE || delta.type == DeltaType.DELETE) {
                    "Found invalid delta type: ${delta.type}"
                }
                var i = 0
                val output = mutableListOf<String>()
                while (i < originalLines.size) {
                    val line = originalLines[i]
                    val nextLine = if (delta.type == DeltaType.CHANGE && i == delta.source.position) {
                        val indentAmount = line.length - line.trimStart().length
                        val currentIndent = " ".repeat(indentAmount)
                        val originalContent = delta.source.lines.joinToString("\n") {
                            currentIndent + "// " + it.substring(indentAmount)
                        }
                        i += delta.source.lines.size
                        """
                    |$currentIndent// Modified by ${mutation.mutation.type.mutationName()}. Originally:
                    |$originalContent
                    |${delta.target.lines.joinToString("\n")}
                    """.trimMargin()
                    } else if (delta.type == DeltaType.DELETE && i == delta.source.position) {
                        val indentAmount = line.length - line.trimStart().length
                        val currentIndent = " ".repeat(indentAmount)
                        val originalContent = delta.source.lines.joinToString("\n") {
                            currentIndent + "// " + it.substring(indentAmount)
                        }
                        i += delta.source.lines.size
                        """
                    |$currentIndent// Removed by ${mutation.mutation.type.mutationName()}. Originally:
                    |$originalContent
                    """.trimMargin()
                    } else {
                        i++
                        line
                    }
                    output += nextLine
                }
                output.joinToString("\n")
            }
        )
    }

    companion object {
        private val matchMutationSuppression = Regex("""\s*// mutate-disable.*$""")
        fun removeMutationSuppressions(contents: String) = contents.lines().joinToString("\n") {
            matchMutationSuppression.replace(it, "")
        }
    }
}

fun MutableMap<String, String>.blankMap(): Map<String, Set<Int>> = mapValues { (_, contents) ->
    contents.lines()
        .mapIndexed { index, line -> Pair(index, line) }
        .filter { (_, line) -> line.isBlank() }
        .map { (index, _) -> index }.toSet()
}

fun MutableMap<String, String>.removeNewBlank(blankMap: Map<String, Set<Int>>) =
    forEach { (path, contents) ->
        val toKeep = contents.lines()
            .mapIndexed { index, line -> Pair(index, line) }
            .filter { (index, line) -> line.isNotBlank() || blankMap[path]!!.contains(index) }
            .map { (index, _) -> index }.toSet()
        this[path] =
            contents.lines().filterIndexed { index, _ -> toKeep.contains(index) }.joinToString("\n")
    }

class Mutater(
    private
    val originalSource: Source,
    shuffle: Boolean,
    seed: Int,
    types: Set<Mutation.Type>
) {

    init {
        check(originalSource.type == Source.FileType.JAVA) { "Can only mutate Java sources" }
    }

    private val random = Random(seed)
    private val mutations = originalSource.sources.keys.map { name ->
        Mutation.find<Mutation>(originalSource.getParsed(name))
            .map { mutation -> SourceMutation(name, mutation) }
            .filter {
                types.contains(it.mutation.type)
            }
    }.flatten().let {
        if (shuffle) {
            it.shuffled(random)
        } else {
            it
        }
    }
    private val availableMutations: MutableList<SourceMutation> = mutations.toMutableList()
    val appliedMutations: MutableList<SourceMutation> = mutableListOf()

    val size: Int
        get() = availableMutations.size

    val sources = originalSource.sources.toMutableMap()
    internal fun apply(): Sources {
        check(availableMutations.isNotEmpty()) { "No more mutations to apply" }
        availableMutations.removeAt(0).also { sourceMutation ->
            val original = sources[sourceMutation.name] ?: error("Couldn't find key that should be there")
            val modified = sourceMutation.mutation.apply(original, random)
            check(original != modified) { "Mutation did not change source" }

            appliedMutations.add(sourceMutation)
            availableMutations.removeIf { it.mutation.overlaps(sourceMutation.mutation) }
            availableMutations.filter { it.mutation.after(sourceMutation.mutation) }.forEach {
                it.mutation.shift(modified.length - original.length)
            }

            sources[sourceMutation.name] = modified
        }
        return Sources(sources)
    }

    fun mutate(limit: Int = 1, removeBlank: Boolean = true): MutatedSource {
        check(appliedMutations.isEmpty()) { "Some mutations already applied" }
        val blankMap = sources.blankMap()
        @Suppress("UnusedPrivateMember")
        for (unused in 0 until limit) {
            if (availableMutations.isEmpty()) {
                break
            }
            apply()
        }
        if (removeBlank) {
            sources.removeNewBlank(blankMap)
        }
        return MutatedSource(
            Sources(sources),
            originalSource.sources,
            appliedMutations,
            appliedMutations.size,
            availableMutations.size
        )
    }
}

fun Source.mutater(shuffle: Boolean = true, seed: Int = Random.nextInt(), types: Set<Mutation.Type> = ALL) =
    Mutater(this, shuffle, seed, types = types)

fun Source.mutate(
    shuffle: Boolean = true,
    seed: Int = Random.nextInt(),
    limit: Int = 1,
    types: Set<Mutation.Type> = Mutation.Type.values().toSet()
) =
    Mutater(this, shuffle, seed, types).mutate(limit)

fun SourceMutation.suppressed() = mutation.location.line.lines().any { line ->
    line.split("""//""").let { parts ->
        parts.size == 2 && (
            parts[1].split(" ").contains("mutate-disable") ||
                parts[1].split(" ").contains(mutation.type.suppressionComment())
            )
    }
}

fun Source.allMutations(
    suppressWithComments: Boolean = true,
    random: Random = Random,
    types: Set<Mutation.Type> = ALL,
    removeBlank: Boolean = true
): List<MutatedSource> {
    check(type == Source.FileType.JAVA) { "Can only mutate Java sources" }
    val mutations = sources.keys.map { name ->
        Mutation.find<Mutation>(getParsed(name)).map { mutation -> SourceMutation(name, mutation) }
    }.flatten()
        .filter { types.contains(it.mutation.type) }
        .filter { !suppressWithComments || !it.suppressed() }

    return mutations.map { sourceMutation ->
        val modifiedSources = sources.copy().toMutableMap()
        val blankMap = modifiedSources.blankMap()
        val original =
            modifiedSources[sourceMutation.name] ?: error("Couldn't find a source that should be there")
        val modified = sourceMutation.mutation.apply(original, random)
        check(original != modified) { "Mutation did not change source" }
        modifiedSources[sourceMutation.name] = modified
        if (removeBlank) {
            modifiedSources.removeNewBlank(blankMap)
        }
        MutatedSource(Sources(modifiedSources), sources, listOf(sourceMutation), 1, mutations.size - 1)
    }
}

fun Source.mutationStream(
    suppressWithComments: Boolean = true,
    random: Random = Random,
    types: Set<Mutation.Type> = ALL,
    removeBlank: Boolean = true,
    retryCount: Int = 32
) = sequence {
    check(type == Source.FileType.JAVA) { "Can only mutate Java sources" }
    val mutations = sources.keys.asSequence().map { name ->
        Mutation.find<Mutation>(getParsed(name)).map { mutation -> SourceMutation(name, mutation) }
    }.flatten()
        .filter { types.contains(it.mutation.type) }
        .filter { !suppressWithComments || !it.suppressed() }
        .toMutableList()

    val seen = mutableSetOf<String>()
    var retries = 0
    val remaining = mutableMapOf<SourceMutation, Int>()
    @Suppress("LoopWithTooManyJumpStatements")
    while (true) {
        val mutation = mutations.shuffled(random).first()
        if (mutation !in remaining) {
            remaining[mutation] = mutation.mutation.estimatedCount
        }
        mutation.mutation.reset()
        val modifiedSources = sources.copy().toMutableMap()
        val blankMap = modifiedSources.blankMap()
        val original = modifiedSources[mutation.name] ?: error("Couldn't find a source that should be there")
        val modified = mutation.mutation.apply(original, random)
        check(original != modified) { "Mutation did not change source" }
        modifiedSources[mutation.name] = modified
        if (removeBlank) {
            modifiedSources.removeNewBlank(blankMap)
        }
        val source = MutatedSource(Sources(modifiedSources), sources, listOf(mutation), 1, mutations.size - 1)
        if (source.md5 !in seen) {
            retries = 0
            seen += source.md5
            yield(source)
            remaining[mutation] = remaining[mutation]!! - 1
            if (remaining[mutation] == 0) {
                mutations.remove(mutation)
                if (mutations.isEmpty()) {
                    return@sequence
                }
            }
        } else {
            if (retries++ >= retryCount) {
                return@sequence
            }
        }
    }
}
