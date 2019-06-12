package edu.illinois.cs.cs125.jeed

import edu.illinois.cs.cs125.jeed.antlr.*
import mu.KotlinLogging
import org.antlr.v4.runtime.*

@Suppress("UNUSED")
private val logger = KotlinLogging.logger {}

val SNIPPET_SOURCE = ""

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

class SnippetParseError(line: Int, column: Int, message: String?) : SourceError(SourceLocation(SNIPPET_SOURCE, line, column), message)
class SnippetParsingFailed(errors: List<SnippetParseError>) : JeepError(errors)

class SnippetErrorListener : BaseErrorListener() {
    private val errors = mutableListOf<SnippetParseError>()
    override fun syntaxError(recognizer: Recognizer<*, *>?, offendingSymbol: Any?, line: Int, charPositionInLine: Int, msg: String, e: RecognitionException?) {
        // Decrement line number by 1 to account for added braces
        errors.add(SnippetParseError( line - 1, charPositionInLine, msg))
    }
    fun check() {
        if (errors.size > 0) {
            throw SnippetParsingFailed(errors)
        }
    }
}

class Snippet(
        sources: Map<String, String>,
        @Suppress("UNUSED") val originalSource: String,
        val rewrittenSource: String,
        private val wrappedClassName: String,
        private val remappedLineMapping: Map<Int, RemappedLine>
) : Source(sources) {
    fun originalSourceFromMap(): String {
        val lines = rewrittenSource.lines()
        return remappedLineMapping.values.sortedBy { it.sourceLineNumber }.joinToString(separator = "\n") {
            val currentLine = lines[it.rewrittenLineNumber - 1]
            assert(it.addedIntentation <= currentLine.length) { "${it.addedIntentation} v. ${currentLine.length}" }
            currentLine.substring(it.addedIntentation)
        }
    }

    override fun mapLocation(input: SourceLocation): SourceLocation {
        assert(input.source == wrappedClassName)
        return mapLocation(input, remappedLineMapping)
    }
    companion object {
        fun mapLocation(input: SourceLocation, remappedLineMapping: Map<Int, RemappedLine>): SourceLocation {
            val remappedLineInfo = remappedLineMapping[input.line]
            check(remappedLineInfo != null)
            return SourceLocation(SNIPPET_SOURCE, remappedLineInfo.sourceLineNumber, input.column - remappedLineInfo.addedIntentation
            )
        }
    }
}

data class RemappedLine(
        val sourceLineNumber: Int,
        val rewrittenLineNumber: Int,
        val addedIntentation: Int = 0
)

@Throws(SnippetParsingFailed::class, SnippetValidationFailed::class)
fun Source.Companion.fromSnippet(originalSource: String, indent: Int = 4): Snippet {
    require(originalSource.isNotEmpty())

    val errorListener = SnippetErrorListener()
    val charStream = CharStreams.fromString("{\n$originalSource\n}")
    val snippetLexer = SnippetLexer(charStream)
    snippetLexer.removeErrorListeners()
    snippetLexer.addErrorListener(errorListener)

    val tokenStream = CommonTokenStream(snippetLexer)
    errorListener.check()

    val snippetParser = SnippetParser(tokenStream)
    snippetParser.removeErrorListeners()
    snippetParser.addErrorListener(errorListener)

    val parseTree = snippetParser.block()
    errorListener.check()

    val contentMapping = mutableMapOf<Int, String>()
    val classNames = mutableSetOf<String>()
    val methodNames = mutableSetOf<String>()

    object : SnippetParserBaseVisitor<Unit>() {
        fun markAs(start: Int, stop: Int, type: String) {
            for (lineNumber in start - 1 until stop) {
                check(!contentMapping.containsKey(lineNumber)) { "line $lineNumber already marked" }
                contentMapping[lineNumber] = type
            }
        }

        override fun visitClassDeclaration(context: SnippetParser.ClassDeclarationContext) {
            markAs(context.start.line, context.stop.line, "class")
            val className = context.IDENTIFIER().text
            classNames.add(className)
        }

        override fun visitMethodDeclaration(context: SnippetParser.MethodDeclarationContext) {
            markAs(context.start.line, context.stop.line, "method")
            contentMapping[context.start.line - 1] = "method:start"
            val methodName = context.IDENTIFIER().text
            methodNames.add(methodName)
        }

        override fun visitImportDeclaration(context: SnippetParser.ImportDeclarationContext) {
            markAs(context.start.line, context.stop.line, "import")
        }
    }.visit(parseTree)

    val snippetClassName = generateName("Main", classNames)
    val snippetMainMethodName = generateName("main", methodNames)

    var currentOutputLineNumber = 1
    val remappedLineMapping = hashMapOf<Int, RemappedLine>()

    val importStatements = mutableListOf<String>()
    originalSource.lines().forEachIndexed { i, line ->
        val lineNumber = i + 1
        if (contentMapping[lineNumber] == "import") {
            importStatements.add(line)
            assert(!remappedLineMapping.containsKey(currentOutputLineNumber))
            remappedLineMapping[currentOutputLineNumber] = RemappedLine(lineNumber, currentOutputLineNumber)
            currentOutputLineNumber++
        }
    }

    val classDeclarations = mutableListOf<String>()
    originalSource.lines().forEachIndexed { i, line ->
        val lineNumber = i + 1
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
    originalSource.lines().forEachIndexed { i, line ->
        val lineNumber = i + 1
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
    val looseCodeStart = currentOutputLineNumber
    currentOutputLineNumber++

    val looseCode = mutableListOf<String>()
    originalSource.lines().forEachIndexed { i, line ->
        val lineNumber = i + 1
        if (!contentMapping.containsKey(lineNumber)) {
            looseCode.add(" ".repeat(indent * 2) + line)
            assert(!remappedLineMapping.containsKey(currentOutputLineNumber))
            remappedLineMapping[currentOutputLineNumber] = RemappedLine(lineNumber, currentOutputLineNumber, indent * 2)
            currentOutputLineNumber++
        }
    }

    checkLooseCode(looseCode.joinToString(separator = "\n"), looseCodeStart, remappedLineMapping)

    assert(originalSource.lines().size == remappedLineMapping.keys.size)

    var rewrittenSource = ""
    if (importStatements.size > 0) {
        rewrittenSource += importStatements.joinToString(separator = "\n", postfix = "\n")
    }
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
    currentOutputLineNumber += 1
    assert(currentOutputLineNumber == rewrittenSource.lines().size)

    return Snippet(
            hashMapOf(snippetClassName to rewrittenSource),
            originalSource,
            rewrittenSource,
            snippetClassName,
            remappedLineMapping
    )
}

class SnippetValidationError(location: SourceLocation, message: String?) : SourceError(location, message)
class SnippetValidationFailed(errors: List<SnippetValidationError>) : JeepError(errors)

private fun checkLooseCode(looseCode: String, looseCodeStart: Int, remappedLineMapping: Map<Int, RemappedLine>) {
    val charStream = CharStreams.fromString(looseCode)
    val javaLexer = JavaLexer(charStream)
    javaLexer.removeErrorListeners()
    val tokenStream = CommonTokenStream(javaLexer)
    tokenStream.fill()

    val validationErrors = tokenStream.tokens.filter {
        it.type == JavaLexer.RETURN
    }.map {
        SnippetValidationError(
                Snippet.mapLocation(SourceLocation(SNIPPET_SOURCE, it.line + looseCodeStart, it.charPositionInLine), remappedLineMapping),
                "return statements not allowed at top level in snippets"
        )
    }
    if (validationErrors.isNotEmpty()) {
        throw SnippetValidationFailed(validationErrors)
    }
}
