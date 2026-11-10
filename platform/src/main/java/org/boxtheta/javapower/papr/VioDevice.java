package org.boxtheta.javapower.papr;

import org.boxtheta.javapower.core.PhysicalMemory;

/**
 * A device on the PAPR virtual I/O bus ({@code /vdevice}). Provides the pieces common to all VIO
 * devices: the unit address ({@code reg}), the interrupt source, an optional TCE table for
 * DMA, the optional CRQ (command/response queue) transport and H_VIO_SIGNAL state.
 *
 * <p>The CRQ is a ring of 16-byte entries in guest memory, addressed through the device's DMA
 * window; byte 0 of an entry is the "valid" byte, 0 meaning free.
 */
public abstract class VioDevice {
    /** First unit address QEMU assigns. */
    public static final int REG_BASE = 0x71000000;

    protected Machine machine;
    private int reg;
    private int irq;
    private TceTable tce;
    private long signalState;

    // CRQ state.
    private long crqAddr;
    private int crqSize;
    private int crqNext;

    /** Device tree node base name ("vty", "v-scsi", "nvram"). */
    public abstract String dtName();

    public abstract String dtType();

    public abstract String dtCompatible();

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

    /** Called when the guest sends a CRQ entry (H_SEND_CRQ). Returns the hcall status. */
    protected long handleCrq(byte[] crq) {
        return H.HARDWARE;
    }

    /** Device-specific reset. */
    protected void resetDevice() {
    }

    /** Add device-specific properties after the generic ones. */
    protected void addDeviceProps(Fdt.Node node) {
    }

    final void attach(Machine m, int unitAddress) {
        this.machine = m;
        this.reg = unitAddress;
        this.irq = regToIrq(unitAddress);
        if (dmaWindowSize() > 0) {
            // LIOBN of a VIO device is its unit address (QEMU's SPAPR_VIO_LIOBN).
            this.tce = new TceTable(unitAddress, 0, dmaWindowSize());
        }
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

    public final int reg() {
        return reg;
    }

    public final int irq() {
        return irq;
    }

    public final TceTable tce() {
        return tce;
    }

    public final long signalState() {
        return signalState;
    }

    final void setSignalState(long s) {
        signalState = s;
    }

    public final String nodeName() {
        return dtName() + "@" + Integer.toHexString(reg);
    }

    /** Bus reset: shut down the CRQ and TCEs, clear signal state. */
    final void reset() {
        quiesce();
        signalState = 0;
        if (tce != null) {
            tce.setBypass(false);
        }
        resetDevice();
    }

    /** RTAS quiesce: free the CRQ and reset the TCE table (QEMU's spapr_vio_quiesce_one). */
    final void quiesce() {
        if (tce != null) {
            tce.clear();
        }
        freeCrq();
    }

    // ---- interrupts --------------------------------------------------------------------------

    /** Pulse this device's (edge) interrupt. */
    protected final void pulseIrq() {
        machine.xics().pulse(irq);
    }

    // ---- DMA ---------------------------------------------------------------------------------

    private PhysicalMemory mem() {
        return machine.memory();
    }

    /** Copy guest memory at DMA address {@code ioba} into {@code buf}. Returns false on a fault. */
    public final boolean dmaRead(long ioba, byte[] buf, int off, int len) {
        PhysicalMemory m = mem();
        while (len > 0) {
            int chunk = (int) Math.min(len, TceTable.PAGE_SIZE - (ioba & ~TceTable.PAGE_MASK));
            long ra = tce == null ? ioba : tce.translate(ioba, false);
            if (ra < 0 || !m.contains(ra, chunk)) {
                PaprLog.once("vio-dma-read-fault", String.format("%s ioba 0x%x", nodeName(), ioba));
                return false;
            }
            m.read(ra, buf, off, chunk);
            ioba += chunk;
            off += chunk;
            len -= chunk;
        }
        return true;
    }

    /** Copy {@code buf} to guest memory at DMA address {@code ioba}. Returns false on a fault. */
    public final boolean dmaWrite(long ioba, byte[] buf, int off, int len) {
        PhysicalMemory m = mem();
        // Check the whole range first so a faulting transfer writes nothing beyond the fault page.
        while (len > 0) {
            int chunk = (int) Math.min(len, TceTable.PAGE_SIZE - (ioba & ~TceTable.PAGE_MASK));
            long ra = tce == null ? ioba : tce.translate(ioba, true);
            if (ra < 0 || !m.contains(ra, chunk)) {
                PaprLog.once("vio-dma-write-fault", String.format("%s ioba 0x%x", nodeName(), ioba));
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

    public final boolean crqRegistered() {
        return crqSize != 0;
    }

    public final long crqAddr() {
        return crqAddr;
    }

    public final int crqSize() {
        return crqSize;
    }

    long registerCrq(long addr, long len) {
        if (len < 0x1000 || len > 0x10000000) {
            PaprLog.once("crq-bad-size", String.format("%s size 0x%x", nodeName(), len));
            return H.PARAMETER;
        }
        if ((addr & 0xfff) != 0) {
            PaprLog.once("crq-unaligned", String.format("%s addr 0x%x", nodeName(), addr));
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
     * queue is full, negative on error. Pulses the interrupt if enabled via H_VIO_SIGNAL.
     */
    protected final int sendCrq(byte[] crq) {
        if (crqSize == 0) {
            PaprLog.once("crq-send-uninitialized", nodeName());
            return -1;
        }
        byte[] valid = new byte[1];
        if (!dmaRead(crqAddr + crqNext, valid, 0, 1)) {
            return (int) H.DEST_PARM;
        }
        if (valid[0] != 0) {
            PaprLog.once("crq-full", nodeName());
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
