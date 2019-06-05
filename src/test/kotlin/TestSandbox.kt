import edu.illinois.cs.cs125.jeed.*

import io.kotlintest.specs.StringSpec
import io.kotlintest.*

class TestSandbox : StringSpec({
    "f:it should prevent snippets from exiting" {
        val executionResult = Source.fromSnippet("""
System.exit(-1);
        """.trim()).compile().execute()

        executionResult shouldNot haveCompleted()
        executionResult.permissionDenied shouldBe true
    }
})
