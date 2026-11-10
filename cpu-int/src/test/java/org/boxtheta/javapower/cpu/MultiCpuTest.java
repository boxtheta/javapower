package org.boxtheta.javapower.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.Hypervisor;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.core.Spr;
import org.junit.jupiter.api.Test;

/**
 * Several interpreters on one memory, each on its own thread, running guest code that contends
 * on larx/stcx and atomics, takes a spinlock, and races tlbie against translation. Every program
 * ends with {@code sc 1} with r3 = 0; any other hypercall (r3 != 0, used by the interrupt
 * vectors) is a failure.
 */
class MultiCpuTest {
    static final long TIMEOUT_MS = 120_000;

    final PhysicalMemory pm = new PhysicalMemory(16L << 20);
    final DecodeTable table = new DecodeTable();
    final ConcurrentLinkedQueue<String> failures = new ConcurrentLinkedQueue<>();

    MultiCpuTest() {
        table.addAll(new IntInsns());
    }

    /** Stops the calling CPU on {@code sc 1}; records a failure unless r3 is 0. */
    final Hypervisor hv = new Hypervisor() {
        @Override
        public void hcall(CpuState s) {
            if (s.gpr[3] != 0) {
                failures.add("cpu " + s.index + " hcall r3=" + Long.toHexString(s.gpr[3]) + " srr0="
                        + Long.toHexString(s.spr[Spr.SRR0]) + " dar=" + Long.toHexString(s.spr[Spr.DAR]));
            }
            s.stopRequested = true;
        }
    };

    List<Interpreter> cpus(int n, CpuDomain d) {
        List<Interpreter> r = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            CpuState s = new CpuState();
            s.index = i;
            s.hv = hv;
            Interpreter in = new Interpreter(s, d, table);
            in.resetPapr();
            in.blockThreshold = 50; // get compiled blocks into the race early
            s.msr = Msr.SF | Msr.ME | Msr.LE;
            s.spr[Spr.LPCR] |= Spr.LPCR_ILE; // interrupts stay little-endian
            r.add(in);
        }
        return r;
    }

    /** Interrupt vectors that report the vector number (real mode, little-endian). */
    void vectors() {
        for (int v : new int[] {0x200, 0x300, 0x380, 0x400, 0x480, 0x500, 0x600, 0x700, 0x800, 0x900, 0xc00}) {
            new Asm().li(3, v).sc1().load(pm, v, true);
        }
    }

    void runAll(List<Interpreter> cpus) throws InterruptedException {
        List<Thread> ts = new ArrayList<>();
        for (Interpreter in : cpus) {
            Thread t = new Thread(() -> {
                try {
                    in.run();
                } catch (Throwable e) {
                    failures.add("cpu " + in.state().index + ": " + e);
                }
            }, "test-cpu-" + in.state().index);
            t.setDaemon(true);
            t.start();
            ts.add(t);
        }
        long end = System.currentTimeMillis() + TIMEOUT_MS;
        for (Thread t : ts) {
            t.join(Math.max(1, end - System.currentTimeMillis()));
        }
        for (Interpreter in : cpus) {
            in.state().stopRequested = true;
            in.state().wake();
        }
        for (Thread t : ts) {
            t.join(5000);
            assertFalse(t.isAlive(), t.getName() + " did not finish");
        }
        assertTrue(failures.isEmpty(), failures.toString());
    }

    static final long DATA = 0x10000;

    /**
     * Contended read-modify-writes of every width: a shared doubleword and word, one byte per CPU
     * in one word (sub-word store-conditional must not clobber the neighbours), a shared
     * halfword, ldat fetch-and-add, and a plain counter under a larx/stcx spinlock.
     */
    @Test
    void larxStcxAndAtomicsAreAtomicAcrossCpus() throws Exception {
        int n = 4;
        int iters = 100_000;
        vectors();
        Asm a = new Asm();
        // r3 = DATA, r4 = iterations, r13 = cpu index.
        a.addi(6, 3, 8).addi(7, 3, 16).add(7, 7, 13).addi(8, 3, 24).addi(9, 3, 64).addi(11, 3, 32);
        a.li(12, 1);
        a.label("loop");
        a.label("l1").ldarx(5, 0, 3).addi(5, 5, 1).stdcx(5, 0, 3).bne("l1");
        a.label("l2").lwarx(5, 0, 6).addi(5, 5, 1).stwcx(5, 0, 6).bne("l2");
        a.label("l3").lbarx(5, 0, 7).addi(5, 5, 1).stbcx(5, 0, 7).bne("l3");
        a.label("l4").lharx(5, 0, 8).addi(5, 5, 1).sthcx(5, 0, 8).bne("l4");
        a.ldat(11, 11, 0); // fetch and add r12 (=1) to DATA+32; r11 gets the old value
        a.addi(11, 3, 32);
        a.label("l5").lwarx(5, 0, 9).cmpwi(5, 0).bne("l5").li(5, 1).stwcx(5, 0, 9).bne("l5").isync();
        a.ld(10, 8, 9).addi(10, 10, 1).std(10, 8, 9);
        a.lwsync().li(5, 0).stw(5, 0, 9);
        a.addi(4, 4, -1).cmpdi(4, 0).bne("loop");
        a.li(3, 0).sc1();
        a.load(pm, 0x1000, true);

        CpuDomain d = new CpuDomain(pm);
        List<Interpreter> cpus = cpus(n, d);
        for (Interpreter in : cpus) {
            CpuState s = in.state();
            s.pc = 0x1000;
            s.gpr[3] = DATA;
            s.gpr[4] = iters;
            s.gpr[13] = s.index;
        }
        runAll(cpus);
        long total = (long) n * iters;
        assertEquals(total, pm.read64le(DATA), "ldarx/stdcx counter");
        assertEquals((int) total, pm.read32le(DATA + 8), "lwarx/stwcx counter");
        for (int i = 0; i < n; i++) {
            assertEquals(iters & 0xff, pm.read8(DATA + 16 + i), "lbarx/stbcx byte of cpu " + i);
        }
        assertEquals((int) (total & 0xffff), pm.read16le(DATA + 24), "lharx/sthcx counter");
        assertEquals(total, pm.read64le(DATA + 32), "ldat fetch-and-add counter");
        assertEquals(total, pm.read64le(DATA + 64 + 8), "counter under the spinlock");
        long blocks = 0;
        for (Interpreter in : cpus) {
            blocks += in.blocksRun;
        }
        assertTrue(blocks > 0, "compiled blocks were not exercised");
    }

    /** 128-bit compare-and-swap: two doubleword counters updated together with lqarx/stqcx. */
    @Test
    void lqarxStqcxUpdatesBothHalvesAtomically() throws Exception {
        int n = 4;
        int iters = 50_000;
        vectors();
        Asm a = new Asm();
        a.label("loop");
        a.label("q").lqarx(6, 0, 3).addi(6, 6, 1).addi(7, 7, 1).stqcx(6, 0, 3).bne("q");
        a.addi(4, 4, -1).cmpdi(4, 0).bne("loop");
        a.li(3, 0).sc1();
        a.load(pm, 0x1000, true);
        CpuDomain d = new CpuDomain(pm);
        List<Interpreter> cpus = cpus(n, d);
        for (Interpreter in : cpus) {
            in.state().pc = 0x1000;
            in.state().gpr[3] = DATA;
            in.state().gpr[4] = iters;
        }
        runAll(cpus);
        assertEquals((long) n * iters, pm.read64le(DATA));
        assertEquals((long) n * iters, pm.read64le(DATA + 8));
    }

    // ---- tlbie against translation ----------------------------------------------------------

    static final long ROOT = 0x40000;
    static final long T1 = 0x50000;
    static final long T2 = 0x51000;
    static final long T3A = 0x52000;
    static final long T3B = 0x53000;
    static final long PRTB = 0x54000;
    static final long PAGES = 0x80000;
    static final long V = 0x10000000L;
    static final long FLAG = 0x3000;
    static final long ERRS = 0x3100;
    static final long LEAF = RadixMmu.PTE_VALID | RadixMmu.PTE_LEAF | RadixMmu.PTE_R | RadixMmu.PTE_C
            | RadixMmu.PTE_PRIV | RadixMmu.PTE_EAA_RW | RadixMmu.PTE_EAA_R | RadixMmu.PTE_EAA_X;

    /** Radix tree for PID 0: 0..2 MiB identity mapped with 4 KiB pages, V mapped to PAGES. */
    void pageTables() {
        long prtbe0 = (1L << 62) | 0xA0 | ROOT | 13; // 52-bit tree, 13-bit root
        pm.write64be(PRTB, prtbe0);
        pm.write64be(ROOT, RadixMmu.PTE_VALID | T1 | 9);
        pm.write64be(T1, RadixMmu.PTE_VALID | T2 | 9);
        pm.write64be(T2, RadixMmu.PTE_VALID | T3A | 9);
        pm.write64be(T2 + 8 * ((V >>> 21) & 511), RadixMmu.PTE_VALID | T3B | 9);
        for (int k = 0; k < 512; k++) {
            pm.write64be(T3A + 8L * k, LEAF | (4096L * k));
        }
        pm.write64be(T3B, LEAF | PAGES);
    }

    /**
     * One CPU remaps V round after round (page k holds the value k) and after each
     * {@code tlbie; eieio; tlbsync; ptesync} publishes the round number; the others read the
     * round number, then V. A value older than the round number means a stale translation
     * survived the flush. With {@code local}, the writer uses tlbiel instead, which does not
     * reach the other CPUs: then stale reads must show up (the test can see them).
     */
    long tlbieRace(boolean local, int readers, int rounds) throws Exception {
        vectors();
        pageTables();
        Asm w = new Asm();
        // r3 = k, r20 = FLAG, r21 = V, r23 = rounds, r24 = PAGES, r25 = PTE of V, r26 = leaf bits.
        w.li(3, 1);
        w.label("loop");
        w.andiDot(6, 3, 15).sldi(6, 6, 12).add(6, 6, 24);
        w.std(3, 0, 6);
        w.or(7, 6, 26).stdbrx(7, 0, 25);
        w.ptesync();
        if (local) {
            w.tlbiel(21, 0);
        } else {
            w.tlbie(21, 0);
        }
        w.eieio().tlbsync().ptesync();
        w.std(3, 0, 20);
        w.cmpd(3, 23).addi(3, 3, 1).blt("loop");
        w.li(3, 0).sc1();
        w.load(pm, 0x1000, true);

        Asm r = new Asm();
        // r20 = FLAG, r21 = V, r22 = error slot, r23 = rounds; r9 counts stale reads.
        r.li(9, 0);
        r.label("loop");
        r.ld(5, 0, 20).lwsync().ld(6, 0, 21);
        r.cmpld(6, 5).bge("ok").addi(9, 9, 1);
        r.label("ok");
        r.cmpd(5, 23).blt("loop");
        r.std(9, 0, 22);
        r.li(3, 0).sc1();
        r.load(pm, 0x2000, true);

        CpuDomain d = new CpuDomain(pm);
        List<Interpreter> cpus = cpus(readers + 1, d);
        for (Interpreter in : cpus) {
            CpuState s = in.state();
            s.msr |= Msr.IR | Msr.DR;
            s.spr[Spr.LPCR] |= Spr.LPCR_HR | Spr.LPCR_UPRT | Spr.LPCR_GTSE;
            s.procTable = PRTB; // PRTS = 0: one 4 KiB table
            s.gpr[20] = FLAG;
            s.gpr[21] = V;
            s.gpr[22] = ERRS + 8L * s.index;
            s.gpr[23] = rounds;
            s.gpr[24] = PAGES;
            s.gpr[25] = T3B;
            s.gpr[26] = LEAF;
            s.pc = s.index == 0 ? 0x1000 : 0x2000;
        }
        runAll(cpus);
        assertEquals(rounds, pm.read64le(FLAG));
        long stale = 0;
        for (int i = 1; i <= readers; i++) {
            stale += pm.read64le(ERRS + 8L * i);
        }
        if (!local) {
            assertEquals(rounds, d.tlbShootdowns());
        }
        return stale;
    }

    @Test
    void tlbieReachesEveryCpuBeforePtesyncCompletes() throws Exception {
        assertEquals(0, tlbieRace(false, 3, 3000));
    }

    @Test
    void tlbielStaysLocalSoTheRaceIsVisible() throws Exception {
        assertTrue(tlbieRace(true, 1, 3000) > 0, "the tlbie test cannot see stale translations");
    }

    /** Two CPUs completing tlbies at the same time must not wait for each other forever. */
    @Test
    void concurrentShootdownsDoNotDeadlock() throws Exception {
        vectors();
        pageTables();
        Asm w = new Asm();
        w.li(3, 0);
        w.label("loop");
        w.tlbie(21, 0).eieio().tlbsync().ptesync();
        w.addi(3, 3, 1).cmpd(3, 23).blt("loop");
        w.li(3, 0).sc1();
        w.load(pm, 0x1000, true);
        CpuDomain d = new CpuDomain(pm);
        List<Interpreter> cpus = cpus(4, d);
        for (Interpreter in : cpus) {
            CpuState s = in.state();
            s.msr |= Msr.IR | Msr.DR;
            s.spr[Spr.LPCR] |= Spr.LPCR_HR | Spr.LPCR_UPRT | Spr.LPCR_GTSE;
            s.procTable = PRTB;
            s.gpr[21] = V;
            s.gpr[23] = 2000;
            s.pc = 0x1000;
        }
        runAll(cpus);
        assertEquals(4 * 2000, d.tlbShootdowns());
    }

    // ---- time --------------------------------------------------------------------------------

    @Test
    void cpusOfADomainShareOneTimebase() {
        CpuDomain d = new CpuDomain(pm);
        List<Interpreter> cpus = cpus(3, d);
        long a = cpus.get(0).tb();
        long b = cpus.get(1).tb();
        long c = cpus.get(2).tb();
        long a2 = cpus.get(0).tb();
        assertTrue(a <= b && b <= c && c <= a2, a + " " + b + " " + c + " " + a2);
        assertTrue(a2 - a < Interpreter.TB_FREQ / 10, "timebases differ by more than the read time");
    }

    @Test
    void clockScaleSlowsTheTimebase() throws Exception {
        CpuDomain real = new CpuDomain(pm, Interpreter.Clock.WALL, 1.0);
        CpuDomain slow = new CpuDomain(new PhysicalMemory(1 << 20), Interpreter.Clock.WALL, 8.0);
        long r0 = real.wallNs();
        long s0 = slow.wallNs();
        Thread.sleep(400);
        long dr = real.wallNs() - r0;
        long ds = slow.wallNs() - s0;
        assertTrue(dr >= 400_000_000L, "real " + dr);
        assertTrue(ds * 8 <= dr + 50_000_000L && ds * 8 >= dr - 50_000_000L, "real " + dr + " slow " + ds);
        assertEquals(8_000_000L, slow.hostNs(1_000_000L));
    }

    @Test
    void deterministicClockIsSingleCpu() {
        CpuDomain d = new CpuDomain(pm, Interpreter.Clock.DETERMINISTIC);
        cpus(1, d);
        try {
            cpus(1, d);
            throw new AssertionError("a second deterministic CPU was accepted");
        } catch (IllegalStateException expected) {
            // fine
        }
    }

    // ---- run loop contract -------------------------------------------------------------------

    /** A halted CPU parks without executing; posted work runs while parked and before resuming. */
    @Test
    void haltedCpuParksRunsPostedWorkAndResumes() throws Exception {
        vectors();
        new Asm().li(3, 0).sc1().load(pm, 0x1000, true);
        CpuDomain d = new CpuDomain(pm);
        Interpreter in = cpus(1, d).get(0);
        CpuState s = in.state();
        s.pc = 0x1000;
        s.halted = true;
        Thread t = new Thread(in::run);
        t.setDaemon(true);
        t.start();
        long dl = System.currentTimeMillis() + 5000;
        while (s.runner == null && System.currentTimeMillis() < dl) {
            Thread.onSpinWait();
        }
        Thread.sleep(50);
        assertEquals(0, s.icount, "a halted CPU executed instructions");
        java.util.concurrent.CountDownLatch ran = new java.util.concurrent.CountDownLatch(1);
        s.runOnCpu(c -> ran.countDown());
        assertTrue(ran.await(5, java.util.concurrent.TimeUnit.SECONDS), "posted work did not run while halted");
        s.runOnCpu(c -> c.gpr[3] = 0);
        s.halted = false;
        s.wake();
        t.join(5000);
        assertFalse(t.isAlive());
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(2, s.icount);
    }

    /** stopRequested ends the halted park and the H_CEDE idle promptly. */
    @Test
    void stopEndsParkAndIdle() throws Exception {
        CpuDomain d = new CpuDomain(pm);
        List<Interpreter> cpus = cpus(2, d);
        cpus.get(0).state().halted = true;
        cpus.get(1).state().ceded = true; // idle with no interrupt source
        List<Thread> ts = new ArrayList<>();
        for (Interpreter in : cpus) {
            Thread t = new Thread(in::run);
            t.setDaemon(true);
            t.start();
            ts.add(t);
        }
        Thread.sleep(100);
        long t0 = System.nanoTime();
        for (Interpreter in : cpus) {
            in.state().stopRequested = true;
            in.state().wake();
        }
        for (Thread t : ts) {
            t.join(5000);
            assertFalse(t.isAlive());
        }
        assertTrue(System.nanoTime() - t0 < 1_000_000_000L, "stop took too long");
        for (Interpreter in : cpus) {
            assertTrue(in.outside);
        }
    }

    /** prodded ends an H_CEDE idle; the exec enter/exit hooks bracket the idle period. */
    @Test
    void prodEndsCedeAndExecHooksBracketIdle() throws Exception {
        vectors();
        new Asm().li(3, 0).sc1().load(pm, 0x1000, true);
        List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        Hypervisor h = new Hypervisor() {
            @Override
            public void hcall(CpuState s) {
                hv.hcall(s);
            }

            @Override
            public void cpuExecExit(CpuState s) {
                events.add("exit");
            }

            @Override
            public void cpuExecEnter(CpuState s) {
                events.add("enter");
                s.prodded = false;
            }
        };
        CpuDomain d = new CpuDomain(pm);
        Interpreter in = cpus(1, d).get(0);
        CpuState s = in.state();
        s.hv = h;
        s.pc = 0x1000;
        s.ceded = true;
        Thread t = new Thread(in::run);
        t.setDaemon(true);
        t.start();
        Thread.sleep(100);
        assertEquals(0, s.icount);
        s.prodded = true;
        s.wake();
        t.join(5000);
        assertFalse(t.isAlive());
        assertTrue(failures.isEmpty(), failures.toString());
        assertEquals(List.of("enter", "exit"), events);
        assertFalse(s.prodded);
    }
}
