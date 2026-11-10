package org.boxtheta.javapower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.cpu.Interpreter;
import org.boxtheta.javapower.papr.BufferCharBackend;
import org.boxtheta.javapower.papr.Machine;
import org.boxtheta.javapower.papr.MachineConfig;
import org.junit.jupiter.api.Test;

/**
 * Whole machines with several CPUs, each on its own thread, running small big-endian "firmware"
 * programs against the real hypervisor: RTAS start-cpu / stop-self / query-cpu-stopped-state,
 * H_CEDE and H_PROD, system-reboot and power-off, and {@link Emulator#stop} while CPUs are idle
 * or stopped.
 */
class SmpEmulatorTest {
    static final int H_CEDE = 0xe0;
    static final int H_PROD = 0xe8;
    static final int H_RTAS = 0xf000;
    static final int RTAS_QUERY_STOPPED = 0x2005;
    static final int RTAS_START_CPU = 0x2006;
    static final int RTAS_STOP_SELF = 0x2007;
    static final int RTAS_POWER_OFF = 0x2003;
    static final int RTAS_REBOOT = 0x2004;

    static final int DATA = 0x10000;
    static final int BOOTS = 0x0;
    static final int COUNTER = 0x8;
    static final int DONE = 0x10;
    static final int FAIL = 0x18;
    static final int RTAS_BUF = 0x4000;
    static final int MAIN = 0x2000;
    static final int SECONDARY = 0x3000;
    static final int WORK = 0x3800;

    /** RTAS call with the argument block at r4 (already filled); clobbers r3. */
    static void rtas(Asm a) {
        a.li32(3, H_RTAS).sc1();
    }

    /** Interrupt vectors: the decrementer re-arms itself; anything else records a failure and spins. */
    static void vectors(Asm a) {
        for (int v = 0x200; v <= 0xf00; v += 0x100) {
            a.org(v);
            if (v == 0x900) {
                a.lis(0, 0x7fff).mtspr(22, 0).rfid();
            } else {
                a.li32(30, DATA).li(31, v).stw(31, FAIL, 30).label("spin" + v).b("spin" + v);
            }
        }
    }

    /**
     * CPU 0 runs {@code rounds} rounds: start every secondary at SECONDARY, add {@code iters} to
     * the shared counter with ldarx/stdcx, cede until all secondaries reported (each prods CPU 0),
     * wait until they are stopped again. Then it reboots once, and powers off on the second boot.
     */
    static byte[] firmware(int cpus, int rounds, int iters) {
        Asm a = new Asm();
        a.org(0x100);
        a.b("main");
        vectors(a);
        a.org(MAIN);
        a.label("main");
        a.li32(20, DATA);
        a.ld(5, BOOTS, 20).addi(5, 5, 1).std(5, BOOTS, 20);
        a.cmpdi(5, 1).bne("second_boot");
        a.li(21, 0); // round
        a.label("round");
        a.li(5, 0).stw(5, DONE, 20).sync();
        a.li(22, 1); // cpu id
        a.label("start");
        a.li32(4, RTAS_BUF);
        a.li32(5, RTAS_START_CPU).stw(5, 0, 4).li(5, 3).stw(5, 4, 4).li(5, 1).stw(5, 8, 4);
        a.stw(22, 12, 4).li32(5, SECONDARY).stw(5, 16, 4).stw(22, 20, 4);
        rtas(a);
        a.lwz(5, 24, 4).cmpwi(5, 0).bne("fail");
        a.addi(22, 22, 1).cmpwi(22, cpus).blt("start");
        a.li32(23, iters).bl("work");
        // Wait for every secondary to report, ceding in between (H_PROD wakes us).
        a.label("wait");
        a.lwz(5, DONE, 20).cmpwi(5, cpus - 1).beq("reported");
        a.li(3, H_CEDE).sc1();
        a.b("wait");
        a.label("reported");
        // Wait until each secondary is RTAS-stopped again.
        a.li(22, 1);
        a.label("query");
        a.li32(4, RTAS_BUF);
        a.li32(5, RTAS_QUERY_STOPPED).stw(5, 0, 4).li(5, 1).stw(5, 4, 4).li(5, 2).stw(5, 8, 4).stw(22, 12, 4);
        rtas(a);
        a.lwz(5, 16, 4).cmpwi(5, 0).bne("fail");
        a.lwz(5, 20, 4).cmpwi(5, 0).bne("query");
        a.addi(22, 22, 1).cmpwi(22, cpus).blt("query");
        a.addi(21, 21, 1).cmpwi(21, rounds).blt("round");
        // First boot done: reboot.
        a.li32(4, RTAS_BUF);
        a.li32(5, RTAS_REBOOT).stw(5, 0, 4).li(5, 0).stw(5, 4, 4).li(5, 1).stw(5, 8, 4);
        rtas(a);
        a.label("hang").b("hang");
        a.label("second_boot");
        a.li32(4, RTAS_BUF);
        a.li32(5, RTAS_POWER_OFF).stw(5, 0, 4).li(5, 2).stw(5, 4, 4).li(5, 1).stw(5, 8, 4);
        a.li(5, 0).stw(5, 12, 4).stw(5, 16, 4);
        rtas(a);
        a.b("hang");
        a.label("fail");
        a.li(31, 1).stw(31, FAIL, 20).b("hang");

        // Secondary entry: r3 = cpu id. Work, report, prod CPU 0, stop.
        a.org(SECONDARY);
        a.mr(13, 3).li32(20, DATA);
        a.li32(23, iters).bl("work");
        a.label("done").lwarx(5, 0, 24).addi(5, 5, 1).stwcx(5, 0, 24).bne("done");
        a.li(3, H_PROD).li(4, 0).sc1();
        a.li32(4, RTAS_BUF).sldi(5, 13, 8).add(4, 4, 5);
        a.li32(5, RTAS_STOP_SELF).stw(5, 0, 4).li(5, 0).stw(5, 4, 4).stw(5, 8, 4);
        rtas(a);
        a.li(31, 2).stw(31, FAIL, 20).label("hang2").b("hang2"); // stop-self returned

        // work: add r23 to the counter, one ldarx/stdcx at a time. r24 = &DONE on return.
        a.org(WORK);
        a.label("work");
        a.addi(24, 20, COUNTER);
        a.label("w1").ldarx(5, 0, 24).addi(5, 5, 1).stdcx(5, 0, 24).bne("w1");
        a.addi(23, 23, -1).cmpdi(23, 0).bne("w1");
        a.addi(24, 20, DONE);
        a.blr();
        return a.bytesBe();
    }

    static MachineConfig config(int cpus, byte[] fw) {
        return new MachineConfig().ram(256L << 20).cpus(cpus).firmware(fw).console(new BufferCharBackend())
                .random(new Random(1));
    }

    static void assertNoCpuThreads() throws InterruptedException {
        long end = System.currentTimeMillis() + 2000;
        while (true) {
            boolean any = Thread.getAllStackTraces().keySet().stream()
                    .anyMatch(t -> t.getName().startsWith("javapower-") && t.isAlive());
            if (!any) {
                return;
            }
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("emulator threads still alive");
            }
            Thread.sleep(10);
        }
    }

    @Test
    void secondariesStartStopCedeProdRebootAndPowerOff() throws Exception {
        for (int cpus : new int[] {2, 4}) {
            int rounds = 20;
            int iters = 20_000;
            try (Emulator emu = new Emulator(config(cpus, firmware(cpus, rounds, iters)))) {
                Machine.ShutdownReason why = emu.run();
                PhysicalMemory pm = emu.machine().memory();
                assertEquals(0, pm.read32be(DATA + FAIL), "failure code");
                assertEquals(Machine.ShutdownReason.POWER_OFF, why);
                assertEquals(2, pm.read64be(DATA + BOOTS), "boots");
                assertEquals((long) rounds * cpus * iters, pm.read64be(DATA + COUNTER), "counter");
                assertTrue(emu.unimplementedInstructions().isEmpty(), emu.unimplementedInstructions().toString());
                // After the reboot only CPU 0 ran: the secondaries are stopped again.
                for (int i = 1; i < cpus; i++) {
                    assertTrue(emu.cpu(i).halted, "cpu " + i + " not stopped after reboot");
                }
            }
            assertNoCpuThreads();
        }
    }

    /** CPU 0 starts CPU 1; both cede forever; CPUs 2 and 3 stay stopped. stop() must still work. */
    @Test
    void stopWhileIdleAndHaltedIsPromptAndResumable() throws Exception {
        Asm a = new Asm();
        a.org(0x100).b("main");
        vectors(a);
        a.org(MAIN).label("main");
        a.li32(4, RTAS_BUF);
        a.li32(5, RTAS_START_CPU).stw(5, 0, 4).li(5, 3).stw(5, 4, 4).li(5, 1).stw(5, 8, 4);
        a.li(5, 1).stw(5, 12, 4).li32(5, SECONDARY).stw(5, 16, 4).stw(5, 20, 4);
        rtas(a);
        a.label("cede").li(3, H_CEDE).sc1().b("cede");
        a.org(SECONDARY).label("cede2").li(3, H_CEDE).sc1().b("cede2");
        try (Emulator emu = new Emulator(config(4, a.bytesBe()))) {
            for (int round = 0; round < 3; round++) {
                AtomicReference<Machine.ShutdownReason> why = new AtomicReference<>();
                Thread runner = new Thread(() -> why.set(emu.run()));
                runner.start();
                Thread.sleep(300);
                long t0 = System.nanoTime();
                emu.stop();
                runner.join(5000);
                assertFalse(runner.isAlive(), "run() did not return after stop()");
                assertTrue(System.nanoTime() - t0 < 2_000_000_000L, "stop was slow");
                assertEquals(Machine.ShutdownReason.NONE, why.get());
                assertNoCpuThreads();
                assertFalse(emu.cpu(1).halted, "cpu 1 was started");
                assertTrue(emu.cpu(2).halted && emu.cpu(3).halted);
                assertTrue(emu.cpu(0).icount > 0 && emu.cpu(1).icount > 0);
            }
        }
    }

    @Test
    void deterministicClockIsSingleCpuOnly() {
        assertThrows(IllegalArgumentException.class,
                () -> new Emulator(config(2, new byte[] {0x48, 0, 0, 0}), Interpreter.Clock.DETERMINISTIC));
        assertThrows(IllegalArgumentException.class,
                () -> new Emulator(config(1, new byte[] {0x48, 0, 0, 0}), Interpreter.Clock.WALL, 0.0));
    }

    /** The instruction limit applies to CPU 0 and leaves a resumable machine. */
    @Test
    void instructionLimitStopsEveryCpu() throws Exception {
        Asm a = new Asm();
        a.org(0x100).b("main");
        vectors(a);
        a.org(MAIN).label("main");
        a.li32(4, RTAS_BUF);
        a.li32(5, RTAS_START_CPU).stw(5, 0, 4).li(5, 3).stw(5, 4, 4).li(5, 1).stw(5, 8, 4);
        a.li(5, 1).stw(5, 12, 4).li32(5, SECONDARY).stw(5, 16, 4).stw(5, 20, 4);
        rtas(a);
        a.label("spin").addi(6, 6, 1).b("spin");
        a.org(SECONDARY).label("spin2").addi(6, 6, 1).b("spin2");
        try (Emulator emu = new Emulator(config(2, a.bytesBe()), Interpreter.Clock.WALL, 4.0)) {
            assertEquals(Machine.ShutdownReason.NONE, emu.run(5_000_000));
            assertEquals(5_000_000, emu.cpu(0).icount);
            long c1 = emu.cpu(1).icount;
            assertTrue(c1 > 0);
            assertEquals(Machine.ShutdownReason.NONE, emu.run(1_000_000));
            assertEquals(6_000_000, emu.cpu(0).icount);
            assertTrue(emu.cpu(1).icount > c1);
            assertEquals(4.0, emu.domain().timeScale());
        }
        assertNoCpuThreads();
    }
}
