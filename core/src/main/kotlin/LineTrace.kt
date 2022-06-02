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

typealias LineCallback = (source: String, line: Int) -> Unit

object LineTrace : SandboxPluginWithDefaultArguments<LineTraceArguments, LineTraceResult> {
    const val KILL_REASON = "exceededLineLimit"

    private const val TRACING_STACK_ITEMS = 4
    private val tracingClassName = classNameToPath(TracingSink::class.java.name)
    private val tracingLineMethodName = TracingSink::enterLine.javaMethod?.name ?: error("missing tracing method name")
    private val tracingLineMethodDesc = Type.getMethodDescriptor(TracingSink::enterLine.javaMethod)

    private val threadArguments: ThreadLocal<LineTraceArguments> = ThreadLocal()
    private val threadData: ThreadLocal<LineTraceWorkingData.PerThreadWorkingData> = ThreadLocal()
    private val threadIndex: ThreadLocal<Int> = ThreadLocal()
    private val threadInSingleThreadTask: ThreadLocal<Boolean> = ThreadLocal()
    private val threadCallbacks: ThreadLocal<List<LineCallback>> = ThreadLocal()
    private val initializedThreadLocals = ThreadLocal.withInitial { false }
    private fun ensureThreadLocalsInitialized() {
        if (initializedThreadLocals.get()) return
        val workingData: LineTraceWorkingData = Sandbox.CurrentTask.getWorkingData(this)
        synchronized(workingData.threadTrackingSyncRoot) {
            if (workingData.singleThread && workingData.nextThreadIndex != LineTraceResult.MAIN_THREAD) {
                throw Sandbox.UnexpectedExtraThreadError()
            }
            val data = LineTraceWorkingData.PerThreadWorkingData()
            threadData.set(data)
            workingData.threads[workingData.nextThreadIndex] = data
            threadIndex.set(workingData.nextThreadIndex)
            workingData.nextThreadIndex++
            threadCallbacks.set(workingData.lineCallbacks.toList())
        }
        threadArguments.set(workingData.arguments)
        threadInSingleThreadTask.set(workingData.singleThread)
        initializedThreadLocals.set(true)
    }

    private inline fun <R> synchronizedIfNeeded(lock: Any, crossinline block: () -> R): R {
        return if (threadInSingleThreadTask.get()) {
            block()
        } else {
            synchronized(lock, block)
        }
    }

    override fun createDefaultArguments(): LineTraceArguments {
        return LineTraceArguments()
    }

    override fun createInstrumentationData(
        arguments: LineTraceArguments,
        classLoaderConfiguration: Sandbox.ClassLoaderConfiguration,
        allPlugins: List<ConfiguredSandboxPlugin<*, *>>
    ): Any {
        val plugins = allPlugins.map { it.plugin }
        val thisIndex = plugins.indexOf(this)
        val jacocoIndex = plugins.indexOf(Jacoco).takeIf { it >= 0 }
        if (jacocoIndex != null && thisIndex < jacocoIndex) {
            error("LineTrace should run after Jacoco to avoid interfering with probe placement")
        }
        return LineTraceInstrumentationData(arguments)
    }

    override val requiredClasses: Set<Class<*>>
        get() = setOf(TracingSink::class.java)

    object TracingSink {
        @JvmStatic
        fun enterLine(file: String, methodId: Int, sequence: Int, line: Int) {
            ensureThreadLocalsInitialized()
            val args = threadArguments.get()
            val data = threadData.get()
            if (args.coalesceDuplicates) {
                val lastStep = synchronizedIfNeeded(data.syncLock) { data.steps.lastOrNull() }
                val isDuplicate = lastStep?.let { previous ->
                    previous.line == line &&
                        data.lastMethodId == methodId &&
                        data.lastLabelSequence == sequence - 1
                } ?: false
                data.lastMethodId = methodId
                data.lastLabelSequence = sequence
                if (isDuplicate) return
            }
            val totalLinesRun = data.linesRun + data.linesRunByOtherThreads + 1
            if (args.runLineLimit?.let { totalLinesRun > it } == true) {
                when (args.runLineLimitExceededAction) {
                    LineTraceArguments.RunLineLimitAction.KILL_SANDBOX -> Sandbox.CurrentTask.kill(KILL_REASON)
                    LineTraceArguments.RunLineLimitAction.THROW_ERROR -> throw LineLimitExceeded()
                }
            }
            threadCallbacks.get().forEach {
                it(file, line)
            }
            synchronizedIfNeeded(data.syncLock) {
                if (args.recordedLineLimit >= totalLinesRun) {
                    data.steps.add(LineTraceResult.LineStep(file, line, threadIndex.get()))
                }
                data.linesRun++
                data.unsynchronizedLines++
            }
            if (data.unsynchronizedLines > args.maxUnsynchronizedLines && !threadInSingleThreadTask.get()) {
                val outerWorkingData: LineTraceWorkingData = Sandbox.CurrentTask.getWorkingData(LineTrace)
                synchronized(outerWorkingData.threadTrackingSyncRoot) {
                    data.linesRunByOtherThreads = 0
                    outerWorkingData.threads.forEach { (i, otherData) ->
                        if (i != threadIndex.get()) {
                            data.linesRunByOtherThreads += otherData.linesRun
                        }
                    }
                }
                data.unsynchronizedLines = 0
            }
        }
    }

    override fun transformBeforeSandbox(bytecode: ByteArray, name: String, instrumentationData: Any?, context: RewritingContext): ByteArray {
        if (context != RewritingContext.UNTRUSTED) return bytecode
        val classReader = ClassReader(bytecode)
        val preinspectingClassVisitor = PreinspectingClassVisitor()
        classReader.accept(NewLabelSplittingClassVisitor(preinspectingClassVisitor), 0)
        val preinspection = preinspectingClassVisitor.getPreinspection()
        if (preinspection.sourceFile == null) return bytecode
        val classWriter = ClassWriter(classReader, 0)
        val rewritingVisitor = TracingClassVisitor(classWriter, preinspection, instrumentationData as LineTraceInstrumentationData)
        classReader.accept(NewLabelSplittingClassVisitor(rewritingVisitor), 0)
        return classWriter.toByteArray()
    }

    override fun createInitialData(instrumentationData: Any?, executionArguments: Sandbox.ExecutionArguments): Any {
        instrumentationData as LineTraceInstrumentationData
        return LineTraceWorkingData(instrumentationData.arguments, singleThread = executionArguments.maxExtraThreads == 0)
    }

    override fun createFinalData(workingData: Any?): LineTraceResult {
        workingData as LineTraceWorkingData
        val allSteps = mutableListOf<LineTraceResult.LineStep>()
        var totalLines = 0L
        synchronized(workingData.threadTrackingSyncRoot) {
            workingData.threads.values.forEach {
                synchronized(it.syncLock) {
                    allSteps.addAll(it.steps)
                    totalLines += it.linesRun
                }
            }
        }
        return LineTraceResult(workingData.arguments, allSteps, totalLines)
    }

    @Suppress("unused") // For trusted code inside the task
    fun getCurrentReport(): LineTraceResult {
        return createFinalData(Sandbox.CurrentTask.getWorkingData(this))
    }

    @Suppress("unused") // For trusted code inside the task
    fun resetLineCounts() {
        ensureThreadLocalsInitialized()
        val workingData: LineTraceWorkingData = Sandbox.CurrentTask.getWorkingData(this)
        synchronizedIfNeeded(workingData.threadTrackingSyncRoot) {
            workingData.threads.values.forEach {
                synchronizedIfNeeded(it.syncLock) {
                    it.linesRun = 0
                    it.linesRunByOtherThreads = 0
                    it.unsynchronizedLines = 0
                    it.steps = mutableListOf()
                    it.lastMethodId = null
                    it.lastLabelSequence = null
                }
            }
        }
    }

    fun addLineCallback(callback: LineCallback) {
        require(!initializedThreadLocals.get()) { "This thread already began collecting line data" }
        val workingData: LineTraceWorkingData = Sandbox.CurrentTask.getWorkingData(this)
        synchronized(workingData.threadTrackingSyncRoot) {
            workingData.lineCallbacks.add(callback)
        }
    }

    private data class ClassPreinspection(
        val sourceFile: String?,
        val methodPreinspections: Map<MethodId, Map<Int, LabelLine>>
    )
    private data class MethodId(val name: String, val descriptor: String)
    private data class LabelInfo(val index: Int, var line: Int?, var waitForFrame: Boolean)
    private data class LabelLine(val line: Int, val labelSequence: Int, val waitForFrame: Boolean)

    private class LineTraceInstrumentationData(
        val arguments: LineTraceArguments,
        var nextMethodId: Int = 0
    )

    private class LineTraceWorkingData(
        val arguments: LineTraceArguments,
        val singleThread: Boolean,
        val threads: MutableMap<Int, PerThreadWorkingData> = mutableMapOf(),
        var nextThreadIndex: Int = LineTraceResult.MAIN_THREAD,
        val lineCallbacks: MutableList<LineCallback> = mutableListOf(),
        val threadTrackingSyncRoot: Any = Object()
    ) {
        class PerThreadWorkingData(
            var linesRun: Long = 0,
            var steps: MutableList<LineTraceResult.LineStep> = mutableListOf(),
            var lastMethodId: Int? = null,
            var lastLabelSequence: Int? = null,
            var unsynchronizedLines: Int = 0,
            var linesRunByOtherThreads: Long = 0,
            val syncLock: Any = Object()
        )
    }

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
data class LineTraceArguments(
    val recordedLineLimit: Long = DEFAULT_RECORDED_LINE_LIMIT,
    val runLineLimit: Long? = null,
    val runLineLimitExceededAction: RunLineLimitAction = RunLineLimitAction.KILL_SANDBOX,
    val maxUnsynchronizedLines: Int = DEFAULT_MAX_UNSYNCHRONIZED_LINES,
    val coalesceDuplicates: Boolean = true
) {
    enum class RunLineLimitAction {
        KILL_SANDBOX, THROW_ERROR
    }

    companion object {
        const val DEFAULT_RECORDED_LINE_LIMIT = 100000L
        const val DEFAULT_MAX_UNSYNCHRONIZED_LINES = 1000
    }
}

@JsonClass(generateAdapter = true)
data class LineTraceResult(
    val arguments: LineTraceArguments,
    val steps: List<LineStep>,
    val linesRun: Long
) {
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
        const val MAIN_THREAD = 0
    }
}

class LineLimitExceeded : Error(LineTrace.KILL_REASON) {
    override fun fillInStackTrace() = this
}
