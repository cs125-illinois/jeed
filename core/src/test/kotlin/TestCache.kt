package edu.illinois.cs.cs125.jeed.core

import io.kotlintest.matchers.numerics.shouldBeGreaterThan
import io.kotlintest.matchers.numerics.shouldBeLessThan
import io.kotlintest.matchers.types.shouldBeTypeOf
import io.kotlintest.should
import io.kotlintest.shouldBe
import io.kotlintest.shouldNot
import io.kotlintest.shouldNotBe
import io.kotlintest.specs.StringSpec
import java.util.PropertyPermission

class TestCache : StringSpec({
    "should cache compiled simple snippets" {
        val first = Source.fromSnippet(
            "int weirdName = 8;"
        ).compile(CompilationArguments(useCache = true))
        val second = Source.fromSnippet(
            "int weirdName = 8;"
        ).compile(CompilationArguments(useCache = true))

        first.cached shouldBe false
        second.cached shouldBe true
        second.interval.length shouldBeLessThan first.interval.length
        first.compiled shouldBe second.compiled
    }
    "should calculate size for file managers" {
        val snippet = Source.fromSnippet(
            "int weirdName = 8;"
        ).compile(CompilationArguments(useCache = true))

        snippet.fileManager.size shouldBeGreaterThan 0
    }
    "cache should return different classloaders and file managers for identical code" {
        val first = Source.fromSnippet(
            "int weirdName = 9;"
        ).compile(CompilationArguments(useCache = true))
        val second = Source.fromSnippet(
            "int weirdName = 9;"
        ).compile(CompilationArguments(useCache = true))

        first.cached shouldBe false
        second.cached shouldBe true
        first.classLoader shouldNotBe second.classLoader
    }
    "should cache compiled sources" {
        val first = Source(
            mapOf(
                "Weird.java" to "public class Weird {}",
                "Name.java" to "public class Name {}"
            )
        ).compile(CompilationArguments(useCache = true))
        val second = Source(
            mapOf(
                "Name.java" to "public class Name {}",
                "Weird.java" to "public class Weird {}"
            )
        ).compile(CompilationArguments(useCache = true))

        first.cached shouldBe false
        second.cached shouldBe true
        second.interval.length shouldBeLessThan first.interval.length
        first.compiled shouldBe second.compiled
    }
    "should not cache compiled sources when told not to" {
        val first = Source(
            mapOf(
                "Testee.java" to "public class Testee {}",
                "Meee.java" to "public class Meee {}"
            )
        ).compile(CompilationArguments(useCache = true))
        val second = Source(
            mapOf(
                "Meee.java" to "public class Meee {}",
                "Testee.java" to "public class Testee {}"
            )
        ).compile(CompilationArguments(useCache = false))

        first.cached shouldBe false
        second.cached shouldBe false
        first.compiled shouldNotBe second.compiled
    }
    "static initializers should still work while cached" {
        val source = Source(
            mapOf(
                "Main.java" to """
public class Main {
    private static int times = 0;
    static {
        times = 1;
    }
    public static void main() {
        times++;
        System.out.println(times);
    }
}""".trim()
            )
        )
        val first = source.compile(CompilationArguments(useCache = true))
        val second = source.compile(CompilationArguments(useCache = true))

        first.cached shouldBe false
        second.cached shouldBe true
        second.interval.length shouldBeLessThan first.interval.length
        first.compiled shouldBe second.compiled

        val firstResult = first.execute()
        firstResult should haveCompleted()
        firstResult should haveOutput("2")

        val secondResult = second.execute()
        secondResult should haveOutput("2")
    }
    "should cache compiled simple kotlin snippets" {
        val first = Source.fromSnippet(
            "val weirdKame = 8", SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).kompile(KompilationArguments(useCache = true))
        val second = Source.fromSnippet(
            "val weirdKame = 8", SnippetArguments(fileType = Source.FileType.KOTLIN)
        ).kompile(KompilationArguments(useCache = true))

        first.cached shouldBe false
        second.cached shouldBe true
        second.interval.length shouldBeLessThan first.interval.length
        first.compiled shouldBe second.compiled
    }
    "should cache compiled kotlin sources" {
        val first = Source(
            mapOf(
                "Weird.kt" to "class Weird {}",
                "Name.kt" to "data class Name(val name: String)"
            )
        ).kompile(KompilationArguments(useCache = true))
        val second = Source(
            mapOf(
                "Name.kt" to "data class Name(val name: String)",
                "Weird.kt" to "class Weird {}"
            )
        ).kompile(KompilationArguments(useCache = true))

        first.cached shouldBe false
        second.cached shouldBe true
        second.interval.length shouldBeLessThan first.interval.length
        first.compiled shouldBe second.compiled
    }
    "should not cache compiled kotlin sources when told not to" {
        val first = Source(
            mapOf(
                "Testee.kt" to "class Testee {}",
                "Meee.kt" to "data class Meee(val whee: Int)"
            )
        ).kompile(KompilationArguments(useCache = true))
        val second = Source(
            mapOf(
                "Meee.kt" to "data class Meee(val whee: Int)",
                "Testee.kt" to "class Testee {}"
            )
        ).kompile(KompilationArguments(useCache = false))

        first.cached shouldBe false
        second.cached shouldBe false
        first.compiled shouldNotBe second.compiled
    }
    "permission requests should work in the presence of caching" {
        val source = Source.fromSnippet(
            """
System.out.println(System.getProperty("file.separator"));
        """.trim()
        )

        val compiled1 = source.compile()
        val compiled2 = source.compile()

        compiled1.execute(
            SourceExecutionArguments(
                permissions = setOf(PropertyPermission("*", "read"))
            )
        )
        val executionResult2 = compiled2.execute(
            SourceExecutionArguments(
                permissions = setOf(PropertyPermission("*", "read"))
            )
        )

        executionResult2.threw?.printStackTrace()
        executionResult2 should haveCompleted()
        executionResult2.permissionDenied shouldBe false
    }
    "bytecode rewriting should work in the presence of caching" {
        val source = Source.fromSnippet(
            """
try {
    System.out.println("Try");
    Object o = null;
    o.toString();
} catch (NullPointerException e) {
    System.out.println("Catch");
} finally {
    System.out.println("Finally");
}
            """.trim()
        )
        source.compile() // .execute(SourceExecutionArguments(dryRun = true))

        val executionResult = source.compile(
            CompilationArguments(useCache = true)
        ).execute(
            SourceExecutionArguments(
                classLoaderConfiguration = Sandbox.ClassLoaderConfiguration(
                    unsafeExceptions = setOf(
                        "java.lang.NullPointerException"
                    )
                )
            )
        )

        println(executionResult.output)
        println(executionResult.threw?.printStackTrace())
        executionResult shouldNot haveCompleted()
        executionResult shouldNot haveTimedOut()
        executionResult should haveOutput("Try")
        executionResult.threw.shouldBeTypeOf<NullPointerException>()
    }
})
