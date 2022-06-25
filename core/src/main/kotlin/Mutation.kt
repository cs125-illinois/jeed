@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package edu.illinois.cs.cs125.jeed.core

import com.github.jknack.handlebars.internal.text.StringEscapeUtils
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
        BOOLEAN_LITERAL, CHAR_LITERAL, STRING_LITERAL, STRING_LITERAL_LOOKALIKE, STRING_LITERAL_CASE, NUMBER_LITERAL,
        CONDITIONAL_BOUNDARY, NEGATE_CONDITIONAL, SWAP_AND_OR,
        INCREMENT_DECREMENT, INVERT_NEGATION, MATH,
        PRIMITIVE_RETURN, TRUE_RETURN, FALSE_RETURN, NULL_RETURN, PLUS_TO_MINUS,
        REMOVE_RUNTIME_CHECK, REMOVE_METHOD,
        NEGATE_IF, NEGATE_WHILE, REMOVE_IF, REMOVE_LOOP, REMOVE_AND_OR, REMOVE_TRY, REMOVE_STATEMENT,
        REMOVE_PLUS, REMOVE_BINARY, CHANGE_EQUALS,
        SWAP_BREAK_CONTINUE, PLUS_OR_MINUS_ONE_TO_ZERO, ADD_BREAK,
        STRING_LITERAL_TRIM, NUMBER_LITERAL_TRIM,

        // TODO: Finish
        MODIFY_ARRAY_LITERAL, MODIFY_LENGTH_AND_SIZE
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
        inline fun <reified T : Mutation> find(parsedSource: Source.ParsedSource, fileType: Source.FileType): List<T> {
            @Suppress("CascadeIf")
            return if (fileType == Source.FileType.JAVA) {
                JavaMutationListener(parsedSource).mutations.filterIsInstance<T>()
            } else if (fileType == Source.FileType.KOTLIN) {
                KotlinMutationListener(parsedSource).mutations.filterIsInstance<T>()
            } else {
                error("Invalid fileType $fileType")
            }
        }
    }
}

@JsonClass(generateAdapter = true)
data class AppliedMutation(
    val mutationType: Mutation.Type,
    var location: Mutation.Location,
    val original: String,
    val mutated: String,
    val linesChanged: Int,
    val mightNotCompile: Boolean
) {
    constructor(mutation: Mutation) : this(
        mutation.mutationType,
        mutation.location,
        mutation.original,
        mutation.modified!!,
        mutation.linesChanged!!,
        mutation.mightNotCompile
    ) {
        require(mutation.applied) { "Must be created from an applied mutation" }
    }
}

val PITEST = setOf(
    Mutation.Type.BOOLEAN_LITERAL,
    Mutation.Type.CHAR_LITERAL,
    Mutation.Type.STRING_LITERAL,
    Mutation.Type.STRING_LITERAL_LOOKALIKE,
    Mutation.Type.STRING_LITERAL_CASE,
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
    Mutation.Type.PLUS_OR_MINUS_ONE_TO_ZERO,
    Mutation.Type.ADD_BREAK,
    Mutation.Type.STRING_LITERAL_TRIM,
    Mutation.Type.NUMBER_LITERAL_TRIM,
    Mutation.Type.MODIFY_LENGTH_AND_SIZE,
    Mutation.Type.MODIFY_ARRAY_LITERAL
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
    override val preservesLength = false
    override val estimatedCount = ALPHANUMERIC_CHARS.size - 1
    override val mightNotCompile = false
    override val fixedCount = false

    private val character = original.removeSurrounding("'").also {
        check(it.length == 1 || it.startsWith("\\")) { "Character didn't have the correct length: $original" }
    }.first()

    override fun applyMutation(random: Random): String =
        ALPHANUMERIC_CHARS.filter { it != character }.shuffled(random).first().let { "'$it'" }
}

private val ALPHANUMERIC_CHARS_AND_SPACE = (('a'..'z') + ('A'..'Z') + ('0'..'9') + (' ')).toSet()

// private val PUNCTUATION = setOf('.', ',', '!', '?', ';', ':', '[', ']', '(', ')', '<', '>')
private val LOOKALIKES =
    mapOf('0' to 'O', '0' to 'o', '1' to 'l', '.' to ',', '!' to '?', ':' to ';', '[' to '(', ']' to ')').toMutableMap()
        .apply {
            val keysCopy = keys.toList()
            for (key in keysCopy) {
                this[this[key]!!] = key
            }
        }.toMap().toSortedMap()

class StringLiteral(
    location: Location,
    original: String,
    fileType: Source.FileType,
    private val withQuotes: Boolean = true
) : Mutation(Type.STRING_LITERAL, location, original, fileType) {
    private val string = if (withQuotes) {
        original.removeSurrounding("\"")
    } else {
        original
    }.let {
        StringEscapeUtils.unescapeJava(it)
    }
    override val preservesLength = string.isNotEmpty()
    override val estimatedCount = ALPHANUMERIC_CHARS_AND_SPACE.size.toDouble().pow(string.length).toInt() - 1
    override val mightNotCompile = false
    override val fixedCount = false

    @Suppress("NestedBlockDepth")
    override fun applyMutation(random: Random): String {
        return if (string.isEmpty()) {
            " "
        } else {
            string.toCharArray().let { characters ->
                val position = characters.indices.random(random)
                characters[position] =
                    ALPHANUMERIC_CHARS_AND_SPACE.filter { it != characters[position] }.shuffled(random).first()
                characters.joinToString("")
            }.let {
                StringEscapeUtils.escapeJava(it)
            }
        }.let {
            if (withQuotes) {
                "\"$it\""
            } else {
                it
            }
        }
    }
}

class StringLiteralLookalike(
    location: Location,
    original: String,
    fileType: Source.FileType,
    private val withQuotes: Boolean = true
) : Mutation(Type.STRING_LITERAL_LOOKALIKE, location, original, fileType) {
    override val preservesLength = true
    private val string = if (withQuotes) {
        original.removeSurrounding("\"")
    } else {
        original
    }.let {
        StringEscapeUtils.unescapeJava(it)
    }
    override val estimatedCount =
        2.0.pow(string.filter { LOOKALIKES.containsKey(it) }.length).toInt() - 1
    override val mightNotCompile = false
    override val fixedCount = false

    @Suppress("NestedBlockDepth")
    override fun applyMutation(random: Random): String = string.toCharArray().let { characters ->
        val position = characters.indices.filter { LOOKALIKES.containsKey(characters[it]) }.random(random)
        characters[position] = LOOKALIKES[characters[position]]!!
        characters.joinToString("")
    }.let {
        StringEscapeUtils.escapeJava(it)
    }.let {
        if (withQuotes) {
            "\"$it\""
        } else {
            it
        }
    }

    companion object {
        fun matches(contents: String, withQuotes: Boolean = true) = contents.let {
            if (withQuotes) {
                contents.removeSurrounding("\"")
            } else {
                contents
            }
        }.let { string ->
            StringEscapeUtils.unescapeJava(string).any { LOOKALIKES.containsKey(it) }
        }
    }
}

class StringLiteralCase(
    location: Location,
    original: String,
    fileType: Source.FileType,
    private val withQuotes: Boolean = true
) : Mutation(Type.STRING_LITERAL_CASE, location, original, fileType) {
    override val preservesLength = true
    private val string = if (withQuotes) {
        original.removeSurrounding("\"")
    } else {
        original
    }.let {
        StringEscapeUtils.unescapeJava(it)
    }
    override val estimatedCount =
        2.0.pow(
            string
                .split(" ")
                .filter { it.isNotEmpty() && (it.first().isUpperCase() || it.first().isLowerCase()) }
                .size
        ).toInt() - 1
    override val mightNotCompile = false
    override val fixedCount = false

    @Suppress("NestedBlockDepth")
    override fun applyMutation(random: Random): String = string.toCharArray().let { characters ->
        val position = characters.indices
            .filter {
                (it == 0 || characters[it - 1] == ' ') &&
                    (characters[it].isLowerCase() || characters[it].isUpperCase())
            }
            .random(random)
        characters[position] = if (characters[position].isUpperCase()) {
            characters[position].lowercaseChar()
        } else if (characters[position].isLowerCase()) {
            characters[position].uppercaseChar()
        } else {
            error("Bad position")
        }
        characters.joinToString("")
    }.let {
        StringEscapeUtils.escapeJava(it)
    }.let {
        if (withQuotes) {
            "\"$it\""
        } else {
            it
        }
    }

    companion object {
        fun matches(contents: String, withQuotes: Boolean = true) = contents.let {
            if (withQuotes) {
                contents.removeSurrounding("\"")
            } else {
                contents
            }
        }.let { string ->
            StringEscapeUtils.unescapeJava(string).split(" ").any {
                it.isNotEmpty() && (it.first().isUpperCase() || it.first().isLowerCase())
            }
        }
    }
}

class StringLiteralTrim(
    location: Location,
    original: String,
    fileType: Source.FileType,
    private val withQuotes: Boolean = true
) : Mutation(Type.STRING_LITERAL_TRIM, location, original, fileType) {
    override val preservesLength = false
    private val string = if (withQuotes) {
        original.removeSurrounding("\"")
    } else {
        original
    }.let {
        StringEscapeUtils.unescapeJava(it)
    }
    override val estimatedCount = 2
    override val mightNotCompile = false
    override val fixedCount = true

    @Suppress("NestedBlockDepth")
    override fun applyMutation(random: Random): String {
        return if (random.nextBoolean()) {
            string.substring(1)
        } else {
            string.substring(0, string.length - 1)
        }.let {
            StringEscapeUtils.escapeJava(it)
        }.let {
            if (withQuotes) {
                "\"$it\""
            } else {
                it
            }
        }
    }

    companion object {
        fun matches(contents: String, withQuotes: Boolean = true) = contents.let {
            if (withQuotes) {
                contents.removeSurrounding("\"")
            } else {
                contents
            }
        }.let {
            StringEscapeUtils.unescapeJava(it).length >= 2
        }
    }
}

internal fun MutableList<Mutation>.addStringMutations(
    location: Mutation.Location,
    contents: String,
    fileType: Source.FileType,
    withQuotes: Boolean = true
) {
    add(StringLiteral(location, contents, fileType, withQuotes))
    if (StringLiteralLookalike.matches(contents, withQuotes)) {
        add(StringLiteralLookalike(location, contents, fileType, withQuotes))
    }
    if (StringLiteralCase.matches(contents, withQuotes)) {
        add(StringLiteralCase(location, contents, fileType, withQuotes))
    }
    if (StringLiteralTrim.matches(contents, withQuotes)) {
        add(StringLiteralTrim(location, contents, fileType, withQuotes))
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

    private val numberPositions = original.toCharArray()
        .mapIndexed { index, c -> Pair(index, c) }
        .filter { (index, c) -> c.isDigit() && (base != 8 || index > 0) }
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

class NumberLiteralTrim(
    location: Location,
    original: String,
    fileType: Source.FileType,
    base: Int = if (original.startsWith("0")) {
        8
    } else {
        10
    }
) : Mutation(Type.NUMBER_LITERAL_TRIM, location, original, fileType) {
    override val preservesLength = false
    override val mightNotCompile = false
    override val fixedCount = true

    private val options = original.trims(base)
    override val estimatedCount = options.size

    override fun applyMutation(random: Random) = options.shuffled(random).first()

    companion object {
        private fun String.trims(base: Int = 10): List<String> {
            val prefix = when (base) {
                10 -> ""
                2 -> "0b"
                16 -> "0x"
                8 -> "0"
                else -> error("Invalid base $base")
            }
            check(startsWith(prefix))
            val suffix = removePrefix(prefix).split(".").last().filter {
                Character.digit(it, base) == -1
            }
            check(endsWith(suffix)) { "Whoops: $this, $suffix" }
            val digitContent = removePrefix(prefix).removeSuffix(suffix)
            return digitContent.split(".", limit = 2).filter { it.length > 1 }.mapIndexed { index, s ->
                when (index) {
                    0 -> s.substring(1, s.length)
                    1 -> s.substring(0, s.length - 1)
                    else -> error("Invalid index")
                }
            }.map { "$prefix$it$suffix" }
        }

        fun matches(contents: String, base: Int = 10) = contents.trims(base).isNotEmpty()
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
    override fun applyMutation(random: Random) = when (fileType) {
        Source.FileType.JAVA -> "(!$original)"
        Source.FileType.KOTLIN -> "!($original)"
    }
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
    override fun applyMutation(random: Random) = when (fileType) {
        Source.FileType.JAVA -> "(!$original)"
        Source.FileType.KOTLIN -> "!($original)"
    }
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

class AddBreak(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.ADD_BREAK, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 1
    override val mightNotCompile = false
    override val fixedCount = false

    override fun applyMutation(random: Random): String = when (fileType) {
        Source.FileType.JAVA -> "break; }"
        Source.FileType.KOTLIN -> "break }"
    }
}

// TODO: Finish
class ModifyArrayLiteral(
    location: Location,
    original: String,
    fileType: Source.FileType,
    private val parts: List<String>
) : Mutation(Type.MODIFY_ARRAY_LITERAL, location, original, fileType) {
    init {
        check(parts.size > 1)
    }
    override val preservesLength = false
    override val estimatedCount = parts.size - 1
    override val mightNotCompile = false
    override val fixedCount = false

    override fun applyMutation(random: Random): String {
        val toRemove = parts.indices.shuffled(random).first()
        val separator = when (fileType) {
            Source.FileType.JAVA -> ","
            Source.FileType.KOTLIN -> ", "
        }
        return parts.filterIndexed { i, _ ->
            i != toRemove
        }.joinToString(separator).trim()
    }
}

class ChangeLengthAndSize(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.MODIFY_LENGTH_AND_SIZE, location, original, fileType) {
    override val preservesLength = false
    override val estimatedCount = 2
    override val mightNotCompile = true
    override val fixedCount = true

    init {
        when (fileType) {
            Source.FileType.JAVA -> check(original in javaLengthAndSize) { "Invalid length or size: $original" }
            Source.FileType.KOTLIN -> check(original in kotlinLengthAndSize) { "Invalid length or size: $original" }
        }
    }

    override fun applyMutation(random: Random): String = random.nextBoolean().let {
        if (it) {
            "$original + 1"
        } else {
            "$original - 1"
        }
    }

    companion object {
        val javaLengthAndSize = listOf("length", "length()", "size()")
        val kotlinLengthAndSize = listOf("length", "size")
    }
}
