package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.JavaLexer
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParserBaseListener
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStreams
import org.antlr.v4.runtime.CommonTokenStream
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.tree.ParseTreeWalker
import java.util.Stack

// Fuzz

data class SourceModification(
    var identifier: String,
    var startLine: Int,
    var startColumn: Int,
    var endLine: Int,
    var endColumn: Int,
    val content: String,
    val replace: String
)

/**
 * Holds information about how the user would like to fuzz the code.
 */
class FuzzConfiguration {
    /**
     * The proportion of eligible transformations that should be performed if they are configured to random
     */
    var proportion_rand_transformed : Double = 0.50

    private var transformation_set : MutableSet<Transformation> = mutableSetOf()

    /**
     * Adds transformation if there is no other transformation of its type in the set
     */
    fun addTransformation(transformation_type : TransformationType, rand : Boolean = true) {
        if (transformation_set.find { it.type == transformation_type } == null) { // If transformation type does not already exist (we cannot have a random and all transformation of the same type)
            transformation_set.add(Transformation(transformation_type, rand))
        }
    }

    /**
     * Used to check if a certain transformation should be performed as per the user's configuration
     */
    public fun shouldTransform(transformation_type : TransformationType) : Boolean {
        var transformation : Transformation? = transformation_set.find { it.type == transformation_type }
        if (transformation == null) {
            return false // If there is no transformation of this type
        }
        else {
            if (!transformation.rand) {
                return true // The transformation is applied every time
            }
            else {
                return Math.random() < proportion_rand_transformed // The transformation is applied randomly
            }
        }
    }

    private data class Transformation(var type : TransformationType, var rand : Boolean = true)
}

/**
 * A type of transformation to be performed on the source code
 *
 * Mutations are based on (but slightly different from) the following: https://pitest.org/quickstart/mutators/
 */
enum class TransformationType {
    /**
     * Flips < with <=, > with >=, and vice versa. Cannot be used with CONDITIONALS_NEG or REMOVE_CONDITIONALS
     */
    CONDITIONALS_BOUNDARY,

    /**
     * Flips ++ with -- and vice versa. Cannot be used with REMOVE_INCREMENTS
     */
    INCREMENT,

    /**
     * Flips the non-operational + with - and vice versa (so "-1" to to "1" but NOT "1 + 1" to "1 - 1")
     */
    INVERT_NEGS,

    /**
     * Flips mathematical operators according to the following table:
     *
     * - to + (but NOT vice versa)
     * * to / and vice versa
     * % to * (but NOT vice versa)
     * >> to << (and vice versa)
     * >>> to << (but NOT vice versa)
     *
     * NOTE: We do not mutate the + operator because it is used for string concatenation
     */
    MATH,

    /**
     * Flips == to !=, >= to <, <= to >, and vice versa
     */
    CONDITIONALS_NEG,

    /**
     * Removes method calls that are either void or do not have their return values used or stored (so, despite the name, non-void methods can be removed)
     */
    VOID_METHOD_CALLS,

    /**
     * Replaces constructor calls with null
     *
     * NOTE: This mutation is a little unstable and may cause compile errors
     */
    CONSTRUCTOR_CALLS,

    /**
     * Mutates numeric literals according to the following table:
     *
     * true to false and vice versa
     * 0 and 5 to 1, -5 to -1, and otherwise increment the mutated value by 1 if positive and decrement by 1 if negative (0 is incremented)
     * 1.0 to 0.0 and any other value to 1.0
     */
    INLINE_CONSTANT,

    /**
     * Replace controls in if statements with 'true' literal
     */
    REMOVE_CONDITIONALS,

    /**
     * Removes ++ and --
     */
    REMOVE_INCREMENTS
}

/**
 * Applies source modifications to the source code
 *
 * @param source the source code that will be modified.
 * @return the modified source code.
 */
fun Set<SourceModification>.apply(source: String): String {
    val modifiedSource = source.lines().toMutableList()

    for(currentModification in this) {
        assert(currentModification.startLine == currentModification.endLine)

        val lineToModify = modifiedSource[currentModification.startLine - 1].toCharArray()
        val toReplace = lineToModify.slice(IntRange(currentModification.startColumn, currentModification.endColumn - 1)).joinToString(separator = "")
        var content = currentModification.content
        if (currentModification.content == "") {
            content = toReplace
        }
        val replacement = toReplace.replace(content, currentModification.replace)
        modifiedSource[currentModification.startLine - 1] =
            lineToModify.slice(IntRange(0, currentModification.startColumn - 1)).joinToString(separator = "") +
                replacement +
                lineToModify.slice(IntRange(currentModification.endColumn, lineToModify.size - 1)).joinToString(separator = "")

    }

    return modifiedSource.joinToString(separator = "\n")
}


/**
 * Fuzzes a "block" of template code.
 *
 * @param block - The block of source code to be fuzzed.
 * @param fuzzConfiguration - The config that will be used to modify the block.
 * @return Returns a block of Java code.
 */
//passed the source code to default of fuzz config so IdSupplier can get all of the non-fuzzy identifiers
fun fuzzBlock(block: String, fuzzConfiguration: FuzzConfiguration = FuzzConfiguration()): String {
    val fuzzyJavaParseTree = parseJava("""{
$block
}""").block()
    val fuzzer = Fuzzer(fuzzConfiguration)



    val walker = ParseTreeWalker()
    walker.walk(fuzzer, fuzzyJavaParseTree) // Pass to fuzz source

    //sourceModifications.map { it.value }.toSet()
    val sourceModifications: Set<SourceModification> = fuzzer.sourceModifications.map { it.value }.map {
        assert(it.startLine > 1 && it.endLine > 1)
        it.copy(startLine = it.startLine - 1, endLine = it.endLine - 1)
    }.toSet()
    var modifiedSource = sourceModifications.apply(block)
    //modifiedSource = document(modifiedSource, sourceModifications)
    parseJava("""{
$modifiedSource
}""").block()

    //println(getDocumentationOfProblem() + "\n\n")
    return modifiedSource
}
/**
 * Fuzzes a "unit" of template code.
 *
 * @param unit - the block of source code to be fuzzed
 * @param fuzzConfiguration - the config that will be used to modify the unit
 * @return a unit of Java code
 */
//passed the source code to default of fuzz config so IdSupplier can get all of the non-fuzzy identifiers
fun fuzzCompilationUnit(unit: String, fuzzConfiguration: FuzzConfiguration = FuzzConfiguration()): String {
    val javaParseTree = parseJava(unit).compilationUnit()
    val fuzzer = Fuzzer(fuzzConfiguration)
    val walker = ParseTreeWalker()
    walker.walk(fuzzer, javaParseTree) // Pass to fuzz source

    val sourceModifications = fuzzer.sourceModifications.map { it.value }.toSet()
    var modifiedSource = sourceModifications.apply(unit)
    //modifiedSource = document(modifiedSource, sourceModifications)

    parseJava("""{
$modifiedSource
}""").block()

    //println(getDocumentationOfProblem() + "\n\n")
    return modifiedSource
}

/**
 * Fuzzes the compilation unit without checking for syntax errors. Useful for
 * intentionally creating code with syntax errors (i.e. "find the bug" type
 * problems).
 *
 * @param unit - the block of source code to be fuzzed
 * @param fuzzConfiguration - The config that will be used to modify the unit.
 * @return returns a string (not a unit) representing the fuzzed Java code
 */
fun fuzzCompilationUnitWithoutParse(unit: String, fuzzConfiguration: FuzzConfiguration = FuzzConfiguration()): String {
    val javaParseTree = parseJava(unit).compilationUnit()
    val fuzzer = Fuzzer(fuzzConfiguration)
    val walker = ParseTreeWalker()

    walker.walk(fuzzer, javaParseTree) // Pass to fuzz source

    val sourceModifications = fuzzer.sourceModifications.map { it.value }.toSet()
    var modifiedSource = sourceModifications.apply(unit)

    return modifiedSource
}

/**
 * Used to check if code adheres to the template language syntax.
 *
 * @param source - the original source inputted by the user.
 * @return a parser
 */
internal fun parseFuzzyJava(source: String): JavaParser {
    val charStream = CharStreams.fromString(source)
    val fuzzyJavaLexer = JavaLexer(charStream)
    val tokenStream = CommonTokenStream(fuzzyJavaLexer)
    return JavaParser(tokenStream)
}

/**
 * A class that holds information about what went wrong while parsing Java code.
 */
class JavaParseException(val line: Int, val column: Int, message: String) : Exception(message)

/**
 * A class that creates a Java Listener.
 */
private class JavaExceptionListener : BaseErrorListener() {
    /**
     * Detects Java syntax errors.
     *
     * @param recognizer -
     * @param offendingSymbol - The illegal symbol.
     * @param line - The line [offendingSymbol] was on.
     * @param charPositionInLine - The character position of [offendingSymbol] within the [line].
     * @param msg - Error message to display.
     * @param e -
     * @throws [JavaParseException]
     */
    @Override
    override fun syntaxError(recognizer: Recognizer<*, *>?, offendingSymbol: Any?, line: Int, charPositionInLine: Int, msg: String, e: RecognitionException?) {
        throw JavaParseException(line, charPositionInLine, msg)
    }
}
/**
 * Parses Java code.
 *
 * @param source - The Java source code to be parsed.
 * @return Returns a parser.
 */
private fun parseJava(source: String): JavaParser {
    val javaErrorListener = JavaExceptionListener()
    val charStream = CharStreams.fromString(source)
    val javaLexer = JavaLexer(charStream)
    javaLexer.removeErrorListeners()
    javaLexer.addErrorListener(javaErrorListener)

    val tokenStream = CommonTokenStream(javaLexer)
    return JavaParser(tokenStream).also {
        it.removeErrorListeners()
        it.addErrorListener(javaErrorListener)
    }
}

// Listeners

private typealias Scopes = Stack<MutableMap<String, String>> //Todo: This mapping might change (key values could be set or map of RuleContexts)

//Namespaces
/**Class namespace.*/
internal const val CLASS = "CLASS"
/**Enum namespace.*/
internal const val ENUM_CONSTANT = "ENUM"
/**Interface namespace.*/
internal const val INTERFACE = "INTERFACE"
/**Method namespace.*/
internal const val METHOD = "METHOD"
/**Variable namespace.*/
internal const val VARIABLE = "VARIABLE"

const val FUZZY_COMPARISON = "?="

/**
 * A class that listens for and bookmarks fuzzy tokens as well as what they map to.
 */
class Fuzzer(private val configuration: FuzzConfiguration) : JavaParserBaseListener() {
    //enter and exit methods are in alphabetical order
    /**Keeps track of the members defined in particular scopes.*/
    private var scopes: Scopes = Scopes()
    /**Keeps track of the modifications made so they can be applied.*/
    internal val sourceModifications: MutableList<Lazy<SourceModification>> = mutableListOf()
    // Lazy because we need to allow for non-variable identifiers that are "used" before their definitions to map to the same generated id as their definitions
    /**
     * Enter event method that is called when the parse tree walker visits a block context.
     * Scope is partially maintained here.
     *
     * @param ctx - The block context visited by the parse tree walker.
     */
    @Override
    override fun enterBlock(ctx: JavaParser.BlockContext) {
        scopes.add(HashMap())
    }
    /**
     * Exit event method that is called when the parse tree walker visits an block context.
     * Scope is partially maintained here.
     *
     * @param ctx - The block context visited by the parse tree walker.
     */
    @Override
    override fun exitBlock(ctx: JavaParser.BlockContext) {
        scopes.pop()
    }
    /**
     * Enter event method that is called when the parse tree walker visits a compilationUnit context.
     * Scope is partially maintained here.
     *
     * @param ctx - The compilationUnit context visited by the parse tree walker.
     */
    @Override
    override fun enterCompilationUnit(ctx: JavaParser.CompilationUnitContext) {
        scopes.add(java.util.HashMap())
    }
    /**
     * Exit event method that is called when the parse tree walker visits an compilationUnit context.
     * Scope is partially maintained here.
     *
     * @param ctx - The compilationUnit context visited by the parse tree walker.
     */
    @Override
    override fun exitCompilationUnit(ctx: JavaParser.CompilationUnitContext) {
        scopes.pop()
    }

    /**
     * Exit event method that is called when the parse tree walker visits an interfaceDeclaration context.
     * Scope is partially maintained here.
     *
     * @param ctx - The interfaceDeclaration context visited by the parse tree walker.
     */
    @Override
    override fun exitInterfaceDeclaration(ctx: JavaParser.InterfaceDeclarationContext?) {
        scopes.pop()
    }
    /**
     * Exit event method that is called when the parse tree walker visits an methodDeclaration context.
     * Scope is partially maintained here.
     *
     * @param ctx - The methodDeclaration context visited by the parse tree walker.
     */
    @Override
    override fun exitMethodDeclaration(ctx: JavaParser.MethodDeclarationContext) {
        scopes.pop()
    }

    override fun enterExpression(ctx: JavaParser.ExpressionContext) {
        if (ctx.childCount == 2) {
            val left = ctx.getChild(0).text // ++ (pre), -- (pre)
            val right = ctx.getChild(1).text // ++ (post), -- (post)
            if (left == "++") { // ++ (pre)
                if (configuration.shouldTransform(TransformationType.INCREMENT)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine
                    var endLine = ctx.start.line
                    var endCol = ctx.start.charPositionInLine + left.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "++", "--")
                    })
                }
            }
            else if (left == "--") { // -- (pre)
                if (configuration.shouldTransform(TransformationType.INCREMENT)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine
                    var endLine = ctx.start.line
                    var endCol = ctx.start.charPositionInLine + left.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "--", "++")
                    })
                }
            }
            else if (right == "++") { // ++ (post)
                if (configuration.shouldTransform(TransformationType.INCREMENT)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.stop.charPositionInLine
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + right.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "++", "--")
                    })
                }
            }
            else if (right == "--") { // -- (post)
                if (configuration.shouldTransform(TransformationType.INCREMENT)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.stop.charPositionInLine
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + right.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "--", "++")
                    })
                }
            }
            else if (left == "+") { // + (sign)
                if (configuration.shouldTransform(TransformationType.INVERT_NEGS)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine
                    var endLine = ctx.start.line
                    var endCol = ctx.start.charPositionInLine + left.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "+", "-")
                    })
                }
            }
            else if (left == "-") { // - (sign)
                if (configuration.shouldTransform(TransformationType.INVERT_NEGS)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine
                    var endLine = ctx.start.line
                    var endCol = ctx.start.charPositionInLine + left.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "-", "")
                    })
                }
            }
            else if (left == "new") {
                if (configuration.shouldTransform(TransformationType.CONSTRUCTOR_CALLS)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "", "null")
                    })
                }
            }
        }
        if (ctx.childCount == 3) {
            val op = ctx.getChild(1).text // >=, <=, >, <, ==, !=, -, *, /, %, &, |, ^, <<, >>, >>>
            if (op == ">=") { // >=
                if (configuration.shouldTransform(TransformationType.CONDITIONALS_BOUNDARY)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(2).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, ">=", ">")
                    })
                }
                else if (configuration.shouldTransform(TransformationType.CONDITIONALS_NEG)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(2).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, ">=", "<")
                    })
                }
            }
            else if (op == "<=") { // <=
                if (configuration.shouldTransform(TransformationType.CONDITIONALS_BOUNDARY)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(2).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "<=", "<")
                    })
                }
                else if (configuration.shouldTransform(TransformationType.CONDITIONALS_NEG)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(2).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "<=", ">")
                    })
                }
            }
            else if (op == ">") { // >
                if (configuration.shouldTransform(TransformationType.CONDITIONALS_BOUNDARY)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(2).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, ">", ">=")
                    })
                }
                else if (configuration.shouldTransform(TransformationType.CONDITIONALS_NEG)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(2).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, ">", "<=")
                    })
                }
            }
            else if (op == "<") { // <
                if (configuration.shouldTransform(TransformationType.CONDITIONALS_BOUNDARY)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(2).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "<", "<=")
                    })
                }
                else if (configuration.shouldTransform(TransformationType.CONDITIONALS_NEG)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(2).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "<", ">=")
                    })
                }
            }
            else if (op == "==") { // ==
                if (configuration.shouldTransform(TransformationType.CONDITIONALS_NEG)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(2).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "==", "!=")
                    })
                }
            }
            else if (op == "!=") { // !=
                if (configuration.shouldTransform(TransformationType.CONDITIONALS_NEG)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(2).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "!=", "==")
                    })
                }
            }
            else if (op == "-") { // - (arithmetic)
                if (configuration.shouldTransform(TransformationType.MATH)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(2).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "-", "+")
                    })
                }
            }
            else if (op == "*") { // *
                if (configuration.shouldTransform(TransformationType.MATH)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(2).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "*", "/")
                    })
                }
            }
            else if (op == "/") { // /
                if (configuration.shouldTransform(TransformationType.MATH)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(2).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "/", "*")
                    })
                }
            }
            else if (op == "%") { // %
                if (configuration.shouldTransform(TransformationType.MATH)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(2).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "%", "*")
                    })
                }
            }
            else if (op == "&") { // &
                if (configuration.shouldTransform(TransformationType.MATH)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(2).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "&", "|")
                    })
                }
            }
            else if (op == "|") { // |
                if (configuration.shouldTransform(TransformationType.MATH)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(2).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "|", "&")
                    })
                }
            }
            else if (op == "^") { // ^
                if (configuration.shouldTransform(TransformationType.MATH)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(2).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "^", "&")
                    })
                }
            }
        }
        else if (ctx.childCount == 4) {
            val op = ctx.getChild(1).text + ctx.getChild(2).text // <<, >>
            if (op == "<<") { // <<
                if (configuration.shouldTransform(TransformationType.MATH)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(3).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, "<<", ">>")
                    })
                }
            }
            else if (op == ">>") { // >>
                if (configuration.shouldTransform(TransformationType.MATH)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(3).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, ">>", "<<")
                    })
                }
            }
        }
        else if (ctx.childCount == 5) {
            val op = ctx.getChild(1).text + ctx.getChild(2).text + ctx.getChild(3).text // <<<
            if (op == ">>>") { // >>>
                if (configuration.shouldTransform(TransformationType.MATH)) {
                    var startLine = ctx.start.line
                    var startCol = ctx.start.charPositionInLine + ctx.getChild(0).text.length
                    var endLine = ctx.start.line
                    var endCol = ctx.stop.charPositionInLine + 1 - ctx.getChild(2).text.length
                    sourceModifications.add(lazy {
                        SourceModification(
                            ctx.text, startLine, startCol,
                            endLine, endCol, ">>>", "<<"
                        )
                    })
                }
            }
        }
    }

    override fun enterStatement(ctx: JavaParser.StatementContext?) {
        if (ctx?.childCount == 2 && ctx.getChild(1).text == ";") { // Single statement
            var expression = ctx.getChild(0)
            if (expression.childCount == 1) {
                var singular_expression = expression.getChild(0)
                if (singular_expression.childCount == 3) {
                    if (singular_expression.getChild(1).text == "(" && singular_expression.getChild(2).text == ")") {
                        if (configuration.shouldTransform(TransformationType.VOID_METHOD_CALLS)) {
                            var startLine = ctx.start.line
                            var startCol = ctx.start.charPositionInLine
                            var endLine = ctx.start.line
                            var endCol = ctx.start.charPositionInLine + ctx.text.length
                            sourceModifications.add(lazy {
                                SourceModification(
                                    ctx.text, startLine, startCol,
                                    endLine, endCol, ctx.text, ""
                                )
                            })
                        }
                    }
                }
                else if (singular_expression.childCount == 4) {
                    if (singular_expression.getChild(1).text == "(" && singular_expression.getChild(3).text == ")") {
                        if (configuration.shouldTransform(TransformationType.VOID_METHOD_CALLS)) {
                            var startLine = ctx.start.line
                            var startCol = ctx.start.charPositionInLine
                            var endLine = ctx.start.line
                            var endCol = ctx.start.charPositionInLine + ctx.text.length
                            sourceModifications.add(lazy {
                                SourceModification(
                                    ctx.text, startLine, startCol,
                                    endLine, endCol, ctx.text, ""
                                )
                            })
                        }
                    }
                }
            }
            else if (expression.childCount == 3) {
                if (expression.getChild(1).text == ".") {
                    var singular_expression = expression.getChild(2)
                    if (singular_expression.childCount == 3) {
                        if (singular_expression.getChild(1).text == "(" && singular_expression.getChild(2).text == ")") {
                            if (configuration.shouldTransform(TransformationType.VOID_METHOD_CALLS)) {
                                var startLine = ctx.start.line
                                var startCol = ctx.start.charPositionInLine
                                var endLine = ctx.start.line
                                var endCol = ctx.start.charPositionInLine + ctx.text.length
                                sourceModifications.add(lazy {
                                    SourceModification(
                                        ctx.text, startLine, startCol,
                                        endLine, endCol, ctx.text, ""
                                    )
                                })
                            }
                        }
                    }
                    else if (singular_expression.childCount == 4) {
                        if (singular_expression.getChild(1).text == "(" && singular_expression.getChild(3).text == ")") {
                            if (configuration.shouldTransform(TransformationType.VOID_METHOD_CALLS)) {
                                var startLine = ctx.start.line
                                var startCol = ctx.start.charPositionInLine
                                var endLine = ctx.start.line
                                var endCol = ctx.start.charPositionInLine + ctx.text.length
                                sourceModifications.add(lazy {
                                    SourceModification(
                                        ctx.text, startLine, startCol,
                                        endLine, endCol, ctx.text, ""
                                    )
                                })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun enterFloatLiteral(ctx: JavaParser.FloatLiteralContext?) {
        if (configuration.shouldTransform(TransformationType.INLINE_CONSTANT)) {
            var num = ctx!!.text.toDouble()
            if (num == 1.0) {
                num = 0.0
            }
            else {
                num = 1.0
            }

            var startLine = ctx.start.line
            var startCol = ctx.start.charPositionInLine
            var endLine = ctx.start.line
            var endCol = ctx.start.charPositionInLine + ctx.text.length
            sourceModifications.add(lazy {
                SourceModification(
                    ctx.text, startLine, startCol,
                    endLine, endCol, ctx.text, num.toString())
            })
        }
    }

    override fun enterIntegerLiteral(ctx: JavaParser.IntegerLiteralContext?) {
        if (configuration.shouldTransform(TransformationType.INLINE_CONSTANT)) {
            var num = ctx!!.text.toInt()
            if (num == 0) {
                num = 1
            }
            else if (num == 5) {
                num = 1
            }
            else {
                num++
            }

            var startLine = ctx.start.line
            var startCol = ctx.start.charPositionInLine
            var endLine = ctx.start.line
            var endCol = ctx.start.charPositionInLine + ctx.text.length
            sourceModifications.add(lazy {
                SourceModification(
                    ctx.text, startLine, startCol,
                    endLine, endCol, "", num.toString())
            })
        }
    }

    override fun enterLiteral(ctx: JavaParser.LiteralContext?) {
        if (configuration.shouldTransform(TransformationType.INLINE_CONSTANT)) {
            val content = ctx!!.text
            var replace : String = ""
            if (content == "true") {
                replace = "false"
            }
            else if (content == "false") {
                replace = "true"
            }

            if (replace != "") {
                var startLine = ctx.start.line
                var startCol = ctx.start.charPositionInLine
                var endLine = ctx.start.line
                var endCol = ctx.start.charPositionInLine + ctx.text.length
                sourceModifications.add(lazy {
                    SourceModification(
                        ctx.text, startLine, startCol,
                        endLine, endCol, "", replace)
                })
            }
        }
    }
}
