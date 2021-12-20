package edu.illinois.cs.cs125.jeed.core

import org.jacoco.core.analysis.Analyzer
import org.jacoco.core.analysis.CoverageBuilder
import org.jacoco.core.data.ExecutionDataStore
import org.jacoco.core.data.SessionInfoStore
import org.jacoco.core.instr.Instrumenter
import org.jacoco.core.runtime.AbstractRuntime
import org.jacoco.core.runtime.RuntimeData
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.lang.Exception
import java.lang.reflect.InvocationTargetException

@Throws(ExecutionFailed::class)
@Suppress("ReturnCount")
suspend fun CompiledSource.jacoco(
    executionArguments: SourceExecutionArguments = SourceExecutionArguments()
): Pair<Sandbox.TaskResults<out Any?>, CoverageBuilder> {
    val actualArguments = updateExecutionArguments(executionArguments)
    check(!actualArguments.dryRun) { "Dry run not supported for Jacoco" }

    val runtime = EmptyRuntime()
    val instrumenter = Instrumenter(runtime)

    val originalByteMap = mutableMapOf<String, ByteArray>()
    val safeClassLoader = Sandbox.SandboxedClassLoader(
        classLoader, executionArguments.classLoaderConfiguration
    ).apply {
        transform { bytes, name ->
            originalByteMap[name] = bytes
            instrumenter.instrument(bytes, name)
        }
    }

    val data = RuntimeData()
    runtime.startup(data)
    val executionData = ExecutionDataStore()

    val taskResults = Sandbox.safeExecute(safeClassLoader, actualArguments) {
        try {
            val method = safeClassLoader
                .loadClass(executionArguments.methodToRun!!.declaringClass.name)
                .getMethod(executionArguments.methodToRun!!.name)
            if (method.parameterTypes.isEmpty()) {
                method.invoke(null)
            } else {
                method.invoke(null, null)
            }
        } catch (e: InvocationTargetException) {
            throw(e.cause ?: e)
        }
    }

    val coverageBuilder = CoverageBuilder()
    data.collect(executionData, SessionInfoStore(), false)
    runtime.shutdown()
    Analyzer(executionData, coverageBuilder).apply {
        for ((name, bytes) in originalByteMap) {
            try {
                analyzeClass(bytes, name)
            } catch (err: Exception) {
                println(err.cause)
            }
        }
    }
    return Pair(taskResults, coverageBuilder)
}

class EmptyRuntime : AbstractRuntime() {
    private val key = Integer.toHexString(hashCode())

    @Suppress("SpellCheckingInspection")
    override fun generateDataAccessor(classid: Long, classname: String, probecount: Int, mv: MethodVisitor): Int {
        mv.visitLdcInsn(key)
        // Stack[0]: Ljava/lang/String;

        mv.visitMethodInsn(
            Opcodes.INVOKESTATIC,
            classNameToPath(EmptyRuntime::class.java.name),
            "get",
            "(Ljava/lang/String;)Ljava/lang/Object;",
            false
        )
        // Stack[0]: Ljava/lang/Object;

        RuntimeData.generateAccessCall(classid, classname, probecount, mv)
        // Stack[0]: [Z

        return STACK_SIZE
    }

    override fun startup(data: RuntimeData) {
        super.startup(data)
        put(key, data)
    }

    override fun shutdown() {
        remove(key)
        return
    }

    companion object {
        private val map = mutableMapOf<String, RuntimeData>()

        fun put(name: String, data: RuntimeData) {
            map[name] = data
        }

        @JvmStatic
        fun get(name: String) = map[name]!! as Any

        fun remove(name: String) = map.remove(name)

        const val STACK_SIZE = 6
    }
}
