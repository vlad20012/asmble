package asmble.compile.jvm

import asmble.ast.Node
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import org.objectweb.asm.tree.InsnNode
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.reflect.KClass
import kotlin.reflect.KFunction
import kotlin.reflect.jvm.reflect

open class ByteBufferMem(val direct: Boolean = true) : Mem {
    override val memType = ByteBuffer::class.ref

    override fun create(func: Func) = func.popExpecting(Int::class.ref).addInsns(
        (if (direct) ByteBuffer::allocateDirect else ByteBuffer::allocate).invokeStatic()
    ).push(memType)

    override fun init(func: Func, initial: Int) = func.popExpecting(memType).addInsns(
        // Set the limit to initial
        (initial * Mem.PAGE_SIZE).const,
        forceFnType<ByteBuffer.(Int) -> Buffer>(ByteBuffer::limit).invokeVirtual(),
        TypeInsnNode(Opcodes.CHECKCAST, ByteBuffer::class.ref.asmName),
        // Set it to use little endian
        ByteOrder::LITTLE_ENDIAN.getStatic(),
        forceFnType<ByteBuffer.(ByteOrder) -> ByteBuffer>(ByteBuffer::order).invokeVirtual()
    ).push(ByteBuffer::class.ref)

    override fun data(func: Func, bytes: ByteArray, buildOffset: (Func) -> Func) = func.
        popExpecting(memType).
        addInsns(
            bytes.size.const,
            IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE)
        ).
        // TODO: Is there a cheaper bulk approach? What's the harm of using a
        // String in the constant pool instead?
        addInsns(bytes.withIndex().flatMap { (index, byte) ->
            listOf(InsnNode(Opcodes.DUP), index.const, byte.toInt().const, InsnNode(Opcodes.BASTORE))
        }).
        apply(buildOffset).popExpecting(Int::class.ref).
        // BOO! https://discuss.kotlinlang.org/t/overload-resolution-ambiguity-function-reference-requiring-local-var/2425
        addInsns(
            bytes.size.const,
            forceFnType<ByteBuffer.(ByteArray, Int, Int) -> ByteBuffer>(ByteBuffer::put).invokeVirtual()
        ).
        push(memType)

    override fun currentMemory(ctx: FuncContext, func: Func) = func.popExpecting(memType).addInsns(
        forceFnType<ByteBuffer.() -> Int>(ByteBuffer::limit).invokeVirtual(),
        Mem.PAGE_SIZE.const,
        InsnNode(Opcodes.IDIV)
    ).push(Int::class.ref)

    override fun growMemory(ctx: FuncContext, func: Func) =
        func.popExpecting(memType).popExpecting(Int::class.ref).addInsns(
            Mem.PAGE_SIZE.const,
            // TODO: overflow check, e.g. Math.multiplyExact
            InsnNode(Opcodes.IMUL),
            forceFnType<ByteBuffer.(Int) -> Buffer>(ByteBuffer::limit).invokeVirtual()
        ).push(ByteBuffer::class.ref)

    override fun loadOp(ctx: FuncContext, func: Func, insn: Node.Instr.Args.AlignOffset): Func {
        // Ug, some tests expect this to be a runtime failure so we feature flagged it
        if (ctx.cls.eagerFailLargeMemOffset)
            require(insn.offset <= Int.MAX_VALUE, { "Offsets > ${Int.MAX_VALUE} unsupported" }).let { this }
        fun Func.load(fn: ByteBuffer.(Int) -> Any, retClass: KClass<*>) =
            this.popExpecting(Int::class.ref).let { func ->
                // No offset means we'll access it directly
                (if (insn.offset == 0L) func else {
                    // Since some things want runtime failure, we'll give it them via -1
                    if (insn.offset > Int.MAX_VALUE) func.addInsns(InsnNode(Opcodes.POP), (-1).const) else {
                        func.addInsns(insn.offset.toInt().const).let { func ->
                            // Simple add if no bounds check
                            if (!ctx.cls.preventMemIndexOverflow) func.addInsns(InsnNode(Opcodes.IADD)) else {
                                // Otherwise, do an addExact
                                func.addInsns(forceFnType<(Int, Int) -> Int>(Math::addExact).invokeStatic())
                            }
                        }
                    }
                }).popExpecting(memType).addInsns((fn as KFunction<*>).invokeVirtual())
            }.push(retClass.ref)
        fun Func.loadI32(fn: ByteBuffer.(Int) -> Any) =
            this.load(fn, Int::class)
        fun Func.loadI64(fn: ByteBuffer.(Int) -> Any) =
            this.load(fn, Long::class)
        /* Ug: https://youtrack.jetbrains.com/issue/KT-17064
        fun Func.toUnsigned(fn: KFunction<*>) =
            this.addInsns(fn.invokeVirtual())
        fun Func.toUnsigned64(fn: KFunction<*>) =
            this.popExpecting(Int::class.ref).toUnsigned(fn).push(Long::class.ref)
        */
        fun Func.toUnsigned(owner: KClass<*>, methodName: String, inClass: KClass<*>) =
            this.addInsns(MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner.ref.asmName, methodName,
                Type.getMethodDescriptor(Int::class.ref.asm, inClass.ref.asm), false))
        fun Func.toUnsigned32(owner: KClass<*>, methodName: String, inClass: KClass<*>) =
            this.popExpecting(Int::class.ref).toUnsigned(owner, methodName, inClass).push(Int::class.ref)
        fun Func.toUnsigned64(owner: KClass<*>, methodName: String, inClass: KClass<*>) =
            this.popExpecting(Int::class.ref).toUnsigned(owner, methodName, inClass).push(Long::class.ref)
        fun Func.i32ToI64() =
            this.popExpecting(Int::class.ref).addInsns(InsnNode(Opcodes.I2L)).push(Long::class.ref)
        // Had to move this in here instead of as first expr because of https://youtrack.jetbrains.com/issue/KT-8689
        return when (insn) {
            is Node.Instr.I32Load ->
                func.loadI32(ByteBuffer::getInt)
            is Node.Instr.I64Load ->
                func.loadI64(ByteBuffer::getLong)
            is Node.Instr.F32Load ->
                func.load(ByteBuffer::getFloat, Float::class)
            is Node.Instr.F64Load ->
                func.load(ByteBuffer::getDouble, Double::class)
            is Node.Instr.I32Load8S ->
                func.loadI32(ByteBuffer::get)
            is Node.Instr.I32Load8U ->
                func.loadI32(ByteBuffer::get).toUnsigned32(java.lang.Byte::class, "toUnsignedInt", Byte::class)
            is Node.Instr.I32Load16S ->
                func.loadI32(ByteBuffer::getShort)
            is Node.Instr.I32Load16U ->
                func.loadI32(ByteBuffer::getShort).toUnsigned32(java.lang.Short::class, "toUnsignedInt", Short::class)
            is Node.Instr.I64Load8S ->
                func.loadI32(ByteBuffer::get).i32ToI64()
            is Node.Instr.I64Load8U ->
                func.loadI32(ByteBuffer::get).toUnsigned64(java.lang.Byte::class, "toUnsignedLong", Byte::class)
            is Node.Instr.I64Load16S ->
                func.loadI32(ByteBuffer::getShort).i32ToI64()
            is Node.Instr.I64Load16U ->
                func.loadI32(ByteBuffer::getShort).toUnsigned64(java.lang.Short::class, "toUnsignedLong", Short::class)
            is Node.Instr.I64Load32S ->
                func.loadI32(ByteBuffer::getInt).i32ToI64()
            is Node.Instr.I64Load32U ->
                func.loadI32(ByteBuffer::getInt).toUnsigned64(java.lang.Integer::class, "toUnsignedLong", Int::class)
            else -> throw IllegalArgumentException("Unknown load op $insn")
        }
    }

    override fun storeOp(ctx: FuncContext, func: Func, insn: Node.Instr.Args.AlignOffset) =
        func.popExpecting(memType).let { func ->
            require(insn.offset <= Int.MAX_VALUE, { "Offsets > ${Int.MAX_VALUE} unsupported" }).let { this }
            fun <T> Func.store(fn: ByteBuffer.(Int, T) -> ByteBuffer, inClass: KClass<*>) =
                // We add the index and then swap with the value already on the stack
                this.addInsns(insn.offset.toInt().const).
                    push(Int::class.ref).
                    stackSwap().
                    popExpecting(inClass.ref).
                    addInsns(fn.reflect()!!.invokeVirtual()).
                    push(ByteBuffer::class.ref)

            fun Func.changeI64ToI32() =
                this.popExpecting(Long::class.ref).push(Int::class.ref)
            when (insn) {
                is Node.Instr.I32Store ->
                    func.store(ByteBuffer::putInt, Int::class)
                is Node.Instr.I64Store ->
                    func.store(ByteBuffer::putLong, Long::class)
                is Node.Instr.F32Store ->
                    func.store(ByteBuffer::putFloat, Float::class)
                is Node.Instr.F64Store ->
                    func.store(ByteBuffer::putDouble, Double::class)
                is Node.Instr.I32Store8 ->
                    func.addInsns(InsnNode(Opcodes.I2B)).store(ByteBuffer::put, Int::class)
                is Node.Instr.I32Store16 ->
                    func.addInsns(InsnNode(Opcodes.I2S)).store(ByteBuffer::putShort, Int::class)
                is Node.Instr.I64Store8 ->
                    func.addInsns(InsnNode(Opcodes.L2I), InsnNode(Opcodes.I2B)).
                        changeI64ToI32().store(ByteBuffer::put, Int::class)
                is Node.Instr.I64Store16 ->
                    func.addInsns(InsnNode(Opcodes.L2I), InsnNode(Opcodes.I2S)).
                        changeI64ToI32().store(ByteBuffer::putShort, Int::class)
                is Node.Instr.I64Store32 ->
                    func.addInsns(InsnNode(Opcodes.L2I)).
                        changeI64ToI32().store(ByteBuffer::putInt, Int::class)
                else -> throw IllegalArgumentException("Unknown store op $insn")
            }
        }

    companion object : ByteBufferMem()
}