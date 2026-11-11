package org.boxtheta.javapower.cpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.core.Spr;
import org.junit.jupiter.api.Test;

/**
 * Randomised differential test of the compilation tiers against the interpreter. Each case is
 * a random loop whose body mixes every instruction {@link RegionCompiler} generates directly
 * (with random registers, immediates, record and overflow forms) with instructions it hands to
 * their handlers, loads and stores of every size and form (page-crossing, faulting on a walking
 * pointer, hitting the region's own page), forward branches (direct, through LR and CTR, and an
 * op of another module that skips an instruction), and optionally decrementer interrupts. It
 * runs in both byte orders, both computation modes, with translation on (radix, with unmapped,
 * read-only and beyond-RAM pages) and off, in supervisor and problem state. Byte order also
 * changes while running: interrupts may be delivered in the other byte order (LPCR[ILE] differs
 * from MSR[LE], so the decrementer handler runs in it and rfid switches back), and in supervisor
 * state the loop may rfid with MSR[LE] flipped into a stub of the other byte order that rfids
 * back. The interpreted and compiled machines must end in identical state: registers, SPRs,
 * interrupt history, memory.
 */
class RegionFuzzTest {
    static final long RAM = 1 << 20;
    static final long CODE = 0x10000;
    static final long DATA = 0x20000;
    static final long UNMAPPED = 0x23000;
    static final long READONLY = 0x24000;
    static final long PRTB = 0x40000;
    static final long ROOT = 0x50000;
    static final long T1 = 0x60000;
    static final long T2 = 0x61000;
    static final long T3 = 0x62000;
    /** The other-byte-order stub reached by rfid with MSR[LE] flipped. */
    static final long FLIP = 0x14000;
    static final int FOREIGN_PO = 5;

    static final int INTERP = 0;
    static final int BLOCKS = 1;
    static final int REGIONS = 2;
    static final int PROMOTED = 3;

    /** le: MSR[LE] of the loop; ile: LPCR[ILE]; flips: the loop rfids into the other byte order. */
    record Mode(boolean le, boolean ile, boolean sf, boolean dr, boolean pr, boolean dec, boolean flips) {}

    static final class Machine {
        final PhysicalMemory pm = new PhysicalMemory(RAM);
        final CpuState s = new CpuState();
        final Interpreter cpu;
        final List<String> interrupts = new ArrayList<>();
        final boolean le;

        Machine(int tier, Mode m) {
            le = m.le;
            DecodeTable t = new DecodeTable();
            t.addAll(new IntInsns());
            // Another module's op: r22 += 1, then skip the next instruction.
            t.addPrimary(FOREIGN_PO, "skip", (st, i) -> {
                st.gpr[22]++;
                st.npc = st.pc + 8;
            });
            s.hv = st -> st.gpr[3] = -2;
            cpu = new Interpreter(s, pm, t);
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
            s.spr[Spr.LPCR] |= (m.ile ? Spr.LPCR_ILE : 0) | Spr.LPCR_HR | Spr.LPCR_UPRT | Spr.LPCR_GTSE;
            s.procTable = PRTB;
            pageTables();
            s.msr = Msr.ME | (m.sf ? Msr.SF : 0) | (m.le ? Msr.LE : 0) | (m.dr ? Msr.IR | Msr.DR : 0)
                    | (m.pr ? Msr.PR : 0) | (m.dec ? Msr.EE : 0);
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
            long prtbe0 = (1L << 62) | 0xA0 | ROOT | 13;
            pm.write64be(PRTB, prtbe0);
            pm.write64be(ROOT, RadixMmu.PTE_VALID | T1 | 9);
            pm.write64be(T1, RadixMmu.PTE_VALID | T2 | 9);
            pm.write64be(T2, RadixMmu.PTE_VALID | T3 | 9);
            for (int k = 0; k < 512; k++) {
                long pa = 4096L * k; // pages past RAM map to unassigned real memory (machine check)
                long pte = leaf | pa;
                if (pa == UNMAPPED) {
                    pte = 0;
                } else if (pa == READONLY) {
                    pte &= ~RadixMmu.PTE_EAA_RW;
                }
                pm.write64be(T3 + 8L * k, pte);
            }
        }

        void load(long addr, List<Integer> code) {
            load(addr, code, le);
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

    static int x(int rt, int ra, int rb, int xo, int rc) {
        return (31 << 26) | (rt << 21) | (ra << 16) | (rb << 11) | (xo << 1) | rc;
    }

    static int xo(int rt, int ra, int rb, int oe, int xo, int rc) {
        return (31 << 26) | (rt << 21) | (ra << 16) | (rb << 11) | (oe << 10) | (xo << 1) | rc;
    }

    static int spr(int n) {
        return ((n & 0x1f) << 16) | ((n >>> 5) << 11);
    }

    static int mtspr(int n, int rs) {
        return (31 << 26) | (rs << 21) | spr(n) | (467 << 1);
    }

    static int mfspr(int rt, int n) {
        return (31 << 26) | (rt << 21) | spr(n) | (339 << 1);
    }

    static int bc(int bo, int bi, long from, long to, int lk) {
        return (16 << 26) | (bo << 21) | (bi << 16) | ((int) (to - from) & 0xfffc) | lk;
    }

    static int b(long from, long to) {
        return (18 << 26) | ((int) (to - from) & 0x3fffffc);
    }

    static int mb6(int mb) {
        return ((mb & 31) << 6) | (mb & 32);
    }

    static final int SC = 0x44000002;
    static final int RFID = 0x4c000024;

    static int mfmsr(int rt) {
        return x(rt, 0, 0, 83, 0);
    }

    static int mtmsrdL(int rs) {
        return (31 << 26) | (rs << 21) | (1 << 16) | (178 << 1);
    }

    /** Registers random instructions may write. */
    static final int[] DST = {0, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23};
    // r24: decrementer count, r25: index / DEC value, r26: data base, r27: page-crossing base,
    // r28: update base (reset every iteration), r29: walking pointer, r30: own code page, r31: loop counter.

    static final class Gen {
        final Random r;
        final List<Integer> code = new ArrayList<>();
        final long base;
        /** Whether the body may rfid into the FLIP stub (supervisor state only). */
        boolean flips;

        Gen(Random r, long base) {
            this.r = r;
            this.base = base;
        }

        long pc() {
            return base + 4L * code.size();
        }

        int dst() {
            return DST[r.nextInt(DST.length)];
        }

        int src() {
            return r.nextInt(32);
        }

        int rc() {
            return r.nextInt(3) == 0 ? 1 : 0;
        }

        void emit(int w) {
            code.add(w);
        }

        /** One random instruction (or a short sequence) of the loop body. */
        void random() {
            if (flips && r.nextInt(25) == 0) {
                flip();
                return;
            }
            int k = r.nextInt(100);
            if (k < 40) {
                alu();
            } else if (k < 50) {
                crOps();
            } else if (k < 75) {
                mem();
            } else if (k < 85) {
                handlerOps();
            } else {
                branches();
            }
        }

        void alu() {
            int rt = dst();
            int ra = src();
            int rb = src();
            int imm = r.nextInt(65536) - 32768;
            String[] xoNames = {"add", "subf", "neg", "mulld", "mullw", "divd", "divdu", "divw", "divwu", "addc", "adde",
                "addze", "addme", "subfc", "subfe", "subfze", "subfme"};
            int[] xoCodes = {266, 40, 104, 233, 235, 489, 457, 491, 459, 10, 138, 202, 234, 8, 136, 200, 232};
            switch (r.nextInt(22)) {
                case 0 -> emit(d(14, rt, ra, imm)); // addi
                case 1 -> emit(d(15, rt, ra, imm)); // addis
                case 2 -> {
                    int j = r.nextInt(xoNames.length);
                    emit(xo(rt, ra, rb, r.nextInt(6) == 0 ? 1 : 0, xoCodes[j], rc()));
                }
                case 3 -> emit(d(7, rt, ra, imm)); // mulli
                case 4 -> emit(x(rt, ra, rb, new int[] {73, 9, 75, 11}[r.nextInt(4)], rc())); // mulh*
                case 5 -> emit(x(rt, ra, rb, new int[] {777, 265, 779, 267}[r.nextInt(4)], 0)); // mod*
                case 6 -> emit(d(new int[] {12, 8, 13}[r.nextInt(3)], rt, ra, imm)); // addic, subfic, addic.
                case 7 -> emit(x(src(), rt, rb, new int[] {28, 60, 444, 412, 316, 476, 124, 284}[r.nextInt(8)], rc()));
                case 8 -> emit(d(new int[] {24, 25, 26, 27, 28, 29}[r.nextInt(6)], src(), rt, imm)); // ori.. andis.
                case 9 -> emit(x(src(), rt, 0, new int[] {954, 922, 986, 26, 58, 538, 570, 506}[r.nextInt(8)],
                        r.nextBoolean() ? 0 : rc() & (r.nextInt(8) < 7 ? 1 : 0)));
                case 10 -> emit(x(src(), rt, rb, new int[] {24, 536, 27, 539, 792, 794}[r.nextInt(6)], rc()));
                case 11 -> emit(x(src(), rt, r.nextInt(32), 824, rc())); // srawi
                case 12 -> {
                    int sh = r.nextInt(64);
                    int xo = r.nextBoolean() ? 413 : 445; // sradi, extswsli
                    emit((31 << 26) | (src() << 21) | (rt << 16) | ((sh & 31) << 11) | (xo << 2) | ((sh >> 5) << 1)
                            | rc());
                }
                case 13 -> {
                    int po = new int[] {21, 23, 20}[r.nextInt(3)]; // rlwinm, rlwnm, rlwimi
                    int sh = po == 23 ? src() : r.nextInt(32);
                    emit((po << 26) | (src() << 21) | (rt << 16) | (sh << 11) | (r.nextInt(32) << 6)
                            | (r.nextInt(32) << 1) | rc());
                }
                case 14 -> {
                    int sh = r.nextInt(64);
                    emit((30 << 26) | (src() << 21) | (rt << 16) | ((sh & 31) << 11) | mb6(r.nextInt(64))
                            | (r.nextInt(4) << 2) | ((sh >> 5) << 1) | rc()); // rldicl/rldicr/rldic/rldimi
                }
                case 15 -> emit((30 << 26) | (src() << 21) | (rt << 16) | (rb << 11) | (mb6(r.nextInt(64)) >> 1 << 1)
                        | ((8 + r.nextInt(2)) << 1) | rc()); // rldcl/rldcr
                case 16 -> {
                    int bf = r.nextInt(8);
                    int l = r.nextInt(2);
                    switch (r.nextInt(4)) {
                        case 0 -> emit((31 << 26) | (bf << 23) | (l << 21) | (ra << 16) | (rb << 11));
                        case 1 -> emit((31 << 26) | (bf << 23) | (l << 21) | (ra << 16) | (rb << 11) | (32 << 1));
                        case 2 -> emit((11 << 26) | (bf << 23) | (l << 21) | (ra << 16) | (imm & 0xffff));
                        default -> emit((10 << 26) | (bf << 23) | (l << 21) | (ra << 16) | (imm & 0xffff));
                    }
                }
                case 17 -> emit((31 << 26) | (rt << 21) | (ra << 16) | (rb << 11) | (r.nextInt(32) << 6) | (15 << 1));
                case 18 -> emit(r.nextBoolean() ? mfspr(rt, r.nextBoolean() ? Spr.LR : Spr.CTR)
                        : mtspr(r.nextBoolean() ? Spr.LR : Spr.CTR, src()));
                case 19 -> emit(x(src(), rt, 0, 378, 0)); // popcntw (handler)
                case 20 -> emit(xo(rt, ra, rb, 1, xoCodes[r.nextInt(xoCodes.length)], rc())); // OE forms (handler)
                default -> emit(d(14, rt, 0, imm)); // li
            }
        }

        void crOps() {
            switch (r.nextInt(6)) {
                case 0, 1 -> emit((19 << 26) | (r.nextInt(32) << 21) | (r.nextInt(32) << 16) | (r.nextInt(32) << 11)
                        | (new int[] {257, 129, 289, 225, 33, 449, 417, 193}[r.nextInt(8)] << 1));
                case 2 -> emit((19 << 26) | (r.nextInt(8) << 23) | (r.nextInt(8) << 18)); // mcrf
                case 3 -> emit(r.nextBoolean() ? x(dst(), 0, 0, 19, 0)
                        : (31 << 26) | (dst() << 21) | (1 << 20) | ((r.nextBoolean() ? 1 << r.nextInt(8) : r.nextInt(256))
                                << 12) | (19 << 1)); // mfcr / mfocrf
                case 4 -> emit((31 << 26) | (src() << 21) | (r.nextInt(4) == 0 ? 1 << 20 : 0)
                        | ((r.nextBoolean() ? 1 << r.nextInt(8) : r.nextInt(256)) << 12) | (144 << 1)); // mt(o)crf
                default -> emit(mfspr(dst(), Spr.XER)); // handler
            }
        }

        void mem() {
            int kind = r.nextInt(10);
            int base;
            int disp;
            if (kind < 5) {
                base = 26;
                disp = r.nextInt(0x80) - 0x10;
            } else if (kind < 7) {
                base = 27; // near the end of a page: some accesses cross it
                disp = r.nextInt(8);
            } else if (kind < 9) {
                base = 29; // walks towards unmapped, read-only and beyond-RAM pages
                disp = r.nextInt(0x40);
            } else {
                base = 30; // the loop's own code page (beyond the code)
                disp = r.nextInt(0x40);
            }
            int rt = dst();
            int[] dLoads = {34, 40, 42, 32};
            int[] dStores = {38, 44, 36};
            int[] xLoads = {87, 279, 343, 23, 341, 21, 790, 534, 532};
            int[] xStores = {215, 407, 151, 149, 918, 662, 660};
            int[] xuLoads = {119, 311, 375, 55, 373, 53};
            int[] xuStores = {247, 439, 183, 181};
            switch (r.nextInt(9)) {
                case 0 -> emit(d(dLoads[r.nextInt(4)], rt, base, disp));
                case 1 -> emit(d(dStores[r.nextInt(3)], src(), base, disp));
                case 2 -> emit(d(58, rt, base, (disp & ~3) | (r.nextBoolean() ? 0 : 2))); // ld, lwa
                case 3 -> emit(d(62, src(), base, disp & ~3)); // std
                case 4 -> emit(x(rt, base, 25, xLoads[r.nextInt(xLoads.length)], 0));
                case 5 -> emit(x(src(), base, 25, xStores[r.nextInt(xStores.length)], 0));
                case 6 -> { // update forms on r28
                    int[] upd = {35, 41, 43, 33, 39, 45, 37};
                    int po = upd[r.nextInt(upd.length)];
                    emit(d(po, po >= 37 && po != 41 && po != 43 ? src() : rt, 28, r.nextInt(0x40)));
                }
                case 7 -> emit(r.nextBoolean() ? d(58, rt, 28, (r.nextInt(0x40) & ~3) | 1) : d(62, src(), 28,
                        (r.nextInt(0x40) & ~3) | 1)); // ldu, stdu
                default -> emit(r.nextBoolean() ? x(rt, 28, 25, xuLoads[r.nextInt(xuLoads.length)], 0)
                        : x(src(), 28, 25, xuStores[r.nextInt(xuStores.length)], 0));
            }
        }

        /**
         * rfid into the FLIP stub with MSR[LE] inverted, which returns to SPRG2 by rfid with it
         * inverted again. MSR[EE] is cleared first so a decrementer interrupt cannot overwrite
         * SRR0/SRR1 in between; the stub sets it again if the mode uses the decrementer.
         */
        void flip() {
            emit(d(14, 23, 0, 0)); // li r23, 0
            emit(mtmsrdL(23)); // EE = RI = 0
            long ret = pc() + 4L * 10;
            emit(d(15, 23, 0, (int) (ret >>> 16)));
            emit(d(24, 23, 23, (int) ret & 0xffff));
            emit(mtspr(Spr.SPRG2, 23));
            emit(d(15, 23, 0, (int) (FLIP >>> 16)));
            emit(d(24, 23, 23, (int) FLIP & 0xffff));
            emit(mtspr(Spr.SRR0, 23));
            emit(mfmsr(23));
            emit(d(26, 23, 23, 1)); // xori r23, r23, MSR[LE]
            emit(mtspr(Spr.SRR1, 23));
            emit(RFID);
        }

        void handlerOps() {
            switch (r.nextInt(8)) {
                case 0 -> emit(mtspr(Spr.XER, src()));
                case 1 -> emit(x(src(), dst(), src(), 508, 0)); // cmpb
                case 2 -> emit(new int[] {0x7c0004ac, 0x7c2004ac, 0x7c0006ac}[r.nextInt(3)]); // sync, lwsync, eieio
                case 3 -> emit(x(0, 26, 25, 1014, 0)); // dcbz
                case 4 -> emit((31 << 26) | (r.nextInt(8) << 23) | (576 << 1)); // mcrxrx
                case 5 -> emit((31 << 26) | (dst() << 21) | (r.nextInt(8) << 18) | (128 << 1)); // setb
                case 6 -> emit(mfspr(dst(), Spr.SPRG0)); // privileged in problem state
                default -> emit(x(0, 26, 25, 278, 0)); // dcbt
            }
        }

        void branches() {
            int skip = 1 + r.nextInt(3);
            switch (r.nextInt(6)) {
                case 0, 1 -> {
                    // conditional forward branch over `skip` instructions
                    int bo = new int[] {4, 12, 0, 2, 8, 10, 16, 18, 20}[r.nextInt(9)];
                    long at = pc();
                    emit(bc(bo, r.nextInt(32), at, at + 4L * (skip + 1), r.nextInt(5) == 0 ? 1 : 0));
                    for (int k = 0; k < skip; k++) {
                        alu();
                    }
                }
                case 2 -> {
                    long at = pc();
                    emit(b(at, at + 4L * (skip + 1)));
                    for (int k = 0; k < skip; k++) {
                        alu();
                    }
                }
                case 3, 4 -> {
                    // through LR or CTR: r23 = target; mtlr/mtctr r23; bclr/bcctr (maybe conditional)
                    boolean ctr = r.nextBoolean();
                    long at = pc() + 16; // after lis/ori/mt*
                    long target = at + 4L * (skip + 1);
                    emit(d(15, 23, 0, (int) (target >>> 16)));
                    emit(d(24, 23, 23, (int) target & 0xffff));
                    emit(mtspr(ctr ? Spr.CTR : Spr.LR, 23));
                    int bo = ctr ? new int[] {20, 12, 4}[r.nextInt(3)] : new int[] {20, 12, 4, 16, 18}[r.nextInt(5)];
                    emit((19 << 26) | (bo << 21) | (r.nextInt(32) << 16) | ((ctr ? 528 : 16) << 1)
                            | (r.nextInt(4) == 0 ? 1 : 0));
                    for (int k = 0; k < skip; k++) {
                        alu();
                    }
                }
                default -> {
                    emit(FOREIGN_PO << 26); // skips the next instruction
                    alu();
                }
            }
        }
    }

    /** Build the program for {@code seed}: setup, loop, sc. Returns it with the register setup applied by {@link #setup}. */
    static List<Integer> program(Random r, Mode mode) {
        Gen g = new Gen(r, CODE);
        g.flips = mode.flips;
        int iterations = 50 + r.nextInt(500);
        g.emit(d(14, 31, 0, iterations)); // li r31, n
        long loop = g.pc();
        g.emit(x(26, 28, 26, 444, 0)); // mr r28, r26
        int n = 4 + r.nextInt(50);
        for (int k = 0; k < n; k++) {
            g.random();
        }
        g.emit(d(14, 29, 29, 0x80)); // r29 += 128
        g.emit(d(14, 31, 31, -1));
        g.emit((11 << 26) | (7 << 23) | (1 << 21) | (31 << 16)); // cmpdi cr7, r31, 0
        g.emit(bc(4, 30, g.pc(), loop, 0)); // bne cr7, loop
        g.emit(SC);
        return g.code;
    }

    /** The FLIP stub (run in the other byte order): random work, then rfid back to SPRG2. */
    static List<Integer> flipStub(Random r, Mode mode) {
        Gen g = new Gen(r, FLIP);
        int n = 2 + r.nextInt(12);
        for (int k = 0; k < n; k++) {
            if (r.nextBoolean()) {
                g.mem();
            } else {
                g.alu();
            }
        }
        g.emit(mfspr(23, Spr.SPRG2));
        g.emit(mtspr(Spr.SRR0, 23));
        g.emit(mfmsr(23));
        g.emit(d(26, 23, 23, 1)); // xori r23, r23, MSR[LE]
        if (mode.dec) {
            g.emit(d(24, 23, 23, (int) Msr.EE)); // ori r23, r23, MSR[EE]
        }
        g.emit(mtspr(Spr.SRR1, 23));
        g.emit(RFID);
        return g.code;
    }

    static void setup(Machine m, Random r, List<Integer> code, List<Integer> stub, Mode mode) {
        m.load(CODE, code);
        m.load(FLIP, stub, !mode.le);
        // Decrementer handler (real mode, byte order from LPCR[ILE]): count, re-arm with r25, return.
        m.load(0x900, List.of(d(14, 24, 24, 1), mtspr(Spr.DEC, 25), RFID), mode.ile);
        for (int k = 0; k < 32; k++) {
            m.s.gpr[k] = r.nextLong();
        }
        m.s.gpr[25] = 8L * r.nextInt(16);
        m.s.gpr[26] = DATA + 0x100;
        m.s.gpr[27] = DATA + 0x1000 - 4 - r.nextInt(4);
        m.s.gpr[29] = DATA + 0x80 + 0x100L * r.nextInt(16) + 0x1000L * r.nextInt(3) - 0x1000L * r.nextInt(6);
        m.s.gpr[30] = CODE + 0xf00;
        m.s.cr = r.nextInt();
        m.s.setXer(r.nextLong() & 0xe00c007fL);
        m.s.lr = r.nextLong();
        m.s.ctr = r.nextLong();
        byte[] data = new byte[0x6000];
        r.nextBytes(data);
        m.pm.write(DATA, data, 0, data.length);
        if (mode.dec) {
            m.cpu.storeDec(5 + r.nextInt(60));
            m.s.gpr[25] = 20 + r.nextInt(100);
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
        byte[] ma = new byte[(int) RAM];
        byte[] mb = new byte[(int) RAM];
        a.pm.read(0, ma, 0, ma.length);
        b.pm.read(0, mb, 0, mb.length);
        assertArrayEquals(ma, mb, what + "memory");
    }

    static long[] run(int seed, Mode mode, long insns) {
        Random r0 = new Random(seed);
        List<Integer> code = program(r0, mode);
        List<Integer> stub = flipStub(r0, mode);
        long setupSeed = r0.nextLong();
        Machine ref = new Machine(INTERP, mode);
        setup(ref, new Random(setupSeed), code, stub, mode);
        ref.cpu.run(insns);
        if (ref.s.spr[Spr.SPRG2] != 0) {
            FLIPPED[0]++;
        }
        if (mode.ile != mode.le && ref.interrupts.stream().anyMatch(v -> v.startsWith("900@"))) {
            FLIPPED[1]++;
        }
        String end = ref.s.stopRequested && !ref.interrupts.isEmpty()
                ? ref.interrupts.get(ref.interrupts.size() - 1).replaceAll("@.*", "") : "limit";
        ENDINGS.merge(end, 1, Integer::sum);
        long[] stats = new long[2];
        for (int tier : new int[] {REGIONS, PROMOTED, BLOCKS}) {
            Machine m = new Machine(tier, mode);
            setup(m, new Random(setupSeed), code, stub, mode);
            m.cpu.run(insns);
            compare(ref, m, "seed " + seed + " " + mode + " tier " + tier + ": ");
            if (tier == REGIONS) {
                stats[0] += m.cpu.regionsCompiled;
                stats[1] += ref.s.icount;
            }
        }
        return stats;
    }

    static void campaign(int seeds, boolean dr, boolean pr) {
        long regions = 0;
        long insns = 0;
        for (int seed = 0; seed < seeds; seed++) {
            Random r = new Random(seed * 7919L + (dr ? 1 : 0) + (pr ? 2 : 0));
            boolean le = r.nextBoolean();
            boolean ile = r.nextInt(3) == 0 ? !le : le;
            boolean dec = r.nextInt(3) == 0 || ile != le;
            Mode mode = new Mode(le, ile, r.nextInt(4) != 0, dr, pr, dec, !pr && r.nextInt(3) == 0);
            long[] st = run(seed, mode, 40_000);
            regions += st[0];
            insns += st[1];
        }
        System.out.printf("RegionFuzzTest dr=%b pr=%b: %d cases, %d regions, %d instructions, endings %s,"
                + " %d ran the other-byte-order stub, %d took other-byte-order interrupts%n", dr, pr,
                seeds, regions, insns, ENDINGS, FLIPPED[0], FLIPPED[1]);
        ENDINGS.clear();
        if (!pr) {
            assertTrue(FLIPPED[0] > seeds / 10, "few cases ran the byte-order stub: " + FLIPPED[0]);
        }
        assertTrue(FLIPPED[1] > seeds / 20, "few cases took other-byte-order interrupts: " + FLIPPED[1]);
        FLIPPED[0] = 0;
        FLIPPED[1] = 0;
        assertTrue(regions > seeds / 2, "few regions compiled: " + regions);
        assertTrue(insns > seeds * 5000L, "cases end too early: " + insns + " instructions");
    }

    /** How the interpreted runs ended (last interrupt vector, or "limit"), for the coverage report. */
    static final java.util.Map<String, Integer> ENDINGS = new java.util.TreeMap<>();
    /** Cases that ran the FLIP stub, and that took a decrementer interrupt in the other byte order. */
    static final int[] FLIPPED = new int[2];

    @Test
    void realMode() {
        campaign(120, false, false);
    }

    @Test
    void translated() {
        campaign(120, true, false);
    }

    @Test
    void problemState() {
        campaign(60, true, true);
    }
}
