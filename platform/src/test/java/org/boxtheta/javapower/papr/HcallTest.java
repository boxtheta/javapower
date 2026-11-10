package org.boxtheta.javapower.papr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.Spr;
import org.junit.jupiter.api.Test;

/** Miscellaneous hypercalls, with return values as QEMU's hw/ppc/spapr_hcall.c produces them. */
class HcallTest {
    @Test
    void unknownHcallIsHFunctionAndLogged() {
        Rig r = new Rig();
        assertEquals(H.FUNCTION, r.hcall(0x1234 & ~3));
        assertEquals(H.FUNCTION, r.hcall(0x3));
        assertEquals(H.FUNCTION, r.hcall(0xf00c));
        assertTrue(r.m.log().unimplementedCounts().containsKey("hcall-0x1234"));
        // H_RANDOM is only registered with an spapr-rng device, as in QEMU.
        assertEquals(H.FUNCTION, r.hcall(H.RANDOM));
        Rig rng = new Rig(new MachineConfig().ram(256L << 20).rng(true));
        assertEquals(H.SUCCESS, rng.hcall(H.RANDOM));
        assertTrue(rng.m.buildDeviceTree(true).find("ibm,platform-facilities/ibm,random-v1") != null);
    }

    @Test
    void setModeEndiannessAndAil() {
        Rig r = new Rig();
        assertEquals(H.SUCCESS, r.hcall(H.SET_MODE, 1, 4, 0, 0));
        assertTrue((r.cpu.spr[Spr.LPCR] & Spr.LPCR_ILE) != 0);
        assertEquals(H.SUCCESS, r.hcall(H.SET_MODE, 0, 4, 0, 0));
        assertEquals(0, r.cpu.spr[Spr.LPCR] & Spr.LPCR_ILE);
        assertEquals(H.UNSUPPORTED_FLAG, r.hcall(H.SET_MODE, 2, 4, 0, 0));
        assertEquals(H.P3, r.hcall(H.SET_MODE, 1, 4, 1, 0));
        assertEquals(H.P4, r.hcall(H.SET_MODE, 1, 4, 0, 1));
        assertEquals(H.SUCCESS, r.hcall(H.SET_MODE, 3, 3, 0, 0));
        assertEquals(3L << Spr.LPCR_AIL_SHIFT, r.cpu.spr[Spr.LPCR] & Spr.LPCR_AIL_MASK);
        assertEquals(H.UNSUPPORTED_FLAG, r.hcall(H.SET_MODE, 2, 3, 0, 0));
        assertEquals(H.SUCCESS, r.hcall(H.SET_MODE, 0, 3, 0, 0));
        assertEquals(0, r.cpu.spr[Spr.LPCR] & Spr.LPCR_AIL_MASK);
        assertEquals(H.P2, r.hcall(H.SET_MODE, 0, 9, 0, 0));
        assertEquals(H.P2, r.hcall(H.SET_MODE, 0, 5, 0, 0)); // DAWR1 is POWER10
        assertEquals(H.SUCCESS, r.hcall(H.SET_MODE, 0, 2, 0x1000, 0x3));
        assertEquals(H.P4, r.hcall(H.SET_MODE, 0, 2, 0x1000, 0x4)); // DAWRX HYP bit
        assertEquals(0x1000, r.cpu.spr[Spr.DAWR0]);
        assertEquals(H.SUCCESS, r.hcall(H.SET_MODE, 0, 1, 0x2001, 0));
        assertEquals(0x2001, r.cpu.spr[Spr.CIABR]);
        assertEquals(H.P3, r.hcall(H.SET_MODE, 0, 1, 0x2003, 0));
    }

    @Test
    void registerRadixProcessTable() {
        Rig r = new Rig();
        // flags: MODIFY | REGISTER | RADIX | GTSE, table at 1 MiB, 64 KiB (size field 4).
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_PROC_TBL, 0x1d, 0x100000, 0, 4));
        assertEquals(0x100004L, r.cpu.procTable);
        assertEquals(Machine.PATE1_GR | 0x100004L, r.m.patbEntry());
        long lpcr = r.cpu.spr[Spr.LPCR];
        assertTrue((lpcr & Spr.LPCR_UPRT) != 0 && (lpcr & Spr.LPCR_HR) != 0 && (lpcr & Spr.LPCR_GTSE) != 0);
        // Keep the registration: flags must agree on radix.
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_PROC_TBL, 0x05, 0, 0, 0));
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_PROC_TBL, 0x01, 0, 0, 0));
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_PROC_TBL, 0x20, 0, 0, 0));
        assertEquals(H.P2, r.hcall(H.REGISTER_PROC_TBL, 0x1c, 0x100800, 0, 4));
        assertEquals(H.P3, r.hcall(H.REGISTER_PROC_TBL, 0x1c, 0x100000, 1, 4));
        assertEquals(H.P4, r.hcall(H.REGISTER_PROC_TBL, 0x1c, 0x100000, 0, 25));
        // Deregister keeps GR.
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_PROC_TBL, 0x10, 0, 0, 0));
        assertEquals(Machine.PATE1_GR, r.m.patbEntry());
        assertEquals(0, r.cpu.procTable);
        assertEquals(0, r.cpu.spr[Spr.LPCR] & (Spr.LPCR_UPRT | Spr.LPCR_HR | Spr.LPCR_GTSE));
        // After reset the CPU is back in (unregistered) hash mode.
        r.m.reset(r.cpu);
        assertEquals(0, r.m.patbEntry());
        assertEquals(0x3f008L, r.cpu.spr[Spr.LPCR]);
    }

    @Test
    void cedeProdConferJoin() {
        Rig r = new Rig();
        r.cpu.msr &= ~Msr.EE;
        assertEquals(H.SUCCESS, r.hcall(H.CEDE));
        assertTrue(r.cpu.ceded);
        assertTrue((r.cpu.msr & Msr.EE) != 0);
        r.cpu.ceded = false;
        // A prod makes the next cede return at once.
        assertEquals(H.SUCCESS, r.hcall(H.PROD, 0));
        assertEquals(H.SUCCESS, r.hcall(H.CEDE));
        assertFalse(r.cpu.ceded);
        assertEquals(H.PARAMETER, r.hcall(H.PROD, 7));
        // A pending external interrupt also keeps the CPU running.
        r.cpu.extIrq = true;
        assertEquals(H.SUCCESS, r.hcall(H.CEDE));
        assertFalse(r.cpu.ceded);
        r.cpu.extIrq = false;
        assertEquals(H.SUCCESS, r.hcall(H.CONFER, -1, 0));
        assertFalse(r.cpu.ceded);
        assertEquals(H.PARAMETER, r.hcall(H.CONFER, 3, 0));
        assertEquals(H.SUCCESS, r.hcall(H.CONFER, 0, 0));
        assertTrue(r.cpu.ceded);
        r.cpu.ceded = false;
        r.cpu.msr |= Msr.EE;
        assertEquals(H.BAD_MODE, r.hcall(H.JOIN));
        r.cpu.msr &= ~Msr.EE;
        assertEquals(H.CONTINUE, r.hcall(H.JOIN)); // the last CPU cannot join
    }

    @Test
    void registerVpaSlbShadowDtl() {
        Rig r = new Rig();
        long vpa = 0x20000;
        r.mem.write16be(vpa + 4, 640);
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_VPA, 0x0000200000000000L, 0, vpa + 64));
        assertEquals(H.HARDWARE, r.hcall(H.REGISTER_VPA, 0x0000200000000000L, 0, 0));
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_VPA, 0x0000200000000000L, 1, vpa));
        // SLB shadow before a VPA: resource error.
        r.mem.write32be(0x21004, 0x100);
        assertEquals(H.RESOURCE, r.hcall(H.REGISTER_VPA, 0x0000600000000000L, 0, 0x21000));
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_VPA, 0x0000200000000000L, 0, vpa));
        assertEquals(vpa, r.m.vpaAddress());
        assertEquals(2, r.mem.read8(vpa + 9)); // shared-processor flag
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_VPA, 0x0000600000000000L, 0, 0x21000));
        r.mem.write32be(0x22004, 48);
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_VPA, 0x0000400000000000L, 0, 0x22000));
        assertEquals(H.RESOURCE, r.hcall(H.REGISTER_VPA, 0x0000a00000000000L, 0, vpa));
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_VPA, 0x0000e00000000000L, 0, 0));
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_VPA, 0x0000c00000000000L, 0, 0));
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_VPA, 0x0000a00000000000L, 0, vpa));
        assertEquals(0, r.m.vpaAddress());
        // VPA crossing a page.
        r.mem.write16be(0x20f80 + 4, 640);
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_VPA, 0x0000200000000000L, 0, 0x20f80));
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_VPA, 0x1234L, 0, vpa));
    }

    @Test
    void sprAndDebugRegisters() {
        Rig r = new Rig();
        assertEquals(H.SUCCESS, r.hcall(H.SET_SPRG0, 0xabcdef));
        assertEquals(0xabcdef, r.cpu.spr[Spr.SPRG0]);
        assertEquals(H.HARDWARE, r.hcall(H.SET_DABR, 0x1000));
        assertEquals(H.HARDWARE, r.hcall(H.SET_XDABR, 0x1000, 3));
    }

    @Test
    void pageInitAndLogicalAccess() {
        Rig r = new Rig();
        r.mem.fill(0x30000, 4096, (byte) 0x5a);
        r.mem.fill(0x31000, 4096, (byte) 0x11);
        long zero = 1L << (63 - 48);
        long copy = 1L << (63 - 49);
        assertEquals(H.SUCCESS, r.hcall(H.PAGE_INIT, zero, 0x30000, 0));
        assertEquals(0, r.mem.read64be(0x30ff8));
        assertEquals(H.SUCCESS, r.hcall(H.PAGE_INIT, copy, 0x30000, 0x31000));
        assertEquals(0x1111111111111111L, r.mem.read64be(0x30800));
        assertEquals(H.PARAMETER, r.hcall(H.PAGE_INIT, zero, 0x30008, 0));
        assertEquals(H.PARAMETER, r.hcall(H.PAGE_INIT, 1, 0x30000, 0));
        assertEquals(H.PARAMETER, r.hcall(H.PAGE_INIT, copy, 0x30000, 0x31001));

        r.mem.write64be(0x32000, 0x0102030405060708L);
        assertEquals(H.SUCCESS, r.hcall(H.LOGICAL_CI_LOAD, 4, 0x32000));
        assertEquals(0x01020304L, r.r(4));
        assertEquals(H.SUCCESS, r.hcall(H.LOGICAL_CACHE_LOAD, 8, 0x32000));
        assertEquals(0x0102030405060708L, r.r(4));
        assertEquals(H.SUCCESS, r.hcall(H.LOGICAL_CI_LOAD, 1, 0x32001));
        assertEquals(2, r.r(4));
        assertEquals(H.PARAMETER, r.hcall(H.LOGICAL_CI_LOAD, 3, 0x32000));
        assertEquals(H.SUCCESS, r.hcall(H.LOGICAL_CI_STORE, 2, 0x32000, 0xbeef));
        assertEquals(0xbeef0304, r.mem.read32be(0x32000));
        assertEquals(H.SUCCESS, r.hcall(H.LOGICAL_ICBI));
        assertEquals(H.SUCCESS, r.hcall(H.LOGICAL_DCBF));

        // KVMPPC_H_LOGICAL_MEMOP: copy 4 words, then an overlapping inverted copy.
        for (int i = 0; i < 4; i++) {
            r.mem.write32be(0x33000 + 4 * i, i + 1);
        }
        assertEquals(H.SUCCESS, r.hcall(H.KVMPPC_H_LOGICAL_MEMOP, 0x34000, 0x33000, 2, 4, 0));
        assertEquals(4, r.mem.read32be(0x3400c));
        assertEquals(H.SUCCESS, r.hcall(H.KVMPPC_H_LOGICAL_MEMOP, 0x34004, 0x34000, 2, 3, 1));
        assertEquals(~1, r.mem.read32be(0x34004));
        assertEquals(~3, r.mem.read32be(0x3400c));
        assertEquals(H.PARAMETER, r.hcall(H.KVMPPC_H_LOGICAL_MEMOP, 0x34001, 0x33000, 2, 1, 0));
        assertEquals(H.PARAMETER, r.hcall(H.KVMPPC_H_LOGICAL_MEMOP, 0x34000, 0x33000, 2, 1, 2));
    }

    @Test
    void cpuCharacteristicsAndAssociativity() {
        Rig r = new Rig();
        assertEquals(H.SUCCESS, r.hcall(H.GET_CPU_CHARACTERISTICS));
        // SPEC_BAR_ORI31 | L1D_FLUSH_ORI30 | L1D_FLUSH_TRIG2 | L1D_THREAD_PRIV | HON_BRANCH_HINTS
        // | BCCTR_FLUSH_ASSIST
        assertEquals(0xbc40000000000000L, r.r(4));
        // FAVOUR_SECURITY | L1D_FLUSH_PR | BNDS_CHK_SPEC_BAR | FLUSH_COUNT_CACHE
        assertEquals(0xe400000000000000L, r.r(5));

        assertEquals(H.FUNCTION, r.hcall(H.HOME_NODE_ASSOCIATIVITY, 2, 0));
        assertEquals(H.P2, r.hcall(H.HOME_NODE_ASSOCIATIVITY, 1, 1));
        assertEquals(H.SUCCESS, r.hcall(H.HOME_NODE_ASSOCIATIVITY, 1, 0));
        assertEquals(0L, r.r(4));
        assertEquals(0L, r.r(5));
        assertEquals(0x00000000ffffffffL, r.r(6));
        assertEquals(-1L, r.r(7));
        assertEquals(-1L, r.r(9));
    }

    @Test
    void hashMmuCallsFailForRadixOnlyMachine() {
        Rig r = new Rig();
        assertEquals(H.PARAMETER, r.hcall(H.ENTER, 0, 0, 0, 0));
        assertEquals(H.PARAMETER, r.hcall(H.REMOVE, 0, 0, 0));
        assertEquals(H.FUNCTION, r.hcall(H.CLEAR_MOD, 0, 0));
        assertEquals(H.FUNCTION, r.hcall(H.CLEAN_SLB));
        assertEquals(H.FUNCTION, r.hcall(H.INVALIDATE_PID));
        r.hcall(H.REGISTER_PROC_TBL, 0x1c, 0x100000, 0, 4);
        assertEquals(H.NOT_AVAILABLE, r.hcall(H.RESIZE_HPT_PREPARE, 0, 24));
    }

    @Test
    void systemResetSignal() {
        Rig r = new Rig();
        assertEquals(H.SUCCESS, r.hcall(H.SIGNAL_SYS_RESET, -2));
        assertEquals(H.PARAMETER, r.hcall(H.SIGNAL_SYS_RESET, -3));
        assertEquals(H.PARAMETER, r.hcall(H.SIGNAL_SYS_RESET, 5));
        assertEquals(H.HARDWARE, r.hcall(H.SIGNAL_SYS_RESET, 0));
        int[] hits = new int[1];
        r.m.setSystemResetHandler(c -> hits[0]++);
        assertEquals(H.SUCCESS, r.hcall(H.SIGNAL_SYS_RESET, -1));
        assertEquals(1, hits[0]);
    }

    @Test
    void watchdog() throws InterruptedException {
        Rig r = new Rig();
        assertEquals(H.SUCCESS, r.hcall(H.WATCHDOG, 3L << 8, 0, 0));
        assertEquals((1L << 48) | (4L << 32), r.r(4));
        assertEquals(H.P2, r.hcall(H.WATCHDOG, (1L << 8) | 1, 5, 1000));
        assertEquals(H.P3, r.hcall(H.WATCHDOG, (1L << 8) | 1, 1, 1));
        assertEquals(H.PARAMETER, r.hcall(H.WATCHDOG, (1L << 8) | 4, 1, 1000));
        assertEquals(H.PARAMETER, r.hcall(H.WATCHDOG, 1L << 20, 1, 1000));
        assertEquals(H.NOOP, r.hcall(H.WATCHDOG, 2L << 8, 1, 0));
        assertEquals(H.SUCCESS, r.hcall(H.WATCHDOG, (1L << 8) | 1, 1, 100000));
        assertEquals(H.SUCCESS, r.hcall(H.WATCHDOG, 2L << 8, -1, 0));
        assertEquals(H.NOOP, r.hcall(H.WATCHDOG, 2L << 8, -1, 0));
        assertEquals(H.SUCCESS, r.hcall(H.WATCHDOG, 4L << 8, 2, 0));
        assertEquals(2, r.r(4));
        // Restart action, 2 ms.
        assertEquals(H.SUCCESS, r.hcall(H.WATCHDOG, (1L << 8) | 2, 1, 2));
        r.m.pump();
        assertFalse(r.cpu.stopRequested);
        Thread.sleep(20);
        r.m.pump();
        assertTrue(r.cpu.stopRequested);
        assertEquals(Machine.ShutdownReason.REBOOT, r.m.shutdownReason());
    }
}
