package org.boxtheta.javapower.papr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.StateInput;
import org.boxtheta.javapower.core.StateMismatchException;
import org.boxtheta.javapower.core.StateOutput;
import org.junit.jupiter.api.Test;

/**
 * The l-lan device ({@code spapr-vlan}): its hypercalls with the results QEMU 11.1's
 * hw/net/spapr_llan.c gives, the receive path, multicast filtering, the threading contract
 * of {@link NetBackend}, reset, quiesce and saved state.
 */
class LLanTest {
    static final int LAN0 = VioDevice.REG_BASE + 3;
    static final int LAN1 = VioDevice.REG_BASE + 4;
    static final int VTY = VioDevice.REG_BASE + 1;
    static final int LAN0_IRQ = Xics.IRQ_VIO | 3;

    // Guest memory layout of the tests (DMA addresses are real addresses unless a test maps TCEs).
    static final long BUF_LIST = 0x100000;
    static final long FILTER_LIST = 0x101000;
    static final long RXQ = 0x102000;
    static final long BUFS = 0x200000;

    /** A backend that records what the guest sends. */
    static class Peer implements NetBackend {
        final List<byte[]> sent = new ArrayList<>();
        volatile FrameSink sink;
        volatile boolean closed;

        @Override
        public synchronized void transmit(byte[] frame) {
            sent.add(frame);
        }

        @Override
        public void start(FrameSink s) {
            sink = s;
        }

        @Override
        public void close() {
            closed = true;
        }

        synchronized List<byte[]> sent() {
            return new ArrayList<>(sent);
        }

        boolean give(byte[] f) {
            return sink.receive(f, 0, f.length);
        }
    }

    static Rig rig(Peer... peers) {
        MachineConfig c = new MachineConfig().ram(256L << 20);
        for (Peer p : peers) {
            c.addNetwork(p);
        }
        return new Rig(c);
    }

    static MachineConfig net(MachineConfig c, Peer p, String mac) {
        MachineConfig.Network n = c.addNetwork(p);
        if (mac != null) {
            n.mac(mac);
        }
        return c;
    }

    /** A machine with one device in TCE bypass (DMA addresses are real addresses). */
    static Rig bypass(Peer p) {
        Rig r = rig(p);
        r.m.networks().get(0).tce().setBypass(true);
        return r;
    }

    static long bd(long addr, long len) {
        return LLan.validBd(addr, len);
    }

    /** Register with a receive queue of {@code entries} entries. */
    static void open(Rig r, int entries) {
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_LOGICAL_LAN, LAN0, BUF_LIST, bd(RXQ, 16L * entries), FILTER_LIST,
                0x525400123456L));
    }

    /** Post buffer {@code i} of {@code size} bytes; its handle (first doubleword) is {@code 0x1000 + i}. */
    static long post(Rig r, int i, int size) {
        long a = BUFS + 0x10000L * i;
        r.mem.write64be(a, 0x1000 + i);
        return r.hcall(H.ADD_LOGICAL_LAN_BUFFER, LAN0, bd(a, size));
    }

    static byte[] frame(int dst0, int len, int fill) {
        byte[] f = new byte[len];
        java.util.Arrays.fill(f, (byte) fill);
        f[0] = (byte) dst0;
        return f;
    }

    /** A receive queue entry: control byte, offset, length, handle. */
    record Entry(int control, int offset, int length, long handle) {}

    static Entry entry(Rig r, int i) {
        long a = RXQ + 16L * i;
        return new Entry(r.mem.read8(a), r.mem.read16be(a + 2), r.mem.read32be(a + 4), r.mem.read64be(a + 8));
    }

    static byte[] payload(Rig r, Entry e) {
        return r.readBytes(BUFS + 0x10000L * (e.handle() - 0x1000) + e.offset(), e.length());
    }

    // ---- device tree --------------------------------------------------------------------------

    /**
     * Three devices against QEMU 11.1's reset tree for the reference machine plus
     * {@code -device spapr-vlan} three times: default MAC, 02:00:00:00:00:01 and
     * 00:11:22:33:44:55 (dumped with the monitor's {@code dumpdtb}). The last shows the 8-byte
     * {@code local-mac-address} QEMU gives addresses that are not locally administered.
     */
    @Test
    void deviceTreeMatchesQemu() throws IOException {
        MachineConfig c = new MachineConfig().ram(2L << 30);
        c.addNetwork(new Peer());
        c.addNetwork(new Peer()).mac("02:00:00:00:00:01");
        c.addNetwork(new Peer()).mac("00-11-22-33-44-55");
        Rig r = new Rig(c);
        long fdt = r.m.fdtAddress();
        byte[] blob = r.readBytes(fdt, r.mem.read32be(fdt + 4));
        DeviceTreeTest.compare("", Fdt.parse(Rig.resource("pseries-vlan3.dtb")), Fdt.parse(blob));
    }

    @Test
    void withoutNetworksTheTreeIsUnchanged() {
        Rig r = rig();
        assertTrue(r.m.networks().isEmpty());
        assertEquals(3, r.m.vioDevices().size());
    }

    // ---- configuration ------------------------------------------------------------------------

    @Test
    void macAddressesAreUniqueAndDefaultLikeQemu() {
        MachineConfig c = new MachineConfig().ram(256L << 20);
        c.addNetwork(new Peer());
        c.addNetwork(new Peer()).mac("52:54:00:12:34:57");
        c.addNetwork(new Peer());
        Rig r = new Rig(c);
        List<LLan> n = r.m.networks();
        assertEquals("52:54:00:12:34:56", mac(n.get(0).permanentMacAddress()));
        assertEquals("52:54:00:12:34:57", mac(n.get(1).permanentMacAddress()));
        assertEquals("52:54:00:12:34:58", mac(n.get(2).permanentMacAddress()), "the default skips a used address");
        assertEquals(LAN0, n.get(0).reg());
        assertEquals(LAN1, n.get(1).reg());
        assertEquals(Xics.IRQ_VIO | 5, n.get(2).irq());

        MachineConfig dup = new MachineConfig().ram(256L << 20);
        dup.addNetwork(new Peer()).mac("02:00:00:00:00:01");
        dup.addNetwork(new Peer()).mac("02:00:00:00:00:01");
        assertThrows(IllegalArgumentException.class, () -> new Machine(dup.firmware(new byte[4])));

        MachineConfig.Network nw = new MachineConfig().addNetwork(new Peer());
        assertThrows(IllegalArgumentException.class, () -> nw.mac("01:00:5e:00:00:01"), "multicast");
        assertThrows(IllegalArgumentException.class, () -> nw.mac("00:00:00:00:00:00"));
        assertThrows(IllegalArgumentException.class, () -> nw.mac("52:54:00:12:34"));
        assertThrows(IllegalArgumentException.class, () -> nw.mac("52:54:00:12:34:5g"));
        assertThrows(IllegalArgumentException.class, () -> nw.mac(new byte[5]));
    }

    static String mac(byte[] b) {
        return LLan.formatMac(LLan.macToLong(b));
    }

    // ---- H_REGISTER_LOGICAL_LAN / H_FREE_LOGICAL_LAN ------------------------------------------

    @Test
    void registerChecksItsArgumentsLikeQemu() {
        Peer p = new Peer();
        Rig r = bypass(p);
        long q = bd(RXQ, 0x1000);
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_LOGICAL_LAN, 0x71000099, BUF_LIST, q, FILTER_LIST), "no device");
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_LOGICAL_LAN, VTY, BUF_LIST, q, FILTER_LIST), "not an l-lan");
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_LOGICAL_LAN, LAN0, BUF_LIST + 8, q, FILTER_LIST), "buffer list alignment");
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_LOGICAL_LAN, LAN0, BUF_LIST, q, FILTER_LIST + 0x800), "filter list alignment");
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_LOGICAL_LAN, LAN0, BUF_LIST, q & ~LLan.BD_VALID, FILTER_LIST), "queue not valid");
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_LOGICAL_LAN, LAN0, BUF_LIST, bd(RXQ + 8, 0x100), FILTER_LIST), "queue alignment");
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_LOGICAL_LAN, LAN0, BUF_LIST, bd(RXQ, 0x108), FILTER_LIST), "queue length");
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_LOGICAL_LAN, LAN0, 0x20000000L, q, FILTER_LIST), "outside RAM");
        assertEquals(H.RESOURCE, r.hcall(H.FREE_LOGICAL_LAN, LAN0), "not registered");
        assertEquals(H.PARAMETER, r.hcall(H.FREE_LOGICAL_LAN, VTY));
        assertFalse(r.m.networks().get(0).isOpen());

        // Registration initialises the buffer list page and clears the queue.
        r.mem.fill(BUF_LIST, 0x1000, (byte) 0xee);
        r.mem.fill(RXQ, 0x1000, (byte) 0xee);
        assertEquals(H.SUCCESS, r.hcall(H.VIO_SIGNAL, LAN0, 1));
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_LOGICAL_LAN, LAN0, BUF_LIST, q | LLan.BD_TOGGLE, FILTER_LIST));
        assertTrue(r.m.networks().get(0).isOpen());
        assertEquals(q, r.mem.read64be(BUF_LIST), "receive queue descriptor, toggle cleared");
        assertEquals(bd(FILTER_LIST, 0x1000), r.mem.read64be(BUF_LIST + 8), "filter list descriptor");
        for (long a = 16; a < 0x1000; a += 8) {
            assertEquals(0, r.mem.read64be(BUF_LIST + a));
        }
        for (long a = 0; a < 0x1000; a += 8) {
            assertEquals(0, r.mem.read64be(RXQ + a));
        }
        assertEquals(0, r.m.networks().get(0).signalState(), "registration disables the interrupt");
        assertEquals(H.RESOURCE, r.hcall(H.REGISTER_LOGICAL_LAN, LAN0, BUF_LIST, q, FILTER_LIST), "twice");
        assertEquals(H.SUCCESS, r.hcall(H.FREE_LOGICAL_LAN, LAN0));
        assertFalse(r.m.networks().get(0).isOpen());
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_LOGICAL_LAN, LAN0, BUF_LIST, q, FILTER_LIST), "again after free");
    }

    @Test
    void registerNeedsReadWriteTces() {
        Rig r = rig(new Peer());
        long q = bd(0x2000, 0x1000);
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_LOGICAL_LAN, LAN0, 0x0, q, 0x1000), "nothing mapped");
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, LAN0, 0x0, BUF_LIST | 3));
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, LAN0, 0x1000, FILTER_LIST | 1)); // read-only
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, LAN0, 0x2000, RXQ | 3));
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_LOGICAL_LAN, LAN0, 0x0, q, 0x1000), "filter list read-only");
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, LAN0, 0x1000, FILTER_LIST | 3));
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_LOGICAL_LAN, LAN0, 0x0, bd(0x2000, 0x2000), 0x1000),
                "queue runs into an unmapped page");
        assertEquals(H.PARAMETER, r.hcall(H.REGISTER_LOGICAL_LAN, LAN0, 0x0, bd(LLan.DMA_WINDOW, 0x10), 0x1000),
                "outside the DMA window");
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_LOGICAL_LAN, LAN0, 0x0, q, 0x1000));
        assertEquals(q, r.mem.read64be(BUF_LIST), "written through the TCE");
    }

    // ---- H_ADD_LOGICAL_LAN_BUFFER and pools ---------------------------------------------------

    @Test
    void addBufferChecksItsArgumentsLikeQemu() {
        Rig r = bypass(new Peer());
        assertEquals(H.PARAMETER, r.hcall(H.ADD_LOGICAL_LAN_BUFFER, 0x71000099, bd(BUFS, 2048)));
        assertEquals(H.PARAMETER, r.hcall(H.ADD_LOGICAL_LAN_BUFFER, LAN0, bd(BUFS + 2, 2048)), "alignment");
        assertEquals(H.PARAMETER, r.hcall(H.ADD_LOGICAL_LAN_BUFFER, LAN0, bd(BUFS, 2046)), "length alignment");
        assertEquals(H.PARAMETER, r.hcall(H.ADD_LOGICAL_LAN_BUFFER, LAN0, bd(BUFS, 12)), "shorter than 16");
        assertEquals(H.RESOURCE, post(r, 0, 2048), "checked before the registration");
        open(r, 256);
        assertEquals(H.SUCCESS, post(r, 0, 2048));
        // Five pools: a sixth size is refused while the others hold buffers.
        for (int i = 1; i <= 4; i++) {
            assertEquals(H.SUCCESS, post(r, i, 2048 + 16 * i));
        }
        assertEquals(H.RESOURCE, post(r, 5, 4096));
        // 4096 buffers per pool.
        for (int i = 1; i < LLan.POOL_MAX_BDS; i++) {
            assertEquals(H.SUCCESS, r.hcall(H.ADD_LOGICAL_LAN_BUFFER, LAN0, bd(BUFS, 2048)));
        }
        assertEquals(H.RESOURCE, r.hcall(H.ADD_LOGICAL_LAN_BUFFER, LAN0, bd(BUFS, 2048)));
    }

    @Test
    void emptyPoolsAreReusedForNewSizes() {
        Peer p = new Peer();
        Rig r = bypass(p);
        open(r, 256);
        for (int i = 0; i < 5; i++) {
            assertEquals(H.SUCCESS, post(r, i, 256 + 256 * i));
        }
        assertTrue(p.give(frame(0x02, 200, 1))); // takes the only 256-byte buffer
        assertEquals(H.SUCCESS, post(r, 5, 4096), "the emptied pool takes the new size");
        assertTrue(p.give(frame(0x02, 3000, 2)));
        assertEquals(0x1005, entry(r, 1).handle());
    }

    // ---- receiving ----------------------------------------------------------------------------

    @Test
    void framesGoToTheSmallestFittingBufferLastInFirstOut() {
        Peer p = new Peer();
        Rig r = bypass(p);
        open(r, 256);
        post(r, 0, 512);
        post(r, 1, 512);
        post(r, 2, 2048);
        byte[] small = frame(0x02, 100, 0xaa);
        byte[] large = frame(0x02, 1514, 0xbb);
        byte[] fits504 = frame(0x02, 504, 0xcc);
        assertTrue(p.give(small));
        assertTrue(p.give(large));
        assertTrue(p.give(fits504)); // 504 + 8 = 512
        Entry e0 = entry(r, 0);
        assertEquals(new Entry(0xc0, 8, 100, 0x1001), e0, "last 512-byte buffer posted is used first");
        assertArrayEquals(small, payload(r, e0));
        Entry e1 = entry(r, 1);
        assertEquals(new Entry(0xc0, 8, 1514, 0x1002), e1);
        assertArrayEquals(large, payload(r, e1));
        assertEquals(new Entry(0xc0, 8, 504, 0x1000), entry(r, 2));
        assertEquals(0, entry(r, 3).control());
    }

    @Test
    void theQueueWrapsWithTheToggleBit() {
        Peer p = new Peer();
        Rig r = bypass(p);
        open(r, 2);
        for (int i = 0; i < 5; i++) {
            post(r, i, 2048);
        }
        long q = r.mem.read64be(BUF_LIST);
        p.give(frame(0x02, 60, 1));
        p.give(frame(0x02, 61, 2));
        assertEquals(0xc0, entry(r, 0).control());
        assertEquals(0xc0, entry(r, 1).control());
        assertEquals(q | LLan.BD_TOGGLE, r.mem.read64be(BUF_LIST), "the device flips the queue descriptor's toggle");
        p.give(frame(0x02, 62, 3));
        assertEquals(new Entry(0x40, 8, 62, 0x1002), entry(r, 0), "second pass: toggle bit clear");
        p.give(frame(0x02, 63, 4));
        p.give(frame(0x02, 64, 5));
        assertEquals(new Entry(0xc0, 8, 64, 0x1000), entry(r, 0), "third pass: set again");
        assertEquals(q, r.mem.read64be(BUF_LIST));
    }

    /** Register as FreeBSD's little-endian driver does: the address byte-reversed in the doubleword. */
    static void openLe(Rig r, int entries) {
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_LOGICAL_LAN, LAN0, BUF_LIST, bd(RXQ, 16L * entries), FILTER_LIST,
                Long.reverseBytes(0x525400123456L)));
    }

    /** A receive queue entry with its offset and length read little-endian. */
    static Entry entryLe(Rig r, int i) {
        long a = RXQ + 16L * i;
        return new Entry(r.mem.read8(a), Short.toUnsignedInt(Short.reverseBytes((short) r.mem.read16be(a + 2))),
                Integer.reverseBytes(r.mem.read32be(a + 4)), r.mem.read64be(a + 8));
    }

    @Test
    void theMacArgumentsByteOrderTellsALittleEndianFreeBsdDriver() {
        assertFalse(LLan.reversedMac(0x525400123456L), "Linux, big-endian FreeBSD");
        assertTrue(LLan.reversedMac(Long.reverseBytes(0x525400123456L)), "FreeBSD ppc64le");
        assertTrue(LLan.reversedMac(Long.reverseBytes(0x020000000001L)));
        assertFalse(LLan.reversedMac(0), "SLOF passes no address");
        assertFalse(LLan.reversedMac(Long.reverseBytes(0x525400120000L)), "ends in :00:00: ambiguous, big-endian");
    }

    @Test
    void aLittleEndianFreeBsdDriverGetsLittleEndianOffsetsAndLengths() {
        Peer p = new Peer();
        Rig r = bypass(p);
        open(r, 2);
        assertEquals(java.util.Set.of(), r.m.appliedQuirks(), "Linux's registration needs no quirk");
        assertEquals(H.SUCCESS, r.hcall(H.FREE_LOGICAL_LAN, LAN0));
        openLe(r, 2);
        assertEquals(java.util.Set.of(GuestQuirks.FREEBSD_LLAN_LE_RXQ), r.m.appliedQuirks());
        for (int i = 0; i < 4; i++) {
            post(r, i, 2048);
        }
        byte[] f = frame(0x02, 1514, 0xab);
        assertTrue(p.give(f));
        Entry e = entryLe(r, 0);
        assertEquals(new Entry(0xc0, 8, 1514, 0x1003), e, "control byte and handle as before");
        assertArrayEquals(f, payload(r, e));
        p.give(frame(0x02, 60, 1));
        p.give(frame(0x02, 61, 2));
        assertEquals(new Entry(0x40, 8, 61, 0x1001), entryLe(r, 0), "second pass of the queue");

        // Its multicast addresses arrive byte-reversed as well.
        byte[] group = {0x01, 0x00, 0x5e, 0x00, 0x00, 0x01, 8, 0};
        assertEquals(H.SUCCESS, r.hcall(H.MULTICAST_CTRL, LAN0, LLan.MC_FILTER_MODIFY | LLan.MC_FILTER_ENABLE
                | LLan.MC_ADD, Long.reverseBytes(0x01005e000001L)));
        post(r, 4, 2048);
        assertTrue(p.give(group));
        assertEquals(new Entry(0x40, 8, 8, 0x1004), entryLe(r, 1), "the filter matches the reversed address");

        // Freed and registered as Linux does: big-endian again.
        assertEquals(H.SUCCESS, r.hcall(H.FREE_LOGICAL_LAN, LAN0));
        open(r, 2);
        post(r, 5, 2048);
        p.give(frame(0x02, 100, 3));
        assertEquals(new Entry(0xc0, 8, 100, 0x1005), entry(r, 0));
        assertEquals(java.util.Set.of(GuestQuirks.FREEBSD_LLAN_LE_RXQ), r.m.appliedQuirks(), "still listed");
    }

    @Test
    void theLittleEndianQueueIsSaved() throws IOException {
        Peer pa = new Peer();
        Rig a = bypass(pa);
        openLe(a, 4);
        byte[] state = save(a);
        Rig b = bypass(new Peer());
        assertEquals(java.util.Set.of(), b.m.appliedQuirks());
        load(b, state);
        assertEquals(java.util.Set.of(GuestQuirks.FREEBSD_LLAN_LE_RXQ), b.m.appliedQuirks(), "the loaded state has it");
        assertArrayEquals(state, save(b));
        assertEquals("52:54:00:12:34:56", mac(b.m.networks().get(0).macAddress()));
        b.m.cpuExecEnter(b.cpu);
        post(b, 0, 2048);
        b.m.networks().get(0).receive(frame(0x02, 300, 1), 0, 300);
        assertEquals(new Entry(0xc0, 8, 300, 0x1000), entryLe(b, 0));
    }

    @Test
    void theInterruptFollowsVioSignal() {
        Peer p = new Peer();
        Rig r = bypass(p);
        r.rtas("ibm,set-xive", 1, LAN0_IRQ, 0, 5);
        r.hcall(H.CPPR, 0xff);
        open(r, 256);
        post(r, 0, 2048);
        post(r, 1, 2048);
        p.give(frame(0x02, 60, 1));
        assertFalse(r.cpu.extIrq, "interrupts off after registration");
        assertEquals(H.SUCCESS, r.hcall(H.VIO_SIGNAL, LAN0, 1));
        assertEquals(H.PARAMETER, r.hcall(H.VIO_SIGNAL, LAN0, 2), "signal mask 1");
        p.give(frame(0x02, 60, 2));
        assertTrue(r.cpu.extIrq);
        assertEquals(H.SUCCESS, r.hcall(H.XIRR));
        assertEquals(0xff000000L | LAN0_IRQ, r.r(4));
    }

    @Test
    void framesWaitForBuffersAndAreDroppedWhileClosed() {
        Peer p = new Peer();
        Rig r = bypass(p);
        LLan n = r.m.networks().get(0);
        assertFalse(p.give(frame(0x02, 60, 1)), "not registered");
        assertEquals(1, n.droppedFrames());
        open(r, 256);
        assertTrue(p.give(frame(0x02, 60, 2)), "no buffers yet: queued");
        assertTrue(p.give(frame(0x02, 61, 3)));
        assertEquals(0, entry(r, 0).control());
        post(r, 0, 2048);
        assertEquals(new Entry(0xc0, 8, 60, 0x1000), entry(r, 0), "delivered when a buffer comes");
        post(r, 1, 2048);
        assertEquals(new Entry(0xc0, 8, 61, 0x1001), entry(r, 1));
        // The queue is bounded.
        for (int i = 0; i < LLan.QUEUE_FRAMES; i++) {
            assertTrue(p.give(frame(0x02, 60, i)));
        }
        assertFalse(p.give(frame(0x02, 60, 0)), "queue full");
        assertEquals(2, n.droppedFrames());
        assertFalse(p.give(new byte[LLan.MAX_FRAME + 1]), "too long");
        // Freeing the device drops what waits.
        assertEquals(H.SUCCESS, r.hcall(H.FREE_LOGICAL_LAN, LAN0));
        open(r, 256);
        post(r, 0, 2048);
        assertEquals(0, entry(r, 0).control(), "nothing left from before");
    }

    @Test
    void aFrameNoBufferHoldsWaitsForTheGuestThenCountsAsDropped() {
        Peer p = new Peer();
        Rig r = bypass(p);
        LLan n = r.m.networks().get(0);
        n.holdNanos = 3_600_000_000_000L;
        open(r, 256);
        post(r, 0, 512);
        assertTrue(p.give(frame(0x02, 1514, 1)), "queued: a larger buffer may come");
        assertTrue(p.give(frame(0x02, 60, 2)), "queued behind it");
        assertEquals(0, entry(r, 0).control());
        post(r, 1, 2048); // the guest goes on posting: both are delivered, in order
        assertEquals(new Entry(0xc0, 8, 1514, 0x1001), entry(r, 0));
        assertEquals(new Entry(0xc0, 8, 60, 0x1000), entry(r, 1));

        // Once the guest has stopped posting, a frame that fits nothing is dropped and counted in
        // the last doubleword of the buffer list page, as QEMU does.
        post(r, 2, 512);
        assertTrue(p.give(frame(0x02, 4000, 3)));
        assertEquals(0, entry(r, 2).control());
        n.holdNanos = 0;
        r.m.pump();
        assertEquals(1, r.mem.read64be(BUF_LIST + 4088));
        assertTrue(p.give(frame(0x02, 60, 4)));
        assertEquals(new Entry(0xc0, 8, 60, 0x1002), entry(r, 2));
        post(r, 3, 512);
        assertTrue(p.give(frame(0x02, 9000, 5)));
        assertEquals(2, r.mem.read64be(BUF_LIST + 4088));
    }

    @Test
    void aFaultingBufferIsLost() {
        Peer p = new Peer();
        Rig r = rig(p);
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, LAN0, 0x0, BUF_LIST | 3));
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, LAN0, 0x1000, FILTER_LIST | 3));
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, LAN0, 0x2000, RXQ | 3));
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, LAN0, 0x3000, BUFS | 3));
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_LOGICAL_LAN, LAN0, 0x0, bd(0x2000, 0x1000), 0x1000));
        r.mem.write64be(BUFS, 0x77);
        assertEquals(H.SUCCESS, r.hcall(H.ADD_LOGICAL_LAN_BUFFER, LAN0, bd(0x3000, 2048)));
        assertEquals(H.SUCCESS, r.hcall(H.ADD_LOGICAL_LAN_BUFFER, LAN0, bd(0x3800, 2048)));
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, LAN0, 0x3000, 0)); // the guest unmaps them
        assertTrue(p.give(frame(0x02, 60, 1)));
        assertEquals(0, r.mem.read8(RXQ));
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, LAN0, 0x3000, BUFS | 3));
        assertTrue(p.give(frame(0x02, 60, 2)));
        assertEquals(0x77, r.mem.read64be(RXQ + 8), "the second buffer, after the lost one");
    }

    // ---- multicast filtering ------------------------------------------------------------------

    static final int BCAST = 0xff;

    @Test
    void multicastFiltering() {
        Peer p = new Peer();
        Rig r = bypass(p);
        open(r, 256);
        for (int i = 0; i < 20; i++) {
            post(r, i, 2048);
        }
        byte[] group1 = {0x01, 0x00, 0x5e, 0x00, 0x00, 0x01, 8, 0};
        byte[] group2 = {0x33, 0x33, 0x00, 0x00, 0x00, 0x01, (byte) 0x86, (byte) 0xdd};
        byte[] bcast = {-1, -1, -1, -1, -1, -1, 8, 6};
        byte[] other = {0x02, 0x00, 0x00, 0x00, 0x00, 0x09, 8, 0}; // unicast, another address
        int[] next = {0};
        Runnable none = () -> assertEquals(0, entry(r, next[0]).control(), "filtered");
        java.util.function.Consumer<byte[]> got = f -> {
            assertTrue(p.give(f));
            assertEquals(f.length, entry(r, next[0]).length());
            next[0]++;
        };
        // After registration: multicast reception on, filtering off (what QEMU delivers).
        got.accept(group1);
        got.accept(other);
        // Filtering on, one address in the table (Linux's flags; FreeBSD only adds and clears).
        assertEquals(H.SUCCESS, r.hcall(H.MULTICAST_CTRL, LAN0, LLan.MC_FILTER_MODIFY | LLan.MC_FILTER_ENABLE
                | LLan.MC_ADD, 0x01005e000001L));
        got.accept(group1);
        p.give(group2);
        none.run();
        got.accept(bcast);
        got.accept(other);
        assertEquals(H.SUCCESS, r.hcall(H.MULTICAST_CTRL, LAN0, LLan.MC_ADD, 0x333300000001L));
        got.accept(group2);
        assertEquals(H.SUCCESS, r.hcall(H.MULTICAST_CTRL, LAN0, LLan.MC_REMOVE, 0x01005e000001L));
        p.give(group1);
        none.run();
        // Reception off: no multicast at all, broadcast still.
        assertEquals(H.SUCCESS, r.hcall(H.MULTICAST_CTRL, LAN0, LLan.MC_RECV_MODIFY, 0));
        p.give(group2);
        none.run();
        got.accept(bcast);
        // Reception on, filtering off.
        assertEquals(H.SUCCESS, r.hcall(H.MULTICAST_CTRL, LAN0, LLan.MC_RECV_MODIFY | LLan.MC_RECV_ENABLE
                | LLan.MC_FILTER_MODIFY | LLan.MC_CLEAR, 0));
        got.accept(group1);
        // Filtering on with an empty table: nothing.
        assertEquals(H.SUCCESS, r.hcall(H.MULTICAST_CTRL, LAN0, LLan.MC_FILTER_MODIFY | LLan.MC_FILTER_ENABLE, 0));
        p.give(group1);
        none.run();
        // A table that overflows stops filtering until cleared.
        for (int i = 0; i <= LLan.MC_TABLE_MAX; i++) {
            assertEquals(H.SUCCESS, r.hcall(H.MULTICAST_CTRL, LAN0, LLan.MC_ADD, 0x01005e100000L + i));
        }
        got.accept(group1);
        assertEquals(H.SUCCESS, r.hcall(H.MULTICAST_CTRL, LAN0, LLan.MC_CLEAR, 0));
        p.give(group1);
        none.run();
        // Any VIO device is accepted, as in QEMU; a missing one is not.
        assertEquals(H.SUCCESS, r.hcall(H.MULTICAST_CTRL, VTY, LLan.MC_ADD, 0));
        assertEquals(H.PARAMETER, r.hcall(H.MULTICAST_CTRL, 0x71000099, LLan.MC_ADD, 0));
        // Freeing the device resets the filters.
        assertEquals(H.SUCCESS, r.hcall(H.FREE_LOGICAL_LAN, LAN0));
        open(r, 256);
        post(r, 0, 2048);
        next[0] = 0;
        got.accept(group1);
    }

    // ---- MAC address --------------------------------------------------------------------------

    @Test
    void changeMac() {
        Rig r = bypass(new Peer());
        LLan n = r.m.networks().get(0);
        assertEquals(H.PARAMETER, r.hcall(H.CHANGE_LOGICAL_LAN_MAC, VTY, 0x020000000001L));
        assertEquals(H.SUCCESS, r.hcall(H.CHANGE_LOGICAL_LAN_MAC, LAN0, 0xffff020000000001L), "works while closed");
        assertEquals("02:00:00:00:00:01", mac(n.macAddress()));
        assertEquals("52:54:00:12:34:56", mac(n.permanentMacAddress()));
        open(r, 16);
        assertEquals("02:00:00:00:00:01", mac(n.macAddress()), "registration keeps it (its MAC argument is ignored)");
        assertEquals(H.SUCCESS, r.hcall(H.FREE_LOGICAL_LAN, LAN0));
        assertEquals("52:54:00:12:34:56", mac(n.macAddress()), "freeing restores the permanent address");
    }

    // ---- sending ------------------------------------------------------------------------------

    @Test
    void sendGathersUpToSixBuffers() {
        Peer p = new Peer();
        Rig r = bypass(p);
        byte[] a = frame(0x02, 14, 1);
        byte[] b = frame(0x03, 100, 2);
        r.writeBytes(0x300000, a);
        r.writeBytes(0x300100, b);
        assertEquals(H.PARAMETER, r.hcall(H.SEND_LOGICAL_LAN, 0x71000099, bd(0x300000, 14)));
        assertEquals(H.PARAMETER, r.hcall(H.SEND_LOGICAL_LAN, VTY, bd(0x300000, 14)));
        assertEquals(H.DROPPED, r.hcall(H.SEND_LOGICAL_LAN, LAN0, bd(0x300000, 14)), "not registered");
        open(r, 16);
        assertEquals(H.SUCCESS, r.hcall(H.SEND_LOGICAL_LAN, LAN0, bd(0x300000, 14), bd(0x300100, 100), 0,
                bd(0x300000, 14)));
        assertEquals(1, p.sent().size());
        byte[] want = new byte[114];
        System.arraycopy(a, 0, want, 0, 14);
        System.arraycopy(b, 0, want, 14, 100);
        assertArrayEquals(want, p.sent().get(0), "up to the first invalid descriptor");
        long[] six = new long[8];
        for (int i = 0; i < 6; i++) {
            six[1 + i] = bd(0x300000, 14);
        }
        six[0] = LAN0;
        assertEquals(H.SUCCESS, r.hcall(H.SEND_LOGICAL_LAN, six));
        assertEquals(84, p.sent().get(1).length);
        assertEquals(H.SUCCESS, r.hcall(H.SEND_LOGICAL_LAN, LAN0, 0), "nothing to send");
        assertEquals(2, p.sent().size());
        six[7] = 1;
        assertEquals(H.HARDWARE, r.hcall(H.SEND_LOGICAL_LAN, six), "continuation token");
        assertEquals(H.RESOURCE, r.hcall(H.SEND_LOGICAL_LAN, LAN0, bd(0x300000, 0x8000), bd(0x300000, 0x8001)),
                "longer than 64 KiB");
        assertEquals(H.DEST_PARM, r.hcall(H.SEND_LOGICAL_LAN, LAN0, bd(0x300000, 14), bd(0x20000000L, 14)),
                "unreadable buffer");
        assertEquals(2, p.sent().size());
    }

    @Test
    void sendNeedsReadableTces() {
        Peer p = new Peer();
        Rig r = rig(p);
        LLan n = r.m.networks().get(0);
        n.tce().setBypass(true);
        open(r, 16);
        n.tce().setBypass(false);
        r.writeBytes(0x300000, frame(0x02, 60, 9));
        assertEquals(H.DEST_PARM, r.hcall(H.SEND_LOGICAL_LAN, LAN0, bd(0x5000, 60)));
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, LAN0, 0x5000, 0x300000 | 2)); // write-only
        assertEquals(H.DEST_PARM, r.hcall(H.SEND_LOGICAL_LAN, LAN0, bd(0x5000, 60)));
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, LAN0, 0x5000, 0x300000 | 1));
        assertEquals(H.SUCCESS, r.hcall(H.SEND_LOGICAL_LAN, LAN0, bd(0x5000, 60)));
        assertArrayEquals(frame(0x02, 60, 9), p.sent().get(0));
    }

    // ---- threading ----------------------------------------------------------------------------

    /**
     * Two "CPUs" on their own threads poll the receive queue and give buffers back, as a driver
     * does, while another thread delivers frames: every frame arrives once, in order, intact.
     */
    @Test
    void framesFromAnotherThreadWhileCpusRun() throws Exception {
        Peer p = new Peer();
        Rig r = new Rig(net(new MachineConfig().ram(256L << 20).cpus(2), p, null));
        r.m.networks().get(0).tce().setBypass(true);
        int entries = 64;
        assertEquals(H.SUCCESS, r.hcallOn(r.cpus[0], H.REGISTER_LOGICAL_LAN, LAN0, BUF_LIST, bd(RXQ, 16L * entries),
                FILTER_LIST));
        for (int i = 0; i < 32; i++) {
            long a = BUFS + 0x1000L * i;
            r.mem.write64be(a, a);
            assertEquals(H.SUCCESS, r.hcallOn(r.cpus[i % 2], H.ADD_LOGICAL_LAN_BUFFER, LAN0, bd(a, 0x800)));
        }
        int total = 20000;
        List<Integer> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Object guestLock = new Object(); // the driver's own lock around its queue position
        int[] slot = {0};
        int[] toggle = {1};
        List<Thread> cpus = new ArrayList<>();
        for (CpuState c : r.cpus) {
            cpus.add(Thread.ofPlatform().start(() -> {
                try {
                    while (seen.size() < total && failure.get() == null) {
                        long handle;
                        int len;
                        synchronized (guestLock) {
                            long e = RXQ + 16L * slot[0];
                            int control = r.mem.read8(e);
                            if ((control >>> 7) != toggle[0] || (control & 0x40) == 0) {
                                continue;
                            }
                            len = r.mem.read32be(e + 4);
                            handle = r.mem.read64be(e + 8);
                            byte[] f = r.readBytes(handle + 8, len);
                            int n = ((f[6] & 0xff) << 24) | ((f[7] & 0xff) << 16) | ((f[8] & 0xff) << 8) | (f[9] & 0xff);
                            for (int k = 10; k < len; k++) {
                                if (f[k] != (byte) n) {
                                    throw new AssertionError("frame " + n + " corrupt at " + k);
                                }
                            }
                            seen.add(n);
                            if (++slot[0] == entries) {
                                slot[0] = 0;
                                toggle[0] ^= 1;
                            }
                        }
                        long rc = r.hcallOn(c, H.ADD_LOGICAL_LAN_BUFFER, LAN0, bd(handle, 0x800));
                        if (rc != H.SUCCESS) {
                            throw new AssertionError("add buffer: " + rc);
                        }
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            }));
        }
        Thread sender = Thread.ofPlatform().start(() -> {
            try {
                for (int n = 0; n < total && failure.get() == null; ) {
                    byte[] f = new byte[60 + n % 1400];
                    f[0] = 0x02;
                    f[6] = (byte) (n >>> 24);
                    f[7] = (byte) (n >>> 16);
                    f[8] = (byte) (n >>> 8);
                    f[9] = (byte) n;
                    java.util.Arrays.fill(f, 10, f.length, (byte) n);
                    if (p.give(f)) {
                        n++;
                    } else {
                        Thread.onSpinWait(); // queue full: back off and retry
                    }
                }
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        });
        sender.join(60_000);
        for (Thread t : cpus) {
            t.join(60_000);
        }
        if (failure.get() != null) {
            throw new AssertionError(failure.get());
        }
        assertEquals(total, seen.size());
        for (int i = 0; i < total; i++) {
            assertEquals(i, seen.get(i));
        }
    }

    /** A backend that blocks in transmit holds up neither receiving nor the receive-side hypercalls. */
    @Test
    void aSlowBackendDoesNotBlockReceiving() throws Exception {
        CountDownLatch inTransmit = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Peer p = new Peer() {
            @Override
            public void transmit(byte[] frame) {
                inTransmit.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        Rig r = new Rig(net(new MachineConfig().ram(256L << 20).cpus(2), p, null));
        r.m.networks().get(0).tce().setBypass(true);
        assertEquals(H.SUCCESS, r.hcallOn(r.cpus[0], H.REGISTER_LOGICAL_LAN, LAN0, BUF_LIST, bd(RXQ, 0x1000),
                FILTER_LIST));
        r.writeBytes(0x300000, frame(0x02, 60, 1));
        Thread cpu0 = Thread.ofPlatform().start(() -> r.hcallOn(r.cpus[0], H.SEND_LOGICAL_LAN, LAN0, bd(0x300000, 60)));
        assertTrue(inTransmit.await(10, TimeUnit.SECONDS));
        // CPU 1 posts a buffer, the backend's thread delivers a frame into it.
        r.mem.write64be(BUFS, 0x4242);
        assertEquals(H.SUCCESS, r.hcallOn(r.cpus[1], H.ADD_LOGICAL_LAN_BUFFER, LAN0, bd(BUFS, 2048)));
        Thread rx = Thread.ofPlatform().start(() -> assertTrue(p.give(frame(0x02, 70, 2))));
        rx.join(10_000);
        assertFalse(rx.isAlive(), "receive returned while transmit blocks");
        assertEquals(0x4242, r.mem.read64be(RXQ + 8));
        release.countDown();
        cpu0.join(10_000);
        assertEquals(H.SUCCESS, r.cpus[0].gpr[3]);
    }

    // ---- reset, quiesce, close ----------------------------------------------------------------

    @Test
    void resetClosesTheDevice() {
        Peer p = new Peer();
        Rig r = bypass(p);
        open(r, 16);
        post(r, 0, 2048);
        r.hcall(H.CHANGE_LOGICAL_LAN_MAC, LAN0, 0x020000000001L);
        r.m.reset();
        LLan n = r.m.networks().get(0);
        assertFalse(n.isOpen());
        assertEquals("52:54:00:12:34:56", mac(n.macAddress()));
        assertFalse(p.give(frame(0x02, 60, 1)));
        assertEquals(H.RESOURCE, r.hcall(H.FREE_LOGICAL_LAN, LAN0));
        assertFalse(n.tce().bypass(), "the bus reset leaves TCE bypass");
    }

    @Test
    void quiesceClearsTheTcesButKeepsTheRegistration() {
        Peer p = new Peer();
        Rig r = rig(p);
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, LAN0, 0x0, BUF_LIST | 3));
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, LAN0, 0x1000, FILTER_LIST | 3));
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, LAN0, 0x2000, RXQ | 3));
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_LOGICAL_LAN, LAN0, 0x0, bd(0x2000, 0x1000), 0x1000));
        assertArrayEquals(new int[] {0}, r.rtas("quiesce", 1));
        assertTrue(r.m.networks().get(0).isOpen(), "as in QEMU, quiesce resets only the TCEs (and CRQs)");
        assertEquals(H.SUCCESS, r.hcall(H.GET_TCE, LAN0, 0x0));
        assertEquals(0, r.r(4));
    }

    @Test
    void closeClosesTheBackendAndDropsLaterFrames() throws IOException {
        Peer p = new Peer();
        Rig r = bypass(p);
        open(r, 16);
        r.m.close();
        assertTrue(p.closed);
        assertFalse(p.give(frame(0x02, 60, 1)));
    }

    // ---- saved state --------------------------------------------------------------------------

    static byte[] save(Rig r) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        StateOutput out = new StateOutput(bo);
        r.m.saveState(out);
        r.mem.saveState(out);
        out.finish();
        return bo.toByteArray();
    }

    static void load(Rig r, byte[] b) throws IOException {
        StateInput in = new StateInput(new ByteArrayInputStream(b));
        r.m.loadState(in);
        r.mem.loadState(in);
        in.finish();
    }

    @Test
    void stateRoundTrips() throws IOException {
        Peer pa = new Peer();
        Rig a = bypass(pa);
        open(a, 4);
        for (int i = 0; i < 5; i++) {
            post(a, i, i < 3 ? 512 : 2048);
        }
        a.hcall(H.VIO_SIGNAL, LAN0, 1);
        a.hcall(H.CHANGE_LOGICAL_LAN_MAC, LAN0, 0x020000000042L);
        a.hcall(H.MULTICAST_CTRL, LAN0, LLan.MC_FILTER_MODIFY | LLan.MC_FILTER_ENABLE | LLan.MC_ADD, 0x01005e000001L);
        for (int i = 0; i < 5; i++) {
            pa.give(frame(0x02, 100 + i, i)); // uses every buffer, wraps the 4-entry queue once
        }
        assertEquals(new Entry(0x40, 8, 104, 0x1003), entry(a, 0));
        assertTrue(pa.give(frame(0x02, 1500, 9)), "waits for a buffer");
        byte[] state = save(a);
        assertFalse(pa.give(frame(0x02, 60, 1)), "nothing is delivered while the machine is saved");

        Peer pb = new Peer();
        Rig b = bypass(pb);
        load(b, state);
        assertArrayEquals(state, save(b), "saved again, the state is unchanged");
        for (Rig r : new Rig[] {a, b}) {
            r.m.cpuExecEnter(r.cpu); // the guest runs again
        }
        for (Rig r : new Rig[] {a, b}) {
            Peer p = r == a ? pa : pb;
            LLan n = r.m.networks().get(0);
            assertTrue(n.isOpen());
            assertEquals("02:00:00:00:00:42", mac(n.macAddress()));
            post(r, 6, 2048);
            assertEquals(new Entry(0xc0, 8, 101, 0x1001), entry(r, 1), "the waiting frame was dropped by the save");
            assertTrue(p.give(frame(0x33, 60, 7)), "a multicast frame the filter drops");
            assertTrue(p.give(frame(0x02, 200, 8)));
            assertEquals(new Entry(0x40, 8, 200, 0x1006), entry(r, 1), "next slot, second pass");
        }
        assertArrayEquals(save(a), save(b), "both behaved the same");
    }

    @Test
    void theHardwareCheckComparesNetworkDevices() throws IOException {
        byte[] none = save(rig());
        byte[] one = save(rig(new Peer()));
        Rig other = new Rig(net(new MachineConfig().ram(256L << 20), new Peer(), "02:00:00:00:00:07"));
        StateMismatchException e = assertThrows(StateMismatchException.class, () -> load(other, one));
        assertTrue(e.getMessage().contains("VIO device l-lan@71000003: identity: saved \"mac=52:54:00:12:34:56\","
                + " this machine \"mac=02:00:00:00:00:07\""), e.getMessage());
        e = assertThrows(StateMismatchException.class, () -> load(rig(), one));
        assertTrue(e.getMessage().contains("added VIO devices: saved 1, this machine 0"), e.getMessage());
        e = assertThrows(StateMismatchException.class, () -> load(rig(new Peer(), new Peer()), none));
        assertTrue(e.getMessage().contains("added VIO devices: saved 0, this machine 2"), e.getMessage());
        Rig ok = rig(new Peer());
        load(ok, one);
        // A machine without network devices saves what it saved before they existed.
        Rig plain = rig();
        load(plain, none);
    }

    @Test
    void aMismatchedDiskCountIsReportedWithoutTheNetworkDevices() throws IOException {
        MachineConfig c = new MachineConfig().ram(256L << 20);
        c.addDisk(ScsiDisk.Type.DISK, new MemoryBackend(1 << 20));
        c.addNetwork(new Peer());
        byte[] state = save(new Rig(c));
        StateMismatchException e = assertThrows(StateMismatchException.class, () -> load(rig(new Peer()), state));
        assertTrue(e.getMessage().contains("SCSI devices: saved 1, this machine 0"), e.getMessage());
        assertFalse(e.getMessage().contains("VIO"), e.getMessage());
    }
}
