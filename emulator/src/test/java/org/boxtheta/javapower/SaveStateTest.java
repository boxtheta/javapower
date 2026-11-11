package org.boxtheta.javapower;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.core.StateFormatException;
import org.boxtheta.javapower.core.StateMismatchException;
import org.boxtheta.javapower.core.StateOutput;
import org.boxtheta.javapower.cpu.Interpreter;
import org.boxtheta.javapower.firmware.slof.SlofFirmware;
import org.boxtheta.javapower.papr.BufferCharBackend;
import org.boxtheta.javapower.papr.Machine;
import org.boxtheta.javapower.papr.MachineConfig;
import org.boxtheta.javapower.papr.MemoryBackend;
import org.boxtheta.javapower.papr.ScsiDisk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Saving a machine and loading it into a new one: the restored machine must continue exactly
 * like the original (deterministic clock), SMP guests keep their running and stopped CPUs, and
 * bad input is refused cleanly.
 */
class SaveStateTest {
    static final int DATA = 0x20000;
    static final int TICKS = 0x0;
    static final int FAIL = 0x38;
    static final int H_PUT_TERM_CHAR = 0x58;
    static final int H_CEDE = 0xe0;

    /**
     * A single-CPU workload with interesting state at any point: the decrementer interrupts it
     * every few thousand instructions (MSR[EE]=1), it keeps values in FP and vector registers,
     * reads {@code darn}, holds ldarx reservations, writes RAM, prints to the console with
     * H_PUT_TERM_CHAR and idles in H_CEDE until the decrementer wakes it.
     *
     * <p>The golden state in the test resources was saved from this program: do not change it
     * (see {@link #writeGoldenState}).
     */
    static byte[] workload() {
        Asm a = new Asm();
        a.org(0x100).b("main");
        for (int v = 0x200; v <= 0xf00; v += 0x100) {
            a.org(v);
            if (v == 0x900) {
                // r28/r29 belong to the handler: count the tick, re-arm DEC.
                a.li32(28, DATA).ld(29, TICKS, 28).addi(29, 29, 1).std(29, TICKS, 28);
                a.li(29, 3000).mtspr(22, 29).rfid();
            } else {
                a.li32(30, DATA).li(31, v).stw(31, FAIL, 30).label("spin" + v).b("spin" + v);
            }
        }
        a.org(0x2000).label("main");
        a.li32(20, DATA).li32(21, DATA + 0x28);
        a.li32(5, 0x3ff00000).sldi(5, 5, 32).std(5, 0x18, 20); // 1.0
        a.li(29, 3000).mtspr(22, 29);
        a.li32(5, 0x0280b000); // VEC VSX FP EE ME, 32-bit big-endian
        a.emit(0x7c000164 | (5 << 21)); // mtmsrd r5
        a.emit(lfd(2, 0x18, 20));
        a.emit((4 << 26) | (2 << 21) | (1 << 16) | 780); // vspltisb v2,1
        a.li(14, 0).li(15, 0);
        a.label("loop");
        a.addi(14, 14, 1).std(14, 8, 20);
        a.emit(lfd(1, 0x10, 20));
        a.emit((63 << 26) | (1 << 21) | (1 << 16) | (2 << 11) | (21 << 1)); // fadd f1,f1,f2
        a.emit((54 << 26) | (1 << 21) | (20 << 16) | 0x10); // stfd f1,0x10(r20)
        a.emit((4 << 26) | (1 << 21) | (1 << 16) | (2 << 11)); // vaddubm v1,v1,v2
        a.emit(0x7c0005e6 | (7 << 21) | (1 << 16)).std(7, 0x20, 20); // darn r7,1
        a.label("retry").ldarx(8, 0, 21).addi(8, 8, 1).stdcx(8, 0, 21).bne("retry");
        a.andiDot(9, 14, 0x7f8).add(10, 20, 9).std(14, 0x100, 10);
        a.andiDot(9, 14, 0x3ff).bne("loop");
        // Every 1024 iterations print a letter; every 8 letters cede until the next tick.
        a.andiDot(6, 15, 15).addi(6, 6, 'a').sldi(6, 6, 56).addi(15, 15, 1);
        a.li(3, H_PUT_TERM_CHAR).li(4, 0).li(5, 1).sc1();
        a.andiDot(9, 15, 7).bne("loop");
        a.li(3, H_CEDE).sc1();
        a.b("loop");
        return a.bytesBe();
    }

    private static int lfd(int frt, int d, int ra) {
        return (50 << 26) | (frt << 21) | (ra << 16) | (d & 0xffff);
    }

    static MachineConfig config(byte[] fw, BufferCharBackend console, Random random) {
        return new MachineConfig().ram(256L << 20).firmware(fw).console(console).random(random)
                .clock(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    static Emulator deterministic(MachineConfig cfg, boolean jit, long seed) throws IOException {
        Emulator e = new Emulator(cfg, Interpreter.Clock.DETERMINISTIC, 1.0, seed);
        e.interpreter().compileBlocks = jit;
        return e;
    }

    static byte[] save(Emulator e) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        e.saveState(bo);
        return bo.toByteArray();
    }

    static void load(Emulator e, byte[] state) throws IOException {
        e.loadState(new ByteArrayInputStream(state));
    }

    /** A copy of {@code r} in its current state (java.util.Random is serializable). */
    static Random copy(Random r) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (ObjectOutputStream o = new ObjectOutputStream(bo)) {
            o.writeObject(r);
        }
        try (ObjectInputStream i = new ObjectInputStream(new ByteArrayInputStream(bo.toByteArray()))) {
            return (Random) i.readObject();
        }
    }

    static byte[] cpuBytes(CpuState s) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        s.saveState(new DataOutputStream(bo));
        return bo.toByteArray();
    }

    static byte[] platformBytes(Machine m) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        m.saveState(new StateOutput(bo));
        return bo.toByteArray();
    }

    /** Full CPU, platform and RAM state of two machines are identical. */
    static void assertSameMachine(Emulator a, Emulator b) throws IOException {
        assertEquals(a.cpuCount(), b.cpuCount());
        for (int i = 0; i < a.cpuCount(); i++) {
            assertArrayEquals(cpuBytes(a.cpu(i)), cpuBytes(b.cpu(i)), "CPU " + i);
            if (a.interpreter(i).clock() == Interpreter.Clock.DETERMINISTIC) {
                assertEquals(a.interpreter(i).tb(), b.interpreter(i).tb(), "TB of CPU " + i);
                assertEquals(a.interpreter(i).loadDec(), b.interpreter(i).loadDec(), "DEC of CPU " + i);
                assertEquals(a.interpreter(i).decPending(), b.interpreter(i).decPending());
            }
        }
        assertArrayEquals(platformBytes(a.machine()), platformBytes(b.machine()), "platform state");
        assertSameRam(a.machine().memory(), b.machine().memory());
    }

    static void assertSameRam(PhysicalMemory a, PhysicalMemory b) {
        assertEquals(a.size(), b.size());
        byte[] x = new byte[1 << 20];
        byte[] y = new byte[1 << 20];
        for (long p = 0; p < a.size(); p += x.length) {
            int n = (int) Math.min(x.length, a.size() - p);
            a.read(p, x, 0, n);
            b.read(p, y, 0, n);
            if (!Arrays.equals(x, 0, n, y, 0, n)) {
                int at = Arrays.mismatch(x, 0, n, y, 0, n);
                throw new AssertionError("RAM differs at 0x" + Long.toHexString(p + at));
            }
        }
    }

    /**
     * Run {@code fw} for {@code before} instructions, save, load into a new machine (built with
     * another darn seed, which the state overrides), run both {@code after} more instructions and
     * compare console output and the whole machine.
     */
    static void continuesBitExactly(byte[] fw, int ramMiB, long before, long after, boolean jit) throws Exception {
        BufferCharBackend ca = new BufferCharBackend();
        Random ra = new Random(1);
        MachineConfig cfga = config(fw, ca, ra).ram((long) ramMiB << 20);
        try (Emulator a = deterministic(cfga, jit, 1)) {
            assertEquals(Machine.ShutdownReason.NONE, a.run(before));
            byte[] state = save(a);
            int mark = ca.outputBytes().length;
            BufferCharBackend cb = new BufferCharBackend();
            // MachineConfig.random is the embedder's, outside the state: continue it by hand.
            MachineConfig cfgb = config(fw, cb, copy(ra)).ram((long) ramMiB << 20);
            try (Emulator b = deterministic(cfgb, jit, 12345)) {
                load(b, state);
                assertSameMachine(a, b);
                assertEquals(Machine.ShutdownReason.NONE, a.run(after));
                assertEquals(Machine.ShutdownReason.NONE, b.run(after));
                byte[] out = ca.outputBytes();
                assertEquals(new String(out, mark, out.length - mark, java.nio.charset.StandardCharsets.ISO_8859_1),
                        cb.output());
                assertSameMachine(a, b);
                assertEquals(a.unimplementedInstructions(), b.unimplementedInstructions());
            }
        }
    }

    @ParameterizedTest(name = "jit={0}")
    @ValueSource(booleans = {false, true})
    void workloadContinuesBitExactly(boolean jit) throws Exception {
        continuesBitExactly(workload(), 256, 2_500_003, 3_000_000, jit);
    }

    /** Checks that the workload really has the state the test is meant to cover at the save point. */
    @Test
    void workloadIsInteresting() throws Exception {
        BufferCharBackend c = new BufferCharBackend();
        try (Emulator e = deterministic(config(workload(), c, new Random(1)), true, 1)) {
            e.run(2_500_003);
            PhysicalMemory m = e.machine().memory();
            assertEquals(0, m.read32be(DATA + FAIL), "failure vector");
            assertTrue(m.read64be(DATA + TICKS) > 100, "decrementer ticks " + m.read64be(DATA + TICKS));
            assertTrue(c.output().length() > 20, c.output());
            assertTrue((e.cpu().msr & org.boxtheta.javapower.core.Msr.EE) != 0);
        }
    }

    @ParameterizedTest(name = "jit={0}")
    @ValueSource(booleans = {false, true})
    void slofContinuesBitExactly(boolean jit) throws Exception {
        continuesBitExactly(SlofFirmware.image(), 512, 40_000_000, 30_000_000, jit);
    }

    /**
     * Four CPUs: CPU 0 starts CPU 1, both count on a shared ldarx/stdcx counter and cede now and
     * then; CPUs 2 and 3 stay RTAS-stopped. After a save and load into a new machine the same
     * CPUs run and stop, and the guest goes on counting.
     */
    @Test
    void smpGuestKeepsItsRunningAndStoppedCpus() throws Exception {
        Asm a = new Asm();
        a.org(0x100).b("main");
        SmpEmulatorTest.vectors(a);
        a.org(SmpEmulatorTest.MAIN).label("main");
        a.li32(4, SmpEmulatorTest.RTAS_BUF);
        a.li32(5, SmpEmulatorTest.RTAS_START_CPU).stw(5, 0, 4).li(5, 3).stw(5, 4, 4).li(5, 1).stw(5, 8, 4);
        a.li(5, 1).stw(5, 12, 4).li32(5, SmpEmulatorTest.SECONDARY).stw(5, 16, 4).stw(5, 20, 4);
        SmpEmulatorTest.rtas(a);
        a.label("count");
        a.li32(24, SmpEmulatorTest.DATA + SmpEmulatorTest.COUNTER);
        a.label("c0").ldarx(5, 0, 24).addi(5, 5, 1).stdcx(5, 0, 24).bne("c0");
        a.addi(6, 6, 1).andiDot(7, 6, 0xffff).bne("count");
        a.li(5, 20000).mtspr(22, 5).li(3, SmpEmulatorTest.H_CEDE).sc1().b("count"); // until the next tick
        a.org(SmpEmulatorTest.SECONDARY);
        a.li32(24, SmpEmulatorTest.DATA + SmpEmulatorTest.COUNTER);
        a.label("c1").ldarx(5, 0, 24).addi(5, 5, 1).stdcx(5, 0, 24).bne("c1");
        a.addi(6, 6, 1).b("c1");
        byte[] fw = a.bytesBe();
        MachineConfig cfg = SmpEmulatorTest.config(4, fw);
        try (Emulator e = new Emulator(cfg, Interpreter.Clock.WALL, 4.0)) {
            runFor(e, 300);
            assertFalse(e.cpu(1).halted);
            assertTrue(e.cpu(2).halted && e.cpu(3).halted);
            long tb = e.interpreter(0).tb();
            byte[] state = save(e);
            long counter = e.machine().memory().read64be(SmpEmulatorTest.DATA + SmpEmulatorTest.COUNTER);
            assertTrue(counter > 0);

            try (Emulator r = new Emulator(SmpEmulatorTest.config(4, new byte[] {0x48, 0, 0, 0}), Interpreter.Clock.WALL,
                    4.0)) {
                load(r, state);
                for (int i = 0; i < 4; i++) {
                    assertArrayEquals(cpuBytes(e.cpu(i)), cpuBytes(r.cpu(i)), "CPU " + i);
                }
                assertArrayEquals(platformBytes(e.machine()), platformBytes(r.machine()));
                assertSameRam(e.machine().memory(), r.machine().memory());
                long tbr = r.interpreter(0).tb();
                assertTrue(tbr >= tb && tbr - tb < Interpreter.TB_FREQ / 10, "timebase jumped: " + tb + " -> " + tbr);

                long[] ic = new long[4];
                for (int i = 0; i < 4; i++) {
                    ic[i] = r.cpu(i).icount;
                }
                runFor(r, 300);
                assertTrue(r.cpu(0).icount > ic[0] && r.cpu(1).icount > ic[1], "CPUs 0 and 1 run");
                assertEquals(ic[2], r.cpu(2).icount);
                assertEquals(ic[3], r.cpu(3).icount);
                assertTrue(r.cpu(2).halted && r.cpu(3).halted);
                assertEquals(0, r.machine().memory().read32be(SmpEmulatorTest.DATA + SmpEmulatorTest.FAIL));
                assertTrue(r.machine().memory().read64be(SmpEmulatorTest.DATA + SmpEmulatorTest.COUNTER) > counter);

                // And again, from the restored machine.
                byte[] again = save(r);
                try (Emulator r2 = new Emulator(SmpEmulatorTest.config(4, fw))) {
                    load(r2, again);
                    for (int i = 0; i < 4; i++) {
                        assertArrayEquals(cpuBytes(r.cpu(i)), cpuBytes(r2.cpu(i)), "CPU " + i);
                    }
                    assertSameRam(r.machine().memory(), r2.machine().memory());
                }
            }
        }
        SmpEmulatorTest.assertNoCpuThreads();
    }

    private static void runFor(Emulator e, long ms) throws InterruptedException {
        AtomicReference<Object> why = new AtomicReference<>();
        Thread t = new Thread(() -> {
            try {
                why.set(e.run());
            } catch (Throwable x) {
                why.set(x);
            }
        });
        t.start();
        Thread.sleep(ms);
        assertThrows(IllegalStateException.class, () -> save(e), "saving while the guest runs");
        e.stop();
        t.join(5000);
        assertFalse(t.isAlive());
        assertEquals(Machine.ShutdownReason.NONE, why.get());
    }

    @Test
    void badStatesAreRefusedAndAFailedLoadIsNotRun() throws Exception {
        byte[] state;
        try (Emulator e = deterministic(config(workload(), new BufferCharBackend(), new Random(1)), true, 1)) {
            e.run(1_000_000);
            state = save(e);
        }
        // Another format version: refused, the machine is untouched and still runs.
        byte[] v2 = state.clone();
        v2[11] = 2;
        try (Emulator e = deterministic(config(workload(), new BufferCharBackend(), new Random(1)), true, 1)) {
            StateFormatException x = assertThrows(StateFormatException.class, () -> load(e, v2));
            assertTrue(x.getMessage().contains("format version 2"), x.getMessage());
            e.run(1000);
            assertEquals(1000, e.cpu().icount);
        }
        // Truncated (in the CPU or RAM records) or corrupt: refused, and the half-loaded machine
        // neither runs nor saves until a reset or a good load.
        int cpusAt = indexOf(state, "CPUS");
        byte[][] bad = {
            Arrays.copyOf(state, state.length / 2),
            Arrays.copyOf(state, state.length - 1),
            Arrays.copyOf(state, cpusAt + 100),
            flip(state, state.length - 5000),
            flip(state, cpusAt + 50),
        };
        for (byte[] b : bad) {
            try (Emulator e = deterministic(config(workload(), new BufferCharBackend(), new Random(1)), true, 1)) {
                assertThrows(StateFormatException.class, () -> load(e, b));
                assertThrows(IllegalStateException.class, () -> e.run(1000));
                assertThrows(IllegalStateException.class, () -> save(e));
                load(e, state);
                long ic = e.cpu().icount;
                e.run(1000);
                assertTrue(e.cpu().icount >= ic + 1000, "runs after a good load");
            }
            try (Emulator e = deterministic(config(workload(), new BufferCharBackend(), new Random(1)), true, 1)) {
                assertThrows(StateFormatException.class, () -> load(e, b));
                long ic = e.cpu().icount;
                e.reset();
                e.run(1000);
                assertTrue(e.cpu().icount >= ic + 1000, "runs after reset");
                assertEquals(0, e.machine().memory().read32be(DATA + FAIL));
            }
        }
    }

    private static int indexOf(byte[] b, String tag) {
        byte[] t = tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for (int i = 0; i + t.length <= b.length; i++) {
            if (Arrays.equals(b, i, i + t.length, t, 0, t.length)) {
                return i;
            }
        }
        throw new AssertionError(tag);
    }

    private static byte[] flip(byte[] b, int at) {
        byte[] c = b.clone();
        c[at] ^= 0x10;
        return c;
    }

    @Test
    void otherHardwareIsRefusedWithoutChangingTheMachine() throws Exception {
        byte[] state;
        MachineConfig src = config(workload(), new BufferCharBackend(), new Random(1));
        src.addDisk(ScsiDisk.Type.DISK, new MemoryBackend(1 << 20));
        try (Emulator e = new Emulator(src)) {
            e.run(100_000);
            state = save(e);
        }
        MachineConfig other = config(workload(), new BufferCharBackend(), new Random(1)).cpus(2).ram(512L << 20);
        other.addDisk(ScsiDisk.Type.DISK, new MemoryBackend(2 << 20));
        other.addDisk(ScsiDisk.Type.CDROM, new MemoryBackend(new byte[2048], true));
        try (Emulator e = new Emulator(other)) {
            e.run(1000);
            long ic = e.cpu().icount;
            StateMismatchException x = assertThrows(StateMismatchException.class, () -> load(e, state));
            String m = x.getMessage();
            assertTrue(m.contains("CPUs: saved 1, this machine 2"), m);
            assertTrue(m.contains("bytes of RAM: saved 268435456, this machine 536870912"), m);
            assertTrue(m.contains("SCSI devices: saved 1, this machine 2"), m);
            assertTrue(m.contains("SCSI device 0: bytes: saved 1048576, this machine 2097152"), m);
            assertEquals(ic, e.cpu().icount);
            e.run(1000); // still usable
        }
    }

    /**
     * A state saved by an earlier build (format version 1, on Java 21, i.e. with guest RAM in heap
     * arrays) still loads, on this JVM, and the guest continues exactly as a machine that ran
     * the same program without being saved.
     */
    @Test
    void goldenStateLoadsAndContinues() throws Exception {
        byte[] state;
        try (InputStream in = new GZIPInputStream(SaveStateTest.class.getResourceAsStream("workload-state-v1.bin.gz"))) {
            state = in.readAllBytes();
        }
        BufferCharBackend ref = new BufferCharBackend();
        BufferCharBackend got = new BufferCharBackend();
        try (Emulator a = deterministic(config(workload(), ref, new Random(1)), true, 1);
                Emulator b = deterministic(config(new byte[] {0x48, 0, 0, 0}, got, new Random(1)), true, 7)) {
            a.run(GOLDEN_INSNS);
            int mark = ref.outputBytes().length;
            load(b, state);
            assertSameMachine(a, b);
            a.run(1_000_000);
            b.run(1_000_000);
            byte[] out = ref.outputBytes();
            assertEquals(new String(out, mark, out.length - mark, java.nio.charset.StandardCharsets.ISO_8859_1),
                    got.output());
            assertSameMachine(a, b);
        }
    }

    static final long GOLDEN_INSNS = 2_000_001;

    /**
     * Regenerates {@code workload-state-v1.bin.gz} (only when the format version changes; keep
     * the old file's test for the old version): run with JAVAPOWER_WRITE_GOLDEN_STATE set to the
     * output path, on JDK 21.
     */
    @Test
    @EnabledIfEnvironmentVariable(named = "JAVAPOWER_WRITE_GOLDEN_STATE", matches = ".+")
    void writeGoldenState() throws Exception {
        try (Emulator a = deterministic(config(workload(), new BufferCharBackend(), new Random(1)), true, 1);
                OutputStream out = new GZIPOutputStream(Files.newOutputStream(
                        Path.of(System.getenv("JAVAPOWER_WRITE_GOLDEN_STATE"))))) {
            a.run(GOLDEN_INSNS);
            a.saveState(out);
        }
    }
}
