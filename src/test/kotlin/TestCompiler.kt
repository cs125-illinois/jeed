import io.kotlintest.specs.StringSpec
import io.kotlintest.*

import edu.illinois.cs.cs125.janini.Task
import edu.illinois.cs.cs125.janini.compile
import java.lang.IllegalStateException

fun haveCompiled() = object : Matcher<Task> {
    override fun test(value: Task): Result {
        return if (value.compiled == null) {
            Result(
                    false,
                    "Compilation should have been attempted",
                    ""
            )
        } else {
            Result(
                    value.compiled == true,
                    "Source should have compiled: ${value.compilerError}",
                    "Source should not have compiled"
            )
        }
    }
}
class TestCompiler : StringSpec({
    "should compile simple snippets" {
        compile(Task("int i = 1;")) should haveCompiled()
    }
    "should not compile broken simple snippets" {
        compile(Task("int i = 1")) shouldNot haveCompiled()
    }
    "should not recompile successful simple snippets" {
        shouldThrow<IllegalStateException> {
            compile(compile(Task("int i = 1;")))
        }
    }
    "should not recompile broken simple snippets" {
        shouldThrow<IllegalStateException> {
            compile(compile(Task("int i = 1")))
        }
    }
})
