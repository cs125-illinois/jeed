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
import kotlin.math.roundToInt


private const val EQUAL_TO = "equal to"
private const val GREATER_THAN = "greater than"
private const val GREATER_THAN_OR_EQUAL_TO = "greater than or equal to"
private const val LESS_THAN = "less than"
private const val LESS_THAN_OR_EQUAL_TO = "less than or equal to"
private const val NOT_EQUAL_TO = "not equal to"

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
data class FuzzConfiguration(
    // These should not need to be changed from the default
    val fuzzyComparisonTargets: List<String> = listOf("==", "!=", "<", "<=", ">", ">="),

    // Default is null for these because we do not know if the user will provide targets, and if not,
    // we need to collect all of the non-fuzzy variables before creating and IdSupplier instance
    var fuzzyIdentifierTargets: IdSupplier? = null,
    var fuzzyLiteralTargets: LiteralSupplier? = null,

    // This is a list of optional transformations that the user may supply - defaults to empty for no transformations
    var fuzzyTransformations: MutableList<Transformation>? = mutableListOf(),

    var conditionals_boundary: Boolean = true,
    var conditionals_boundary_rand: Boolean = true

)
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
        val replacement = toReplace.replace(currentModification.content, currentModification.replace)

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

    if (fuzzConfiguration.fuzzyIdentifierTargets == null) { // In case the user does not provide any identifier targets
        val idCollector = IdentifierListener()
        walker.walk(idCollector, fuzzyJavaParseTree) //Pass to collect non-fuzzy ids
        fuzzConfiguration.fuzzyIdentifierTargets = IdSupplier(idCollector.getIdentifiers())
    }
    assert(fuzzConfiguration.fuzzyIdentifierTargets != null)
    if (fuzzConfiguration.fuzzyLiteralTargets == null) {
        fuzzConfiguration.fuzzyLiteralTargets = LiteralSupplier()
    }
    assert(fuzzConfiguration.fuzzyLiteralTargets != null)
    walker.walk(fuzzer, fuzzyJavaParseTree) // Pass to fuzz source

    //sourceModifications.map { it.value }.toSet()
    val sourceModifications: Set<SourceModification> = fuzzer.sourceModifications.map { it.value }.map {
        assert(it.startLine > 1 && it.endLine > 1)
        it.copy(startLine = it.startLine - 1, endLine = it.endLine - 1)
    }.toSet()
    var modifiedSource = sourceModifications.apply(block)
    modifiedSource = document(modifiedSource, sourceModifications)

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
    val fuzzer = Fuzzer(fuzzConfiguration)
    //val walker = ParseTreeWalker()

    /** TODO: Fix this

    if (fuzzConfiguration.fuzzyIdentifierTargets == null) { // In case the user does not provide any identifier targets
        val idCollector = IdentifierListener()
        walker.walk(idCollector, javaParseTree) //Pass to collect non-fuzzy ids
        fuzzConfiguration.fuzzyIdentifierTargets = IdSupplier(idCollector.getIdentifiers())
    }
    assert(fuzzConfiguration.fuzzyIdentifierTargets != null)
    if (fuzzConfiguration.fuzzyLiteralTargets == null) {
        fuzzConfiguration.fuzzyLiteralTargets = LiteralSupplier()
    }
    assert(fuzzConfiguration.fuzzyLiteralTargets != null)
    walker.walk(fuzzer, javaParseTree) // Pass to fuzz source

    */

    val sourceModifications = fuzzer.sourceModifications.map { it.value }.toSet()
    var modifiedSource = sourceModifications.apply(unit)
    modifiedSource = document(modifiedSource, sourceModifications)

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
    val fuzzyJavaParseTree = parseJava(unit).compilationUnit()
    val fuzzer = Fuzzer(fuzzConfiguration)
    val walker = ParseTreeWalker()

    /** TODO: Fix this

    if (fuzzConfiguration.fuzzyIdentifierTargets == null) { // In case the user does not provide any identifier targets
        val idCollector = IdentifierListener()
        walker.walk(idCollector, fuzzyJavaParseTree) //Pass to collect non-fuzzy ids
        fuzzConfiguration.fuzzyIdentifierTargets = IdSupplier(idCollector.getIdentifiers())
    }
    assert(fuzzConfiguration.fuzzyIdentifierTargets != null)
    if (fuzzConfiguration.fuzzyLiteralTargets == null) {
        fuzzConfiguration.fuzzyLiteralTargets = LiteralSupplier()
    }
    assert(fuzzConfiguration.fuzzyLiteralTargets != null)
    walker.walk(fuzzer, fuzzyJavaParseTree) // Pass to fuzz source

    */

    val sourceModifications = fuzzer.sourceModifications.map { it.value }.toSet()
    var modifiedSource = sourceModifications.apply(unit)

    return modifiedSource
}

private fun document(source : String, sourceModifications : Set<SourceModification>): String {
    val documenter = Documenter(source, sourceModifications)
    return documenter.generate()
}
//Todo: Better docs for code below
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

//Todo: Better class descriptions
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
    /** TODO: Fix this
    /**
     * Enter event method that is called when the parse tree walker visits a classDeclaration context.
     * Scope is partially maintained here.
     *
     * @param ctx - The classDeclaration context visited by the parse tree walker.
     */
    @Override
    override fun enterClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
        if (ctx.identifier().FUZZY_IDENTIFIER() != null) {
            val name = ctx.identifier().FUZZY_IDENTIFIER().symbol.text
            scopes.peek()[name] = configuration.fuzzyIdentifierTargets!!.nextId
        }
        // We do not push a new "scope" onto the stack because the methods within a class need to be visible to outer scopes
        // If they try to use a private fuzzed method or a fuzzed method from a private class then we delegate that error to the compiler
    }
    */
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
    /**
     * Enter event method that is called when the parse tree walker visits a enumDeclaration context.
     * Scope is partially maintained here.
     *
     * @param ctx - The enumDeclaration context visited by the parse tree walker.
     */
    @Override
    override fun enterEnumDeclaration(ctx: JavaParser.EnumDeclarationContext) {
        if (ctx.identifier().FUZZY_IDENTIFIER() != null) {
            val name = ctx.identifier().FUZZY_IDENTIFIER().symbol.text
            scopes.peek()[name] = configuration.fuzzyIdentifierTargets!!.nextId
        }
    }
    /**
     * Enter event method that is called when the parse tree walker visits a enumConstant context.
     * Scope is partially maintained here.
     *
     * @param ctx - The classDeclaration context visited by the parse tree walker.
     */
    @Override
    override fun enterEnumConstant(ctx: JavaParser.EnumConstantContext) {
        if (ctx.identifier().FUZZY_IDENTIFIER() != null) {
            val name = ctx.identifier().FUZZY_IDENTIFIER().symbol.text
            scopes.peek()[name] = configuration.fuzzyIdentifierTargets!!.nextId
        }
    }
     */
    /** TODO: Fix this
    /**
     * Enter event method that is called when the parse tree walker visits a fuzzyComparison context.
     *
     * @param ctx - The fuzzyComparison context visited by the parse tree walker.
     */
    @Override
    override fun enterFuzzyComparison(ctx: JavaParser.FuzzyComparisonContext) {
        val matchLength = ctx.stop.charPositionInLine + 1
        //The start won't ever be zero because that would be an illegal match
        sourceModifications.add(lazy {
            SourceModification(
                ctx.IDENTIFIER().text, ctx.start.line, ctx.start.charPositionInLine,
                ctx.start.line, matchLength, ctx.text, configuration.fuzzyComparisonTargets.random()
            )
        })
    }
    */
    /* TODO: Fix this
    /**
     * Enter event method that is called when the parse tree walker visits a fuzzyLiteral context.
     *
     * @param ctx - The fuzzyLiteral context visited by the parse tree walker.
     */
    override fun enterFuzzyLiteral(ctx: JavaParser.FuzzyLiteralContext) {
        val matchLength = ctx.stop.charPositionInLine + ctx.IDENTIFIER().text.length
        //The start won't ever be zero because that would be an illegal match
        sourceModifications.add(lazy {
            SourceModification(
                ctx.IDENTIFIER().text, ctx.start.line, ctx.start.charPositionInLine,
                ctx.start.line, matchLength, ctx.text, configuration.fuzzyLiteralTargets!!.next(ctx.FUZZY_LITERAL().text.substring(1))
            )
        })
    }

     */
    /* TODO: Fix this
    /**
     * Enter event method that is called when the parse tree walker visits an identifier context.
     *
     * @param ctx - identifier context visited by the parse tree walker.
     */
    @Override
    override fun enterIdentifier(ctx: JavaParser.IdentifierContext) {
        val fuzzyIdentifier = (ctx.FUZZY_IDENTIFIER() ?: return).symbol // The token that represents the fuzzy identifier
        val scopesCopy: Scopes = Stack()
        scopes.forEach { scopesCopy.add(it) }
        sourceModifications.add(lazy {
            val identifier = ctx.FUZZY_IDENTIFIER().text
            val startLine = fuzzyIdentifier.line
            val endLine = fuzzyIdentifier.line
            val startColumn = fuzzyIdentifier.charPositionInLine
            val endColumn = startColumn + fuzzyIdentifier.text.length
            val content = fuzzyIdentifier.text
            //Using firstOrNull traverses the stack bottom up so shadowed fuzzy identifiers map to the same generated id
            val map = scopesCopy.firstOrNull { it.containsKey(content) } ?: throw IllegalStateException("Fuzzy Id used but not declared.")
            val replacement = map[content]!!
            SourceModification(
                identifier, startLine, startColumn, endLine, endColumn, content, replacement
            )
        })
        // Find all of the scopes that contain this fuzzy id
        //val scopesWithFuzzyId = scopes.filter { it.containsKey(fuzzyIdentifier) }
    }
     */
    /**
     * Enter event method that is called when the parse tree walker visits a interfaceDeclaration context.
     * Scope is partially maintained here.
     *
     * @param ctx - The interfaceDeclaration context visited by the parse tree walker.
     */
    @Override
    override fun enterInterfaceDeclaration(ctx: JavaParser.InterfaceDeclarationContext) {
        /* TODO: Fix this
        if (ctx.identifier().FUZZY_IDENTIFIER() != null) {
            val name = ctx.identifier().FUZZY_IDENTIFIER().symbol.text
            scopes.peek()[name] = configuration.fuzzyIdentifierTargets!!.nextId
        }
        scopes.add(java.util.HashMap())

         */
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
     * Enter event method that is called when the parse tree walker visits a methodDeclaration context.
     * Scope is partially maintained here.
     *
     * @param ctx - methodDeclaration context visited by the parse tree walker.
     */
    @Override
    override fun enterMethodDeclaration(ctx: JavaParser.MethodDeclarationContext) {
        /* TODO: Fix this!
        if (ctx.identifier().FUZZY_IDENTIFIER() != null) {
            val name = ctx.identifier().FUZZY_IDENTIFIER().symbol.text
            scopes.peek()[name] = configuration.fuzzyIdentifierTargets!!.nextId
        }
        scopes.add(java.util.HashMap())
         */
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
    /**
     * Enter event method that is called when the parse tree walker visits a variableDeclaratorId context.
     * Scope is partially maintained here.
     *
     * @param ctx - The variableDeclaratorId context visited by the parse tree walker.
     */
    @Override
    override fun enterVariableDeclaratorId(ctx: JavaParser.VariableDeclaratorIdContext) {
        /* TODO: FIX THIS
        if (ctx.identifier().FUZZY_IDENTIFIER() != null) {
            val name = ctx.identifier().FUZZY_IDENTIFIER().symbol.text
            scopes.peek()[name] = configuration.fuzzyIdentifierTargets!!.nextId
        }
         */
    }

    /** TODO: Fix this

    /**
     * Enter a parse tree produced by [JavaParser.semicolon].
     * @param ctx the parse tree
     */
    @Override
    override fun enterSemicolon(ctx: JavaParser.SemicolonContext) {
        val removeSemicolonsTransformation = configuration.fuzzyTransformations?.find { it.name == "remove-semicolons" }
        if (removeSemicolonsTransformation != null) {
            val matchLength = ctx.stop.charPositionInLine + 1
            // Check that semicolon transformation is requested by the user
            if (removeSemicolonsTransformation.arguments.contains("all") || Math.random() > 0.5) {
                // If all semicolons are to be removed OR if semicolons are removed randomly and
                // random chance lands on true, remove semicolon
                sourceModifications.add(lazy {
                    SourceModification(
                        ctx.text, ctx.start.line, ctx.start.charPositionInLine,
                        ctx.start.line, matchLength, ";", " ")
                })
            }
        }
    }
    */

    override fun enterExpression(ctx: JavaParser.ExpressionContext) {
        if (ctx.childCount == 3) {
            val op = ctx.getChild(1)
            if (op.text == ">=") {
                if (configuration.conditionals_boundary && (!configuration.conditionals_boundary_rand || Math.random() > 0.5)) {
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
            }
            else if (op.text == "<=") {
                if (configuration.conditionals_boundary && (!configuration.conditionals_boundary_rand || Math.random() > 0.5)) {
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
            }
            else if (op.text == ">") {
                if (configuration.conditionals_boundary && (!configuration.conditionals_boundary_rand || Math.random() > 0.5)) {
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
            }
            else if (op.text == "<") {
                if (configuration.conditionals_boundary && (!configuration.conditionals_boundary_rand || Math.random() > 0.5)) {
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
            }
        }
    }
}
/**
 * A class that collects all of the user defined identifiers.
 *
 * Used by [IdSupplier].
 */
class IdentifierListener: JavaParserBaseListener() {
    /**The collected identifiers.*/
    private val identifiers: MutableSet<Pair<String, String>> = mutableSetOf()

    /** TODO: Fix this
    /**
     * Enter event method that is called when the parse tree walker visits an identifier context.
     * Used to collect non-fuzzy variables.
     *
     * @param ctx - The identifier context visited by the parse tree walker.
     */
    @Override
    override fun enterIdentifier(ctx: JavaParser.IdentifierContext) {
        if (ctx.IDENTIFIER() != null) {
            assert(ctx.parent != null)
            //Separates the identifiers into their respective namespaces
            when {
                ctx.parent is JavaParser.ClassDeclarationContext ||
                    ctx.parent is JavaParser.CreatedNameContext      ||
                    ctx.parent is JavaParser.InnerCreatorContext -> {
                    identifiers.add(Pair(CLASS, ctx.IDENTIFIER().text))
                }
                ctx.parent is JavaParser.EnumConstantContext -> {
                    identifiers.add(Pair(ENUM_CONSTANT, ctx.IDENTIFIER().text))
                }
                ctx.parent is JavaParser.InterfaceDeclarationContext -> {
                    identifiers.add(Pair(INTERFACE, ctx.IDENTIFIER().text))
                }
                ctx.parent is JavaParser.MethodDeclarationContext ||
                    ctx.parent is JavaParser.MethodCallContext-> {
                    identifiers.add(Pair(METHOD, ctx.IDENTIFIER().text))
                }
                ctx.parent is JavaParser.VariableDeclaratorIdContext ||
                    ctx.parent is JavaParser.PrimaryContext -> {
                    identifiers.add(Pair(VARIABLE, ctx.IDENTIFIER().text))
                }
            }
        }
    }
    */
    /**
     * Getter method for the user defined [identifiers].
     *
     * @return The user defined [identifiers] and their appropriate namespaces.
     */
    fun getIdentifiers(): Set<Pair<String, String>> {
        val toReturn = identifiers.toSet()
        identifiers.clear()
        return toReturn
    }
}

// Suppliers

/**
 * A class that lazily generates an infinite sequence of ids.
 *
 * @param definedIdentifiers the identifiers in the code
 * @param fuzzyIdentifiers an optional set of ids the user would like to replace the ids in the code
 *        with randomly - defaults to empty (NOTE: if definedIdentifiers.size > fuzzyIdentifiers.size,
 *        then a lazily generated infinite sequence of ids labeled cs125Id# will be used for the
 *        remaining ids)
 *
 * Used by [FuzzConfiguration.fuzzyIdentifierTargets].
 */
class IdSupplier(private val definedIdentifiers: Set<Pair<String, String>>, private val fuzzyIdentifiers: MutableList<String> = mutableListOf()) {
    /**The infinite sequence of identifiers to choose from.*/
    private var sequenceOfCS125Ids = Sequence { object : Iterator<String> {
        val identifierPrefix = "cs125Id_"
        var next = 0 // start at -1 so first number used is 0
        override fun hasNext(): Boolean {return true}
        override fun next(): String {
            return identifierPrefix + next++
        }
    } }.filter { Pair(CLASS, it) !in definedIdentifiers &&
    Pair(ENUM_CONSTANT, it) !in definedIdentifiers &&
    Pair(METHOD, it) !in definedIdentifiers &&
    Pair(VARIABLE, it) !in definedIdentifiers
    }
    /**
     * Getter method for the next identifier in the [sequenceOfIds].
     *
     * @return The next identifier in the [sequenceOfIds].
     */
    val nextId: String
        get() {
            if (!fuzzyIdentifiers.isEmpty()) {
                // If we have fuzzy identifiers left, find a random identifier to replace and then
                // remove it from the list so as not to have doubles
                return fuzzyIdentifiers.removeAt((Math.random() * fuzzyIdentifiers.size).toInt())
            }
            else {
                // If we run out of fuzzy identifiers, use cs125# to fuzz the remaining ids
                val newId = sequenceOfCS125Ids.first()
                sequenceOfCS125Ids = sequenceOfCS125Ids.drop(1)
                return newId
            }
        }

}
//Todo: Change this class so that the user is able to provide lists of primitives into the constructor
//Sequences are just used in the initial implementation
/**
 * A class that generates primitive literals
 */
class LiteralSupplier {
    /**The infinite sequence of integers to choose from.*/
    private var sequenceOfInts = Sequence { object : Iterator<Int> {
        var next = 0
        override fun hasNext(): Boolean {return true}
        override fun next(): Int {
            next += (Math.random() * 100).roundToInt() //Todo: Just an initial implementation
            return next
        }
    } }
    /**The infinite sequence of doubles to choose from.*/
    private var sequenceOfDoubles = Sequence { object : Iterator<Double> {
        var next = 0.0
        override fun hasNext(): Boolean {return true}
        override fun next(): Double {
            next += (Math.random() * 100).roundToInt() + Math.random() //Todo: Just an initial implementation
            return next
        }
    } }
    fun next(primitiveType: String): String {
        var ret: Number = -1
        when (primitiveType) {
            "int" -> ret = nextInt
            "double" -> ret = nextDouble
        }
        assert(ret != -1) //Just for testing to make sure ret was reassigned
        return ret.toString()
    }
    /**
     * Getter method for the next integer in the [sequenceOfInts].
     *
     * @return The next integer in the [sequenceOfInts].
     */
    private val nextInt: Int
        get () {
            val newInt = sequenceOfInts.first()
            sequenceOfInts = sequenceOfInts.drop(1)
            return newInt
        }
    /**
     * Getter method for the next double in the [sequenceOfDoubles].
     *
     * @return The next double in the [sequenceOfDoubles].
     */
    private val nextDouble: Double
        get () {
            val newDouble = sequenceOfDoubles.first()
            sequenceOfDoubles = sequenceOfDoubles.drop(1)
            return newDouble
        }


}

// Transformations

/**
 * A transformation/mutation that can be performed on a unit of code.
 */
interface Transformation {
    val name: String // The type of transformation
    val arguments: MutableSet<String> // A list of arguments for the transformation (size varies based on transformation type)
    val isSemanticsPreserving: Boolean // Whether the transformation preserves semantics (i.e. the code functions the same)
}

// -- Semantics Preserving Transformations --

/**
 * Inverts if-else statements so as to preserve semantics.
 *
 * ex. if (condition) { Code Block 1 } else { Code Block 2) ->
 *     if (!condition) { Code Block 2 } else { Code Block 1)
 *
 * @param includeElif true if if-else statements with else-if clauses are also to be inverted
 */
class InvertIfElse(private val includeElif : Boolean) : Transformation {
    override val name: String = "invert-if-else"
    override val arguments = mutableSetOf<String>()
    override val isSemanticsPreserving = true
    init {
        if (includeElif) {
            arguments.add("include-elif")
        }
    }
}

// -- Non-Semantics Preserving Transformations --

/**
 * Removes semicolons from the code, either randomly or entirely.
 *
 * @param rand true if semicolons are to be randomly removed, false if they are to all be removed
 */
class RemoveSemicolons(private val rand: Boolean) : Transformation {
    override val name: String = "remove-semicolons"
    override val arguments = mutableSetOf<String>()
    override val isSemanticsPreserving = false // Change this to true if Fuzzy Java is ported to Fuzzy Kotlin
    init {
        if (rand == true) {
            arguments.add("random")
        }
        else {
            arguments.add("all")
        }
    }
}

// Documenter

/**
 * A class that takes the modifications from generating a variant and documents the code.
 */
class Documenter(private val source: String, private var modifications: Set<SourceModification>) {
    /**
     * Generates descriptions of the modifications
     */
    fun generate(): String {
        var results : String = source
        for (modification in modifications) {
            if (modification.content.contains(FUZZY_COMPARISON)) {
                var replacement = ""
                when (modification.replace) {
                    "==" -> replacement = EQUAL_TO
                    ">" -> replacement = edu.illinois.cs.cs125.jeed.core.GREATER_THAN
                    ">=" -> replacement = GREATER_THAN_OR_EQUAL_TO
                    "<" -> replacement = edu.illinois.cs.cs125.jeed.core.LESS_THAN
                    "<=" -> replacement = LESS_THAN_OR_EQUAL_TO
                    "!=" -> replacement = NOT_EQUAL_TO
                }
                assert(replacement != "")
                results = results.replace(modification.content, replacement)
                continue
            }
            //Space added to the content so that fuzzy ids like ?i do not class with fuzzy literals ?int...
            results = results.replace(modification.content + " ", modification.replace + " ")
        }
        return results
    }

}