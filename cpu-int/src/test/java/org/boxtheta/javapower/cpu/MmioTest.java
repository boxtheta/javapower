package org.boxtheta.javapower.cpu;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.MmioBus;
import org.boxtheta.javapower.core.MmioHandler;
import org.boxtheta.javapower.core.MmioRegion;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.core.Spr;
import org.junit.jupiter.api.Test;

/**
 * Guest loads and stores to MMIO regions: every size and form, both byte orders of guest and
 * region, real mode and radix translation, in the interpreter and both compilation tiers; the
 * accesses that never reach a handler (unaligned, leaving a region, unassigned, instruction
 * fetch, atomics, cache-block instructions, bus errors); and regions mapped and unmapped while
 * several CPUs use them.
 */
class MmioTest {
    static final long RAM = 1 << 20;
    static final long CODE = 0x10000;
    static final long DATA = 0x20000;
    static final long PRTB = 0x40000;
    static final long ROOT = 0x50000;
    static final long T1 = 0x60000;
    static final long T2 = 0x61000;
    static final long T3 = 0x62000;

    /** Real addresses: a big-endian and a little-endian region, a hole, and a 6-byte region. */
    static final long BE_BASE = 0x1_0000_0000L;
    static final long LE_BASE = BE_BASE + 0x1000;
    static final long HOLE = BE_BASE + 0x2000;
    static final long SMALL = BE_BASE + 0x3000;
    /** Effective addresses mapping them under translation. */
    static final long EA_BE = 0x80000;
    static final long EA_LE = 0x81000;
    static final long EA_HOLE = 0x82000;
    static final long EA_SMALL = 0x83000;

    static final int INTERP = 0;
    static final int BLOCKS = 1;
    static final int REGIONS = 2;

    /** Registers of 0x40 bytes that read back what was written, in the region's order, logging each access. */
    static final class Dev implements MmioHandler {
        final byte[] b = new byte[0x40];
        final ByteOrder order;
        final List<String> log = Collections.synchronizedList(new ArrayList<>());
        boolean busError;

        Dev(ByteOrder order) {
            this.order = order;
            for (int i = 0; i < b.length; i++) {
                b[i] = (byte) (i * 7 + 1);
            }
        }

        @Override
        public long read(long offset, int size) {
            if (busError) {
                throw new BusError("test");
            }
            long v = 0;
            for (int k = 0; k < size; k++) {
                long by = b[(int) offset + k] & 0xffL;
                v |= order == ByteOrder.BIG_ENDIAN ? by << (8 * (size - 1 - k)) : by << (8 * k);
            }
            log.add(String.format("r %x %d %x", offset, size, v));
            return v;
        }

        @Override
        public void write(long offset, int size, long value) {
            if (busError) {
                throw new BusError("test");
            }
            log.add(String.format("w %x %d %x", offset, size, value));
            for (int k = 0; k < size; k++) {
                int sh = order == ByteOrder.BIG_ENDIAN ? 8 * (size - 1 - k) : 8 * k;
                b[(int) offset + k] = (byte) (value >>> sh);
            }
        }
    }

    /** One CPU over 1 MiB of RAM and the four test regions. */
    static final class Rig {
        final PhysicalMemory pm = new PhysicalMemory(RAM);
        final MmioBus bus = new MmioBus();
        final Dev be = new Dev(ByteOrder.BIG_ENDIAN);
        final Dev le = new Dev(ByteOrder.LITTLE_ENDIAN);
        final Dev small = new Dev(ByteOrder.BIG_ENDIAN);
        final CpuState s = new CpuState();
        final Interpreter cpu;
        final boolean leMode;
        final boolean dr;
        /** {vector, srr0, dar, dsisr} of each interrupt. */
        final List<long[]> interrupts = new ArrayList<>();

        Rig(int tier, boolean leMode, boolean dr) {
            this.leMode = leMode;
            this.dr = dr;
            bus.map(new MmioRegion("be", BE_BASE, 0x1000, ByteOrder.BIG_ENDIAN, be));
            bus.map(new MmioRegion("le", LE_BASE, 0x1000, ByteOrder.LITTLE_ENDIAN, le));
            bus.map(new MmioRegion("small", SMALL, 6, ByteOrder.BIG_ENDIAN, small));
            DecodeTable t = new DecodeTable();
            t.addAll(new IntInsns());
            s.hv = st -> st.stopRequested = true;
            cpu = new Interpreter(s, new CpuDomain(pm, bus, Interpreter.Clock.DETERMINISTIC, 1.0, 1), t);
            cpu.resetPapr();
            switch (tier) {
                case INTERP -> cpu.compileBlocks = false;
                case BLOCKS -> {
                    cpu.blockThreshold = 1;
                    cpu.compileRegions = false;
                }
                default -> {
                    cpu.blockThreshold = 1;
                    cpu.regionThreshold = 1;
                }
            }
            s.spr[Spr.LPCR] |= (leMode ? Spr.LPCR_ILE : 0) | Spr.LPCR_HR | Spr.LPCR_UPRT | Spr.LPCR_GTSE;
            s.procTable = PRTB;
            pageTables();
            s.msr = Msr.SF | Msr.ME | (leMode ? Msr.LE : 0) | (dr ? Msr.IR | Msr.DR : 0);
            s.pc = CODE;
            cpu.interruptHook = (st, vec, srr0) -> {
                interrupts.add(new long[] {vec, srr0, st.spr[Spr.DAR], st.spr[Spr.DSISR], st.spr[Spr.SRR1]});
                st.stopRequested = true;
            };
        }

        void pageTables() {
            long leaf = RadixMmu.PTE_VALID | RadixMmu.PTE_LEAF | RadixMmu.PTE_R | RadixMmu.PTE_C
                    | RadixMmu.PTE_EAA_RW | RadixMmu.PTE_EAA_R | RadixMmu.PTE_EAA_X;
            pm.write64be(PRTB, (1L << 62) | 0xA0 | ROOT | 13);
            pm.write64be(ROOT, RadixMmu.PTE_VALID | T1 | 9);
            pm.write64be(T1, RadixMmu.PTE_VALID | T2 | 9);
            pm.write64be(T2, RadixMmu.PTE_VALID | T3 | 9);
            for (int k = 0; k < 256; k++) {
                pm.write64be(T3 + 8L * k, leaf | 4096L * k);
            }
            pm.write64be(T3 + 8 * (EA_BE >> 12), leaf | BE_BASE);
            pm.write64be(T3 + 8 * (EA_LE >> 12), leaf | LE_BASE);
            pm.write64be(T3 + 8 * (EA_HOLE >> 12), leaf | HOLE);
            pm.write64be(T3 + 8 * (EA_SMALL >> 12), leaf | SMALL);
        }

        /** The effective address of real address {@code real} in this rig's mode. */
        long ea(long real) {
            if (!dr || real < RAM) {
                return real;
            }
            return real == BE_BASE ? EA_BE : real == LE_BASE ? EA_LE : real == HOLE ? EA_HOLE : EA_SMALL;
        }

        void run(int... code) {
            for (int k = 0; k < code.length; k++) {
                if (leMode) {
                    pm.write32le(CODE + 4L * k, code[k]);
                } else {
                    pm.write32be(CODE + 4L * k, code[k]);
                }
            }
            cpu.run(1_000_000);
        }

        long[] onlyInterrupt() {
            assertEquals(1, interrupts.size(), "interrupts");
            return interrupts.get(0);
        }
    }

    // ---- encoders (ISA field order)

    static int d(int po, int rt, int ra, int imm) {
        return (po << 26) | (rt << 21) | (ra << 16) | (imm & 0xffff);
    }

    static int ds(int po, int rt, int ra, int imm, int xo) {
        return (po << 26) | (rt << 21) | (ra << 16) | (imm & 0xfffc) | xo;
    }

    static int x(int rt, int ra, int rb, int xo) {
        return (31 << 26) | (rt << 21) | (ra << 16) | (rb << 11) | (xo << 1);
    }

    static final int SC1 = 0x44000022;

    static int lbz(int rt, int dd, int ra) {
        return d(34, rt, ra, dd);
    }

    static int lhz(int rt, int dd, int ra) {
        return d(40, rt, ra, dd);
    }

    static int lha(int rt, int dd, int ra) {
        return d(42, rt, ra, dd);
    }

    static int lwz(int rt, int dd, int ra) {
        return d(32, rt, ra, dd);
    }

    static int lwzu(int rt, int dd, int ra) {
        return d(33, rt, ra, dd);
    }

    static int stb(int rs, int dd, int ra) {
        return d(38, rs, ra, dd);
    }

    static int sth(int rs, int dd, int ra) {
        return d(44, rs, ra, dd);
    }

    static int stw(int rs, int dd, int ra) {
        return d(36, rs, ra, dd);
    }

    static int stwu(int rs, int dd, int ra) {
        return d(37, rs, ra, dd);
    }

    static int ld(int rt, int dd, int ra) {
        return ds(58, rt, ra, dd, 0);
    }

    static int lwa(int rt, int dd, int ra) {
        return ds(58, rt, ra, dd, 2);
    }

    static int std(int rs, int dd, int ra) {
        return ds(62, rs, ra, dd, 0);
    }

    static int addi(int rt, int ra, int si) {
        return d(14, rt, ra, si);
    }

    static int lmw(int rt, int dd, int ra) {
        return d(46, rt, ra, dd);
    }

    static int stmw(int rs, int dd, int ra) {
        return d(47, rs, ra, dd);
    }

    /** bdnz to {@code target} from the instruction at index {@code at} of the program at CODE. */
    static int bdnz(int at, int target) {
        return (16 << 26) | (16 << 21) | (((target - at) * 4) & 0xfffc);
    }

    static int mtctr(int rs) {
        return (31 << 26) | (rs << 21) | (9 << 16) | (467 << 1);
    }

    static final int BCTR = 0x4e800420;

    // ---- the access program

    /**
     * Every integer load and store form against base r26: stores of each size, loads of each
     * size and extension, byte-reversed loads and stores, indexed and update forms. Runs
     * {@code iters} times (CTR), changing the stored values each time, then {@code sc 1}.
     */
    static int[] accessProgram() {
        List<Integer> p = new ArrayList<>();
        p.add(stb(3, 0, 26));
        p.add(sth(4, 2, 26));
        p.add(stw(5, 4, 26));
        p.add(std(6, 8, 26));
        p.add(lbz(7, 0x10, 26));
        p.add(lhz(8, 0x12, 26));
        p.add(lha(9, 0x12, 26));
        p.add(lwz(10, 0x14, 26));
        p.add(lwa(11, 0x14, 26));
        p.add(ld(12, 0x18, 26));
        p.add(x(13, 26, 20, 790)); // lhbrx r13, r26, r20
        p.add(x(14, 26, 21, 534)); // lwbrx
        p.add(x(15, 26, 22, 532)); // ldbrx
        p.add(x(4, 26, 23, 918)); // sthbrx r4
        p.add(x(5, 26, 24, 662)); // stwbrx r5
        p.add(x(6, 26, 25, 660)); // stdbrx r6
        p.add(x(16, 26, 21, 23)); // lwzx
        p.add(x(5, 26, 22, 151)); // stwx r5 at 0x18
        p.add(addi(27, 26, 0x30));
        p.add(lwzu(17, 4, 27));
        p.add(stwu(3, 4, 27));
        p.add(ld(18, 0, 26));
        p.add(ld(19, 8, 26));
        p.add(ld(28, 0x20, 26));
        p.add(ld(29, 0x28, 26));
        p.add(addi(3, 3, 0x101));
        p.add(addi(4, 4, 0x3));
        p.add(x(5, 5, 7, 266)); // add r5, r5, r7
        p.add(x(6, 6, 12, 266)); // add r6, r6, r12
        p.add(bdnz(p.size(), 0));
        p.add(SC1);
        return p.stream().mapToInt(Integer::intValue).toArray();
    }

    static void presetRegs(CpuState s, long base) {
        s.gpr[3] = 0x11;
        s.gpr[4] = 0x2233;
        s.gpr[5] = 0x44556677L;
        s.gpr[6] = 0x8899aabbccddeeffL;
        s.gpr[20] = 0x12;
        s.gpr[21] = 0x14;
        s.gpr[22] = 0x18;
        s.gpr[23] = 0x20;
        s.gpr[24] = 0x24;
        s.gpr[25] = 0x28;
        s.gpr[26] = base;
        s.ctr = 12;
    }

    @Test
    void everyFormActsAsOnRam() {
        for (boolean le : new boolean[] {false, true}) {
            for (boolean dr : new boolean[] {false, true}) {
                for (ByteOrder order : new ByteOrder[] {ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN}) {
                    List<String> firstLog = null;
                    for (int tier : new int[] {INTERP, BLOCKS, REGIONS}) {
                        String what = "le=" + le + " dr=" + dr + " region " + order + " tier " + tier;
                        // On RAM, as the reference.
                        Rig ram = new Rig(tier, le, dr);
                        Dev init = new Dev(order);
                        ram.pm.write(DATA, init.b, 0, init.b.length);
                        presetRegs(ram.s, ram.ea(DATA));
                        ram.run(accessProgram());
                        assertTrue(ram.interrupts.isEmpty(), what);
                        byte[] want = new byte[0x40];
                        ram.pm.read(DATA, want, 0, want.length);

                        Rig io = new Rig(tier, le, dr);
                        Dev dev = order == ByteOrder.BIG_ENDIAN ? io.be : io.le;
                        presetRegs(io.s, io.ea(order == ByteOrder.BIG_ENDIAN ? BE_BASE : LE_BASE));
                        io.run(accessProgram());
                        assertTrue(io.interrupts.isEmpty(), what + ": " + io.interrupts.size() + " interrupts");
                        assertArrayEquals(want, dev.b, what);
                        for (int r = 3; r < 30; r++) {
                            if (r != 26 && r != 27) {
                                assertEquals(ram.s.gpr[r], io.s.gpr[r], what + " r" + r);
                            }
                        }
                        assertEquals(io.ea(order == ByteOrder.BIG_ENDIAN ? BE_BASE : LE_BASE) + 0x38, io.s.gpr[27], what);
                        assertEquals(12 * 24, dev.log.size(), what);
                        if (firstLog == null) {
                            firstLog = List.copyOf(dev.log);
                        } else {
                            assertEquals(firstLog, dev.log, what + ": the same accesses in every tier");
                        }
                    }
                }
            }
        }
    }

    @Test
    void handlerSeesTheRegionsByteOrder() {
        for (int tier : new int[] {INTERP, REGIONS}) {
            for (boolean le : new boolean[] {false, true}) {
                Rig r = new Rig(tier, le, true);
                r.s.gpr[3] = 0x11223344;
                r.s.gpr[4] = r.ea(BE_BASE);
                r.s.gpr[5] = r.ea(LE_BASE);
                r.s.gpr[6] = 0x10;
                r.run(stw(3, 0, 4), stw(3, 0, 5), x(3, 4, 6, 662) /* stwbrx */, lhz(7, 0x20, 4), lhz(8, 0x20, 5),
                        x(9, 4, 6, 790) /* lhbrx at 0x10 */, SC1);
                // The device's bytes 0x20, 0x21 are e1 e8; the guest stores 11223344 in its own order,
                // then byte-reversed at 0x10.
                String what = "tier " + tier + " le=" + le;
                assertEquals(le ? List.of("w 0 4 44332211", "w 10 4 11223344", "r 20 2 e1e8", "r 10 2 1122")
                        : List.of("w 0 4 11223344", "w 10 4 44332211", "r 20 2 e1e8", "r 10 2 4433"), r.be.log, what);
                assertEquals(List.of(le ? "w 0 4 11223344" : "w 0 4 44332211", "r 20 2 e8e1"), r.le.log, what);
                // A guest load sees the bytes in its own order, as from RAM.
                assertEquals(le ? 0xe8e1 : 0xe1e8, r.s.gpr[7], what);
                assertEquals(le ? 0xe8e1 : 0xe1e8, r.s.gpr[8], what);
                assertEquals(le ? 0x1122 : 0x3344, r.s.gpr[9], what);
            }
        }
    }

    @Test
    void multipleWordsAreWordAccesses() {
        Rig r = new Rig(INTERP, false, false);
        r.s.gpr[26] = BE_BASE;
        r.s.gpr[28] = 0x1c;
        r.s.gpr[29] = 0x1d;
        r.s.gpr[30] = 0x1e;
        r.s.gpr[31] = 0x1f;
        r.run(stmw(28, 0x20, 26), lmw(24, 0x20, 26), SC1);
        assertEquals(List.of("w 20 4 1c", "w 24 4 1d", "w 28 4 1e", "w 2c 4 1f", "r 20 4 1c", "r 24 4 1d",
                "r 28 4 1e", "r 2c 4 1f", "r 30 4 51585f66", "r 34 4 6d747b82", "r 38 4 8990979e",
                "r 3c 4 a5acb3ba"), r.be.log);
        assertEquals(0x1c, r.s.gpr[24]);
        assertEquals(0xa5acb3baL, r.s.gpr[31]);
    }

    /** Run {@code code} with r26 = the BE region, r27 = the hole, r28 = the small region; return the interrupt. */
    static long[] fault(int tier, boolean dr, int... code) {
        Rig r = new Rig(tier, false, dr);
        r.s.gpr[26] = r.ea(BE_BASE);
        r.s.gpr[27] = r.ea(HOLE);
        r.s.gpr[28] = r.ea(SMALL);
        r.s.gpr[29] = DATA;
        r.s.gpr[3] = 0x55;
        r.run(code);
        assertTrue(r.be.log.isEmpty(), "the device saw " + r.be.log);
        assertTrue(r.small.log.isEmpty(), "the device saw " + r.small.log);
        return r.onlyInterrupt();
    }

    @Test
    void accessesThatReachNoHandler() {
        for (int tier : new int[] {INTERP, REGIONS}) {
            for (boolean dr : new boolean[] {false, true}) {
                String what = "tier " + tier + " dr=" + dr;
                long be = dr ? EA_BE : BE_BASE;
                // Unaligned: the machine check of unassigned memory, with DAR and the load's DSISR.
                long[] i = fault(tier, dr, lwz(3, 2, 26), SC1);
                assertEquals(0x200, i[0], what);
                assertEquals(CODE, i[1], what);
                assertEquals(be + 2, i[2], what);
                assertEquals(RadixMmu.MC_DSISR_LOAD, i[3], what);
                assertEquals(0x200, fault(tier, dr, std(3, 4, 26), SC1)[0], what);
                // Crossing a 4 KiB boundary into a region.
                assertEquals(0x200, fault(tier, dr, addi(26, 26, -2), lwz(3, 0, 26), SC1)[0], what);
                // Leaving a region: 4 bytes at offset 4 of a 6-byte region.
                assertEquals(0x200, fault(tier, dr, lwz(3, 4, 28), SC1)[0], what);
                assertEquals(0x200, fault(tier, dr, stw(3, 4, 28), SC1)[0], what);
                // Nothing mapped there: as before.
                i = fault(tier, dr, stb(3, 0, 27), SC1);
                assertEquals(0x200, i[0], what);
                assertEquals((dr ? EA_HOLE : HOLE), i[2], what);
                // Instruction fetch.
                i = fault(tier, dr, mtctr(26), BCTR);
                assertEquals(0x200, i[0], what);
                assertEquals(be, i[1], what);
                // Reservations and atomics: DSI with DSISR bit 37, before the device sees anything.
                i = fault(tier, dr, x(3, 0, 26, 20) /* lwarx */, SC1);
                assertEquals(0x300, i[0], what);
                assertEquals(be, i[2], what);
                assertEquals(0x04000000, i[3], what);
                i = fault(tier, dr, x(3, 0, 29, 20) /* lwarx on RAM */, x(3, 0, 26, 150) | 1 /* stwcx. */, SC1);
                assertEquals(0x300, i[0], what);
                assertEquals(0x06000000, i[3], what);
                i = fault(tier, dr, x(3, 26, 0, 614) /* ldat fetch-and-add */, SC1);
                assertEquals(0x300, i[0], what);
                assertEquals(0x04000000, i[3], what);
                // dcbz: alignment interrupt, as for caching-inhibited storage.
                i = fault(tier, dr, x(0, 0, 26, 1014), SC1);
                assertEquals(0x600, i[0], what);
                assertEquals(be, i[2], what);
            }
        }
    }

    @Test
    void cacheBlockInstructionsDoNotTouchTheDevice() {
        Rig r = new Rig(INTERP, false, true);
        r.s.gpr[26] = r.ea(BE_BASE);
        r.run(x(0, 0, 26, 86) /* dcbf */, x(0, 0, 26, 54) /* dcbst */, x(0, 0, 26, 982) /* icbi */, SC1);
        assertTrue(r.interrupts.isEmpty());
        assertTrue(r.be.log.isEmpty(), r.be.log.toString());
    }

    @Test
    void busErrorIsAMachineCheck() {
        for (int tier : new int[] {INTERP, REGIONS}) {
            for (boolean store : new boolean[] {false, true}) {
                Rig r = new Rig(tier, true, true);
                r.be.busError = true;
                r.s.gpr[26] = r.ea(BE_BASE);
                r.run(store ? stw(3, 8, 26) : lwz(3, 8, 26), SC1);
                long[] i = r.onlyInterrupt();
                assertEquals(0x200, i[0]);
                assertEquals(EA_BE + 8, i[2]);
                assertEquals(store ? RadixMmu.MC_SRR1_STORE : RadixMmu.MC_SRR1_LOAD,
                        i[4] & (store ? RadixMmu.MC_SRR1_STORE : RadixMmu.MC_SRR1_LOAD));
            }
        }
    }

    @Test
    void mmioPagesNeverEnterTheTlb() {
        // The same program on RAM and on a region: RAM pages are walked once, each MMIO access
        // walks again (so a region unmapped or moved is never reached through a stale entry).
        Rig r = new Rig(REGIONS, true, true);
        presetRegs(r.s, r.ea(DATA));
        r.run(accessProgram());
        assertTrue(r.interrupts.isEmpty());
        long walks = r.cpu.mmu().walks;
        Rig io = new Rig(REGIONS, true, true);
        presetRegs(io.s, io.ea(BE_BASE));
        io.run(accessProgram());
        // RAM: the code page and the data page once each. MMIO: the code page once, then every
        // one of the 12 * 24 device accesses.
        assertEquals(2, walks);
        assertEquals(1 + 12 * 24, io.cpu.mmu().walks);
    }

    // ---- several CPUs, regions changing under them

    @Test
    void mapAndUnmapWhileCpusRun() throws Exception {
        PhysicalMemory pm = new PhysicalMemory(RAM);
        MmioBus bus = new MmioBus();
        AtomicLong reads = new AtomicLong();
        Set<String> threads = ConcurrentHashMap.newKeySet();
        MmioHandler h = new MmioHandler() {
            @Override
            public long read(long offset, int size) {
                reads.incrementAndGet();
                threads.add(Thread.currentThread().getName());
                return 0x1234;
            }

            @Override
            public void write(long offset, int size, long value) {
            }
        };
        MmioRegion region = new MmioRegion("toggled", BE_BASE, 0x1000, ByteOrder.LITTLE_ENDIAN, h);
        CpuDomain domain = new CpuDomain(pm, bus, Interpreter.Clock.WALL, 1.0, 1);
        DecodeTable t = new DecodeTable();
        t.addAll(new IntInsns());
        // Machine check vector: count in r21, clear r3, resume after the load.
        int[] mc = {0x7e9a02a6 /* mfsrr0 r20 */, addi(20, 20, 4), 0x7e9a03a6 /* mtsrr0 r20 */, addi(21, 21, 1),
            addi(3, 0, 0), 0x4c000024 /* rfid */};
        for (int k = 0; k < mc.length; k++) {
            pm.write32le(0x200 + 4L * k, mc[k]);
        }
        int[] loop = {lwz(3, 0, 26), x(4, 4, 3, 266) /* add r4, r4, r3 */, bdnz(2, 0), SC1};
        for (int k = 0; k < loop.length; k++) {
            pm.write32le(CODE + 4L * k, loop[k]);
        }
        int n = 3;
        int iters = 200_000;
        List<Interpreter> cpus = new ArrayList<>();
        ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        for (int c = 0; c < n; c++) {
            CpuState s = new CpuState();
            s.index = c;
            s.hv = st -> st.stopRequested = true;
            Interpreter in = new Interpreter(s, domain, t);
            in.resetPapr();
            in.blockThreshold = 50;
            in.regionThreshold = 200;
            s.spr[Spr.LPCR] |= Spr.LPCR_ILE;
            s.msr = Msr.SF | Msr.ME | Msr.LE;
            s.pc = CODE;
            s.gpr[26] = BE_BASE;
            s.ctr = iters;
            cpus.add(in);
        }
        List<Thread> ts = new ArrayList<>();
        for (Interpreter in : cpus) {
            Thread th = new Thread(() -> {
                try {
                    in.run();
                } catch (Throwable e) {
                    failures.add(e);
                }
            }, "cpu-" + in.state().index);
            ts.add(th);
            th.start();
        }
        int toggles = 0;
        while (ts.stream().anyMatch(Thread::isAlive)) {
            bus.map(region);
            Thread.onSpinWait();
            assertTrue(bus.unmap(region));
            toggles++;
        }
        for (Thread th : ts) {
            th.join();
        }
        assertTrue(failures.isEmpty(), failures.toString());
        long checks = 0;
        long sum = 0;
        for (Interpreter in : cpus) {
            checks += in.state().gpr[21];
            sum += in.state().gpr[4];
        }
        assertEquals((long) n * iters, reads.get() + checks, "every load reached the device or machine-checked");
        assertEquals(reads.get() * 0x1234, sum);
        assertTrue(toggles > 0);
        assertFalse(bus.unmap(region));
        assertTrue(reads.get() > 0 && checks > 0, "reads " + reads.get() + ", machine checks " + checks);
        assertTrue(threads.size() > 1, "handler called on " + threads);
    }
}
