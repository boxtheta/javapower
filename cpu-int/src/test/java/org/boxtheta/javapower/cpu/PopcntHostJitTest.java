package org.boxtheta.javapower.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.core.Spr;
import org.junit.jupiter.api.Test;

/**
 * The population count instructions still give the right answer once the host JIT has compiled
 * their handlers. What is under test is the host's optimized code for a handler, not the handler's
 * logic, so a guest loop runs each instruction a million times and sums the results: JDK 21's C2 on
 * aarch64 returned a zero upper word for {@code popcntw} written with {@code Integer.bitCount},
 * in the handler compiled on its own, as the interpreter calls it.
 */
class PopcntHostJitTest {
    static final long CODE = 0x1000;
    static final long MUL = 6364136223846793005L;
    static final int N = 1_000_000;

    private static int x(int rs, int ra, int rb, int xo) {
        return (31 << 26) | (rs << 21) | (ra << 16) | (rb << 11) | (xo << 1);
    }

    /** The number of one bits in each {@code width}-bit field of {@code v}, counted one at a time. */
    private static long perField(long v, int width) {
        long r = 0;
        for (int from = 0; from < 64; from += width) {
            long c = 0;
            for (int k = from; k < from + width; k++) c += (v >>> k) & 1;
            r |= c << from;
        }
        return r;
    }

    /** r5 = the sum over N values of r3 of the instruction's result; r3 steps through a sequence. */
    private static void check(String name, int xo, int width, boolean jit) {
        PhysicalMemory pm = new PhysicalMemory(4 << 20);
        CpuState s = new CpuState();
        DecodeTable table = new DecodeTable();
        table.addAll(new IntInsns());
        Interpreter cpu = new Interpreter(s, pm, table, Interpreter.Clock.DETERMINISTIC);
        cpu.resetPapr();
        cpu.compileBlocks = jit;
        s.spr[Spr.LPCR] |= Spr.LPCR_ILE;
        s.msr = Msr.SF | Msr.ME | Msr.LE;
        s.pc = CODE;
        int[] code = {
            x(3, 4, 0, xo),                // popcnt r4,r3
            x(5, 5, 4, 266),               // add r5,r5,r4
            x(3, 3, 6, 233),               // mulld r3,r3,r6
            (14 << 26) | (3 << 21) | (3 << 16) | 1, // addi r3,r3,1
            0x4200fff0,                    // bdnz -16
            0x44000002,                    // sc
        };
        for (int k = 0; k < code.length; k++) pm.write32le(CODE + 4L * k, code[k]);
        cpu.interruptHook = (st, vec, srr0) -> st.stopRequested = true;
        long v = 0x0123456789abcdefL;
        s.gpr[3] = v;
        s.gpr[5] = 0;
        s.gpr[6] = MUL;
        s.ctr = N;
        cpu.run(5L * N + 1);
        long want = 0;
        for (int k = 0; k < N; k++) {
            want += perField(v, width);
            v = v * MUL + 1;
        }
        assertEquals(Long.toHexString(want), Long.toHexString(s.gpr[5]), name + (jit ? ", compiled" : ", interpreted"));
    }

    @Test
    void popcntb() {
        check("popcntb", 122, 8, false);
        check("popcntb", 122, 8, true);
    }

    @Test
    void popcntw() {
        check("popcntw", 378, 32, false);
        check("popcntw", 378, 32, true);
    }

    @Test
    void popcntd() {
        check("popcntd", 506, 64, false);
        check("popcntd", 506, 64, true);
    }
}
