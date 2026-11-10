package org.boxtheta.javapower.cpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.core.Spr;
import org.junit.jupiter.api.Test;

/**
 * Compiled blocks against the plain interpreter: the same program runs on two machines, one with
 * block compilation off and one compiling every block before its first execution, and must leave
 * identical architectural state, memory and interrupt history. The cases target the block
 * framing the golden vectors cannot reach: stores into the running block's own code, faults in
 * the middle of a block, decrementer interrupts due inside a block, an op from another module
 * that branches, both byte orders and both computation modes.
 */
class BlockCompilerTest {
    static final long CODE = 0x10000;
    static final long DATA = 0x20000;
    static final long HANDLER_DEC = 0x900;
    /** An unused primary opcode for the "other module" op. */
    static final int FOREIGN_PO = 5;

    /** One machine; {@code compiled} selects block compilation of every block. */
    static final class Machine {
        final PhysicalMemory pm = new PhysicalMemory(1 << 20);
        final CpuState s = new CpuState();
        final Interpreter cpu;
        final List<String> interrupts = new ArrayList<>();
        final boolean le;

        Machine(boolean compiled, boolean le, boolean sf) {
            this.le = le;
            DecodeTable t = new DecodeTable();
            t.addAll(new IntInsns());
            assertNull(t.lookup(FOREIGN_PO << 26));
            // Another module's op: r22 += 1, then skip the next instruction (a branch).
            t.addPrimary(FOREIGN_PO, "skip", (st, i) -> {
                st.gpr[22]++;
                st.npc = st.pc + 8;
            });
            s.hv = st -> st.gpr[3] = -2;
            cpu = new Interpreter(s, pm, t);
            cpu.resetPapr();
            if (compiled) {
                cpu.blockThreshold = 1;
            } else {
                cpu.compileBlocks = false;
            }
            s.spr[Spr.LPCR] |= le ? Spr.LPCR_ILE : 0;
            s.msr = Msr.ME | (sf ? Msr.SF : 0) | (le ? Msr.LE : 0);
            s.pc = CODE;
            cpu.interruptHook = (st, vec, srr0) -> {
                interrupts.add(Integer.toHexString(vec) + "@" + Long.toHexString(srr0) + "#" + st.icount);
                if (vec != HANDLER_DEC) {
                    st.stopRequested = true;
                }
            };
        }

        void load(long addr, int... code) {
            for (int k = 0; k < code.length; k++) {
                if (le) {
                    pm.write32le(addr + 4L * k, code[k]);
                } else {
                    pm.write32be(addr + 4L * k, code[k]);
                }
            }
        }
    }

    /** Run {@code setup} on an interpreted and a compiled machine for {@code n} instructions; compare. */
    static void same(boolean le, boolean sf, long n, Consumer<Machine> setup) {
        Machine a = new Machine(false, le, sf);
        Machine b = new Machine(true, le, sf);
        setup.accept(a);
        setup.accept(b);
        a.cpu.run(n);
        b.cpu.run(n);
        assertTrue(b.cpu.blocksRun > 0, "no compiled block ran");
        assertEquals(a.interrupts, b.interrupts, "interrupts");
        assertEquals(a.s.icount, b.s.icount, "icount");
        assertEquals(Long.toHexString(a.s.pc), Long.toHexString(b.s.pc), "pc");
        assertEquals(a.s.msr, b.s.msr, "msr");
        assertArrayEquals(a.s.gpr, b.s.gpr, "gpr");
        assertEquals(a.s.cr, b.s.cr, "cr");
        assertEquals(a.s.getXer(), b.s.getXer(), "xer");
        assertEquals(a.s.lr, b.s.lr, "lr");
        assertEquals(a.s.ctr, b.s.ctr, "ctr");
        assertArrayEquals(a.s.spr, b.s.spr, "spr");
        byte[] ma = new byte[0x20000];
        byte[] mb = new byte[0x20000];
        a.pm.read(CODE, ma, 0, ma.length);
        b.pm.read(CODE, mb, 0, mb.length);
        assertArrayEquals(ma, mb, "memory");
    }

    // ---- encoders (ISA field order)
    static int dform(int po, int rt, int ra, int d) {
        return (po << 26) | (rt << 21) | (ra << 16) | (d & 0xffff);
    }

    static int addi(int rt, int ra, int si) {
        return dform(14, rt, ra, si);
    }

    static int addis(int rt, int ra, int si) {
        return dform(15, rt, ra, si);
    }

    static int ori(int ra, int rs, int ui) {
        return dform(24, rs, ra, ui);
    }

    static int xform(int rt, int ra, int rb, int xo) {
        return (31 << 26) | (rt << 21) | (ra << 16) | (rb << 11) | (xo << 1);
    }

    static int add(int rt, int ra, int rb) {
        return xform(rt, ra, rb, 266);
    }

    static int xor(int ra, int rs, int rb) {
        return xform(rs, ra, rb, 316);
    }

    static int ld(int rt, int ra, int ds) {
        return (58 << 26) | (rt << 21) | (ra << 16) | (ds & 0xfffc);
    }

    static int std(int rs, int ra, int ds) {
        return (62 << 26) | (rs << 21) | (ra << 16) | (ds & 0xfffc);
    }

    static int stw(int rs, int ra, int d) {
        return dform(36, rs, ra, d);
    }

    static int lwz(int rt, int ra, int d) {
        return dform(32, rt, ra, d);
    }

    static int rlwinm(int ra, int rs, int sh, int mb, int me) {
        return (21 << 26) | (rs << 21) | (ra << 16) | (sh << 11) | (mb << 6) | (me << 1);
    }

    static int cmpdi(int ra, int si) {
        return (11 << 26) | (1 << 21) | (ra << 16) | (si & 0xffff);
    }

    static int sprField(int n) {
        return ((n & 0x1f) << 16) | ((n >>> 5) << 11);
    }

    static int mtspr(int n, int rs) {
        return (31 << 26) | (rs << 21) | sprField(n) | (467 << 1);
    }

    static int mfspr(int rt, int n) {
        return (31 << 26) | (rt << 21) | sprField(n) | (339 << 1);
    }

    /** bdnz to {@code target} from {@code from}. */
    static int bdnz(long from, long target) {
        return (16 << 26) | (16 << 21) | ((int) (target - from) & 0xfffc);
    }

    static int beq(long from, long target) {
        return (16 << 26) | (12 << 21) | (2 << 16) | ((int) (target - from) & 0xfffc);
    }

    static final int SC = 0x44000002;
    static final int RFID = 0x4c000024;
    static final int MTCTR_R3 = mtspr(Spr.CTR, 3);

    /** A counted loop of {@code body} (at CODE + 8), then sc. r3 = iterations. */
    static int[] loop(int iterations, int... body) {
        int[] p = new int[body.length + 4];
        p[0] = addi(3, 0, iterations);
        p[1] = MTCTR_R3;
        System.arraycopy(body, 0, p, 2, body.length);
        long bdnzAt = CODE + 4L * (2 + body.length);
        p[2 + body.length] = bdnz(bdnzAt, CODE + 8);
        p[3 + body.length] = SC;
        return p;
    }

    static final int[] MIX = {
        addi(20, 20, 1), ld(7, 6, 0), add(7, 7, 20), std(7, 6, 0), stw(20, 6, 8), lwz(8, 6, 8),
        xor(9, 9, 8), rlwinm(10, 9, 3, 0, 28), cmpdi(10, 0), mfspr(11, Spr.LR), mtspr(Spr.LR, 9),
    };

    @Test
    void loopsMatchInEveryMode() {
        for (boolean le : new boolean[] {true, false}) {
            for (boolean sf : new boolean[] {true, false}) {
                same(le, sf, 20_000, m -> {
                    m.load(CODE, loop(500, MIX));
                    m.s.gpr[6] = DATA;
                });
            }
        }
    }

    @Test
    void storeIntoTheRunningBlock() {
        // The loop rewrites an instruction further down its own block on every iteration,
        // alternating between an add and a multiply (so the order of execution shows in the
        // result); the rewritten instruction must take effect at once.
        int w1 = addi(21, 21, 1);
        int w2 = dform(7, 21, 21, 3); // mulli r21,r21,3
        for (boolean le : new boolean[] {true, false}) {
            same(le, true, 10_000, m -> {
                // body: stw r5,0(r4); xor r5,r5,r12; addi r22,r22,1; <target>; addi r23,r23,1
                m.load(CODE, loop(300, stw(5, 4, 0), xor(5, 5, 12), addi(22, 22, 1), w1, addi(23, 23, 1)));
                m.s.gpr[4] = CODE + 8 + 12; // the target slot
                m.s.gpr[5] = w2;
                m.s.gpr[12] = w1 ^ w2;
            });
        }
    }

    @Test
    void faultInTheMiddleOfABlock() {
        // A load from beyond guest RAM raises a machine check in the middle of the block.
        same(true, true, 10_000, m -> {
            m.load(CODE, addi(20, 20, 1), addi(21, 21, 2), ld(7, 9, 0), addi(22, 22, 3), addi(23, 23, 4), SC);
            m.s.gpr[9] = 0x40000000L;
        });
        // A trap partway through a loop body, taken on the last iteration only.
        same(true, true, 10_000, m -> {
            // tdeqi r3? use: cmpdi then a trap word: tdi 4 (equal), r3, 1 -> traps when ctr reg r3 == 1
            m.load(CODE, loop(50, addi(20, 20, 1), mfspr(3, Spr.CTR), (2 << 26) | (4 << 21) | (3 << 16) | 1,
                    addi(21, 21, 1)));
        });
    }

    @Test
    void decrementerDueInsideBlocks() {
        // DEC interrupts land at many different offsets inside the loop's block; the handler
        // counts them and re-arms DEC. Times are icount-based, so both runs must agree exactly.
        for (int dec : new int[] {7, 37, 101}) {
            same(true, true, 30_000, m -> {
                m.load(HANDLER_DEC, addi(24, 24, 1), mtspr(Spr.DEC, 25), RFID);
                m.load(CODE, loop(2000, MIX));
                m.s.gpr[6] = DATA;
                m.s.gpr[25] = dec;
                m.s.msr |= Msr.EE;
                m.cpu.storeDec(dec);
            });
        }
    }

    @Test
    void foreignOpThatBranches() {
        // FOREIGN_PO skips the next instruction; the block must not run the skipped one.
        same(true, true, 10_000, m -> m.load(CODE,
                loop(200, addi(20, 20, 1), FOREIGN_PO << 26, addi(21, 21, 1000), addi(23, 23, 1))));
    }

    @Test
    void randomStraightLineCode() {
        Random r = new Random(42);
        for (int round = 0; round < 40; round++) {
            int n = 3 + r.nextInt(25);
            int[] body = new int[n];
            for (int k = 0; k < n; k++) {
                int rt = 14 + r.nextInt(10);
                int ra = 14 + r.nextInt(10);
                int rb = 14 + r.nextInt(10);
                body[k] = switch (r.nextInt(10)) {
                    case 0 -> addi(rt, ra, r.nextInt(65536) - 32768);
                    case 1 -> addis(rt, ra, r.nextInt(65536) - 32768);
                    case 2 -> add(rt, ra, rb);
                    case 3 -> xor(rt, ra, rb);
                    case 4 -> rlwinm(rt, ra, r.nextInt(32), r.nextInt(32), r.nextInt(32));
                    case 5 -> ld(rt, 6, 8 * r.nextInt(64));
                    case 6 -> std(rt, 6, 8 * r.nextInt(64));
                    case 7 -> stw(rt, 6, 4 * r.nextInt(128));
                    case 8 -> cmpdi(ra, r.nextInt(100));
                    default -> ori(rt, ra, r.nextInt(65536));
                };
            }
            boolean le = r.nextBoolean();
            same(le, true, 5_000, m -> {
                m.load(CODE, loop(100, body));
                m.s.gpr[6] = DATA;
                for (int g = 14; g < 24; g++) {
                    m.s.gpr[g] = g * 0x0123456789abcdefL;
                }
            });
        }
    }

    @Test
    void conditionalBranchesInsideLoops() {
        // A forward conditional branch splits the body; both paths become blocks.
        same(true, true, 20_000, m -> {
            long beqAt = CODE + 8 + 8;
            m.load(CODE, loop(400, rlwinm(10, 20, 0, 31, 31), cmpdi(10, 0), beq(beqAt, beqAt + 12),
                    addi(21, 21, 1), addi(22, 22, 1), addi(20, 20, 1), addi(23, 23, 1)));
        });
    }
}
