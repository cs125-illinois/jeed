import edu.illinois.cs.cs125.jeed.*

import io.kotlintest.specs.StringSpec
import io.kotlintest.*
import io.kotlintest.matchers.collections.shouldHaveSize

class TestCheckstyle : StringSpec({
    "it should check strings without errors" {
        val checkstyleErrors = Source.fromSnippet("""
int i = 0;
""".trim()).checkstyle()

        checkstyleErrors shouldHaveSize 0
    }
    "it should identify checkstyle errors in strings" {
        val checkstyleErrors = Source.fromSnippet("""
int i = 0;
int y =1;
""".trim()).checkstyle()

        checkstyleErrors shouldHaveSize 1
        checkstyleErrors.filter { it.location.line == 2 } shouldHaveSize 1
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

        checkstyleErrors shouldHaveSize 1
        checkstyleErrors.filter { it.location.line == 4 } shouldHaveSize 1
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

        checkstyleErrors shouldHaveSize 1
        checkstyleErrors.filter { it.location.line == 4 } shouldHaveSize 1
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

        checkstyleErrors shouldHaveSize 1
        checkstyleErrors.filter { it.location.line == 4 } shouldHaveSize 1
    }
})
