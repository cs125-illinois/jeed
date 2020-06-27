@file:Suppress("MatchingDeclarationName")

package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParserBaseListener
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.misc.Interval
import org.antlr.v4.runtime.tree.ParseTreeWalker
import kotlin.random.Random

sealed class Mutation(val type: Type, val location: Location, val original: String) {
    data class Location(val start: Int, val end: Int)

    enum class Type { STRING_LITERAL, INCREMENT_DECREMENT, BOOLEAN_LITERAL, CONDITIONAL_BOUNDARY }

    var modified: String? = null
    val applied: Boolean
        get() = modified != null

    data class Config(
        val stringLiteral: StringLiteral.Config = StringLiteral.Config(),
        val incrementDecrement: IncrementDecrement.Config = IncrementDecrement.Config()
    )

    fun apply(config: Config = Config()): Boolean {
        check(modified == null) { "Mutation already applied" }
        applyMutation(config).also {
            if (it != original) {
                modified = it
            }
        }
        return applied
    }

    abstract fun applyMutation(config: Config = Config()): String

    override fun toString(): String = "$type: $location ($original)"

    class Listener(private val parsedSource: Source.ParsedSource) : JavaParserBaseListener() {
        val mutations: MutableList<Mutation> = mutableListOf()

        private fun ParserRuleContext.toLocation() =
            Location(start.startIndex, stop.stopIndex)

        private fun Token.toLocation() = Location(startIndex, stopIndex)

        override fun enterLiteral(ctx: JavaParser.LiteralContext?) {
            ctx?.STRING_LITERAL()?.also {
                ctx.toLocation().also { location ->
                    mutations.add(StringLiteral(location, parsedSource.contents(location)))
                }
            }
            ctx?.BOOL_LITERAL()?.also {
                ctx.toLocation().also { location ->
                    mutations.add(BooleanLiteral(location, parsedSource.contents(location)))
                }
            }
        }

        override fun enterExpression(ctx: JavaParser.ExpressionContext?) {
            ctx?.prefix?.toLocation()?.also { location ->
                val contents = parsedSource.contents(location)
                if (IncrementDecrement.matches(contents)) {
                    mutations.add(IncrementDecrement(location, contents, true))
                }

            }
            ctx?.postfix?.toLocation()?.also { location ->
                val contents = parsedSource.contents(location)
                if (IncrementDecrement.matches(contents)) {
                    mutations.add(IncrementDecrement(location, contents, false))
                }
            }
            ctx?.bop?.toLocation()?.also { location ->
                val contents = parsedSource.contents(location)
                if (ConditionalBoundary.matches(contents)) {
                    mutations.add(ConditionalBoundary(location, contents))
                }
            }
        }

        init {
            ParseTreeWalker.DEFAULT.walk(this, parsedSource.tree)
        }
    }

    companion object {
        fun find(parsedSource: Source.ParsedSource): List<Mutation> = Listener(parsedSource).mutations
    }
}

class StringLiteral(location: Location, original: String) : Mutation(Type.STRING_LITERAL, location, original) {
    data class Config(
        val random: Boolean = DEFAULT_RANDOM,
        val preserveLength: Boolean = DEFAULT_PRESERVE_LENGTH,
        val replaceWith: String? = null,
        val minLength: Int = DEFAULT_MIN_LENGTH,
        val maxLength: Int = DEFAULT_MAX_LENGTH
    ) {
        companion object {
            const val DEFAULT_RANDOM = true
            const val DEFAULT_PRESERVE_LENGTH = false
            const val DEFAULT_MIN_LENGTH = 4
            const val DEFAULT_MAX_LENGTH = 32
            const val MAX_STRING_RETRIES = 32
        }
    }

    private fun randomString(length: Int): String {
        if (length == 0) {
            return ""
        }
        for (i in 0..Config.MAX_STRING_RETRIES) {
            (1..length)
                .map { Random.nextInt(0, ALPHANUMERIC_CHARS.size) }
                .map(ALPHANUMERIC_CHARS::get)
                .joinToString("").also {
                    if (it != original) {
                        return it
                    }
                }
        }
        error("Couldn't generate string")
    }

    override fun applyMutation(config: Mutation.Config): String {
        val ourConfig = config.stringLiteral
        return if (ourConfig.random) {
            if (ourConfig.preserveLength) {
                randomString(original.length - 2)
            } else {
                randomString(Random.nextInt(ourConfig.minLength, ourConfig.maxLength))
            }.let {
                "\"$it\""
            }
        } else {
            ourConfig.replaceWith.toString()
        }
    }

    companion object {
        val ALPHANUMERIC_CHARS: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        fun find(parsedSource: Source.ParsedSource): List<StringLiteral> =
            Mutation.find(parsedSource).filterIsInstance<StringLiteral>()
    }
}

class IncrementDecrement(
    location: Location, original: String, val prefix: Boolean
) : Mutation(Type.INCREMENT_DECREMENT, location, original) {
    data class Config(
        val changePrefix: Boolean = CHANGE_PREFIX,
        val changePostfix: Boolean = CHANGE_POSTFIX
    ) {
        companion object {
            const val CHANGE_PREFIX = true
            const val CHANGE_POSTFIX = true
        }
    }

    override fun applyMutation(config: Mutation.Config): String {
        val ourConfig = config.incrementDecrement
        check(original == INC || original == DEC) { "${this.javaClass.name} didn't find expected text" }
        @Suppress("ComplexCondition")
        return if ((prefix && ourConfig.changePrefix) || (!prefix || ourConfig.changePostfix)) {
            if (original == INC) {
                DEC
            } else {
                INC
            }
        } else {
            original
        }
    }

    companion object {
        fun find(parsedSource: Source.ParsedSource): List<IncrementDecrement> =
            Mutation.find(parsedSource).filterIsInstance<IncrementDecrement>()

        fun matches(contents: String) = contents in setOf(INC, DEC)

        private const val INC = "++"
        private const val DEC = "--"
    }
}

class BooleanLiteral(
    location: Location, original: String
) : Mutation(Type.BOOLEAN_LITERAL, location, original) {

    override fun applyMutation(config: Config): String {
        return when (original) {
            TRUE -> FALSE
            FALSE -> TRUE
            else -> error("${this.javaClass.name} didn't find expected text")
        }
    }

    companion object {
        fun find(parsedSource: Source.ParsedSource): List<BooleanLiteral> =
            Mutation.find(parsedSource).filterIsInstance<BooleanLiteral>()

        private const val TRUE = "true"
        private const val FALSE = "false"
    }
}

class ConditionalBoundary(
    location: Location, original: String
) : Mutation(Type.CONDITIONAL_BOUNDARY, location, original) {
    override fun applyMutation(config: Config): String {
        return when (original) {
            LT -> LTE
            LTE -> LT
            GT -> GTE
            GTE -> GT
            else -> error("${javaClass.name} didn't find the expected text")
        }
    }

    companion object {
        fun find(parsedSource: Source.ParsedSource): List<ConditionalBoundary> =
            Mutation.find(parsedSource).filterIsInstance<ConditionalBoundary>()

        fun matches(contents: String) = contents in setOf(LT, LTE, GT, GTE)

        private const val LT = "<"
        private const val LTE = "<="
        private const val GT = ">"
        private const val GTE = ">="
    }
}

fun Source.ParsedSource.contents(location: Mutation.Location): String =
    stream.getText(Interval(location.start, location.end))

