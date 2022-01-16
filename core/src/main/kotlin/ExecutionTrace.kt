package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.invoke.CallSite
import java.lang.invoke.ConstantCallSite
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kotlin.reflect.jvm.javaMethod

object ExecutionTrace : SandboxPluginWithDefaultArguments<ExecutionTraceArguments, ExecutionTraceResults> {
    private val supportClassName = classNameToPath(TracingSupport::class.java.name)
    private val methodEnterBootstrapDesc = Type.getMethodDescriptor(TracingSupport::bootstrapEnterMethod.javaMethod)

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

    override fun transformBeforeSandbox(
        bytecode: ByteArray,
        name: String,
        instrumentationData: Any?,
        context: RewritingContext
    ): ByteArray {
        if (context != RewritingContext.UNTRUSTED) return bytecode
        instrumentationData as ExecutionTraceInstrumentationData
        val reader = ClassReader(bytecode)
        val classTree = ClassNode(Opcodes.ASM9)
        reader.accept(classTree, 0)
        classTree.methods.forEach {
            instrumentMethod(instrumentationData, classTree.name, it)
        }
        val writer = ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        classTree.accept(writer)
        return writer.toByteArray()
    }

    private fun instrumentMethod(data: ExecutionTraceInstrumentationData, className: String, method: MethodNode) {
        if (method.instructions.size() == 0) return // Can't instrument an abstract method
        val methodStart = method.instructions.first { it is LabelNode } as? LabelNode ?: return // No locals
        val methodIndex = data.nextMethodIndex
        data.nextMethodIndex++
        val methodKey = "m$methodIndex"
        val localVariables = method.localVariables ?: listOf()
        val passableArguments = localVariables.filter {
            it.start == methodStart && (it.index != 0 || method.name != "<init>")
        }.sortedBy {
            it.index
        }.map {
            ExecutionTraceInstrumentationData.ArgumentInfo(it.index, it.name, Type.getType(it.desc))
        }
        val methodInfo = ExecutionTraceInstrumentationData.MethodInfo(
            methodIndex,
            className,
            method.name,
            method.desc,
            passableArguments,
            method.access.and(Opcodes.ACC_STATIC) == 0 && method.name != "<init>"
        )
        data.instrumentedMethods[methodKey] = methodInfo
        val enterMethodCall = InsnList()
        passableArguments.forEach {
            enterMethodCall.add(VarInsnNode(it.type.getOpcode(Opcodes.ILOAD), it.localIndex))
        }
        val enterMethodHandle = Handle(
            Opcodes.H_INVOKESTATIC,
            supportClassName,
            "bootstrapEnterMethod",
            methodEnterBootstrapDesc,
            false
        )
        val enterMethodDesc = Type.getMethodDescriptor(Type.VOID_TYPE, *passableArguments.map { it.type }.toTypedArray())
        enterMethodCall.add(InvokeDynamicInsnNode(methodKey, enterMethodDesc, enterMethodHandle))
        method.instructions.insert(enterMethodCall)
    }

    override fun createInitialData(instrumentationData: Any?, executionArguments: Sandbox.ExecutionArguments): Any {
        require(executionArguments.maxExtraThreads == 0) { "ExecutionTrace does not support multiple threads" }
        return ExecutionTraceWorkingData(instrumentationData as ExecutionTraceInstrumentationData)
    }

    override fun executionStartedInSandbox() {
        val workingData: ExecutionTraceWorkingData = Sandbox.CurrentTask.getWorkingData(this)
        if (workingData.instrumentationData.hasLineTrace) {
            LineTrace.addLineCallback { source, line ->
                if (!workingData.atCapacity()) {
                    workingData.steps.add(ExecutionStep.Line(source, line))
                }
            }
        }
    }

    override fun createFinalData(workingData: Any?): ExecutionTraceResults {
        workingData as ExecutionTraceWorkingData
        return ExecutionTraceResults(workingData.instrumentationData.arguments, workingData.steps)
    }

    override val requiredClasses: Set<Class<*>>
        get() = setOf(TracingSupport::class.java)

    object TracingSupport {
        private val lookup = MethodHandles.lookup()
        private val enterMethodHandle = lookup.unreflect(TracingSupport::enterMethod.javaMethod)

        @JvmStatic
        @Suppress("UNUSED_PARAMETER") // The first MethodHandles.Lookup parameter is required by the JVM
        fun bootstrapEnterMethod(caller: MethodHandles.Lookup, methodKey: String, callSignature: MethodType): CallSite {
            val handle = enterMethodHandle
                .bindTo(methodKey)
                .asCollector(Array<Any>::class.java, callSignature.parameterCount())
                .asType(MethodType.methodType(Void.TYPE, callSignature.parameterArray()))
            return ConstantCallSite(handle)
        }

        @JvmStatic
        private fun enterMethod(methodKey: String, passableArguments: Array<Any?>) {
            val data = threadData.get()
            if (data.atCapacity()) return
            val methodInfo = data.instrumentationData.instrumentedMethods[methodKey] ?: error("uninstrumented method")
            val receiver = if (methodInfo.local0IsReceiver) passableArguments[0] else null
            val passedArgumentInfo = methodInfo.passableArguments.zip(passableArguments).filter { (iai, _) ->
                !methodInfo.local0IsReceiver || iai.localIndex > 0
            }.map { (iai, value) ->
                ExecutionTraceResults.PassedArgument(iai.name, value)
            }
            data.steps.add(ExecutionStep.EnterMethod(methodInfo.asPublishableInfo(), receiver, passedArgumentInfo))
        }
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
    val hasLineTrace: Boolean,
    var nextMethodIndex: Int = 1,
    val instrumentedMethods: MutableMap<String, MethodInfo> = mutableMapOf()
) {
    data class MethodInfo(
        val index: Int,
        val className: String,
        val name: String,
        val descriptor: String,
        val passableArguments: List<ArgumentInfo>,
        val local0IsReceiver: Boolean
    ) {
        fun asPublishableInfo(): ExecutionTraceResults.MethodInfo {
            val arguments = passableArguments
                .filter { !local0IsReceiver || it.localIndex > 0 }
                .map { it.type.toString() }
            return ExecutionTraceResults.MethodInfo(className, name, arguments)
        }
    }

    data class ArgumentInfo(val localIndex: Int, val name: String, val type: Type)
}

private class ExecutionTraceWorkingData(
    val instrumentationData: ExecutionTraceInstrumentationData,
    val steps: MutableList<ExecutionStep> = mutableListOf()
) {
    fun atCapacity(): Boolean {
        return steps.size >= instrumentationData.arguments.recordedStepLimit
    }
}

@JsonClass(generateAdapter = true)
data class ExecutionTraceResults(
    val arguments: ExecutionTraceArguments,
    val steps: List<ExecutionStep>
) {
    @JsonClass(generateAdapter = true)
    data class MethodInfo(val className: String, val method: String, val argumentTypes: List<String>)

    @JsonClass(generateAdapter = true)
    data class PassedArgument(val argumentName: String, val value: Any?)
}

@Suppress("unused") // stepClass is for serialization
enum class ExecutionStepType(val stepClass: Class<out ExecutionStep>) {
    LINE(ExecutionStep.Line::class.java),
    ENTER_METHOD(ExecutionStep.EnterMethod::class.java)
}

sealed class ExecutionStep(val type: ExecutionStepType) {
    data class Line(val source: String, val line: Int) : ExecutionStep(ExecutionStepType.LINE)
    data class EnterMethod(
        val method: ExecutionTraceResults.MethodInfo,
        val receiver: Any?,
        val arguments: List<ExecutionTraceResults.PassedArgument>
    ) : ExecutionStep(ExecutionStepType.ENTER_METHOD)
}
