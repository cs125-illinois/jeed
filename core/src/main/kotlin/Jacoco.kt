package edu.illinois.cs.cs125.jeed.core

import org.jacoco.core.analysis.Analyzer
import org.jacoco.core.analysis.CoverageBuilder
import org.jacoco.core.data.ExecutionDataStore
import org.jacoco.core.data.SessionInfoStore
import org.jacoco.core.instr.Instrumenter
import org.jacoco.core.runtime.LoggerRuntime
import org.jacoco.core.runtime.RuntimeData
import java.lang.Exception
import java.lang.reflect.InvocationTargetException

@Throws(ExecutionFailed::class)
@Suppress("ReturnCount")
suspend fun CompiledSource.jacoco(
    executionArguments: SourceExecutionArguments = SourceExecutionArguments()
): Pair<Sandbox.TaskResults<out Any?>, CoverageBuilder> {
    val actualArguments = updateExecutionArguments(executionArguments)
    check(!actualArguments.dryRun) { "Dry run not supported for Jacoco" }

    val runtime = LoggerRuntime()
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
    // Have to do this to avoid hitting a method handle restriction in the sandbox
    safeClassLoader.findClassMethod(actualArguments.klass!!, actualArguments.method)

    val data = RuntimeData()
    runtime.startup(data)
    val executionData = ExecutionDataStore()

    val taskResults = Sandbox.safeExecute(safeClassLoader, actualArguments) {
        try {
            val method = safeClassLoader.findClassMethod(actualArguments.klass!!, actualArguments.method!!)
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

/*
class Jacocoerr(private val originalLoader: Sandbox.SandboxedClassLoader) {
    private val runtime = LoggerRuntime()
    private val instrumenter = Instrumenter(runtime)
    private var completed = false

    fun instrument(bytes: ByteArray, name: String): ByteArray = instrumenter.instrument(bytes, name)

    fun <T> runWithCoverage(method: () -> T): Pair<T, CoverageBuilder> {
        check(!completed)

        val data = RuntimeData()
        runtime.startup(data)

        val result = method()

        val executionData = ExecutionDataStore()
        data.collect(executionData, SessionInfoStore(), false)
        runtime.shutdown()
        val coverageBuilder = CoverageBuilder()
        Analyzer(executionData, coverageBuilder).apply {
            for ((name, bytes) in originalLoader.knownClasses) {
                analyzeClass(bytes, name)
            }
        }
        completed = true
        return Pair(result, coverageBuilder)
    }
}

class Jacocoer(private val originalLoader: Sandbox.SandboxedClassLoader) {
    private val classLoader: MemoryClassLoader
    private val runtime = LoggerRuntime()
    private var completed = false

    init {
        val instrumenter = Instrumenter(runtime)
        classLoader = MemoryClassLoader(
            originalLoader.parent,
            originalLoader.knownClasses.mapValues { (name, bytes) ->
                instrumenter.instrument(bytes, name)
            }
        )
    }

    fun <T> runWithCoverage(method: (modifierLoader: ClassLoader) -> T): Pair<T, CoverageBuilder> {
        check(!completed)

        val data = RuntimeData()
        runtime.startup(data)

        val result = method(classLoader)

        val executionData = ExecutionDataStore()
        data.collect(executionData, SessionInfoStore(), false)
        runtime.shutdown()
        val coverageBuilder = CoverageBuilder()
        Analyzer(executionData, coverageBuilder).apply {
            for ((name, bytes) in originalLoader.knownClasses) {
                analyzeClass(bytes, name)
            }
        }
        completed = true
        return Pair(result, coverageBuilder)
    }
}

class MemoryClassLoader(
    parent: ClassLoader,
    val bytecodeForClasses: Map<String, ByteArray>
) : Sandbox.SandboxableClassLoader {
    override val definedClasses: Set<String> get() = bytecodeForClasses.keys.toSet()
    override var providedClasses: MutableSet<String> = mutableSetOf()
    override var loadedClasses: MutableSet<String> = mutableSetOf()

    override fun findClass(name: String): Class<*> {
        bytecodeForClasses[name]?.let {
            loadedClasses += name
            providedClasses += name
            return defineClass(name, it, 0, it.size)
        } ?: throw ClassNotFoundException(name)
    }

    override fun loadClass(name: String): Class<*> {
        val klass = super.loadClass(name)
        loadedClasses += name
        return klass
    }
}
*/
