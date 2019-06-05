import edu.illinois.cs.cs125.jeed.*

import io.kotlintest.specs.StringSpec
import io.kotlintest.*

class TestSandbox : StringSpec({
    "it should prevent snippets from exiting" {
        val executionResult = Source.fromSnippet("""
System.exit(-1);
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
    "it should prevent snippets from reading files" {
        val executionResult = Source.fromSnippet("""
import java.io.*;
System.out.println(new File("/").listFiles().length);
        """.trim()).compile().execute(ExecutionArguments(captureOutput = false))

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
})
