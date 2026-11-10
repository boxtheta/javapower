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

    /** Interpreter only; first tier (blocks) only; both tiers (regions first). */
    static final int INTERP = 0;
    static final int BLOCKS = 1;
    static final int REGIONS = 2;
    /** First-tier blocks promoted to regions after a few runs. */
    static final int PROMOTED = 3;

    /** One machine; {@code tier} selects how code runs: compiled before its first execution, or not at all. */
    static final class Machine {
        final PhysicalMemory pm = new PhysicalMemory(1 << 20);
        final CpuState s = new CpuState();
        final Interpreter cpu;
        final List<String> interrupts = new ArrayList<>();
        final boolean le;

        Machine(boolean compiled, boolean le, boolean sf) {
            this(compiled ? REGIONS : INTERP, le, sf);
        }

        Machine(int tier, boolean le, boolean sf) {
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
            if (tier != INTERP) {
                cpu.blockThreshold = 1;
                cpu.compileRegions = tier != BLOCKS;
                cpu.regionThreshold = tier == PROMOTED ? 5 : 1;
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

    /**
     * Run {@code setup} on an interpreted machine and on one per compilation tier for {@code n}
     * instructions; compare.
     */
    static void same(boolean le, boolean sf, long n, Consumer<Machine> setup) {
        Machine a = new Machine(INTERP, le, sf);
        a.cpu.trace = null;
        setup.accept(a);
        a.cpu.run(n);
        for (int tier : new int[] {BLOCKS, REGIONS, PROMOTED}) {
            Machine b = new Machine(tier, le, sf);
            setup.accept(b);
            b.cpu.run(n);
            assertTrue(b.cpu.blocksRun > 0, "no compiled block ran");
            if (tier == REGIONS) {
                assertTrue(b.cpu.regionsCompiled > 0, "no region compiled");
            }
            compare(a, b, "tier " + tier + ": ");
        }
    }

    static void compare(Machine a, Machine b, String what) {
        assertEquals(a.interrupts, b.interrupts, what + "interrupts");
        assertEquals(a.s.icount, b.s.icount, what + "icount");
        assertEquals(Long.toHexString(a.s.pc), Long.toHexString(b.s.pc), what + "pc");
        assertEquals(a.s.msr, b.s.msr, what + "msr");
        assertArrayEquals(a.s.gpr, b.s.gpr, what + "gpr");
        assertEquals(a.s.cr, b.s.cr, what + "cr");
        assertEquals(a.s.getXer(), b.s.getXer(), what + "xer");
        assertEquals(a.s.lr, b.s.lr, what + "lr");
        assertEquals(a.s.ctr, b.s.ctr, what + "ctr");
        assertArrayEquals(a.s.spr, b.s.spr, what + "spr");
        byte[] ma = new byte[0x20000];
        byte[] mb = new byte[0x20000];
        a.pm.read(CODE, ma, 0, ma.length);
        b.pm.read(CODE, mb, 0, mb.length);
        assertArrayEquals(ma, mb, what + "memory");
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
    void handlerThatRaisesAnInterruptCondition() {
        // mtspr DEC with a negative value makes a decrementer interrupt pending at once; the run
        // loop delivers it after that instruction, so compiled code must stop there too.
        for (boolean le : new boolean[] {true, false}) {
            same(le, true, 20_000, m -> {
                m.load(HANDLER_DEC, addi(24, 24, 1), mtspr(Spr.DEC, 25), RFID);
                m.load(CODE, loop(300, addi(20, 20, 1), mtspr(Spr.DEC, 26), addi(21, 21, 1), addi(22, 22, 1)));
                m.s.gpr[25] = 0x7fffffffL;
                m.s.gpr[26] = -1L;
                m.s.msr |= Msr.EE;
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

    /** Writes into guest memory the way a device or another CPU would, bypassing the running CPU. */
    interface Writer {
        void write(Machine m, long addr, int word);
    }

    static byte[] wordBytes(Machine m, int word) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(4)
                .order(m.le ? java.nio.ByteOrder.LITTLE_ENDIAN : java.nio.ByteOrder.BIG_ENDIAN);
        return bb.putInt(word).array();
    }

    /**
     * Run a compiled loop, change one of its instructions with {@code w} while the CPU is stopped,
     * then run it again: the new instruction must take effect. With {@code icbi}, the second run
     * first executes {@code icbi} on the changed word and {@code isync} (the guest's
     * cross-modification protocol), for writers that bypass code-write tracking.
     */
    static void rewriteBetweenRuns(Writer w, boolean icbi) {
        rewriteBetweenRuns(w, icbi, BLOCKS);
        rewriteBetweenRuns(w, icbi, REGIONS);
    }

    static void rewriteBetweenRuns(Writer w, boolean icbi, int tier) {
        for (boolean le : new boolean[] {true, false}) {
            Machine a = new Machine(INTERP, le, true);
            Machine b = new Machine(tier, le, true);
            long target = CODE + 8 + 4;
            long stub = CODE + 0x800;
            for (Machine m : List.of(a, b)) {
                m.load(CODE, loop(50, addi(20, 20, 1), addi(21, 21, 1), addi(22, 22, 1)));
                m.load(stub, xform(0, 0, 4, 982), 0x4c00012c, (18 << 26) | ((int) (CODE - stub - 8) & 0x3fffffc));
                m.s.gpr[4] = target;
                m.cpu.run(10_000);
                long runs = m.cpu.blocksRun;
                w.write(m, target, dform(7, 21, 21, 3)); // mulli r21,r21,3
                m.s.stopRequested = false;
                m.s.pc = icbi ? stub : CODE;
                m.cpu.run(10_000);
                if (m == b) {
                    assertTrue(m.cpu.blocksRun > runs, "no compiled block ran after the write");
                }
            }
            assertEquals(a.interrupts, b.interrupts, "interrupts");
            assertEquals(a.s.icount, b.s.icount, "icount");
            assertArrayEquals(a.s.gpr, b.s.gpr, "gpr");
        }
    }

    @Test
    void deviceWritesIntoCompiledCode() {
        rewriteBetweenRuns((m, a, v) -> m.pm.write(a, wordBytes(m, v), 0, 4), false);
        rewriteBetweenRuns((m, a, v) -> {
            if (m.le) {
                m.pm.write32le(a, v);
            } else {
                m.pm.write32be(a, v);
            }
        }, false);
        rewriteBetweenRuns((m, a, v) -> {
            byte[] w = wordBytes(m, v);
            for (int k = 0; k < 4; k++) {
                m.pm.fill(a + k, 1, w[k]);
            }
        }, false);
        rewriteBetweenRuns((m, a, v) -> {
            int old = m.le ? m.pm.read32le(a) : m.pm.read32be(a);
            assertTrue(m.le ? m.pm.cas32le(a, old, v) : m.pm.cas32be(a, old, v));
        }, false);
    }

    @Test
    void icbiDropsCodeChangedBehindTheTracking() {
        // A write straight to the segment is not tracked (like a store racing with the compile on
        // another CPU); icbi on the changed block makes the change visible.
        rewriteBetweenRuns((m, a, v) -> m.pm.segment().set(m.le ? java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED
                .withOrder(java.nio.ByteOrder.LITTLE_ENDIAN) : java.lang.foreign.ValueLayout.JAVA_INT_UNALIGNED
                .withOrder(java.nio.ByteOrder.BIG_ENDIAN), a, v), true);
    }

    /**
     * Compiled code does not depend on its effective address: the same real code run at a second
     * address (here the real-mode alias with the top bits set) reuses it, with the results the
     * interpreter gives.
     */
    @Test
    void codeIsSharedAcrossAddresses() {
        for (int tier : new int[] {BLOCKS, REGIONS}) {
            Machine a = new Machine(INTERP, true, true);
            Machine b = new Machine(tier, true, true);
            for (Machine m : List.of(a, b)) {
                m.load(CODE, loop(300, MIX));
                m.s.gpr[6] = DATA;
                m.cpu.run(20_000);
                m.s.stopRequested = false;
                m.s.pc = 0xc000000000000000L | CODE;
                m.cpu.run(20_000);
            }
            assertTrue(b.cpu.sharedHits > 0, "tier " + tier + ": code not reused at the second address");
            assertEquals(1, b.cpu.dupCompiles == 0 ? 1 : 0, "tier " + tier + ": recompiled");
            compare(a, b, "tier " + tier + ": ");
        }
    }

    /** A CPU on {@code d} set up like {@link Machine}, compiling at once. */
    static Interpreter cpuOn(CpuDomain d, boolean regions, long data) {
        CpuState st = new CpuState();
        DecodeTable t = new DecodeTable();
        t.addAll(new IntInsns());
        Interpreter in = new Interpreter(st, d, t);
        in.resetPapr();
        in.blockThreshold = 1;
        in.regionThreshold = 1;
        in.compileRegions = regions;
        st.hv = x -> x.gpr[3] = -2;
        st.msr = Msr.ME | Msr.SF | Msr.LE;
        st.spr[Spr.LPCR] |= Spr.LPCR_ILE;
        st.pc = CODE;
        st.gpr[6] = data;
        in.interruptHook = (x, vec, srr0) -> x.stopRequested = true;
        return in;
    }

    /** A second CPU of the domain runs the code the first compiled, without compiling it again. */
    @Test
    void codeIsSharedBetweenCpus() {
        for (boolean regions : new boolean[] {false, true}) {
            Machine ref = new Machine(INTERP, true, true);
            ref.load(CODE, loop(300, MIX));
            ref.s.gpr[6] = DATA;
            ref.cpu.run(20_000);

            PhysicalMemory pm = new PhysicalMemory(1 << 20);
            CpuDomain d = new CpuDomain(pm, Interpreter.Clock.WALL);
            Interpreter a = cpuOn(d, regions, DATA);
            Interpreter b = cpuOn(d, regions, DATA + 0x1000);
            int[] code = loop(300, MIX);
            for (int k = 0; k < code.length; k++) {
                pm.write32le(CODE + 4L * k, code[k]);
            }
            a.run(20_000);
            b.run(20_000);
            assertTrue(b.sharedHits > 0, "regions=" + regions + ": nothing shared");
            assertEquals(0, b.blocksCompiled + b.regionsCompiled, "regions=" + regions + ": compiled again");
            for (Interpreter in : List.of(a, b)) {
                CpuState st = in.state();
                assertEquals(ref.s.icount, st.icount, "icount");
                long[] want = ref.s.gpr.clone();
                long[] got = st.gpr.clone();
                want[6] = got[6] = 0;
                assertArrayEquals(want, got, "gpr");
                assertEquals(ref.s.cr, st.cr, "cr");
                assertEquals(ref.s.lr, st.lr, "lr");
            }
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
