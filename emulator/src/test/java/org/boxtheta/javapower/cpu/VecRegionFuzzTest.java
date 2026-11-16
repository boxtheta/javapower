package org.boxtheta.javapower.cpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.MmioBus;
import org.boxtheta.javapower.core.MmioHandler;
import org.boxtheta.javapower.core.MmioRegion;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.core.Spr;
import org.boxtheta.javapower.fpvec.FpuSets;
import org.junit.jupiter.api.Test;

/**
 * Randomised differential test of the compilation tiers against the interpreter for the VMX and
 * VSX loads and stores, which blocks and regions make through their handlers. Each case is a random loop mixing every vector load and store form with
 * integer address arithmetic, integer loads and stores and forward branches. Its addresses are
 * aligned and unaligned, in ordinary pages, crossing page ends, walking towards unmapped,
 * read-only and beyond-RAM pages, in MMIO, in a page holding translated code and in the loop's
 * own page; the vector facilities are sometimes off; decrementer interrupts arrive at random
 * instruction counts. It runs in both byte orders and computation modes, with translation on
 * (quadrants 0 and 3) and off, in supervisor and problem state. The interpreted and compiled
 * machines must end in identical state: registers (VSRs included), SPRs, interrupt history,
 * memory and the MMIO accesses made.
 */
class VecRegionFuzzTest {
    static final long RAM = 1 << 20;
    static final long CODE = 0x10000;
    /**
     * A subroutine the loop calls, at the start of a page the loop's stores also write: stores
     * from the end of the page before cross into it and may change the subroutine's code.
     */
    static final long SUB = 0x22000;
    static final long DATA = 0x20000;
    static final long UNMAPPED = 0x23000;
    static final long READONLY = 0x24000;
    static final long MMIO = RAM; // the page just past RAM (effective = real address)
    static final long PRTB = 0x40000;
    static final long ROOT = 0x50000;
    static final long T1 = 0x60000;
    static final long T2 = 0x61000;
    static final long T3 = 0x62000;

    static final int INTERP = 0;
    static final int BLOCKS = 1;
    static final int REGIONS = 2;
    static final int PROMOTED = 3;

    record Mode(boolean le, boolean sf, boolean dr, boolean pr, boolean q3, boolean dec, long facilities) {}

    static final class Machine {
        final PhysicalMemory pm = new PhysicalMemory(RAM);
        final CpuState s = new CpuState();
        final MmioBus bus = new MmioBus();
        final Interpreter cpu;
        final List<String> interrupts = new ArrayList<>();
        final StringBuilder mmio = new StringBuilder();

        Machine(int tier, Mode m) {
            DecodeTable t = new DecodeTable();
            t.addAll(new IntInsns());
            FpuSets.registerAll(t);
            bus.map(new MmioRegion("t", MMIO, 0x1000, ByteOrder.LITTLE_ENDIAN, new MmioHandler() {
                @Override
                public long read(long offset, int size) {
                    mmio.append('r').append(offset).append('/').append(size).append(' ');
                    return 0x0123456789abcdefL * (offset + 3) >>> (64 - 8 * size);
                }

                @Override
                public void write(long offset, int size, long value) {
                    mmio.append('w').append(offset).append('/').append(size).append('=')
                            .append(Long.toHexString(value)).append(' ');
                }
            }));
            s.hv = st -> st.gpr[3] = -2;
            cpu = new Interpreter(s, new CpuDomain(pm, bus, Interpreter.Clock.DETERMINISTIC, 1.0, 1), t);
            cpu.resetPapr();
            switch (tier) {
                case INTERP -> cpu.compileBlocks = false;
                case BLOCKS -> {
                    cpu.blockThreshold = 1;
                    cpu.compileRegions = false;
                }
                case REGIONS -> {
                    cpu.blockThreshold = 1;
                    cpu.regionThreshold = 1;
                }
                default -> {
                    cpu.blockThreshold = 1;
                    cpu.regionThreshold = 4;
                }
            }
            s.spr[Spr.LPCR] |= (m.le ? Spr.LPCR_ILE : 0) | Spr.LPCR_HR | Spr.LPCR_UPRT | Spr.LPCR_GTSE;
            s.procTable = PRTB;
            pageTables();
            s.msr = Msr.ME | (m.sf ? Msr.SF : 0) | (m.le ? Msr.LE : 0) | (m.dr ? Msr.IR | Msr.DR : 0)
                    | (m.pr ? Msr.PR : 0) | (m.dec ? Msr.EE : 0) | m.facilities;
            s.pc = CODE;
            cpu.interruptHook = (st, vec, srr0) -> {
                interrupts.add(Integer.toHexString(vec) + "@" + Long.toHexString(srr0) + "#" + st.icount);
                if (vec != 0x900) {
                    st.stopRequested = true;
                }
            };
        }

        void pageTables() {
            long leaf = RadixMmu.PTE_VALID | RadixMmu.PTE_LEAF | RadixMmu.PTE_R | RadixMmu.PTE_C
                    | RadixMmu.PTE_EAA_RW | RadixMmu.PTE_EAA_R | RadixMmu.PTE_EAA_X;
            pm.write64be(PRTB, (1L << 62) | 0xA0 | ROOT | 13);
            pm.write64be(ROOT, RadixMmu.PTE_VALID | T1 | 9);
            pm.write64be(T1, RadixMmu.PTE_VALID | T2 | 9);
            pm.write64be(T2, RadixMmu.PTE_VALID | T3 | 9);
            for (int k = 0; k < 512; k++) {
                long pa = 4096L * k; // pages past RAM: MMIO (the first), else unassigned
                long pte = leaf | pa;
                if (pa == UNMAPPED) {
                    pte = 0;
                } else if (pa == READONLY) {
                    pte &= ~RadixMmu.PTE_EAA_RW;
                }
                pm.write64be(T3 + 8L * k, pte);
            }
        }

        void load(long addr, List<Integer> code, boolean le) {
            for (int k = 0; k < code.size(); k++) {
                if (le) {
                    pm.write32le(addr + 4L * k, code.get(k));
                } else {
                    pm.write32be(addr + 4L * k, code.get(k));
                }
            }
        }
    }

    // ---- encoders (ISA field order)

    static int d(int po, int rt, int ra, int imm) {
        return (po << 26) | (rt << 21) | (ra << 16) | (imm & 0xffff);
    }

    static int x(int rt, int ra, int rb, int xo, int low) {
        return (31 << 26) | (rt << 21) | (ra << 16) | (rb << 11) | (xo << 1) | low;
    }

    static int spr(int n) {
        return ((n & 0x1f) << 16) | ((n >>> 5) << 11);
    }

    static int mtspr(int n, int rs) {
        return (31 << 26) | (rs << 21) | spr(n) | (467 << 1);
    }

    static int bc(int bo, int bi, long from, long to, int lk) {
        return (16 << 26) | (bo << 21) | (bi << 16) | ((int) (to - from) & 0xfffc) | lk;
    }

    static int b(long from, long to, int lk) {
        return (18 << 26) | ((int) (to - from) & 0x3fffffc) | lk;
    }

    static final int SC = 0x44000002;
    static final int RFID = 0x4c000024;
    static final int BLR = 0x4e800020;

    /** The quadword forms regions make directly, by X-form extended opcode. */
    static final int[] QUAD_X = {103, 359, 231, 487, 268, 300, 396, 844, 972, 780, 908, 876, 1004};
    /** The other X forms (handler calls): element, scalar, splat, h8x and length-controlled forms. */
    static final int[] OTHER_X = {7, 39, 71, 135, 167, 199, 6, 38, 588, 76, 12, 524, 781, 813, 716, 140, 652, 909,
        941, 812, 940, 332, 364};
    static final int[] LEN_X = {269, 301, 397, 429};

    // r18: counted by the subroutine, r19: length for lxvl & co, r20: before the subroutine's page, r21: MMIO, r22: link save,
    // r24: decrementer count, r25: index, r26: data base, r27: page-crossing base, r28: scratch base,
    // r29: walking pointer, r30: own code page, r31: loop counter.
    static final int[] BASES = {26, 26, 26, 27, 27, 29, 29, 30, 20, 21};
    static final int[] DST = {3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18};

    static final class Gen {
        final Random r;
        final List<Integer> code = new ArrayList<>();
        final long base;

        Gen(Random r, long base) {
            this.r = r;
            this.base = base;
        }

        long pc() {
            return base + 4L * code.size();
        }

        void emit(int w) {
            code.add(w);
        }

        void random() {
            int k = r.nextInt(100);
            if (k < 55) {
                quad();
            } else if (k < 70) {
                other();
            } else if (k < 82) {
                arith();
            } else if (k < 92) {
                intMem();
            } else {
                branch();
            }
        }

        int base() {
            int b = BASES[r.nextInt(BASES.length)];
            // MMIO (a misaligned access stops the case) and the subroutine's page: rarer
            return (b == 21 || b == 20) && r.nextInt(3) != 0 ? 26 : b;
        }

        int vreg() {
            return r.nextInt(4) == 0 ? r.nextInt(64) : (r.nextBoolean() ? 32 : 0) + r.nextInt(4);
        }

        void quad() {
            int t = vreg();
            if (r.nextInt(16) == 0) {
                // A quadword store at r20, which crosses into the subroutine's page.
                int[] st = {396, 972, 908, 1004};
                emit(r.nextInt(4) == 0
                        ? (61 << 26) | ((t & 31) << 21) | (20 << 16) | ((t >> 5) << 3) | 5 // stxv
                        : x(t & 31, 0, 20, st[r.nextInt(st.length)], t >> 5));
                return;
            }
            int ra = base();
            if (r.nextInt(5) == 0) {
                // lxv / stxv: DQ = small multiple of 16
                int dq = (r.nextInt(16) - 4) * 16;
                emit((61 << 26) | ((t & 31) << 21) | (ra << 16) | (dq & 0xfff0) | ((t >> 5) << 3)
                        | (r.nextBoolean() ? 1 : 5));
                return;
            }
            int xo = QUAD_X[r.nextInt(QUAD_X.length)];
            if (r.nextInt(6) == 0) {
                emit(x(t & 31, 0, ra, xo, t >> 5)); // RA = 0: EA = (RB)
            } else {
                emit(x(t & 31, ra, 25, xo, t >> 5));
            }
        }

        void other() {
            int t = vreg();
            int ra = base();
            switch (r.nextInt(5)) {
                case 0 -> emit(x(t & 31, ra, 19, LEN_X[r.nextInt(LEN_X.length)], t >> 5));
                case 1 -> {
                    int po = r.nextBoolean() ? 57 : 61;
                    int ds = (r.nextInt(32) - 8) * 4;
                    emit((po << 26) | ((t & 31) << 21) | (ra << 16) | (ds & 0xfffc) | (2 + r.nextInt(2)));
                }
                default -> emit(x(t & 31, ra, 25, OTHER_X[r.nextInt(OTHER_X.length)], t >> 5));
            }
        }

        void arith() {
            switch (r.nextInt(5)) {
                case 0 -> emit(d(14, 25, 25, r.nextInt(5) == 0 ? r.nextInt(16) - 8 : 16 * (r.nextInt(5) - 2)));
                case 1 -> emit(d(14, 25, 0, r.nextInt(0x100) & (r.nextBoolean() ? ~15 : ~0))); // li r25
                case 2 -> emit(d(14, 28, 26, r.nextInt(0x200))); // r28 = r26 + n
                case 3 -> emit(x(DST[r.nextInt(DST.length)], DST[r.nextInt(DST.length)], DST[r.nextInt(DST.length)],
                        266, 0)); // add
                default -> emit(d(14, DST[r.nextInt(DST.length)], 0, r.nextInt(65536) - 32768));
            }
        }

        void intMem() {
            int ra = base();
            int rt = DST[r.nextInt(DST.length)];
            switch (r.nextInt(4)) {
                case 0 -> emit(d(58, rt, ra, r.nextInt(0x40) & ~3)); // ld
                case 1 -> emit(d(62, rt, ra, r.nextInt(0x40) & ~3)); // std
                case 2 -> emit(x(rt, ra, 25, 21, 0)); // ldx
                default -> emit(x(rt, ra, 25, 149, 0)); // stdx
            }
        }

        void branch() {
            if (r.nextInt(4) == 0) {
                emit(b(pc(), SUB, 1)); // bl SUB: counts in r18 and returns, unless stores changed it
                return;
            }
            int skip = 1 + r.nextInt(2);
            long at = pc();
            emit(bc(new int[] {4, 12, 20}[r.nextInt(3)], r.nextInt(32), at, at + 4L * (skip + 1), 0));
            for (int k = 0; k < skip; k++) {
                quad();
            }
        }
    }

    static List<Integer> program(Random r) {
        Gen g = new Gen(r, CODE);
        int iterations = 30 + r.nextInt(300);
        g.emit(d(14, 31, 0, iterations)); // li r31, n
        long loop = g.pc();
        int n = 4 + r.nextInt(40);
        for (int k = 0; k < n; k++) {
            g.random();
        }
        g.emit(d(14, 29, 29, 0x40)); // r29 += 64: towards the unmapped and read-only pages
        g.emit(d(14, 31, 31, -1));
        g.emit((11 << 26) | (7 << 23) | (1 << 21) | (31 << 16)); // cmpdi cr7, r31, 0
        g.emit(bc(4, 30, g.pc(), loop, 0)); // bne cr7, loop
        g.emit(SC);
        return g.code;
    }

    static void setup(Machine m, Random r, List<Integer> code, Mode mode) {
        byte[] data = new byte[0x6000];
        r.nextBytes(data);
        m.pm.write(DATA, data, 0, data.length);
        m.load(CODE, code, mode.le);
        m.load(SUB, List.of(d(14, 18, 18, 1), d(14, 18, 18, 2), d(14, 18, 18, 4), BLR), mode.le);
        m.load(0x900, List.of(d(14, 24, 24, 1), mtspr(Spr.DEC, 23), RFID), mode.le);
        for (int k = 0; k < 32; k++) {
            m.s.gpr[k] = r.nextLong();
        }
        for (int v = 0; v < 64; v++) {
            m.s.vsrHi[v] = r.nextLong();
            m.s.vsrLo[v] = r.nextLong();
        }
        long q = mode.q3 ? 0xC000000000000000L : 0;
        m.s.gpr[19] = ((long) new int[] {0, 1, 7, 8, 15, 16, 17, 255, r.nextInt(17)}[r.nextInt(9)] << 56)
                | (r.nextLong() >>> 8);
        m.s.gpr[20] = q | (SUB - 1 - r.nextInt(15));
        m.s.gpr[21] = MMIO + 0x100;
        m.s.gpr[23] = 30 + r.nextInt(200);
        m.s.gpr[25] = r.nextInt(3) == 0 ? r.nextInt(0x80) : 16L * r.nextInt(8);
        m.s.gpr[26] = q | (DATA + 0x100 + (r.nextBoolean() ? 0 : r.nextInt(16)));
        m.s.gpr[27] = q | (DATA + 0x1000 - 8 - r.nextInt(24));
        m.s.gpr[28] = m.s.gpr[26];
        m.s.gpr[29] = q | (DATA + 0x100L * r.nextInt(32) + (r.nextBoolean() ? 0 : r.nextInt(16)));
        m.s.gpr[30] = CODE + 0xf00;
        if (!mode.sf) {
            for (int k : new int[] {20, 21, 26, 27, 28, 29, 30}) {
                m.s.gpr[k] |= r.nextLong() & 0xffffffff00000000L; // ignored in 32-bit mode
            }
        }
        m.s.cr = r.nextInt();
        if (mode.dec) {
            m.cpu.storeDec(5 + r.nextInt(60));
        }
    }

    static void compare(Machine a, Machine b, String what) {
        assertEquals(a.interrupts, b.interrupts, what + "interrupts");
        assertEquals(a.s.icount, b.s.icount, what + "icount");
        assertEquals(Long.toHexString(a.s.pc), Long.toHexString(b.s.pc), what + "pc");
        assertEquals(a.s.msr, b.s.msr, what + "msr");
        assertArrayEquals(a.s.gpr, b.s.gpr, what + "gpr");
        assertArrayEquals(a.s.vsrHi, b.s.vsrHi, what + "vsr (dw0)");
        assertArrayEquals(a.s.vsrLo, b.s.vsrLo, what + "vsr (dw1)");
        assertEquals(a.s.cr, b.s.cr, what + "cr");
        assertEquals(a.s.getXer(), b.s.getXer(), what + "xer");
        assertEquals(a.s.lr, b.s.lr, what + "lr");
        assertEquals(a.s.ctr, b.s.ctr, what + "ctr");
        assertArrayEquals(a.s.spr, b.s.spr, what + "spr");
        assertEquals(a.mmio.toString(), b.mmio.toString(), what + "mmio");
        byte[] ma = new byte[(int) RAM];
        byte[] mb = new byte[(int) RAM];
        a.pm.read(0, ma, 0, ma.length);
        b.pm.read(0, mb, 0, mb.length);
        assertArrayEquals(ma, mb, what + "memory");
    }

    /** How the interpreted runs ended (last interrupt vector, or "limit"). */
    static final java.util.Map<String, Integer> ENDINGS = new java.util.concurrent.ConcurrentSkipListMap<>();

    static long[] run(int seed, Mode mode, long insns) {
        Random r0 = new Random(seed);
        List<Integer> code = program(r0);
        long setupSeed = r0.nextLong();
        Machine ref = new Machine(INTERP, mode);
        setup(ref, new Random(setupSeed), code, mode);
        ref.cpu.run(insns);
        String end = ref.s.stopRequested && !ref.interrupts.isEmpty()
                ? ref.interrupts.get(ref.interrupts.size() - 1).replaceAll("@.*", "") : "limit";
        ENDINGS.merge(end, 1, Integer::sum);
        long[] stats = new long[2];
        for (int tier : new int[] {REGIONS, PROMOTED, BLOCKS}) {
            Machine m = new Machine(tier, mode);
            setup(m, new Random(setupSeed), code, mode);
            m.cpu.run(insns);
            compare(ref, m, "seed " + seed + " " + mode + " tier " + tier + ": ");
            if (tier == REGIONS) {
                stats[0] += m.cpu.regionsCompiled;
                stats[1] += ref.s.icount;
            }
        }
        return stats;
    }

    static void campaign(int seeds, boolean dr, boolean pr, int salt) {
        long regions = 0;
        long insns = 0;
        for (int seed = 0; seed < seeds; seed++) {
            Random r = new Random(seed * 7919L + salt);
            boolean sf = r.nextInt(4) != 0;
            long fac = Msr.FP | Msr.VEC | Msr.VSX;
            if (r.nextInt(12) == 0) {
                fac &= r.nextBoolean() ? ~Msr.VEC : ~Msr.VSX;
            }
            Mode mode = new Mode(r.nextBoolean(), sf, dr, pr, dr && sf && r.nextInt(3) == 0, r.nextInt(3) == 0, fac);
            long[] st = run(seed * 31 + salt, mode, 30_000);
            regions += st[0];
            insns += st[1];
        }
        System.out.printf("VecRegionFuzzTest dr=%b pr=%b: %d cases, %d regions, %d instructions, endings %s%n", dr, pr,
                seeds, regions, insns, ENDINGS);
        ENDINGS.clear();
        assertTrue(regions > seeds / 2, "few regions compiled: " + regions);
        assertTrue(insns > seeds * 1500L, "cases end too early: " + insns + " instructions");
    }

    @Test
    void realMode() {
        campaign(100, false, false, 1);
    }

    @Test
    void translated() {
        campaign(100, true, false, 2);
    }

    @Test
    void problemState() {
        campaign(60, true, true, 3);
    }
}
