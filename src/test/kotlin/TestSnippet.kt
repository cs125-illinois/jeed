import edu.illinois.cs.cs125.jeed.*

import io.kotlintest.specs.StringSpec
import io.kotlintest.*
import io.kotlintest.matchers.collections.shouldHaveSize

class TestSnippet : StringSpec({
  "should parse snippets" {
      Source.fromSnippet("""
class Test {
    int me = 0;
    int anotherTest() {
        return 8;
    }
}
int testing() {
    int j = 0;
    return 10;
}
import java.util.List;
class AnotherTest { }
int i = 0;
i++;""".trim())
  }
    "should identify a parse errors in a broken snippet" {
        val exception = shouldThrow<SnippetParsingFailed> {
            Source.fromSnippet("""
class Test {
    int me = 0;
    int anotherTest() {
      return 8;
    }
}
int testing() {
    int j = 0;
    return 10;
}
int i = 0;
i++
""".trim())
        }
        exception.errors shouldHaveSize 1
        exception should haveParseErrorOnLine(13)

    }
    "should identify multiple parse errors in a broken snippet" {
        val exception = shouldThrow<SnippetParsingFailed> {
            Source.fromSnippet("""
class;
class Test {
    int me = 0;
    int anotherTest() {
      return 8;
    }
}
int testing() {
    int j = 0;
    return 10;
}
int i = 0;
i++
""".trim())
        }
        exception.errors shouldHaveSize 2
        exception should haveParseErrorOnLine(1)
        exception should haveParseErrorOnLine(14)
    }
    "should be able to reconstruct original sources using source map" {
        val snippet = """
int i = 0;
i++;
public class Test {}
int adder(int first, int second) {
    return first + second;
}
        """.trim()
        val source = Source.fromSnippet(snippet)

        source.originalSource shouldBe(snippet)
        source.rewrittenSource shouldNotBe(snippet)
        source.originalSourceFromMap() shouldBe(snippet)
    }
    "should not allow return statements in loose code" {
        shouldThrow<SnippetValidationFailed> {
            Source.fromSnippet("""
return;
        """.trim())
        }
    }
    "should not allow return statements in loose code even under if statements" {
        shouldThrow<SnippetValidationFailed> {
            Source.fromSnippet("""
int i = 0;
if (i > 2) {
    return;
}
        """.trim())
        }
    }
})

fun haveParseErrorOnLine(line: Int) = object : Matcher<SnippetParsingFailed> {
    override fun test(value: SnippetParsingFailed): Result {
        return Result(value.errors.any { it.location.line == line },
                "should have parse error on line $line",
                "should not have parse error on line $line")
    }
}
