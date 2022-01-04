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

object Jacoco : SandboxPlugin<CoverageBuilder> {
    private val instrumenter = Instrumenter(IsolatedJacocoRuntime)

    override fun createInstrumentationData(): Any {
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
        return RuntimeData()
    }

    override fun createFinalData(instrumentationData: Any?, workingData: Any?): CoverageBuilder {
        instrumentationData as JacocoInstrumentationData
        workingData as RuntimeData
        val executionData = ExecutionDataStore()
        workingData.collect(executionData, SessionInfoStore(), false)
        val coverageBuilder = CoverageBuilder()
        Analyzer(executionData, coverageBuilder).apply {
            try {
                for ((name, bytes) in instrumentationData.coverageClasses) {
                    analyzeClass(bytes, name)
                }
            } catch (_: Exception) {}
        }
        return coverageBuilder
    }

    private class JacocoInstrumentationData(
        val coverageClasses: MutableMap<String, ByteArray> = mutableMapOf()
    )
}

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
        fun get(): Any = Sandbox.confinedTaskWorkingData(Jacoco)
    }
}

@Throws(ExecutionFailed::class)
@Suppress("ReturnCount")
suspend fun CompiledSource.jacoco(
    executionArguments: SourceExecutionArguments = SourceExecutionArguments()
): Pair<Sandbox.TaskResults<out Any?>, CoverageBuilder> {
    check(!executionArguments.dryRun) { "Dry run not supported for Jacoco" }
    val taskResults = execute(executionArguments.addPlugin(Jacoco))
    return Pair(taskResults, taskResults.pluginResult(Jacoco))
}
