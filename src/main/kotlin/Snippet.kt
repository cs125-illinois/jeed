package edu.illinois.cs.cs125.janini

import edu.illinois.cs.cs125.janini.antlr.*
import org.antlr.v4.runtime.BailErrorStrategy
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream

fun generateName(prefix: String, existingNames: Set<String>) : String {
    if (!existingNames.contains(prefix)) {
        return prefix
    } else {
        for (suffix in 1..64) {
            val testClassName = "$prefix$suffix"
            if (!existingNames.contains(testClassName)) {
                return testClassName
            }
        }
    }
    throw IllegalStateException("couldn't generate $prefix class name")
}

class Snippet(snippet: String, indent:Int = 4) {
    init {
        require(snippet.isNotEmpty())

        val charStream = CharStreams.fromString("{\n$snippet\n}")
        val snippetLexer = SnippetLexer(charStream)
        val tokenStream = CommonTokenStream(snippetLexer)
        val snippetParser = SnippetParser(tokenStream)
        snippetParser.removeErrorListeners()
        snippetParser.errorHandler = BailErrorStrategy()
        val parseTree = snippetParser.block()

        val contentMapping = mutableMapOf<Int, String>()
        val classNames = mutableSetOf<String>()
        val methodNames = mutableSetOf<String>()

        object : SnippetParserBaseVisitor<Unit>() {
            fun markAs(start: Int, stop: Int, type: String) {
                for (lineNumber in start - 2..stop - 2) {
                    check(!contentMapping.containsKey(lineNumber)) { "line $lineNumber already marked" }
                    contentMapping[lineNumber] = type
                }
            }
            override fun visitClassDeclaration(context: SnippetParser.ClassDeclarationContext) {
                markAs(context.start.line, context.stop.line, "class")
                val className = context.IDENTIFIER().text
                check(!classNames.contains(className))
                classNames.add(className)
            }
            override fun visitMethodDeclaration(context: SnippetParser.MethodDeclarationContext) {
                markAs(context.start.line, context.stop.line, "method")
                contentMapping[context.start.line - 2] = "method:start"
                val methodName = context.IDENTIFIER().text
                check(!methodNames.contains(methodName))
                methodNames.add(methodName)
            }
        }.visit(parseTree)

        val snippetClassName = generateName("Snippet", classNames)
        val snippetMainMethodName = generateName("main", methodNames)

        val classDeclarations = mutableListOf<String>()
        snippet.lines().forEachIndexed { lineNumber, line ->
            if (contentMapping[lineNumber] == "class") {
                classDeclarations.add(line)
            }
        }

        val methodDeclarations = mutableListOf<String>()
        snippet.lines().forEachIndexed { lineNumber, line ->
            if (contentMapping[lineNumber] == "method:start") {
                // Placing the visibility modifier on the preceding line preserves the line number mapping for
                // error handling. Adding extra indentation to the following line allows checkstyles indentation checks
                // to proceed normally.
                methodDeclarations.add(" ".repeat(indent) + "private static")
                methodDeclarations.add(" ".repeat(indent * 2) + line)
            } else if (contentMapping[lineNumber] == "method") {
                methodDeclarations.add(" ".repeat(indent) + line)
            }
        }

        val looseCode = mutableListOf<String>()
        snippet.lines().forEachIndexed { lineNumber, line ->
            if (!contentMapping.containsKey(lineNumber)) {
               looseCode.add(" ".repeat(indent * 2) + line)
            }
        }

        val source = """
${classDeclarations.joinToString(separator = "\n")}
public class $snippetClassName {
${methodDeclarations.joinToString(separator = "\n")}
${" ".repeat(indent)}public static void $snippetMainMethodName() {
${looseCode.joinToString(separator = "\n")}
${" ".repeat(indent)}}
}"""
        println(source)
    }
}
