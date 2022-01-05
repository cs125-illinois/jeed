package edu.illinois.cs.cs125.jeed.core

import org.jacoco.core.analysis.Analyzer
import org.jacoco.core.analysis.CoverageBuilder
import org.jacoco.core.data.ExecutionDataStore
import org.jacoco.core.data.SessionInfoStore
import org.jacoco.core.instr.Instrumenter
import org.jacoco.core.runtime.IRuntime
import org.jacoco.core.runtime.RuntimeData
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

object Jacoco : SandboxPlugin<Unit, CoverageBuilder> {
    private val instrumenter = Instrumenter(IsolatedJacocoRuntime)

    override fun createInstrumentationData(arguments: Unit): Any {
        return JacocoInstrumentationData()
    }

    override fun transformBeforeSandbox(
        bytecode: ByteArray,
        name: String,
        instrumentationData: Any?,
        context: RewritingContext
    ): ByteArray {
        if (context != RewritingContext.UNTRUSTED) return bytecode
        instrumentationData as JacocoInstrumentationData
        instrumentationData.coverageClasses[name] = bytecode
        return instrumenter.instrument(bytecode, name)
    }

    override val requiredClasses: Set<Class<*>>
        get() = setOf(IsolatedJacocoRuntime.RuntimeDataAccessor::class.java)

    override fun createInitialData(instrumentationData: Any?): Any {
        return JacocoWorkingData(instrumentationData as JacocoInstrumentationData)
    }

    override fun createFinalData(workingData: Any?): CoverageBuilder {
        workingData as JacocoWorkingData
        val executionData = ExecutionDataStore()
        workingData.runtimeData.collect(executionData, SessionInfoStore(), false)
        val coverageBuilder = CoverageBuilder()
        Analyzer(executionData, coverageBuilder).apply {
            try {
                for ((name, bytes) in workingData.instrumentationData.coverageClasses) {
                    analyzeClass(bytes, name)
                }
            } catch (_: Exception) {}
        }
        return coverageBuilder
    }
}

private class JacocoInstrumentationData(
    val coverageClasses: MutableMap<String, ByteArray> = mutableMapOf()
)

private class JacocoWorkingData(
    val instrumentationData: JacocoInstrumentationData,
    val runtimeData: RuntimeData = RuntimeData()
)

object IsolatedJacocoRuntime : IRuntime {
    private const val STACK_SIZE = 6

    override fun generateDataAccessor(classid: Long, classname: String?, probecount: Int, mv: MethodVisitor): Int {
        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            classNameToPath(RuntimeDataAccessor::class.java.name),
            "get",
            "()Ljava/lang/Object;",
            false
        )
        RuntimeData.generateAccessCall(classid, classname, probecount, mv)
        return STACK_SIZE
    }

    override fun startup(data: RuntimeData?) {
        // Nothing to do - the data is owned by the sandbox task
    }

    override fun shutdown() {
        // Nothing to do - the data is owned by the sandbox task
    }

    object RuntimeDataAccessor {
        @JvmStatic
        fun get(): Any {
            val workingData: JacocoWorkingData = Sandbox.CurrentTask.getWorkingData(Jacoco)
            return workingData.runtimeData
        }
    }
}

@Throws(ExecutionFailed::class)
suspend fun CompiledSource.jacoco(
    executionArguments: SourceExecutionArguments = SourceExecutionArguments()
): Pair<Sandbox.TaskResults<out Any?>, CoverageBuilder> {
    check(!executionArguments.dryRun) { "Dry run not supported for Jacoco" }
    val taskResults = execute(executionArguments.addPlugin(Jacoco))
    return Pair(taskResults, taskResults.pluginResult(Jacoco))
}
