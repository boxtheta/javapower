package org.boxtheta.javapower;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.BooleanSupplier;
import org.boxtheta.javapower.core.MmioHandler;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.papr.BufferCharBackend;
import org.boxtheta.javapower.papr.MachineConfig;
import org.junit.jupiter.api.Test;

/**
 * An embedder's MMIO region in a whole machine: the guest reaches it with integer, floating
 * point and vector loads and stores and with H_LOGICAL_CI_LOAD/STORE, its interrupt is raised
 * from another thread, several CPUs use it at once, and its state travels with the machine.
 */
class MmioEmulatorTest {
    static final long BASE = 0x1000_0000_0000L;
    static final int DATA = 0x10000;
    static final int RTAS_BUF = 0x4000;
    static final int MAIN = 0x2000;
    static final int SECONDARY = 0x3000;

    /** 0x100 bytes of big-endian registers that read back what was written; logs accesses. */
    static final class Regs implements MmioHandler {
        final byte[] b = new byte[0x100];
        final List<String> log = Collections.synchronizedList(new ArrayList<>());

        @Override
        public synchronized long read(long offset, int size) {
            long v = 0;
            for (int k = 0; k < size; k++) {
                v = (v << 8) | (b[(int) offset + k] & 0xff);
            }
            log.add(String.format("r %x %d %x", offset, size, v));
            return v;
        }

        @Override
        public synchronized void write(long offset, int size, long value) {
            log.add(String.format("w %x %d %x", offset, size, value));
            for (int k = size - 1; k >= 0; k--, value >>>= 8) {
                b[(int) offset + k] = (byte) value;
            }
        }

        @Override
        public synchronized void saveState(DataOutput out) throws IOException {
            out.write(b);
        }

        @Override
        public synchronized void loadState(DataInput in) throws IOException {
            in.readFully(b);
        }
    }

    static MachineConfig config(int cpus, byte[] fw) {
        return new MachineConfig().ram(256L << 20).cpus(cpus).firmware(fw).console(new BufferCharBackend())
                .random(new Random(1));
    }

    // Raw encodings the test Asm lacks.
    static int mtmsrd(int rs) {
        return (31 << 26) | (rs << 21) | (178 << 1);
    }

    static int mtmsrdL(int rs) {
        return mtmsrd(rs) | (1 << 16);
    }

    static int dForm(int po, int rt, int ra, int d) {
        return (po << 26) | (rt << 21) | (ra << 16) | (d & 0xffff);
    }

    static int xx1(int t, int ra, int rb, int xo) {
        return (31 << 26) | ((t & 31) << 21) | (ra << 16) | (rb << 11) | (xo << 1) | (t >>> 5);
    }

    /** The decrementer re-arms itself; any other unexpected interrupt stores its vector at DATA + 0xf0 and spins. */
    static void vectors(Asm a, int from, int to) {
        for (int v = from; v <= to; v += 0x100) {
            if (v == 0x900) {
                a.org(v).lis(0, 0x7fff).mtspr(22, 0).rfid();
            } else {
                a.org(v).li32(30, DATA).li(31, v).stw(31, 0xf0, 30).label("spin" + v).b("spin" + v);
            }
        }
    }

    /** r20 = BASE, 64-bit mode with FP, VMX and VSX on. */
    static void prologue(Asm a) {
        a.li(5, 1).sldi(5, 5, 63).li32(6, (1 << 25) | (1 << 23) | (1 << 13) | (1 << 12)).or(5, 5, 6)
                .emit(mtmsrd(5));
        a.li(20, 1).sldi(20, 20, 44).li32(21, DATA);
    }

    static void waitFor(BooleanSupplier cond, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + 60_000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(5);
        }
    }

    @Test
    void everyKindOfAccessAndSavedState() throws Exception {
        Asm a = new Asm();
        a.org(0x100).b("main");
        vectors(a, 0x200, 0xf00);
        a.org(MAIN).label("main");
        prologue(a);
        // Integer.
        a.li32(5, 0x11223344).stw(5, 0, 20).lwz(6, 0, 20).stw(6, 0, 21);
        // Floating point: a double from RAM to the device and back.
        a.emit(dForm(50, 1, 21, 0x100)).emit(dForm(54, 1, 20, 8)).emit(dForm(50, 2, 20, 8))
                .emit(dForm(54, 2, 21, 8));
        // VSX: 16 bytes from RAM to the device and back (lxvd2x / stxvd2x).
        a.li(7, 0x110).li(8, 0x10).li(9, 0x20);
        a.emit(xx1(0, 21, 7, 844)).emit(xx1(0, 20, 8, 972)).emit(xx1(1, 20, 8, 844)).emit(xx1(1, 21, 9, 972));
        // H_LOGICAL_CI_LOAD and H_LOGICAL_CI_STORE.
        a.li(3, 0x3c).li(4, 4).mr(5, 20).sc1().stw(4, 0x30, 21);
        a.li(3, 0x40).li(4, 8).addi(5, 20, 0x18).li32(6, 0x55667788).sc1();
        a.li(31, 0x77).stw(31, 0x40, 21);
        a.label("hang").b("hang");
        a.org(DATA + 0x100).emit(0x400921fb).emit(0x54442d18);
        a.org(DATA + 0x110).emit(0x01020304).emit(0x05060708).emit(0x11121314).emit(0x15161718);

        Regs dev = new Regs();
        MachineConfig cfg = config(1, a.bytesBe());
        cfg.addMmio("regs", BASE, 0x100, dev);
        byte[] state;
        try (Emulator emu = new Emulator(cfg)) {
            emu.run(10_000);
            PhysicalMemory pm = emu.machine().memory();
            assertEquals(0, pm.read32be(DATA + 0xf0), "unexpected interrupt");
            assertEquals(0x77, pm.read32be(DATA + 0x40));
            assertEquals(List.of("w 0 4 11223344", "r 0 4 11223344", "w 8 8 400921fb54442d18",
                    "r 8 8 400921fb54442d18", "w 10 8 102030405060708", "w 18 8 1112131415161718",
                    "r 10 8 102030405060708", "r 18 8 1112131415161718", "r 0 4 11223344", "w 18 8 55667788"),
                    dev.log);
            assertEquals(0x11223344, pm.read32be(DATA));
            assertEquals(0x400921fb54442d18L, pm.read64be(DATA + 8));
            assertEquals(0x0102030405060708L, pm.read64be(DATA + 0x20));
            assertEquals(0x1112131415161718L, pm.read64be(DATA + 0x28));
            assertEquals(0x11223344, pm.read32be(DATA + 0x30));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            emu.saveState(out);
            state = out.toByteArray();
        }
        Regs dev2 = new Regs();
        MachineConfig cfg2 = config(1, a.bytesBe());
        cfg2.addMmio("regs", BASE, 0x100, dev2);
        try (Emulator emu = new Emulator(cfg2)) {
            emu.loadState(new ByteArrayInputStream(state));
            assertArrayEquals(dev.b, dev2.b);
            emu.run(1000);
            assertTrue(dev2.log.isEmpty());
        }
    }

    @Test
    void interruptRaisedFromAnotherThread() throws Exception {
        int irq = 0x1f00;
        Asm a = new Asm();
        a.org(0x100).b("main");
        vectors(a, 0x200, 0x400);
        // External interrupt: accept, record the XIRR, EOI, report.
        a.org(0x500).li32(21, DATA).li(3, 0x74).sc1().mr(10, 4).std(4, 0x50, 21);
        a.li(3, 0x64).mr(4, 10).sc1().li(5, 1).stw(5, 0x58, 21).label("done").b("done");
        vectors(a, 0x600, 0xf00);
        a.org(MAIN).label("main");
        prologue(a);
        // ibm,set-xive(irq, server 0, priority 5), then CPPR 0xff and MSR[EE].
        a.li32(4, RTAS_BUF).li32(5, 0x200a).stw(5, 0, 4).li(5, 3).stw(5, 4, 4).li(5, 1).stw(5, 8, 4);
        a.li32(5, irq).stw(5, 12, 4).li(5, 0).stw(5, 16, 4).li(5, 5).stw(5, 20, 4);
        a.li32(3, 0xf000).sc1();
        a.li(3, 0x68).li(4, 0xff).sc1();
        a.li(5, 1).stw(5, 0x60, 21);
        a.li32(5, 1 << 15).emit(mtmsrdL(5));
        a.label("idle").b("idle");

        MachineConfig cfg = config(1, a.bytesBe());
        cfg.addMmio("dev", BASE, 0x100, new Regs()).compatible("test,dev").interrupt(false);
        try (Emulator emu = new Emulator(cfg)) {
            assertEquals(irq, emu.machine().mmioInterrupt("dev"));
            PhysicalMemory pm = emu.machine().memory();
            Thread runner = new Thread(emu::run);
            runner.start();
            waitFor(() -> pm.read32be(DATA + 0x60) == 1, "the guest to unmask the interrupt");
            Thread raiser = new Thread(() -> emu.machine().xics().setIrq(irq, true));
            raiser.start();
            raiser.join();
            waitFor(() -> pm.read32be(DATA + 0x58) == 1, "the guest to take the interrupt");
            emu.stop();
            runner.join();
            assertEquals(0, pm.read32be(DATA + 0xf0), "unexpected interrupt");
            assertEquals(0xff000000L | irq, pm.read64be(DATA + 0x50));
        }
    }

    @Test
    void severalCpusAtOnce() throws Exception {
        int cpus = 4;
        int iters = 20_000;
        Asm a = new Asm();
        a.org(0x100).b("main");
        vectors(a, 0x200, 0xf00);
        a.org(MAIN).label("main");
        prologue(a);
        for (int id = 1; id < cpus; id++) {
            a.li32(4, RTAS_BUF).li32(5, 0x2006).stw(5, 0, 4).li(5, 3).stw(5, 4, 4).li(5, 1).stw(5, 8, 4);
            a.li(5, id).stw(5, 12, 4).li32(5, SECONDARY).stw(5, 16, 4).li(5, id).stw(5, 20, 4);
            a.li32(3, 0xf000).sc1();
        }
        a.li(3, 0).bl("work");
        a.label("wait").lwz(5, 0x60, 21).cmpwi(5, cpus).bne("wait");
        a.li(5, 1).stw(5, 0x64, 21);
        a.label("hang").b("hang");
        a.org(SECONDARY);
        a.mr(13, 3);
        prologue(a);
        a.mr(3, 13).bl("work");
        a.label("hang2").b("hang2");
        // work: r3 = cpu id; store 1..iters to the device at id * 8, reading each back.
        a.org(SECONDARY + 0x400).label("work");
        a.sldi(22, 3, 3).add(22, 20, 22).li32(23, iters).li(24, 1);
        a.label("loop").std(24, 0, 22).ld(25, 0, 22).cmpd(25, 24).bne("bad");
        a.addi(24, 24, 1).cmpd(24, 23).blt("loop").beq("loop");
        a.addi(26, 21, 0x60);
        a.label("inc").lwarx(5, 0, 26).addi(5, 5, 1).stwcx(5, 0, 26).bne("inc");
        a.blr();
        a.label("bad").li(5, 0x99).stw(5, 0xf0, 21).b("bad");

        AtomicLongArray last = new AtomicLongArray(cpus);
        ConcurrentLinkedQueue<String> errors = new ConcurrentLinkedQueue<>();
        Set<String> threads = ConcurrentHashMap.newKeySet();
        MmioHandler h = new MmioHandler() {
            @Override
            public long read(long offset, int size) {
                return last.get((int) offset / 8);
            }

            @Override
            public void write(long offset, int size, long value) {
                threads.add(Thread.currentThread().getName());
                int slot = (int) offset / 8;
                if (size != 8 || value != last.get(slot) + 1) {
                    errors.add("slot " + slot + ": " + value + " after " + last.get(slot));
                }
                last.set(slot, value);
            }
        };
        MachineConfig cfg = config(cpus, a.bytesBe());
        cfg.addMmio("shared", BASE, 0x100, h);
        try (Emulator emu = new Emulator(cfg)) {
            PhysicalMemory pm = emu.machine().memory();
            Thread runner = new Thread(emu::run, "runner");
            runner.start();
            waitFor(() -> pm.read32be(DATA + 0x64) == 1 || pm.read32be(DATA + 0xf0) != 0, "the CPUs to finish");
            emu.stop();
            runner.join();
            assertEquals(0, pm.read32be(DATA + 0xf0), "failure code");
        }
        assertTrue(errors.isEmpty(), errors.toString());
        for (int i = 0; i < cpus; i++) {
            assertEquals(iters, last.get(i), "slot " + i);
        }
        assertEquals(cpus, threads.size(), "handler threads " + threads);
    }
}
