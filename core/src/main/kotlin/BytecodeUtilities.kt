package edu.illinois.cs.cs125.jeed.core

import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.LabelNode
import org.objectweb.asm.tree.MethodInsnNode
import java.lang.reflect.Modifier
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.javaMethod

fun KFunction<*>.asAsmHandle(): Handle {
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

fun KFunction<*>.asAsmMethodInsn(): MethodInsnNode {
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

fun AbstractInsnNode.skipToBeforeRealInsn(): AbstractInsnNode {
    var currentInsn = this
    while ((currentInsn.next?.opcode ?: 0) < 0) {
        currentInsn = currentInsn.next
    }
    return currentInsn
}

fun AbstractInsnNode.skipToBeforeRealInsnOrLabel(): AbstractInsnNode {
    var currentInsn = this
    while (currentInsn.next !is LabelNode && (currentInsn.next?.opcode ?: 0) < 0) {
        currentInsn = currentInsn.next
    }
    return currentInsn
}

fun AbstractInsnNode.previousRealInsn(): AbstractInsnNode? {
    var currentInsn: AbstractInsnNode? = this.previous
    while (currentInsn != null && currentInsn.opcode < 0) {
        currentInsn = currentInsn.previous
    }
    return currentInsn
}

fun Type.toArrayType(): Type {
    return Type.getType("[" + this.descriptor)
}

class NewLabelSplittingClassVisitor(visitor: ClassVisitor) : ClassVisitor(Opcodes.ASM9, visitor) {
    override fun visitMethod(
        access: Int,
        name: String?,
        descriptor: String?,
        signature: String?,
        exceptions: Array<out String>?
    ): MethodVisitor {
        return NewLabelSplittingMethodVisitor(super.visitMethod(access, name, descriptor, signature, exceptions))
    }
}

private class NewLabelSplittingMethodVisitor(visitor: MethodVisitor) : MethodVisitor(Opcodes.ASM9, visitor) {
    private var lastLabel: Label? = null
    private val flowLabelToNewLabel = mutableMapOf<Label, Label>()

    override fun visitLabel(label: Label) {
        super.visitLabel(label)
        lastLabel = label
    }

    override fun visitFrame(type: Int, numLocal: Int, local: Array<out Any>?, numStack: Int, stack: Array<out Any>?) {
        fun remapTypeArray(types: Array<out Any>?): Array<Any>? {
            if (types == null) return null
            return types.map {
                if (it is Label) {
                    flowLabelToNewLabel.getOrPut(it) { Label() }
                } else {
                    it
                }
            }.toTypedArray()
        }
        super.visitFrame(type, numLocal, remapTypeArray(local), numStack, remapTypeArray(stack))
    }

    override fun visitTypeInsn(opcode: Int, type: String?) {
        if (opcode == Opcodes.NEW && lastLabel != null) {
            val newLabel = flowLabelToNewLabel.getOrPut(lastLabel!!) { Label() }
            super.visitLabel(newLabel)
        }
        super.visitTypeInsn(opcode, type)
        lastLabel = null
    }

    // All the other instructions just reset whether a label is active

    override fun visitFieldInsn(opcode: Int, owner: String?, name: String?, descriptor: String?) {
        super.visitFieldInsn(opcode, owner, name, descriptor)
        lastLabel = null
    }

    override fun visitIincInsn(`var`: Int, increment: Int) {
        super.visitIincInsn(`var`, increment)
        lastLabel = null
    }

    override fun visitInsn(opcode: Int) {
        super.visitInsn(opcode)
        lastLabel = null
    }

    override fun visitIntInsn(opcode: Int, operand: Int) {
        super.visitIntInsn(opcode, operand)
        lastLabel = null
    }

    override fun visitInvokeDynamicInsn(
        name: String?,
        descriptor: String?,
        bootstrapMethodHandle: Handle?,
        vararg bootstrapMethodArguments: Any?
    ) {
        super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, *bootstrapMethodArguments)
        lastLabel = null
    }

    override fun visitJumpInsn(opcode: Int, label: Label?) {
        super.visitJumpInsn(opcode, label)
        lastLabel = null
    }

    override fun visitLdcInsn(value: Any?) {
        super.visitLdcInsn(value)
        lastLabel = null
    }

    override fun visitLookupSwitchInsn(dflt: Label?, keys: IntArray?, labels: Array<out Label>?) {
        super.visitLookupSwitchInsn(dflt, keys, labels)
        lastLabel = null
    }

    override fun visitMethodInsn(
        opcode: Int,
        owner: String?,
        name: String?,
        descriptor: String?,
        isInterface: Boolean
    ) {
        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
        lastLabel = null
    }

    override fun visitMultiANewArrayInsn(descriptor: String?, numDimensions: Int) {
        super.visitMultiANewArrayInsn(descriptor, numDimensions)
        lastLabel = null
    }

    override fun visitTableSwitchInsn(min: Int, max: Int, dflt: Label?, vararg labels: Label?) {
        super.visitTableSwitchInsn(min, max, dflt, *labels)
        lastLabel = null
    }

    override fun visitVarInsn(opcode: Int, `var`: Int) {
        super.visitVarInsn(opcode, `var`)
        lastLabel = null
    }
}
