// ktlint-disable filename
@file:Suppress("MatchingDeclarationName")

package edu.illinois.cs.cs125.jeed.core

import edu.illinois.cs.cs125.jeed.core.antlr.JavaParser
import edu.illinois.cs.cs125.jeed.core.antlr.JavaParserBaseListener
import org.antlr.v4.runtime.tree.ParseTreeWalker

@Suppress("TooManyFunctions", "LargeClass", "MagicNumber", "LongMethod", "ComplexMethod")
class JavaFeatureListener(val source: Source, entry: Map.Entry<String, String>) : JavaParserBaseListener() {
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
                declaration.classDeclaration()?.isSnippetClass() != true
            }.filter { declaration ->
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
        if (!ctx.isSnippetClass()) {
            count(FeatureName.CLASS)
        }
        enterClassOrInterface(
            ctx.identifier().text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
        count(
            FeatureName.STATIC_METHOD,
            ctx.classBody().classBodyDeclaration().filter { declaration ->
                declaration.memberDeclaration()?.methodDeclaration()?.isSnippetMethod() != true
            }.filter { declaration ->
                declaration.modifier().any {
                    it.classOrInterfaceModifier().STATIC() != null &&
                        declaration.memberDeclaration().methodDeclaration() != null
                }
            }.size
        )
        count(
            FeatureName.STATIC_FIELD,
            ctx.classBody().classBodyDeclaration().filter { declaration ->
                declaration.modifier().any {
                    it.classOrInterfaceModifier().STATIC() != null &&
                        declaration.memberDeclaration().fieldDeclaration() != null
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
            FeatureName.FINAL_FIELD,
            ctx.classBody().classBodyDeclaration().filter { declaration ->
                declaration.modifier().any {
                    it.classOrInterfaceModifier().FINAL() != null &&
                        declaration.memberDeclaration().fieldDeclaration() != null
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
            FeatureName.ABSTRACT_FIELD,
            ctx.classBody().classBodyDeclaration().filter { declaration ->
                declaration.modifier().any {
                    it.classOrInterfaceModifier().ABSTRACT() != null &&
                        declaration.memberDeclaration().fieldDeclaration() != null
                }
            }.size
        )
        count(
            FeatureName.VISIBILITY_MODIFIERS,
            ctx.classBody().classBodyDeclaration()
                .filter { it.memberDeclaration() != null }
                .filter { declaration ->
                    declaration.memberDeclaration().methodDeclaration()?.isSnippetMethod() != true
                }.filter { declaration ->
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
            FeatureName.NESTED_CLASS,
            ctx.classBody().classBodyDeclaration().filter { declaration ->
                declaration.memberDeclaration()?.classDeclaration() != null
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
            count(FeatureName.EXTENDS)
        }
        ctx.IMPLEMENTS()?.also {
            count(FeatureName.IMPLEMENTS)
        }
        ctx.typeParameters()?.also {
            count(FeatureName.GENERIC_CLASS)
        }
    }

    override fun exitClassDeclaration(ctx: JavaParser.ClassDeclarationContext) {
        exitClassOrInterface()
    }

    override fun enterInterfaceDeclaration(ctx: JavaParser.InterfaceDeclarationContext) {
        enterClassOrInterface(
            ctx.identifier().text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
        count(FeatureName.INTERFACE)
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
            ctx.identifier().text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
        count(FeatureName.ENUM)
    }

    override fun exitEnumDeclaration(ctx: JavaParser.EnumDeclarationContext) {
        exitClassOrInterface()
    }

    override fun enterRecordDeclaration(ctx: JavaParser.RecordDeclarationContext) {
        enterClassOrInterface(
            ctx.identifier().text,
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
        count(FeatureName.RECORD)
    }

    override fun exitRecordDeclaration(ctx: JavaParser.RecordDeclarationContext?) {
        exitClassOrInterface()
    }

    private fun JavaParser.MethodDeclarationContext.fullName(): String {
        val parameters = formalParameters().formalParameterList()?.formalParameter()?.joinToString(",") {
            it.typeType().text
        } ?: ""
        return "${identifier().text}($parameters)"
    }

    private fun JavaParser.ClassDeclarationContext.isSnippetClass() = source is Snippet &&
        identifier().text == source.wrappedClassName

    private fun JavaParser.MethodDeclarationContext.isSnippetMethod() = source is Snippet &&
        fullName() == source.looseCodeMethodName &&
        (featureStack.getOrNull(0) as? ClassFeatures)?.name == ""

    override fun enterMethodDeclaration(ctx: JavaParser.MethodDeclarationContext) {
        if (!ctx.isSnippetMethod()) {
            count(FeatureName.METHOD)
            if (ctx.identifier().text.startsWith("get")) {
                count(FeatureName.GETTER)
            }
            if (ctx.identifier().text.startsWith("set")) {
                count(FeatureName.SETTER)
            }
            ctx.THROWS()?.also {
                count(FeatureName.THROWS)
            }
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
        enterMethodOrConstructor(
            "${ctx.typeTypeOrVoid().text} ${ctx.fullName()}",
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
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
            "${ctx.typeTypeOrVoid().text} ${ctx.identifier().text}($parameters)",
            Location(ctx.start.line, ctx.start.charPositionInLine),
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
        count(FeatureName.METHOD)
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
            Location(ctx.stop.line, ctx.stop.charPositionInLine)
        )
        count(FeatureName.CONSTRUCTOR)
        ctx.THROWS()?.also {
            count(FeatureName.THROWS)
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
        val numBrackets = ctx.typeType().text.filter { it == '[' || it == ']' }.length
        when {
            numBrackets > 2 -> count(FeatureName.MULTIDIMENSIONAL_ARRAYS)
            numBrackets > 0 -> count(FeatureName.ARRAYS)
        }
        for (declarator in ctx.variableDeclarators().variableDeclarator()) {
            currentFeatures.features.identifierList.add(declarator.variableDeclaratorId().identifier().text)
        }
        // Check if variable is an object
        ctx.typeType().classOrInterfaceType()?.also {
            for (declarator in ctx.variableDeclarators().variableDeclarator()) {
                seenObjectIdentifiers += declarator.variableDeclaratorId().identifier().text
            }
        }
    }

    private val seenIfStarts = mutableSetOf<Int>()

    private val currentFeatureMap: MutableMap<FeatureName, Int>
        get() = currentFeatures.features.featureMap

    private fun count(feature: FeatureName, amount: Int = 1) {
        currentFeatureMap[feature] = (currentFeatureMap[feature] ?: 0) + amount
    }

    @Suppress("LongMethod", "ComplexMethod", "NestedBlockDepth")
    override fun enterStatement(ctx: JavaParser.StatementContext) {
        /*
        if (currentFeatures.features.skeleton.isEmpty()) {
            val skeleton = ctx.text
            for (i in skeleton.indices) {
                if (skeleton[i] == '{') {
                    currentFeatures.features.skeleton += "{ "
                } else if (skeleton[i] == '}') {
                    currentFeatures.features.skeleton += "} "
                }

                if (i + 5 < skeleton.length) {
                    if (skeleton.subSequence(i, i + 5) == "while") {
                        currentFeatures.features.skeleton += "while "
                    }
                }
                if (i + 4 < skeleton.length) {
                    if (skeleton.subSequence(i, i + 4) == "else") {
                        currentFeatures.features.skeleton += "else "
                    }
                }
                if (i + 3 < skeleton.length) {
                    if (skeleton.subSequence(i, i + 3) == "for") {
                        currentFeatures.features.skeleton += "for "
                    }
                }
                if (i + 2 < skeleton.length) {
                    when (skeleton.subSequence(i, i + 2)) {
                        "if" -> currentFeatures.features.skeleton += "if "
                        "do" -> currentFeatures.features.skeleton += "do "
                    }
                }
            }
            while (currentFeatures.features.skeleton.contains("{ } ")) {
                currentFeatures.features.skeleton = currentFeatures.features.skeleton.replace("{ } ", "")
            }
        }
         */
        ctx.statementExpression?.also {
            @Suppress("ComplexCondition")
            if (it.text.startsWith("System.out.println(") ||
                it.text.startsWith("System.out.print(") ||
                it.text.startsWith("System.err.println(") ||
                it.text.startsWith("System.err.print(")
            ) {
                count(FeatureName.PRINT_STATEMENTS)
                count(FeatureName.DOTTED_VARIABLE_ACCESS, -1)
                count(FeatureName.DOTTED_METHOD_CALL, -1)
                count(FeatureName.DOT_NOTATION, -2)
            }
            if (it.bop?.text == "=") {
                count(FeatureName.VARIABLE_REASSIGNMENTS)
            }
        }
        ctx.FOR()?.also {
            count(FeatureName.FOR_LOOPS)
            if (ctx.forControl().enhancedForControl() != null) {
                count(FeatureName.ENHANCED_FOR)
                count(FeatureName.LOCAL_VARIABLE_DECLARATIONS)
            }
        }
        ctx.WHILE()?.also {
            // Only increment whileLoopCount if it's not a do-while loop
            if (ctx.DO() != null) {
                count(FeatureName.DO_WHILE_LOOPS)
            } else {
                count(FeatureName.WHILE_LOOPS)
            }
        }
        ctx.IF()?.also {
            // Check for else-if chains
            val outerIfStart = ctx.start.startIndex
            if (outerIfStart !in seenIfStarts) {
                // Count if block
                count(FeatureName.IF_STATEMENTS)
                seenIfStarts += outerIfStart
                check(ctx.statement().isNotEmpty())

                if (ctx.statement().size == 2 && ctx.statement(1).block() != null) {
                    // Count else block
                    check(ctx.ELSE() != null)
                    count(FeatureName.ELSE_STATEMENTS)
                } else if (ctx.statement().size >= 2) {
                    var statement = ctx.statement(1)
                    while (statement != null) {
                        if (statement.IF() != null) {
                            // If statement contains an IF, it is part of a chain
                            seenIfStarts += statement.start.startIndex
                            count(FeatureName.ELSE_IF)
                        } else {
                            count(FeatureName.ELSE_STATEMENTS)
                        }
                        statement = statement.statement(1)
                    }
                }
            }
        }
        ctx.TRY()?.also {
            count(FeatureName.TRY_BLOCK)
            ctx.finallyBlock()?.also {
                count(FeatureName.FINALLY)
            }
        }
        ctx.ASSERT()?.also {
            count(FeatureName.ASSERT)
        }
        ctx.SWITCH()?.also {
            count(FeatureName.SWITCH)
        }
        ctx.THROW()?.also {
            count(FeatureName.THROW)
        }
        ctx.BREAK()?.also {
            count(FeatureName.BREAK)
        }
        ctx.CONTINUE()?.also {
            count(FeatureName.CONTINUE)
        }
        ctx.RETURN()?.also {
            count(FeatureName.RETURN)
        }
        // Count nested statements
        for (ctxStatement in ctx.statement()) {
            ctxStatement?.block()?.also {
                val statement = ctxStatement.block().blockStatement()
                for (block in statement) {
                    val blockStatement = block.statement()
                    blockStatement?.FOR()?.also {
                        count(FeatureName.NESTED_FOR)
                    }
                    blockStatement?.IF()?.also {
                        count(FeatureName.NESTED_IF)
                    }
                    blockStatement?.WHILE()?.also {
                        if (block.statement().DO() != null) {
                            count(FeatureName.NESTED_DO_WHILE)
                        } else {
                            count(FeatureName.NESTED_WHILE)
                        }
                    }
                }
            }
        }
    }

    override fun enterExpression(ctx: JavaParser.ExpressionContext) {
        when (ctx.bop?.text) {
            "<", ">", "<=", ">=", "==", "!=" -> count(FeatureName.COMPARISON_OPERATORS)
            "&&", "||" -> count(FeatureName.LOGICAL_OPERATORS)
            "+", "-", "*", "/", "%" -> count(FeatureName.ARITHMETIC_OPERATORS)
            "&", "|", "^" -> count(FeatureName.BITWISE_OPERATORS)
            "+=", "-=", "*=", "/=", "%=" -> {
                count(FeatureName.ASSIGNMENT_OPERATORS)
                count(FeatureName.VARIABLE_REASSIGNMENTS)
            }
            "?" -> count(FeatureName.TERNARY_OPERATOR)
            "instanceof" -> count(FeatureName.INSTANCEOF)
            "." -> {
                count(FeatureName.DOT_NOTATION)
                if (ctx.identifier() != null) {
                    if (ctx.identifier().text != "length") {
                        count(FeatureName.DOTTED_VARIABLE_ACCESS)
                    } else {
                        count(FeatureName.DOT_NOTATION, -1)
                    }
                }
                if (ctx.methodCall() != null) {
                    count(FeatureName.DOTTED_METHOD_CALL)
                    if (ctx.methodCall().identifier() != null) {
                        currentFeatures.features.dottedMethodList += ctx.methodCall().identifier().text
                    }
                }
            }
        }
        when (ctx.prefix?.text) {
            "++", "--" -> {
                count(FeatureName.UNARY_OPERATORS)
                count(FeatureName.VARIABLE_REASSIGNMENTS)
            }
            "~" -> count(FeatureName.BITWISE_OPERATORS)
            "!" -> count(FeatureName.LOGICAL_OPERATORS)
        }
        when (ctx.postfix?.text) {
            "++", "--" -> {
                count(FeatureName.UNARY_OPERATORS)
                count(FeatureName.VARIABLE_REASSIGNMENTS)
            }
        }
        if (ctx.text == "null") {
            count(FeatureName.NULL)
        }
        if (ctx.bop == null) {
            if (ctx.text.contains("<<") || ctx.text.contains(">>")) {
                count(FeatureName.BITWISE_OPERATORS)
            }
            if (ctx.expression().size != 0 && (ctx.text.contains("[") || ctx.text.contains("]"))) {
                count(FeatureName.ARRAY_ACCESS)
            }
        }
        if (ctx.text.startsWith("(" + ctx.typeType()?.text + ")")) {
            if (ctx.typeType()?.primitiveType() != null) {
                count(FeatureName.PRIMITIVE_CASTING)
            } else {
                count(FeatureName.CASTING)
            }
        }
        if (ctx.bop?.text == "==" || ctx.bop?.text == "!=") {
            // Check if both expressions are objects, i.e. references are being compared
            if (seenObjectIdentifiers.contains(ctx.expression(0).text) &&
                seenObjectIdentifiers.contains(ctx.expression(1).text)
            ) {
                count(FeatureName.REFERENCE_EQUALITY)
            }
        }
        ctx.NEW()?.also {
            if (ctx.creator()?.arrayCreatorRest() == null && ctx.creator()?.createdName()?.text != "String") {
                count(FeatureName.NEW_KEYWORD)
            }
        }
        ctx.primary()?.THIS()?.also {
            count(FeatureName.THIS)
        }
        ctx.methodCall()?.SUPER()?.also {
            count(FeatureName.SUPER)
        }
        ctx.creator()?.classCreatorRest()?.classBody()?.also {
            count(FeatureName.ANONYMOUS_CLASSES)
        }
        ctx.lambdaExpression()?.also {
            count(FeatureName.LAMBDA_EXPRESSIONS)
        }
        if (ctx.bop?.text != ".") {
            ctx.methodCall()?.also {
                val methodName = ctx.methodCall().identifier()
                if (methodName != null && featureStack[0].name.contains(methodName.text)) {
                    if (ctx.methodCall().expressionList()?.text?.filter { it == ',' }?.length
                        == featureStack[0].name.filter { it == ',' }.length
                    ) {
                        count(FeatureName.RECURSION)
                    }
                }
            }
        }
    }

    override fun enterTypeType(ctx: JavaParser.TypeTypeContext) {
        ctx.primitiveType()?.also {
            currentFeatures.features.typeList.add(it.text)
        }
        ctx.classOrInterfaceType()?.also {
            currentFeatures.features.typeList.add(it.text)
        }
        count(
            FeatureName.STRING,
            ctx.classOrInterfaceType()?.identifier()?.filter {
                it.text == "String"
            }?.size ?: 0
        )
        count(
            FeatureName.STREAM,
            ctx.classOrInterfaceType()?.identifier()?.filter {
                it.text == "Stream"
            }?.size ?: 0
        )
        count(
            FeatureName.COMPARABLE,
            ctx.classOrInterfaceType()?.identifier()?.filter {
                it.text == "Comparable"
            }?.size ?: 0
        )
        count(
            FeatureName.BOXING_CLASSES,
            ctx.classOrInterfaceType()?.identifier()?.filter {
                when (it.text) {
                    "Boolean", "Byte", "Character", "Float", "Integer", "Long", "Short", "Double" -> true
                    else -> false
                }
            }?.size ?: 0
        )
        count(
            FeatureName.TYPE_PARAMETERS,
            ctx.classOrInterfaceType()?.typeArguments()?.size ?: 0
        )
        if (ctx.text == "var" || ctx.text == "val") {
            count(FeatureName.TYPE_INFERENCE)
        }
    }

    init {
        val parsedSource = source.getParsed(filename)
        // println(parsedSource.tree.format(parsedSource.parser))
        ParseTreeWalker.DEFAULT.walk(this, parsedSource.tree)
    }
}
