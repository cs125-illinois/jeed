import edu.illinois.cs.cs125.jeed.*

import io.kotlintest.specs.StringSpec
import io.kotlintest.*

class TestCheckstyle : StringSpec({
    "it should check strings without errors" {
        val checkstyleResult = Source.fromSnippet("""
int i = 0;
""".trim()).checkstyle()

        checkstyleResult shouldNot haveCheckstyleErrors()
    }
    "it should identify checkstyle errors in strings" {
        val checkstyleErrors = Source.fromSnippet("""
int i = 0;
int y =1;
""".trim()).checkstyle()

        checkstyleErrors should haveCheckstyleErrors()
        checkstyleErrors should haveCheckstyleErrorAt(line=2)
    }
    "it should identify checkstyle errors in snippet methods" {
        val checkstyleErrors = Source.fromSnippet("""
int i = 0;
int y = 1;
int add(int a, int b) {
    return a+ b;
}
add(i, y);
""".trim()).checkstyle()

        checkstyleErrors should haveCheckstyleErrors()
        checkstyleErrors should haveCheckstyleErrorAt(line=4)
    }
    "it should identify checkstyle errors in snippet static methods" {
        val checkstyleErrors = Source.fromSnippet("""
int i = 0;
int y = 1;
static int add(int a, int b) {
    return a+ b;
}
add(i, y);
""".trim()).checkstyle()

        checkstyleErrors should haveCheckstyleErrors()
        checkstyleErrors should haveCheckstyleErrorAt(line=4)
    }
    "it should identify checkstyle errors in snippet methods with modifiers" {
        val checkstyleErrors = Source.fromSnippet("""
int i = 0;
int y = 1;
public int add(int a, int b) {
    return a+ b;
}
add(i, y);
""".trim()).checkstyle()

        checkstyleErrors should haveCheckstyleErrors()
        checkstyleErrors should haveCheckstyleErrorAt(line=4)
    }
})

fun haveCheckstyleErrors() = object : Matcher<CheckstyleResults> {
    override fun test(value: CheckstyleResults): Result {
        return Result(value.errors.values.flatten().isNotEmpty(),
                "should have checkstyle errors",
                "should have checkstyle errors")
    }
}
fun haveCheckstyleErrorAt(source: String? = null, line: Int) = object : Matcher<CheckstyleResults> {
    override fun test(value: CheckstyleResults): Result {
        return Result(value.errors.values.flatten().any { it.location.source == source && it.location.line == line },
                "should have checkstyle error on line $line",
                "should not have checkstyle error on line $line")
    }
}
