package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TryCatchBlockNode
import org.objectweb.asm.tree.VarInsnNode
import java.lang.invoke.CallSite
import java.lang.invoke.ConstantCallSite
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Modifier
import java.util.Stack
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod

object ExecutionTrace : SandboxPluginWithDefaultArguments<ExecutionTraceArguments, ExecutionTraceResults> {
    private val RETURN_OPCODES =
        setOf(Opcodes.RETURN, Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN)

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
        if (method.name == "<init>") {
            // TODO: Constructors are tricky because "this" can't be used immediately
            // ...and because the chain constructor call can't be inside an exception handler
            return
        }
        val methodStart = method.instructions.first { it is LabelNode } as? LabelNode ?: return // No locals
        val methodIndex = data.nextMethodIndex
        data.nextMethodIndex++
        val methodDesc = Type.getType(method.desc)
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
        method.instructions.toList().forEach { insn ->
            if (insn.opcode in RETURN_OPCODES) {
                val returnTracing = InsnList()
                when (methodDesc.returnType.size) {
                    1 -> returnTracing.add(InsnNode(Opcodes.DUP))
                    2 -> returnTracing.add(InsnNode(Opcodes.DUP2))
                }
                val exitMethodNormallyHandle = TracingSupport::bootstrapExitMethodNormally.asAsmHandle()
                val exitMethodNormallyDesc = if (methodDesc.returnType.size == 0) {
                    Type.getMethodDescriptor(Type.VOID_TYPE)
                } else {
                    Type.getMethodDescriptor(Type.VOID_TYPE, methodDesc.returnType)
                }
                returnTracing.add(InvokeDynamicInsnNode(methodKey, exitMethodNormallyDesc, exitMethodNormallyHandle))
                method.instructions.insertBefore(insn, returnTracing)
            }
        }
        val methodPrologue = InsnList()
        passableArguments.forEach {
            methodPrologue.add(VarInsnNode(it.type.getOpcode(Opcodes.ILOAD), it.localIndex))
        }
        val enterMethodHandle = TracingSupport::bootstrapEnterMethod.asAsmHandle()
        val enterMethodDesc = Type.getMethodDescriptor(Type.VOID_TYPE, *passableArguments.map { it.type }.toTypedArray())
        methodPrologue.add(InvokeDynamicInsnNode(methodKey, enterMethodDesc, enterMethodHandle))
        val tryLabel = LabelNode()
        methodPrologue.add(tryLabel)
        method.instructions.insert(methodPrologue)
        val handlerLabel = LabelNode()
        method.instructions.add(handlerLabel)
        val onlyThrowableOnStack = arrayOf<Any>(classNameToPath(Throwable::class.java.name))
        method.instructions.add(FrameNode(Opcodes.F_FULL, 0, arrayOf(), 1, onlyThrowableOnStack))
        method.instructions.add(InsnNode(Opcodes.DUP))
        val exitMethodExceptionallyHandle = TracingSupport::bootstrapExitMethodExceptionally.asAsmHandle()
        val exitMethodExceptionallyDesc = Type.getMethodDescriptor(Type.VOID_TYPE, Type.getType(Throwable::class.java))
        method.instructions.add(InvokeDynamicInsnNode(methodKey, exitMethodExceptionallyDesc, exitMethodExceptionallyHandle))
        method.instructions.add(InsnNode(Opcodes.ATHROW))
        method.tryCatchBlocks.add(TryCatchBlockNode(tryLabel, handlerLabel, handlerLabel, null))
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
        private val exitMethodNormallyHandle = lookup.unreflect(TracingSupport::exitMethodNormally.javaMethod)
        private val exitMethodExceptionallyHandle = lookup.unreflect(TracingSupport::exitMethodExceptionally.javaMethod)

        @JvmStatic
        @Suppress("UNUSED_PARAMETER") // The first MethodHandles.Lookup parameter is required by the JVM
        fun bootstrapEnterMethod(caller: MethodHandles.Lookup, methodKey: String, callSignature: MethodType): CallSite {
            val handle = enterMethodHandle
                .bindTo(methodKey)
                .asCollector(Array<Any>::class.java, callSignature.parameterCount())
                .asType(callSignature)
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
            val frame = ExecutionTraceWorkingData.Frame(methodInfo)
            data.callStack.push(frame)
        }

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun bootstrapExitMethodNormally(
            caller: MethodHandles.Lookup,
            methodKey: String,
            callSignature: MethodType
        ): CallSite {
            val handle = when (callSignature.parameterCount()) {
                0 -> MethodHandles.insertArguments(exitMethodNormallyHandle, 0, methodKey, null)
                1 -> exitMethodNormallyHandle.bindTo(methodKey)
                else -> error("should only have one return value")
            }.asType(callSignature)
            return ConstantCallSite(handle)
        }

        @JvmStatic
        private fun exitMethodNormally(methodKey: String, returnValue: Any?) {
            val data = threadData.get()
            if (data.atCapacity()) return
            val methodInfo = data.instrumentationData.instrumentedMethods[methodKey] ?: error("uninstrumented method")
            val thisFrame = data.callStack.pop()
            if (thisFrame.method != methodInfo) error("mismatched enterMethod/exitMethodNormally")
            data.steps.add(ExecutionStep.ExitMethodNormally(returnValue))
        }

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun bootstrapExitMethodExceptionally(
            caller: MethodHandles.Lookup,
            methodKey: String,
            callSignature: MethodType
        ): CallSite {
            val handle = exitMethodExceptionallyHandle.bindTo(methodKey)
            return ConstantCallSite(handle)
        }

        @JvmStatic
        private fun exitMethodExceptionally(methodKey: String, throwable: Throwable) {
            val data = threadData.get()
            if (data.atCapacity()) return
            val methodInfo = data.instrumentationData.instrumentedMethods[methodKey] ?: error("uninstrumented method")
            val thisFrame = data.callStack.pop()
            if (thisFrame.method != methodInfo) error("mismatched enterMethod/exitMethodExceptionally")
            data.steps.add(ExecutionStep.ExitMethodExceptionally(throwable))
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
    val steps: MutableList<ExecutionStep> = mutableListOf(),
    val callStack: Stack<Frame> = Stack()
) {
    fun atCapacity(): Boolean {
        return steps.size >= instrumentationData.arguments.recordedStepLimit
    }

    class Frame(
        val method: ExecutionTraceInstrumentationData.MethodInfo
    )
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

sealed class ExecutionStep {
    data class Line(val source: String, val line: Int) : ExecutionStep()
    data class EnterMethod(
        val method: ExecutionTraceResults.MethodInfo,
        val receiver: Any?,
        val arguments: List<ExecutionTraceResults.PassedArgument>
    ) : ExecutionStep()
    data class ExitMethodNormally(val returnValue: Any?) : ExecutionStep()
    data class ExitMethodExceptionally(val throwable: Any) : ExecutionStep()
}

private fun KFunction<*>.asAsmHandle(): Handle {
    val javaMethod = this.javaMethod ?: error("must represent a JVM method")
    require(javaMethod.modifiers.and(Modifier.STATIC) != 0) { "must be a static method" }
    return Handle(
        Opcodes.H_INVOKESTATIC,
        classNameToPath(javaMethod.declaringClass.name),
        javaMethod.name,
        Type.getMethodDescriptor(javaMethod),
        false
    )
}
