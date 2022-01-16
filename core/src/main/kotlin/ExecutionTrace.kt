package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass

object ExecutionTrace : SandboxPluginWithDefaultArguments<ExecutionTraceArguments, ExecutionTraceResults> {
    private val threadData: ThreadLocal<ExecutionTraceWorkingData> = ThreadLocal.withInitial {
        Sandbox.CurrentTask.getWorkingData(this)
    }

    override fun createDefaultArguments(): ExecutionTraceArguments {
        return ExecutionTraceArguments()
    }

    override fun createInstrumentationData(
        arguments: ExecutionTraceArguments,
        classLoaderConfiguration: Sandbox.ClassLoaderConfiguration,
        allPlugins: List<ConfiguredSandboxPlugin<*, *>>
    ): Any {
        val hasLineTrace = allPlugins.any { it.plugin == LineTrace }
        return ExecutionTraceInstrumentationData(arguments, hasLineTrace)
    }

    override fun createInitialData(instrumentationData: Any?, executionArguments: Sandbox.ExecutionArguments): Any {
        require(executionArguments.maxExtraThreads == 0) { "ExecutionTrace does not support multiple threads" }
        return ExecutionTraceWorkingData(instrumentationData as ExecutionTraceInstrumentationData)
    }

    override fun executionStartedInSandbox() {
        val workingData: ExecutionTraceWorkingData = Sandbox.CurrentTask.getWorkingData(this)
        if (workingData.instrumentationData.hasLineTrace) {
            LineTrace.addLineCallback { source, line ->
                if (workingData.steps.size < workingData.instrumentationData.arguments.recordedStepLimit) {
                    workingData.steps.add(ExecutionStep.Line(source, line))
                }
            }
        }
    }

    override fun createFinalData(workingData: Any?): ExecutionTraceResults {
        workingData as ExecutionTraceWorkingData
        return ExecutionTraceResults(workingData.instrumentationData.arguments, workingData.steps)
    }
}

@JsonClass(generateAdapter = true)
data class ExecutionTraceArguments(
    val recordedStepLimit: Int = DEFAULT_RECORDED_STEP_LIMIT
) {
    companion object {
        const val DEFAULT_RECORDED_STEP_LIMIT = 5000
    }
}

private class ExecutionTraceInstrumentationData(
    val arguments: ExecutionTraceArguments,
    val hasLineTrace: Boolean
)

private class ExecutionTraceWorkingData(
    val instrumentationData: ExecutionTraceInstrumentationData,
    val steps: MutableList<ExecutionStep> = mutableListOf()
)

@JsonClass(generateAdapter = true)
data class ExecutionTraceResults(
    val arguments: ExecutionTraceArguments,
    val steps: List<ExecutionStep>
)

enum class ExecutionStepType(val stepClass: Class<out ExecutionStep>) {
    LINE(ExecutionStep.Line::class.java)
}

sealed class ExecutionStep(type: ExecutionStepType) {
    data class Line(val source: String, val line: Int) : ExecutionStep(ExecutionStepType.LINE)
}
