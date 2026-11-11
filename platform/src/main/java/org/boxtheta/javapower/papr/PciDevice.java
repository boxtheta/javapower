package org.boxtheta.javapower.papr;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.Objects;
import org.boxtheta.javapower.core.MmioHandler;
import org.boxtheta.javapower.core.MmioRegion;

/**
 * A conventional PCI device (function 0, type 0 header) on the machine's PCI host bridge, added
 * with {@link MachineConfig#addPciDevice}. A subclass describes the device in its constructor
 * (identity, BARs, interrupt pin, capabilities) and implements what its BARs do; this class
 * implements the 256 bytes of configuration space the guest reaches through RTAS, maps the BARs
 * into the bridge's windows when the guest programs them, routes the interrupt pin to the
 * bridge's LSIs and translates DMA through the bridge's TCE table.
 *
 * <h2>Configuration space</h2>
 * The standard header behaves as QEMU's: the command register's I/O, memory, bus master, SERR
 * and INTx-disable bits, the cache line size and the interrupt line are writable, the error bits
 * of the status register are write-one-to-clear, BARs keep their type bits and decode a
 * naturally aligned range of their size. Bytes 0x40 to 0xff are plain writable storage except
 * where a capability ({@link #addCapability}) lies: its bytes are read-only to the guest unless
 * the device makes some writable ({@link #setWriteMask}). A device that needs to act on guest
 * accesses overrides {@link #configReading} and {@link #configWritten}.
 *
 * <h2>BARs</h2>
 * Memory and I/O BARs ({@link #addMemoryBar}, {@link #addIoBar}) are backed by an
 * {@link MmioHandler}. While the guest has a BAR enabled (memory or I/O decoding in the command
 * register) at an address inside one of the bridge's windows, the CPUs' loads and stores there
 * call the handler with offsets from the BAR's start. PCI is little-endian: values are the bytes
 * at those offsets read in little-endian order. Accesses that the bus cannot complete (unaligned,
 * crossing the BAR's end) never reach the handler, as described for {@link MmioHandler}.
 *
 * <h2>Threads</h2>
 * <ul>
 * <li>The constructor (and {@link #addMemoryBar}, {@link #addIoBar}, {@link #addCapability},
 *     {@link #setSubsystem}, {@link #setInterruptPin}) runs on the embedder's thread before the
 *     machine is built.</li>
 * <li>BAR handlers run on the CPU threads, concurrently, with no lock of the machine held, as for
 *     any {@link MmioHandler}.</li>
 * <li>Configuration accesses arrive on a CPU thread, inside an RTAS call, with the machine lock
 *     and this device's configuration lock held; {@link #configReading} and
 *     {@link #configWritten} run there. They may take the device's own locks, but code holding
 *     those locks must not wait for a configuration access (the accessors of this class that
 *     read or change configuration space take the configuration lock themselves).</li>
 * <li>{@link #setIrq}, {@link #dmaRead} and {@link #dmaWrite} may be called from any thread,
 *     including the embedder's own and the CPU threads in a BAR handler.</li>
 * <li>{@link #reset}, {@link #saveState}, {@link #loadState}, {@link #deviceTree} and
 *     {@link #hardwareDescription} are called while no CPU runs (the device tree is also built
 *     when the guest negotiates its features, on that CPU's thread).</li>
 * </ul>
 *
 * <p>A device belongs to one machine at a time. After that machine is closed, the same object
 * may be added to the configuration of a new one (as when a saved state is loaded into a new
 * emulator built from the same configuration).
 */
public abstract class PciDevice {
    /** Size of conventional configuration space. */
    public static final int CONFIG_SIZE = 256;
    /** Size of the type 0 header; capabilities and device registers follow it. */
    public static final int HEADER_SIZE = 0x40;

    // Configuration space registers of the type 0 header.
    public static final int VENDOR_ID = 0x00;
    public static final int DEVICE_ID = 0x02;
    public static final int COMMAND = 0x04;
    public static final int STATUS = 0x06;
    public static final int REVISION_ID = 0x08;
    public static final int CLASS_PROG = 0x09;
    public static final int CACHE_LINE_SIZE = 0x0c;
    public static final int HEADER_TYPE = 0x0e;
    public static final int BAR0 = 0x10;
    public static final int SUBSYSTEM_VENDOR_ID = 0x2c;
    public static final int SUBSYSTEM_ID = 0x2e;
    public static final int ROM_ADDRESS = 0x30;
    public static final int CAPABILITY_LIST = 0x34;
    public static final int INTERRUPT_LINE = 0x3c;
    public static final int INTERRUPT_PIN = 0x3d;
    public static final int MIN_GNT = 0x3e;
    public static final int MAX_LAT = 0x3f;

    public static final int COMMAND_IO = 0x1;
    public static final int COMMAND_MEMORY = 0x2;
    public static final int COMMAND_MASTER = 0x4;
    public static final int COMMAND_SERR = 0x100;
    public static final int COMMAND_INTX_DISABLE = 0x400;
    public static final int STATUS_INTERRUPT = 0x8;
    public static final int STATUS_CAP_LIST = 0x10;
    private static final int STATUS_W1C = 0x0100 | 0x0800 | 0x1000 | 0x2000 | 0x4000 | 0x8000;

    /** Number of BARs of a type 0 header. */
    public static final int BARS = 6;
    private static final long UNMAPPED = -1;

    /** One base address register. */
    private static final class Bar {
        final int index;
        final long size;
        final boolean io;
        final boolean bits64;
        final boolean prefetchable;
        final MmioHandler handler;
        /** The PCI address it is mapped at, or {@link #UNMAPPED}. */
        long pciAddr = UNMAPPED;
        MmioRegion region;

        Bar(int index, long size, boolean io, boolean bits64, boolean prefetchable, MmioHandler handler) {
            this.index = index;
            this.size = size;
            this.io = io;
            this.bits64 = bits64;
            this.prefetchable = prefetchable;
            this.handler = handler;
        }

        int typeBits() {
            if (io) {
                return 0x1;
            }
            return (bits64 ? 0x4 : 0) | (prefetchable ? 0x8 : 0);
        }
    }

    private final String name;
    /** Guards configuration space and the BAR mappings. */
    private final Object configLock = new Object();
    private final byte[] config = new byte[CONFIG_SIZE];
    private final byte[] wmask = new byte[CONFIG_SIZE];
    private final byte[] w1cmask = new byte[CONFIG_SIZE];
    /** Configuration bytes owned by a capability. */
    private final boolean[] capUsed = new boolean[CONFIG_SIZE];
    private final Bar[] bars = new Bar[BARS];
    /** Configuration space as constructed, restored at every reset; set at the first attach. */
    private byte[] initialConfig;

    private volatile Phb phb;
    private int slot = -1;

    /** Guards the INTx state. A leaf below the configuration lock and any device lock. */
    private final Object irqLock = new Object();
    private boolean irqLevel;
    private boolean intxDisabled;
    private boolean irqAsserted;
    private volatile boolean busMaster;

    /**
     * A device with the given identity and no BARs, capabilities or interrupt.
     *
     * @param name the device's model name, for messages and its {@code ibm,loc-code}
     *     ({@code qemu_<name>:0000:00:<slot>.0}, as QEMU forms it); e.g. {@code "virtio-rng-pci"}
     * @param classCode the 24-bit class code: base class, subclass and programming interface
     */
    protected PciDevice(String name, int vendorId, int deviceId, int revision, int classCode) {
        this.name = Objects.requireNonNull(name, "name");
        setConfig16Raw(VENDOR_ID, vendorId);
        setConfig16Raw(DEVICE_ID, deviceId);
        config[REVISION_ID] = (byte) revision;
        config[CLASS_PROG] = (byte) classCode;
        config[CLASS_PROG + 1] = (byte) (classCode >>> 8);
        config[CLASS_PROG + 2] = (byte) (classCode >>> 16);
        wmask[CACHE_LINE_SIZE] = (byte) 0xff;
        wmask[INTERRUPT_LINE] = (byte) 0xff;
        int cmd = COMMAND_IO | COMMAND_MEMORY | COMMAND_MASTER | COMMAND_INTX_DISABLE | COMMAND_SERR;
        wmask[COMMAND] = (byte) cmd;
        wmask[COMMAND + 1] = (byte) (cmd >>> 8);
        w1cmask[STATUS] = (byte) STATUS_W1C;
        w1cmask[STATUS + 1] = (byte) (STATUS_W1C >>> 8);
        for (int i = HEADER_SIZE; i < CONFIG_SIZE; i++) {
            wmask[i] = (byte) 0xff;
        }
    }

    // ---- description, before the machine is built ---------------------------------------------

    private void checkUnattached() {
        if (initialConfig != null) {
            throw new IllegalStateException("PCI device " + name + " has been added to a machine; describe it in its"
                    + " constructor");
        }
    }

    /** Set the subsystem vendor and subsystem ids (0 by default). */
    protected final void setSubsystem(int vendorId, int id) {
        checkUnattached();
        setConfig16Raw(SUBSYSTEM_VENDOR_ID, vendorId);
        setConfig16Raw(SUBSYSTEM_ID, id);
    }

    /** The interrupt pin: 0 for none (the default), 1 to 4 for INTA to INTD. */
    protected final void setInterruptPin(int pin) {
        checkUnattached();
        if (pin < 0 || pin > 4) {
            throw new IllegalArgumentException("interrupt pin " + pin);
        }
        config[INTERRUPT_PIN] = (byte) pin;
    }

    /**
     * Add memory BAR {@code index}: {@code size} bytes (a power of two, at least 16) decoded by
     * {@code handler}. A 64-bit BAR also takes register {@code index + 1}.
     *
     * @throws IllegalArgumentException if the index, the size or the registers are invalid
     */
    protected final void addMemoryBar(int index, long size, boolean bits64, boolean prefetchable,
            MmioHandler handler) {
        checkUnattached();
        if (size < 16 || Long.bitCount(size) != 1 || (!bits64 && size > 0x8000_0000L)) {
            throw new IllegalArgumentException("BAR size 0x" + Long.toHexString(size));
        }
        addBar(new Bar(index, size, false, bits64, prefetchable, Objects.requireNonNull(handler, "handler")));
    }

    /**
     * Add I/O BAR {@code index}: {@code size} ports (a power of two from 4 to 256) decoded by
     * {@code handler}.
     *
     * @throws IllegalArgumentException if the index or the size is invalid
     */
    protected final void addIoBar(int index, int size, MmioHandler handler) {
        checkUnattached();
        if (size < 4 || size > 256 || Integer.bitCount(size) != 1) {
            throw new IllegalArgumentException("I/O BAR size " + size);
        }
        addBar(new Bar(index, size, true, false, false, Objects.requireNonNull(handler, "handler")));
    }

    private void addBar(Bar b) {
        int last = b.bits64 ? b.index + 1 : b.index;
        if (b.index < 0 || last >= BARS) {
            throw new IllegalArgumentException("BAR index " + b.index);
        }
        for (int i = 0; i < BARS; i++) {
            Bar o = bars[i];
            if (o != null && (i == b.index || i == last || (o.bits64 && i + 1 == b.index))) {
                throw new IllegalArgumentException("BAR " + b.index + " overlaps BAR " + i);
            }
        }
        bars[b.index] = b;
        int off = BAR0 + 4 * b.index;
        long mask = ~(b.size - 1);
        setConfig32Raw(off, b.typeBits());
        setMask(off, 4, (int) mask);
        if (b.bits64) {
            setMask(off + 4, 4, (int) (mask >>> 32));
        }
    }

    /**
     * Add a capability with id {@code id} taking {@code length} bytes (at least 2) of
     * configuration space, at the first free dword-aligned offset from 0x40, and put it at the
     * head of the capability list, as QEMU does. Returns its offset; its first byte holds the id
     * and the second the next pointer. The rest is the device's to fill with {@link #setConfig8}
     * and the like, and is read-only to the guest unless made writable with
     * {@link #setWriteMask}.
     *
     * @throws IllegalArgumentException if there is no room
     */
    protected final int addCapability(int id, int length) {
        checkUnattached();
        if (length < 2) {
            throw new IllegalArgumentException("capability length " + length);
        }
        int off = -1;
        for (int o = HEADER_SIZE; o + length <= CONFIG_SIZE; o += 4) {
            boolean free = true;
            for (int k = 0; k < length && free; k++) {
                free = !capUsed[o + k];
            }
            if (free) {
                off = o;
                break;
            }
        }
        if (off < 0) {
            throw new IllegalArgumentException("no room for a capability of " + length + " bytes");
        }
        for (int k = 0; k < length; k++) {
            capUsed[off + k] = true;
            wmask[off + k] = 0;
            config[off + k] = 0;
        }
        config[off] = (byte) id;
        config[off + 1] = config[CAPABILITY_LIST];
        config[CAPABILITY_LIST] = (byte) off;
        config[STATUS] |= (byte) STATUS_CAP_LIST;
        return off;
    }

    // ---- configuration space for the device ---------------------------------------------------

    private void setConfig16Raw(int off, int v) {
        config[off] = (byte) v;
        config[off + 1] = (byte) (v >>> 8);
    }

    private void setConfig32Raw(int off, int v) {
        for (int k = 0; k < 4; k++) {
            config[off + k] = (byte) (v >>> (8 * k));
        }
    }

    private void setMask(int off, int size, int mask) {
        for (int k = 0; k < size; k++) {
            wmask[off + k] = (byte) (mask >>> (8 * k));
        }
    }

    private static void checkRange(int off, int size) {
        if (off < 0 || size < 1 || size > 4 || off + size > CONFIG_SIZE) {
            throw new IndexOutOfBoundsException("configuration bytes [" + off + ", " + (off + size) + ")");
        }
    }

    private int getRaw(int off, int size) {
        int v = 0;
        for (int k = size - 1; k >= 0; k--) {
            v = (v << 8) | (config[off + k] & 0xff);
        }
        return v;
    }

    /** The {@code size} (1 to 4) little-endian bytes of configuration space at {@code offset}. */
    protected final int getConfig(int offset, int size) {
        checkRange(offset, size);
        synchronized (configLock) {
            return getRaw(offset, size);
        }
    }

    /**
     * Set {@code size} (1 to 4) bytes of configuration space at {@code offset} to the low bytes
     * of {@code value}, little-endian, whatever the guest may write there.
     */
    protected final void setConfig(int offset, int size, int value) {
        checkRange(offset, size);
        synchronized (configLock) {
            for (int k = 0; k < size; k++) {
                config[offset + k] = (byte) (value >>> (8 * k));
            }
        }
    }

    /** {@link #setConfig} of one byte. */
    protected final void setConfig8(int offset, int value) {
        setConfig(offset, 1, value);
    }

    /** {@link #setConfig} of two bytes. */
    protected final void setConfig16(int offset, int value) {
        setConfig(offset, 2, value);
    }

    /** {@link #setConfig} of four bytes. */
    protected final void setConfig32(int offset, int value) {
        setConfig(offset, 4, value);
    }

    /**
     * Which bits of the {@code size} (1 to 4) bytes at {@code offset} the guest may change (set
     * bits of {@code mask}, little-endian). Only for bytes from {@link #HEADER_SIZE}; call it in
     * the constructor.
     */
    protected final void setWriteMask(int offset, int size, int mask) {
        checkUnattached();
        checkRange(offset, size);
        if (offset < HEADER_SIZE) {
            throw new IllegalArgumentException("the header's write masks are fixed");
        }
        setMask(offset, size, mask);
    }

    /**
     * The guest is about to read {@code size} bytes at {@code offset}: a device whose registers
     * there are not plain storage updates them with {@link #setConfig}. Called with the
     * configuration lock held, on a CPU thread. The default does nothing.
     */
    protected void configReading(int offset, int size) {
    }

    /**
     * The guest wrote {@code size} bytes at {@code offset}; the write masks have been applied and
     * any BAR or command change has taken effect. Called with the configuration lock held, on a
     * CPU thread. The default does nothing.
     */
    protected void configWritten(int offset, int size) {
    }

    // ---- identity --------------------------------------------------------------------------------

    /** The model name given to the constructor. */
    public final String name() {
        return name;
    }

    public final int vendorId() {
        return getConfig(VENDOR_ID, 2);
    }

    public final int deviceId() {
        return getConfig(DEVICE_ID, 2);
    }

    /** The 24-bit class code. */
    public final int classCode() {
        return getConfig(CLASS_PROG, 3);
    }

    /** The slot on the bus (device number, function 0), or -1 before the device is in a machine. */
    public final int slot() {
        return slot;
    }

    /**
     * The real address BAR {@code index} is mapped at for the CPUs, or -1 while it is not
     * mapped (decoding disabled, not yet assigned, or outside the bridge's windows).
     */
    public final long barAddress(int index) {
        synchronized (configLock) {
            Bar b = index >= 0 && index < BARS ? bars[index] : null;
            return b == null || b.region == null ? -1 : b.region.base();
        }
    }

    // ---- interrupts and DMA ---------------------------------------------------------------------

    /**
     * Raise ({@code true}) or lower the device's interrupt pin (level-triggered, INTx). The
     * guest sees it as pending on the bridge's LSI the pin is wired to while it is raised and
     * the guest has not set INTx-disable in the command register; several devices may share an
     * LSI. Any thread. Without an interrupt pin, nothing happens.
     */
    public final void setIrq(boolean level) {
        synchronized (irqLock) {
            irqLevel = level;
            updateIrq();
        }
    }

    /** Whether the device's interrupt pin is raised ({@link #setIrq}). */
    public final boolean irqLevel() {
        synchronized (irqLock) {
            return irqLevel;
        }
    }

    private void updateIrq() {
        boolean want = irqLevel && !intxDisabled && config[INTERRUPT_PIN] != 0;
        Phb p = phb;
        if (want != irqAsserted && p != null) {
            irqAsserted = want;
            p.intx(this, want);
        }
    }

    /**
     * Read {@code len} bytes of guest memory at bus address {@code busAddress} through the host
     * bridge's TCE table, as a bus-master read. Returns false, leaving {@code dst} partly
     * written, if the guest has not enabled bus mastering or a page is not mapped for reading.
     * Any thread.
     */
    public final boolean dmaRead(long busAddress, byte[] dst, int off, int len) {
        Objects.checkFromIndexSize(off, len, dst.length);
        return dma(busAddress, dst, off, len, false);
    }

    /**
     * Write {@code len} bytes to guest memory at bus address {@code busAddress} through the host
     * bridge's TCE table. Returns false, having written a part or nothing, if the guest has not
     * enabled bus mastering or a page is not mapped for writing. Any thread.
     */
    public final boolean dmaWrite(long busAddress, byte[] src, int off, int len) {
        Objects.checkFromIndexSize(off, len, src.length);
        return dma(busAddress, src, off, len, true);
    }

    private boolean dma(long addr, byte[] b, int off, int len, boolean write) {
        Phb p = phb;
        if (p == null || !busMaster) {
            return false;
        }
        return p.dma(addr, b, off, len, write);
    }

    // ---- hooks -----------------------------------------------------------------------------------

    /**
     * The machine is reset (power-on or reboot): configuration space is back to what the
     * constructor made, the BARs are unmapped and the interrupt is lowered. Called while no CPU
     * runs; the default does nothing.
     */
    protected void reset() {
    }

    /**
     * Write the device's own state into a saved machine (configuration space, the interrupt
     * level and the BAR mappings are saved by the machine). Called while no CPU runs.
     */
    protected void saveState(DataOutput out) throws IOException {
    }

    /**
     * Read what {@link #saveState} wrote; the input ends where that output ended. Reading past
     * the end or leaving bytes unread makes the load fail. Configuration space has already been
     * restored and the BARs remapped. Called while no CPU runs.
     */
    protected void loadState(DataInput in) throws IOException {
    }

    /**
     * Add properties to the device's node under the host bridge, after the ones QEMU gives a PCI
     * device. Called whenever the machine builds its device tree; add the same properties each
     * time. The default adds nothing.
     */
    protected void deviceTree(Fdt.Node node) {
    }

    /**
     * What else must match for a saved state to load into a machine with this device in the same
     * slot, besides the ids, class, interrupt pin and BARs the machine compares: a short text,
     * e.g. the model of the device behind a transport. The default is empty.
     */
    protected String hardwareDescription() {
        return "";
    }

    // ---- the machine's side ------------------------------------------------------------------

    final void attach(Phb p, int s) {
        synchronized (configLock) {
            if (phb != null) {
                throw new IllegalStateException("PCI device " + name + " is already in a machine");
            }
            if (initialConfig == null) {
                initialConfig = config.clone();
            }
            phb = p;
            slot = s;
        }
        attached(p);
    }

    /** The bridge the device is on, or null. */
    final Phb phb() {
        return phb;
    }

    /** Called from {@link Machine#pump} (for the transports in this package). */
    void pump() {
    }

    /** The device joined a machine (for the transports in this package). */
    void attached(Phb p) {
    }

    final void detach() {
        synchronized (configLock) {
            unmapAll();
            phb = null;
        }
        synchronized (irqLock) {
            irqAsserted = false;
        }
    }

    final void machineReset() {
        synchronized (configLock) {
            unmapAll();
            System.arraycopy(initialConfig, 0, config, 0, CONFIG_SIZE);
            busMaster = false;
        }
        synchronized (irqLock) {
            irqLevel = false;
            intxDisabled = false;
            updateIrq();
        }
        reset();
    }

    private void unmapAll() {
        for (Bar b : bars) {
            if (b != null) {
                unmap(b);
            }
        }
    }

    private void unmap(Bar b) {
        if (b.region != null) {
            phb.bus().unmap(b.region);
            b.region = null;
        }
        b.pciAddr = UNMAPPED;
    }

    /** A guest configuration read; {@code off} is aligned to {@code size} (1, 2 or 4). */
    final int configRead(int off, int size) {
        synchronized (configLock) {
            configReading(off, size);
            int v = getRaw(off, size);
            if (off <= STATUS && STATUS < off + size && irqLevel()) {
                v |= STATUS_INTERRUPT << (8 * (STATUS - off));
            }
            return v;
        }
    }

    /** A guest configuration write; {@code off} is aligned to {@code size} (1, 2 or 4). */
    final void configWrite(int off, int size, int value) {
        synchronized (configLock) {
            for (int k = 0; k < size; k++) {
                int i = off + k;
                int v = (value >>> (8 * k)) & 0xff;
                config[i] = (byte) ((config[i] & ~wmask[i]) | (v & wmask[i]));
                config[i] &= (byte) ~(v & w1cmask[i]);
            }
            if (overlaps(off, size, BAR0, 4 * BARS) || overlaps(off, size, COMMAND, 1)) {
                updateMappings();
            }
            if (overlaps(off, size, COMMAND, 2)) {
                commandChanged();
            }
            configWritten(off, size);
        }
    }

    private void commandChanged() {
        int cmd = getRaw(COMMAND, 2);
        busMaster = (cmd & COMMAND_MASTER) != 0;
        synchronized (irqLock) {
            intxDisabled = (cmd & COMMAND_INTX_DISABLE) != 0;
            updateIrq();
        }
    }

    private static boolean overlaps(int off, int size, int start, int len) {
        return off < start + len && start < off + size;
    }

    /** Map every BAR where the configuration says (QEMU's pci_update_mappings). */
    private void updateMappings() {
        int cmd = getRaw(COMMAND, 2);
        for (Bar b : bars) {
            if (b == null) {
                continue;
            }
            long addr = barAddress(b, cmd);
            if (addr == b.pciAddr) {
                continue;
            }
            unmap(b);
            b.pciAddr = addr;
            if (addr != UNMAPPED) {
                b.region = phb.map(this, b.index, addr, b.size, b.io, b.handler);
            }
        }
    }

    /** The PCI address BAR {@code b} decodes, or {@link #UNMAPPED} (QEMU's pci_bar_address). */
    private long barAddress(Bar b, int cmd) {
        int off = BAR0 + 4 * b.index;
        long raw = getRaw(off, 4) & 0xffffffffL;
        if (b.io) {
            if ((cmd & COMMAND_IO) == 0) {
                return UNMAPPED;
            }
            long a = raw & ~(b.size - 1) & ~0x3L;
            long last = a + b.size - 1;
            return last <= a || last >= 0xffffffffL ? UNMAPPED : a;
        }
        if ((cmd & COMMAND_MEMORY) == 0) {
            return UNMAPPED;
        }
        if (b.bits64) {
            raw |= (getRaw(off + 4, 4) & 0xffffffffL) << 32;
        }
        long a = raw & ~(b.size - 1) & ~0xfL;
        long last = a + b.size - 1;
        if (Long.compareUnsigned(last, a) <= 0 || last == -1L) {
            return UNMAPPED;
        }
        if (!b.bits64 && last >= 0xffffffffL) {
            return UNMAPPED;
        }
        return a;
    }

    /** The BAR descriptions the hardware check compares: per register, kind and size. */
    final String barSummary(int i) {
        Bar b = bars[i];
        if (b == null) {
            return "none";
        }
        String kind = b.io ? "I/O" : (b.bits64 ? "64-bit" : "32-bit") + (b.prefetchable ? " prefetchable" : "")
                + " memory";
        return kind + " 0x" + Long.toHexString(b.size);
    }

    /** The {@code reg} entries of the device's node: config space, then each BAR. */
    final int[] regProperty(int devfnBits) {
        int n = 1;
        for (Bar b : bars) {
            if (b != null) {
                n++;
            }
        }
        int[] r = new int[5 * n];
        r[0] = devfnBits;
        int k = 5;
        for (Bar b : bars) {
            if (b == null) {
                continue;
            }
            int space = b.io ? 1 : b.bits64 ? 3 : 2;
            r[k] = devfnBits | (space << 24) | (BAR0 + 4 * b.index);
            r[k + 3] = (int) (b.size >>> 32);
            r[k + 4] = (int) b.size;
            k += 5;
        }
        return r;
    }

    /** Configuration bytes as the device tree builder reads them (no hooks). */
    final int rawConfig(int off, int size) {
        synchronized (configLock) {
            return getRaw(off, size);
        }
    }

    final void saveBase(DataOutput out) throws IOException {
        synchronized (configLock) {
            out.write(config);
        }
        synchronized (irqLock) {
            out.writeBoolean(irqLevel);
        }
    }

    /** Restore configuration space and the interrupt level, then map the BARs again. */
    final void loadBase(DataInput in) throws IOException {
        byte[] c = new byte[CONFIG_SIZE];
        in.readFully(c);
        boolean level = in.readBoolean();
        synchronized (configLock) {
            unmapAll();
            // Read-only bytes (ids, BAR types, capabilities) stay as this device has them.
            for (int i = 0; i < CONFIG_SIZE; i++) {
                int m = (wmask[i] | w1cmask[i]) & 0xff;
                config[i] = (byte) ((initialConfig[i] & ~m) | (c[i] & m));
            }
            // Bytes a device keeps current itself (setConfig) are its state: restore them as saved.
            for (int i = HEADER_SIZE; i < CONFIG_SIZE; i++) {
                if (capUsed[i]) {
                    config[i] = c[i];
                }
            }
            updateMappings();
            int cmd = getRaw(COMMAND, 2);
            busMaster = (cmd & COMMAND_MASTER) != 0;
            synchronized (irqLock) {
                irqLevel = level;
                intxDisabled = (cmd & COMMAND_INTX_DISABLE) != 0;
                irqAsserted = level && !intxDisabled && config[INTERRUPT_PIN] != 0;
            }
        }
    }

    /** Whether the pin is asserted towards the bridge (for recomputing the LSI levels on load). */
    final boolean irqAsserted() {
        synchronized (irqLock) {
            return irqAsserted;
        }
    }

    /** The byte order of every BAR. */
    static final ByteOrder ORDER = ByteOrder.LITTLE_ENDIAN;

    @Override
    public String toString() {
        return String.format("%s (%04x:%04x) in slot %d", name, rawConfig(VENDOR_ID, 2), rawConfig(DEVICE_ID, 2), slot);
    }
}
