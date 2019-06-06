import edu.illinois.cs.cs125.jeed.*

import io.kotlintest.specs.StringSpec
import io.kotlintest.*

class TestCheckstyle : StringSpec({
    "it should check strings" {
        Source.fromSnippet("""
int i = 0;
""".trim()).checkstyle()
    }
})
