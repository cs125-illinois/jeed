package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParserBaseListener
import org.antlr.v4.runtime.tree.ParseTreeWalker

enum class FeatureName {
    LOCAL_VARIABLE_DECLARATIONS,
    VARIABLE_ASSIGNMENTS,
    VARIABLE_REASSIGNMENTS,
    // Operators
    UNARY_OPERATORS,
    ARITHMETIC_OPERATORS,
    BITWISE_OPERATORS,
    ASSIGNMENT_OPERATORS,
    TERNARY_OPERATOR,
    CONDITIONAL,
    COMPLEX_CONDITIONAL,
    // If & Else
    IF_STATEMENTS,
    ELSE_STATEMENTS,
    ELSE_IF,
    // Arrays
    ARRAY_ACCESS,
    ARRAY_LITERAL,
    MULTIDIMENSIONAL_ARRAYS,
    // Loops
    FOR_LOOPS,
    ENHANCED_FOR,
    WHILE_LOOPS,
    DO_WHILE_LOOPS,
    // Nesting
    NESTED_IF,
    NESTED_FOR,
    NESTED_WHILE,
    NESTED_DO_WHILE,
    // Methods
    METHOD,
    CONSTRUCTOR,
    GETTER,
    SETTER,
    // Strings & null
    STRING,
    NULL,
    // Type handling
    CASTING,
    TYPE_INFERENCE,
    INSTANCEOF,
    // Class & Interface
    CLASS,
    IMPLEMENTS,
    INTERFACE,
    // Polymorphism
    EXTENDS,
    SUPER,
    OVERRIDE,
    // Exceptions
    TRY_BLOCK,
    FINALLY,
    ASSERT,
    THROW,
    THROWS,
    // Objects
    NEW_KEYWORD,
    THIS,
    REFERENCE_EQUALITY,
    // Modifiers
    VISIBILITY_MODIFIERS,
    STATIC_METHOD,
    FINAL_METHOD,
    ABSTRACT_METHOD,
    FINAL_CLASS,
    ABSTRACT_CLASS,
    // Import
    IMPORT,
    // Misc.
    ANONYMOUS_CLASSES,
    LAMBDA_EXPRESSIONS,
    GENERIC_CLASS,
    SWITCH,
    STREAM,
    ENUM,
    RECURSION,
    COMPARABLE
}

data class Features(
    var featureMap: MutableMap<FeatureName, Int> = FeatureName.values().associate { it to 0 }.toMutableMap(),
    var importList: MutableList<String> = arrayListOf(),
    var typeList: MutableList<String> = arrayListOf(),
    var identifierList: MutableList<String> = arrayListOf(),
    var skeleton: String = ""
) {
    operator fun plus(other: Features): Features {
        val map = mutableMapOf<FeatureName, Int>()
        for (key in FeatureName.values()) {
            map[key] = featureMap[key]!! + other.featureMap[key]!!
        }
        importList.addAll(other.importList)
        typeList.addAll(other.typeList)
        identifierList.addAll(other.identifierList)
        return Features(
            map,
            importList,
            typeList,
            identifierList,
            skeleton + " " + other.skeleton
        )
    }
}

sealed class FeatureValue(
    name: String,
    range: SourceRange,
    methods: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    classes: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    var features: Features
) : LocatedClassOrMethod(name, range, methods, classes) {
    fun lookup(name: String): FeatureValue {
        check(name.isNotEmpty())
        return if (name[0].isUpperCase() && !name.endsWith(")")) {
            classes[name] ?: error("class $name not found ${classes.keys}")
        } else {
            methods[name] ?: error("method $name not found ${methods.keys}")
        } as FeatureValue
    }
}

@JsonClass(generateAdapter = true)
class ClassFeatures(
    name: String,
    range: SourceRange,
    methods: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    classes: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    features: Features = Features(),
) : FeatureValue(name, range, methods, classes, features)

@JsonClass(generateAdapter = true)
class MethodFeatures(
    name: String,
    range: SourceRange,
    methods: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    classes: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    features: Features = Features(),
) : FeatureValue(name, range, methods, classes, features)

@JsonClass(generateAdapter = true)
class UnitFeatures(
    name: String,
    range: SourceRange,
    methods: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    classes: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    features: Features = Features(),
) : FeatureValue(name, range, methods, classes, features)

@Suppress("TooManyFunctions")
private class FeatureListener(val source: Source, entry: Map.Entry<String, String>) : JavaParserBaseListener() {
    @Suppress("unused")
    private val contents = entry.value
    private val filename = entry.key

    private var featureStack: MutableList<FeatureValue> = mutableListOf()
    private val currentFeatures: FeatureValue
        get() = featureStack[0]
    var results: MutableMap<String, UnitFeatures> = mutableMapOf()

    private fun enterClassOrInterface(name: String, start: Location, end: Location) {
        val locatedClass = if (source is Snippet && name == source.wrappedClassName) {
            ClassFeatures("", source.snippetRange)
        } else {
            ClassFeatures(
                name,
                SourceRange(filename, source.mapLocation(filename, start), source.mapLocation(filename, end))
            )
        }
        if (featureStack.isNotEmpty()) {
            assert(!currentFeatures.classes.containsKey(locatedClass.name))
            currentFeatures.classes[locatedClass.name] = locatedClass
        }
        featureStack.add(0, locatedClass)
    }

    private fun enterMethodOrConstructor(name: String, start: Location, end: Location) {
        val locatedMethod =
            if (source is Snippet &&
                "void " + source.looseCodeMethodName == name &&
                (featureStack.getOrNull(0) as? ClassFeatures)?.name == ""
            ) {
                MethodFeatures("", source.snippetRange)
            } else {
                MethodFeatures(
                    name,
                    SourceRange(name, source.mapLocation(filename, start), source.mapLocation(filename, end))
                )
            }
        if (featureStack.isNotEmpty()) {
            assert(!currentFeatures.methods.containsKey(locatedMethod.name))
            currentFeatures.methods[locatedMethod.name] = locatedMethod
        }
        featureStack.add(0, locatedMethod)
    }

    private fun exitClassOrInterface() {
        assert(featureStack.isNotEmpty())
        val lastFeatures = featureStack.removeAt(0)
        assert(lastFeatures is ClassFeatures)
        if (featureStack.isNotEmpty()) {
            currentFeatures.features += lastFeatures.features
        }
    }

    private fun exitMethodOrConstructor() {
        assert(featureStack.isNotEmpty())
        val lastFeatures = featureStack.removeAt(0)
        assert(lastFeatures is MethodFeatures)
        assert(featureStack.isNotEmpty())
        currentFeatures.features += lastFeatures.features
    }

    override fun enterCompilationUnit(ctx: JavaParser.CompilationUnitContext) {
        val unitFeatures = UnitFeatures(
            filename,
            SourceRange(
                filename,
                Location(ctx.start.line, ctx.start.charPositionInLine),
                Location(ctx.stop.line, ctx.stop.charPositionInLine)
            )
        )
        assert(featureStack.isEmpty())
        featureStack.add(0, unitFeatures)
        count(
            FeatureName.IMPORT,
            ctx.importDeclaration().filter {
                it.IMPORT() != null
            }.size
        )
        count(
            FeatureName.FINAL_CLASS,
            ctx.typeDeclaration().filter { declaration ->
                declaration.classOrInterfaceModifier().any {
                    it.FINAL() != null
                }
            }.size
        )
        count(
            FeatureName.ABSTRACT_CLASS,
            ctx.typeDeclaration().filter { declaration ->
                declaration.classOrInterfaceModifier().any {
                    it.ABSTRACT() != null
                }
            }.size
        )
        count(
            FeatureName.VISIBILITY_MODIFIERS,
            ctx.typeDeclaration().filter { declaration ->
                declaration.classOrInterfaceModifier().any {
                    when (it.text) {
                        "public", "private", "protected" -> true
                        else -> false
                    }
                }
            }.size
        )
        for (import in ctx.importDeclaration()) {
            currentFeatures.features.importList.add(import.qualifiedName().text)
        }
    }

    override fun exitCompilationUnit(ctx: JavaParser.CompilationUnitContext) {
        assert(featureStack.size == 1)
        val topLevelFeatures = featureStack.removeAt(0) as UnitFeatures
        assert(!results.keys.contains(topLevelFeatures.name))
        results[topLevelFeatures.name] = topLevelFeatures
    }

    override fun enterClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
        count(FeatureName.CLASS, 1)
        enterClassOrInterface(
            ctx.IDENTIFIER().text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
        count(
            FeatureName.STATIC_METHOD,
            ctx.classBody().classBodyDeclaration().filter { declaration ->
                declaration.modifier().any {
                    it.classOrInterfaceModifier().STATIC() != null &&
                        declaration.memberDeclaration().methodDeclaration() != null
                }
            }.size
        )
        count(
            FeatureName.FINAL_METHOD,
            ctx.classBody().classBodyDeclaration().filter { declaration ->
                declaration.modifier().any {
                    it.classOrInterfaceModifier().FINAL() != null &&
                        declaration.memberDeclaration().methodDeclaration() != null
                }
            }.size
        )
        count(
            FeatureName.ABSTRACT_METHOD,
            ctx.classBody().classBodyDeclaration().filter { declaration ->
                declaration.modifier().any {
                    it.classOrInterfaceModifier().ABSTRACT() != null &&
                        declaration.memberDeclaration().methodDeclaration() != null
                }
            }.size
        )
        count(
            FeatureName.VISIBILITY_MODIFIERS,
            ctx.classBody().classBodyDeclaration().filter { declaration ->
                declaration.modifier().any {
                    when (it.text) {
                        "public", "private", "protected" -> true
                        else -> false
                    }
                }
            }.size
        )
        count(
            FeatureName.OVERRIDE,
            ctx.classBody().classBodyDeclaration().filter { declaration ->
                declaration.modifier().any {
                    it?.text == "@Override"
                }
            }.size
        )
        count(
            FeatureName.FINAL_CLASS,
            ctx.classBody().classBodyDeclaration().filter { declaration ->
                declaration.modifier().any {
                    it.classOrInterfaceModifier().FINAL() != null &&
                        declaration.memberDeclaration().classDeclaration() != null
                }
            }.size
        )
        count(
            FeatureName.ABSTRACT_CLASS,
            ctx.classBody().classBodyDeclaration().filter { declaration ->
                declaration.modifier().any {
                    it.classOrInterfaceModifier().ABSTRACT() != null &&
                        declaration.memberDeclaration().classDeclaration() != null
                }
            }.size
        )
        ctx.EXTENDS()?.also {
            count(FeatureName.EXTENDS, 1)
        }
        ctx.IMPLEMENTS()?.also {
            count(FeatureName.IMPLEMENTS, 1)
            if (ctx.typeList().text.contains("Comparable")) count(FeatureName.COMPARABLE, 1)
        }
        ctx.typeParameters()?.also {
            count(FeatureName.GENERIC_CLASS, 1)
        }
    }

    override fun exitClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
        exitClassOrInterface()
    }

    override fun enterInterfaceDeclaration(ctx: JavaParser.InterfaceDeclarationContext) {
        enterClassOrInterface(
            ctx.IDENTIFIER().text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
        count(FeatureName.INTERFACE, 1)
        count(
            FeatureName.STATIC_METHOD,
            ctx.interfaceBody().interfaceBodyDeclaration().filter { declaration ->
                declaration.modifier().any {
                    it.classOrInterfaceModifier().STATIC() != null &&
                        declaration.interfaceMemberDeclaration().interfaceMethodDeclaration() != null
                }
            }.size
        )
        count(
            FeatureName.FINAL_METHOD,
            ctx.interfaceBody().interfaceBodyDeclaration().filter { declaration ->
                declaration.modifier().any {
                    it.classOrInterfaceModifier().FINAL() != null &&
                        declaration.interfaceMemberDeclaration().interfaceMethodDeclaration() != null
                }
            }.size
        )
        count(
            FeatureName.ABSTRACT_METHOD,
            ctx.interfaceBody().interfaceBodyDeclaration().filter { declaration ->
                declaration.modifier().any {
                    it.classOrInterfaceModifier().ABSTRACT() != null &&
                        declaration.interfaceMemberDeclaration().interfaceMethodDeclaration() != null
                }
            }.size
        )
        count(
            FeatureName.VISIBILITY_MODIFIERS,
            ctx.interfaceBody().interfaceBodyDeclaration().filter { declaration ->
                declaration.modifier().any {
                    when (it.text) {
                        "public", "private", "protected" -> true
                        else -> false
                    }
                }
            }.size
        )
    }

    override fun exitInterfaceDeclaration(ctx: JavaParser.InterfaceDeclarationContext?) {
        exitClassOrInterface()
    }

    override fun enterEnumDeclaration(ctx: JavaParser.EnumDeclarationContext) {
        enterClassOrInterface(
            ctx.IDENTIFIER().text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
        count(FeatureName.ENUM, 1)
    }

    override fun exitEnumDeclaration(ctx: JavaParser.EnumDeclarationContext) {
        exitClassOrInterface()
    }

    override fun enterRecordDeclaration(ctx: JavaParser.RecordDeclarationContext) {
        enterClassOrInterface(
            ctx.IDENTIFIER().text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
    }

    override fun exitRecordDeclaration(ctx: JavaParser.RecordDeclarationContext?) {
        exitClassOrInterface()
    }

    override fun enterMethodDeclaration(ctx: JavaParser.MethodDeclarationContext) {
        val parameters = ctx.formalParameters().formalParameterList()?.formalParameter()?.joinToString(",") {
            it.typeType().text
        } ?: ""
        enterMethodOrConstructor(
            "${ctx.typeTypeOrVoid().text} ${ctx.IDENTIFIER().text}($parameters)",
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
        count(FeatureName.METHOD, 1)
        if (ctx.IDENTIFIER().text.startsWith("get")) {
            count(FeatureName.GETTER, 1)
        }
        if (ctx.IDENTIFIER().text.startsWith("set")) {
            count(FeatureName.SETTER, 1)
        }
        ctx.THROWS()?.also {
            count(FeatureName.THROWS, 1)
        }
        count(
            FeatureName.FINAL_CLASS,
            ctx.methodBody().block()?.blockStatement()?.filter { statement ->
                statement.localTypeDeclaration()?.classOrInterfaceModifier()?.any {
                    it.FINAL() != null && statement.localTypeDeclaration().classDeclaration() != null
                } ?: false
            }?.size ?: 0
        )
        count(
            FeatureName.ABSTRACT_CLASS,
            ctx.methodBody().block()?.blockStatement()?.filter { statement ->
                statement.localTypeDeclaration()?.classOrInterfaceModifier()?.any {
                    it.ABSTRACT() != null && statement.localTypeDeclaration().classDeclaration() != null
                } ?: false
            }?.size ?: 0
        )
    }

    override fun exitMethodDeclaration(ctx: JavaParser.MethodDeclarationContext) {
        exitMethodOrConstructor()
    }

    override fun enterInterfaceMethodDeclaration(ctx: JavaParser.InterfaceMethodDeclarationContext) {
        val parameters = ctx.formalParameters().formalParameterList()?.formalParameter()?.joinToString(",") {
            it.typeType().text
        } ?: ""
        enterMethodOrConstructor(
            "${ctx.typeTypeOrVoid().text} ${ctx.IDENTIFIER().text}($parameters)",
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
        count(FeatureName.METHOD, 1)
    }

    override fun exitInterfaceMethodDeclaration(ctx: JavaParser.InterfaceMethodDeclarationContext) {
        exitMethodOrConstructor()
    }

    override fun enterConstructorDeclaration(ctx: JavaParser.ConstructorDeclarationContext) {
        assert(featureStack.isNotEmpty())
        val currentClass = currentFeatures as ClassFeatures
        val parameters = ctx.formalParameters().formalParameterList().formalParameter().joinToString(",") {
            it.typeType().text
        }
        enterMethodOrConstructor(
            "${currentClass.name}($parameters)",
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine),
        )
        count(FeatureName.CONSTRUCTOR, 1)
        ctx.THROWS()?.also {
            count(FeatureName.THROWS, 1)
        }
    }

    override fun exitConstructorDeclaration(ctx: JavaParser.ConstructorDeclarationContext?) {
        exitMethodOrConstructor()
    }

    private val seenObjectIdentifiers = mutableSetOf<String>()

    override fun enterLocalVariableDeclaration(ctx: JavaParser.LocalVariableDeclarationContext) {
        count(FeatureName.LOCAL_VARIABLE_DECLARATIONS, ctx.variableDeclarators().variableDeclarator().size)
        count(
            FeatureName.VARIABLE_ASSIGNMENTS,
            ctx.variableDeclarators().variableDeclarator().filter {
                it.variableInitializer() != null
            }.size
        )
        count(
            FeatureName.ARRAY_LITERAL,
            ctx.variableDeclarators().variableDeclarator().filter {
                it.variableInitializer()?.arrayInitializer() != null
            }.size
        )
        if (ctx.typeType().classOrInterfaceType()?.IDENTIFIER(0)?.text == "String") {
            count(FeatureName.STRING, 1)
        }
        if (ctx.typeType().classOrInterfaceType()?.IDENTIFIER(0)?.text == "Stream") {
            count(FeatureName.STREAM, 1)
        }
        if (ctx.typeType().text.contains("[][]")) {
            count(FeatureName.MULTIDIMENSIONAL_ARRAYS, 1)
        }
        if (ctx.typeType().text.contains("var")) {
            count(FeatureName.TYPE_INFERENCE, 1)
        }
        // Check if variable is an object
        ctx.typeType().classOrInterfaceType()?.also {
            for (declarator in ctx.variableDeclarators().variableDeclarator()) {
                seenObjectIdentifiers += declarator.variableDeclaratorId().IDENTIFIER().text
            }
            currentFeatures.features.typeList.add(it.text)
        }
        ctx.typeType().primitiveType()?.also {
            currentFeatures.features.typeList.add(it.text)
        }
        for (declarator in ctx.variableDeclarators().variableDeclarator()) {
            currentFeatures.features.identifierList.add(declarator.variableDeclaratorId().IDENTIFIER().text)
        }
    }

    private val seenIfStarts = mutableSetOf<Int>()

    private val seenElseStatementStarts = mutableSetOf<Int>()
    private var numNestedElse = 0

    private val currentFeatureMap: MutableMap<FeatureName, Int>
        get() = currentFeatures.features.featureMap

    private fun count(feature: FeatureName, amount: Int) {
        currentFeatureMap[feature] = (currentFeatureMap[feature] ?: 0) + amount
    }

    override fun enterStatement(ctx: JavaParser.StatementContext) {
        ctx.statementExpression?.also {
            if (it.bop?.text == "=") {
                count(FeatureName.VARIABLE_ASSIGNMENTS, 1)
                count(FeatureName.VARIABLE_REASSIGNMENTS, 1)
            }
        }
        ctx.FOR()?.also {
            currentFeatures.features.skeleton += "for "
            count(FeatureName.FOR_LOOPS, 1)
            if (ctx.forControl().enhancedForControl() != null) {
                count(FeatureName.ENHANCED_FOR, 1)
                count(FeatureName.LOCAL_VARIABLE_DECLARATIONS, 1)
            }
        }
        ctx.WHILE()?.also {
            // Only increment whileLoopCount if it's not a do-while loop
            if (ctx.DO() != null) {
                currentFeatures.features.skeleton += "do "
                count(FeatureName.DO_WHILE_LOOPS, 1)
            } else {
                count(FeatureName.WHILE_LOOPS, 1)
            }
            currentFeatures.features.skeleton += "while "
        }
        ctx.IF()?.also {
            // Check for else-if chains
            val outerIfStart = ctx.start.startIndex
            if (outerIfStart !in seenIfStarts) {
                currentFeatures.features.skeleton += "if "
                // Count if block
                count(FeatureName.IF_STATEMENTS, 1)
                seenIfStarts += outerIfStart
                check(ctx.statement().isNotEmpty())

                if (ctx.statement().size == 2 && ctx.statement(1).block() != null) {
                    // Count else block
                    check(ctx.ELSE() != null)
                    count(FeatureName.ELSE_STATEMENTS, 1)
                    seenElseStatementStarts += ctx.statement(1).start.startIndex
                } else if (ctx.statement().size >= 2) {
                    var statement = ctx.statement(1)
                    while (statement != null) {
                        if (statement.IF() != null) {
                            // If statement contains an IF, it is part of a chain
                            seenIfStarts += statement.start.startIndex
                            count(FeatureName.ELSE_IF, 1)
                        } else {
                            count(FeatureName.ELSE_STATEMENTS, 1)
                        }
                        statement = statement.statement(1)
                    }
                }
            }
        }
        ctx.TRY()?.also {
            count(FeatureName.TRY_BLOCK, 1)
            ctx.finallyBlock()?.also {
                count(FeatureName.FINALLY, 1)
            }
        }
        ctx.ASSERT()?.also {
            count(FeatureName.ASSERT, 1)
        }
        ctx.SWITCH()?.also {
            count(FeatureName.SWITCH, 1)
        }
        ctx.THROW()?.also {
            count(FeatureName.THROW, 1)
        }
        if (seenElseStatementStarts.contains(ctx.start.startIndex)) {
            seenElseStatementStarts.remove(ctx.start.startIndex)
            currentFeatures.features.skeleton += "else "
            var hasNesting = 0
            for (block in ctx.block().blockStatement()) {
                val blockStatement = block.statement()
                blockStatement?.FOR()?.also {
                    hasNesting = 1
                }
                blockStatement?.IF()?.also {
                    hasNesting = 1
                }
                blockStatement?.WHILE()?.also {
                    hasNesting = 1
                }
            }
            if (hasNesting == 1) {
                currentFeatures.features.skeleton += "{ "
            } else if (hasNesting == 0) {
                if (numNestedElse > 0) {
                    currentFeatures.features.skeleton += "} "
                    numNestedElse--
                }
            }
        }
        // Count nested statements
        val list = mutableListOf<String>()
        var hasNesting = 0
        for (ctxStatement in ctx.statement()) {
            ctxStatement?.block()?.also {
                hasNesting = 0
                val statement = ctxStatement.block().blockStatement()
                for (block in statement) {
                    val blockStatement = block.statement()
                    blockStatement?.FOR()?.also {
                        hasNesting = 1
                        list.add("for")
                        count(FeatureName.NESTED_FOR, 1)
                    }
                    blockStatement?.IF()?.also {
                        hasNesting = 1
                        list.add("if")
                        count(FeatureName.NESTED_IF, 1)
                        blockStatement.ELSE()?.also {
                            list.add("else")
                            numNestedElse++
                        }
                    }
                    blockStatement?.WHILE()?.also {
                        hasNesting = 1
                        if (block.statement().DO() != null) {
                            list.add("do")
                            count(FeatureName.NESTED_DO_WHILE, 1)
                        } else {
                            list.add("while")
                            count(FeatureName.NESTED_WHILE, 1)
                        }
                    }
                }
                if (hasNesting == 0) {
                    if (ctx.ELSE() == null) currentFeatures.features.skeleton += "} "
                }
            }
        }
        if (hasNesting == 1) {
            currentFeatures.features.skeleton += "{ "
        }
    }

    override fun enterExpression(ctx: JavaParser.ExpressionContext) {
        when (ctx.bop?.text) {
            "<", ">", "<=", ">=", "==", "!=" -> count(FeatureName.CONDITIONAL, 1)
            "&&", "||" -> count(FeatureName.COMPLEX_CONDITIONAL, 1)
            "+", "-", "*", "/", "%" -> count(FeatureName.ARITHMETIC_OPERATORS, 1)
            "&", "|", "^" -> count(FeatureName.BITWISE_OPERATORS, 1)
            "+=", "-=", "*=", "/=", "%=" -> count(FeatureName.ASSIGNMENT_OPERATORS, 1)
            "?" -> count(FeatureName.TERNARY_OPERATOR, 1)
            "instanceof" -> count(FeatureName.INSTANCEOF, 1)
        }
        when (ctx.prefix?.text) {
            "++", "--" -> count(FeatureName.UNARY_OPERATORS, 1)
            "~" -> count(FeatureName.BITWISE_OPERATORS, 1)
        }
        when (ctx.postfix?.text) {
            "++", "--" -> count(FeatureName.UNARY_OPERATORS, 1)
        }
        if (ctx.text == "null") {
            count(FeatureName.NULL, 1)
        }
        if (ctx.bop == null) {
            if (ctx.text.contains("<<") || ctx.text.contains(">>")) {
                count(FeatureName.BITWISE_OPERATORS, 1)
            }
            if (ctx.expression().size != 0 && (ctx.text.contains("[") || ctx.text.contains("]"))) {
                count(FeatureName.ARRAY_ACCESS, 1)
            }
        }
        if (ctx.text.startsWith("(" + ctx.typeType()?.text + ")")) {
            count(FeatureName.CASTING, 1)
        }
        if (ctx.bop?.text == "==" || ctx.bop?.text == "!=") {
            // Check if both expressions are objects, i.e. references are being compared
            if (seenObjectIdentifiers.contains(ctx.expression(0).text) &&
                seenObjectIdentifiers.contains(ctx.expression(1).text)
            ) {
                count(FeatureName.REFERENCE_EQUALITY, 1)
            }
        }
        ctx.NEW()?.also {
            count(FeatureName.NEW_KEYWORD, 1)
        }
        ctx.primary()?.THIS()?.also {
            count(FeatureName.THIS, 1)
        }
        ctx.methodCall()?.SUPER()?.also {
            count(FeatureName.SUPER, 1)
        }
        ctx.creator()?.classCreatorRest()?.classBody()?.also {
            count(FeatureName.ANONYMOUS_CLASSES, 1)
        }
        ctx.lambdaExpression()?.also {
            count(FeatureName.LAMBDA_EXPRESSIONS, 1)
        }
        ctx.methodCall()?.also {
            if (featureStack[0].name.contains(ctx.methodCall()?.IDENTIFIER()?.text ?: "")) {
                if (ctx.methodCall().expressionList().text.filter { it == ',' }.length
                    == featureStack[0].name.filter { it == ',' }.length) {
                    count(FeatureName.RECURSION, 1)
                }
            }
        }
    }

    init {
        ParseTreeWalker.DEFAULT.walk(this, source.getParsed(filename).tree)
    }
}

class FeaturesResults(val source: Source, val results: Map<String, Map<String, UnitFeatures>>) {
    @Suppress("ReturnCount")
    fun lookup(path: String, filename: String = ""): FeatureValue {
        val components = path.split(".").toMutableList()

        if (source is Snippet) {
            require(filename == "") { "filename cannot be set for snippet lookups" }
        }
        val resultSource = results[filename] ?: error("results does not contain key $filename")

        val unitFeatures = resultSource[filename] ?: error("missing UnitFeatures")
        if (path == "") {
            return unitFeatures
        }

        var currentFeatures = if (source is Snippet) {
            val rootFeatures = unitFeatures.classes[""] as? FeatureValue ?: error("")
            if (path.isEmpty()) {
                return rootFeatures
            } else if (path == ".") {
                return rootFeatures.methods[""] as FeatureValue
            }
            if (unitFeatures.classes[components[0]] != null) {
                unitFeatures.classes[components.removeAt(0)]
            } else {
                rootFeatures
            }
        } else {
            unitFeatures.classes[components.removeAt(0)]
        } as FeatureValue

        for (component in components) {
            currentFeatures = currentFeatures.lookup(component)
        }
        return currentFeatures
    }
}

class FeaturesFailed(errors: List<SourceError>) : JeedError(errors) {
    override fun toString(): String {
        return "errors were encountered while performing feature analysis: ${errors.joinToString(separator = ",")}"
    }
}

@Throws(FeaturesFailed::class)
fun Source.features(names: Set<String> = sources.keys.toSet()): FeaturesResults {
    require(type == Source.FileType.JAVA) { "Can't perform feature analysis yet for Kotlin sources" }
    try {
        return FeaturesResults(
            this,
            sources.filter {
                names.contains(it.key)
            }.mapValues {
                FeatureListener(this, it).results
            }
        )
    } catch (e: JeedParsingException) {
        throw FeaturesFailed(e.errors)
    }
}
