package org.boxtheta.javapower.papr;

import org.boxtheta.javapower.core.PhysicalMemory;

/**
 * A device on the PAPR virtual I/O bus ({@code /vdevice}). Provides the pieces common to all VIO
 * devices: the unit address ({@code reg}), the interrupt source, an optional TCE table for
 * DMA, the optional CRQ (command/response queue) transport and H_VIO_SIGNAL state.
 *
 * <p>The CRQ is a ring of 16-byte entries in guest memory, addressed through the device's DMA
 * window; byte 0 of an entry is the "valid" byte, 0 meaning free.
 *
 * <h2>Devices of your own</h2>
 * Embedders add devices with {@link MachineConfig#addVioDevice}; the machine then gives each one
 * a unit address, an interrupt source, a TCE table if {@link #dmaWindowSize} is not 0, and a
 * node under {@code /vdevice}, exactly as for the built-in devices. A subclass outside this
 * package works with these members, which are supported API:
 * <ul>
 * <li>Description: {@link #dtName}, {@link #dtType}, {@link #dtCompatible}, {@link #dmaWindowSize},
 *     {@link #signalMask}, {@link #supportsCrq}, {@link #addDeviceProps}, {@link #hardwareIdentity}.
 *     The machine reads them while it is built and whenever it builds a device tree, so they must
 *     return the same values for the device's whole life.</li>
 * <li>The guest's way in: the CRQ. The guest registers a queue (H_REG_CRQ), sends 16-byte entries
 *     to the device (H_SEND_CRQ, which calls {@link #handleCrq}) and receives entries the device
 *     posts with {@link #sendCrq}; bulk data moves with {@link #dmaRead} and {@link #dmaWrite}
 *     through the TCE table the guest maps (H_PUT_TCE). This is the transport of PAPR's own
 *     CRQ devices (v-scsi, vfc, vtpm, vmc), so a guest driver for a new device can follow
 *     theirs.</li>
 * <li>Interrupts: {@link #pulseIrq}; {@link #sendCrq} pulses by itself when the guest enabled
 *     interrupts with H_VIO_SIGNAL ({@link #signalState} bit 0).</li>
 * <li>Life cycle: {@link #startDevice}, {@link #resetDevice}, {@link #quiesceDevice},
 *     {@link #closeDevice}; saved state: {@link #saveDevice}, {@link #loadDevice}.</li>
 * <li>{@link #log()}, {@link #lock()}, {@link #reg()}, {@link #irq()}, {@link #nodeName()},
 *     {@link #crqRegistered()}.</li>
 * </ul>
 * Hypercalls other than the CRQ, TCE and H_VIO_SIGNAL ones are not routed to devices: the PAPR
 * hypercall numbers belong to the platform, and a guest has drivers only for the devices PAPR
 * defines. A device the guest already has a driver for that uses its own hypercalls (vty, the
 * virtual Ethernet adapter) is a javapower device.
 *
 * <h2>Threading</h2>
 * <ul>
 * <li>{@link #handleCrq} runs on the thread of the guest CPU that made H_SEND_CRQ, with the
 *     device lock ({@link #lock()}) held and the machine lock not held. CPUs on other threads may
 *     be inside the hypervisor at the same time, but never inside this device: everything the
 *     machine calls on a device, except {@link #startDevice} and {@link #closeDevice}, holds the
 *     device lock.</li>
 * <li>{@link #resetDevice} and {@link #quiesceDevice} run with the device lock held: at a machine
 *     reset (on the thread calling {@link Machine#reset}, no CPU running) and, for quiesce, also
 *     on a CPU thread when the guest calls the quiesce RTAS service (SLOF does before it starts
 *     the operating system).</li>
 * <li>{@link #addDeviceProps} runs with the machine lock held, at a reset and when the guest
 *     negotiates its features with the platform (on that CPU's thread).</li>
 * <li>{@link #saveDevice} and {@link #loadDevice} run with the machine and device locks held, on
 *     the thread saving or loading the machine, with no CPU running.</li>
 * <li>{@link #startDevice} runs once, on the thread constructing the machine, when the machine
 *     is complete; {@link #closeDevice} runs once, on the thread closing the machine (or
 *     constructing it, if construction fails), with no CPU running.</li>
 * <li>A device may also act from threads of its own (host input arriving, a timer): hold
 *     {@link #lock()} around {@link #sendCrq} and any change to the device's state, as the
 *     machine does. {@link #dmaRead}, {@link #dmaWrite} and {@link #pulseIrq} are safe from any
 *     thread. Such work must not touch guest memory or device state while the machine is being
 *     saved or loaded, since guest RAM and the devices are written at different moments: buffer
 *     host input in the device (and its saved state) or pause it around
 *     {@code saveState} and {@code loadState}.</li>
 * </ul>
 *
 * <p>A device instance belongs to one machine: attaching it to a second machine fails.
 */
public abstract class VioDevice {
    /** First unit address QEMU assigns. */
    public static final int REG_BASE = 0x71000000;

    /** Hypercall status for {@link #handleCrq}: done. */
    public static final long H_SUCCESS = H.SUCCESS;
    /** Hypercall status: the device is busy, try again. */
    public static final long H_BUSY = H.BUSY;
    /** Hypercall status: the device's partner is gone (the connection is closed). */
    public static final long H_CLOSED = H.CLOSED;
    /** Hypercall status: the device failed. */
    public static final long H_HARDWARE = H.HARDWARE;
    /** Hypercall status: an argument is invalid. */
    public static final long H_PARAMETER = H.PARAMETER;
    /** Hypercall status: the entry was dropped. */
    public static final long H_DROPPED = H.DROPPED;
    /** Hypercall status: the device lacks a resource for the request. */
    public static final long H_RESOURCE = H.RESOURCE;

    private Machine machine;
    private final Object lock = new Object();
    private PaprLog log = new PaprLog();
    private int reg;
    private int irq;
    private TceTable tce;
    private long signalState;

    // CRQ state.
    private long crqAddr;
    private int crqSize;
    private int crqNext;

    /**
     * Device tree node base name ("vty", "v-scsi", "nvram"); the node is
     * {@code /vdevice/<name>@<unit address>}.
     */
    public abstract String dtName();

    /** The node's {@code device_type}. */
    public abstract String dtType();

    /** The node's {@code compatible} string, which the guest matches its driver against. */
    public abstract String dtCompatible();

    /**
     * Anything else about this device that a saved state must agree on, beyond what the machine
     * compares itself (node name and unit address, type, compatible string, DMA window size).
     * Return a short readable text, for example {@code "ports=4 mtu=1500"}: when a state is loaded,
     * a different value makes {@code loadState} refuse it with a {@code StateMismatchException}
     * that quotes both texts. Called by the machine when it saves or loads a state. The default is
     * empty: nothing beyond the generic description.
     */
    public String hardwareIdentity() {
        return "";
    }

    /** Size of the DMA window in bytes, or 0 for devices without DMA. */
    public long dmaWindowSize() {
        return 0;
    }

    /** Bits of H_VIO_SIGNAL's mode argument this device accepts. */
    public long signalMask() {
        return 0;
    }

    /** Whether this device uses a CRQ. */
    public boolean supportsCrq() {
        return false;
    }

    /**
     * Called when the guest sends a CRQ entry (H_SEND_CRQ), only if {@link #supportsCrq}. The
     * entry is the 16 bytes of the call's two doubleword arguments, big-endian. Returns the
     * hypercall status ({@link #H_SUCCESS}, {@link #H_PARAMETER}, ...), which the guest sees in
     * r3. Runs on the calling CPU's thread with the device lock held; replies posted with
     * {@link #sendCrq} from here reach the guest before the hypercall returns.
     */
    protected long handleCrq(byte[] crq) {
        return H.HARDWARE;
    }

    /**
     * Device-specific reset, at every machine reset (power-on and reboot), after the CRQ, the TCE
     * table and the signal state were cleared and {@link #quiesceDevice} was called. Runs with the
     * device lock held and no CPU running.
     */
    protected void resetDevice() {
    }

    /**
     * Called when the guest quiesces the VIO devices (RTAS {@code quiesce}) and at every machine
     * reset, after the machine freed the CRQ and cleared the TCE table: forget anything that
     * refers to guest memory. Runs with the device lock held.
     */
    protected void quiesceDevice() {
    }

    /**
     * Called once when the machine has been built, before it runs: start threads or host
     * resources here rather than in the constructor. Runs on the thread constructing the
     * machine, without the device lock. If it throws, the machine's construction fails and every
     * device is closed.
     */
    protected void startDevice() {
    }

    /**
     * Release the device's host resources (threads, files, sockets). Called once, when the machine
     * is closed or when its construction fails after this device was attached (whether or not
     * {@link #startDevice} ran), with no CPU running and without the device lock. The machine
     * closes every device even if one fails; the first failure is what {@link Machine#close}
     * throws.
     */
    protected void closeDevice() throws java.io.IOException {
    }

    /**
     * Add device-specific properties to this device's node. The machine then sets the generic
     * ones ({@code ibm,my-dma-window} and its cell sizes, {@code interrupts}, {@code compatible},
     * {@code device_type}, {@code reg}), which replace properties of the same names. Called with
     * the machine lock held whenever a device tree is built (at reset, and when the guest
     * negotiates its features), so it must add the same properties every time.
     */
    protected void addDeviceProps(Fdt.Node node) {
    }

    final void attach(Machine m, int unitAddress) {
        if (machine != null) {
            throw new IllegalStateException(getClass().getName() + " " + nodeName()
                    + " is already attached to a machine; a VioDevice belongs to one machine, so the"
                    + " factory given to MachineConfig.addVioDevice must create a new one each time");
        }
        this.machine = m;
        this.log = m.log();
        this.reg = unitAddress;
        this.irq = regToIrq(unitAddress);
        if (dmaWindowSize() > 0) {
            // LIOBN of a VIO device is its unit address (QEMU's SPAPR_VIO_LIOBN).
            this.tce = new TceTable(unitAddress, 0, dmaWindowSize(), log);
        }
    }

    /** The machine this device is attached to, or null before. */
    final Machine machine() {
        return machine;
    }

    /**
     * The machine's log once attached (messages go to {@link MachineConfig#logSink}); before that,
     * a log of the device's own. Safe from any thread.
     */
    protected final PaprLog log() {
        return log;
    }

    /** QEMU's spapr_vio_reg_to_irq for the address ranges it allocates. */
    static int regToIrq(int reg) {
        int idx;
        if (Integer.compareUnsigned(reg, REG_BASE) >= 0) {
            idx = reg & 0xff;
        } else if (Integer.compareUnsigned(reg, 0x30000000) >= 0) {
            idx = 0xf0 | ((reg >>> 12) & 0xf);
        } else {
            idx = (reg >>> 12) & 0xff;
        }
        return Xics.IRQ_VIO | idx;
    }

    /**
     * The device lock (see the class comment): the machine holds it around every call into the
     * device, and threads of the device's own hold it around {@link #sendCrq} and changes to the
     * device's state.
     */
    protected final Object lock() {
        return lock;
    }

    /** The unit address ({@code reg}), assigned when the device is attached. */
    public final int reg() {
        return reg;
    }

    /** The interrupt source number, assigned when the device is attached. */
    public final int irq() {
        return irq;
    }

    /** The device's TCE table (the guest's mapping of its DMA window), or null without DMA. */
    public final TceTable tce() {
        return tce;
    }

    /** The mode the guest last set with H_VIO_SIGNAL: bit 0 enables the device's interrupt. */
    public final long signalState() {
        return signalState;
    }

    final void setSignalState(long s) {
        signalState = s;
    }

    /** The node's name under {@code /vdevice}: {@code <dtName>@<unit address in hex>}. */
    public final String nodeName() {
        return dtName() + "@" + Integer.toHexString(reg);
    }

    /** Bus reset: shut down the CRQ and TCEs, clear signal state. */
    final void reset() {
        synchronized (lock) {
            quiesce();
            signalState = 0;
            if (tce != null) {
                tce.setBypass(false);
            }
            resetDevice();
        }
    }

    /** RTAS quiesce: free the CRQ and reset the TCE table (QEMU's spapr_vio_quiesce_one). */
    final void quiesce() {
        synchronized (lock) {
            if (tce != null) {
                tce.clear();
            }
            freeCrq();
            quiesceDevice();
        }
    }

    // ---- saved state ----------------------------------------------------------------------------

    /** Write the bus state (signal state, CRQ, TCE table) and then the device's own state. */
    final void saveState(java.io.DataOutput out) throws java.io.IOException {
        synchronized (lock) {
            out.writeInt(reg);
            out.writeLong(signalState);
            out.writeLong(crqAddr);
            out.writeInt(crqSize);
            out.writeInt(crqNext);
            out.writeBoolean(tce != null);
            if (tce != null) {
                tce.saveState(out);
            }
            saveDevice(out);
        }
    }

    /** Read the state written by {@link #saveState}. */
    final void loadState(org.boxtheta.javapower.core.StateInput.Record in) throws java.io.IOException {
        synchronized (lock) {
            int r = in.readInt();
            if (r != reg) {
                throw in.corrupt(String.format("VIO device 0x%x where %s was expected", r, nodeName()));
            }
            long sig = in.readLong();
            long addr = in.readLong();
            int size = in.readInt();
            int next = in.readInt();
            if (size < 0 || next < 0 || (size == 0 ? next != 0 : next >= size || next % 16 != 0)) {
                throw in.corrupt(nodeName() + " CRQ of " + size + " bytes at offset " + next);
            }
            if (in.readBoolean() != (tce != null)) {
                throw in.corrupt(nodeName() + " TCE table presence");
            }
            if (tce != null) {
                tce.loadState(in);
            }
            signalState = sig;
            crqAddr = addr;
            crqSize = size;
            crqNext = next;
            loadDevice(in);
        }
    }

    /**
     * Write the device-specific part of the saved state: everything the guest can observe that is
     * not in guest memory (registers, queued input, ...). Called with the machine and device
     * locks held, no CPU running. The machine has already written the generic state (CRQ, TCE
     * table, signal state).
     */
    protected void saveDevice(java.io.DataOutput out) throws java.io.IOException {
    }

    /**
     * Read what {@link #saveDevice} wrote, exactly to its end, and replace the device's state with
     * it. Called with the machine and device locks held, no CPU running, after the hardware check
     * passed (so the device has the same description and {@link #hardwareIdentity} as the one
     * that saved it). Report corrupt data with {@code in.corrupt(...)}.
     */
    protected void loadDevice(org.boxtheta.javapower.core.StateInput.Record in) throws java.io.IOException {
    }

    // ---- interrupts --------------------------------------------------------------------------

    /**
     * Pulse this device's (edge-triggered) interrupt, whatever the H_VIO_SIGNAL state; most
     * devices only interrupt when {@code (signalState() & 1) != 0}. Safe from any thread.
     */
    protected final void pulseIrq() {
        machine.xics().pulse(irq);
    }

    // ---- DMA ---------------------------------------------------------------------------------

    private PhysicalMemory mem() {
        return machine.memory();
    }

    /**
     * Copy guest memory at DMA address {@code ioba} into {@code buf}, translating through the TCE
     * table (or real addresses for a device without one). Returns false on a fault (an unmapped
     * page or one the guest did not allow the device to read). Safe from any thread.
     */
    public final boolean dmaRead(long ioba, byte[] buf, int off, int len) {
        PhysicalMemory m = mem();
        while (len > 0) {
            int chunk = (int) Math.min(len, TceTable.PAGE_SIZE - (ioba & ~TceTable.PAGE_MASK));
            long ra = tce == null ? ioba : tce.translate(ioba, false);
            if (ra < 0 || !m.contains(ra, chunk)) {
                log.once("vio-dma-read-fault", String.format("%s ioba 0x%x", nodeName(), ioba));
                return false;
            }
            m.read(ra, buf, off, chunk);
            ioba += chunk;
            off += chunk;
            len -= chunk;
        }
        return true;
    }

    /**
     * Copy {@code buf} to guest memory at DMA address {@code ioba}. Returns false on a fault; the
     * pages before the faulting one have then been written. Safe from any thread.
     */
    public final boolean dmaWrite(long ioba, byte[] buf, int off, int len) {
        PhysicalMemory m = mem();
        // Check the whole range first so a faulting transfer writes nothing beyond the fault page.
        while (len > 0) {
            int chunk = (int) Math.min(len, TceTable.PAGE_SIZE - (ioba & ~TceTable.PAGE_MASK));
            long ra = tce == null ? ioba : tce.translate(ioba, true);
            if (ra < 0 || !m.contains(ra, chunk)) {
                log.once("vio-dma-write-fault", String.format("%s ioba 0x%x", nodeName(), ioba));
                return false;
            }
            m.write(ra, buf, off, chunk);
            ioba += chunk;
            off += chunk;
            len -= chunk;
        }
        return true;
    }

    /** Fill {@code len} bytes at {@code ioba} with {@code value}. */
    public final boolean dmaFill(long ioba, int value, int len) {
        byte[] b = new byte[len];
        java.util.Arrays.fill(b, (byte) value);
        return dmaWrite(ioba, b, 0, len);
    }

    // ---- CRQ ---------------------------------------------------------------------------------

    /** Whether the guest has registered a CRQ (H_REG_CRQ) that is not freed yet. */
    public final boolean crqRegistered() {
        return crqSize != 0;
    }

    /** DMA address of the registered CRQ, 0 if none. */
    public final long crqAddr() {
        return crqAddr;
    }

    /** Size of the registered CRQ in bytes, 0 if none. */
    public final int crqSize() {
        return crqSize;
    }

    final long registerCrq(long addr, long len) {
        if (len < 0x1000 || len > 0x10000000) {
            log.once("crq-bad-size", String.format("%s size 0x%x", nodeName(), len));
            return H.PARAMETER;
        }
        if ((addr & 0xfff) != 0) {
            log.once("crq-unaligned", String.format("%s addr 0x%x", nodeName(), addr));
            return H.PARAMETER;
        }
        if (!supportsCrq()) {
            return H.NOT_FOUND;
        }
        if (crqSize != 0) {
            return H.RESOURCE;
        }
        crqAddr = addr;
        crqSize = (int) len;
        crqNext = 0;
        return H.SUCCESS;
    }

    final long freeCrq() {
        crqAddr = 0;
        crqSize = 0;
        crqNext = 0;
        return H.SUCCESS;
    }

    /**
     * Post a 16-byte entry to the guest's CRQ (spapr_vio_send_crq): the second doubleword is
     * written first, then the first (which holds the valid byte). Returns 0 on success, 1 if the
     * queue is full, negative on error (no CRQ registered, or a DMA fault). Pulses the interrupt
     * if enabled via H_VIO_SIGNAL. Call it with the device lock held: from {@link #handleCrq}
     * (which has it) or inside {@code synchronized (lock())} on a thread of the device's own.
     */
    protected final int sendCrq(byte[] crq) {
        if (crqSize == 0) {
            log.once("crq-send-uninitialized", nodeName());
            return -1;
        }
        byte[] valid = new byte[1];
        if (!dmaRead(crqAddr + crqNext, valid, 0, 1)) {
            return (int) H.DEST_PARM;
        }
        if (valid[0] != 0) {
            log.once("crq-full", nodeName());
            return 1;
        }
        if (!dmaWrite(crqAddr + crqNext + 8, crq, 8, 8)) {
            return (int) H.DEST_PARM;
        }
        if (!dmaWrite(crqAddr + crqNext, crq, 0, 8)) {
            return (int) H.DEST_PARM;
        }
        crqNext = (crqNext + 16) % crqSize;
        if ((signalState & 1) != 0) {
            pulseIrq();
        }
        return 0;
    }
}
