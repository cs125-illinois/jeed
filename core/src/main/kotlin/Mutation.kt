@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
import java.util.Objects
import kotlin.math.abs
import kotlin.math.pow
import kotlin.random.Random

sealed class Mutation(
    val mutationType: Type,
    var location: Location,
    val original: String,
    val fileType: Source.FileType
) {
    @JsonClass(generateAdapter = true)
    data class Location(
        val start: Int,
        val end: Int,
        val line: String,
        val startLine: Int,
        val endLine: Int
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
        REMOVE_PLUS, REMOVE_BINARY, CHANGE_EQUALS,
        SWAP_BREAK_CONTINUE, PLUS_OR_MINUS_ONE_TO_ZERO
    }

    var modified: String? = null
    val applied: Boolean
        get() = modified != null

    fun reset() {
        modified = null
    }

    var linesChanged: Int? = null

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

        val originalLines = original.lines()
        val modifiedLines = modified!!.lines()
        linesChanged = when {
            modified!!.isBlank() -> original.lines().size
            originalLines.size == modifiedLines.size ->
                originalLines.zip(modifiedLines).filter { (m, o) -> m != o }.size
            else -> abs(originalLines.size - modifiedLines.size)
        }
        require(linesChanged!! > 0) { "Line change count failed" }

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

    companion object {
        inline fun <reified T : Mutation> find(parsedSource: Source.ParsedSource, fileType: Source.FileType): List<T> =
            when (fileType) {
                Source.FileType.JAVA -> JavaMutationListener(parsedSource).mutations.filterIsInstance<T>()
                Source.FileType.KOTLIN -> KotlinMutationListener(parsedSource).mutations.filterIsInstance<T>()
            }
    }
}

@JsonClass(generateAdapter = true)
data class AppliedMutation(
    val mutationType: Mutation.Type,
    var location: Mutation.Location,
    val original: String,
    val mutated: String,
    val linesChanged: Int
) {
    constructor(mutation: Mutation) : this(
        mutation.mutationType,
        mutation.location,
        mutation.original,
        mutation.modified!!,
        mutation.linesChanged!!
    ) {
        require(mutation.applied) { "Must be created from an applied mutation" }
    }
}

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
    Mutation.Type.SWAP_BREAK_CONTINUE,
    Mutation.Type.PLUS_OR_MINUS_ONE_TO_ZERO
)
val ALL = PITEST + OTHER

fun Mutation.Type.suppressionComment() = "mutate-disable-" + mutationName()
fun Mutation.Type.mutationName() = name.lowercase().replace("_", "-")

class BooleanLiteral(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.BOOLEAN_LITERAL, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

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
    original: String,
    fileType: Source.FileType
) : Mutation(Type.CHAR_LITERAL, location, original, fileType) {
    override val preservesLength = true
    override val estimatedCount = ALPHANUMERIC_CHARS.size - 1
    override val mightNotCompile = false
    override val fixedCount = false

    private val character = original.removeSurrounding("'").also {
        check(it.length == 1) { "Character didn't have the correct length: $original" }
    }.first()

    override fun applyMutation(random: Random): String =
        ALPHANUMERIC_CHARS.filter { it != character }.shuffled(random).first().let { "'$it'" }
}

val NUMERIC_CHARS = ('0'..'9').toSet()

val ALPHANUMERIC_CHARS_AND_SPACE = (('a'..'z') + ('A'..'Z') + ('0'..'9') + (' ')).toSet()

class StringLiteral(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.STRING_LITERAL, location, original, fileType) {
    override val preservesLength = true
    private val string = original.removeSurrounding("\"")
    override val estimatedCount = ALPHANUMERIC_CHARS_AND_SPACE.size.toDouble().pow(string.length).toInt() - 1
    override val mightNotCompile = false
    override val fixedCount = false

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
    fileType: Source.FileType,
    private val base: Int = 10
) : Mutation(Type.NUMBER_LITERAL, location, original, fileType) {
    override val preservesLength = true
    override val mightNotCompile = false
    override val fixedCount = false

    private val numberPositions = original
        .toCharArray()
        .mapIndexed { index, c -> Pair(index, c) }
        .filter { it.second in NUMERIC_CHARS }
        .map { it.first }.also {
            check(it.isNotEmpty()) { "No numeric characters in numeric literal" }
        }
    override val estimatedCount = numberPositions.size * 2

    override fun applyMutation(random: Random): String {
        // Special case since 0 -> 9 is a bit too obvious
        if (original == "0") {
            return "1"
        }
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
                if (position == 0 && original.length > 1 && it == 0) {
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
    original: String,
    fileType: Source.FileType
) : Mutation(Type.INCREMENT_DECREMENT, location, original, fileType) {
    override val preservesLength = true
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

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
    original: String,
    fileType: Source.FileType
) : Mutation(Type.INVERT_NEGATION, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

    override fun applyMutation(random: Random): String = ""

    companion object {
        fun matches(contents: String) = contents == "-"
    }
}

class MutateMath(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.MATH, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

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

class PlusToMinus(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.PLUS_TO_MINUS, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = true
    override val fixedCount = true

    override fun applyMutation(random: Random) = "-"

    companion object {
        fun matches(contents: String) = contents == "+"
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
    original: String,
    fileType: Source.FileType
) : Mutation(Type.CONDITIONAL_BOUNDARY, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

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
    original: String,
    fileType: Source.FileType
) : Mutation(Type.NEGATE_CONDITIONAL, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

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
    original: String,
    fileType: Source.FileType
) : Mutation(Type.SWAP_AND_OR, location, original, fileType) {
    override val preservesLength = true
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

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

class SwapBreakContinue(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.SWAP_BREAK_CONTINUE, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

    override fun applyMutation(random: Random): String {
        return when (original) {
            "break" -> "continue"
            "continue" -> "break"
            else -> error("${javaClass.name} didn't find the expected text")
        }
    }

    companion object {
        fun matches(contents: String) = contents in setOf("break", "continue")
    }
}

class PlusOrMinusOneToZero(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.PLUS_OR_MINUS_ONE_TO_ZERO, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

    override fun applyMutation(random: Random): String {
        return when (original) {
            "1" -> "0"
            else -> error("${javaClass.name} didn't find the expected text: $original")
        }
    }

    companion object {
        fun matches(contents: String) = contents == "1"
    }
}

private val javaPrimitiveTypes = setOf("byte", "short", "int", "long", "float", "double", "char", "boolean")
private val kotlinPrimitiveTypes = setOf("Byte", "Short", "Int", "Long", "Float", "Double", "Char", "Boolean")

class PrimitiveReturn(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.PRIMITIVE_RETURN, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

    override fun applyMutation(random: Random): String = "0"

    companion object {
        private val zeros = setOf("0", "0L", "0.0", "0.0f")
        fun matches(contents: String, returnType: String, fileType: Source.FileType) = when (fileType) {
            Source.FileType.JAVA -> contents !in zeros && returnType in (javaPrimitiveTypes - "boolean")
            Source.FileType.KOTLIN -> contents !in zeros && returnType in (kotlinPrimitiveTypes - "Boolean")
        }
    }
}

class TrueReturn(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.TRUE_RETURN, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

    override fun applyMutation(random: Random): String = "true"

    companion object {
        fun matches(contents: String, returnType: String) =
            contents != "true" && returnType in setOf("boolean", "Boolean")
    }
}

class FalseReturn(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.FALSE_RETURN, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

    override fun applyMutation(random: Random): String = "false"

    companion object {
        fun matches(contents: String, returnType: String) =
            contents != "false" && returnType in setOf("boolean", "Boolean")
    }
}

class NullReturn(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.NULL_RETURN, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

    override fun applyMutation(random: Random): String = "null"

    companion object {
        @Suppress("DEPRECATION")
        fun matches(contents: String, returnType: String, fileType: Source.FileType) = when (fileType) {
            Source.FileType.JAVA -> contents != "null" && (
                returnType == returnType.capitalize() ||
                    returnType.endsWith("[]")
                )
            Source.FileType.KOTLIN -> contents != "null" && !kotlinPrimitiveTypes.contains(returnType)
        }
    }
}

class RemoveRuntimeCheck(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.REMOVE_RUNTIME_CHECK, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

    override fun applyMutation(random: Random): String = ""
}

class RemoveMethod(
    location: Location,
    original: String,
    private val returnType: String,
    fileType: Source.FileType
) : Mutation(Type.REMOVE_METHOD, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

    private val prefix = getPrefix(original)
    private val postfix = getPostfix(original)

    override fun applyMutation(random: Random) = forReturnType(returnType, fileType).let {
        when (fileType) {
            Source.FileType.JAVA -> "$prefix$it;$postfix"
            Source.FileType.KOTLIN -> "$prefix$it$postfix"
        }
    }

    companion object {
        @Suppress("DEPRECATION", "ComplexMethod")
        private fun forReturnType(returnType: String, fileType: Source.FileType) = when (fileType) {
            Source.FileType.JAVA -> when (returnType) {
                "String" -> "return \"\""
                "void" -> "return"
                "byte" -> "return 0"
                "short" -> "return 0"
                "int" -> "return 0"
                "long" -> "return 0L"
                "char" -> "return '0'"
                "boolean" -> "return false"
                "float" -> "return 0.0f"
                "double" -> "return 0.0"
                else -> "return null"
            }
            Source.FileType.KOTLIN -> when (returnType.removeSuffix("?")) {
                "String" -> "return \"\""
                "" -> "return"
                "Byte" -> "return 0"
                "Short" -> "return 0"
                "Int" -> "return 0"
                "Long" -> "return 0L"
                "Char" -> "return '0'"
                "Boolean" -> "return false"
                "Float" -> "return 0.0f"
                "Double" -> "return 0.0"
                else -> "return null"
            }
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

        fun matches(contents: String, returnType: String, fileType: Source.FileType) =
            contents.removePrefix(getPrefix(contents)).let {
                it.removeSuffix(getPostfix(it))
            }.let {
                it.isNotBlank() && it.trim().removeSuffix(";").trim() != forReturnType(returnType, fileType)
            }
    }
}

class NegateIf(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.NEGATE_IF, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true
    override fun applyMutation(random: Random) = "(!$original)"
}

class NegateWhile(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.NEGATE_WHILE, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true
    override fun applyMutation(random: Random) = "(!$original)"
}

class RemoveIf(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.REMOVE_IF, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = true
    override val fixedCount = true

    override fun applyMutation(random: Random): String = ""
}

class RemoveLoop(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.REMOVE_LOOP, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

    override fun applyMutation(random: Random): String = ""
}

class RemoveAndOr(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.REMOVE_AND_OR, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

    override fun applyMutation(random: Random): String = ""
}

class RemoveTry(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.REMOVE_TRY, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

    override fun applyMutation(random: Random): String = ""
}

class RemoveStatement(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.REMOVE_STATEMENT, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = when (fileType) {
        Source.FileType.JAVA -> false
        Source.FileType.KOTLIN -> true
    }
    override val fixedCount = true
    override fun applyMutation(random: Random): String = ""
}

class RemovePlus(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.REMOVE_PLUS, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = true
    override val fixedCount = true

    override fun applyMutation(random: Random): String = ""

    companion object {
        fun matches(contents: String) = contents == "+"
    }
}

class RemoveBinary(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.REMOVE_BINARY, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = true

    override fun applyMutation(random: Random): String = ""

    companion object {
        private val BINARY = setOf("-", "*", "/", "%", "&", "|", "^", "<<", ">>", ">>>")
        fun matches(contents: String) = BINARY.contains(contents)
    }
}

class ChangeEquals(
    location: Location,
    original: String,
    fileType: Source.FileType,
    private val originalEqualsType: String = "",
    private val firstValue: String = "",
    private val secondValue: String = ""
) : Mutation(Type.CHANGE_EQUALS, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = true
    override val fixedCount = true

    override fun applyMutation(random: Random): String {
        when (fileType) {
            Source.FileType.KOTLIN -> {
                return when (originalEqualsType) {
                    "==" -> "==="
                    "===" -> "=="
                    else -> error("Unknown kotlin equals type: $originalEqualsType")
                }
            }
            Source.FileType.JAVA -> {
                return when (originalEqualsType) {
                    "==" -> "($firstValue.equals($secondValue))"
                    ".equals" -> "($firstValue == $secondValue)"
                    else -> error("Unknown java equals type: $originalEqualsType")
                }
            }
        }
    }
}
