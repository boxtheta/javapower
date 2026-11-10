package org.boxtheta.javapower.fpvec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PpcException;
import org.junit.jupiter.api.Test;

/**
 * With MSR[FE0] = MSR[FE1] = 0 an enabled FP exception never interrupts: FEX is set and execution
 * continues (QEMU in system mode writes the target in that case). Every golden case that trapped
 * under QEMU user mode must therefore complete without a program interrupt and leave FEX set.
 */
class NoTrapTest {
    @Test
    void enabledExceptionsDoNotTrapWithFeZero() throws IOException {
        DecodeTable t = GoldenTest.table();
        StringBuilder bad = new StringBuilder();
        int n = 0;
        for (String g : GoldenTest.GROUPS) {
            boolean be = g.endsWith("_be");
            for (Golden.Case c : Golden.load(g)) {
                Golden.State exp = new Golden.State();
                exp.apply(c.in());
                exp.apply(c.out());
                if (exp.sig != 8) continue;
                Golden.State in = new Golden.State();
                in.apply(c.in());
                CpuState s = new CpuState();
                s.msr = Msr.SF | (be ? 0 : Msr.LE) | Msr.FP | Msr.VEC | Msr.VSX | Msr.PR;
                TestMemory m = new TestMemory(s, Golden.MEMBASE, Golden.MEMSIZE);
                System.arraycopy(in.mem, 0, m.bytes, 0, Golden.MEMSIZE);
                s.mem = m;
                s.fpscr = in.fpscr;
                s.cr = in.cr;
                s.vscr = in.vscr;
                System.arraycopy(in.gpr, 0, s.gpr, 0, 32);
                System.arraycopy(in.hi, 0, s.vsrHi, 0, 64);
                System.arraycopy(in.lo, 0, s.vsrLo, 0, 64);
                n++;
                try {
                    t.lookup(c.insn()).op().exec(s, c.insn());
                } catch (PpcException ex) {
                    bad.append(String.format("%n  %s: interrupt 0x%x", c.asm(), ex.vector));
                    continue;
                }
                if ((s.fpscr & Fpscr.FEX) == 0) bad.append(String.format("%n  %s: FEX clear", c.asm()));
            }
        }
        assertTrue(n > 1000, "only " + n + " trapping cases");
        assertEquals("", bad.toString());
    }
}
