package edu.illinois.cs.cs125.jeed

import antlr.ParseTree
import edu.illinois.cs.cs125.jeed.antlr.JavaLexer
import edu.illinois.cs.cs125.jeed.antlr.JavaParser
import org.antlr.v4.runtime.*

class JavaParseError(source: String?, line: Int, column: Int, message: String?) : SourceError(SourceLocation(source, line, column), message)
class JavaParsingFailed(errors: List<JavaParseError>) : JeepError(errors)

class JavaErrorListener : BaseErrorListener() {
    private val errors = mutableListOf<JavaParseError>()
    override fun syntaxError(recognizer: Recognizer<*, *>?, offendingSymbol: Any?, line: Int, charPositionInLine: Int, msg: String, e: RecognitionException?) {
        errors.add(JavaParseError(null, line, charPositionInLine, msg))
    }
    fun check() {
        if (errors.size > 0) {
            throw JavaParsingFailed(errors)
        }
    }
}

fun parse(source: String, errorListener: JavaErrorListener): JavaParser {
    val charStream = CharStreams.fromString(source)
    val javaLexer = JavaLexer(charStream)
    javaLexer.removeErrorListeners()
    javaLexer.addErrorListener(errorListener)

    val tokenStream = CommonTokenStream(javaLexer)
    errorListener.check()

    val javaParser = JavaParser(tokenStream)
    javaParser.removeErrorListeners()
    javaParser.addErrorListener(errorListener)

    return javaParser
}
fun parseCompilationUnit(source: String): JavaParser.CompilationUnitContext {
    val errorListener = JavaErrorListener()
    val toReturn = parse(source, errorListener).compilationUnit()
    errorListener.check()
    return toReturn
}
