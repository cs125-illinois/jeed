import edu.illinois.cs.cs125.jeed.*

import io.kotlintest.specs.StringSpec
import io.kotlintest.*
import io.kotlintest.matchers.collections.shouldHaveAtLeastSize
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
class AnotherTest { }
int i = 0;
i++;""".trim())
  }
    "should identify all parse errors in broken snippets" {
        var exception = shouldThrow<SnippetParseErrors> {
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

        exception = shouldThrow<SnippetParseErrors> {
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
    "f:should reconstruct original sources" {
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
})

fun haveParseErrorOnLine(line: Int) = object : Matcher<SnippetParseErrors> {
    override fun test(value: SnippetParseErrors): Result {
        return Result(value.errors.any { it.location.line == line },
                "should have parse error on line $line",
                "should not have parse error on line $line")
    }
}

