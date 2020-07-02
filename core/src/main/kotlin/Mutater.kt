@file:Suppress("MatchingDeclarationName")

package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParserBaseListener
import org.antlr.v4.runtime.ParserRuleContext
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.misc.Interval
import org.antlr.v4.runtime.tree.ParseTreeWalker
import org.jetbrains.kotlin.backend.common.pop
import kotlin.random.Random

sealed class Mutation(val type: Type, val location: Location, val original: String) {
    data class Location(val start: Int, val end: Int, val path: List<SourcePath>) {
        data class SourcePath(val type: Type, val name: String) {
            enum class Type { CLASS, METHOD }
        }
    }

    enum class Type {
        STRING_LITERAL, INCREMENT_DECREMENT, BOOLEAN_LITERAL, CONDITIONAL_BOUNDARY, NEGATE_CONDITIONAL,
        PRIMITIVE_RETURN, TRUE_RETURN, FALSE_RETURN
    }

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

        private fun ParserRuleContext.toLocation() = Location(start.startIndex, stop.stopIndex, currentPath)
        private fun Token.toLocation() = Location(startIndex, stopIndex, currentPath)

        override fun enterLiteral(ctx: JavaParser.LiteralContext) {
            ctx.STRING_LITERAL().also {
                ctx.toLocation().also { location ->
                    mutations.add(StringLiteral(location, parsedSource.contents(location)))
                }
            }
            ctx.BOOL_LITERAL().also {
                ctx.toLocation().also { location ->
                    mutations.add(BooleanLiteral(location, parsedSource.contents(location)))
                }
            }
        }

        override fun enterExpression(ctx: JavaParser.ExpressionContext) {
            ctx.prefix?.toLocation()?.also { location ->
                val contents = parsedSource.contents(location)
                if (IncrementDecrement.matches(contents)) {
                    mutations.add(IncrementDecrement(location, contents, true))
                }
            }
            ctx.postfix?.toLocation()?.also { location ->
                val contents = parsedSource.contents(location)
                if (IncrementDecrement.matches(contents)) {
                    mutations.add(IncrementDecrement(location, contents, false))
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
            }
        }

        override fun enterStatement(ctx: JavaParser.StatementContext) {
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
    }
}

class IncrementDecrement(
    location: Location,
    original: String,
    val prefix: Boolean
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
        fun matches(contents: String) = contents in setOf(INC, DEC)

        private const val INC = "++"
        private const val DEC = "--"
    }
}

class BooleanLiteral(
    location: Location,
    original: String
) : Mutation(Type.BOOLEAN_LITERAL, location, original) {

    override fun applyMutation(config: Config): String {
        return when (original) {
            TRUE -> FALSE
            FALSE -> TRUE
            else -> error("${this.javaClass.name} didn't find expected text")
        }
    }

    companion object {
        private const val TRUE = "true"
        private const val FALSE = "false"
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
    override fun applyMutation(config: Config): String {
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
            Conditionals.LT, Conditionals.LTE, Conditionals.GT, Conditionals.GTE
        )
    }
}

class NegateConditional(
    location: Location,
    original: String
) : Mutation(Type.NEGATE_CONDITIONAL, location, original) {
    override fun applyMutation(config: Config): String {
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
            Conditionals.EQ, Conditionals.NE, Conditionals.LT, Conditionals.LTE, Conditionals.GT, Conditionals.GTE
        )
    }
}

private val primitiveTypes = setOf("byte", "short", "int", "long", "float", "double", "char", "boolean")

class PrimitiveReturn(
    location: Location,
    original: String
) : Mutation(Type.PRIMITIVE_RETURN, location, original) {
    override fun applyMutation(config: Config): String = "0"

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
    override fun applyMutation(config: Config): String = "true"

    companion object {
        fun matches(contents: String, returnType: String) =
            contents != "true" && returnType in setOf("boolean", "Boolean")
    }
}

class FalseReturn(
    location: Location,
    original: String
) : Mutation(Type.FALSE_RETURN, location, original) {
    override fun applyMutation(config: Config): String = "false"

    companion object {
        fun matches(contents: String, returnType: String) =
            contents != "false" && returnType in setOf("boolean", "Boolean")
    }
}

fun Source.ParsedSource.contents(location: Mutation.Location): String =
    stream.getText(Interval(location.start, location.end))

fun MutableList<Mutation.Location.SourcePath>.klass(): String =
    findLast { it.type == Mutation.Location.SourcePath.Type.CLASS }?.name ?: error("No current class in path")

fun MutableList<Mutation.Location.SourcePath>.method(): String =
    findLast { it.type == Mutation.Location.SourcePath.Type.METHOD }?.name ?: error("No current method in path")
