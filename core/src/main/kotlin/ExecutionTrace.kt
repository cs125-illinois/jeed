package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FrameNode
import org.objectweb.asm.tree.IincInsnNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.InvokeDynamicInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.TableSwitchInsnNode
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
import java.lang.reflect.Array as ReflectArray

object ExecutionTrace : SandboxPluginWithDefaultArguments<ExecutionTraceArguments, ExecutionTraceResults> {
    private val RETURN_OPCODES =
        setOf(Opcodes.RETURN, Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN, Opcodes.DRETURN, Opcodes.ARETURN)
    private val STORE_OPCODES =
        setOf(Opcodes.ISTORE, Opcodes.LSTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.ASTORE)
    private val NEVER_FALLTHROUGH_OPCODES =
        RETURN_OPCODES.union(setOf(Opcodes.GOTO, Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH, Opcodes.ATHROW))
    private val UNAMBIGUOUS_ARRAYSTORE_OPCODES =
        mapOf(
            Opcodes.IASTORE to Type.INT_TYPE,
            Opcodes.LASTORE to Type.LONG_TYPE,
            Opcodes.FASTORE to Type.FLOAT_TYPE,
            Opcodes.DASTORE to Type.DOUBLE_TYPE,
            Opcodes.AASTORE to Type.getType(Object::class.java),
            Opcodes.CASTORE to Type.CHAR_TYPE,
            Opcodes.SASTORE to Type.SHORT_TYPE
        )
    private val PRIMITIVE_TYPES =
        mapOf(
            Type.BOOLEAN_TYPE to ExecutionTraceResults.ValueType.BOOLEAN,
            Type.BYTE_TYPE to ExecutionTraceResults.ValueType.BYTE,
            Type.CHAR_TYPE to ExecutionTraceResults.ValueType.CHAR,
            Type.DOUBLE_TYPE to ExecutionTraceResults.ValueType.DOUBLE,
            Type.FLOAT_TYPE to ExecutionTraceResults.ValueType.FLOAT,
            Type.INT_TYPE to ExecutionTraceResults.ValueType.INT,
            Type.LONG_TYPE to ExecutionTraceResults.ValueType.LONG,
            Type.SHORT_TYPE to ExecutionTraceResults.ValueType.SHORT
        )

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

    // TODO: Instrument method calls to check for in-place modifications of e.g. arrays
    private fun instrumentMethod(data: ExecutionTraceInstrumentationData, className: String, method: MethodNode) {
        if (method.instructions.size() == 0) return // Can't instrument an abstract method
        if (method.name == "<init>") {
            // TODO: Constructors are tricky because "this" can't be used immediately
            // ...and because the chain constructor call can't be inside an exception handler
            return
        }
        val methodStart = method.instructions.first { it is LabelNode } as? LabelNode ?: return // No locals
        val methodIndex = data.nextUniqueId()
        val methodDesc = Type.getType(method.desc)
        val methodKey = "m$methodIndex"
        val localVariables = method.localVariables ?: listOf()
        val passableArguments = localVariables.filter {
            it.start == methodStart && (it.index != 0 || method.name != "<init>")
        }.sortedBy {
            it.index
        }.map {
            ExecutionTraceInstrumentationData.LocalInfo(it.index, it.name, Type.getType(it.desc))
        }
        val methodInfo = ExecutionTraceInstrumentationData.MethodInfo(
            methodIndex,
            className,
            method.name,
            methodDesc,
            passableArguments,
            method.access.and(Opcodes.ACC_STATIC) == 0 && method.name != "<init>"
        )
        data.instrumentedMethods[methodKey] = methodInfo
        val liveLocalIndexes = mutableMapOf<Int, ExecutionTraceInstrumentationData.LocalInfo>()
        val reachableLabels = findReachableLabels(method)
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
            } else if (insn.opcode in STORE_OPCODES || insn is IincInsnNode) {
                val localIndex = when (insn) {
                    is VarInsnNode -> insn.`var`
                    is IincInsnNode -> insn.`var`
                    else -> error("unknown instruction type")
                }
                liveLocalIndexes[localIndex]?.let { local ->
                    val varTracing = InsnList()
                    varTracing.add(VarInsnNode(local.type.getOpcode(Opcodes.ILOAD), localIndex))
                    val setVariableHandle = TracingSupport::bootstrapSetVariable.asAsmHandle()
                    val setVariableDesc = Type.getMethodDescriptor(Type.VOID_TYPE, local.type)
                    varTracing.add(InvokeDynamicInsnNode(local.name, setVariableDesc, setVariableHandle))
                    method.instructions.insert(insn, varTracing)
                }
            } else if (insn.opcode in UNAMBIGUOUS_ARRAYSTORE_OPCODES) {
                val elementType = UNAMBIGUOUS_ARRAYSTORE_OPCODES[insn.opcode]!!
                val arrayType = elementType.toArrayType()
                val setArrayHandle = TracingSupport::bootstrapSetArray.asAsmHandle()
                val setArrayDesc = Type.getMethodDescriptor(Type.VOID_TYPE, arrayType, Type.INT_TYPE, elementType)
                val replacement = InvokeDynamicInsnNode("arraystore", setArrayDesc, setArrayHandle)
                method.instructions.set(insn, replacement)
            } else if (insn.opcode == Opcodes.BASTORE) {
                // bastore can be used on either byte[] or boolean[], so there is no equivalent descriptor
                method.instructions.set(insn, TracingSupport::bastore.asAsmMethodInsn())
            } else if (insn is LabelNode) {
                localVariables.filter { it.end == insn }.forEach {
                    liveLocalIndexes.remove(it.index)
                }
                localVariables.filter {
                    it.start == insn && it.name != "this"
                }.forEach {
                    liveLocalIndexes[it.index] = ExecutionTraceInstrumentationData.LocalInfo(
                        it.index,
                        it.name,
                        Type.getType(it.desc)
                    )
                }
                if (insn in reachableLabels) {
                    val currentLocals = liveLocalIndexes.values.toList()
                    val siteKey = "c${data.nextUniqueId()}"
                    data.scopeSites[siteKey] = ExecutionTraceInstrumentationData.ScopeSite(currentLocals)
                    val scopeTracing = InsnList()
                    currentLocals.forEach {
                        scopeTracing.add(VarInsnNode(it.type.getOpcode(Opcodes.ILOAD), it.localIndex))
                    }
                    val newScopeHandle = TracingSupport::bootstrapNewScope.asAsmHandle()
                    val newScopeDesc = Type.getMethodDescriptor(Type.VOID_TYPE, *currentLocals.map { it.type }.toTypedArray())
                    scopeTracing.add(InvokeDynamicInsnNode(siteKey, newScopeDesc, newScopeHandle))
                    method.instructions.insert(insn.skipToBeforeRealInsn(), scopeTracing)
                }
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

    private fun findReachableLabels(method: MethodNode): Set<LabelNode> {
        val reachable = mutableSetOf<LabelNode>()
        method.instructions.forEach { insn ->
            when (insn) {
                is JumpInsnNode -> reachable.add(insn.label)
                is LookupSwitchInsnNode -> reachable.addAll(insn.labels)
                is TableSwitchInsnNode -> reachable.addAll(insn.labels)
                is LabelNode -> {
                    val previous = insn.previousRealInsn()
                    if (previous == null || previous.opcode !in NEVER_FALLTHROUGH_OPCODES) {
                        reachable.add(insn)
                    }
                }
            }
        }
        return reachable
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
        private val newScopeHandle = lookup.unreflect(TracingSupport::newScope.javaMethod)
        private val setVariableHandle = lookup.unreflect(TracingSupport::setVariable.javaMethod)
        private val recordSetArrayHandle = lookup.unreflect(TracingSupport::recordSetArray.javaMethod)

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
            val frame = ExecutionTraceWorkingData.Frame(methodInfo, receiver?.let { lookupOrTrackObject(receiver) })
            val passedArgumentInfo = methodInfo.passableArguments.zip(passableArguments).filter { (iai, _) ->
                !methodInfo.local0IsReceiver || iai.localIndex > 0
            }.map { (iai, value) ->
                val serializableValue = serializeValue(value, iai.type)
                frame.locals[iai.name] = serializableValue
                ExecutionTraceResults.PassedArgument(iai.name, serializableValue)
            }
            data.steps.add(ExecutionStep.EnterMethod(methodInfo.asPublishableInfo(), receiver, passedArgumentInfo))
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
            val serializableValue = serializeValue(returnValue, methodInfo.type.returnType)
            data.steps.add(ExecutionStep.ExitMethodNormally(serializableValue))
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
            data.steps.add(ExecutionStep.ExitMethodExceptionally(findObjectId(throwable)))
        }

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun bootstrapNewScope(caller: MethodHandles.Lookup, siteKey: String, callSignature: MethodType): CallSite {
            val handle = newScopeHandle
                .bindTo(siteKey)
                .asCollector(Array<Any?>::class.java, callSignature.parameterCount())
                .asType(callSignature)
            return ConstantCallSite(handle)
        }

        @JvmStatic
        private fun newScope(siteKey: String, localValues: Array<Any?>) {
            val data = threadData.get()
            if (data.atCapacity()) return
            val siteInfo = data.instrumentationData.scopeSites[siteKey] ?: error("unknown scope site")
            val thisFrame = data.callStack.peek()
            val destroyedLocals = mutableListOf<String>()
            val existingLocals = siteInfo.localsInScope.map { it.name }.toSet()
            val oldLocals = thisFrame.locals.keys.toSet()
            oldLocals.minus(existingLocals).forEach {
                destroyedLocals.add(it)
                thisFrame.locals.remove(it)
            }
            val createdLocals = mutableMapOf<String, ExecutionTraceResults.Value>()
            siteInfo.localsInScope.zip(localValues).filter { (local, _) ->
                local.name !in oldLocals
            }.forEach { (local, value) ->
                val serializableValue = serializeValue(value, local.type)
                createdLocals[local.name] = serializableValue
                thisFrame.locals[local.name] = serializableValue
            }
            if (destroyedLocals.isNotEmpty() || createdLocals.isNotEmpty()) {
                data.steps.add(ExecutionStep.ChangeScope(createdLocals, destroyedLocals))
            }
        }

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun bootstrapSetVariable(caller: MethodHandles.Lookup, localName: String, callSignature: MethodType): CallSite {
            val localType = Type.getType(callSignature.parameterType(0))
            val handle = MethodHandles
                .insertArguments(setVariableHandle, 0, localName, localType)
                .asType(callSignature)
            return ConstantCallSite(handle)
        }

        @JvmStatic
        private fun setVariable(localName: String, localType: Type, newValue: Any?) {
            val data = threadData.get()
            if (data.atCapacity()) return
            val thisFrame = data.callStack.peek()
            val serializableValue = serializeValue(newValue, localType)
            thisFrame.locals[localName] = serializableValue
            data.steps.add(ExecutionStep.SetVariable(localName, serializableValue))
        }

        @JvmStatic
        @Suppress("UNUSED_PARAMETER")
        fun bootstrapSetArray(caller: MethodHandles.Lookup, name: String, callSignature: MethodType): CallSite {
            val arrayClass = callSignature.parameterType(0) ?: error("must have a first parameter")
            val elementType = arrayClass.componentType ?: error("first parameter should be an array")
            val logger = recordSetArrayHandle
                .bindTo(Type.getType(elementType))
                .asType(callSignature)
            val originalBehavior = MethodHandles.arrayElementSetter(arrayClass)
            val handle = MethodHandles.foldArguments(logger, originalBehavior)
            return ConstantCallSite(handle)
        }

        @JvmStatic
        fun bastore(array: Any?, index: Int, value: Int) {
            when (array) {
                is ByteArray -> {
                    val byteValue = value.toByte()
                    array[index] = byteValue
                    recordSetArray(Type.BYTE_TYPE, array, index, byteValue)
                }
                is BooleanArray -> {
                    val booleanValue = (value and 1) != 0
                    array[index] = booleanValue
                    recordSetArray(Type.BOOLEAN_TYPE, array, index, booleanValue)
                }
                null -> throw NullPointerException("cannot store to byte/boolean array because it is null")
                else -> error("bastore instructions only operate on byte[] or boolean[]")
            }
        }

        @JvmStatic
        private fun recordSetArray(elementType: Type, array: Any, index: Int, value: Any) {
            val data = threadData.get()
            if (data.atCapacity()) return
            // Only log changes to explicitly created arrays, not e.g. varargs
            val trackedObject = data.knownObjects[IdentityHolder(array)] ?: return
            val indexedComponents = trackedObject.indexedComponents ?: error("arrays must have indexed components")
            val serializedValue = serializeValue(value, elementType)
            indexedComponents[index] = serializedValue
            data.steps.add(ExecutionStep.SetIndexedComponent(trackedObject.id, index, serializedValue))
        }

        @JvmStatic
        private fun serializeValue(value: Any?, type: Type): ExecutionTraceResults.Value {
            return when (type.sort) {
                Type.OBJECT, Type.ARRAY -> {
                    val objId = value?.let { findObjectId(it) }
                    ExecutionTraceResults.Value(ExecutionTraceResults.ValueType.REFERENCE, objId)
                }
                Type.VOID -> {
                    ExecutionTraceResults.Value(ExecutionTraceResults.ValueType.VOID, null)
                }
                Type.METHOD -> {
                    error("instances of method types are impossible")
                }
                else -> {
                    val publishableType = PRIMITIVE_TYPES[type] ?: error("unknown type $type")
                    val box = value ?: error("primitives cannot be null")
                    ExecutionTraceResults.Value(publishableType, box)
                }
            }
        }

        @JvmStatic
        private fun findObjectId(obj: Any): Int {
            return lookupOrTrackObject(obj).id
        }

        @JvmStatic
        private fun lookupOrTrackObject(obj: Any): ExecutionTraceWorkingData.TrackedObject {
            val data = threadData.get()
            val holder = IdentityHolder(obj)
            if (holder !in data.knownObjects) {
                val tracking = trackObject(obj) // Adds to map, avoiding circularity during state gathering
                data.steps.add(ExecutionStep.ObtainObject(tracking.id, tracking.asPublishableState()))
            }
            return data.knownObjects[holder]!!
        }

        @JvmStatic
        private fun trackObject(obj: Any): ExecutionTraceWorkingData.TrackedObject {
            val data = threadData.get()
            val tracking = ExecutionTraceWorkingData.TrackedObject(data.nextUniqueId(), obj.javaClass)
            data.knownObjects[IdentityHolder(obj)] = tracking // Avoid circularity during state gathering
            gatherObjectState(obj, tracking)
            return tracking
        }

        @JvmStatic
        private fun gatherObjectState(obj: Any, state: ExecutionTraceWorkingData.TrackedObject) {
            // TODO: Untrusted class instances, boxes, common collections, etc.
            when {
                obj.javaClass.isPrimitive -> error("primitives are not objects")
                obj is String -> state.stringRepresentation = obj
                obj is Enum<*> -> state.stringRepresentation = obj.name
                obj.javaClass.isArray -> {
                    val length = ReflectArray.getLength(obj)
                    val asmElemType = Type.getType(obj.javaClass.componentType)
                    val elements = mutableListOf<ExecutionTraceResults.Value>()
                    state.indexedComponents = elements
                    (0 until length).mapTo(elements) { i ->
                        serializeValue(ReflectArray.get(obj, i), asmElemType)
                    }
                }
            }
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
    val instrumentedMethods: MutableMap<String, MethodInfo> = mutableMapOf(),
    val scopeSites: MutableMap<String, ScopeSite> = mutableMapOf()
) {
    private var nextIndex = 1

    fun nextUniqueId(): Int {
        return nextIndex.also { nextIndex += 1 }
    }

    class LocalInfo(val localIndex: Int, val name: String, val type: Type)

    class MethodInfo(
        val index: Int,
        val className: String,
        val name: String,
        val type: Type,
        val passableArguments: List<LocalInfo>,
        val local0IsReceiver: Boolean
    ) {
        fun asPublishableInfo(): ExecutionTraceResults.MethodInfo {
            val arguments = passableArguments
                .filter { !local0IsReceiver || it.localIndex > 0 }
                .map { it.type.toString() }
            return ExecutionTraceResults.MethodInfo(className, name, arguments)
        }
    }

    class ScopeSite(val localsInScope: List<LocalInfo>)
}

private class ExecutionTraceWorkingData(
    val instrumentationData: ExecutionTraceInstrumentationData,
    val steps: MutableList<ExecutionStep> = mutableListOf(),
    val callStack: Stack<Frame> = Stack(),
    val knownObjects: MutableMap<IdentityHolder, TrackedObject> = mutableMapOf()
) {
    private var nextObjectId = 1

    fun atCapacity(): Boolean {
        return steps.size >= instrumentationData.arguments.recordedStepLimit
    }

    fun nextUniqueId(): Int {
        return nextObjectId.also { nextObjectId++ }
    }

    class Frame(
        val method: ExecutionTraceInstrumentationData.MethodInfo,
        val receiver: TrackedObject?,
        val locals: MutableMap<String, ExecutionTraceResults.Value> = mutableMapOf()
    )

    class TrackedObject(
        val id: Int,
        val type: Class<*>,
        var stringRepresentation: String? = null,
        var primaryValue: ExecutionTraceResults.Value? = null,
        var namedComponents: MutableMap<String, ExecutionTraceResults.Value>? = null,
        var indexedComponents: MutableList<ExecutionTraceResults.Value>? = null,
        var unorderedRowsValue: Set<List<ExecutionTraceResults.Value>>? = null
    ) {
        fun asPublishableState(): ExecutionTraceResults.ObjectState {
            return ExecutionTraceResults.ObjectState(
                type.name,
                stringRepresentation,
                primaryValue,
                namedComponents?.toMap(),
                indexedComponents?.toList(),
                unorderedRowsValue
            )
        }
    }
}

@JsonClass(generateAdapter = true)
data class ExecutionTraceResults(
    val arguments: ExecutionTraceArguments,
    val steps: List<ExecutionStep>
) {
    @JsonClass(generateAdapter = true)
    data class MethodInfo(val className: String, val method: String, val argumentTypes: List<String>)

    /* ktlint-disable no-multi-spaces */
    enum class ValueType {
        REFERENCE, // value is an Integer object ID or null if null reference
        VOID,      // value is always null
        INT,       // otherwise value is a box of the primitive value
        SHORT,
        CHAR,
        BYTE,
        BOOLEAN,
        FLOAT,
        DOUBLE,
        LONG
    }
    /* ktlint-enable no-multi-spaces */

    @JsonClass(generateAdapter = true)
    data class Value(val type: ValueType, val value: Any?)

    @JsonClass(generateAdapter = true)
    data class PassedArgument(val argumentName: String, val value: Value)

    @JsonClass(generateAdapter = true)
    data class ObjectState(
        val type: String,
        val stringRepresentation: String?,
        val primaryValue: Value?,
        val namedComponents: Map<String, Value>?,
        val indexedComponents: List<Value>?,
        val unorderedRowsValue: Set<List<Value>>?
    )
}

sealed class ExecutionStep {
    // No change to state, coalesce with next step if LineTrace was installed after ExecutionTrace
    data class Line(val source: String, val line: Int) : ExecutionStep()

    // Push call stack, initialize locals with arguments
    data class EnterMethod(
        val method: ExecutionTraceResults.MethodInfo,
        val receiver: Any?,
        val arguments: List<ExecutionTraceResults.PassedArgument>
    ) : ExecutionStep()

    // Pop call stack
    data class ExitMethodNormally(val returnValue: ExecutionTraceResults.Value) : ExecutionStep()

    // Pop call stack
    data class ExitMethodExceptionally(val throwableObjectId: Int) : ExecutionStep()

    // Delete locals in deadLocals, create locals in newLocals
    data class ChangeScope(
        val newLocals: Map<String, ExecutionTraceResults.Value>,
        val deadLocals: List<String>
    ) : ExecutionStep()

    // Alter one existing local
    data class SetVariable(val local: String, val value: ExecutionTraceResults.Value) : ExecutionStep()

    // Create new object in object table, coalesce with next step
    data class ObtainObject(val id: Int, val obj: ExecutionTraceResults.ObjectState) : ExecutionStep()

    // Alter one indexed component in an object
    data class SetIndexedComponent(
        val objectId: Int,
        val component: Int,
        val value: ExecutionTraceResults.Value
    ) : ExecutionStep()
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

private fun KFunction<*>.asAsmMethodInsn(): MethodInsnNode {
    val javaMethod = this.javaMethod ?: error("must represent a JVM method")
    require(javaMethod.modifiers.and(Modifier.STATIC) != 0) { "must be a static method" }
    return MethodInsnNode(
        Opcodes.INVOKESTATIC,
        classNameToPath(javaMethod.declaringClass.name),
        javaMethod.name,
        Type.getMethodDescriptor(javaMethod),
        false
    )
}

private fun AbstractInsnNode.skipToBeforeRealInsn(): AbstractInsnNode {
    var currentInsn = this
    while ((currentInsn.next?.opcode ?: 0) < 0) {
        currentInsn = currentInsn.next
    }
    return currentInsn
}

private fun AbstractInsnNode.previousRealInsn(): AbstractInsnNode? {
    var currentInsn: AbstractInsnNode? = this.previous
    while (currentInsn != null && currentInsn.opcode < 0) {
        currentInsn = currentInsn.previous
    }
    return currentInsn
}

private fun Type.toArrayType(): Type {
    return Type.getType("[" + this.descriptor)
}
