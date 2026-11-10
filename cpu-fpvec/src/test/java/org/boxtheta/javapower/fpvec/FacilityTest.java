package org.boxtheta.javapower.fpvec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.Arrays;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PpcException;
import org.junit.jupiter.api.Test;

/**
 * Facility-unavailable interrupts: every instruction needs MSR[FP], MSR[VEC] or MSR[VSX] (per
 * QEMU's rules), and raising the interrupt leaves all architectural state untouched.
 */
class FacilityTest {
    static final long[] VARIANTS = {Msr.FP, Msr.VEC, Msr.VSX, Msr.FP | Msr.VEC | Msr.VSX};

    @Test
    void unavailableTrapsBeforeAnyStateChange() throws IOException {
        DecodeTable t = GoldenTest.table();
        StringBuilder bad = new StringBuilder();
        int checked = 0;
        for (String g : GoldenTest.GROUPS) {
            boolean be = g.endsWith("_be");
            for (Golden.Case c : Golden.load(g)) {
                DecodeTable.Entry e = t.lookup(c.insn());
                if (e == null) continue;
                for (long off : VARIANTS) {
                    Golden.State in = new Golden.State();
                    in.apply(c.in());
                    CpuState s = new CpuState();
                    s.msr = (Msr.SF | (be ? 0 : Msr.LE) | Msr.FP | Msr.VEC | Msr.VSX | Msr.FE0 | Msr.FE1 | Msr.PR) & ~off;
                    TestMemory m = new TestMemory(s, Golden.MEMBASE, Golden.MEMSIZE);
                    System.arraycopy(in.mem, 0, m.bytes, 0, Golden.MEMSIZE);
                    s.mem = m;
                    s.fpscr = in.fpscr;
                    s.cr = in.cr;
                    s.vscr = in.vscr;
                    System.arraycopy(in.gpr, 0, s.gpr, 0, 32);
                    System.arraycopy(in.hi, 0, s.vsrHi, 0, 64);
                    System.arraycopy(in.lo, 0, s.vsrLo, 0, 64);
                    int vector = -1;
                    try {
                        e.op().exec(s, c.insn());
                    } catch (PpcException ex) {
                        vector = ex.vector;
                    }
                    boolean unavailable = vector == PpcException.FP_UNAVAILABLE || vector == PpcException.VEC_UNAVAILABLE
                            || vector == PpcException.VSX_UNAVAILABLE;
                    if (off == VARIANTS[3] && !unavailable) {
                        bad.append(String.format("%n  %s: runs with FP=VEC=VSX=0", c.asm()));
                    }
                    if (unavailable) {
                        checked++;
                        boolean same = s.fpscr == in.fpscr && s.cr == in.cr && s.vscr == in.vscr
                                && Arrays.equals(s.gpr, in.gpr) && Arrays.equals(s.vsrHi, in.hi)
                                && Arrays.equals(s.vsrLo, in.lo) && Arrays.equals(m.bytes, in.mem);
                        if (!same) bad.append(String.format("%n  %s: state modified before unavailable trap", c.asm()));
                    }
                    if (bad.length() > 4000) break;
                }
            }
        }
        assertEquals("", bad.toString());
        org.junit.jupiter.api.Assertions.assertTrue(checked > 10000);
    }
}
