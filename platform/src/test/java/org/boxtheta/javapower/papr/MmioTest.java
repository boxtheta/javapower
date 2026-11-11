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
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.boxtheta.javapower.core.MmioHandler;
import org.boxtheta.javapower.core.MmioRegion;
import org.boxtheta.javapower.core.StateFormatException;
import org.boxtheta.javapower.core.StateMismatchException;
import org.junit.jupiter.api.Test;

/**
 * Embedder MMIO regions on the machine: configuration checks, the device tree node, the
 * interrupt, H_LOGICAL_CI_LOAD/STORE, reset and the saved state.
 */
class MmioTest {
    static final long BASE = 0x1000_0000_0000L;

    /** Eight bytes of registers, big-endian values; logs accesses; saves its registers. */
    static class Regs implements MmioHandler {
        long value = 0x0102030405060708L;
        final List<String> log = new ArrayList<>();
        int resets;
        boolean busError;
        /** Extra bytes saveState writes (to test the load's checks). */
        int junk;

        @Override
        public long read(long offset, int size) {
            if (busError) {
                throw new BusError("no");
            }
            log.add("r " + offset + " " + size);
            return value >>> (8 * (8 - size - offset));
        }

        @Override
        public void write(long offset, int size, long v) {
            if (busError) {
                throw new BusError("no");
            }
            log.add("w " + offset + " " + size + " " + Long.toHexString(v));
            int sh = 8 * (8 - size - (int) offset);
            long mask = (size == 8 ? -1L : (1L << (8 * size)) - 1) << sh;
            value = (value & ~mask) | ((v << sh) & mask);
        }

        @Override
        public void reset() {
            resets++;
            value = 0x0102030405060708L;
        }

        @Override
        public void saveState(DataOutput out) throws IOException {
            out.writeLong(value);
            for (int i = 0; i < junk; i++) {
                out.writeByte(0);
            }
        }

        @Override
        public void loadState(DataInput in) throws IOException {
            value = in.readLong();
        }
    }

    static MachineConfig cfg() {
        return new MachineConfig().ram(256L << 20);
    }

    @Test
    void configurationIsChecked() throws IOException {
        MachineConfig c = cfg();
        Regs h = new Regs();
        c.addMmio("dev", BASE, 0x1000, h);
        String m = assertThrows(IllegalArgumentException.class, () -> c.addMmio("dev", BASE + 0x1000, 0x10, h))
                .getMessage();
        assertTrue(m.contains("already an MMIO region named dev"), m);
        m = assertThrows(IllegalArgumentException.class, () -> c.addMmio("other", BASE + 0xff8, 0x10, h)).getMessage();
        assertTrue(m.contains("overlaps MMIO region dev"), m);
        m = assertThrows(IllegalArgumentException.class, () -> c.addMmio("pci", (1L << 46) - 8, 0x10, h)).getMessage();
        assertTrue(m.contains("PCI host bridge windows"), m);
        assertThrows(IllegalArgumentException.class, () -> c.addMmio("1st", BASE + 0x2000, 0x10, h));
        assertThrows(IllegalArgumentException.class, () -> c.addMmio("a/b", BASE + 0x2000, 0x10, h));
        assertThrows(IllegalArgumentException.class, () -> c.addMmio("a".repeat(32), BASE, 1, h));
        assertThrows(IllegalArgumentException.class, () -> c.addMmio("empty", BASE + 0x2000, 0, h));
        assertThrows(IllegalArgumentException.class, () -> c.addMmio("high", MachineConfig.MMIO_LIMIT - 8, 0x10, h));
        assertThrows(IllegalArgumentException.class, () -> c.addMmio("neg", -0x1000, 0x10, h));
        assertThrows(IllegalArgumentException.class, () -> c.addMmio("ok", BASE + 0x2000, 0x10, h).compatible());

        // RAM is checked when the machine is built (the size may change after addMmio).
        MachineConfig low = cfg();
        low.addMmio("low", 0x0f00_0000L, 0x1000, h);
        m = assertThrows(IllegalArgumentException.class, () -> new Rig(low)).getMessage();
        assertTrue(m.contains("overlaps guest RAM"), m);
        low.ram(64L << 20);
        new Rig(low).m.close();
    }

    @Test
    void withoutRegionsTheMachineIsUnchanged() throws IOException {
        Rig a = new Rig(cfg());
        Rig b = new Rig(cfg());
        b.m.config().addMmio("late", BASE, 0x1000, new Regs()); // after construction: ignored
        assertArrayEquals(a.m.fdtBlob(), b.m.fdtBlob());
        assertTrue(a.m.mmio().isEmpty());
        assertTrue(b.m.mmio().isEmpty());
        assertArrayEquals(MachineStateTest.save(a.m), MachineStateTest.save(b.m));
    }

    @Test
    void deviceTreeNodes() {
        MachineConfig c = cfg();
        c.addMmio("hidden", BASE, 0x100, new Regs());
        c.addMmio("serial", BASE + 0x1000, 0x100, new Regs()).compatible("acme,uart", "ns16550a").interrupt(true);
        c.addMmio("net", BASE + 0x2000, 0x2000, new Regs()).byteOrder(ByteOrder.LITTLE_ENDIAN).interrupt(false)
                .deviceTree(n -> n.setCell("clock-frequency", 1843200).setString("compatible", "acme,net"));
        Rig r = new Rig(c);
        Fdt.Node root = Fdt.parse(r.m.fdtBlob());
        assertNull(root.child("hidden@100000000000"));
        Fdt.Node s = root.child("serial@100000001000");
        assertArrayEquals(Fdt.cells(0x1000, 0x1000, 0, 0x100), s.get("reg"));
        assertEquals("acme,uart", s.getString("compatible"));
        assertArrayEquals(Fdt.cells(Xics.IRQ_MMIO, 1), s.get("interrupts"));
        assertEquals(DeviceTree.PHANDLE_INTC, s.getCell("interrupt-parent", 0));
        Fdt.Node n = root.child("net@100000002000");
        assertArrayEquals(Fdt.cells(Xics.IRQ_MMIO + 1, 0), n.get("interrupts"));
        assertEquals(1843200, n.getCell("clock-frequency", 0));
        assertEquals("acme,net", n.getString("compatible"));
        // The nodes come after everything the machine had before.
        List<Fdt.Node> kids = root.children();
        assertEquals("interrupt-controller", kids.get(kids.size() - 3).name());
        assertEquals(Xics.IRQ_MMIO, r.m.mmioInterrupt("serial"));
        assertEquals(Xics.IRQ_MMIO + 1, r.m.mmioInterrupt("net"));
        assertThrows(IllegalArgumentException.class, () -> r.m.mmioInterrupt("hidden"));
        assertThrows(IllegalArgumentException.class, () -> r.m.mmioInterrupt("nothing"));
        assertTrue(r.m.xics().isLsi(Xics.IRQ_MMIO));
        assertFalse(r.m.xics().isLsi(Xics.IRQ_MMIO + 1));
        List<MmioRegion> regions = r.m.mmio().regions();
        assertEquals(3, regions.size());
        assertEquals(ByteOrder.LITTLE_ENDIAN, regions.get(2).order());
    }

    @Test
    void interruptReachesTheCpuFromAnotherThread() throws Exception {
        MachineConfig c = cfg();
        c.addMmio("dev", BASE, 0x100, new Regs()).compatible("acme,dev").interrupt(true);
        Rig r = new Rig(c);
        int irq = r.m.mmioInterrupt("dev");
        assertArrayEquals(new int[] {0}, r.rtas("ibm,set-xive", 1, irq, 0, 5));
        assertEquals(H.SUCCESS, r.hcall(H.CPPR, 0xff));
        assertFalse(r.cpu.extIrq);
        Thread t = new Thread(() -> r.m.xics().setIrq(irq, true));
        t.start();
        t.join();
        assertTrue(r.cpu.extIrq);
        assertEquals(H.SUCCESS, r.hcall(H.XIRR));
        assertEquals((0xffL << 24) | irq, r.r(4) & 0xffffffffL);
        assertFalse(r.cpu.extIrq);
        // Level-triggered: still asserted after EOI, so presented again; lowered, it is not.
        assertEquals(H.SUCCESS, r.hcall(H.EOI, (0xffL << 24) | irq));
        assertTrue(r.cpu.extIrq);
        r.m.xics().setIrq(irq, false);
        assertEquals(H.SUCCESS, r.hcall(H.XIRR));
        assertEquals(H.SUCCESS, r.hcall(H.EOI, (0xffL << 24) | irq));
        assertFalse(r.cpu.extIrq);
    }

    @Test
    void logicalLoadsAndStoresReachTheRegion() {
        MachineConfig c = cfg();
        Regs be = new Regs();
        Regs le = new Regs();
        c.addMmio("be", BASE, 8, be);
        c.addMmio("le", BASE + 0x10, 8, le).byteOrder(ByteOrder.LITTLE_ENDIAN);
        Rig r = new Rig(c);
        assertEquals(H.SUCCESS, r.hcall(H.LOGICAL_CI_LOAD, 4, BASE + 4));
        assertEquals(0x05060708L, r.r(4));
        assertEquals(H.SUCCESS, r.hcall(H.LOGICAL_CACHE_LOAD, 8, BASE));
        assertEquals(0x0102030405060708L, r.r(4));
        assertEquals(H.SUCCESS, r.hcall(H.LOGICAL_CI_LOAD, 1, BASE + 1));
        assertEquals(2, r.r(4));
        assertEquals(H.SUCCESS, r.hcall(H.LOGICAL_CI_STORE, 2, BASE + 2, 0xbeef));
        assertEquals(0x0102beef05060708L, be.value);
        // The hypercalls are big-endian: a little-endian region sees the bytes reversed.
        assertEquals(H.SUCCESS, r.hcall(H.LOGICAL_CI_LOAD, 2, BASE + 0x10));
        assertEquals(0x0201L, r.r(4));
        assertEquals(H.SUCCESS, r.hcall(H.LOGICAL_CI_STORE, 4, BASE + 0x14, 0x11223344));
        assertEquals("w 4 4 44332211", le.log.get(le.log.size() - 1));
        // Unaligned, leaving the region, or a bus error: as unassigned memory (0, store dropped).
        int n = be.log.size();
        assertEquals(H.SUCCESS, r.hcall(H.LOGICAL_CI_LOAD, 4, BASE + 2));
        assertEquals(0, r.r(4));
        assertEquals(H.SUCCESS, r.hcall(H.LOGICAL_CI_LOAD, 8, BASE + 8));
        assertEquals(0, r.r(4));
        be.busError = true;
        assertEquals(H.SUCCESS, r.hcall(H.LOGICAL_CI_LOAD, 4, BASE));
        assertEquals(0, r.r(4));
        assertEquals(H.SUCCESS, r.hcall(H.LOGICAL_CI_STORE, 4, BASE, 1));
        assertEquals(n, be.log.size());
        assertEquals(H.PARAMETER, r.hcall(H.LOGICAL_CI_LOAD, 3, BASE));
    }

    @Test
    void resetResetsTheDevices() {
        MachineConfig c = cfg();
        Regs h = new Regs();
        c.addMmio("dev", BASE, 8, h);
        Rig r = new Rig(c);
        assertEquals(1, h.resets);
        r.hcall(H.LOGICAL_CI_STORE, 8, BASE, 42);
        r.m.reset();
        assertEquals(2, h.resets);
        assertEquals(0x0102030405060708L, h.value);
    }

    static MachineConfig withRegion(Regs h, String name, long base, boolean irq) {
        MachineConfig c = cfg();
        MachineConfig.Mmio m = c.addMmio(name, base, 8, h);
        if (irq) {
            m.interrupt(true);
        }
        return c;
    }

    @Test
    void handlerStateIsSavedAndLoaded() throws IOException {
        Regs a = new Regs();
        Rig ra = new Rig(withRegion(a, "dev", BASE, true));
        ra.hcall(H.LOGICAL_CI_STORE, 8, BASE, 0x1122334455667788L);
        ra.m.xics().setIrq(Xics.IRQ_MMIO, true);
        byte[] state = MachineStateTest.save(ra.m);

        Regs b = new Regs();
        Rig rb = new Rig(withRegion(b, "dev", BASE, true));
        MachineStateTest.load(rb.m, state);
        assertEquals(0x1122334455667788L, b.value);
        assertArrayEquals(state, MachineStateTest.save(rb.m));

        // A handler that does not read all it wrote, or reads more.
        a.junk = 3;
        byte[] longer = MachineStateTest.save(ra.m);
        String m = assertThrows(StateFormatException.class, () -> MachineStateTest.load(rb.m, longer)).getMessage();
        assertTrue(m.contains("3 bytes of its state left unread"), m);
        Regs c = new Regs() {
            @Override
            public void loadState(DataInput in) throws IOException {
                in.readLong();
                in.readLong();
            }
        };
        Rig rc = new Rig(withRegion(c, "dev", BASE, true));
        m = assertThrows(StateFormatException.class, () -> MachineStateTest.load(rc.m, state)).getMessage();
        assertTrue(m.contains("ends early"), m);
    }

    @Test
    void otherRegionsAreOtherHardware() throws IOException {
        byte[] state = MachineStateTest.save(new Rig(withRegion(new Regs(), "dev", BASE, true)).m);
        byte[] none = MachineStateTest.save(new Rig(cfg()).m);
        String[][] cases = {
            {"other", "MMIO region 0 (other): name: saved dev, this machine other"},
            {"moved", "base: saved 0x100000000000, this machine 0x100000001000"},
            {"noirq", "interrupt: saved 0x1f00, this machine 0x0"},
            {"none", "MMIO regions: saved 1, this machine 0"},
        };
        for (String[] k : cases) {
            MachineConfig c = switch (k[0]) {
                case "other" -> withRegion(new Regs(), "other", BASE, true);
                case "moved" -> withRegion(new Regs(), "dev", BASE + 0x1000, true);
                case "noirq" -> withRegion(new Regs(), "dev", BASE, false);
                default -> cfg();
            };
            Rig r = new Rig(c);
            String m = assertThrows(StateMismatchException.class, () -> MachineStateTest.load(r.m, state))
                    .getMessage();
            assertTrue(m.contains(k[1]), k[1] + " in: " + m);
        }
        Rig r = new Rig(withRegion(new Regs(), "dev", BASE, true));
        String m = assertThrows(StateMismatchException.class, () -> MachineStateTest.load(r.m, none)).getMessage();
        assertTrue(m.contains("MMIO regions: saved 0, this machine 1"), m);
    }
}
