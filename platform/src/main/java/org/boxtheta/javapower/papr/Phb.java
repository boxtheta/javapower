package org.boxtheta.javapower.papr;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.boxtheta.javapower.core.MmioBus;
import org.boxtheta.javapower.core.MmioHandler;
import org.boxtheta.javapower.core.MmioRegion;
import org.boxtheta.javapower.core.PhysicalMemory;

/**
 * The devices on PHB 0, the machine's PCI host bridge (QEMU's spapr-pci-host-bridge with its
 * default windows): configuration space through RTAS, BARs mapped on the machine's
 * {@link MmioBus} through the bridge's windows, the four LSIs the interrupt pins are swizzled
 * onto, and DMA through the bridge's TCE table. Only bus 0, function 0 of each slot is
 * populated; there is no hot-plug.
 *
 * <p>Address windows, as in the bridge's {@code ranges} property: PCI I/O ports 0 to 0xffff at
 * real address 32 TiB; PCI memory 2 GiB to 4 GiB at 32 TiB + 2 GiB; PCI memory 33 TiB to 34 TiB
 * at the same real addresses. A BAR is reachable by the CPUs only where it lies wholly inside
 * one of them.
 */
final class Phb {
    static final int SLOTS = 32;
    static final long IO_WIN = MachineConfig.PCI_WINDOWS_BASE;
    static final long IO_SIZE = 0x10000;
    static final long MEM32_PCI = 0x8000_0000L;
    static final long MEM32_SIZE = 0x8000_0000L;
    static final long MEM64 = 0x2100_0000_0000L;
    static final long MEM64_SIZE = 0x100_0000_0000L;

    private final MmioBus bus;
    private final TceTable tce;
    private final PhysicalMemory mem;
    private final Xics xics;
    private final PaprLog log;
    /** The machine, for the current CPU's byte order; null in tests that build a bridge alone. */
    Machine machine;
    private final PciDevice[] slots = new PciDevice[SLOTS];
    /** Devices in slot order. */
    private final List<PciDevice> devices = new ArrayList<>();
    /** Number of devices asserting each LSI; guarded by itself. */
    private final int[] lsiCount = new int[4];

    Phb(MmioBus bus, TceTable tce, PhysicalMemory mem, Xics xics, PaprLog log) {
        this.bus = bus;
        this.tce = tce;
        this.mem = mem;
        this.xics = xics;
        this.log = log;
    }

    /**
     * Place the configured devices: those with a slot first, then the others in the order they
     * were added, each into the lowest free slot.
     *
     * @throws IllegalArgumentException if slots clash or there are too many devices
     */
    void place(List<MachineConfig.Pci> config) {
        for (MachineConfig.Pci p : config) {
            if (p.slot() >= 0) {
                if (slots[p.slot()] != null) {
                    throw new IllegalArgumentException("two PCI devices in slot " + p.slot());
                }
                slots[p.slot()] = p.device();
            }
        }
        for (MachineConfig.Pci p : config) {
            if (p.slot() < 0) {
                int s = 0;
                while (s < SLOTS && slots[s] != null) {
                    s++;
                }
                if (s == SLOTS) {
                    throw new IllegalArgumentException("more than " + SLOTS + " PCI devices");
                }
                slots[s] = p.device();
            }
        }
        for (int s = 0; s < SLOTS; s++) {
            if (slots[s] != null) {
                devices.add(slots[s]);
            }
        }
        List<PciDevice> done = new ArrayList<>();
        try {
            for (int s = 0; s < SLOTS; s++) {
                if (slots[s] != null) {
                    slots[s].attach(this, s);
                    done.add(slots[s]);
                }
            }
        } catch (RuntimeException e) {
            for (PciDevice d : done) {
                d.detach();
            }
            throw e;
        }
    }

    List<PciDevice> devices() {
        return devices;
    }

    PciDevice device(int slot) {
        return slots[slot];
    }

    MmioBus bus() {
        return bus;
    }

    PhysicalMemory memory() {
        return mem;
    }

    PaprLog log() {
        return log;
    }

    /** Whether the CPU on the calling thread is big-endian (see {@link Machine#currentCpuBigEndian}). */
    boolean currentCpuBigEndian() {
        return machine == null || machine.currentCpuBigEndian();
    }

    // ---- configuration space (RTAS) ------------------------------------------------------------

    /**
     * The device a configuration address names on bus 0 of this bridge, or null: the address
     * holds the bus in bits 16..23, device and function in bits 8..15 (big-endian bit numbers
     * aside, as plain shifts).
     */
    private PciDevice find(int cfgAddr) {
        int busNo = (cfgAddr >>> 16) & 0xff;
        int devfn = (cfgAddr >>> 8) & 0xff;
        if (busNo != 0 || (devfn & 7) != 0) {
            return null;
        }
        return slots[devfn >>> 3];
    }

    /** The register a configuration address names, with the extended-register bits 28..31. */
    private static int register(int cfgAddr) {
        return ((cfgAddr >>> 20) & 0xf00) | (cfgAddr & 0xff);
    }

    /**
     * A configuration read (RTAS read-pci-config and ibm,read-pci-config): the value, or -1L if
     * there is no such device or the access is invalid, which RTAS reports as a hardware error.
     */
    long configRead(int cfgAddr, int size) {
        if (size != 1 && size != 2 && size != 4) {
            return -1;
        }
        PciDevice d = find(cfgAddr);
        int reg = register(cfgAddr);
        if (d == null || reg % size != 0 || reg >= PciDevice.CONFIG_SIZE) {
            return -1;
        }
        return d.configRead(reg, size) & 0xffffffffL;
    }

    /** A configuration write; false for the cases {@link #configRead} fails. */
    boolean configWrite(int cfgAddr, int size, int value) {
        if (size != 1 && size != 2 && size != 4) {
            return false;
        }
        PciDevice d = find(cfgAddr);
        int reg = register(cfgAddr);
        if (d == null || reg % size != 0 || reg >= PciDevice.CONFIG_SIZE) {
            return false;
        }
        d.configWrite(reg, size, value);
        return true;
    }

    // ---- BARs --------------------------------------------------------------------------------

    /**
     * Map BAR {@code bar} of {@code d}, decoding PCI addresses {@code [pci, pci + size)}, at the
     * real address the bridge's windows give it. Returns the region, or null if the BAR lies
     * outside the windows or would overlap another BAR (the guest is still assigning
     * addresses); such a BAR is unreachable, as in QEMU when nothing else claims the addresses.
     */
    MmioRegion map(PciDevice d, int bar, long pci, long size, boolean io, MmioHandler h) {
        long real;
        if (io) {
            if (pci + size > IO_SIZE) {
                return null;
            }
            real = IO_WIN + pci;
        } else if (pci >= MEM32_PCI && pci + size <= MEM32_PCI + MEM32_SIZE) {
            real = IO_WIN + pci;
        } else if (pci >= MEM64 && pci + size <= MEM64 + MEM64_SIZE) {
            real = pci;
        } else {
            log.once("pci-bar-outside-" + d.slot() + "-" + bar, String.format("%s: BAR %d at PCI address 0x%x is"
                    + " outside the host bridge's windows", d, bar, pci));
            return null;
        }
        MmioRegion r = new MmioRegion(String.format("pci@%d.bar%d", d.slot(), bar), real, size, PciDevice.ORDER, h);
        try {
            bus.map(r);
        } catch (IllegalArgumentException e) {
            log.once("pci-bar-overlap-" + d.slot() + "-" + bar, d + ": BAR " + bar + " not mapped: " + e.getMessage());
            return null;
        }
        return r;
    }

    // ---- interrupts --------------------------------------------------------------------------

    /** The LSI the interrupt pin of the device in {@code slot} is wired to (QEMU's pci_swizzle). */
    static int lsi(int slot, int pin) {
        return DeviceTree.PHB0_LSI_BASE + (slot + pin - 1) % 4;
    }

    /** Device {@code d} asserts ({@code true}) or deasserts its pin; called under its irq lock. */
    void intx(PciDevice d, boolean asserted) {
        int pin = d.rawConfig(PciDevice.INTERRUPT_PIN, 1);
        int n = lsi(d.slot(), pin) - DeviceTree.PHB0_LSI_BASE;
        synchronized (lsiCount) {
            lsiCount[n] += asserted ? 1 : -1;
            xics.setIrq(DeviceTree.PHB0_LSI_BASE + n, lsiCount[n] > 0);
        }
    }

    /** After a load: the LSI levels from the devices' restored pins. */
    void recomputeLsis() {
        synchronized (lsiCount) {
            java.util.Arrays.fill(lsiCount, 0);
            for (PciDevice d : devices) {
                if (d.irqAsserted()) {
                    lsiCount[lsi(d.slot(), d.rawConfig(PciDevice.INTERRUPT_PIN, 1)) - DeviceTree.PHB0_LSI_BASE]++;
                }
            }
            for (int n = 0; n < 4; n++) {
                xics.setIrq(DeviceTree.PHB0_LSI_BASE + n, lsiCount[n] > 0);
            }
        }
    }

    // ---- DMA ---------------------------------------------------------------------------------

    /** Copy between {@code b} and guest memory at bus address {@code addr}, through the TCE table. */
    boolean dma(long addr, byte[] b, int off, int len, boolean write) {
        while (len > 0) {
            int n = (int) Math.min(len, TceTable.PAGE_SIZE - (addr & ~TceTable.PAGE_MASK));
            long real = tce.translate(addr, write);
            if (real < 0 || !mem.contains(real, n)) {
                log.once("pci-dma-fault", String.format("PCI DMA %s at bus address 0x%x is not mapped",
                        write ? "write" : "read", addr));
                return false;
            }
            if (write) {
                mem.write(real, b, off, n);
            } else {
                mem.read(real, b, off, n);
            }
            addr += n;
            off += n;
            len -= n;
        }
        return true;
    }

    // ---- machine reset, device tree, saved state ---------------------------------------------

    void reset() {
        for (PciDevice d : devices) {
            d.machineReset();
        }
        synchronized (lsiCount) {
            java.util.Arrays.fill(lsiCount, 0);
        }
    }

    /** Periodic work: let devices act on what a load left them. Any thread, no lock held. */
    void pump() {
        for (PciDevice d : devices) {
            d.pump();
        }
    }

    void close() {
        for (PciDevice d : devices) {
            d.detach();
        }
    }

    /** The node of the device in {@code slot}, as QEMU's spapr_dt_pci_device makes it. */
    static Fdt.Node deviceNode(PciDevice d) {
        int slot = d.slot();
        int classCode = d.rawConfig(PciDevice.CLASS_PROG, 3);
        Fdt.Node n = new Fdt.Node(String.format("%s@%x", PciClassNames.name(classCode), slot));
        int devfnBits = slot << 11;
        int status = d.rawConfig(PciDevice.STATUS, 2);
        n.setCell("max-latency", d.rawConfig(PciDevice.MAX_LAT, 1));
        n.setCell("min-grant", d.rawConfig(PciDevice.MIN_GNT, 1));
        n.setCells("reg", d.regProperty(devfnBits));
        n.setCell("ibm,my-drc-index", Drc.Kind.PCI.base | (slot << 3));
        n.setString("ibm,loc-code", String.format("qemu_%s:0000:00:%02x.0", d.name(), slot));
        if ((status & 0x40) != 0) {
            n.setEmpty("udf-supported");
        }
        if ((status & 0x20) != 0) {
            n.setEmpty("66mhz-capable");
        }
        if ((status & 0x80) != 0) {
            n.setEmpty("fast-back-to-back");
        }
        n.setCell("devsel-speed", status & 0x600);
        n.setCell("cache-line-size", d.rawConfig(PciDevice.CACHE_LINE_SIZE, 1));
        int subVendor = d.rawConfig(PciDevice.SUBSYSTEM_VENDOR_ID, 2);
        int sub = d.rawConfig(PciDevice.SUBSYSTEM_ID, 2);
        if (subVendor != 0) {
            n.setCell("subsystem-vendor-id", subVendor);
        }
        if (sub != 0) {
            n.setCell("subsystem-id", sub);
        }
        int pin = d.rawConfig(PciDevice.INTERRUPT_PIN, 1);
        if (pin != 0) {
            n.setCell("interrupts", pin);
        }
        n.setCell("class-code", classCode);
        n.setCell("revision-id", d.rawConfig(PciDevice.REVISION_ID, 1));
        n.setCell("device-id", d.rawConfig(PciDevice.DEVICE_ID, 2));
        n.setCell("vendor-id", d.rawConfig(PciDevice.VENDOR_ID, 2));
        d.deviceTree(n);
        return n;
    }

    /** The "PCI " section of the hardware description. */
    void saveHardware(java.io.DataOutput o) throws IOException {
        o.writeInt(devices.size());
        for (PciDevice d : devices) {
            o.writeInt(d.slot());
            o.writeUTF(d.name());
            o.writeInt(d.rawConfig(PciDevice.VENDOR_ID, 2));
            o.writeInt(d.rawConfig(PciDevice.DEVICE_ID, 2));
            o.writeInt(d.rawConfig(PciDevice.REVISION_ID, 1));
            o.writeInt(d.rawConfig(PciDevice.CLASS_PROG, 3));
            o.writeInt(d.rawConfig(PciDevice.SUBSYSTEM_VENDOR_ID, 2));
            o.writeInt(d.rawConfig(PciDevice.SUBSYSTEM_ID, 2));
            o.writeInt(d.rawConfig(PciDevice.INTERRUPT_PIN, 1));
            for (int i = 0; i < PciDevice.BARS; i++) {
                o.writeUTF(d.barSummary(i));
            }
            o.writeUTF(d.hardwareDescription());
        }
    }

    /** Compare two "PCI " sections; null stands for no devices. */
    static void compareHardware(List<String> diff, DataInputStream r, DataInputStream cur) throws IOException {
        int sn = r == null ? 0 : r.readInt();
        int cn = cur == null ? 0 : cur.readInt();
        if (sn != cn) {
            diff.add("PCI devices: saved " + sn + ", this machine " + cn);
        }
        for (int i = 0; i < Math.min(sn, cn); i++) {
            int slot = cur.readInt();
            String name = cur.readUTF();
            String what = "PCI device " + i + " (" + name + " in slot " + slot + "): ";
            cmp(diff, what + "slot", r.readInt(), slot);
            cmp(diff, what + "model", r.readUTF(), name);
            String[] fields = {"vendor id", "device id", "revision", "class code", "subsystem vendor id",
                "subsystem id", "interrupt pin"};
            for (String f : fields) {
                cmp(diff, what + f, "0x" + Integer.toHexString(r.readInt()), "0x" + Integer.toHexString(cur.readInt()));
            }
            for (int b = 0; b < PciDevice.BARS; b++) {
                cmp(diff, what + "BAR " + b, r.readUTF(), cur.readUTF());
            }
            cmp(diff, what + "device", r.readUTF(), cur.readUTF());
        }
    }

    private static void cmp(List<String> diff, String what, Object saved, Object now) {
        if (!saved.equals(now)) {
            diff.add(what + ": saved " + saved + ", this machine " + now);
        }
    }

    /** The "PCI " record: per device its configuration space, interrupt level and own state. */
    void saveState(java.io.DataOutput out) throws IOException {
        out.writeInt(devices.size());
        for (PciDevice d : devices) {
            out.writeInt(d.slot());
            d.saveBase(out);
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(b);
            d.saveState(o);
            o.flush();
            org.boxtheta.javapower.core.StateOutput.writeBytes(out, b.toByteArray());
        }
    }

    void loadState(org.boxtheta.javapower.core.StateInput.Record r) throws IOException {
        if (r.readInt() != devices.size()) {
            throw r.corrupt("PCI device count");
        }
        for (PciDevice d : devices) {
            if (r.readInt() != d.slot()) {
                throw r.corrupt("PCI slot of " + d);
            }
            d.loadBase(r);
            byte[] b = r.readBytes(org.boxtheta.javapower.core.StateOutput.MAX_RECORD);
            if (b == null) {
                throw r.corrupt("PCI device " + d);
            }
            ByteArrayInputStream bin = new ByteArrayInputStream(b);
            try {
                d.loadState(new DataInputStream(bin));
            } catch (EOFException e) {
                throw r.corrupt("PCI device " + d + ": its state ends early");
            }
            if (bin.available() != 0) {
                throw r.corrupt("PCI device " + d + ": " + bin.available() + " bytes of its state left unread");
            }
        }
        recomputeLsis();
    }
}
