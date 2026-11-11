package org.boxtheta.javapower.papr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.junit.jupiter.api.Test;

/**
 * The virtio PCI transport, driven from the test as a driver would: configuration space and
 * capabilities, reset, feature negotiation, queue setup, notification, the ISR and the
 * interrupt, request parsing (chained, indirect, malformed), event index suppression, requests
 * completed from another thread, and the saved state with requests in flight.
 */
class VirtioTest {
    static final long BAR = 0x2100_0000_0000L;
    static final long COMMON = BAR;
    static final long ISR = BAR + 0x1000;
    static final long DEVCFG = BAR + 0x2000;
    static final long NOTIFY = BAR + 0x3000;
    // Ring memory of queue 0 and 1, and data buffers.
    static final long DESC = 0x100000;
    static final long AVAIL = 0x101000;
    static final long USED = 0x102000;
    static final long BUF = 0x200000;

    /** An echo device: two queues; requests on queue 0 get their readable bytes back, plus one. */
    static class Echo extends VirtioDevice {
        final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        final LinkedBlockingQueue<Virtqueue> kicks = new LinkedBlockingQueue<>();
        volatile boolean autoServe = true;
        int configWord = 0x11223344;
        int extra;

        Echo() {
            super(30, 16, 8);
        }

        @Override
        protected long deviceFeatures() {
            return 1L << 3;
        }

        @Override
        protected int configSize() {
            return 8;
        }

        @Override
        protected long readConfig(int offset, int size) {
            calls.add("readConfig " + offset + " " + size);
            long v = (configWord & 0xffffffffL) | ((long) extra << 32);
            return (v >>> (8 * offset)) & (size == 8 ? -1L : (1L << (8 * size)) - 1);
        }

        @Override
        protected void writeConfig(int offset, int size, long value) {
            calls.add("writeConfig " + offset + " " + size + " " + Long.toHexString(value));
            if (offset == 4) {
                extra = (int) value;
            }
        }

        volatile boolean refuse;

        @Override
        protected boolean featuresAccepted(long features) {
            calls.add("features " + Long.toHexString(features));
            return !refuse;
        }

        @Override
        protected void driverReady() {
            calls.add("ready");
        }

        @Override
        protected void reset() {
            calls.add("reset");
        }

        @Override
        protected void queueNotified(Virtqueue q) {
            calls.add("notify " + q.index());
            if (autoServe) {
                serve(q);
            } else {
                kicks.add(q);
            }
        }

        static void serve(Virtqueue q) {
            VirtqueueRequest r;
            while ((r = q.pop()) != null) {
                byte[] b = new byte[(int) r.readableLength()];
                assertEquals(b.length, r.read(0, b, 0, b.length));
                for (int i = 0; i < b.length; i++) {
                    b[i]++;
                }
                int n = r.write(0, b, 0, (int) Math.min(b.length, r.writableLength()));
                assertTrue(r.complete(n));
            }
        }

        @Override
        protected void saveState(DataOutput out) throws IOException {
            out.writeInt(extra);
        }

        @Override
        protected void loadState(DataInput in) throws IOException {
            extra = in.readInt();
        }
    }

    /** A driver for the transport, from the test thread. */
    static final class Driver {
        final Rig r;
        final PhysicalMemory mem;

        Driver(Rig r) {
            this.r = r;
            this.mem = r.mem;
        }

        long rd(long a, int size) {
            Long v = PciTest.load(r, a, size);
            assertTrue(v != null, "nothing mapped at " + Long.toHexString(a));
            return v;
        }

        void wr(long a, int size, long v) {
            PciTest.store(r, a, size, v);
        }

        /** Program BAR 4 at {@link #BAR} and enable memory decoding and bus mastering. */
        void enableBar(int slot) {
            PciTest.put(r, slot, 0x20, 4, (int) BAR);
            PciTest.put(r, slot, 0x24, 4, (int) (BAR >>> 32));
            PciTest.put(r, slot, 4, 2, PciDevice.COMMAND_MEMORY | PciDevice.COMMAND_MASTER);
        }

        long features() {
            wr(COMMON, 4, 0);
            long lo = rd(COMMON + 4, 4);
            wr(COMMON, 4, 1);
            return lo | (rd(COMMON + 4, 4) << 32);
        }

        void setFeatures(long f) {
            wr(COMMON + 8, 4, 0);
            wr(COMMON + 0xc, 4, f & 0xffffffffL);
            wr(COMMON + 8, 4, 1);
            wr(COMMON + 0xc, 4, f >>> 32);
        }

        int status() {
            return (int) rd(COMMON + 0x14, 1);
        }

        void status(int s) {
            wr(COMMON + 0x14, 1, s);
        }

        /** Reset, negotiate {@code f}, set up queue 0 of {@code size}, DRIVER_OK. */
        void init(long f, int size) {
            status(0);
            status(VirtioDevice.STATUS_ACKNOWLEDGE | VirtioDevice.STATUS_DRIVER);
            setFeatures(f);
            status(VirtioDevice.STATUS_ACKNOWLEDGE | VirtioDevice.STATUS_DRIVER | VirtioDevice.STATUS_FEATURES_OK);
            assertTrue((status() & VirtioDevice.STATUS_FEATURES_OK) != 0);
            setupQueue(0, size, DESC, AVAIL, USED);
            status(VirtioDevice.STATUS_ACKNOWLEDGE | VirtioDevice.STATUS_DRIVER | VirtioDevice.STATUS_FEATURES_OK
                    | VirtioDevice.STATUS_DRIVER_OK);
        }

        void setupQueue(int q, int size, long desc, long avail, long used) {
            mem.fill(desc, 0x1000, (byte) 0);
            mem.fill(avail, 0x1000, (byte) 0);
            mem.fill(used, 0x1000, (byte) 0);
            wr(COMMON + 0x16, 2, q);
            wr(COMMON + 0x18, 2, size);
            wr(COMMON + 0x20, 4, desc);
            wr(COMMON + 0x24, 4, 0);
            wr(COMMON + 0x28, 4, avail);
            wr(COMMON + 0x2c, 4, 0);
            wr(COMMON + 0x30, 8, used);
            wr(COMMON + 0x1c, 2, 1);
        }

        void desc(long table, int i, long addr, int len, int flags, int next) {
            long d = table + 16L * i;
            mem.write64le(d, addr);
            mem.write32le(d + 8, len);
            mem.write16le(d + 12, flags);
            mem.write16le(d + 14, next);
        }

        /** Make chain {@code head} available on the queue with rings at AVAIL, size {@code size}. */
        void offer(int head, int size) {
            int idx = mem.read16le(AVAIL + 2);
            mem.write16le(AVAIL + 4 + 2L * (idx % size), head);
            mem.write16le(AVAIL + 2, idx + 1);
        }

        void kick(int q) {
            wr(NOTIFY + 4L * q, 2, q);
        }

        int usedIdx() {
            return mem.read16le(USED + 2);
        }

        int[] used(int i, int size) {
            long e = USED + 4 + 8L * (i % size);
            return new int[] {mem.read32le(e), mem.read32le(e + 4)};
        }
    }

    static Rig rig(Echo e) {
        return new Rig(new MachineConfig().ram(256L << 20).addPciDevice(new VirtioPciDevice(e)));
    }

    static long f(int... bits) {
        long v = 0;
        for (int b : bits) {
            v |= 1L << b;
        }
        return v;
    }

    @Test
    void configurationSpaceIsQemus() {
        Echo e = new Echo();
        Rig r = rig(e);
        assertEquals(0x105e1af4, PciTest.cfg(r, 0, 0, 4));
        assertEquals(0x00ff0001, PciTest.cfg(r, 0, 8, 4));
        assertEquals(0x11001af4, PciTest.cfg(r, 0, 0x2c, 4));
        assertEquals(1, PciTest.cfg(r, 0, 0x3d, 1));
        assertEquals(0x84, PciTest.cfg(r, 0, 0x34, 1));
        // The capability chain, as QEMU lays it out: PCI_CFG, notify, device, ISR, common.
        int[][] caps = {
            {0x84, 0x70, 20, 5, 0, 0, 0},
            {0x70, 0x60, 20, 2, 4, 0x3000, 0x1000},
            {0x60, 0x50, 16, 4, 4, 0x2000, 0x1000},
            {0x50, 0x40, 16, 3, 4, 0x1000, 0x1000},
            {0x40, 0x00, 16, 1, 4, 0x0000, 0x1000},
        };
        for (int[] c : caps) {
            int o = c[0];
            assertEquals(0x09 | (c[1] << 8) | (c[2] << 16) | (c[3] << 24), PciTest.cfg(r, 0, o, 4), "cap at " + o);
            assertEquals(c[4], PciTest.cfg(r, 0, o + 4, 1));
            assertEquals(c[5], PciTest.cfg(r, 0, o + 8, 4));
            assertEquals(c[6], PciTest.cfg(r, 0, o + 12, 4));
        }
        assertEquals(4, PciTest.cfg(r, 0, 0x70 + 16, 4));
        // BAR 4: 16 KiB, 64-bit, prefetchable.
        PciTest.put(r, 0, 0x20, 4, -1);
        assertEquals(0xffffc00c, PciTest.cfg(r, 0, 0x20, 4));
        // The node QEMU makes for it.
        Fdt.Node n = Fdt.parse(r.readBytes(r.m.fdtAddress(), Machine.FDT_MAX_SIZE)).find("/pci@800000020000000/legacy-device@0");
        assertArrayEquals(Fdt.cells(0, 0, 0, 0, 0, 0x3000020, 0, 0, 0, 0x4000), n.get("reg"));
        assertEquals("qemu_virtio-type30-pci:0000:00:00.0", n.getString("ibm,loc-code"));
        assertEquals(0x1100, n.getCell("subsystem-id", 0));
        assertEquals("virtio-rng-pci", new VirtioPciDevice(new VirtioRng()).name());
    }

    @Test
    void negotiationQueueAndInterrupt() {
        Echo e = new Echo();
        Rig r = rig(e);
        Driver d = new Driver(r);
        d.enableBar(0);
        assertEquals(f(3, 28, 29, 32), d.features());
        assertEquals(2, d.rd(COMMON + 0x12, 2));
        assertEquals(0xffff, d.rd(COMMON + 0x10, 2));
        // As in QEMU, bits not offered are dropped and VERSION_1 is not insisted on.
        d.status(3);
        d.setFeatures(f(3, 5));
        d.status(11);
        assertEquals(11, d.status());
        assertEquals(f(3), e.negotiatedFeatures());
        d.status(0);
        d.status(3);
        d.setFeatures(f(32, 3));
        d.status(11);
        assertEquals(11, d.status());
        assertEquals(f(32, 3), e.negotiatedFeatures());
        assertTrue(e.calls.contains("features 100000008"), e.calls.toString());
        // A device may refuse.
        e.refuse = true;
        d.status(0);
        d.status(3);
        d.status(11);
        assertEquals(3, d.status());
        e.refuse = false;
        d.status(0);
        d.status(3);
        d.setFeatures(f(32, 3));
        d.status(11);
        // Feature bits are frozen once accepted.
        d.setFeatures(f(32, 3, 29));
        assertEquals(f(32, 3), e.negotiatedFeatures());
        // Queue setup: size must be a power of two up to the maximum.
        d.wr(COMMON + 0x16, 2, 0);
        assertEquals(16, d.rd(COMMON + 0x18, 2));
        d.wr(COMMON + 0x18, 2, 12);
        assertEquals(16, d.rd(COMMON + 0x18, 2));
        d.wr(COMMON + 0x18, 2, 32);
        assertEquals(16, d.rd(COMMON + 0x18, 2));
        d.wr(COMMON + 0x16, 2, 1);
        assertEquals(8, d.rd(COMMON + 0x18, 2));
        assertEquals(1, d.rd(COMMON + 0x1e, 2));
        d.wr(COMMON + 0x16, 2, 2);
        assertEquals(0, d.rd(COMMON + 0x18, 2));
        d.setupQueue(0, 8, DESC, AVAIL, USED);
        assertEquals(8, d.rd(COMMON + 0x18, 2));
        assertEquals(1, d.rd(COMMON + 0x1c, 2));
        assertEquals(DESC, d.rd(COMMON + 0x20, 4));
        assertEquals(USED, d.rd(COMMON + 0x30, 8));
        d.status(15);
        assertTrue(e.calls.contains("ready"));
        // One request: 4 readable bytes, then 8 writable in two buffers.
        r.writeBytes(BUF, (byte) 1, (byte) 2, (byte) 3, (byte) 4);
        d.desc(DESC, 0, BUF, 4, Virtqueue.DESC_F_NEXT, 5);
        d.desc(DESC, 5, BUF + 0x100, 2, Virtqueue.DESC_F_NEXT | Virtqueue.DESC_F_WRITE, 2);
        d.desc(DESC, 2, BUF + 0x200, 6, Virtqueue.DESC_F_WRITE, 0);
        d.offer(0, 8);
        assertArrayEquals(new int[] {0}, r.rtas("ibm,set-xive", 1, DeviceTree.PHB0_LSI_BASE, 0, 5));
        assertEquals(H.SUCCESS, r.hcall(H.CPPR, 0xff));
        assertFalse(r.cpu.extIrq);
        d.kick(0);
        assertEquals(1, d.usedIdx());
        assertArrayEquals(new int[] {0, 4}, d.used(0, 8));
        assertArrayEquals(new byte[] {2, 3}, r.readBytes(BUF + 0x100, 2));
        assertArrayEquals(new byte[] {4, 5, 0}, r.readBytes(BUF + 0x200, 3));
        // The interrupt is pending until the driver reads the ISR, which clears it.
        assertTrue(r.cpu.extIrq);
        assertEquals(1, d.rd(ISR, 1));
        assertEquals(0, d.rd(ISR, 1));
        assertEquals(H.SUCCESS, r.hcall(H.XIRR));
        assertEquals(H.SUCCESS, r.hcall(H.EOI, r.r(4)));
        assertFalse(r.cpu.extIrq);
        // NO_INTERRUPT in the available ring suppresses it.
        r.mem.write16le(AVAIL, Virtqueue.AVAIL_F_NO_INTERRUPT);
        d.desc(DESC, 1, BUF, 4, 0, 0);
        d.offer(1, 8);
        d.kick(0);
        assertEquals(2, d.usedIdx());
        assertEquals(0, d.rd(ISR, 1));
        // Device configuration space, and a configuration change interrupt.
        assertEquals(0x11223344L, d.rd(DEVCFG, 4));
        assertEquals(0x22L, d.rd(DEVCFG + 2, 1));
        d.wr(DEVCFG + 4, 4, 0x55);
        assertEquals(0x55L, d.rd(DEVCFG + 4, 4));
        assertEquals(0L, d.rd(DEVCFG + 8, 4)); // beyond configSize
        int gen = (int) d.rd(COMMON + 0x15, 1);
        e.configChanged();
        assertEquals(gen + 1, d.rd(COMMON + 0x15, 1));
        assertEquals(2, d.rd(ISR, 1));
        // Reset: status 0, queues disabled, the device told.
        d.status(0);
        assertEquals(0, d.status());
        assertTrue(e.calls.contains("reset"));
        d.wr(COMMON + 0x16, 2, 0);
        assertEquals(0, d.rd(COMMON + 0x1c, 2));
        assertEquals(16, d.rd(COMMON + 0x18, 2));
        assertEquals(0L, e.negotiatedFeatures());
    }

    @Test
    void indirectAndMalformedChains() {
        Echo e = new Echo();
        Rig r = rig(e);
        Driver d = new Driver(r);
        d.enableBar(0);
        d.init(f(32, 28), 8);
        // An indirect table of two descriptors.
        long table = BUF + 0x1000;
        r.writeBytes(BUF, (byte) 7, (byte) 8);
        d.desc(table, 0, BUF, 2, Virtqueue.DESC_F_NEXT, 1);
        d.desc(table, 1, BUF + 0x10, 2, Virtqueue.DESC_F_WRITE, 0);
        d.desc(DESC, 3, table, 32, Virtqueue.DESC_F_INDIRECT, 0);
        d.offer(3, 8);
        d.kick(0);
        assertArrayEquals(new int[] {3, 2}, d.used(0, 8));
        assertArrayEquals(new byte[] {8, 9}, r.readBytes(BUF + 0x10, 2));
        // A loop: the device needs a reset, and the driver gets a configuration interrupt.
        d.desc(DESC, 0, BUF, 2, Virtqueue.DESC_F_NEXT, 1);
        d.desc(DESC, 1, BUF, 2, Virtqueue.DESC_F_NEXT, 0);
        d.offer(0, 8);
        d.kick(0);
        assertEquals(1, d.usedIdx());
        assertTrue((d.status() & VirtioDevice.STATUS_DEVICE_NEEDS_RESET) != 0);
        assertEquals(3, d.rd(ISR, 1)); // the indirect request's interrupt, then the configuration one
        assertNull(e.queue(0).pop());
        // Reset recovers; then a buffer outside RAM breaks it again.
        d.init(f(32), 8);
        assertEquals(0, d.status() & VirtioDevice.STATUS_DEVICE_NEEDS_RESET);
        d.desc(DESC, 0, 0x7fff_0000_0000L, 16, 0, 0);
        d.offer(0, 8);
        d.kick(0);
        assertTrue((d.status() & VirtioDevice.STATUS_DEVICE_NEEDS_RESET) != 0);
        // Indirect descriptors without the feature are malformed too.
        d.init(f(32), 8);
        d.desc(DESC, 3, table, 32, Virtqueue.DESC_F_INDIRECT, 0);
        d.offer(3, 8);
        d.kick(0);
        assertTrue((d.status() & VirtioDevice.STATUS_DEVICE_NEEDS_RESET) != 0);
        // A readable buffer after a writable one.
        d.init(f(32), 8);
        d.desc(DESC, 0, BUF, 2, Virtqueue.DESC_F_NEXT | Virtqueue.DESC_F_WRITE, 1);
        d.desc(DESC, 1, BUF, 2, 0, 0);
        d.offer(0, 8);
        d.kick(0);
        assertTrue((d.status() & VirtioDevice.STATUS_DEVICE_NEEDS_RESET) != 0);
        // Misaligned rings, or rings outside RAM, are refused at enable.
        d.init(f(32), 8);
        d.wr(COMMON + 0x16, 2, 1);
        d.wr(COMMON + 0x20, 8, DESC + 0x2008);
        d.wr(COMMON + 0x1c, 2, 1);
        assertEquals(0, d.rd(COMMON + 0x1c, 2));
        assertTrue((d.status() & VirtioDevice.STATUS_DEVICE_NEEDS_RESET) != 0);
        d.init(f(32), 8);
        d.wr(COMMON + 0x16, 2, 1);
        d.wr(COMMON + 0x20, 8, 0x7fff_0000_0000L);
        d.wr(COMMON + 0x1c, 2, 1);
        assertEquals(0, d.rd(COMMON + 0x1c, 2));
        assertTrue((d.status() & VirtioDevice.STATUS_DEVICE_NEEDS_RESET) != 0);
    }

    @Test
    void eventIndexSuppressesKicksAndInterrupts() {
        Echo e = new Echo();
        e.autoServe = false;
        Rig r = rig(e);
        Driver d = new Driver(r);
        d.enableBar(0);
        d.init(f(32, 29), 8);
        Virtqueue q = e.queue(0);
        long availEvent = USED + 4 + 8 * 8;
        long usedEvent = AVAIL + 4 + 2 * 8;
        for (int i = 0; i < 3; i++) {
            d.desc(DESC, i, BUF + 16L * i, 4, 0, 0);
            d.offer(i, 8);
        }
        // Popping asks for a kick at the next new request.
        VirtqueueRequest a = q.pop();
        assertEquals(1, r.mem.read16le(availEvent));
        VirtqueueRequest b = q.pop();
        VirtqueueRequest c = q.pop();
        assertNull(q.pop());
        assertEquals(3, r.mem.read16le(availEvent));
        // The driver wants an interrupt only once the used index passes used_event (2 here).
        r.mem.write16le(usedEvent, 2);
        assertTrue(a.complete(0));
        assertEquals(0, d.rd(ISR, 1));
        assertTrue(b.complete(0));
        assertEquals(0, d.rd(ISR, 1));
        assertTrue(c.complete(0));
        assertEquals(1, d.rd(ISR, 1));
        assertFalse(c.complete(0)); // already completed
        // Turning notifications off stops avail_event moving.
        q.setNotifications(false);
        d.desc(DESC, 4, BUF, 4, 0, 0);
        d.offer(4, 8);
        assertTrue(q.hasAvailable());
        assertTrue(q.pop().complete(0));
        assertEquals(3, r.mem.read16le(availEvent));
        q.setNotifications(true);
        assertEquals(4, r.mem.read16le(availEvent));
    }

    @Test
    void noEventIndexUsesTheFlag() {
        Echo e = new Echo();
        Rig r = rig(e);
        Driver d = new Driver(r);
        d.enableBar(0);
        d.init(f(32), 8);
        e.queue(0).setNotifications(false);
        assertEquals(Virtqueue.USED_F_NO_NOTIFY, r.mem.read16le(USED));
        e.queue(0).setNotifications(true);
        assertEquals(0, r.mem.read16le(USED));
    }

    @Test
    void pciConfigurationAccessCapability() {
        Echo e = new Echo();
        Rig r = rig(e);
        new Driver(r).enableBar(0);
        int cap = 0x84;
        // Read num_queues (offset 0x12, 2 bytes) of BAR 4 through the window.
        PciTest.put(r, 0, cap + 4, 1, 4);
        PciTest.put(r, 0, cap + 8, 4, 0x12);
        PciTest.put(r, 0, cap + 12, 4, 2);
        assertEquals(2, PciTest.cfg(r, 0, cap + 16, 4) & 0xffff);
        // Write the status through it.
        PciTest.put(r, 0, cap + 8, 4, 0x14);
        PciTest.put(r, 0, cap + 12, 4, 1);
        PciTest.put(r, 0, cap + 16, 1, 3);
        assertEquals(3, new Driver(r).status());
        // Another BAR or a bad length does nothing.
        PciTest.put(r, 0, cap + 4, 1, 1);
        PciTest.put(r, 0, cap + 16, 1, 0);
        assertEquals(3, new Driver(r).status());
    }

    @Test
    void requestsCompletedFromAnotherThreadAndStaleAfterReset() throws Exception {
        Echo e = new Echo();
        e.autoServe = false;
        Rig r = rig(e);
        Driver d = new Driver(r);
        d.enableBar(0);
        d.init(f(32), 16);
        // The embedder's thread serves kicks while the test thread (a CPU) makes requests.
        AtomicInteger served = new AtomicInteger();
        Thread t = new Thread(() -> {
            try {
                Virtqueue q;
                while ((q = e.kicks.poll(10, TimeUnit.SECONDS)) != null) {
                    VirtqueueRequest req;
                    while ((req = q.pop()) != null) {
                        byte[] b = new byte[1];
                        req.read(0, b, 0, 1);
                        b[0] += 1;
                        req.write(0, b, 0, 1);
                        req.complete(1);
                        served.incrementAndGet();
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }, "device");
        t.start();
        int n = 2000;
        for (int i = 0; i < n; i++) {
            int head = (i % 8) * 2;
            // Keep at most 8 requests outstanding: request i - 8 used the same buffers.
            while (((i - d.usedIdx()) & 0xffff) >= 8) {
                Thread.onSpinWait();
            }
            if (i >= 8) {
                assertEquals(((i - 8) & 0x7f) + 1, r.mem.read8(BUF + 0x100 + i % 8));
            }
            r.mem.write8(BUF + i % 8, i & 0x7f);
            d.desc(DESC, head, BUF + i % 8, 1, Virtqueue.DESC_F_NEXT, head + 1);
            d.desc(DESC, head + 1, BUF + 0x100 + i % 8, 1, Virtqueue.DESC_F_WRITE, 0);
            d.offer(head, 16);
            d.kick(0);
        }
        long end = System.currentTimeMillis() + 10_000;
        while (d.usedIdx() != (n & 0xffff) && System.currentTimeMillis() < end) {
            Thread.sleep(1);
        }
        assertEquals(n & 0xffff, d.usedIdx());
        assertEquals(n, served.get());
        t.interrupt();
        t.join();
        // A request held across a reset is stale: no copies, no completion.
        d.desc(DESC, 0, BUF, 1, Virtqueue.DESC_F_NEXT, 1);
        d.desc(DESC, 1, BUF + 0x100, 1, Virtqueue.DESC_F_WRITE, 0);
        d.offer(0, 16);
        VirtqueueRequest held = e.queue(0).pop();
        assertTrue(held.isCurrent());
        d.status(0);
        assertFalse(held.isCurrent());
        assertEquals(-1, held.write(0, new byte[1], 0, 1));
        assertEquals(-1, held.read(0, new byte[1], 0, 1));
        assertFalse(held.complete(1));
    }

    @Test
    void savedStateRedeliversRequestsInFlight() throws IOException {
        Echo e = new Echo();
        e.autoServe = false;
        Rig ra = rig(e);
        Driver d = new Driver(ra);
        d.enableBar(0);
        d.init(f(32, 29), 8);
        d.wr(DEVCFG + 4, 4, 0x77);
        for (int i = 0; i < 3; i++) {
            ra.mem.write8(BUF + i, 10 + i);
            d.desc(DESC, 2 * i, BUF + i, 1, Virtqueue.DESC_F_NEXT, 2 * i + 1);
            d.desc(DESC, 2 * i + 1, BUF + 0x100 + i, 1, Virtqueue.DESC_F_WRITE, 0);
            d.offer(2 * i, 8);
        }
        // Pop all three, complete the middle one: the first and last are in flight.
        VirtqueueRequest a = e.queue(0).pop();
        VirtqueueRequest b = e.queue(0).pop();
        VirtqueueRequest c = e.queue(0).pop();
        assertTrue(b.complete(0));
        byte[] state = MachineStateTest.save(ra.m);
        // The saved machine keeps its requests.
        assertTrue(a.isCurrent() && c.isCurrent());

        Echo e2 = new Echo();
        e2.autoServe = false;
        Rig rb = rig(e2);
        MachineStateTest.load(rb.m, state);
        // State loaded: no RAM here (the platform test saves only the machine), so copy the rings.
        rb.mem.write(0, ra.readBytes(0, 0x300000), 0, 0x300000);
        assertEquals(0x77, e2.extra);
        assertEquals(15, new Driver(rb).status());
        // The loaded device hears about them from the pump, and pops them again first.
        rb.m.pump();
        assertEquals(List.of(e2.queue(0)), List.copyOf(e2.kicks));
        VirtqueueRequest a2 = e2.queue(0).pop();
        VirtqueueRequest c2 = e2.queue(0).pop();
        assertEquals(0, a2.head());
        assertEquals(4, c2.head());
        assertNull(e2.queue(0).pop());
        byte[] one = new byte[1];
        a2.read(0, one, 0, 1);
        assertEquals(10, one[0]);
        assertTrue(a2.complete(1));
        assertTrue(c2.complete(1));
        assertEquals(3, rb.mem.read16le(USED + 2));
        // A second pump has nothing more to say.
        e2.kicks.clear();
        rb.m.pump();
        assertTrue(e2.kicks.isEmpty());
        // The hardware check compares the virtio device.
        Rig other = new Rig(new MachineConfig().ram(256L << 20).addPciDevice(new VirtioPciDevice(new VirtioRng())));
        String m = assertThrows(org.boxtheta.javapower.core.StateMismatchException.class,
                () -> MachineStateTest.load(other.m, state)).getMessage();
        assertTrue(m.contains("device id: saved 0x105e, this machine 0x1005"), m);
        assertTrue(m.contains("BAR 0: saved none, this machine I/O 0x20"), m);
        assertTrue(m.contains("device: saved virtio device 30, modern, queues [16, 8]"), m);
    }

    @Test
    void entropyDevice() {
        VirtioRng rng = new VirtioRng(new Random(5));
        Rig r = new Rig(new MachineConfig().ram(256L << 20).addPciDevice(new VirtioPciDevice(rng)));
        Driver d = new Driver(r);
        d.enableBar(0);
        assertEquals(0x10051af4, PciTest.cfg(r, 0, 0, 4)); // transitional by default, as in QEMU
        assertEquals(8, d.rd(COMMON + 0x18, 2));
        assertEquals(f(28, 29, 32), d.features());
        d.init(f(32), 8);
        d.desc(DESC, 0, BUF, 64, Virtqueue.DESC_F_WRITE, 0);
        d.offer(0, 8);
        d.kick(0);
        assertArrayEquals(new int[] {0, 64}, d.used(0, 8));
        byte[] want = new byte[64];
        new Random(5).nextBytes(want);
        assertArrayEquals(want, r.readBytes(BUF, 64));
        assertEquals(64, rng.bytesServed());
    }

    @Test
    void transitionalDeviceLegacyInterface() {
        for (boolean little : new boolean[] {false, true}) {
            VirtioRng rng = new VirtioRng(new Random(9));
            VirtioPciDevice t = new VirtioPciDevice(rng);
            assertTrue(t.isTransitional());
            Rig r = new Rig(new MachineConfig().ram(256L << 20).addPciDevice(t));
            // Identity as QEMU's default virtio-rng-pci: legacy id, revision 0, subsystem = type.
            assertEquals(0x10051af4, PciTest.cfg(r, 0, 0, 4));
            assertEquals(0x00ff0000, PciTest.cfg(r, 0, 8, 4));
            assertEquals(0x00041af4, PciTest.cfg(r, 0, 0x2c, 4));
            PciTest.put(r, 0, 0x10, 4, -1);
            assertEquals(0xffffffe1, PciTest.cfg(r, 0, 0x10, 4)); // 32 I/O ports
            Fdt.Node n = Fdt.parse(r.readBytes(r.m.fdtAddress(), Machine.FDT_MAX_SIZE))
                    .find("/pci@800000020000000/legacy-device@0");
            assertArrayEquals(Fdt.cells(0, 0, 0, 0, 0, 0x1000010, 0, 0, 0, 0x20, 0x3000020, 0, 0, 0, 0x4000),
                    n.get("reg"));
            assertEquals(4, n.getCell("subsystem-id", 0));
            assertEquals(0, n.getCell("revision-id", 0));
            PciTest.put(r, 0, 0x10, 4, 0x1000);
            PciTest.put(r, 0, 4, 2, PciDevice.COMMAND_IO | PciDevice.COMMAND_MASTER);
            long io = Phb.IO_WIN + 0x1000;
            Driver d = new Driver(r);
            // The legacy driver resets the device on a CPU of the byte order under test.
            r.cpu.runner = Thread.currentThread();
            r.cpu.msr = little ? r.cpu.msr | org.boxtheta.javapower.core.Msr.LE
                    : r.cpu.msr & ~org.boxtheta.javapower.core.Msr.LE;
            d.wr(io + VirtioPciDevice.L_STATUS, 1, 0);
            r.cpu.runner = null;
            assertEquals(0x30000000L, d.rd(io + VirtioPciDevice.L_HOST_FEATURES, 4));
            d.wr(io + VirtioPciDevice.L_STATUS, 1, 3);
            d.wr(io + VirtioPciDevice.L_GUEST_FEATURES, 4, 0);
            assertEquals(8L, d.rd(io + VirtioPciDevice.L_QUEUE_NUM, 2));
            r.mem.fill(DESC, 0x3000, (byte) 0);
            d.wr(io + VirtioPciDevice.L_QUEUE_PFN, 4, DESC >>> 12);
            assertEquals(DESC >>> 12, d.rd(io + VirtioPciDevice.L_QUEUE_PFN, 4));
            d.wr(io + VirtioPciDevice.L_STATUS, 1, 7);
            // Legacy layout: avail after the 8 descriptors, used at the next 4 KiB.
            long avail = DESC + 16 * 8;
            long used = DESC + 0x1000;
            if (little) {
                r.mem.write64le(DESC, BUF);
                r.mem.write32le(DESC + 8, 16);
                r.mem.write16le(DESC + 12, Virtqueue.DESC_F_WRITE);
                r.mem.write16le(avail + 2, 1);
            } else {
                r.mem.write64be(DESC, BUF);
                r.mem.write32be(DESC + 8, 16);
                r.mem.write16be(DESC + 12, Virtqueue.DESC_F_WRITE);
                r.mem.write16be(avail + 2, 1);
            }
            d.wr(io + VirtioPciDevice.L_QUEUE_NOTIFY, 2, 0);
            assertEquals(1, little ? r.mem.read16le(used + 2) : r.mem.read16be(used + 2));
            assertEquals(16, little ? r.mem.read32le(used + 8) : r.mem.read32be(used + 8));
            byte[] want = new byte[16];
            new Random(9).nextBytes(want);
            assertArrayEquals(want, r.readBytes(BUF, 16));
            assertEquals(1L, d.rd(io + VirtioPciDevice.L_ISR, 1));
            assertEquals(0L, d.rd(io + VirtioPciDevice.L_ISR, 1));
            // A zero page frame number resets the device.
            d.wr(io + VirtioPciDevice.L_QUEUE_PFN, 4, 0);
            assertEquals(0L, d.rd(io + VirtioPciDevice.L_STATUS, 1));
        }
        // Modern only on request; a type without a legacy interface cannot be transitional.
        assertFalse(new VirtioPciDevice(new VirtioRng(), false).isTransitional());
        assertFalse(new VirtioPciDevice(new Echo()).isTransitional());
        assertThrows(IllegalArgumentException.class, () -> new VirtioPciDevice(new Echo(), true));
    }

    @Test
    void deviceDescriptionChecks() {
        assertThrows(IllegalArgumentException.class, () -> new VirtioDevice(0, 8) {});
        assertThrows(IllegalArgumentException.class, () -> new VirtioDevice(4, 6) {});
        assertThrows(IllegalArgumentException.class, () -> new VirtioDevice(4) {});
        VirtioDevice transportBits = new VirtioDevice(4, 8) {
            @Override
            protected long deviceFeatures() {
                return 1L << 32;
            }
        };
        assertThrows(IllegalArgumentException.class, () -> new VirtioPciDevice(transportBits));
        VirtioRng rng = new VirtioRng();
        new VirtioPciDevice(rng);
        assertThrows(IllegalStateException.class, () -> new VirtioPciDevice(rng));
        assertThrows(IllegalStateException.class, () -> new VirtioRng().queue(0));
    }
}
