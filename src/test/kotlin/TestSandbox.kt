import edu.illinois.cs.cs125.jeed.*

import io.kotlintest.specs.StringSpec
import io.kotlintest.*

class TestSandbox : StringSpec({
    "it should prevent snippets from reading files" {
        val executionResult = Source.fromSnippet("""
import java.io.*;

File folder = new File(System.getProperty("user.dir"));
System.out.println(folder.listFiles().length > 0);
        """.trim()).compile().execute()

        executionResult should haveOutput("true")
    }
})
