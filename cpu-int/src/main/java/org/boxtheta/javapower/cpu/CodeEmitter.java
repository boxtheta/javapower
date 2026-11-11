package org.boxtheta.javapower.cpu;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDesc;
import java.lang.constant.DirectMethodHandleDesc;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodTypeDesc;

import org.boxtheta.javapower.cpu.internal.asm.ClassWriter;
import org.boxtheta.javapower.cpu.internal.asm.ConstantDynamic;
import org.boxtheta.javapower.cpu.internal.asm.Handle;
import org.boxtheta.javapower.cpu.internal.asm.Label;
import org.boxtheta.javapower.cpu.internal.asm.MethodVisitor;
import org.boxtheta.javapower.cpu.internal.asm.Opcodes;

/**
 * A small fluent layer over ASM (bundled, relocated to {@code org.boxtheta.javapower.cpu.internal.asm})
 * for the bytecode {@link BlockCompiler} and {@link RegionCompiler} generate: one final class
 * extending {@link CompiledBlock} with a no-argument constructor and the {@code run} method,
 * whose body the caller emits through this object. Types are given as {@link ClassDesc} and
 * {@link MethodTypeDesc}; stack map frames are computed by ASM.
 */
final class CodeEmitter {
    /** Local variable kinds for {@link #allocateLocal}. */
    static final int INT = 1;
    static final int LONG = 2;
    static final int REFERENCE = 3;

    private static final String BASE = "org/boxtheta/javapower/cpu/CompiledBlock";
    private static final String OBJECT = "java/lang/Object";

    private final ClassWriter cw;
    private final MethodVisitor mv;
    private int nextLocal;
    private Label endOfCode;

    /**
     * Start class {@code name} (in this package; it is defined as a hidden class) with method
     * {@code run} of type {@code runType}, an instance method whose parameters take the first
     * locals after {@code this}.
     */
    CodeEmitter(ClassDesc name, MethodTypeDesc runType) {
        cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
            // Frames only ever merge identical reference types in the generated code; never load
            // classes to find a common superclass (the class loader could be anything).
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                return type1.equals(type2) ? type1 : OBJECT;
            }
        };
        String internal = internalName(name);
        cw.visit(Opcodes.V21, Opcodes.ACC_FINAL | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC, internal, null, BASE,
                null);
        MethodVisitor init = cw.visitMethod(0, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(Opcodes.ALOAD, 0);
        init.visitMethodInsn(Opcodes.INVOKESPECIAL, BASE, "<init>", "()V", false);
        init.visitInsn(Opcodes.RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        mv = cw.visitMethod(Opcodes.ACC_FINAL, "run", runType.descriptorString(), null, null);
        mv.visitCode();
        nextLocal = 1;
        for (int k = 0; k < runType.parameterCount(); k++) {
            String d = runType.parameterType(k).descriptorString();
            nextLocal += d.equals("J") || d.equals("D") ? 2 : 1;
        }
    }

    private static String internalName(ClassDesc c) {
        String d = c.descriptorString();
        return d.charAt(0) == 'L' ? d.substring(1, d.length() - 1) : d;
    }

    /**
     * Finish the method and the class. Returns the class file bytes; afterwards {@link #codeLength}
     * is known.
     */
    byte[] finish() {
        endOfCode = new Label();
        mv.visitLabel(endOfCode);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Bytes of bytecode in {@code run} (after {@link #finish}). */
    int codeLength() {
        return endOfCode.getOffset();
    }

    // ---------------------------------------------------------------- locals and labels

    /** A new local of kind {@link #INT}, {@link #LONG} or {@link #REFERENCE}; returns its slot. */
    int allocateLocal(int kind) {
        int s = nextLocal;
        nextLocal += kind == LONG ? 2 : 1;
        return s;
    }

    Label newLabel() {
        return new Label();
    }

    CodeEmitter labelBinding(Label l) {
        mv.visitLabel(l);
        return this;
    }

    /** Catch {@code type} thrown in [start, end) at {@code handler}. Call before binding the labels. */
    CodeEmitter exceptionCatch(Label start, Label end, Label handler, ClassDesc type) {
        mv.visitTryCatchBlock(start, end, handler, internalName(type));
        return this;
    }

    // ---------------------------------------------------------------- loads, stores, constants

    private CodeEmitter var(int op, int slot) {
        mv.visitVarInsn(op, slot);
        return this;
    }

    CodeEmitter aload(int s) {
        return var(Opcodes.ALOAD, s);
    }

    CodeEmitter astore(int s) {
        return var(Opcodes.ASTORE, s);
    }

    CodeEmitter iload(int s) {
        return var(Opcodes.ILOAD, s);
    }

    CodeEmitter istore(int s) {
        return var(Opcodes.ISTORE, s);
    }

    CodeEmitter lload(int s) {
        return var(Opcodes.LLOAD, s);
    }

    CodeEmitter lstore(int s) {
        return var(Opcodes.LSTORE, s);
    }

    CodeEmitter loadConstant(int v) {
        if (v >= -1 && v <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + v);
        } else if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, v);
        } else if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, v);
        } else {
            mv.visitLdcInsn(v);
        }
        return this;
    }

    CodeEmitter loadConstant(long v) {
        if (v == 0 || v == 1) {
            mv.visitInsn(Opcodes.LCONST_0 + (int) v);
        } else {
            mv.visitLdcInsn(v);
        }
        return this;
    }

    /** A dynamic constant whose bootstrap method is a static method (e.g. {@code classDataAt}). */
    CodeEmitter loadConstant(DynamicConstantDesc<?> c) {
        DirectMethodHandleDesc bsm = c.bootstrapMethod();
        Handle h = new Handle(Opcodes.H_INVOKESTATIC, internalName(bsm.owner()), bsm.methodName(),
                bsm.lookupDescriptor(), bsm.isOwnerInterface());
        ConstantDesc[] args = c.bootstrapArgs();
        Object[] asmArgs = new Object[args.length];
        for (int k = 0; k < args.length; k++) {
            if (!(args[k] instanceof Integer || args[k] instanceof Long || args[k] instanceof String)) {
                throw new IllegalArgumentException("unsupported bootstrap argument " + args[k]);
            }
            asmArgs[k] = args[k];
        }
        mv.visitLdcInsn(new ConstantDynamic(c.constantName(), c.constantType().descriptorString(), h, asmArgs));
        return this;
    }

    CodeEmitter iconst_m1() {
        return loadConstant(-1);
    }

    CodeEmitter iconst_0() {
        return loadConstant(0);
    }

    CodeEmitter iconst_1() {
        return loadConstant(1);
    }

    CodeEmitter lconst_0() {
        return loadConstant(0L);
    }

    CodeEmitter lconst_1() {
        return loadConstant(1L);
    }

    // ---------------------------------------------------------------- fields, arrays, calls

    CodeEmitter getfield(ClassDesc owner, String name, ClassDesc type) {
        mv.visitFieldInsn(Opcodes.GETFIELD, internalName(owner), name, type.descriptorString());
        return this;
    }

    CodeEmitter putfield(ClassDesc owner, String name, ClassDesc type) {
        mv.visitFieldInsn(Opcodes.PUTFIELD, internalName(owner), name, type.descriptorString());
        return this;
    }

    CodeEmitter invokestatic(ClassDesc owner, String name, MethodTypeDesc type) {
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, internalName(owner), name, type.descriptorString(), false);
        return this;
    }

    CodeEmitter invokevirtual(ClassDesc owner, String name, MethodTypeDesc type) {
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, internalName(owner), name, type.descriptorString(), false);
        return this;
    }

    CodeEmitter invokeinterface(ClassDesc owner, String name, MethodTypeDesc type) {
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, internalName(owner), name, type.descriptorString(), true);
        return this;
    }

    private CodeEmitter insn(int op) {
        mv.visitInsn(op);
        return this;
    }

    CodeEmitter laload() {
        return insn(Opcodes.LALOAD);
    }

    CodeEmitter lastore() {
        return insn(Opcodes.LASTORE);
    }

    CodeEmitter iaload() {
        return insn(Opcodes.IALOAD);
    }

    // ---------------------------------------------------------------- arithmetic

    CodeEmitter ladd() {
        return insn(Opcodes.LADD);
    }

    CodeEmitter lsub() {
        return insn(Opcodes.LSUB);
    }

    CodeEmitter lmul() {
        return insn(Opcodes.LMUL);
    }

    CodeEmitter lneg() {
        return insn(Opcodes.LNEG);
    }

    CodeEmitter land() {
        return insn(Opcodes.LAND);
    }

    CodeEmitter lor() {
        return insn(Opcodes.LOR);
    }

    CodeEmitter lxor() {
        return insn(Opcodes.LXOR);
    }

    CodeEmitter lshl() {
        return insn(Opcodes.LSHL);
    }

    CodeEmitter lcmp() {
        return insn(Opcodes.LCMP);
    }

    CodeEmitter iand() {
        return insn(Opcodes.IAND);
    }

    CodeEmitter ior() {
        return insn(Opcodes.IOR);
    }

    CodeEmitter ixor() {
        return insn(Opcodes.IXOR);
    }

    CodeEmitter ishl() {
        return insn(Opcodes.ISHL);
    }

    CodeEmitter iushr() {
        return insn(Opcodes.IUSHR);
    }

    CodeEmitter l2i() {
        return insn(Opcodes.L2I);
    }

    CodeEmitter i2l() {
        return insn(Opcodes.I2L);
    }

    CodeEmitter i2b() {
        return insn(Opcodes.I2B);
    }

    CodeEmitter i2s() {
        return insn(Opcodes.I2S);
    }

    CodeEmitter dup() {
        return insn(Opcodes.DUP);
    }

    CodeEmitter dup2() {
        return insn(Opcodes.DUP2);
    }

    // ---------------------------------------------------------------- control flow

    private CodeEmitter jump(int op, Label l) {
        mv.visitJumpInsn(op, l);
        return this;
    }

    CodeEmitter goto_(Label l) {
        return jump(Opcodes.GOTO, l);
    }

    CodeEmitter ifeq(Label l) {
        return jump(Opcodes.IFEQ, l);
    }

    CodeEmitter ifne(Label l) {
        return jump(Opcodes.IFNE, l);
    }

    CodeEmitter ifle(Label l) {
        return jump(Opcodes.IFLE, l);
    }

    CodeEmitter ireturn() {
        return insn(Opcodes.IRETURN);
    }
}
