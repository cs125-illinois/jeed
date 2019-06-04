package edu.illinois.cs.cs125.jeed

import edu.illinois.cs.cs125.jeed.antlr.*
import mu.KotlinLogging
import org.antlr.v4.runtime.*
import org.antlr.v4.runtime.misc.ParseCancellationException

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

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

val errorListener = object : BaseErrorListener() {
    override fun syntaxError(recognizer: Recognizer<*, *>?, offendingSymbol: Any?, line: Int, charPositionInLine: Int, msg: String?, e: RecognitionException?) {
        throw ParseCancellationException("line $line:$charPositionInLine $msg")
    }
}

class Snippet(
        sources: Map<String, String>,
        @Suppress("UNUSED") val originalSource: String,
        val remappedLineMapping: Map<Int, RemappedLine>
) : Source(sources) {
    fun originalSourceFromMap(): String {
        assert(sources.keys.size == 1)
        val remappedSource = sources[sources.keys.first()]!!.lines()
        return remappedLineMapping.values.sortedBy { it.sourceLineNumber }.map {
            remappedSource[it.rewrittenLineNumber].substring(it.addedIntentation)
        }.joinToString(separator = "\n")
    }
}

data class RemappedLine(
        val sourceLineNumber: Int,
        val rewrittenLineNumber: Int,
        val addedIntentation: Int = 0
)

fun Source.Companion.fromSnippet(originalSource: String, indent: Int = 4): Source {
    require(originalSource.isNotEmpty())

    val charStream = CharStreams.fromString("{\n$originalSource\n}")
    val snippetLexer = SnippetLexer(charStream)
    snippetLexer.removeErrorListeners()
    snippetLexer.addErrorListener(errorListener)

    val tokenStream = CommonTokenStream(snippetLexer)

    val snippetParser = SnippetParser(tokenStream)
    snippetParser.removeErrorListeners()
    snippetParser.addErrorListener(errorListener)

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

    val snippetClassName = generateName("Main", classNames)
    val snippetMainMethodName = generateName("main", methodNames)

    var currentOutputLineNumber = 0
    val remappedLineMapping = hashMapOf<Int, RemappedLine>()

    val classDeclarations = mutableListOf<String>()
    originalSource.lines().forEachIndexed { lineNumber, line ->
        if (contentMapping[lineNumber] == "class") {
            classDeclarations.add(line)
            assert(!remappedLineMapping.containsKey(currentOutputLineNumber))
            remappedLineMapping[currentOutputLineNumber] = RemappedLine(lineNumber, currentOutputLineNumber)
            currentOutputLineNumber++
        }
    }

    // Adding public class $snippetClassName
    currentOutputLineNumber++

    val methodDeclarations = mutableListOf<String>()
    originalSource.lines().forEachIndexed { lineNumber, line ->
        if (contentMapping[lineNumber]?.startsWith(("method")) == true) {
            val indentToUse = if ((contentMapping[lineNumber] == "method.start") && !line.contains("""\bstatic\b""".toRegex())) {
                methodDeclarations.add(" ".repeat(indent) + "static")
                currentOutputLineNumber++
                // Adding indentation preserves checkstyle processing
                indent * 2
            } else {
                indent
            }
            methodDeclarations.add(" ".repeat(indentToUse) + line)
            assert(!remappedLineMapping.containsKey(currentOutputLineNumber))
            remappedLineMapping[currentOutputLineNumber] = RemappedLine(lineNumber, currentOutputLineNumber, indentToUse)
            currentOutputLineNumber++
        }
    }

    // Adding public static void $snippetMainMethodName()
    currentOutputLineNumber++

    val looseCode = mutableListOf<String>()
    originalSource.lines().forEachIndexed { lineNumber, line ->
        if (!contentMapping.containsKey(lineNumber)) {
            looseCode.add(" ".repeat(indent * 2) + line)
            assert(!remappedLineMapping.containsKey(currentOutputLineNumber))
            remappedLineMapping[currentOutputLineNumber] = RemappedLine(lineNumber, currentOutputLineNumber, indent * 2)
            currentOutputLineNumber++
        }
    }

    assert(originalSource.lines().size == remappedLineMapping.keys.size)

    var rewrittenSource = ""
    if (classDeclarations.size > 0) {
        rewrittenSource += classDeclarations.joinToString(separator = "\n", postfix = "\n")
    }
    rewrittenSource += "public class $snippetClassName {\n"
    if (methodDeclarations.size > 0) {
        rewrittenSource += methodDeclarations.joinToString(separator = "\n", postfix = "\n")
    }
    rewrittenSource += """${" ".repeat(indent)}public static void $snippetMainMethodName() {""" + "\n"
    if (looseCode.size > 0) {
        rewrittenSource += looseCode.joinToString(separator = "\n", postfix = "\n")
    }
    rewrittenSource += """${" ".repeat(indent)}}""" + "\n}"

    // Add final two braces
    currentOutputLineNumber += 2
    assert(currentOutputLineNumber == rewrittenSource.lines().size)

    logger.debug("\n" + rewrittenSource)
    val snippet = Snippet(hashMapOf(snippetClassName to rewrittenSource), originalSource, remappedLineMapping)

    logger.debug("\n" + snippet.originalSourceFromMap())

    return snippet
}
