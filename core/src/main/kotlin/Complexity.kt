package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.*
import org.antlr.v4.runtime.tree.ParseTreeWalker

val basicComplexityTokens = listOf(JavaLexer.FOR, JavaLexer.WHILE, JavaLexer.DO, JavaLexer.THROW)
val complexityExpressionBOPs = listOf(JavaLexer.AND, JavaLexer.OR, JavaLexer.QUESTION)

interface ComplexityValue {
    var complexity: Int
    fun lookup(name: String): ComplexityValue
}

@Suppress("UNCHECKED_CAST")
class ClassComplexity(
        name: String, range: SourceRange,
        methods: MutableMap<String, MethodComplexity> = mutableMapOf(),
        classes: MutableMap<String, ClassComplexity> = mutableMapOf(),
        override var complexity: Int = 0
) : LocatedClass(name, range, classes as MutableMap<String, LocatedClass>, methods as MutableMap<String, LocatedMethod>), ComplexityValue {
    override fun lookup(name: String): ComplexityValue {
        check(name.isNotEmpty())
        return try {
            if (name[0].isUpperCase()) {
                classes[name] as ComplexityValue
            } else {
                methods[name] as ComplexityValue
            }
        } catch (e: Exception) {
            if (name[0].isUpperCase()) {
                error("class $name not found: ${classes.keys}")
            } else {
                error("method $name not found: ${methods.keys}")
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
class MethodComplexity(
        name: String, range: SourceRange,
        classes: MutableMap<String, ClassComplexity> = mutableMapOf(),
        override var complexity: Int = 1
) : LocatedMethod(name, range, classes as MutableMap<String, LocatedClass>), ComplexityValue {
    override fun lookup(name: String): ComplexityValue {
        check(name.isNotEmpty())
        check(name[0].isUpperCase()) { "methods cannot contain other methods" }
        return try {
            classes[name] as ComplexityValue
        } catch (e: Exception) {
            error("class $name not found")
        }
    }
}

class ComplexityResult(val source: Source, entry: Map.Entry<String, String>) : JavaParserBaseListener() {
    private val name = entry.key
    @Suppress("unused")
    private val contents = entry.value

    private var complexityStack: MutableList<ComplexityValue> = mutableListOf()
    var results: MutableMap<String, ClassComplexity> = mutableMapOf()

    private fun enterClassOrInterface(classOrInterfaceName: String, start: Location, end: Location) {
        val locatedClass = if (source is Snippet && classOrInterfaceName == source.wrappedClassName) {
            ClassComplexity("", source.snippetRange)
        } else {
            ClassComplexity(classOrInterfaceName, SourceRange(name, source.mapLocation(name, start), source.mapLocation(name, end)))
        }
        if (complexityStack.isNotEmpty()) {
            when (val currentComplexity = complexityStack[0]) {
                is ClassComplexity -> {
                    assert(!currentComplexity.classes.containsKey(locatedClass.name))
                    currentComplexity.classes[locatedClass.name] = locatedClass
                }
                is MethodComplexity -> {
                    assert(!currentComplexity.classes.containsKey(locatedClass.name))
                    currentComplexity.classes[locatedClass.name] = locatedClass
                }
            }
        }
        complexityStack.add(0, locatedClass)
    }
    private fun exitClassOrInterface() {
        assert(complexityStack.isNotEmpty())
        val lastComplexity = complexityStack.removeAt(0)
        assert(lastComplexity is ClassComplexity)
        if (complexityStack.isNotEmpty()) {
            complexityStack[0].complexity += lastComplexity.complexity
        } else {
            val topLevelClassComplexity = lastComplexity as ClassComplexity
            assert(!results.keys.contains(topLevelClassComplexity.name))
            results[topLevelClassComplexity.name] = topLevelClassComplexity
        }
    }

    override fun enterClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
        enterClassOrInterface(ctx.children[1].text, Location(ctx.start.line, ctx.start.charPositionInLine), Location(ctx.stop.line, ctx.stop.charPositionInLine))
    }
    override fun exitClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
        exitClassOrInterface()
    }
    override fun enterInterfaceDeclaration(ctx: JavaParser.InterfaceDeclarationContext) {
        enterClassOrInterface(ctx.children[1].text, Location(ctx.start.line, ctx.start.charPositionInLine), Location(ctx.stop.line, ctx.stop.charPositionInLine))
    }
    override fun exitInterfaceDeclaration(ctx: JavaParser.InterfaceDeclarationContext?) {
        exitClassOrInterface()
    }

    private var currentMethodName: String? = null
    private var currentMethodLocation: SourceRange? = null
    private var currentMethodReturnType: String? = null
    private var currentMethodParameters: MutableList<String>? = null

    private fun enterMethodOrConstructor(methodOrConstructorName: String, start: Location, end: Location, returnType: String) {
        assert(complexityStack.isNotEmpty())
        assert(complexityStack[0] is ClassComplexity)

        assert(currentMethodName == null)
        assert(currentMethodLocation == null)
        assert(currentMethodReturnType == null)
        assert(currentMethodParameters == null)
        currentMethodName = methodOrConstructorName
        currentMethodLocation = SourceRange(name, start, end)
        currentMethodReturnType = returnType
        currentMethodParameters = mutableListOf()
    }
    private fun exitMethodOrConstructor() {
        assert(complexityStack.isNotEmpty())
        val lastComplexity = complexityStack.removeAt(0)
        assert(lastComplexity is MethodComplexity)
        assert(complexityStack.isNotEmpty())
        complexityStack[0].complexity += lastComplexity.complexity

        currentMethodName = null
        currentMethodLocation = null
        currentMethodReturnType = null
        currentMethodParameters = null
    }

    override fun enterMethodDeclaration(ctx: JavaParser.MethodDeclarationContext) {
        enterMethodOrConstructor(
                ctx.children[1].text,
                Location(ctx.start.line, ctx.start.charPositionInLine),
                Location(ctx.stop.line, ctx.stop.charPositionInLine),
                ctx.children[0].text
        )
    }
    override fun exitMethodDeclaration(ctx: JavaParser.MethodDeclarationContext) {
        exitMethodOrConstructor()
    }
    override fun enterConstructorDeclaration(ctx: JavaParser.ConstructorDeclarationContext) {
        assert(complexityStack.isNotEmpty())
        val currentClass = complexityStack[0] as ClassComplexity
        enterMethodOrConstructor(
                currentClass.name,
                Location(ctx.start.line, ctx.start.charPositionInLine),
                Location(ctx.stop.line, ctx.stop.charPositionInLine),
                currentClass.name
        )
    }
    override fun exitConstructorDeclaration(ctx: JavaParser.ConstructorDeclarationContext?) {
        exitMethodOrConstructor()
    }
    override fun enterFormalParameter(ctx: JavaParser.FormalParameterContext) {
        if (!insideLambda) {
            assert(ctx.children.size >= 2)
            currentMethodParameters?.add(ctx.children[ctx.children.lastIndex - 1].text)
        }
    }
    override fun enterLastFormalParameter(ctx: JavaParser.LastFormalParameterContext) {
        if (!insideLambda) {
            assert(ctx.children.size >= 2)
            val type = ctx.children[ctx.children.lastIndex - 1].text
            if (type != "...") {
                currentMethodParameters?.add(type)
            } else {
                assert(ctx.children.size > 3)
                currentMethodParameters?.add("...${ctx.children[ctx.children.lastIndex - 2].text}")
            }
        }
    }

    override fun exitFormalParameters(ctx: JavaParser.FormalParametersContext) {
        assert(complexityStack.isNotEmpty())
        assert(complexityStack[0] is ClassComplexity)

        assert(currentMethodName != null)
        assert(currentMethodLocation != null)
        assert(currentMethodReturnType != null)
        assert(currentMethodParameters != null)

        val fullName = "$currentMethodName(${currentMethodParameters?.joinToString(separator = ",")})"
        val methodComplexity = if (source is Snippet && source.looseCodeMethodName == fullName) {
            val snippetMethodComplexity = MethodComplexity("", source.snippetRange)
            // We add "throws Exception" to the main method wrapping loose code for snippets.
            // This hack ensures that we still calculate complexity correctly in this special case.
            snippetMethodComplexity.complexity = 0
            snippetMethodComplexity
        } else {
            MethodComplexity(
                    fullName,
                    SourceRange(name,
                            source.mapLocation(name, currentMethodLocation!!.start),
                            source.mapLocation(name, currentMethodLocation!!.end)
                    )
            )
        }
        val currentComplexity = complexityStack[0] as ClassComplexity
        assert(!currentComplexity.methods.containsKey(methodComplexity.name))
        currentComplexity.methods[methodComplexity.name] = methodComplexity
        complexityStack.add(0, methodComplexity)
    }

    override fun enterStatement(ctx: JavaParser.StatementContext) {
        assert(complexityStack.isNotEmpty())
        val currentMethod = complexityStack[0] as MethodComplexity

        val firstToken = ctx.getStart() ?: error("can't get first token in statement")

        // for, while, do and throw each represent one new path
        if (basicComplexityTokens.contains(firstToken.type)) {
            currentMethod.complexity++
        }

        // if statements only ever add one unit of complexity. If no else is present then we either enter the condition
        // or not, adding one path. If else is present then we either take the condition or the else, adding one path.
        if (firstToken.type == JavaLexer.IF) {
            currentMethod.complexity++
        }

    }
    override fun enterExpression(ctx: JavaParser.ExpressionContext) {
        assert(complexityStack.isNotEmpty())
        val currentMethod = complexityStack[0] as MethodComplexity

        val bop = ctx.bop?.type ?: return

        // &&, ||, and ? each represent one new path
        if (complexityExpressionBOPs.contains(bop)) {
            currentMethod.complexity++
        }
    }

    // Each switch label represents one new path
    override fun enterSwitchLabel(ctx: JavaParser.SwitchLabelContext) {
        assert(complexityStack.isNotEmpty())
        val currentMethod = complexityStack[0] as MethodComplexity
        currentMethod.complexity++
    }

    // Each throws clause in the method declaration indicates one new path
    override fun enterQualifiedNameList(ctx: JavaParser.QualifiedNameListContext) {
        assert(complexityStack.isNotEmpty())
        val currentMethod = complexityStack[0] as MethodComplexity
        currentMethod.complexity += ctx.children.size
    }
    // Each catch clause represents one new path
    override fun enterCatchClause(ctx: JavaParser.CatchClauseContext) {
        assert(complexityStack.isNotEmpty())
        val currentMethod = complexityStack[0] as MethodComplexity
        currentMethod.complexity++
    }

    // Ignore argument lists that are part of lambda expressions
    private var insideLambda = false
    override fun enterLambdaExpression(ctx: JavaParser.LambdaExpressionContext?) {
        insideLambda = true
    }
    override fun exitLambdaExpression(ctx: JavaParser.LambdaExpressionContext?) {
        insideLambda = false
    }

    private var insideSwitch = false
    override fun enterSwitchBlockStatementGroup(ctx: JavaParser.SwitchBlockStatementGroupContext) {
        insideSwitch = true
    }
    override fun exitSwitchBlockStatementGroup(ctx: JavaParser.SwitchBlockStatementGroupContext) {
        insideSwitch = false
    }

    init {
        ParseTreeWalker.DEFAULT.walk(this, source.parsed[name])
    }
}

class ComplexityResults(@Transient val source: Source, val results: Map<String, Map<String, ClassComplexity>>) {
    fun lookup(path: String, filename: String = ""): ComplexityValue {
        return try {
            val components = path.split(".").toMutableList()

            if (source is Snippet) {
                require(filename == "") { "filename cannot be set for snippet lookups" }
            }
            val resultSource = results[filename] ?: error("results does not contain key $filename")

            var currentComplexity = if (source is Snippet) {
                val rootComplexity = resultSource[""] ?: error("")
                if (path.isEmpty()) {
                    return rootComplexity
                } else if (path == ".") {
                    return rootComplexity.methods[""] as ComplexityValue
                }
                rootComplexity
            } else {
                resultSource[components.removeAt(0)]
            } as ComplexityValue

            for (component in components) {
                currentComplexity = currentComplexity.lookup(component)
            }
            currentComplexity
        } catch (e: Exception) {
            error("lookup failed: $e")
        }
    }
}

@Throws(JavaParsingException::class)
fun Source.complexity(names: Set<String> = sources.keys.toSet()): ComplexityResults {
    return ComplexityResults(this, sources.filter {
        names.contains(it.key)
    }.mapValues {
        ComplexityResult(this, it).results
    })
}
