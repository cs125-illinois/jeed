@file:Suppress("MatchingDeclarationName")

package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParserBaseListener
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.misc.Interval
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.antlr.v4.runtime.tree.TerminalNode
import org.jetbrains.kotlin.backend.common.pop
import kotlin.random.Random

sealed class Mutation(val type: Type, var location: Location, val original: String) {
    data class Location(val start: Int, val end: Int, val path: List<SourcePath>, val line: String) {
        init {
            check(end >= start) { "Invalid location: $end $start" }
        }

        data class SourcePath(val type: Type, val name: String) {
            enum class Type { CLASS, METHOD }
        }

        fun shift(amount: Int) = copy(start = start + amount, end = end + amount)
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
        CONDITIONAL_BOUNDARY, NEGATE_CONDITIONAL,
        INCREMENT_DECREMENT, INVERT_NEGATION, MATH,
        PRIMITIVE_RETURN, TRUE_RETURN, FALSE_RETURN, NULL_RETURN,
        REMOVE_ASSERT
    }

    var modified: String? = null
    val applied: Boolean
        get() = modified != null

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

    override fun toString(): String = "$type: $location ($original)"

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

        private var insideAnnotation = false
        override fun enterAnnotation(ctx: JavaParser.AnnotationContext?) {
            insideAnnotation = true
        }

        override fun exitAnnotation(ctx: JavaParser.AnnotationContext?) {
            insideAnnotation = false
        }

        private fun ParserRuleContext.toLocation() =
            Location(start.startIndex, stop.stopIndex, currentPath, lines[start.line - 1])

        private fun Token.toLocation() = Location(startIndex, stopIndex, currentPath, lines[line - 1])
        private fun List<TerminalNode>.toLocation() =
            Location(first().symbol.startIndex, last().symbol.stopIndex, currentPath, lines[first().symbol.line - 1])

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
            }
        }

        override fun enterStatement(ctx: JavaParser.StatementContext) {
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

    override fun applyMutation(random: Random): String {
        val position = numberPositions.shuffled(random).first()
        return original.toCharArray().also { characters ->
            val randomValue = Math.floorMod((characters[position].toString().toInt() + 1), base).let {
                // Avoid adding leading zeros
                if (position == 0 && it == 0) {
                    1
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

private val primitiveTypes = setOf("byte", "short", "int", "long", "float", "double", "char", "boolean")

class PrimitiveReturn(
    location: Location,
    original: String
) : Mutation(Type.PRIMITIVE_RETURN, location, original) {
    override val preservesLength = false

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

    override fun applyMutation(random: Random): String = "null"

    companion object {
        fun matches(contents: String, returnType: String) =
            contents != "null" && returnType == returnType.capitalize()
    }
}

class RemoveAssert(
    location: Location,
    original: String
) : Mutation(Type.REMOVE_ASSERT, location, original) {
    override val preservesLength = true

    override fun applyMutation(random: Random): String = ""
}

fun Source.ParsedSource.contents(location: Mutation.Location): String =
    stream.getText(Interval(location.start, location.end))

fun MutableList<Mutation.Location.SourcePath>.klass(): String =
    findLast { it.type == Mutation.Location.SourcePath.Type.CLASS }?.name ?: error("No current class in path")

fun MutableList<Mutation.Location.SourcePath>.method(): String =
    findLast { it.type == Mutation.Location.SourcePath.Type.METHOD }?.name ?: error("No current method in path")

data class SourceMutation(val name: String, val mutation: Mutation)

@Suppress("unused")
class MutatedSource(
    sources: Sources,
    val originalSources: Sources,
    val mutations: List<SourceMutation>,
    val appliedMutations: Int,
    val unappliedMutations: Int
) : Source(sources)

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
        this[path] = contents.lines().filterIndexed { index, _ -> toKeep.contains(index) }.joinToString("\n")
    }

class Mutater(
    private val originalSource: Source,
    shuffle: Boolean,
    seed: Int,
    types: Set<Mutation.Type> = Mutation.Type.values().toSet()
) {

    init {
        check(originalSource.type == Source.FileType.JAVA) { "Can only mutate Java sources" }
    }

    private val random = Random(seed)
    private val mutations = originalSource.sources.keys.map { name ->
        Mutation.find<Mutation>(originalSource.getParsed(name)).map { mutation -> SourceMutation(name, mutation) }
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

fun Source.mutater(shuffle: Boolean = true, seed: Int = Random.nextInt()) = Mutater(this, shuffle, seed)
fun Source.mutate(
    shuffle: Boolean = true,
    seed: Int = Random.nextInt(),
    limit: Int = 1,
    types: Set<Mutation.Type> = Mutation.Type.values().toSet()
) =
    Mutater(this, shuffle, seed, types).mutate(limit)

fun Source.allMutations(
    suppressWithComments: Boolean = true,
    random: Random = Random,
    types: Set<Mutation.Type> = Mutation.Type.values().toSet(),
    removeBlank: Boolean = true
): List<MutatedSource> {
    check(type == Source.FileType.JAVA) { "Can only mutate Java sources" }
    val mutations = sources.keys.map { name ->
        Mutation.find<Mutation>(getParsed(name))
            .map { mutation -> SourceMutation(name, mutation) }
    }.flatten()
        .filter { types.contains(it.mutation.type) }
        .filter {
            !suppressWithComments || it.mutation.location.line.split("""//""").let { parts ->
                parts.size <= 1 || !parts[1].contains("mutate-disable")
            }
        }

    return mutations.map { sourceMutation ->
        val modifiedSources = sources.copy().toMutableMap()
        val blankMap = modifiedSources.blankMap()
        val original = modifiedSources[sourceMutation.name] ?: error("Couldn't find a source that should be there")
        val modified = sourceMutation.mutation.apply(original, random)
        check(original != modified) { "Mutation did not change source" }
        modifiedSources[sourceMutation.name] = modified
        if (removeBlank) {
            modifiedSources.removeNewBlank(blankMap)
        }
        MutatedSource(Sources(modifiedSources), sources, listOf(sourceMutation), 1, mutations.size - 1)
    }
}
