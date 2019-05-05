import edu.illinois.cs.cs125.janini.*

import io.kotlintest.specs.StringSpec
import io.kotlintest.*

fun haveExecuted() = object : Matcher<ExecutionResult> {
    override fun test(value: ExecutionResult): Result {
        return Result(
                value.succeeded,
                "Code should have run",
                "Code should not have run"
        )
    }
}
class TestExecute : StringSpec({
    "should execute snippets" {
        Task("""System.out.println("Here");""").compile().execute() should haveExecuted()
    }
})
