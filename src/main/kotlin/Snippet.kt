package edu.illinois.cs.cs125.janini

import edu.illinois.cs.cs125.janini.antlr.SnippetLexer
import edu.illinois.cs.cs125.janini.antlr.SnippetParser
import edu.illinois.cs.cs125.janini.antlr.SnippetParserBaseListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.tree.ParseTreeListener
import org.antlr.v4.runtime.tree.ParseTreeWalker

class Snippet(val source: String) {
    init {
        require(source.isNotEmpty())

        val charStream = CharStreams.fromString(source)
        val snippetLexer = SnippetLexer(charStream)
        val tokenStream = CommonTokenStream(snippetLexer)
        val snippetParser = SnippetParser(tokenStream)
        val parseTree = snippetParser.block()
        val treeWalker = ParseTreeWalker()
        treeWalker.walk(object : SnippetParserBaseListener() {
            override fun enterClassDeclaration(ctx: SnippetParser.ClassDeclarationContext) {
                println("class: ${ctx.start.line} -> ${ctx.stop.line}")
            }

            override fun enterMethodDeclaration(ctx: SnippetParser.MethodDeclarationContext) {
                println("method: ${ctx.start.line} -> ${ctx.stop.line}")
            }
        }, parseTree)
    }
}
