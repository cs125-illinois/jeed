@file:Suppress("MatchingDeclarationName", "TooManyFunctions")

package edu.illinois.cs.cs125.jeed.core

import com.github.difflib.DiffUtils
import com.github.difflib.patch.DeltaType
import org.antlr.v4.runtime.misc.Interval
import kotlin.math.pow
import kotlin.random.Random

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

    @Suppress("LongMethod")
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

                val deltas = DiffUtils.diff(originalLines, modifiedLines).deltas
                val type = if (deltas.size == 1) {
                    deltas.first().type
                } else {
                    check(deltas.size == 2)
                    check(deltas.all { it.type == DeltaType.CHANGE }) {
                        println(deltas.map { it.type })
                        println(deltas.first().source.lines)
                        println(deltas.first().target.lines)
                        println(deltas[1].source.lines)
                        println(original)
                        println(modified)
                    }
                    deltas.first().type
                }
                val (sourceLines, targetLines) = if (deltas.size == 1) {
                    Pair(deltas.first().source.lines, deltas.first().target.lines)
                } else {
                    Pair(
                        originalLines.subList(deltas[0].source.position, deltas[1].source.position + 1),
                        modifiedLines.subList(deltas[0].source.position, deltas[1].source.position + 1)
                    )
                }
                check(type == DeltaType.CHANGE || type == DeltaType.DELETE) {
                    "Found invalid delta type: $type"
                }
                var i = 0
                val output = mutableListOf<String>()
                while (i < originalLines.size) {
                    val line = originalLines[i]
                    val nextLine = if (type == DeltaType.CHANGE && i == deltas.first().source.position) {
                        val indentAmount = line.length - line.trimStart().length
                        val currentIndent = " ".repeat(indentAmount)
                        val originalContent = sourceLines.joinToString("\n") {
                            if (it.length < indentAmount) {
                                "$currentIndent//"
                            } else {
                                currentIndent + "// " + it.substring(indentAmount)
                            }
                        }
                        i += sourceLines.size
                        """
                    |$currentIndent// Modified by ${mutation.mutation.mutationType.mutationName()}. Originally:
                    |$originalContent
                    |${targetLines.joinToString("\n")}
                    """.trimMargin()
                    } else if (type == DeltaType.DELETE && i == deltas.first().source.position) {
                        val indentAmount = line.length - line.trimStart().length
                        val currentIndent = " ".repeat(indentAmount)
                        val originalContent = sourceLines.joinToString("\n") {
                            if (it.length < indentAmount) {
                                "$currentIndent//"
                            } else {
                                currentIndent + "// " + it.substring(indentAmount)
                            }
                        }
                        i += sourceLines.size
                        """
                    |$currentIndent// Removed by ${mutation.mutation.mutationType.mutationName()}. Originally:
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

class Mutater(
    private val originalSource: Source,
    shuffle: Boolean,
    seed: Int,
    types: Set<Mutation.Type>
) {

    init {
        // check(originalSource.type == Source.FileType.JAVA) { "Can only mutate Java sources" }
    }

    private val random = Random(seed)
    private val mutations = originalSource.sources.keys.map { name ->
        Mutation.find<Mutation>(originalSource.getParsed(name), originalSource.type)
            .map { mutation -> SourceMutation(name, mutation) }
            .filter {
                types.contains(it.mutation.mutationType)
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

    fun mutate(limit: Int = 1): MutatedSource {
        check(appliedMutations.isEmpty()) { "Some mutations already applied" }
        @Suppress("UnusedPrivateMember")
        for (unused in 0 until limit) {
            if (availableMutations.isEmpty()) {
                break
            }
            apply()
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
                parts[1].split(" ").contains(mutation.mutationType.suppressionComment())
            )
    }
}

fun Source.allMutations(
    suppressWithComments: Boolean = true,
    random: Random = Random,
    types: Set<Mutation.Type> = ALL
): List<MutatedSource> {
    // check(type == Source.FileType.JAVA) { "Can only mutate Java sources" }
    val mutations = sources.keys.map { name ->
        Mutation.find<Mutation>(getParsed(name), type).map { mutation -> SourceMutation(name, mutation) }
    }.flatten()
        .filter { types.contains(it.mutation.mutationType) }
        .filter { !suppressWithComments || !it.suppressed() }

    return mutations.map { sourceMutation ->
        val modifiedSources = sources.copy().toMutableMap()
        val original =
            modifiedSources[sourceMutation.name] ?: error("Couldn't find a source that should be there")
        val modified = sourceMutation.mutation.apply(original, random)
        check(original != modified) { "Mutation did not change source" }
        modifiedSources[sourceMutation.name] = modified
        MutatedSource(Sources(modifiedSources), sources, listOf(sourceMutation), 1, mutations.size - 1)
    }
}

fun Source.mutationStream(
    suppressWithComments: Boolean = true,
    random: Random = Random,
    types: Set<Mutation.Type> = ALL,
    retryCount: Int = 32
) = sequence {
    // check(type == Source.FileType.JAVA) { "Can only mutate Java sources" }
    val mutations = sources.keys.asSequence().map { name ->
        Mutation.find<Mutation>(getParsed(name), type).map { mutation -> SourceMutation(name, mutation) }
    }.flatten()
        .filter { types.contains(it.mutation.mutationType) }
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
        val original = modifiedSources[mutation.name] ?: error("Couldn't find a source that should be there")
        val modified = mutation.mutation.apply(original, random)
        check(original != modified) { "Mutation did not change source" }
        modifiedSources[mutation.name] = modified
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

@Suppress("NestedBlockDepth", "LongParameterList")
fun Source.allFixedMutations(
    suppressWithComments: Boolean = true,
    random: Random = Random,
    types: Set<Mutation.Type> = ALL,
    nonFixedMax: Int = 4,
    retryCount: Int = 8
): List<MutatedSource> {
    // check(type == Source.FileType.JAVA) { "Can only mutate Java sources" }
    val mutations = sources.keys.asSequence()
        .map {
            Mutation.find<Mutation>(getParsed(it), type).map { mutation -> SourceMutation(name, mutation) }
        }.flatten()
        .filter { types.contains(it.mutation.mutationType) }
        .filter { !suppressWithComments || !it.suppressed() }
        .toMutableList()

    val mutatedSources = mutableListOf<MutatedSource>()

    for (mutation in mutations) {
        val seen = mutableSetOf<String>()
        var retries = 0
        val count = if (mutation.mutation.fixedCount) {
            mutation.mutation.estimatedCount
        } else {
            mutation.mutation.estimatedCount.coerceAtMost(nonFixedMax)
        }
        @Suppress("UnusedPrivateMember")
        for (unused in 0 until count) {
            mutation.mutation.reset()
            val modifiedSources = sources.copy().toMutableMap()
            val original = modifiedSources[mutation.name] ?: error("Couldn't find a source that should be there")
            val modified = mutation.mutation.apply(original, random)
            check(original != modified) { "Mutation did not change source" }
            modifiedSources[mutation.name] = modified
            val source = MutatedSource(Sources(modifiedSources), sources, listOf(mutation), 1, mutations.size - 1)
            if (source.md5 !in seen) {
                retries = 0
                seen += source.md5
                mutatedSources += source
            } else {
                if (retries++ >= retryCount) {
                    break
                }
            }
        }
    }
    return mutatedSources
}

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
            Source.FileType.JAVA -> contents != "null" && (returnType == returnType.capitalize() || returnType.endsWith("[]"))
            Source.FileType.KOTLIN -> contents != "null" && !kotlinPrimitiveTypes.contains(returnType)
        }
    }
}

class RemoveAssert(
    location: Location,
    original: String,
    fileType: Source.FileType
) : Mutation(Type.REMOVE_ASSERT, location, original, fileType) {
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
    override val mightNotCompile = false
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
