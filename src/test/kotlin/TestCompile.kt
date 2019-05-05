import io.kotlintest.specs.StringSpec
import io.kotlintest.*

import edu.illinois.cs.cs125.janini.*

fun haveCompiled() = object : Matcher<CompiledSource> {
    override fun test(value: CompiledSource): Result {
        return Result(
                value.succeeded,
                "Source should have compiled: ${value.error}",
                "Source should not have compiled"
        )
    }
}
class TestCompile : StringSpec({
    "should compile simple snippets" {
        Source("int i = 1;").compile() should haveCompiled()
    }
    "should not compile broken simple snippets" {
        Source("int i = 1").compile() shouldNot haveCompiled()
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
""",
                "me/Me" to
"""
package me;
public class Me {}
"""
        )).compile() should haveCompiled()
    }
    "should compile sources in multiple packages with dependencies in wrong order" {
        Source(mapOf(
                "test/Test" to
                        """
package test;
import me.Me;
public class Test extends Me {}
""",
                "me/Me" to
                        """
package me;
public class Me {}
"""
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
"""
        )).compile() should haveCompiled()
    }
})
