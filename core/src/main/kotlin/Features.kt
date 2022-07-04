package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass

enum class FeatureName(val description: String) {
    LOCAL_VARIABLE_DECLARATIONS("local variable declarations"),
    VARIABLE_ASSIGNMENTS("variable assignments"),
    VARIABLE_REASSIGNMENTS("variable reassignments"),

    // Operators
    UNARY_OPERATORS("unary operators"),
    ARITHMETIC_OPERATORS("arithmetic operators"),
    BITWISE_OPERATORS("bitwise operators"),
    ASSIGNMENT_OPERATORS("assignment operators"),
    TERNARY_OPERATOR("ternary operators"),
    COMPARISON_OPERATORS("comparison operators"),
    LOGICAL_OPERATORS("logical operators"),

    // If & Else
    IF_STATEMENTS("if statements"),
    ELSE_STATEMENTS("else statements"),
    ELSE_IF("else-if blocks"),

    // Arrays
    ARRAYS("arrays"),
    ARRAY_ACCESS("array accesses"),
    ARRAY_LITERAL("array literal"),
    MULTIDIMENSIONAL_ARRAYS("multidimensional arrays"),

    // Loops
    FOR_LOOPS("for loops"),
    ENHANCED_FOR("enhanced for loops"),
    WHILE_LOOPS("while loops"),
    DO_WHILE_LOOPS("do-while loops"),
    BREAK("break"),
    CONTINUE("continue"),

    // Nesting
    NESTED_IF("nested if"),
    NESTED_FOR("nested for"),
    NESTED_WHILE("nested while"),
    NESTED_DO_WHILE("nested do-while"),
    NESTED_CLASS("nested class declaration"),

    // Methods
    METHOD("method declarations"),
    RETURN("method return"),
    CONSTRUCTOR("constructor declarations"),
    GETTER("getters"),
    SETTER("setters"),

    // Strings & null
    STRING("Strings"),
    NULL("null"),

    // Type handling
    CASTING("type casting"),
    TYPE_INFERENCE("type inference"),
    INSTANCEOF("instanceof"),

    // Class & Interface
    CLASS("class declarations"),
    IMPLEMENTS("interface implementations"),
    INTERFACE("interface declarations"),

    // Polymorphism
    EXTENDS("inheritance"),
    SUPER("super"),
    OVERRIDE("overrides"),

    // Exceptions
    TRY_BLOCK("try statement"),
    FINALLY("finally block"),
    ASSERT("assert"),
    THROW("throw"),
    THROWS("throws"),

    // Objects
    NEW_KEYWORD("new keyword"),
    THIS("this"),
    REFERENCE_EQUALITY("referential equality"),

    // Modifiers
    VISIBILITY_MODIFIERS("visibility modifiers"),
    STATIC_METHOD("static methods"),
    FINAL_METHOD("final methods"),
    ABSTRACT_METHOD("abstract methods"),
    STATIC_FIELD("static fields"),
    FINAL_FIELD("final fields"),
    ABSTRACT_FIELD("abstract fields"),
    FINAL_CLASS("final classes"),
    ABSTRACT_CLASS("abstract classes"),

    // Import
    IMPORT("import statements"),

    // Misc.
    ANONYMOUS_CLASSES("anonymous classes"),
    LAMBDA_EXPRESSIONS("lambda expressions"),
    GENERIC_CLASS("generic classes"),
    SWITCH("switch statements"),
    STREAM("streams"),
    ENUM("enums"),
    RECURSION("recursion"),
    COMPARABLE("Comparable interface"),
    RECORD("records"),
    BOXING_CLASSES("boxing classes"),
    TYPE_PARAMETERS("type parameters"),
    PRINT_STATEMENTS("print statements"),
    DOT_NOTATION("dot notation"),
    DOTTED_METHOD_CALL("dotted method call"),
    DOTTED_VARIABLE_ACCESS("dotted variable access"),

    // Kotlin only
    NESTED_METHOD("nested method"),
    JAVA_PRINT_STATEMENTS("java print statements")
}

@Suppress("unused")
val SUPPORTED_KOTLIN_FEATURES =
    setOf(
        FeatureName.LOCAL_VARIABLE_DECLARATIONS,
        FeatureName.VARIABLE_ASSIGNMENTS,
        FeatureName.VARIABLE_REASSIGNMENTS,
        FeatureName.FOR_LOOPS,
        FeatureName.WHILE_LOOPS,
        FeatureName.DO_WHILE_LOOPS,
        FeatureName.NESTED_FOR,
        FeatureName.NESTED_WHILE,
        FeatureName.NESTED_DO_WHILE,
        FeatureName.ARRAYS,
        FeatureName.IF_STATEMENTS,
        FeatureName.ELSE_IF,
        FeatureName.ELSE_STATEMENTS,
        FeatureName.NESTED_IF,
        FeatureName.NESTED_METHOD,
        FeatureName.DOTTED_VARIABLE_ACCESS,
        FeatureName.DOTTED_METHOD_CALL,
        FeatureName.DOT_NOTATION,
        FeatureName.PRINT_STATEMENTS,
        FeatureName.JAVA_PRINT_STATEMENTS,
        FeatureName.COMPARISON_OPERATORS,
        FeatureName.LOGICAL_OPERATORS,
        FeatureName.CLASS,
        FeatureName.METHOD
    )
val ALL_FEATURES = FeatureName.values().associate { it.name to it.description }

class FeatureMap(val map: MutableMap<FeatureName, Int> = mutableMapOf()) : MutableMap<FeatureName, Int> by map {
    override fun get(key: FeatureName): Int = map.getOrDefault(key, 0)
    override fun put(key: FeatureName, value: Int): Int? {
        val previous = map[key]
        if (value == 0) {
            map.remove(key)
        } else {
            map[key] = value
        }
        return previous
    }
}

@JsonClass(generateAdapter = true)
data class Features(
    var featureMap: FeatureMap = FeatureMap(),
    var importList: MutableSet<String> = mutableSetOf(),
    var typeList: MutableSet<String> = mutableSetOf(),
    var identifierList: MutableSet<String> = mutableSetOf(),
    var dottedMethodList: MutableSet<String> = mutableSetOf()
) {
    operator fun plus(other: Features): Features {
        val map = FeatureMap()
        for (key in FeatureName.values()) {
            map[key] = featureMap.getValue(key) + other.featureMap.getValue(key)
        }
        return Features(
            map,
            (importList + other.importList).toMutableSet(),
            (typeList + other.typeList).toMutableSet(),
            (identifierList + other.identifierList).toMutableSet(),
            (dottedMethodList + other.dottedMethodList).toMutableSet()
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
    features: Features = Features()
) : FeatureValue(name, range, methods, classes, features)

@JsonClass(generateAdapter = true)
class MethodFeatures(
    name: String,
    range: SourceRange,
    methods: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    classes: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    features: Features = Features()
) : FeatureValue(name, range, methods, classes, features)

@JsonClass(generateAdapter = true)
class UnitFeatures(
    name: String,
    range: SourceRange,
    methods: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    classes: MutableMap<String, LocatedClassOrMethod> = mutableMapOf(),
    features: Features = Features()
) : FeatureValue(name, range, methods, classes, features)

class FeaturesFailed(errors: List<SourceError>) : JeedError(errors) {
    override fun toString(): String {
        return "errors were encountered while performing feature analysis: ${errors.joinToString(separator = ",")}"
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

@Throws(FeaturesFailed::class)
fun Source.features(names: Set<String> = sources.keys.toSet()): FeaturesResults {
    @Suppress("SwallowedException")
    try {
        return FeaturesResults(
            this,
            sources.filter {
                names.contains(it.key)
            }.mapValues {
                when (type) {
                    Source.FileType.JAVA -> JavaFeatureListener(this, it).results
                    Source.FileType.KOTLIN -> KotlinFeatureListener(this, it).results
                }
            }
        )
    } catch (e: JeedParsingException) {
        throw FeaturesFailed(e.errors)
    }
}
