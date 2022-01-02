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

@Suppress("UNCHECKED_CAST")
object LineTrace : SandboxPlugin<LineTraceResult> {
    private const val TRACING_STACK_ITEMS = 2
    private val tracingClassName = classNameToPath(TracingSink::class.java.name)
    private val tracingLineMethodName = TracingSink::enterLine.javaMethod?.name ?: error("missing tracing method name")
    private val tracingLineMethodDesc = Type.getMethodDescriptor(TracingSink::enterLine.javaMethod)

    override fun createInitialData(): Any {
        return mutableListOf<LineTraceResult.LineStep>()
    }

    override fun createFinalData(workingData: Any?): LineTraceResult {
        return LineTraceResult(workingData as List<LineTraceResult.LineStep>)
    }

    override val requiredClasses: Set<Class<*>>
        get() = setOf(TracingSink::class.java)

    object TracingSink {
        @JvmStatic
        fun enterLine(file: String, line: Int) {
            val data: MutableList<LineTraceResult.LineStep> = Sandbox.confinedTaskWorkingData(LineTrace)
            data.add(LineTraceResult.LineStep(file, line))
        }
    }

    override fun transformBeforeSandbox(bytecode: ByteArray, context: RewritingContext): ByteArray {
        if (context != RewritingContext.UNTRUSTED) return bytecode
        val classReader = ClassReader(bytecode)
        val preinspectingClassVisitor = PreinspectingClassVisitor()
        classReader.accept(preinspectingClassVisitor, 0)
        val preinspection = preinspectingClassVisitor.getPreinspection()
        if (preinspection.sourceFile == null) return bytecode
        val classWriter = ClassWriter(classReader, 0)
        val rewritingVisitor = TracingClassVisitor(classWriter, preinspection)
        classReader.accept(rewritingVisitor, 0)
        return classWriter.toByteArray()
    }

    private data class ClassPreinspection(
        val sourceFile: String?,
        val methodPreinspections: Map<MethodId, Map<Int, LabelInfo>>
    )
    private data class MethodId(val name: String, val descriptor: String)
    private data class LabelInfo(val line: Int, var waitForFrame: Boolean)

    private class PreinspectingClassVisitor : ClassVisitor(Opcodes.ASM9) {
        private var sourceFile: String? = null
        private val inspectedMethods = mutableMapOf<MethodId, Map<Int, LabelInfo>>()

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
            val labelLines = mutableMapOf<Int, LabelInfo>()
            inspectedMethods[MethodId(name, descriptor)] = labelLines
            return PreinspectingMethodVisitor(labelLines)
        }

        private class PreinspectingMethodVisitor(
            val labelLines: MutableMap<Int, LabelInfo>
        ) : MethodVisitor(Opcodes.ASM9) {
            private val labelIndexes = mutableMapOf<Label, Int>()
            private val needsFrame = mutableMapOf<Label, Boolean>()
            private var mightNeedFrame: Label? = null
            override fun visitLabel(label: Label) {
                labelIndexes[label] = labelIndexes.size
                needsFrame[label] = false
                mightNeedFrame = label
            }
            override fun visitLineNumber(line: Int, start: Label) {
                val index = labelIndexes[start] ?: error("must have visited the line label")
                labelLines[index] = LabelInfo(line, false)
            }
            override fun visitFrame(
                type: Int,
                numLocal: Int,
                local: Array<out Any>?,
                numStack: Int,
                stack: Array<out Any>?
            ) {
                mightNeedFrame?.let { needsFrame[it] = true } // If it's still pending, no insn was visited
                mightNeedFrame = null
            }
            override fun visitEnd() {
                needsFrame.forEach { (label, wait) ->
                    labelLines[labelIndexes[label]]?.let { it.waitForFrame = wait }
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
        private val preinspection: ClassPreinspection
    ) : ClassVisitor(Opcodes.ASM9, visitor) {
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor {
            return TracingMethodVisitor(
                super.visitMethod(access, name, descriptor, signature, exceptions),
                preinspection.sourceFile ?: error("should only trace a class with a source file"),
                preinspection.methodPreinspections[MethodId(name, descriptor)] ?: error("missing preinspection")
            )
        }
    }

    private class TracingMethodVisitor(
        visitor: MethodVisitor,
        private val sourceFile: String,
        private val labelLines: Map<Int, LabelInfo>
    ) : MethodVisitor(Opcodes.ASM9, visitor) {
        private var currentLabelIndex = 0
        private var waitingForFrameLine: Int? = null

        override fun visitLabel(label: Label?) {
            super.visitLabel(label)
            labelLines[currentLabelIndex]?.let { labelInfo ->
                if (labelInfo.waitForFrame) {
                    waitingForFrameLine = labelInfo.line
                } else {
                    addTraceCall(labelInfo.line)
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
            waitingForFrameLine?.let {
                addTraceCall(it)
            }
            waitingForFrameLine = null
        }

        override fun visitMaxs(maxStack: Int, maxLocals: Int) {
            super.visitMaxs(maxStack + TRACING_STACK_ITEMS, maxLocals)
        }

        private fun addTraceCall(line: Int) {
            visitLdcInsn(sourceFile)
            visitLdcInsn(line)
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
    data class LineStep(val source: String, val line: Int)

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
