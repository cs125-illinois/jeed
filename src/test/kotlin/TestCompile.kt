import edu.illinois.cs.cs125.jeed.*

import io.kotlintest.specs.StringSpec
import io.kotlintest.*

class TestCompile : StringSpec({
    "should compile simple snippets" {
        Source.fromSnippet("int i = 1;").compile() should haveCompiled()
    }
    "should not compile broken simple snippets" {
        Source.fromSnippet("int i = a;").compile() shouldNot haveCompiled()
    }
    "should compile snippets that include method definitions" {
        Source.fromSnippet("""
int i = 0;
private static int main() {
    return 0;
}""".trim()).compile() should haveCompiled()
    }
    "should compile snippets that include class definitions" {
        Source.fromSnippet("""
int i = 0;
public class Foo {
    int i;
}
Foo foo = new Foo();
""".trim()).compile() should haveCompiled()
    }
    "should compile multiple sources" {
        Source(mapOf(
                "Test" to "public class Test {}",
                "Me" to "public class Me {}"
        )).compile() should haveCompiled()
    }
    "should compile sources with dependencies" {
        Source(mapOf(
                "Test" to "public class Test {}",
                "Me" to "public class Me extends Test {}"
        )).compile() should haveCompiled()
    }
    "should compile sources with dependencies in wrong order" {
        Source(mapOf(
                "Test" to "public class Test extends Me {}",
                "Me" to "public class Me {}"
        )).compile() should haveCompiled()
    }
    "should compile sources in multiple packages" {
        Source(mapOf(
                "test/Test" to
"""
package test;
public class Test {}
""".trim(),
                "me/Me" to
"""
package me;
public class Me {}
""".trim()
        )).compile() should haveCompiled()
    }
    "should compile sources in multiple packages with dependencies in wrong order" {
        Source(mapOf(
                "test/Test" to
                        """
package test;
import me.Me;
public class Test extends Me {}
""".trim(),
                "me/Me" to
                        """
package me;
public class Me {}
""".trim()
        )).compile() should haveCompiled()
    }
    "should compile sources that use Java 10 features" {
        Source(mapOf(
                "Test" to
                        """
public class Test {
    public static void main() {
        var i = 0;
    }
}
""".trim()
        )).compile() should haveCompiled()
    }
})

fun haveCompiled() = object : Matcher<CompiledSource> {
    override fun test(value: CompiledSource): Result {
        return Result(
                value.succeeded,
                "Source should have compiled",
                "Source should not have compiled"
        )
    }
}
