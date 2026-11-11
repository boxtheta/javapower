package org.boxtheta.javapower.papr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.boxtheta.javapower.core.MmioHandler;
import org.boxtheta.javapower.core.MmioRegion;
import org.boxtheta.javapower.core.StateMismatchException;
import org.junit.jupiter.api.Test;

/**
 * PCI devices on PHB 0: configuration space through RTAS, BAR sizing and mapping into the
 * bridge's windows, the INTx path to the LSIs, DMA through the TCE table, the device tree, reset
 * and the saved state with its hardware check.
 */
class PciTest {
    static final long BUID = DeviceTree.PHB0_BUID;
    static final long MEM32_CPU = Phb.IO_WIN + Phb.MEM32_PCI;

    /** Registers that read back what was written, little-endian; logs accesses. */
    static final class Regs implements MmioHandler {
        final byte[] b;
        final List<String> log = Collections.synchronizedList(new ArrayList<>());

        Regs(int size) {
            b = new byte[size];
        }

        @Override
        public synchronized long read(long offset, int size) {
            long v = 0;
            for (int k = size - 1; k >= 0; k--) {
                v = (v << 8) | (b[(int) offset + k] & 0xff);
            }
            log.add("r " + offset + " " + size);
            return v;
        }

        @Override
        public synchronized void write(long offset, int size, long value) {
            log.add("w " + offset + " " + size + " " + Long.toHexString(value));
            for (int k = 0; k < size; k++, value >>>= 8) {
                b[(int) offset + k] = (byte) value;
            }
        }
    }

    /** A device with a 32-bit memory BAR 0, a 64-bit prefetchable BAR 2, an I/O BAR 4 and a capability. */
    static final class Dev extends PciDevice {
        final Regs bar0 = new Regs(0x1000);
        final Regs bar2 = new Regs(0x10000);
        final Regs bar4 = new Regs(0x20);
        final int cap;
        int resets;
        int saved = 42;
        String hw = "";
        final List<String> writes = new ArrayList<>();

        Dev() {
            this(0x1234);
        }

        Dev(int deviceId) {
            super("test-dev", 0x1b36, deviceId, 2, 0x078000);
            setSubsystem(0x1af4, 0x1100);
            setInterruptPin(1);
            addMemoryBar(0, 0x1000, false, false, bar0);
            addMemoryBar(2, 0x10000, true, true, bar2);
            addIoBar(4, 0x20, bar4);
            cap = addCapability(0x09, 8);
            setConfig16(cap + 2, 0xabcd);
            setWriteMask(cap + 4, 4, 0x0000ffff);
        }

        @Override
        protected void configWritten(int offset, int size) {
            writes.add(offset + "/" + size);
        }

        @Override
        protected void reset() {
            resets++;
        }

        @Override
        protected void saveState(DataOutput out) throws IOException {
            out.writeInt(saved);
        }

        @Override
        protected void loadState(DataInput in) throws IOException {
            saved = in.readInt();
        }

        @Override
        protected String hardwareDescription() {
            return hw;
        }
    }

    static MachineConfig cfg(PciDevice... devs) {
        MachineConfig c = new MachineConfig().ram(256L << 20);
        for (PciDevice d : devs) {
            c.addPciDevice(d);
        }
        return c;
    }

    static int addr(int slot, int reg) {
        return (slot << 11) | reg;
    }

    /** ibm,read-pci-config; returns {status, value}. */
    static int[] read(Rig r, int slot, int reg, int size) {
        return r.rtas("ibm,read-pci-config", 2, addr(slot, reg), (int) (BUID >>> 32), (int) BUID, size);
    }

    static int cfg(Rig r, int slot, int reg, int size) {
        int[] v = read(r, slot, reg, size);
        assertEquals(0, v[0], "read status");
        return v[1];
    }

    static int write(Rig r, int slot, int reg, int size, int value) {
        return r.rtas("ibm,write-pci-config", 1, addr(slot, reg), (int) (BUID >>> 32), (int) BUID, size, value)[0];
    }

    static void put(Rig r, int slot, int reg, int size, int value) {
        assertEquals(0, write(r, slot, reg, size, value), "write status");
    }

    /** A little-endian CPU load from real address {@code a}, or null if nothing is mapped there. */
    static Long load(Rig r, long a, int size) {
        MmioRegion reg = r.m.mmio().regionFor(a, size);
        return reg == null ? null : reg.read(a - reg.base(), size, ByteOrder.LITTLE_ENDIAN);
    }

    static void store(Rig r, long a, int size, long v) {
        MmioRegion reg = r.m.mmio().regionFor(a, size);
        assertNotNull(reg, String.format("no region at 0x%x", a));
        reg.write(a - reg.base(), size, v, ByteOrder.LITTLE_ENDIAN);
    }

    @Test
    void configurationSpaceThroughRtas() {
        Dev d = new Dev();
        Rig r = new Rig(cfg(d));
        assertEquals(0, d.slot());
        assertEquals(0x12341b36, cfg(r, 0, 0, 4));
        assertEquals(0x1b36, cfg(r, 0, 0, 2));
        assertEquals(0x12, cfg(r, 0, 3, 1));
        assertEquals(0x07800002, cfg(r, 0, 8, 4));
        assertEquals(0x11001af4, cfg(r, 0, 0x2c, 4));
        assertEquals(0x0100, cfg(r, 0, 0x3c, 2));
        // Status has the capability list; the list starts at the capability.
        assertEquals(PciDevice.STATUS_CAP_LIST, cfg(r, 0, 6, 2));
        assertEquals(0x40, d.cap);
        assertEquals(0x40, cfg(r, 0, 0x34, 1));
        assertEquals(0xabcd0009, cfg(r, 0, 0x40, 4));
        // Ids and class are read-only; command and interrupt line are writable.
        put(r, 0, 0, 4, 0xffffffff);
        assertEquals(0x12341b36, cfg(r, 0, 0, 4));
        put(r, 0, 4, 2, 0xffff);
        assertEquals(0x0507, cfg(r, 0, 4, 2));
        put(r, 0, 0x3c, 1, 0x5a);
        assertEquals(0x5a, cfg(r, 0, 0x3c, 1));
        // Capability bytes: read-only unless the device made them writable.
        put(r, 0, 0x40, 4, 0xffffffff);
        assertEquals(0xabcd0009, cfg(r, 0, 0x40, 4));
        put(r, 0, 0x44, 4, 0x12345678);
        assertEquals(0x5678, cfg(r, 0, 0x44, 4));
        // Free bytes after the header are plain storage.
        put(r, 0, 0xf0, 4, 0xcafef00d);
        assertEquals(0xcafef00d, cfg(r, 0, 0xf0, 4));
        assertTrue(d.writes.contains("68/4"), d.writes.toString());
        // Missing devices, other functions, misaligned, out of range, bad sizes: hardware errors.
        assertEquals(-1, read(r, 1, 0, 4)[0]);
        assertEquals(-1, r.rtas("ibm,read-pci-config", 2, (1 << 8), (int) (BUID >>> 32), (int) BUID, 4)[0]);
        assertEquals(-1, r.rtas("ibm,read-pci-config", 2, (1 << 16), (int) (BUID >>> 32), (int) BUID, 4)[0]);
        assertEquals(-1, read(r, 0, 2, 4)[0]);
        assertEquals(-1, read(r, 0, 0, 3)[0]);
        assertEquals(-1, read(r, 0, 0x100 | (1 << 28), 4)[0]);
        assertEquals(-1, r.rtas("ibm,read-pci-config", 2, 0, 0, 0x1234, 4)[0]);
        assertEquals(-1, write(r, 1, 0, 4, 0));
        // The forms without a BUID name no bridge, as in QEMU.
        assertEquals(-1, r.rtas("read-pci-config", 2, 0, 4)[0]);
        assertEquals(-1, r.rtas("write-pci-config", 1, 0, 4, 0)[0]);
        assertEquals(-1, r.rtas("ibm,read-pci-config", 1, 0, 0, (int) BUID, 4)[0]);
    }

    @Test
    void barSizingAndMapping() {
        Dev d = new Dev();
        Rig r = new Rig(cfg(d));
        // Sizing: all ones read back as the size mask with the type bits.
        put(r, 0, 0x10, 4, -1);
        assertEquals(0xfffff000, cfg(r, 0, 0x10, 4));
        put(r, 0, 0x18, 4, -1);
        put(r, 0, 0x1c, 4, -1);
        assertEquals(0xffff000c, cfg(r, 0, 0x18, 4));
        assertEquals(0xffffffff, cfg(r, 0, 0x1c, 4));
        put(r, 0, 0x20, 4, -1);
        assertEquals(0xffffffe1, cfg(r, 0, 0x20, 4));
        assertEquals(0, cfg(r, 0, 0x14, 4)); // no BAR 1 (the upper half is BAR 2's)
        assertEquals(0, cfg(r, 0, 0x24, 4));
        assertEquals(0, cfg(r, 0, 0x30, 4)); // no expansion ROM
        // Nothing is mapped while decoding is off.
        put(r, 0, 0x10, 4, 0x80001000);
        put(r, 0, 0x18, 4, 0x00000000);
        put(r, 0, 0x1c, 4, 0x2100);
        put(r, 0, 0x20, 4, 0x1000);
        assertEquals(-1, d.barAddress(0));
        assertNull(load(r, MEM32_CPU + 0x1000, 4));
        put(r, 0, 4, 2, PciDevice.COMMAND_MEMORY | PciDevice.COMMAND_IO);
        assertEquals(MEM32_CPU + 0x1000, d.barAddress(0));
        assertEquals(0x2100_0000_0000L, d.barAddress(2));
        assertEquals(Phb.IO_WIN + 0x1000, d.barAddress(4));
        store(r, MEM32_CPU + 0x1004, 4, 0x11223344);
        assertEquals(0x11223344L, load(r, MEM32_CPU + 0x1004, 4));
        assertEquals(List.of("w 4 4 11223344", "r 4 4"), d.bar0.log);
        store(r, 0x2100_0000_0008L, 8, 0x0102030405060708L);
        assertEquals(0x0102030405060708L, load(r, 0x2100_0000_0008L, 8));
        store(r, Phb.IO_WIN + 0x1002, 2, 0xbeef);
        assertEquals(0xbeefL, load(r, Phb.IO_WIN + 0x1002, 2));
        // Little-endian: the bytes of a big-endian load are reversed.
        MmioRegion reg = r.m.mmio().regionFor(MEM32_CPU + 0x1004, 4);
        assertEquals(0x44332211L, reg.read(4, 4, ByteOrder.BIG_ENDIAN));
        // H_LOGICAL_CI_LOAD (big-endian) reaches the BAR too.
        assertEquals(H.SUCCESS, r.hcall(H.LOGICAL_CI_LOAD, 4, MEM32_CPU + 0x1004));
        assertEquals(0x44332211L, r.r(4));
        // Moving a BAR unmaps the old range; disabling decoding unmaps everything.
        put(r, 0, 0x10, 4, 0x90000000);
        assertNull(load(r, MEM32_CPU + 0x1004, 4));
        assertEquals(0x11223344L, load(r, Phb.IO_WIN + 0x90000004L, 4));
        put(r, 0, 4, 2, PciDevice.COMMAND_IO);
        assertNull(load(r, Phb.IO_WIN + 0x90000004L, 4));
        assertNull(load(r, 0x2100_0000_0008L, 8));
        assertEquals(0xbeefL, load(r, Phb.IO_WIN + 0x1002, 2));
        // Outside the windows: below 2 GiB, or a 64-bit BAR below 33 TiB and above 4 GiB.
        put(r, 0, 0x10, 4, 0x10000000);
        put(r, 0, 0x1c, 4, 0x10);
        put(r, 0, 4, 2, PciDevice.COMMAND_MEMORY);
        assertEquals(-1, d.barAddress(0));
        assertEquals(-1, d.barAddress(2));
        // A 64-bit BAR inside the 32-bit window works there.
        put(r, 0, 0x18, 4, 0xa0000000);
        put(r, 0, 0x1c, 4, 0);
        assertEquals(Phb.IO_WIN + 0xa0000000L, d.barAddress(2));
        // Machine reset: BARs cleared and unmapped.
        r.m.reset();
        assertEquals(-1, d.barAddress(2));
        assertEquals(0xc, cfg(r, 0, 0x18, 4));
        assertEquals(0, cfg(r, 0, 4, 2));
        assertEquals(2, d.resets); // the Rig's reset and this one
        assertTrue(r.m.mmio().isEmpty());
    }

    @Test
    void barMovedWhileAnotherCpuUsesIt() throws Exception {
        Dev d = new Dev();
        Rig r = new Rig(cfg(d).cpus(2));
        long a = MEM32_CPU + 0x1000;
        long b = MEM32_CPU + 0x8000;
        put(r, 0, 0x10, 4, 0x80001000);
        put(r, 0, 4, 2, PciDevice.COMMAND_MEMORY);
        d.bar0.write(0, 4, 0x5a5a5a5aL);
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicLong hits = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.ConcurrentLinkedQueue<String> errors = new java.util.concurrent.ConcurrentLinkedQueue<>();
        // CPU 1 loads from both addresses while CPU 0 moves the BAR between them and disables it.
        Thread cpu1 = new Thread(() -> {
            while (!stop.get()) {
                for (long addr : new long[] {a, b}) {
                    long st = r.hcallOn(r.cpus[1], H.LOGICAL_CI_LOAD, 4, addr);
                    long v = r.cpus[1].gpr[4];
                    if (st != H.SUCCESS) {
                        errors.add("status " + st);
                    } else if (v == 0x5a5a5a5aL) {
                        hits.incrementAndGet();
                    } else if (v != 0) { // 0: nothing mapped there at that moment
                        errors.add("read 0x" + Long.toHexString(v));
                    }
                }
            }
        });
        cpu1.start();
        for (int i = 0; i < 2000; i++) {
            put(r, 0, 0x10, 4, i % 2 == 0 ? 0x80008000 : 0x80001000);
            if (i % 7 == 0) {
                put(r, 0, 4, 2, 0);
                put(r, 0, 4, 2, PciDevice.COMMAND_MEMORY);
            }
        }
        stop.set(true);
        cpu1.join();
        assertTrue(errors.isEmpty(), errors.toString());
        assertTrue(hits.get() > 0);
        // It ended at a: only that address reaches the device.
        assertEquals(a, d.barAddress(0));
        assertEquals(0x5a5a5a5aL, load(r, a, 4));
        assertNull(load(r, b, 4));
        assertEquals(1, r.m.mmio().regions().size());
    }

    @Test
    void overlappingBarsAreNotMapped() {
        Dev a = new Dev();
        Dev b = new Dev();
        Rig r = new Rig(cfg(a, b));
        assertEquals(1, b.slot());
        for (int s = 0; s < 2; s++) {
            put(r, s, 0x10, 4, 0x80000000);
            put(r, s, 4, 2, PciDevice.COMMAND_MEMORY);
        }
        assertEquals(MEM32_CPU, a.barAddress(0));
        assertEquals(-1, b.barAddress(0));
        put(r, 0, 0x10, 4, 0x80002000);
        put(r, 1, 0x10, 4, 0x80003000);
        assertEquals(MEM32_CPU + 0x3000, b.barAddress(0));
    }

    @Test
    void slotsAreStableAndChecked() {
        Dev a = new Dev();
        Dev b = new Dev();
        Dev c = new Dev();
        MachineConfig mc = new MachineConfig().ram(256L << 20);
        mc.addPciDevice(a).addPciDevice(0, b).addPciDevice(c);
        Rig r = new Rig(mc);
        assertEquals(0, b.slot());
        assertEquals(1, a.slot());
        assertEquals(2, c.slot());
        assertThrows(IllegalArgumentException.class, () -> new MachineConfig().addPciDevice(32, new Dev()));
        assertThrows(IllegalArgumentException.class, () -> new MachineConfig().addPciDevice(a).addPciDevice(a));
        assertThrows(IllegalArgumentException.class,
                () -> new MachineConfig().addPciDevice(3, new Dev()).addPciDevice(3, new Dev()));
        // A device belongs to one machine at a time; after close it may join another.
        assertThrows(IllegalStateException.class, () -> new Rig(cfg(a)));
        try {
            r.m.close();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        Rig r2 = new Rig(cfg(a));
        assertEquals(0, a.slot());
        assertEquals(0x12341b36, cfg(r2, 0, 0, 4));
        // PCI devices need the host bridge.
        MachineConfig noPhb = cfg(new Dev()).phb(false);
        assertThrows(IllegalArgumentException.class, () -> new Rig(noPhb));
        // Describing the device after it joined a machine is refused.
        assertThrows(IllegalStateException.class, () -> a.setInterruptPin(2));
    }

    @Test
    void interruptPinReachesItsLsi() {
        Dev a = new Dev();
        Dev b = new Dev();
        MachineConfig mc = new MachineConfig().ram(256L << 20).addPciDevice(a).addPciDevice(4, b);
        Rig r = new Rig(mc);
        int lsi = DeviceTree.PHB0_LSI_BASE;
        assertEquals(lsi, Phb.lsi(0, 1));
        assertEquals(lsi + 1, Phb.lsi(1, 1));
        assertEquals(lsi, Phb.lsi(4, 1)); // slots 0 and 4 share INTA's LSI
        assertArrayEquals(new int[] {0}, r.rtas("ibm,set-xive", 1, lsi, 0, 5));
        assertEquals(H.SUCCESS, r.hcall(H.CPPR, 0xff));
        Thread t = new Thread(() -> a.setIrq(true));
        t.start();
        try {
            t.join();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        assertTrue(r.cpu.extIrq);
        assertEquals(PciDevice.STATUS_INTERRUPT | PciDevice.STATUS_CAP_LIST, cfg(r, 0, 6, 2));
        assertEquals(H.SUCCESS, r.hcall(H.XIRR));
        assertEquals((0xffL << 24) | lsi, r.r(4) & 0xffffffffL);
        // Shared: still pending while the other device holds it.
        b.setIrq(true);
        a.setIrq(false);
        assertEquals(H.SUCCESS, r.hcall(H.EOI, (0xffL << 24) | lsi));
        assertTrue(r.cpu.extIrq);
        b.setIrq(false);
        assertEquals(H.SUCCESS, r.hcall(H.XIRR));
        assertEquals(H.SUCCESS, r.hcall(H.EOI, (0xffL << 24) | lsi));
        assertFalse(r.cpu.extIrq);
        // INTx disable masks the pin, the status bit still shows it.
        put(r, 0, 4, 2, PciDevice.COMMAND_INTX_DISABLE);
        a.setIrq(true);
        assertFalse(r.cpu.extIrq);
        assertEquals(PciDevice.STATUS_INTERRUPT | PciDevice.STATUS_CAP_LIST, cfg(r, 0, 6, 2));
        put(r, 0, 4, 2, 0);
        assertTrue(r.cpu.extIrq);
        assertEquals(H.SUCCESS, r.hcall(H.XIRR));
        a.setIrq(false);
        assertEquals(H.SUCCESS, r.hcall(H.EOI, (0xffL << 24) | lsi));
        assertFalse(r.cpu.extIrq);
        // A machine reset lowers the pin.
        a.setIrq(true);
        r.m.reset();
        assertFalse(a.irqLevel());
    }

    @Test
    void dmaThroughTheTceTable() {
        Dev d = new Dev();
        Rig r = new Rig(cfg(d));
        long liobn = DeviceTree.PHB0_LIOBN & 0xffffffffL;
        byte[] b = {1, 2, 3, 4, 5, 6, 7, 8};
        // Bus mastering is off.
        assertFalse(d.dmaWrite(0x1ff8, b, 0, 8));
        put(r, 0, 4, 2, PciDevice.COMMAND_MASTER);
        // Not mapped.
        assertFalse(d.dmaWrite(0x1ff8, b, 0, 8));
        // Map bus pages 1 and 2 to real pages 0x50 and 0x80, the first read-only.
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, liobn, 0x1000, 0x50000 | TceTable.PERM_READ));
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, liobn, 0x2000, 0x80000 | 3));
        assertFalse(d.dmaWrite(0x1ff8, b, 0, 8));
        assertTrue(d.dmaWrite(0x2ff8, b, 0, 8));
        assertArrayEquals(b, r.readBytes(0x80ff8, 8));
        r.writeBytes(0x50ffc, (byte) 9, (byte) 10, (byte) 11, (byte) 12);
        r.writeBytes(0x80000, (byte) 13, (byte) 14, (byte) 15, (byte) 16);
        byte[] got = new byte[8];
        assertTrue(d.dmaRead(0x1ffc, got, 0, 8));
        assertArrayEquals(new byte[] {9, 10, 11, 12, 13, 14, 15, 16}, got);
        // Beyond the 1 GiB window.
        assertFalse(d.dmaRead(DeviceTree.PHB0_DMA_WINDOW, got, 0, 1));
    }

    @Test
    void deviceTreeNodeIsQemus() {
        Dev d = new Dev();
        Rig r = new Rig(cfg(new Dev(), d));
        Fdt.Node phb = Fdt.parse(r.readBytes(r.m.fdtAddress(), Machine.FDT_MAX_SIZE)).child("pci@800000020000000");
        Fdt.Node n = phb.child("communication-controller@1");
        assertNotNull(n, Fdt.toText(phb));
        assertEquals(List.of("max-latency", "min-grant", "reg", "ibm,my-drc-index", "ibm,loc-code", "devsel-speed",
                "cache-line-size", "subsystem-vendor-id", "subsystem-id", "interrupts", "class-code", "revision-id",
                "device-id", "vendor-id"), new ArrayList<>(n.props().keySet()));
        assertArrayEquals(Fdt.cells(0x800, 0, 0, 0, 0, 0x02000810, 0, 0, 0, 0x1000, 0x03000818, 0, 0, 0, 0x10000,
                0x01000820, 0, 0, 0, 0x20), n.get("reg"));
        assertEquals(0x40000008, n.getCell("ibm,my-drc-index", 0));
        assertEquals("qemu_test-dev:0000:00:01.0", n.getString("ibm,loc-code"));
        assertEquals(0x078000, n.getCell("class-code", 0));
        assertEquals(1, n.getCell("interrupts", 0));
        assertEquals(0x1af4, n.getCell("subsystem-vendor-id", 0));
        assertEquals(0x1b36, n.getCell("vendor-id", 0));
        // The slot's DR connector is populated.
        assertArrayEquals(new int[] {0, 1}, r.rtas("get-sensor-state", 2, 9003, 0x40000008));
        assertArrayEquals(new int[] {0, 0}, r.rtas("get-sensor-state", 2, 9003, 0x40000010));
        // Without devices, the bridge has no children, as before.
        Rig plain = new Rig();
        Fdt.Node p = Fdt.parse(plain.readBytes(plain.m.fdtAddress(), Machine.FDT_MAX_SIZE)).child("pci@800000020000000");
        assertTrue(p.children().isEmpty());
    }

    @Test
    void classNames() {
        assertEquals("legacy-device", PciClassNames.name(0x00ff00));
        assertEquals("display", PciClassNames.name(0x000100));
        assertEquals("ethernet", PciClassNames.name(0x020000));
        assertEquals("scsi", PciClassNames.name(0x010000));
        assertEquals("sound", PciClassNames.name(0x040100));
        assertEquals("multimedia-device", PciClassNames.name(0x048000));
        assertEquals("usb-xhci", PciClassNames.name(0x0c0330));
        assertEquals("usb", PciClassNames.name(0x0c0301));
        assertEquals("io-apic", PciClassNames.name(0x080010));
        assertEquals("intelligent-io", PciClassNames.name(0x0e0000));
        assertEquals("pci", PciClassNames.name(0xff0000));
    }

    @Test
    void stateIsSavedAndBarsRemapped() throws IOException {
        Dev a = new Dev();
        Rig ra = new Rig(cfg(a));
        put(ra, 0, 0x10, 4, 0x80004000);
        put(ra, 0, 4, 2, PciDevice.COMMAND_MEMORY | PciDevice.COMMAND_MASTER);
        put(ra, 0, 0x44, 2, 0x7777);
        store(ra, MEM32_CPU + 0x4000, 4, 0x01020304);
        a.saved = 99;
        a.setIrq(true);
        byte[] state = MachineStateTest.save(ra.m);

        Dev b = new Dev();
        Rig rb = new Rig(cfg(b));
        MachineStateTest.load(rb.m, state);
        assertEquals(99, b.saved);
        assertEquals(MEM32_CPU + 0x4000, b.barAddress(0));
        assertTrue(b.irqLevel());
        assertEquals(0x7777, cfg(rb, 0, 0x44, 2));
        assertEquals(PciDevice.COMMAND_MEMORY | PciDevice.COMMAND_MASTER, cfg(rb, 0, 4, 2));
        // The BAR's contents are the device's own business (not saved by this test device).
        assertEquals(0L, load(rb, MEM32_CPU + 0x4000, 4));
        assertArrayEquals(state, MachineStateTest.save(rb.m));
        // The LSI is asserted again in the loaded machine.
        assertArrayEquals(new int[] {0}, rb.rtas("ibm,set-xive", 1, DeviceTree.PHB0_LSI_BASE, 0, 5));
        assertEquals(H.SUCCESS, rb.hcall(H.CPPR, 0xff));
        assertTrue(rb.cpu.extIrq);
    }

    @Test
    void otherDevicesAreOtherHardware() throws IOException {
        byte[] state = MachineStateTest.save(new Rig(cfg(new Dev())).m);
        byte[] none = MachineStateTest.save(new Rig().m);
        Dev other = new Dev();
        other.hw = "model 2";
        Object[][] cases = {
            {cfg(new Dev(0x4321)), "PCI device 0 (test-dev in slot 0): device id: saved 0x1234, this machine 0x4321"},
            {new MachineConfig().ram(256L << 20).addPciDevice(3, new Dev()), "slot: saved 0, this machine 3"},
            {cfg(other), "device: saved , this machine model 2"},
            {cfg(new Dev(), new Dev()), "PCI devices: saved 1, this machine 2"},
            {cfg(new VirtioPciDevice(new VirtioRng())), "model: saved test-dev, this machine virtio-rng-pci"},
            {new MachineConfig().ram(256L << 20), "PCI devices: saved 1, this machine 0"},
        };
        for (Object[] k : cases) {
            Rig r = new Rig((MachineConfig) k[0]);
            String m = assertThrows(StateMismatchException.class, () -> MachineStateTest.load(r.m, state)).getMessage();
            assertTrue(m.contains((String) k[1]), k[1] + " in: " + m);
        }
        Rig r = new Rig(cfg(new Dev()));
        String m = assertThrows(StateMismatchException.class, () -> MachineStateTest.load(r.m, none)).getMessage();
        assertTrue(m.contains("PCI devices: saved 0, this machine 1"), m);
    }
}
