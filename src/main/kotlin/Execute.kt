package edu.illinois.cs.cs125.janini

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

data class ExecutionResult(
        val succeeded: Boolean
)

fun CompilationResult.execute(): ExecutionResult {
    check(succeeded)
    assert(scriptEvaluator != null || classLoader != null)
    runBlocking {
        withTimeout(1000L) {
            if (task.snippet) {
                assert(scriptEvaluator != null)
                scriptEvaluator?.evaluate(arrayOf<Object>())
            } else {

            }
        }
    }
    return ExecutionResult(true)
}
