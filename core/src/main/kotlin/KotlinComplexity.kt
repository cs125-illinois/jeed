@file:Suppress("unused")

package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.KotlinLexer
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParser
import edu.illinois.cs.cs125.jeed.core.antlr.KotlinParserBaseListener
import org.antlr.v4.runtime.tree.ParseTreeWalker

private val basicComplexityTokens = listOf(KotlinLexer.FOR, KotlinLexer.WHILE, KotlinLexer.DO, KotlinLexer.THROW)
private val complexityExpressionBOPs = listOf(KotlinLexer.CONJ, KotlinLexer.DISJ)

class KotlinComplexityListener(val source: Source, entry: Map.Entry<String, String>) :
    KotlinParserBaseListener() {
    private val name = entry.key
    var results: MutableMap<String, ComplexityValue> = mutableMapOf()
    // Track top-level methods and top-level methods in top-level class declarations
    // No need to track anything deeper than that

    private var complexityStack: MutableList<ComplexityValue> = mutableListOf()

    override fun enterClassDeclaration(ctx: KotlinParser.ClassDeclarationContext?) {
        super.enterClassDeclaration(ctx)
    }

    override fun enterFunctionDeclaration(ctx: KotlinParser.FunctionDeclarationContext) {
        val name = ctx.identifier().text
        val parameters = ctx.functionValueParameters().functionValueParameter()?.joinToString(", ") {
            it.parameter().type().text
        }
        val returnType = ctx.type().let {
            if (it.isEmpty()) {
                null
            } else {
                check(it.last().start.startIndex > ctx.identifier().start.startIndex) {
                    "Couldn't find method return type"
                }
                it.last().text
            }
        }
        println("fun $name($parameters)${returnType?.let { ": $returnType" } ?: ""}")
    }

    init {
        ParseTreeWalker.DEFAULT.walk(this, source.getParsed(name).tree)
    }
}
