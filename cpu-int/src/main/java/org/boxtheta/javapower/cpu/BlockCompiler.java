package org.boxtheta.javapower.cpu;

import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_long;
import static java.lang.constant.ConstantDescs.CD_void;
import static java.lang.constant.ConstantDescs.INIT_NAME;
import static java.lang.constant.ConstantDescs.MTD_void;

import java.lang.classfile.ClassFile;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Label;
import java.lang.classfile.TypeKind;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

import org.boxtheta.javapower.core.Op;

/**
 * Translates a straight-line run of guest instructions into a hidden class whose
 * {@link CompiledBlock#run} executes them. The generated code does not re-implement any
 * instruction: for every instruction it performs exactly the run loop's bookkeeping and calls the
 * same handler the interpreter would (this module's handlers by direct static call, others
 * through their {@link Op}, held as a constant), with the instruction word as a constant. The JIT
 * then inlines the handlers and folds the decoded fields.
 *
 * <p>The code does not depend on the block's effective address: it reads it from {@code s.pc} on
 * entry, so one compiled block serves every address its real address is mapped at (any CPU, any
 * process).
 *
 * <p>For instruction {@code k} at address {@code pc} the generated code does what one run-loop
 * iteration does: {@code s.pc = pc; s.npc = pc + 4} (masked to the computation mode the block was
 * built for), execute, {@code s.icount++}. A {@link org.boxtheta.javapower.core.PpcException}
 * leaves through {@link Interpreter#blockFault}, as in the run loop. Blocks end after any
 * unconditional branch or instruction that may change state the run loop caches (see
 * {@link #flags}); the run loop's {@code recheck} check therefore only needs to happen after the
 * block. Early exits keep the semantics exact where the next instruction may not be the one that
 * was translated: a conditional branch that was taken, an instruction from another module that
 * changed {@code s.npc}, and a store that hits the block's own code (the MMU's store guard).
 */
final class BlockCompiler {
    private BlockCompiler() {}

    /** Longest block, in instructions. */
    static final int MAX_LENGTH = 32;

    /** The block may continue after this instruction. */
    static final int CONT = 1;
    /** The instruction may store: check the MMU's guard for a write to the block's own code. */
    static final int STORE = 2;
    /** May branch (a conditional branch, or another module's op): leave the block if it did. */
    static final int FOREIGN = 4;

    private static final ClassDesc CD_CPU = ClassDesc.of("org.boxtheta.javapower.core.CpuState");
    private static final ClassDesc CD_OP = ClassDesc.of("org.boxtheta.javapower.core.Op");
    private static final ClassDesc CD_PPCEX = ClassDesc.of("org.boxtheta.javapower.core.PpcException");
    private static final ClassDesc CD_INTERP = ClassDesc.of("org.boxtheta.javapower.cpu.Interpreter");
    private static final ClassDesc CD_MMU = ClassDesc.of("org.boxtheta.javapower.cpu.RadixMmu");
    private static final ClassDesc CD_BASE = ClassDesc.of("org.boxtheta.javapower.cpu.CompiledBlock");
    private static final ClassDesc CD_BLOCK = ClassDesc.of("org.boxtheta.javapower.cpu.Block");
    private static final MethodTypeDesc MTD_HANDLER = MethodTypeDesc.of(CD_void, CD_CPU, CD_int);
    private static final MethodTypeDesc MTD_RUN = MethodTypeDesc.of(CD_int, CD_CPU, CD_INTERP);
    private static final MethodTypeDesc MTD_FAULT = MethodTypeDesc.of(CD_void, CD_PPCEX);

    /** Flags per inline dispatch slot (see {@link #flags} for the other slots). */
    private static final int[] FLAGS = new int[Interpreter.FIRST_DYNAMIC];

    static {
        for (int k = 1; k < Interpreter.INLINE_HANDLERS.length; k++) {
            String h = Interpreter.INLINE_HANDLERS[k];
            String cls = h.substring(0, h.indexOf('.'));
            String m = h.substring(h.indexOf('.') + 1);
            FLAGS[k] = switch (cls) {
                case "Alu" -> CONT;
                // Pure loads need no store guard; everything else in LoadStore may write.
                case "LoadStore" -> m.startsWith("l") && !m.endsWith("at") ? CONT : CONT | STORE;
                case "BranchInsns" -> switch (m) {
                    // A conditional branch continues the block on its not-taken path.
                    case "bc" -> CONT | FOREIGN;
                    case "b", "bclr", "bcctr", "bctar" -> 0;
                    default -> CONT;
                };
                case "SprAccess" -> m.equals("mfspr") ? CONT : 0;
                case "SystemInsns" -> switch (m) {
                    case "dcbz" -> CONT | STORE;
                    case "dcbfst", "icbi", "nop", "eieio", "mfmsr" -> CONT;
                    default -> 0;
                };
                default -> 0;
            };
        }
        for (int k = Interpreter.NUM_INLINE + 1; k < Interpreter.FIRST_DYNAMIC; k++) {
            FLAGS[k] = CONT; // mflr, mfctr, mtlr, mtctr
        }
    }

    /**
     * Flags of the instruction in dispatch slot {@code slot} with handler {@code op}. Handlers of
     * this module outside the inline set end a block; another module's handlers may continue it
     * (they cannot reach the run loop's state) but may store or branch.
     */
    static int flags(int slot, Op op) {
        if (slot < FLAGS.length) {
            return FLAGS[slot];
        }
        return op instanceof Reg.Local ? 0 : CONT | STORE | FOREIGN;
    }

    /** Push {@code (base + off) & mask} (a long), base being a long local. */
    private static void address(CodeBuilder cob, int base, long off, long mask) {
        cob.lload(base);
        if (off != 0) {
            cob.loadConstant(off).ladd();
            if (mask != -1L) {
                cob.loadConstant(mask).land();
            }
        }
    }

    /**
     * Generate the block for the {@code n} instructions {@code words}, decoded to dispatch slots
     * {@code slots}, starting at effective address {@code ea}. {@code pcMask} is the run loop's
     * mask for the next-instruction address (MSR[SF]); {@code ops} maps slots to handlers.
     */
    static CompiledBlock compile(long ea, long pcMask, int[] words, int[] slots, int n, Op[] ops) {
        List<Op> foreign = new ArrayList<>();
        byte[] bytes = ClassFile.of().build(CD_BLOCK, clb -> {
            clb.withFlags(ClassFile.ACC_FINAL | ClassFile.ACC_SYNTHETIC);
            clb.withSuperclass(CD_BASE);
            clb.withMethodBody(INIT_NAME, MTD_void, 0, cob -> cob
                    .aload(0)
                    .invokespecial(CD_BASE, INIT_NAME, MTD_void)
                    .return_());
            clb.withMethodBody("run", MTD_RUN, ClassFile.ACC_FINAL, cob -> {
                int base = cob.allocateLocal(TypeKind.LONG);
                cob.aload(1).getfield(CD_CPU, "pc", CD_long).lstore(base);
                Label start = cob.newLabel();
                Label end = cob.newLabel();
                Label exit = cob.newLabel();
                Label fault = cob.newLabel();
                cob.labelBinding(start);
                for (int k = 0; k < n; k++) {
                    cob.aload(1);
                    address(cob, base, 4L * k, pcMask);
                    cob.putfield(CD_CPU, "pc", CD_long);
                    cob.aload(1);
                    address(cob, base, 4L * k + 4, pcMask);
                    cob.putfield(CD_CPU, "npc", CD_long);
                    int slot = slots[k];
                    if (slot < Interpreter.INLINE_HANDLERS.length) {
                        String h = Interpreter.INLINE_HANDLERS[slot];
                        ClassDesc owner = ClassDesc.of("org.boxtheta.javapower.cpu." + h.substring(0, h.indexOf('.')));
                        cob.aload(1).loadConstant(words[k])
                                .invokestatic(owner, h.substring(h.indexOf('.') + 1), MTD_HANDLER);
                    } else if (slot < Interpreter.FIRST_DYNAMIC) {
                        cob.aload(1).loadConstant(words[k])
                                .invokestatic(CD_INTERP, Interpreter.specialHandler(slot), MTD_HANDLER);
                    } else {
                        cob.loadConstant(DynamicConstantDesc.ofNamed(ConstantDescs.BSM_CLASS_DATA_AT,
                                ConstantDescs.DEFAULT_NAME, CD_OP, foreign.size()));
                        foreign.add(ops[slot]);
                        cob.aload(1).loadConstant(words[k]).invokeinterface(CD_OP, "exec", MTD_HANDLER);
                    }
                    cob.aload(1).dup().getfield(CD_CPU, "icount", CD_long).lconst_1().ladd()
                            .putfield(CD_CPU, "icount", CD_long);
                    int f = flags(slot, slot < ops.length ? ops[slot] : null);
                    if (k < n - 1) {
                        if ((f & STORE) != 0) {
                            cob.aload(2).getfield(CD_INTERP, "mmu", CD_MMU).getfield(CD_MMU, "guardHit", CD_boolean)
                                    .ifne(exit);
                        }
                        if ((f & FOREIGN) != 0) {
                            cob.aload(1).getfield(CD_CPU, "npc", CD_long);
                            address(cob, base, 4L * k + 4, pcMask);
                            cob.lcmp().ifne(exit);
                        }
                    }
                }
                cob.labelBinding(end);
                cob.labelBinding(exit);
                // s.pc = s.npc, as after every instruction in the run loop.
                cob.aload(1).aload(1).getfield(CD_CPU, "npc", CD_long).putfield(CD_CPU, "pc", CD_long);
                cob.loadConstant(CompiledBlock.CONTINUE).ireturn();
                cob.labelBinding(fault);
                int ex = cob.allocateLocal(TypeKind.REFERENCE);
                cob.astore(ex).aload(2).aload(ex).invokevirtual(CD_INTERP, "blockFault", MTD_FAULT);
                cob.loadConstant(CompiledBlock.FAULTED).ireturn();
                cob.exceptionCatch(start, end, fault, CD_PPCEX);
            });
        });
        try {
            MethodHandles.Lookup hl = MethodHandles.lookup().defineHiddenClassWithClassData(bytes, foreign, true);
            return (CompiledBlock) hl.findConstructor(hl.lookupClass(), MethodType.methodType(void.class)).invoke();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("cannot define compiled block", e);
        }
    }
}
