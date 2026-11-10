package org.boxtheta.javapower.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.core.PpcException;
import org.boxtheta.javapower.core.Spr;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Run-loop, timer, interrupt and idle behaviour that the QEMU golden vectors cannot pin down
 * (wall-clock timing, asynchronous interrupts, H_CEDE). Expectations follow QEMU's ppc.c
 * (level-triggered DEC raised when DEC reaches 0), excp_helper.c (delivery, AIL, ILE) and
 * spapr's H_CEDE (sets MSR[EE], halts until an exit-enabled interrupt is pending).
 */
class InterpreterTest {
    static final long CODE = 0x1000;
    static final long MSR64LE = Msr.SF | Msr.ME | Msr.LE;
    static final int NOP = 0x60000000;
    static final int SC = 0x44000002;
    static final int HCALL = 0x44000022;

    final PhysicalMemory pm = new PhysicalMemory(4 << 20);
    final CpuState s = new CpuState();
    final DecodeTable table = new DecodeTable();
    Interpreter cpu;
    final List<int[]> interrupts = new ArrayList<>(); // {vector, icount}

    InterpreterTest() {
        table.addAll(new IntInsns());
    }

    void boot(Interpreter.Clock clock, int... code) {
        cpu = new Interpreter(s, pm, table, clock);
        cpu.resetPapr();
        s.spr[Spr.LPCR] |= Spr.LPCR_ILE;
        s.msr = MSR64LE;
        s.pc = CODE;
        load(CODE, code);
        cpu.interruptHook = (st, vec, srr0) -> {
            if (interrupts.size() < 2 && System.getenv("JP_DEBUG") != null) {
                System.err.printf("int %x srr0=%x srr1=%x insn=%08x%n", vec, srr0, st.spr[Spr.SRR1], pm.read32le(srr0));
            }
            interrupts.add(new int[] {vec, (int) st.icount});
            st.stopRequested = true; // each test looks at the first interrupt
        };
    }

    void load(long addr, int... code) {
        for (int k = 0; k < code.length; k++) {
            pm.write32le(addr + 4L * k, code[k]);
        }
    }

    // ---- a few instruction encoders (ISA field order)
    static int addi(int rt, int ra, int si) {
        return (14 << 26) | (rt << 21) | (ra << 16) | (si & 0xffff);
    }

    static int ori(int ra, int rs, int ui) {
        return (24 << 26) | (rs << 21) | (ra << 16) | (ui & 0xffff);
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

    static int mtmsrdL(int rs) {
        return (31 << 26) | (rs << 21) | (1 << 16) | (178 << 1);
    }

    static int branch(int off) {
        return (18 << 26) | (off & 0x03fffffc);
    }

    static int tw(int to) {
        return (31 << 26) | (to << 21) | (4 << 1);
    }

    // ================================================================ timebase / decrementer

    @Test
    void decrementerCountsAt512MHzOfVirtualTime() {
        boot(Interpreter.Clock.DETERMINISTIC, addi(3, 0, 10000), mtspr(Spr.DEC, 3),
                NOP, NOP, NOP, NOP, NOP, NOP, NOP, NOP, NOP, NOP, NOP, NOP,
                mfspr(4, Spr.DEC), SC);
        cpu.icountShift = 4; // 16 ns per instruction
        cpu.run(15);
        // mtdec retired at icount 1 (time 16 ns); mfdec executes at icount 14 (224 ns).
        long expect = 10000 - (Interpreter.nsToTb(14 << 4) - Interpreter.nsToTb(1 << 4));
        assertEquals(expect, s.gpr[4]);
        assertEquals(Interpreter.nsToTb(15 << 4), cpu.tb());
    }

    @Test
    void decrementerInterruptWhenDecReachesZero() {
        boot(Interpreter.Clock.DETERMINISTIC, addi(3, 0, 100), mtspr(Spr.DEC, 3), ori(5, 0, 0x8000),
                mtmsrdL(5), branch(0));
        cpu.run(10_000);
        assertEquals(1, interrupts.size());
        assertEquals(PpcException.DECREMENTER, interrupts.get(0)[0]);
        // DEC was written at icount 1 (TB 0): the interrupt is taken once TB reaches 100.
        long due = Interpreter.tbToNsCeil(Interpreter.nsToTb(1) + 100);
        assertEquals(due, interrupts.get(0)[1]);
        assertEquals(CODE + 16, s.spr[Spr.SRR0]);
        assertEquals(0x900, s.pc);
        // Level-triggered: still pending until DEC is rewritten.
        assertTrue(cpu.decPending());
    }

    @Test
    void negativeDecIsDeliveredWhenEeIsSet() {
        boot(Interpreter.Clock.DETERMINISTIC, addi(3, 0, -1), mtspr(Spr.DEC, 3), NOP, NOP,
                ori(5, 0, 0x8000), mtmsrdL(5), NOP, SC);
        cpu.run(100);
        assertEquals(PpcException.DECREMENTER, interrupts.get(0)[0]);
        assertEquals(CODE + 24, s.spr[Spr.SRR0]); // right after mtmsrd
    }

    @Test
    void largeDecrementerIsSignExtendedFrom56Bits() {
        boot(Interpreter.Clock.DETERMINISTIC);
        cpu.storeDec(0x00FFFFFFFFFFFFFFL);
        assertEquals(-1L, cpu.loadDec());
        assertTrue(cpu.decPending());
        cpu.storeDec(0x007FFFFFFFFFFFFFL);
        assertFalse(cpu.decPending());
        s.spr[Spr.LPCR] &= ~Spr.LPCR_LD;
        cpu.storeDec(0xFFFFFFFFL);
        assertEquals(0xFFFFFFFFL, cpu.loadDec());
        assertTrue(cpu.decPending());
    }

    @Test
    void setTimebaseKeepsTheDecrementer() {
        boot(Interpreter.Clock.DETERMINISTIC);
        s.icount = 5000;
        cpu.storeDec(1234);
        cpu.setTimebase(0x123456789L);
        assertEquals(0x123456789L, cpu.tb());
        assertEquals(1234, cpu.loadDec());
    }

    @Test
    void wallClockTimebaseRunsAt512MHz() throws InterruptedException {
        boot(Interpreter.Clock.WALL);
        long t0 = System.nanoTime();
        long tb0 = cpu.tb();
        Thread.sleep(50);
        long tb1 = cpu.tb();
        long ns = System.nanoTime() - t0;
        double rate = (tb1 - tb0) * 1e9 / ns;
        assertTrue(Math.abs(rate - 512e6) < 512e6 * 0.05, "rate " + rate);
    }

    @Test
    @Timeout(10)
    void wallClockDecrementerFires() {
        boot(Interpreter.Clock.WALL, addi(3, 0, 0x7fff), ori(3, 3, 0), mtspr(Spr.DEC, 3), ori(5, 0, 0x8000),
                mtmsrdL(5), branch(0));
        long t0 = System.nanoTime();
        while (interrupts.isEmpty() && System.nanoTime() - t0 < 5_000_000_000L) {
            cpu.run(1_000_000);
        }
        assertFalse(interrupts.isEmpty(), "decrementer did not fire");
        assertEquals(PpcException.DECREMENTER, interrupts.get(0)[0]);
        assertTrue(System.nanoTime() - t0 >= 60_000, "0x7fff ticks is 64 us");
    }

    // ================================================================ external interrupt, idle

    @Test
    void externalInterruptGatedByEe() {
        boot(Interpreter.Clock.DETERMINISTIC, NOP, NOP, ori(5, 0, 0x8000), mtmsrdL(5), branch(0));
        s.setExternalIrq(true);
        cpu.run(2);
        assertTrue(interrupts.isEmpty());
        cpu.run(100);
        assertEquals(PpcException.EXTERNAL, interrupts.get(0)[0]);
        assertEquals(CODE + 16, s.spr[Spr.SRR0]);
        assertEquals(MSR64LE | Msr.EE, s.spr[Spr.SRR1]);
        assertEquals(Msr.SF | Msr.ME | Msr.LE, s.msr);
    }

    @Test
    void externalBeatsDecrementer() {
        boot(Interpreter.Clock.DETERMINISTIC, addi(3, 0, -1), mtspr(Spr.DEC, 3), ori(5, 0, 0x8000),
                mtmsrdL(5), branch(0));
        s.setExternalIrq(true);
        cpu.run(100);
        assertEquals(PpcException.EXTERNAL, interrupts.get(0)[0]);
    }

    /** H_CEDE as spapr does it: MSR[EE]=1 and halt; the CPU resumes on an exit-enabled interrupt. */
    private void installCede() {
        s.hv = st -> {
            st.msr |= Msr.EE;
            st.ceded = true;
            st.gpr[3] = 0;
        };
    }

    @Test
    @Timeout(10)
    void cedeParksUntilExternalInterrupt() throws InterruptedException {
        boot(Interpreter.Clock.WALL, addi(3, 0, 0xe0), HCALL, branch(0));
        installCede();
        cpu.storeDec(0x7fffffffffL); // far away
        Thread t = new Thread(() -> cpu.run());
        t.start();
        Thread.sleep(100);
        assertTrue(s.ceded, "parked in H_CEDE");
        assertTrue(interrupts.isEmpty());
        s.setExternalIrq(true);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (interrupts.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        s.stopRequested = true;
        s.wake();
        t.join(5000);
        assertFalse(t.isAlive());
        assertEquals(PpcException.EXTERNAL, interrupts.get(0)[0]);
        assertEquals(CODE + 8, s.spr[Spr.SRR0]);
        assertFalse(s.ceded);
    }

    @Test
    void deterministicCedeJumpsToTheDecrementer() {
        boot(Interpreter.Clock.DETERMINISTIC, addi(4, 0, 0x7fff), mtspr(Spr.DEC, 4), addi(3, 0, 0xe0), HCALL,
                branch(0));
        installCede();
        cpu.run(100);
        assertEquals(PpcException.DECREMENTER, interrupts.get(0)[0]);
        assertEquals(Interpreter.tbToNsCeil(Interpreter.nsToTb(1) + 0x7fff), interrupts.get(0)[1]);
        assertEquals(CODE + 16, s.spr[Spr.SRR0]);
    }

    @Test
    void waitWithEeClearResumesWithoutInterrupt() {
        boot(Interpreter.Clock.DETERMINISTIC, addi(4, 0, 500), mtspr(Spr.DEC, 4), (31 << 26) | (30 << 1), SC);
        cpu.run(100);
        assertEquals(1, interrupts.size());
        assertEquals(PpcException.SYSCALL, interrupts.get(0)[0]);
        assertTrue(s.icount >= Interpreter.tbToNsCeil(500));
    }

    @Test
    @Timeout(10)
    void stopRequestEndsAnInfiniteLoop() throws InterruptedException {
        boot(Interpreter.Clock.WALL, branch(0));
        Thread t = new Thread(() -> cpu.run());
        t.start();
        Thread.sleep(50);
        s.stopRequested = true;
        t.join(5000);
        assertFalse(t.isAlive());
        assertEquals(CODE, s.pc);
        assertTrue(s.icount > 1000);
    }

    // ================================================================ hooks, decode cache

    @Test
    void traceHookSeesEveryInstruction() {
        boot(Interpreter.Clock.DETERMINISTIC, addi(3, 0, 1), addi(3, 3, 1), SC);
        List<Long> pcs = new ArrayList<>();
        List<Long> r3 = new ArrayList<>();
        cpu.trace = (st, pc, insn) -> {
            pcs.add(pc);
            r3.add(st.gpr[3]);
        };
        cpu.run(3);
        assertEquals(List.of(CODE, CODE + 4, CODE + 8), pcs);
        assertEquals(List.of(0L, 1L, 2L), r3);
    }

    @Test
    void unimplementedInstructionsAreReportedAndRaiseIllegal() {
        boot(Interpreter.Clock.DETERMINISTIC, 0xfc00002a /* fadd: not in this table */, SC);
        AtomicInteger seen = new AtomicInteger();
        cpu.unimplementedHook = (st, pc, insn, key) -> {
            assertEquals(CODE, pc);
            assertEquals("63/21", key);
            seen.incrementAndGet();
        };
        cpu.run(1);
        assertEquals(1, seen.get());
        assertEquals(PpcException.PROGRAM, interrupts.get(0)[0]);
        assertEquals(PpcException.SRR1_ILLEGAL, s.spr[Spr.SRR1] & PpcException.SRR1_ILLEGAL);
        assertEquals(1L, cpu.unimplementedCounts().get("63/21"));
        assertEquals("unimplemented", cpu.disasmName(0xfc00002a));
        assertEquals("addi", cpu.disasmName(addi(1, 2, 3)));
    }

    @Test
    void selfModifyingCodeIsSeen() {
        // r3 = word of "addi r4,0,7"; stw it over the instruction at CODE+16, then execute it.
        int patched = addi(4, 0, 7);
        boot(Interpreter.Clock.DETERMINISTIC, addi(3, 0, patched & 0xffff), (15 << 26) | (5 << 21) | (patched >>> 16),
                ori(3, 3, 0), NOP, addi(4, 0, 1), SC);
        // CODE+4 is "addis r5,0,hi" - build r3 = hi:lo with an or.
        load(CODE + 8, (31 << 26) | (5 << 21) | (3 << 16) | (3 << 11) | (444 << 1)); // or r3,r5,r3
        load(CODE + 12, (36 << 26) | (3 << 21) | (6 << 16) | 16); // stw r3,16(r6)
        s.gpr[6] = CODE;
        cpu.run(1); // decode-cache the original word at CODE+16 by running it once first
        s.pc = CODE + 16;
        cpu.run(1);
        assertEquals(1, s.gpr[4]);
        s.pc = CODE;
        cpu.run(5);
        assertEquals(7, s.gpr[4]);
    }

    @Test
    void endiannessSwitchByRfid() {
        // rfid to big-endian code at 0x2000.
        boot(Interpreter.Clock.DETERMINISTIC, mtspr(Spr.SRR0, 4), mtspr(Spr.SRR1, 5), 0x4c000024);
        s.gpr[4] = 0x2000;
        s.gpr[5] = Msr.SF | Msr.ME;
        pm.write32be(0x2000, addi(7, 0, 42));
        pm.write32be(0x2004, SC);
        cpu.run(5);
        assertEquals(42, s.gpr[7]);
        assertEquals(PpcException.SYSCALL, interrupts.get(0)[0]);
        assertEquals(0x2008, s.spr[Spr.SRR0]);
        assertEquals(Msr.SF | Msr.ME, s.spr[Spr.SRR1]);
    }

    // ================================================================ interrupt delivery details

    @Test
    void bigEndianInterruptsWhenIleClear() {
        boot(Interpreter.Clock.DETERMINISTIC, tw(31));
        s.spr[Spr.LPCR] &= ~Spr.LPCR_ILE;
        cpu.run(1);
        assertEquals(0x700, s.pc);
        assertEquals(Msr.SF | Msr.ME, s.msr);
        assertEquals(MSR64LE | PpcException.SRR1_TRAP, s.spr[Spr.SRR1]);
    }

    /** Minimal radix tree: 2 MiB identity pages for 0..4 MiB, both quadrants, PID 0. */
    void mapLowMemory() {
        long proc = 0x100000;
        long root = 0x110000;
        long pud = 0x120000;
        long pmd = 0x121000;
        int rts = 52 - 31;
        pm.write64be(proc, ((long) (rts >> 3) << 61) | ((long) (rts & 7) << 5) | root | 13);
        pm.write64be(root, (1L << 63) | pud | 9);
        pm.write64be(pud, (1L << 63) | pmd | 9);
        for (int k = 0; k < 2; k++) {
            pm.write64be(pmd + 8L * k, (3L << 62) | (0x200000L * k) | 0x100 | 0x80 | 7);
        }
        s.procTable = proc;
        s.spr[Spr.PIDR] = 0;
    }

    @Test
    void ail2RelocatesToVectorPlus0x18000() {
        boot(Interpreter.Clock.DETERMINISTIC, tw(31));
        mapLowMemory();
        s.spr[Spr.LPCR] |= 2L << Spr.LPCR_AIL_SHIFT;
        s.msr = MSR64LE | Msr.IR | Msr.DR;
        cpu.run(1);
        assertEquals(0x18700, s.pc);
        assertEquals(Msr.SF | Msr.ME | Msr.LE | Msr.IR | Msr.DR, s.msr);
    }

    @Test
    void ail2ScvKeepsBaseAndAil3UsesC000Base() {
        boot(Interpreter.Clock.DETERMINISTIC, (17 << 26) | (5 << 5) | 1);
        mapLowMemory();
        s.spr[Spr.FSCR] = 1L << 12;
        s.spr[Spr.LPCR] |= 2L << Spr.LPCR_AIL_SHIFT;
        s.msr = MSR64LE | Msr.IR | Msr.DR | Msr.EE | Msr.PR;
        cpu.run(1);
        assertEquals(0x17000 + 5 * 0x20, s.pc);
        assertEquals(Msr.SF | Msr.ME | Msr.LE | Msr.IR | Msr.DR | Msr.EE, s.msr);
        assertEquals(CODE + 4, s.lr);
        assertEquals(MSR64LE | Msr.IR | Msr.DR | Msr.EE | Msr.PR, s.ctr);

        s.spr[Spr.LPCR] |= 3L << Spr.LPCR_AIL_SHIFT;
        s.msr = MSR64LE | Msr.IR | Msr.DR;
        s.pc = CODE;
        s.stopRequested = false;
        cpu.run(1);
        assertEquals(0xc000000000003000L + 5 * 0x20, s.pc);
    }

    @Test
    void registeringAProcessTableFlushesTheTlb() {
        // Two different trees; switching s.procTable (as H_REGISTER_PROC_TBL does) must take effect.
        boot(Interpreter.Clock.DETERMINISTIC, (32 << 26) | (3 << 21) | (4 << 16), HCALL, (32 << 26) | (3 << 21) | (4 << 16), SC);
        mapLowMemory();
        long first = s.procTable;
        pm.write32le(0x3F0000, 0x11111111);
        // Second table: same tree but the 2..4 MiB leaf points at 0..2 MiB.
        long proc2 = 0x130000;
        pm.write64be(proc2, pm.read64be(first));
        long root2 = 0x140000;
        long pud2 = 0x150000;
        long pmd2 = 0x151000;
        pm.write64be(proc2, (pm.read64be(first) & ~0x0FFFFFFFFFFFFF00L) | root2);
        pm.write64be(root2, (1L << 63) | pud2 | 9);
        pm.write64be(pud2, (1L << 63) | pmd2 | 9);
        pm.write64be(pmd2, (3L << 62) | 0x100 | 0x80 | 0x8 | 7);
        pm.write64be(pmd2 + 8, (3L << 62) | 0x100 | 0x80 | 0x8 | 7);
        pm.write32le(0x1F0000, 0x22222222); // what EA 0x3F0000 maps to in the second tree
        s.gpr[4] = 0x3F0000;
        s.msr = MSR64LE | Msr.DR;
        s.hv = st -> st.procTable = proc2;
        cpu.run(1);
        assertEquals(0x11111111L, s.gpr[3]);
        cpu.run(3);
        assertEquals(0x22222222L, s.gpr[3]);
        assertNotNull(cpu.mmu());
    }
}
