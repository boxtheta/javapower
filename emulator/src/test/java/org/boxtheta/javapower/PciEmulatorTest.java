package org.boxtheta.javapower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.boxtheta.javapower.core.MmioHandler;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.papr.MachineConfig;
import org.boxtheta.javapower.papr.PciDevice;
import org.boxtheta.javapower.papr.VirtioDevice;
import org.boxtheta.javapower.papr.VirtioPciDevice;
import org.boxtheta.javapower.papr.Virtqueue;
import org.boxtheta.javapower.papr.VirtqueueRequest;
import org.junit.jupiter.api.Test;

/**
 * PCI devices written outside the platform package, as an embedder writes them, driven by guest
 * code: a virtio device whose two queues are used by two CPUs at once while the embedder's own
 * thread serves them, and a plain PCI device with a register BAR that does DMA through the TCE
 * table the guest programs.
 */
class PciEmulatorTest {
    static final int DATA = MmioEmulatorTest.DATA;
    static final int RTAS_BUF = MmioEmulatorTest.RTAS_BUF;
    static final int MAIN = MmioEmulatorTest.MAIN;
    static final int SECONDARY = MmioEmulatorTest.SECONDARY;
    /** Rings and buffers of queue q at RINGS + q * 0x1000: desc +0, avail +0x200, used +0x400, in +0x800, out +0x810. */
    static final int RINGS = 0x20000;
    static final int DMA_PAGE = 0x30000;
    static final long VIRTIO_BAR = 0x2100_0000_0000L;
    static final int BUID_HI = 0x08000000;
    static final int BUID_LO = 0x20000000;

    /** A virtio device answering each 8-byte value v with 2v + 1, served by the embedder's thread. */
    static final class Doubler extends VirtioDevice {
        final Semaphore kicks = new Semaphore(0);
        final Set<String> kickThreads = ConcurrentHashMap.newKeySet();
        final AtomicLong served = new AtomicLong();
        volatile String error;

        Doubler() {
            super(63, 8, 8);
        }

        @Override
        protected void queueNotified(Virtqueue q) {
            kickThreads.add(Thread.currentThread().getName());
            kicks.release();
        }

        /** The embedder's service loop: until interrupted, serve both queues whenever kicked. */
        void serve() {
            byte[] b = new byte[8];
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    kicks.tryAcquire(100, TimeUnit.MILLISECONDS);
                    for (int i = 0; i < 2; i++) {
                        VirtqueueRequest r;
                        while ((r = queue(i).pop()) != null) {
                            if (r.readableLength() != 8 || r.writableLength() != 8 || r.read(0, b, 0, 8) != 8) {
                                error = "bad request " + r;
                            }
                            long v = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong();
                            java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(2 * v + 1);
                            r.write(0, b, 0, 8);
                            r.complete(8);
                            served.incrementAndGet();
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Sixteen bytes of little-endian registers: offset 0 reads back what was written; a write of
     * the bus address to offset 8 makes the device DMA the word at offset 0 there.
     */
    static final class Scratch extends PciDevice {
        final long[] regs = new long[2];
        volatile boolean dmaOk;

        Scratch() {
            super("scratch", 0x1234, 0x5678, 1, 0xff0000);
            addMemoryBar(0, 16, false, false, new MmioHandler() {
                @Override
                public synchronized long read(long offset, int size) {
                    return regs[(int) offset / 8] >>> (8 * (offset % 8));
                }

                @Override
                public synchronized void write(long offset, int size, long value) {
                    regs[(int) offset / 8] = value;
                    if (offset == 8) {
                        byte[] w = new byte[8];
                        java.nio.ByteBuffer.wrap(w).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(regs[0]);
                        dmaOk = dmaWrite(value, w, 0, 8);
                    }
                }
            });
            int cap = addCapability(0x09, 4);
            setConfig16(cap + 2, 0x4a50);
        }
    }

    // Byte-reversed loads and stores the test Asm lacks.
    static int xForm(int rs, int ra, int rb, int xo) {
        return (31 << 26) | (rs << 21) | (ra << 16) | (rb << 11) | (xo << 1);
    }

    static void stwbrx(Asm a, int rs, int ra, int rb) {
        a.emit(xForm(rs, ra, rb, 662));
    }

    static void sthbrx(Asm a, int rs, int ra, int rb) {
        a.emit(xForm(rs, ra, rb, 918));
    }

    static void lhbrx(Asm a, int rt, int ra, int rb) {
        a.emit(xForm(rt, ra, rb, 790));
    }

    static void ldbrx(Asm a, int rt, int ra, int rb) {
        a.emit(xForm(rt, ra, rb, 532));
    }

    /** Store {@code v} little-endian ({@code size} 1, 2 or 4) at r20 + {@code off}. */
    static void st(Asm a, int size, int off, int v) {
        a.li32(5, v).li32(6, off);
        switch (size) {
            case 1 -> a.add(7, 20, 6).emit(MmioEmulatorTest.dForm(38, 5, 7, 0));
            case 2 -> sthbrx(a, 5, 20, 6);
            default -> stwbrx(a, 5, 20, 6);
        }
    }

    /** An RTAS call with nret 1; a non-zero status stores {@code code} at DATA + 0xf0 and spins. */
    static void rtas(Asm a, int token, int code, int... args) {
        a.li32(4, RTAS_BUF).li32(5, token).stw(5, 0, 4).li(5, args.length).stw(5, 4, 4).li(5, 1).stw(5, 8, 4);
        for (int i = 0; i < args.length; i++) {
            a.li32(5, args[i]).stw(5, 12 + 4 * i, 4);
        }
        a.li32(3, 0xf000).sc1();
        a.li32(4, RTAS_BUF).lwz(5, 12 + 4 * args.length, 4).cmpwi(5, 0).beq("ok" + code);
        a.li(5, code).stw(5, 0xf0, 21).label("fail" + code).b("fail" + code);
        a.label("ok" + code);
    }

    static void configWrite(Asm a, int slot, int reg, int size, int value, int code) {
        rtas(a, 0x2017, code, (slot << 11) | reg, BUID_HI, BUID_LO, size, value);
    }

    @Test
    void guestDrivesDevicesOfAnEmbedder() throws Exception {
        int iters = 3000;
        Asm a = new Asm();
        a.org(0x100).b("main");
        MmioEmulatorTest.vectors(a, 0x200, 0xf00);
        a.org(MAIN).label("main");
        MmioEmulatorTest.prologue(a);
        // Slot 0 (virtio): BAR 4 at 33 TiB, memory and bus master. Slot 1: BAR 0 at PCI 2 GiB.
        configWrite(a, 0, 0x20, 4, (int) VIRTIO_BAR, 1);
        configWrite(a, 0, 0x24, 4, (int) (VIRTIO_BAR >>> 32), 2);
        configWrite(a, 0, 0x04, 2, 6, 3);
        configWrite(a, 1, 0x10, 4, 0x80000000, 4);
        configWrite(a, 1, 0x04, 2, 6, 5);
        // The driver: reset, features (VERSION_1 only), both queues of 8, DRIVER_OK.
        a.li(20, 0x21).sldi(20, 20, 40);
        st(a, 1, 0x14, 0);
        st(a, 1, 0x14, 3);
        st(a, 4, 0x08, 0);
        st(a, 4, 0x0c, 0);
        st(a, 4, 0x08, 1);
        st(a, 4, 0x0c, 1);
        st(a, 1, 0x14, 11);
        a.emit(MmioEmulatorTest.dForm(34, 5, 20, 0x14)).cmpwi(5, 11).beq("featok").li(5, 6).stw(5, 0xf0, 21).label("fspin").b("fspin");
        a.label("featok");
        for (int q = 0; q < 2; q++) {
            int base = RINGS + q * 0x1000;
            st(a, 2, 0x16, q);
            st(a, 2, 0x18, 8);
            st(a, 4, 0x20, base);
            st(a, 4, 0x24, 0);
            st(a, 4, 0x28, base + 0x200);
            st(a, 4, 0x2c, 0);
            st(a, 4, 0x30, base + 0x400);
            st(a, 4, 0x34, 0);
            st(a, 2, 0x1c, 1);
        }
        st(a, 1, 0x14, 15);
        // The plain device: map bus page 0 to DMA_PAGE, store a word, let the device DMA it.
        a.li(3, 0x20).li(4, 1).sldi(4, 4, 31).li(5, 0).li32(6, DMA_PAGE | 3).sc1();
        a.li(20, 0x2000).sldi(20, 20, 32).li(6, 1).sldi(6, 6, 31).add(20, 20, 6);
        a.li32(5, 0x0bad_cafe).li(6, 0);
        stwbrx(a, 5, 20, 6);
        a.li(5, 0).li(6, 8);
        stwbrx(a, 5, 20, 6);
        a.li(20, 0x21).sldi(20, 20, 40);
        // Start CPU 1 on queue 1, take queue 0 here.
        a.li32(4, RTAS_BUF).li32(5, 0x2006).stw(5, 0, 4).li(5, 3).stw(5, 4, 4).li(5, 1).stw(5, 8, 4);
        a.li(5, 1).stw(5, 12, 4).li32(5, SECONDARY).stw(5, 16, 4).li(5, 1).stw(5, 20, 4);
        a.li32(3, 0xf000).sc1();
        a.li(3, 0).bl("work");
        a.label("wait").lwz(5, 0x60, 21).cmpwi(5, 2).bne("wait");
        a.li(5, 1).stw(5, 0x64, 21);
        a.label("hang").b("hang");

        a.org(SECONDARY);
        a.mr(13, 3);
        MmioEmulatorTest.prologue(a);
        a.li(20, 0x21).sldi(20, 20, 40);
        a.mr(3, 13).bl("work");
        a.label("hang2").b("hang2");

        // work: r3 = queue; r22 = its rings; r24 = i; send i + (q << 32), expect twice that plus one.
        a.org(SECONDARY + 0x400).label("work");
        a.sldi(22, 3, 12).li32(5, RINGS).add(22, 22, 5).li32(23, iters).li(24, 0);
        a.label("loop");
        a.sldi(25, 3, 32).add(25, 25, 24).li(26, 0x800).stdbrx(25, 22, 26);
        a.andiDot(27, 24, 7).sldi(27, 27, 1).addi(27, 27, 0x204).li(26, 0);
        sthbrx(a, 26, 22, 27);
        a.lwsync();
        a.addi(28, 24, 1).li(27, 0x202);
        sthbrx(a, 28, 22, 27);
        a.sync();
        a.sldi(27, 3, 2).addi(27, 27, 0x3000);
        sthbrx(a, 3, 20, 27);
        a.andiDot(28, 28, 0xffff).li(27, 0x402);
        a.label("poll");
        lhbrx(a, 29, 22, 27);
        a.cmpd(29, 28).bne("poll");
        a.lwsync();
        a.li(26, 0x810);
        ldbrx(a, 29, 22, 26);
        a.add(30, 25, 25).addi(30, 30, 1).cmpd(29, 30).bne("bad");
        a.addi(24, 24, 1).cmpd(24, 23).blt("loop");
        a.addi(26, 21, 0x60);
        a.label("inc").lwarx(5, 0, 26).addi(5, 5, 1).stwcx(5, 0, 26).bne("inc");
        a.blr();
        a.label("bad").li(5, 0x99).stw(5, 0xf0, 21).std(29, 0xe0, 21).std(30, 0xe8, 21).b("bad");

        Doubler dev = new Doubler();
        Scratch scratch = new Scratch();
        MachineConfig cfg = MmioEmulatorTest.config(2, a.bytesBe());
        cfg.addPciDevice(new VirtioPciDevice(dev)).addPciDevice(scratch);
        try (Emulator emu = new Emulator(cfg)) {
            PhysicalMemory pm = emu.machine().memory();
            for (int q = 0; q < 2; q++) {
                long base = RINGS + q * 0x1000L;
                pm.write64le(base, base + 0x800);
                pm.write32le(base + 8, 8);
                pm.write16le(base + 12, 1); // NEXT
                pm.write16le(base + 14, 1);
                pm.write64le(base + 16, base + 0x810);
                pm.write32le(base + 24, 8);
                pm.write16le(base + 28, 2); // WRITE
            }
            Thread service = new Thread(dev::serve, "embedder");
            service.start();
            Thread runner = new Thread(emu::run, "runner");
            runner.start();
            MmioEmulatorTest.waitFor(() -> pm.read32be(DATA + 0x64) == 1 || pm.read32be(DATA + 0xf0) != 0,
                    "the CPUs to finish");
            emu.stop();
            runner.join();
            service.interrupt();
            service.join();
            assertEquals(0, pm.read32be(DATA + 0xf0), String.format("failure code; got 0x%x, want 0x%x",
                    pm.read64be(DATA + 0xe0), pm.read64be(DATA + 0xe8)));
            assertEquals(null, dev.error);
            assertEquals(2L * iters, dev.served.get());
            assertTrue(dev.kickThreads.size() == 2, "kicks from both CPU threads: " + dev.kickThreads);
            assertEquals(0, scratch.slot() - 1);
            assertTrue(scratch.dmaOk);
            assertEquals(0x0bad_cafeL, pm.read64le(DMA_PAGE));
            assertEquals(0x200080000000L, scratch.barAddress(0));
        }
    }
}
