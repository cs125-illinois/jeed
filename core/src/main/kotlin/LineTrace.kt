package edu.illinois.cs.cs125.jeed.core

import com.squareup.moshi.JsonClass
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import kotlin.reflect.jvm.javaMethod

object LineTrace : SandboxPlugin<LineTraceResult> {
    private const val TRACING_STACK_ITEMS = 4
    private val tracingClassName = classNameToPath(TracingSink::class.java.name)
    private val tracingLineMethodName = TracingSink::enterLine.javaMethod?.name ?: error("missing tracing method name")
    private val tracingLineMethodDesc = Type.getMethodDescriptor(TracingSink::enterLine.javaMethod)

    private val threadSteps: ThreadLocal<MutableList<LineTraceResult.LineStep>> = ThreadLocal()
    private val threadIndex: ThreadLocal<Int> = ThreadLocal()
    private val threadLastMethodId: ThreadLocal<Int?> = ThreadLocal()
    private val threadLastLabelSequence: ThreadLocal<Int?> = ThreadLocal()
    private val initializedThreadLocals = ThreadLocal.withInitial { false }
    private fun ensureThreadLocalsInitialized() {
        if (initializedThreadLocals.get()) return
        val data: LineTraceWorkingData = Sandbox.confinedTaskWorkingData(this)
        synchronized(data.threadTrackingSyncRoot) {
            val steps = mutableListOf<LineTraceResult.LineStep>()
            threadSteps.set(steps)
            data.threadSteps[data.nextThreadIndex] = steps
            threadIndex.set(data.nextThreadIndex)
            data.nextThreadIndex++
        }
        threadLastMethodId.set(null)
        threadLastLabelSequence.set(null)
        initializedThreadLocals.set(true)
    }

    override fun createInstrumentationData(): Any {
        return LineTraceInstrumentationData()
    }

    override fun createInitialData(instrumentationData: Any?): Any {
        return LineTraceWorkingData()
    }

    override fun createFinalData(instrumentationData: Any?, workingData: Any?): LineTraceResult {
        workingData as LineTraceWorkingData
        val allSteps = mutableListOf<LineTraceResult.LineStep>()
        synchronized(workingData.threadTrackingSyncRoot) {
            workingData.threadSteps.values.forEach { allSteps.addAll(it) }
        }
        return LineTraceResult(allSteps)
    }

    override val requiredClasses: Set<Class<*>>
        get() = setOf(TracingSink::class.java)

    object TracingSink {
        @JvmStatic
        fun enterLine(file: String, methodId: Int, sequence: Int, line: Int) {
            ensureThreadLocalsInitialized()
            val steps = threadSteps.get()
            val isDuplicate = steps.lastOrNull()?.let { previous ->
                previous.line == line &&
                    threadLastMethodId.get() == methodId &&
                    threadLastLabelSequence.get() == sequence - 1
            } ?: false
            threadLastMethodId.set(methodId)
            threadLastLabelSequence.set(sequence)
            if (!isDuplicate) {
                steps.add(LineTraceResult.LineStep(file, line, threadIndex.get()))
            }
        }
    }

    override fun transformBeforeSandbox(bytecode: ByteArray, instrumentationData: Any?, context: RewritingContext): ByteArray {
        if (context != RewritingContext.UNTRUSTED) return bytecode
        val classReader = ClassReader(bytecode)
        val preinspectingClassVisitor = PreinspectingClassVisitor()
        classReader.accept(preinspectingClassVisitor, 0)
        val preinspection = preinspectingClassVisitor.getPreinspection()
        if (preinspection.sourceFile == null) return bytecode
        val classWriter = ClassWriter(classReader, 0)
        val rewritingVisitor = TracingClassVisitor(classWriter, preinspection, instrumentationData as LineTraceInstrumentationData)
        classReader.accept(rewritingVisitor, 0)
        return classWriter.toByteArray()
    }

    private data class ClassPreinspection(
        val sourceFile: String?,
        val methodPreinspections: Map<MethodId, Map<Int, LabelLine>>
    )
    private data class MethodId(val name: String, val descriptor: String)
    private data class LabelInfo(val index: Int, var line: Int?, var waitForFrame: Boolean)
    private data class LabelLine(val line: Int, val labelSequence: Int, val waitForFrame: Boolean)

    private class LineTraceInstrumentationData(
        var nextMethodId: Int = 0
    )

    private class LineTraceWorkingData(
        val threadSteps: MutableMap<Int, MutableList<LineTraceResult.LineStep>> = mutableMapOf(),
        var nextThreadIndex: Int = 0,
        val threadTrackingSyncRoot: Any = Object()
    )

    private class PreinspectingClassVisitor : ClassVisitor(Opcodes.ASM9) {
        private var sourceFile: String? = null
        private val inspectedMethods = mutableMapOf<MethodId, Map<Int, LabelLine>>()

        override fun visitSource(source: String?, debug: String?) {
            sourceFile = source
        }

        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor {
            val labelLines = mutableMapOf<Int, LabelLine>()
            inspectedMethods[MethodId(name, descriptor)] = labelLines
            return PreinspectingMethodVisitor(labelLines)
        }

        private class PreinspectingMethodVisitor(
            val labelLines: MutableMap<Int, LabelLine>
        ) : MethodVisitor(Opcodes.ASM9) {
            private val knownLabels = mutableMapOf<Label, LabelInfo>()
            private var mightNeedFrame: LabelInfo? = null
            override fun visitLabel(label: Label) {
                val labelInfo = LabelInfo(knownLabels.size, null, false)
                knownLabels[label] = labelInfo
                mightNeedFrame = labelInfo
            }
            override fun visitLineNumber(line: Int, start: Label) {
                val info = knownLabels[start] ?: error("must have visited the line label")
                info.line = line
            }
            override fun visitFrame(
                type: Int,
                numLocal: Int,
                local: Array<out Any>?,
                numStack: Int,
                stack: Array<out Any>?
            ) {
                mightNeedFrame?.let { it.waitForFrame = true } // If it's still pending, no insn was visited
                mightNeedFrame = null
            }
            override fun visitEnd() {
                var lineLabelSequence = 0
                knownLabels.values.sortedBy { it.index }.forEach { info ->
                    info.line?.let {
                        lineLabelSequence++
                        labelLines[info.index] = LabelLine(it, lineLabelSequence, info.waitForFrame)
                    }
                }
            }
            override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) {
                mightNeedFrame = null
            }
            override fun visitIincInsn(`var`: Int, increment: Int) {
                mightNeedFrame = null
            }
            override fun visitInsn(opcode: Int) {
                mightNeedFrame = null
            }
            override fun visitIntInsn(opcode: Int, operand: Int) {
                mightNeedFrame = null
            }
            override fun visitInvokeDynamicInsn(
                name: String?,
                descriptor: String?,
                bootstrapMethodHandle: Handle?,
                vararg bootstrapMethodArguments: Any?
            ) {
                mightNeedFrame = null
            }
            override fun visitJumpInsn(opcode: Int, label: Label?) {
                mightNeedFrame = null
            }
            override fun visitLdcInsn(value: Any?) {
                mightNeedFrame = null
            }
            override fun visitLookupSwitchInsn(dflt: Label?, keys: IntArray?, labels: Array<out Label>?) {
                mightNeedFrame = null
            }
            override fun visitMethodInsn(
                opcode: Int,
                owner: String?,
                name: String?,
                descriptor: String?,
                isInterface: Boolean
            ) {
                mightNeedFrame = null
            }
            override fun visitMultiANewArrayInsn(descriptor: String?, numDimensions: Int) {
                mightNeedFrame = null
            }
            override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label?, vararg labels: Label?) {
                mightNeedFrame = null
            }
            override fun visitTypeInsn(opcode: Int, type: String?) {
                mightNeedFrame = null
            }
            override fun visitVarInsn(opcode: Int, `var`: Int) {
                mightNeedFrame = null
            }
        }

        fun getPreinspection(): ClassPreinspection {
            return ClassPreinspection(sourceFile, inspectedMethods)
        }
    }

    private class TracingClassVisitor(
        visitor: ClassVisitor,
        private val preinspection: ClassPreinspection,
        private val instrumentationData: LineTraceInstrumentationData
    ) : ClassVisitor(Opcodes.ASM9, visitor) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor {
            val methodId = instrumentationData.nextMethodId
            instrumentationData.nextMethodId++
            return TracingMethodVisitor(
                super.visitMethod(access, name, descriptor, signature, exceptions),
                preinspection.sourceFile ?: error("should only trace a class with a source file"),
                preinspection.methodPreinspections[MethodId(name, descriptor)] ?: error("missing preinspection"),
                methodId
            )
        }
    }

    private class TracingMethodVisitor(
        visitor: MethodVisitor,
        private val sourceFile: String,
        private val labelLines: Map<Int, LabelLine>,
        private val methodId: Int
    ) : MethodVisitor(Opcodes.ASM9, visitor) {
        private var currentLabelIndex = 0
        private var waitingForFrameLabel: LabelLine? = null

        override fun visitLabel(label: Label?) {
            super.visitLabel(label)
            labelLines[currentLabelIndex]?.let { labelInfo ->
                if (labelInfo.waitForFrame) {
                    waitingForFrameLabel = labelInfo
                } else {
                    addTraceCall(labelInfo)
                }
            }
            currentLabelIndex++
        }

        override fun visitFrame(
            type: Int,
            numLocal: Int,
            local: Array<out Any>?,
            numStack: Int,
            stack: Array<out Any>?
        ) {
            super.visitFrame(type, numLocal, local, numStack, stack)
            waitingForFrameLabel?.let {
                addTraceCall(it)
            }
            waitingForFrameLabel = null
        }

        override fun visitMaxs(maxStack: Int, maxLocals: Int) {
            super.visitMaxs(maxStack + TRACING_STACK_ITEMS, maxLocals)
        }

        private fun addTraceCall(label: LabelLine) {
            visitLdcInsn(sourceFile)
            visitLdcInsn(methodId)
            visitLdcInsn(label.labelSequence)
            visitLdcInsn(label.line)
            visitMethodInsn(
                Opcodes.INVOKESTATIC,
                tracingClassName,
                tracingLineMethodName,
                tracingLineMethodDesc,
                false
            )
        }
    }
}

@JsonClass(generateAdapter = true)
data class LineTraceResult(val steps: List<LineStep>) {
    @JsonClass(generateAdapter = true)
    data class LineStep(val source: String, val line: Int, val threadIndex: Int)

    fun remap(source: Source): LineTraceResult {
        val remappedSteps = steps.mapNotNull { step ->
            source.sources[step.source]?.let { code ->
                // The Kotlin compiler adds fake line number entries past the end of the source e.g. for inlined code.
                // That can be checked for first because the sources map always has the full compilable code.
                var lines = code.count { it == '\n' }
                if (!code.endsWith("\n")) {
                    // On Windows, there are line separators, not line terminators
                    lines += 1
                }
                if (step.line > lines) return@mapNotNull null
            }
            val originalLocation = SourceLocation(step.source, step.line, COLUMN_UNKNOWN)
            val mappedLocation = try {
                source.mapLocation(originalLocation)
            } catch (_: SourceMappingException) {
                // HACK: This exception is thrown if this line wasn't derived from the snippet and so can't be mapped.
                // Ideally mapLocation could return null, but it may be too late to change that.
                return@mapNotNull null
            }
            step.copy(source = mappedLocation.source, line = mappedLocation.line)
        }
        return copy(steps = remappedSteps)
    }

    companion object {
        private const val COLUMN_UNKNOWN = -1
    }
}
