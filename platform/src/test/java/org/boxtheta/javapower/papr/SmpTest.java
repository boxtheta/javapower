package org.boxtheta.javapower.papr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.Spr;
import org.junit.jupiter.api.Test;

/**
 * Several CPUs at the hypercall boundary: start-cpu / stop-self / query-cpu-stopped-state, the
 * per-CPU hypervisor state, XICS across servers, and partition-wide effects, with return codes as
 * QEMU's hw/ppc/spapr_rtas.c, spapr_hcall.c and xics_spapr.c produce them.
 */
class SmpTest {
    static final int VTY = 0x71000001;
    static final int VTY_IRQ = 0x1101;
    static final long START = 0xde5c;

    /** RTAS argument block for calls made on CPU {@code c} (distinct per CPU). */
    static long buf(CpuState c) {
        return Rig.RTAS_BUF + 0x1000L * c.index;
    }

    static int[] rtas(Rig r, CpuState c, String name, int nret, int... args) {
        return r.rtasOn(c, buf(c), name, nret, args);
    }

    // ---- wiring -------------------------------------------------------------------------------

    @Test
    void attachAndReset() throws Exception {
        Rig r = Rig.smp(4);
        assertEquals(4, r.m.cpuCount());
        assertEquals(List.of(r.cpus), r.m.cpus());
        for (int i = 0; i < 4; i++) {
            assertSame(r.cpus[i], r.m.cpu(i));
            assertSame(r.m, r.cpus[i].hv);
            assertEquals(i, r.cpus[i].index);
        }
        assertNull(r.m.cpu(4));

        Machine m = new Machine(new MachineConfig().ram(256L << 20).cpus(2).firmware(new byte[4]));
        CpuState a = new CpuState();
        a.index = 2;
        assertThrows(IllegalArgumentException.class, () -> m.attachCpu(a));
        a.index = 1;
        m.attachCpu(a);
        m.attachCpu(a); // idempotent
        CpuState b = new CpuState();
        b.index = 1;
        assertThrows(IllegalStateException.class, () -> m.attachCpu(b));
        assertThrows(IllegalStateException.class, m::reset); // no CPU 0
        assertThrows(IllegalArgumentException.class, () -> m.attachCpus(List.of(b)));
        m.attachCpus(List.of(b, a)); // b becomes CPU 0, a stays CPU 1
        assertEquals(0, b.index);
        m.reset();
        assertFalse(b.halted);
        assertTrue(a.halted);
        assertEquals(m.fdtAddress(), b.gpr[3]);
        m.close();

        assertThrows(IllegalArgumentException.class, () -> new MachineConfig().cpus(0));
        assertThrows(IllegalArgumentException.class, () -> new MachineConfig().cpus(MachineConfig.MAX_CPUS + 1));
    }

    // ---- start-cpu, stop-self, query-cpu-stopped-state -----------------------------------------

    @Test
    void startCpuGivesQemuEntryState() {
        Rig r = Rig.smp(4);
        CpuState c0 = r.cpu;
        CpuState c2 = r.cpus[2];
        // As Linux does it: query, then start-cpu(id, entry, id).
        assertArrayEquals(new int[] {0, 0}, rtas(r, c0, "query-cpu-stopped-state", 2, 2));
        assertArrayEquals(new int[] {0, 2}, rtas(r, c0, "query-cpu-stopped-state", 2, 0));
        c2.gpr[1] = 0x1234;
        c2.gpr[4] = 0x5678;
        assertArrayEquals(new int[] {0}, rtas(r, c0, "start-cpu", 1, 2, (int) START, 2));
        assertFalse(c2.halted);
        assertEquals(START, c2.pc);
        assertEquals(START, c2.npc);
        assertEquals(2, c2.gpr[3]);
        assertEquals(0, c2.gpr[1]);
        assertEquals(0, c2.gpr[4]);
        assertEquals(Msr.SF | Msr.ME, c2.msr);
        // Hash caller (no H_REGISTER_PROC_TBL yet): LPCR 0x20008 plus the exit enables, no radix bits.
        assertEquals(0x3f008L, c2.spr[Spr.LPCR]);
        assertEquals(0, c2.spr[Machine.SPR_PSSCR] & Machine.PSSCR_EC);
        assertTrue(c2.workPending, "the entry state is also posted to the CPU's own thread");
        assertArrayEquals(new int[] {0, 2}, rtas(r, c0, "query-cpu-stopped-state", 2, 2));
        // Already running: hardware error. No such CPU / bad arguments: parameter error.
        assertArrayEquals(new int[] {-1}, rtas(r, c0, "start-cpu", 1, 2, (int) START, 2));
        assertArrayEquals(new int[] {-1}, rtas(r, c0, "start-cpu", 1, 0, (int) START, 0));
        assertArrayEquals(new int[] {-3}, rtas(r, c0, "start-cpu", 1, 4, (int) START, 4));
        assertArrayEquals(new int[] {-3}, rtas(r, c0, "start-cpu", 1, -1, (int) START, 0));
        assertArrayEquals(new int[] {-3}, rtas(r, c0, "start-cpu", 1, 3, (int) START));
        assertArrayEquals(new int[] {-3, 0xdeadbeef}, rtas(r, c0, "query-cpu-stopped-state", 2, 4));
        assertArrayEquals(new int[] {-3}, rtas(r, c0, "query-cpu-stopped-state", 1, 1));

        // The posted copy wins over anything the CPU thread wrote after the direct copy.
        c2.pc = 0x777;
        c2.gpr[3] = 0;
        c2.runPostedWork();
        assertEquals(START, c2.pc);
        assertEquals(2, c2.gpr[3]);
        assertFalse(c2.workPending);
    }

    @Test
    void startedCpuInheritsCallerModes() {
        Rig r = Rig.smp(2);
        CpuState c0 = r.cpu;
        CpuState c1 = r.cpus[1];
        // Radix process table, interrupts little-endian, AIL 3 (what Linux does before SMP).
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_PROC_TBL, 0x18 | 0x04, 0x100000, 0, 4)); // no GTSE
        assertEquals(H.SUCCESS, r.hcall(H.SET_MODE, 1, 4, 0, 0));
        assertEquals(H.SUCCESS, r.hcall(H.SET_MODE, 3, 3, 0, 0));
        // The stopped CPU has the partition-wide updates queued, not applied.
        assertEquals(0, c1.procTable);
        assertTrue(c1.workPending);
        assertArrayEquals(new int[] {0}, rtas(r, c0, "start-cpu", 1, 1, 0x100, 0x1cbf000));
        c1.runPostedWork(); // what the run loop does before the first instruction
        long lpcr = c1.spr[Spr.LPCR];
        assertEquals(c0.spr[Spr.LPCR] & (Spr.LPCR_ILE | Spr.LPCR_AIL_MASK), lpcr & (Spr.LPCR_ILE | Spr.LPCR_AIL_MASK));
        // Radix caller: UPRT, HR and also GTSE (QEMU sets all three), exit enables on.
        long radix = Spr.LPCR_UPRT | Spr.LPCR_HR | Spr.LPCR_GTSE;
        assertEquals(radix, lpcr & radix);
        assertEquals(0, c0.spr[Spr.LPCR] & Spr.LPCR_GTSE);
        assertEquals(Machine.LPCR_PM, lpcr & Machine.LPCR_PM);
        assertEquals(0x100004L, c1.procTable);
        assertEquals(0x1cbf000L, c1.gpr[3]);
    }

    @Test
    void stopSelfAndRestart() {
        Rig r = Rig.smp(2);
        CpuState c0 = r.cpu;
        CpuState c1 = r.cpus[1];
        assertArrayEquals(new int[] {0}, rtas(r, c0, "start-cpu", 1, 1, (int) START, 1));
        c1.runPostedWork();
        // stop-self, called on CPU 1: no return words, the CPU is stopped and quiet.
        r.rtasOn(c1, buf(c1), "stop-self", 0);
        assertTrue(c1.halted);
        assertTrue(c1.ceded);
        assertEquals(Machine.PSSCR_EC, c1.spr[Machine.SPR_PSSCR] & Machine.PSSCR_EC);
        assertEquals(0, c1.spr[Spr.LPCR] & Machine.LPCR_PM);
        assertArrayEquals(new int[] {0, 0}, rtas(r, c0, "query-cpu-stopped-state", 2, 1));
        assertArrayEquals(new int[] {0}, rtas(r, c0, "start-cpu", 1, 1, 0x2000, 7));
        assertFalse(c1.ceded);
        assertEquals(0x2000, c1.pc);
        assertEquals(7, c1.gpr[3]);
        assertArrayEquals(new int[] {0, 2}, rtas(r, c0, "query-cpu-stopped-state", 2, 1));
    }

    // ---- per-CPU hypervisor state --------------------------------------------------------------

    @Test
    void vpaRegistrationIsPerCpu() {
        Rig r = Rig.smp(3);
        long[] vpa = {0x20000, 0x20400, 0x20800};
        for (long v : vpa) {
            r.mem.write16be(v + 4, 640);
        }
        // CPU 0 registers everyone's VPA, as Linux's boot CPU does.
        for (int i = 0; i < 3; i++) {
            assertEquals(H.SUCCESS, r.hcall(H.REGISTER_VPA, 0x0000200000000000L, i, vpa[i]));
            assertEquals(vpa[i], r.m.vpaAddress(i));
            assertEquals(2, r.mem.read8(vpa[i] + 9)); // shared-processor flag
        }
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_VPA, 0x0000200000000000L, 3, vpa[0]));
        // SLB shadow / DTL are per target and need that target's VPA.
        r.mem.write32be(0x21000 + 4, 0x100);
        assertEquals(H.SUCCESS, r.hcallOn(r.cpus[2], H.REGISTER_VPA, 0x0000600000000000L, 1, 0x21000));
        assertEquals(H.RESOURCE, r.hcall(H.REGISTER_VPA, 0x0000a00000000000L, 1, 0)); // CPU 1 has a shadow
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_VPA, 0x0000a00000000000L, 2, 0));
        assertEquals(0, r.m.vpaAddress(2));
        assertEquals(vpa[1], r.m.vpaAddress(1));
        assertEquals(H.RESOURCE, r.hcall(H.REGISTER_VPA, 0x0000600000000000L, 2, 0x21000));
        // Machine reset clears every registration.
        r.m.reset();
        for (int i = 0; i < 3; i++) {
            assertEquals(0, r.m.vpaAddress(i));
        }
    }

    @Test
    void dispatchCounterAndDirectedConfer() {
        Rig r = Rig.smp(2);
        CpuState c1 = r.cpus[1];
        long vpa = 0x20000;
        r.mem.write16be(vpa + 4, 640);
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_VPA, 0x0000200000000000L, 1, vpa));
        long ctr = vpa + Machine.VPA_DISPATCH_COUNTER;
        r.m.cpuExecEnter(c1);
        assertEquals(2, r.mem.read32be(ctr)); // 0 -> 1 would be odd: corrected to 2, like QEMU
        r.m.cpuExecExit(c1);
        assertEquals(3, r.mem.read32be(ctr)); // preempted: odd
        // Directed confer: yields when the sample is current and odd; never waits.
        assertEquals(H.SUCCESS, r.hcall(H.CONFER, 1, 3));
        assertEquals(H.SUCCESS, r.hcall(H.CONFER, 1, 5));
        assertEquals(H.SUCCESS, r.hcall(H.CONFER, 1, 4));
        assertFalse(r.cpu.ceded);
        assertEquals(H.PARAMETER, r.hcall(H.CONFER, 2, 0));
        assertEquals(H.SUCCESS, r.hcall(H.CONFER, -1, 0));
        // Confer to self waits for a prod.
        assertEquals(H.SUCCESS, r.hcall(H.CONFER, 0, 0));
        assertTrue(r.cpu.ceded);
        r.m.cpuExecEnter(c1);
        assertEquals(4, r.mem.read32be(ctr));
    }

    @Test
    void prodWakesTheTarget() throws Exception {
        Rig r = Rig.smp(2);
        CpuState c1 = r.cpus[1];
        assertEquals(H.PARAMETER, r.hcall(H.PROD, 2));
        assertEquals(H.PARAMETER, r.hcall(H.PROD, -1));
        // A prod before the cede makes the cede return at once.
        assertEquals(H.SUCCESS, r.hcall(H.PROD, 1));
        assertTrue(c1.prodded);
        assertEquals(H.SUCCESS, r.hcallOn(c1, H.CEDE));
        assertFalse(c1.ceded);
        assertFalse(c1.prodded);
        assertTrue((c1.msr & Msr.EE) != 0);
        // Ceded, then prodded from another thread: the parked runner is unparked.
        assertEquals(H.SUCCESS, r.hcallOn(c1, H.CEDE));
        assertTrue(c1.ceded);
        boolean[] woke = {false};
        Thread t = new Thread(() -> {
            c1.runner = Thread.currentThread();
            long end = System.nanoTime() + 10_000_000_000L;
            while (!c1.prodded && System.nanoTime() < end) {
                java.util.concurrent.locks.LockSupport.parkNanos(1_000_000_000L);
            }
            woke[0] = c1.prodded;
            r.m.cpuExecEnter(c1);
            c1.ceded = false;
        });
        t.start();
        Thread.sleep(50);
        assertEquals(H.SUCCESS, r.hcall(H.PROD, 1));
        t.join(10_000);
        assertTrue(woke[0]);
        assertFalse(c1.prodded); // consumed when the CPU resumed
    }

    @Test
    void joinNeedsAnotherRunningCpu() {
        Rig r = Rig.smp(2);
        CpuState c0 = r.cpu;
        c0.msr |= Msr.EE;
        assertEquals(H.BAD_MODE, r.hcall(H.JOIN));
        c0.msr &= ~Msr.EE;
        // CPU 1 is stopped: CPU 0 is the last one, so it may not join.
        assertEquals(H.CONTINUE, r.hcall(H.JOIN));
        assertFalse(c0.ceded);
        assertArrayEquals(new int[] {0}, rtas(r, c0, "start-cpu", 1, 1, (int) START, 1));
        r.cpus[1].runPostedWork();
        // CPU 1 runs with EE=0 and not joined: CPU 0 joins (confers to itself).
        assertEquals(H.SUCCESS, r.hcall(H.JOIN));
        assertTrue(c0.ceded);
        c0.ceded = false;
        // CPU 1 ceded with EE=1 does not count as joined either.
        r.hcallOn(r.cpus[1], H.CEDE);
        assertEquals(H.SUCCESS, r.hcall(H.JOIN));
        assertTrue(c0.ceded);
        c0.msr |= Msr.EE;
        assertEquals(H.BAD_MODE, r.hcall(H.JOIN));
    }

    @Test
    void homeNodeAssociativityNamesTheCpu() {
        Rig r = Rig.smp(4);
        assertEquals(H.SUCCESS, r.hcall(H.HOME_NODE_ASSOCIATIVITY, 1, 3));
        // FORM1 before CAS: {0,0,0,node 0,vcpu 3}, padded with -1.
        assertEquals(0L, r.r(4));
        assertEquals(0L, r.r(5));
        assertEquals((3L << 32) | 0xffffffffL, r.r(6));
        assertEquals(-1L, r.r(7));
        assertEquals(H.P2, r.hcall(H.HOME_NODE_ASSOCIATIVITY, 1, 4));
    }

    // ---- XICS across servers -------------------------------------------------------------------

    @Test
    void ipiReachesTheNamedServer() {
        Rig r = Rig.smp(3);
        for (CpuState c : r.cpus) {
            assertEquals(H.SUCCESS, r.hcallOn(c, H.CPPR, 0xff));
        }
        assertEquals(H.SUCCESS, r.hcall(H.IPI, 2, 5));
        assertFalse(r.cpus[0].extIrq);
        assertFalse(r.cpus[1].extIrq);
        assertTrue(r.cpus[2].extIrq);
        assertEquals(H.PARAMETER, r.hcall(H.IPI, 3, 5));
        // H_IPOLL looks at any server without side effects.
        assertEquals(H.SUCCESS, r.hcall(H.IPOLL, 2));
        assertEquals(0xff000002L, r.r(4));
        assertEquals(5, r.r(5));
        assertEquals(H.PARAMETER, r.hcall(H.IPOLL, 3));
        // H_XIRR / H_EOI act on the caller's own presenter.
        assertEquals(H.SUCCESS, r.hcallOn(r.cpus[1], H.XIRR));
        assertEquals(0xff000000L, r.cpus[1].gpr[4]);
        assertEquals(H.SUCCESS, r.hcallOn(r.cpus[2], H.XIRR));
        assertEquals(0xff000002L, r.cpus[2].gpr[4]);
        assertFalse(r.cpus[2].extIrq);
        assertEquals(H.SUCCESS, r.hcallOn(r.cpus[2], H.IPI, 2, 0xff));
        assertEquals(H.SUCCESS, r.hcallOn(r.cpus[2], H.EOI, 0xff000002L));
        assertEquals(0xff000000, r.m.xics().xirr(2));
        assertFalse(r.cpus[2].extIrq);
    }

    @Test
    void setXiveRoutesToAnyServer() {
        Rig r = Rig.smp(4);
        assertArrayEquals(new int[] {0}, rtas(r, r.cpu, "ibm,set-xive", 1, VTY_IRQ, 3, 5));
        assertArrayEquals(new int[] {0, 3, 5}, rtas(r, r.cpu, "ibm,get-xive", 3, VTY_IRQ));
        assertArrayEquals(new int[] {-3}, rtas(r, r.cpu, "ibm,set-xive", 1, VTY_IRQ, 4, 5));
        r.hcallOn(r.cpus[3], H.CPPR, 0xff);
        r.console.type("x");
        assertTrue(r.cpus[3].extIrq);
        assertFalse(r.cpu.extIrq);
        assertEquals(H.SUCCESS, r.hcallOn(r.cpus[3], H.XIRR));
        assertEquals(0xff000000L | VTY_IRQ, r.cpus[3].gpr[4]);
        assertEquals(H.SUCCESS, r.hcallOn(r.cpus[3], H.EOI, 0xff000000L | VTY_IRQ));
    }

    // ---- partition-wide effects ----------------------------------------------------------------

    @Test
    void partitionWideUpdatesReachRunningCpusOnTheirOwnThread() {
        Rig r = Rig.smp(3);
        CpuState c0 = r.cpu;
        CpuState c1 = r.cpus[1];
        CpuState c2 = r.cpus[2];
        assertArrayEquals(new int[] {0}, rtas(r, c0, "start-cpu", 1, 1, (int) START, 1));
        c1.runPostedWork();
        // CPU 1 runs, CPU 2 is stopped. Interrupts little-endian from CPU 0:
        assertEquals(H.SUCCESS, r.hcall(H.SET_MODE, 1, 4, 0, 0));
        assertTrue((c0.spr[Spr.LPCR] & Spr.LPCR_ILE) != 0); // caller: at once
        assertEquals(0, c1.spr[Spr.LPCR] & Spr.LPCR_ILE); // others: posted, stopped or not
        assertEquals(0, c2.spr[Spr.LPCR] & Spr.LPCR_ILE);
        assertTrue(c1.workPending && c2.workPending);
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_PROC_TBL, 0x1d, 0x100000, 0, 4));
        assertEquals(0, c1.procTable);
        assertEquals(0, c2.procTable);
        c1.runPostedWork();
        c2.runPostedWork();
        assertTrue((c2.spr[Spr.LPCR] & Spr.LPCR_ILE) != 0);
        assertEquals(0x100004L, c2.procTable);
        assertTrue((c1.spr[Spr.LPCR] & Spr.LPCR_ILE) != 0);
        assertEquals(0x100004L, c1.procTable);
        long radix = Spr.LPCR_UPRT | Spr.LPCR_HR | Spr.LPCR_GTSE;
        for (CpuState c : r.cpus) {
            assertEquals(radix, c.spr[Spr.LPCR] & radix);
        }
        // AIL from CPU 1 this time.
        assertEquals(H.SUCCESS, r.hcallOn(c1, H.SET_MODE, 3, 3, 0, 0));
        assertEquals(3L << Spr.LPCR_AIL_SHIFT, c1.spr[Spr.LPCR] & Spr.LPCR_AIL_MASK);
        assertEquals(0, c0.spr[Spr.LPCR] & Spr.LPCR_AIL_MASK);
        c0.runPostedWork();
        c2.runPostedWork();
        assertEquals(3L << Spr.LPCR_AIL_SHIFT, c2.spr[Spr.LPCR] & Spr.LPCR_AIL_MASK);
        assertEquals(3L << Spr.LPCR_AIL_SHIFT, c0.spr[Spr.LPCR] & Spr.LPCR_AIL_MASK);
    }

    @Test
    void casChecksOtherCpusAndSetsCompatEverywhere() {
        Rig r = Rig.smp(2);
        CpuState c1 = r.cpus[1];
        // ISA 2.07 compat only: PCR 2.07 on every CPU (posted to the stopped CPU 1).
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(256);
        bb.putInt(0xffffffff).putInt(0x0f000004).putInt(0).putInt(0xffffffff);
        bb.put((byte) 4).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        bb.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
        bb.put((byte) 25).put(DeviceTreeTest.FREEBSD_OV5);
        r.writeBytes(DeviceTreeTest.CAS_VEC, java.util.Arrays.copyOf(bb.array(), bb.position()));
        assertEquals(H.SUCCESS, r.hcall(H.KVMPPC_H_CAS, DeviceTreeTest.CAS_VEC, DeviceTreeTest.CAS_BUF, 0x100000));
        assertEquals(Machine.PCR_COMPAT_2_07, r.cpu.spr[Machine.SPR_PCR]);
        c1.runPostedWork();
        assertEquals(Machine.PCR_COMPAT_2_07, c1.spr[Machine.SPR_PCR]);
        assertEquals(0x0f000004, r.m.buildDeviceTree(false).find("cpus/PowerPC,POWER9@1").getCell("cpu-version", 0));

        // With CPU 1 running (and not idle) CAS is refused.
        assertArrayEquals(new int[] {0}, rtas(r, r.cpu, "start-cpu", 1, 1, (int) START, 1));
        c1.runPostedWork();
        assertEquals(H.MULTI_THREADS_ACTIVE, r.hcall(H.KVMPPC_H_CAS, DeviceTreeTest.CAS_VEC, DeviceTreeTest.CAS_BUF, 0x100000));
        r.hcallOn(c1, H.CEDE);
        assertEquals(H.SUCCESS, r.hcall(H.KVMPPC_H_CAS, DeviceTreeTest.CAS_VEC, DeviceTreeTest.CAS_BUF, 0x100000));
        assertEquals(0, Machine.pcrForCompat(Machine.PVR_LOGICAL_3_00));
        assertEquals(0xe, Machine.pcrForCompat(Machine.PVR_LOGICAL_2_05));
    }

    @Test
    void systemResetTargets() {
        Rig r = Rig.smp(3);
        List<String> hits = new ArrayList<>();
        r.m.setSystemResetHandler(c -> hits.add(c.index + "@" + Thread.currentThread().getName()));
        String me = Thread.currentThread().getName();
        // CPU 1 running, CPU 2 stopped.
        assertArrayEquals(new int[] {0}, rtas(r, r.cpu, "start-cpu", 1, 1, (int) START, 1));
        r.cpus[1].runPostedWork();
        assertEquals(H.SUCCESS, r.hcall(H.SIGNAL_SYS_RESET, -1));
        assertEquals(List.of("0@" + me), hits); // self at once; CPU 1 posted; CPU 2 skipped
        assertTrue(r.cpus[1].workPending);
        assertFalse(r.cpus[2].workPending);
        r.cpus[1].runPostedWork();
        assertEquals(List.of("0@" + me, "1@" + me), hits);
        hits.clear();
        assertEquals(H.SUCCESS, r.hcall(H.SIGNAL_SYS_RESET, -2));
        assertTrue(hits.isEmpty());
        r.cpus[1].runPostedWork();
        assertEquals(List.of("1@" + me), hits);
        // Unicast reaches a stopped CPU too.
        assertEquals(H.SUCCESS, r.hcall(H.SIGNAL_SYS_RESET, 2));
        assertTrue(r.cpus[2].workPending);
        assertEquals(H.PARAMETER, r.hcall(H.SIGNAL_SYS_RESET, 3));
    }

    @Test
    void shutdownStopsEveryCpu() {
        Rig r = Rig.smp(4);
        assertArrayEquals(new int[] {0}, rtas(r, r.cpu, "power-off", 1, 0, 0));
        assertEquals(Machine.ShutdownReason.POWER_OFF, r.m.shutdownReason());
        for (CpuState c : r.cpus) {
            assertTrue(c.stopRequested);
        }
        // ibm,os-term from a secondary as well.
        Rig r2 = Rig.smp(2);
        r2.writeBytes(0x30000, "oops\0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertArrayEquals(new int[] {0}, rtas(r2, r2.cpu, "start-cpu", 1, 1, (int) START, 1));
        rtas(r2, r2.cpus[1], "ibm,os-term", 1, 0x30000);
        assertEquals(Machine.ShutdownReason.PANIC, r2.m.shutdownReason());
        assertTrue(r2.cpu.stopRequested && r2.cpus[1].stopRequested);
    }

    @Test
    void splparCharacteristicsCountCpus() {
        Rig r = Rig.smp(4);
        assertArrayEquals(new int[] {0}, rtas(r, r.cpu, "ibm,get-system-parameter", 1, 20, 0x30000, 256));
        int len = r.mem.read16be(0x30000);
        String v = new String(r.readBytes(0x30002, len - 1), java.nio.charset.StandardCharsets.US_ASCII);
        assertEquals("MaxEntCap=4,DesMem=256,DesProcs=4,MaxPlatProcs=4", v);
    }

    @Test
    void resetStopsSecondariesAgain() {
        Rig r = Rig.smp(2);
        CpuState c1 = r.cpus[1];
        assertArrayEquals(new int[] {0}, rtas(r, r.cpu, "start-cpu", 1, 1, (int) START, 1));
        r.hcall(H.SET_MODE, 1, 4, 0, 0);
        r.m.reset();
        assertTrue(c1.halted);
        assertFalse(c1.workPending);
        assertEquals(0x20008L, c1.spr[Spr.LPCR]);
        assertEquals(0x3f008L, r.cpu.spr[Spr.LPCR]);
        assertEquals(0x100, c1.pc);
        assertEquals(Msr.ME | Msr.FP, c1.msr);
    }
}
