package org.boxtheta.javapower.cpu;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.Op;
import org.boxtheta.javapower.core.PpcException;

/**
 * Instruction field extraction (ISA bit numbering in comments: bit 0 is the MSB of the 32-bit
 * instruction word) and small helpers shared by the instruction handlers.
 */
final class Fields {
    private Fields() {}

    /** RT/RS/BO/TO: ISA bits 6:10. */
    static int rt(int i) {
        return (i >>> 21) & 31;
    }

    /** RA/BI: ISA bits 11:15. */
    static int ra(int i) {
        return (i >>> 16) & 31;
    }

    /** RB: ISA bits 16:20. */
    static int rb(int i) {
        return (i >>> 11) & 31;
    }

    /** RC / BC / MB(A-form): ISA bits 21:25. */
    static int rc(int i) {
        return (i >>> 6) & 31;
    }

    /** BF: ISA bits 6:8. */
    static int bf(int i) {
        return (i >>> 23) & 7;
    }

    /** BFA: ISA bits 11:13. */
    static int bfa(int i) {
        return (i >>> 18) & 7;
    }

    /** Rc: ISA bit 31. */
    static boolean rcBit(int i) {
        return (i & 1) != 0;
    }

    /** OE: ISA bit 21. */
    static boolean oe(int i) {
        return (i & 0x400) != 0;
    }

    /** Signed 16-bit immediate (D/SI). */
    static long si(int i) {
        return (short) i;
    }

    /** Unsigned 16-bit immediate (UI). */
    static long ui(int i) {
        return i & 0xffffL;
    }

    /** DS field, sign-extended, low two bits cleared. */
    static long ds(int i) {
        return (short) (i & 0xfffc);
    }

    /** SPR field: the two 5-bit halves swapped. */
    static int spr(int i) {
        return ((i >>> 16) & 0x1f) | ((i >>> 6) & 0x3e0);
    }

    /** (RA|0): the register value, or zero when RA is 0. */
    static long raOr0(CpuState s, int i) {
        int ra = ra(i);
        return ra == 0 ? 0 : s.gpr[ra];
    }

    /** Truncate an effective address to the current computation mode. */
    static long ea(CpuState s, long ea) {
        return (s.msr < 0) ? ea : ea & 0xffffffffL;
    }

    /** Set the OV/OV32/SO bits after an OE=1 operation. */
    static void setOv(CpuState s, int ov, int ov32) {
        s.ov = ov;
        s.ov32 = ov32;
        s.so |= ov;
    }

    /** An invalid instruction form: illegal-instruction interrupt (not counted as unimplemented). */
    static final Op INVALID = (s, i) -> {
        throw PpcException.illegal();
    };

    /**
     * Register an instruction whose encodings with any of the {@code inval} bits set are invalid
     * forms, as in QEMU's opcode table (reserved fields must be zero there).
     */
    static void add(Reg t, int mask, int match, int inval, String name, Op op) {
        t.add(mask | inval, match, name, op);
        if (inval != 0) {
            t.add(mask, match, name + " (invalid form)", INVALID);
        }
    }

    /** X-form variant of {@link #add(Reg, int, int, int, String, Op)}. */
    static void addX(Reg t, int po, int xo, int inval, String name, Op op) {
        add(t, 0xfc000000 | (0x3ff << 1), (po << 26) | (xo << 1), inval, name, op);
    }
}
