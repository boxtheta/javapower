package org.boxtheta.javapower.papr;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import org.boxtheta.javapower.core.MmioHandler;
import org.boxtheta.javapower.core.StateFormatException;

/**
 * The virtio PCI transport (virtio 1.1, 4.1), as QEMU's virtio-*-pci devices present it on
 * this bridge with {@code vectors=0}. The modern interface is one 16 KiB 64-bit prefetchable
 * memory BAR (BAR 4) holding the common configuration (offset 0), the ISR status (0x1000), the
 * device configuration (0x2000) and the notification registers (0x3000, 4 bytes per queue),
 * described by vendor-specific capabilities, plus the PCI configuration access capability.
 * Interrupt pin INTA, vendor 0x1af4.
 *
 * <p>Two forms, as in QEMU:
 * <ul>
 * <li><b>Modern</b> ({@code disable-legacy=on}): device 0x1040 plus the device type, revision 1,
 *     subsystem 0x1af4:0x1100. Linux's driver and FreeBSD's modern driver use it; FreeBSD's on a
 *     big-endian kernel does not work with it (it byte-swaps the registers twice, under QEMU
 *     too).</li>
 * <li><b>Transitional</b>, QEMU's default on a conventional PCI bus for the device types that
 *     have a legacy interface (network, block, balloon, console, SCSI, entropy, 9P): the legacy
 *     device id (0x1000 to 0x103f), revision 0, subsystem id the device type, and the legacy
 *     registers in an I/O BAR 0 as well. Linux still uses the modern interface; FreeBSD uses the
 *     legacy one, on either byte order. Legacy rings are in the guest's byte order, taken from
 *     the CPU that resets the device, as QEMU does.</li>
 * </ul>
 *
 * <p>Interrupts are the INTx pin (an LSI of the host bridge): the transport raises it when it
 * sets a bit of the ISR status and lowers it when the driver reads the ISR, which clears it.
 * There is no MSI-X; the drivers of Linux and FreeBSD fall back to INTx, as with QEMU's
 * {@code vectors=0}. The transport offers {@link VirtioDevice#F_VERSION_1},
 * {@link VirtioDevice#F_INDIRECT_DESC} and {@link VirtioDevice#F_EVENT_IDX}, and not
 * VIRTIO_F_ACCESS_PLATFORM: ring and buffer addresses are guest real addresses (QEMU's default,
 * which the stock drivers expect on pseries), not bus addresses through the TCE table.
 *
 * <p>See {@link VirtioDevice} for what runs on which thread.
 */
public final class VirtioPciDevice extends PciDevice {
    /** Most queues a device may have: the notification area holds 1024. */
    public static final int MAX_QUEUES = 1024;
    public static final int VENDOR = 0x1af4;
    public static final int DEVICE_BASE = 0x1040;
    public static final int SUBSYSTEM_ID = 0x1100;
    /** The BAR holding the virtio structures. */
    public static final int BAR = 4;
    /** The I/O BAR of a transitional device's legacy registers. */
    public static final int LEGACY_BAR = 0;
    /** Legacy register offsets; the device configuration follows them. */
    static final int L_HOST_FEATURES = 0x00;
    static final int L_GUEST_FEATURES = 0x04;
    static final int L_QUEUE_PFN = 0x08;
    static final int L_QUEUE_NUM = 0x0c;
    static final int L_QUEUE_SEL = 0x0e;
    static final int L_QUEUE_NOTIFY = 0x10;
    static final int L_STATUS = 0x12;
    static final int L_ISR = 0x13;
    static final int L_CONFIG = 0x14;
    static final long BAR_SIZE = 0x4000;

    static final int COMMON = 0x0000;
    static final int ISR = 0x1000;
    static final int DEVICE = 0x2000;
    static final int NOTIFY = 0x3000;
    static final int NOTIFY_MULT = 4;

    static final int ISR_QUEUE = 1;
    static final int ISR_CONFIG = 2;
    static final int NO_VECTOR = 0xffff;

    // Capability configuration types.
    static final int CAP_COMMON = 1;
    static final int CAP_NOTIFY = 2;
    static final int CAP_ISR = 3;
    static final int CAP_DEVICE = 4;
    static final int CAP_PCI_CFG = 5;

    private static final long TRANSPORT_OFFER = (1L << VirtioDevice.F_VERSION_1)
            | (1L << VirtioDevice.F_INDIRECT_DESC) | (1L << VirtioDevice.F_EVENT_IDX);

    private final VirtioDevice dev;
    private final Object lock = new Object();
    private final Virtqueue[] queues;
    private final long offered;
    private final int pciCfgCap;
    private final boolean legacy;

    // Transport state, guarded by lock.
    private int status;
    private int deviceFeatureSelect;
    private int driverFeatureSelect;
    private long driverFeatures;
    private long negotiated;
    private int queueSelect;
    private int configGeneration;
    private int isr;
    /** The driver uses the legacy registers (no FEATURES_OK step). */
    private boolean legacyDriver;
    /** Byte order of legacy rings: that of the CPU that last reset the device. */
    private boolean legacyBig = true;

    /**
     * The device on the transport as QEMU makes it by default on this bus: transitional if its
     * type has a legacy interface ({@link #hasLegacy}), modern otherwise; with the class code
     * QEMU gives its type.
     *
     * @throws IllegalStateException if {@code device} is already on a transport
     */
    public VirtioPciDevice(VirtioDevice device) {
        this(device, hasLegacy(device.deviceType()));
    }

    /**
     * The device on the transport, transitional ({@code legacy}) or modern only, with the class
     * code QEMU gives its type.
     *
     * @throws IllegalArgumentException if {@code legacy} is asked for a type without a legacy
     *     interface
     * @throws IllegalStateException if {@code device} is already on a transport
     */
    public VirtioPciDevice(VirtioDevice device, boolean legacy) {
        this(device, legacy, defaultClass(device.deviceType()));
    }

    /**
     * The device on the transport, transitional ({@code legacy}) or modern only, with the given
     * 24-bit PCI class code.
     *
     * @throws IllegalArgumentException if {@code legacy} is asked for a type without a legacy
     *     interface, or its configuration does not fit the legacy I/O BAR
     * @throws IllegalStateException if {@code device} is already on a transport
     */
    public VirtioPciDevice(VirtioDevice device, boolean legacy, int classCode) {
        super(modelName(device.deviceType()), VENDOR, deviceId(device.deviceType(), legacy), legacy ? 0 : 1,
                classCode);
        this.dev = Objects.requireNonNull(device, "device");
        this.legacy = legacy;
        if (device.transport != null) {
            throw new IllegalStateException("the virtio device is already on a transport");
        }
        if (device.configSize() < 0 || device.configSize() > 0x1000) {
            throw new IllegalArgumentException("device configuration of " + device.configSize() + " bytes");
        }
        long devFeatures = device.deviceFeatures();
        if ((devFeatures & VirtioDevice.TRANSPORT_FEATURES) != 0) {
            throw new IllegalArgumentException(String.format("the device offers transport feature bits 0x%x",
                    devFeatures & VirtioDevice.TRANSPORT_FEATURES));
        }
        this.offered = devFeatures | TRANSPORT_OFFER;
        queues = new Virtqueue[device.queueCount()];
        for (int i = 0; i < queues.length; i++) {
            queues[i] = new Virtqueue(this, lock, i, device.maxQueueSize(i));
        }
        setSubsystem(VENDOR, legacy ? device.deviceType() : SUBSYSTEM_ID);
        setInterruptPin(1);
        if (legacy) {
            int size = Integer.highestOneBit(L_CONFIG + device.configSize() - 1) << 1;
            if (size > 256) {
                throw new IllegalArgumentException("a legacy interface holds at most " + (256 - L_CONFIG)
                        + " bytes of device configuration");
            }
            addIoBar(LEGACY_BAR, size, new LegacyRegs());
        }
        addMemoryBar(BAR, BAR_SIZE, true, true, new Regs());
        // Capabilities in QEMU's order; each new one goes to the head of the list.
        vendorCap(CAP_COMMON, COMMON, 0x1000, 16);
        vendorCap(CAP_ISR, ISR, 0x1000, 16);
        vendorCap(CAP_DEVICE, DEVICE, 0x1000, 16);
        int notify = vendorCap(CAP_NOTIFY, NOTIFY, NOTIFY_MULT * MAX_QUEUES, 20);
        setConfig32(notify + 16, NOTIFY_MULT);
        pciCfgCap = vendorCap(CAP_PCI_CFG, 0, 0, 20);
        setWriteMask(pciCfgCap + 4, 1, 0xff);
        setWriteMask(pciCfgCap + 8, 4, -1);
        setWriteMask(pciCfgCap + 12, 4, -1);
        setWriteMask(pciCfgCap + 16, 4, -1);
        device.transport = this;
    }

    private int vendorCap(int type, int offset, int length, int capLen) {
        int c = addCapability(0x09, capLen);
        setConfig8(c + 2, capLen);
        setConfig8(c + 3, type);
        setConfig8(c + 4, type == CAP_PCI_CFG ? 0 : BAR);
        setConfig32(c + 8, offset);
        setConfig32(c + 12, length);
        return c;
    }

    /** The legacy device id of a type, or -1 if it has no legacy interface. */
    private static int legacyId(int type) {
        return switch (type) {
            case 1 -> 0x1000;
            case 2 -> 0x1001;
            case 5 -> 0x1002;
            case 3 -> 0x1003;
            case 8 -> 0x1004;
            case 4 -> 0x1005;
            case 9 -> 0x1009;
            default -> -1;
        };
    }

    /**
     * Whether a device type has a legacy (virtio 0.9) PCI interface, so that its device can be
     * transitional: network (1), block (2), console (3), entropy (4), balloon (5), SCSI (8), 9P
     * (9). QEMU makes such devices transitional by default on a conventional PCI bus.
     */
    public static boolean hasLegacy(int deviceType) {
        return legacyId(deviceType) >= 0;
    }

    private static int deviceId(int type, boolean legacy) {
        if (!legacy) {
            return DEVICE_BASE + type;
        }
        if (legacyId(type) < 0) {
            throw new IllegalArgumentException("virtio device type " + type + " has no legacy interface");
        }
        return legacyId(type);
    }

    /** Whether the device is transitional (has the legacy interface too). */
    public boolean isTransitional() {
        return legacy;
    }

    static int defaultClass(int type) {
        return switch (type) {
            case 1 -> 0x020000;  // network: ethernet
            case 2 -> 0x010000;  // block: SCSI storage, as QEMU's virtio-blk-pci
            case 3 -> 0x078000;  // console: other communication controller
            case 25 -> 0x040100; // sound: audio
            default -> 0x00ff00; // QEMU's PCI_CLASS_OTHERS
        };
    }

    static String modelName(int type) {
        String t = switch (type) {
            case 1 -> "net";
            case 2 -> "blk";
            case 3 -> "serial";
            case 4 -> "rng";
            case 5 -> "balloon";
            case 8 -> "scsi";
            case 9 -> "9p";
            case 16 -> "gpu";
            case 18 -> "input";
            case 19 -> "vsock";
            case 25 -> "sound";
            default -> "type" + type;
        };
        return "virtio-" + t + "-pci";
    }

    /** The device-specific part. */
    public VirtioDevice device() {
        return dev;
    }

    @Override
    void attached(Phb p) {
        synchronized (lock) {
            for (Virtqueue q : queues) {
                q.setMemory(p.memory());
            }
        }
    }

    // ---- state shared with the queues and the device ------------------------------------------

    Virtqueue queue(int i) {
        return queues[i];
    }

    long negotiatedFeatures() {
        synchronized (lock) {
            return negotiated;
        }
    }

    int status() {
        synchronized (lock) {
            return status;
        }
    }

    /**
     * Whether the queues may be used: features negotiated (or a legacy driver), no reset
     * pending. Under the lock.
     */
    boolean usable() {
        return ((status & VirtioDevice.STATUS_FEATURES_OK) != 0 || legacyDriver)
                && (status & (VirtioDevice.STATUS_DEVICE_NEEDS_RESET | VirtioDevice.STATUS_FAILED)) == 0;
    }

    /**
     * Whether the rings are big-endian: without VERSION_1 they are in the legacy byte order, as
     * in QEMU. Under the lock.
     */
    boolean ringsBigEndian() {
        return (negotiated & (1L << VirtioDevice.F_VERSION_1)) == 0 && legacyBig;
    }

    /** Set {@code bits} in the ISR status and raise the interrupt. */
    void interrupt(int bits) {
        synchronized (lock) {
            isr |= bits;
            setIrq(true);
        }
    }

    void configChanged() {
        synchronized (lock) {
            configGeneration = (configGeneration + 1) & 0xff;
            if ((status & VirtioDevice.STATUS_DRIVER_OK) != 0) {
                interrupt(ISR_CONFIG);
            }
        }
    }

    void needsReset() {
        synchronized (lock) {
            if ((status & VirtioDevice.STATUS_DEVICE_NEEDS_RESET) == 0 && status != 0) {
                status |= VirtioDevice.STATUS_DEVICE_NEEDS_RESET;
                interrupt(ISR_CONFIG);
            }
        }
    }

    /** A queue met a malformed request: the device needs a reset. Under the lock. */
    void brokenQueue(Virtqueue q, String why) {
        Phb p = phb();
        if (p != null) {
            p.log().once("virtio-broken-" + slot(), this + ": " + q + ": " + why + "; the device needs a reset");
        }
        needsReset();
    }

    /** Set by a load when requests saved in flight wait to be popped again. Guarded by lock. */
    private boolean announce;

    @Override
    void pump() {
        java.util.List<Virtqueue> kick = new java.util.ArrayList<>();
        synchronized (lock) {
            if (!announce) {
                return;
            }
            announce = false;
            for (Virtqueue q : queues) {
                if (q.hasRedelivery() && q.enabled && usable()) {
                    kick.add(q);
                }
            }
        }
        for (Virtqueue q : kick) {
            dev.queueNotified(q);
        }
    }

    // ---- device reset ----------------------------------------------------------------------------

    /**
     * Reset the transport and its queues (status 0). Legacy rings take the byte order of the CPU
     * on this thread when the driver resets the device, big-endian at a machine reset (QEMU's
     * default when no CPU makes the reset). Returns with the lock released.
     */
    private void resetTransport(boolean machineReset) {
        Phb p = phb();
        boolean big = machineReset || p == null || p.currentCpuBigEndian();
        synchronized (lock) {
            legacyBig = big;
            legacyDriver = false;
            status = 0;
            deviceFeatureSelect = 0;
            driverFeatureSelect = 0;
            driverFeatures = 0;
            negotiated = 0;
            queueSelect = 0;
            isr = 0;
            announce = false;
            for (Virtqueue q : queues) {
                q.reset();
            }
            setIrq(false);
        }
    }

    @Override
    protected void reset() {
        resetTransport(true);
        dev.reset();
    }

    // ---- the BAR ---------------------------------------------------------------------------------

    /** The registers of BAR 4. Called on CPU threads; device hooks run without the lock. */
    private final class Regs implements MmioHandler {
        @Override
        public long read(long offset, int size) {
            int off = (int) offset;
            if (off < ISR) {
                return commonRead(off, size);
            } else if (off < DEVICE) {
                if (off != ISR) {
                    return 0;
                }
                synchronized (lock) {
                    int v = isr;
                    isr = 0;
                    setIrq(false);
                    return v;
                }
            } else if (off < NOTIFY) {
                int o = off - DEVICE;
                if (o + size > dev.configSize()) {
                    return 0;
                }
                return dev.readConfig(o, size);
            }
            return 0;
        }

        @Override
        public void write(long offset, int size, long value) {
            int off = (int) offset;
            if (off < ISR) {
                commonWrite(off, size, value);
            } else if (off >= DEVICE && off < NOTIFY) {
                int o = off - DEVICE;
                if (o + size <= dev.configSize()) {
                    dev.writeConfig(o, size, value);
                }
            } else if (off >= NOTIFY) {
                int q = (off - NOTIFY) / NOTIFY_MULT;
                if (q < queues.length && queues[q].isReady()) {
                    dev.queueNotified(queues[q]);
                }
            }
        }
    }

    /**
     * The legacy registers in I/O BAR 0 of a transitional device (virtio 1.1, 4.1.4.8): host and
     * guest features (bits 0 to 31), the selected queue's page frame number and size, queue
     * select and notify, status, ISR, then the device configuration. Device hooks run without
     * the lock.
     */
    private final class LegacyRegs implements MmioHandler {
        @Override
        public long read(long offset, int size) {
            int off = (int) offset;
            if (off >= L_CONFIG) {
                int o = off - L_CONFIG;
                return o + size <= dev.configSize() ? dev.readConfig(o, size) : 0;
            }
            synchronized (lock) {
                Virtqueue q = queueSelect < queues.length ? queues[queueSelect] : null;
                switch (off) {
                    case L_HOST_FEATURES:
                        return offered & 0xffffffffL;
                    case L_GUEST_FEATURES:
                        return negotiated & 0xffffffffL;
                    case L_QUEUE_PFN:
                        return q == null || !q.enabled ? 0 : q.desc >>> 12;
                    case L_QUEUE_NUM:
                        return q == null ? 0 : q.maxSize();
                    case L_QUEUE_SEL:
                        return queueSelect;
                    case L_STATUS:
                        return status;
                    case L_ISR: {
                        int v = isr;
                        isr = 0;
                        setIrq(false);
                        return v;
                    }
                    default:
                        return 0;
                }
            }
        }

        @Override
        public void write(long offset, int size, long value) {
            int off = (int) offset;
            int v = (int) value;
            if (off >= L_CONFIG) {
                int o = off - L_CONFIG;
                if (o + size <= dev.configSize()) {
                    dev.writeConfig(o, size, value);
                }
                return;
            }
            Runnable after = null;
            synchronized (lock) {
                Virtqueue q = queueSelect < queues.length ? queues[queueSelect] : null;
                switch (off) {
                    case L_GUEST_FEATURES -> {
                        legacyDriver = true;
                        long f = value & 0xffffffffL & offered;
                        negotiated = f;
                        after = () -> dev.featuresAccepted(f);
                    }
                    case L_QUEUE_PFN -> {
                        if (v == 0) {
                            // QEMU resets the whole device on a zero page frame number.
                            after = () -> {
                                resetTransport(false);
                                dev.reset();
                            };
                        } else if (q != null) {
                            legacyDriver = true;
                            q.setLegacy((value & 0xffffffffL) << 12);
                            if (q.ringsValid()) {
                                q.enable();
                            } else {
                                brokenQueue(q, String.format("legacy rings at 0x%x are outside guest RAM", q.desc));
                            }
                        }
                    }
                    case L_QUEUE_SEL -> queueSelect = v & 0xffff;
                    case L_QUEUE_NOTIFY -> {
                        int n = v & 0xffff;
                        if (n < queues.length && queues[n].isReady()) {
                            Virtqueue nq = queues[n];
                            after = () -> dev.queueNotified(nq);
                        }
                    }
                    case L_STATUS -> {
                        legacyDriver = true;
                        after = writeStatus(v & 0xff);
                    }
                    default -> {
                    }
                }
            }
            if (after != null) {
                after.run();
            }
        }
    }

    private long commonRead(int off, int size) {
        synchronized (lock) {
            Virtqueue q = queueSelect < queues.length ? queues[queueSelect] : null;
            long v = switch (off) {
                case 0x00 -> deviceFeatureSelect;
                case 0x04 -> deviceFeatureSelect < 2 ? (offered >>> (32 * deviceFeatureSelect)) & 0xffffffffL : 0;
                case 0x08 -> driverFeatureSelect;
                case 0x0c -> driverFeatureSelect < 2 ? (driverFeatures >>> (32 * driverFeatureSelect)) & 0xffffffffL : 0;
                case 0x10 -> NO_VECTOR;
                case 0x12 -> queues.length;
                case 0x14 -> status;
                case 0x15 -> configGeneration;
                case 0x16 -> queueSelect;
                case 0x18 -> q == null ? 0 : q.size;
                case 0x1a -> NO_VECTOR;
                case 0x1c -> q != null && q.enabled ? 1 : 0;
                case 0x1e -> q == null ? 0 : queueSelect;
                case 0x20 -> q == null ? 0 : q.desc & 0xffffffffL;
                case 0x24 -> q == null ? 0 : q.desc >>> 32;
                case 0x28 -> q == null ? 0 : q.driver & 0xffffffffL;
                case 0x2c -> q == null ? 0 : q.driver >>> 32;
                case 0x30 -> q == null ? 0 : q.device & 0xffffffffL;
                case 0x34 -> q == null ? 0 : q.device >>> 32;
                default -> 0;
            };
            if (size == 8 && (off == 0x20 || off == 0x28 || off == 0x30)) {
                v = off == 0x20 ? q == null ? 0 : q.desc : off == 0x28 ? q == null ? 0 : q.driver : q == null ? 0 : q.device;
            }
            return v;
        }
    }

    private void commonWrite(int off, int size, long value) {
        int v = (int) value;
        Runnable after = null;
        synchronized (lock) {
            Virtqueue q = queueSelect < queues.length ? queues[queueSelect] : null;
            boolean qWritable = q != null && !q.enabled;
            switch (off) {
                case 0x00 -> deviceFeatureSelect = v;
                case 0x08 -> driverFeatureSelect = v;
                case 0x0c -> {
                    if (driverFeatureSelect < 2 && (status & VirtioDevice.STATUS_FEATURES_OK) == 0) {
                        int sh = 32 * driverFeatureSelect;
                        driverFeatures = (driverFeatures & ~(0xffffffffL << sh)) | ((value & 0xffffffffL) << sh);
                    }
                }
                case 0x14 -> after = writeStatus(v & 0xff);
                case 0x16 -> queueSelect = v & 0xffff;
                case 0x18 -> {
                    int s = v & 0xffff;
                    if (qWritable && s >= 1 && s <= q.maxSize() && Integer.bitCount(s) == 1) {
                        q.size = s;
                    }
                }
                case 0x1c -> {
                    if (q != null && !q.enabled && (v & 0xffff) == 1) {
                        if (q.ringsValid()) {
                            q.enable();
                        } else {
                            brokenQueue(q, String.format("rings at 0x%x, 0x%x, 0x%x are misaligned or outside guest RAM",
                                    q.desc, q.driver, q.device));
                        }
                    }
                }
                case 0x20, 0x24, 0x28, 0x2c, 0x30, 0x34 -> {
                    if (qWritable) {
                        setQueueAddress(q, off, size, value);
                    }
                }
                default -> {
                }
            }
        }
        if (after != null) {
            after.run();
        }
    }

    private static void setQueueAddress(Virtqueue q, int off, int size, long value) {
        int base = off & ~7;
        boolean high = (off & 4) != 0;
        long cur = base == 0x20 ? q.desc : base == 0x28 ? q.driver : q.device;
        long nv;
        if (size == 8) {
            nv = value;
        } else if (high) {
            nv = (cur & 0xffffffffL) | (value << 32);
        } else {
            nv = (cur & ~0xffffffffL) | (value & 0xffffffffL);
        }
        if (base == 0x20) {
            q.desc = nv;
        } else if (base == 0x28) {
            q.driver = nv;
        } else {
            q.device = nv;
        }
    }

    /** A status write, under the lock; returns the device hook to call after releasing it, if any. */
    private Runnable writeStatus(int v) {
        if (v == 0) {
            return () -> {
                resetTransport(false);
                dev.reset();
            };
        }
        int old = status;
        int next = v | (old & VirtioDevice.STATUS_DEVICE_NEEDS_RESET);
        Runnable after = null;
        if ((next & VirtioDevice.STATUS_FEATURES_OK) != 0 && (old & VirtioDevice.STATUS_FEATURES_OK) == 0) {
            // As QEMU: bits not offered are dropped, and VERSION_1 is not required (FreeBSD's
            // modern driver on big-endian hosts byte-swaps every register and negotiates none).
            long f = driverFeatures & offered;
            if ((driverFeatures & ~offered) != 0 || (f & (1L << VirtioDevice.F_VERSION_1)) == 0) {
                Phb p = phb();
                if (p != null) {
                    p.log().once("virtio-features-" + slot(), String.format("%s: the driver wrote features 0x%x"
                            + " (offered 0x%x)%s", this, driverFeatures, offered,
                            (f & (1L << VirtioDevice.F_VERSION_1)) == 0 ? ", without VERSION_1" : ""));
                }
            }
            // Ask the device without the lock; FEATURES_OK holds only if it agrees.
            status = next & ~VirtioDevice.STATUS_FEATURES_OK;
            int want = next;
            return () -> {
                boolean ok = dev.featuresAccepted(f);
                Runnable ready = null;
                synchronized (lock) {
                    if (status == (want & ~VirtioDevice.STATUS_FEATURES_OK)) {
                        if (ok) {
                            negotiated = f;
                            status = want;
                        } else {
                            status = want & ~(VirtioDevice.STATUS_FEATURES_OK | VirtioDevice.STATUS_DRIVER_OK);
                        }
                        if (ok && (want & VirtioDevice.STATUS_DRIVER_OK) != 0) {
                            ready = dev::driverReady;
                        }
                    }
                }
                if (ready != null) {
                    ready.run();
                }
            };
        }
        if ((next & VirtioDevice.STATUS_DRIVER_OK) != 0 && (old & VirtioDevice.STATUS_DRIVER_OK) == 0) {
            after = dev::driverReady;
        }
        status = next;
        return after;
    }

    // ---- the PCI configuration access capability ------------------------------------------------

    @Override
    protected void configReading(int offset, int size) {
        if (offset < pciCfgCap + 20 && pciCfgCap + 16 < offset + size) {
            int len = getConfig(pciCfgCap + 12, 4);
            int off = getConfig(pciCfgCap + 8, 4);
            if (cfgAccessOk(off, len)) {
                long v = new Regs().read(off, len);
                setConfig(pciCfgCap + 16, len, (int) v);
            }
        }
    }

    @Override
    protected void configWritten(int offset, int size) {
        if (offset < pciCfgCap + 20 && pciCfgCap + 16 < offset + size) {
            int len = getConfig(pciCfgCap + 12, 4);
            int off = getConfig(pciCfgCap + 8, 4);
            if (cfgAccessOk(off, len)) {
                new Regs().write(off, len, getConfig(pciCfgCap + 16, len) & 0xffffffffL);
            }
        }
    }

    private boolean cfgAccessOk(int off, int len) {
        int bar = getConfig(pciCfgCap + 4, 1);
        return bar == BAR && (len == 1 || len == 2 || len == 4) && off >= 0 && off % len == 0
                && off + len <= BAR_SIZE;
    }

    // ---- saved state and hardware ----------------------------------------------------------------

    @Override
    protected String hardwareDescription() {
        int[] sizes = new int[queues.length];
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = queues[i].maxSize();
        }
        String d = dev.hardwareDescription();
        return String.format("virtio device %d, %s, queues %s, features 0x%x, %d bytes of configuration%s",
                dev.deviceType(), legacy ? "transitional" : "modern", Arrays.toString(sizes), offered,
                dev.configSize(), d.isEmpty() ? "" : ", " + d);
    }

    @Override
    protected void saveState(DataOutput out) throws IOException {
        synchronized (lock) {
            out.writeInt(status);
            out.writeInt(deviceFeatureSelect);
            out.writeInt(driverFeatureSelect);
            out.writeLong(driverFeatures);
            out.writeLong(negotiated);
            out.writeInt(queueSelect);
            out.writeInt(configGeneration);
            out.writeInt(isr);
            out.writeBoolean(legacyDriver);
            out.writeBoolean(legacyBig);
            out.writeInt(queues.length);
            for (Virtqueue q : queues) {
                q.saveState(out);
            }
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        dev.saveState(o);
        o.flush();
        out.writeInt(b.size());
        out.write(b.toByteArray());
    }

    @Override
    protected void loadState(DataInput in) throws IOException {
        synchronized (lock) {
            status = in.readInt();
            deviceFeatureSelect = in.readInt();
            driverFeatureSelect = in.readInt();
            driverFeatures = in.readLong();
            negotiated = in.readLong();
            queueSelect = in.readInt();
            configGeneration = in.readInt();
            isr = in.readInt();
            legacyDriver = in.readBoolean();
            legacyBig = in.readBoolean();
            if (in.readInt() != queues.length) {
                throw new StateFormatException(this + ": saved with another number of virtqueues");
            }
            announce = false;
            for (Virtqueue q : queues) {
                q.loadState(in);
                if (q.enabled && !q.ringsValid()) {
                    throw new StateFormatException(this + ": " + q + " has its rings outside guest RAM");
                }
                announce |= q.hasRedelivery();
            }
        }
        int n = in.readInt();
        if (n < 0 || n > org.boxtheta.javapower.core.StateOutput.MAX_RECORD) {
            throw new StateFormatException(this + ": device state of " + n + " bytes");
        }
        byte[] b = new byte[n];
        in.readFully(b);
        ByteArrayInputStream bin = new ByteArrayInputStream(b);
        try {
            dev.loadState(new DataInputStream(bin));
        } catch (EOFException e) {
            throw new StateFormatException(this + ": the virtio device's state ends early");
        }
        if (bin.available() != 0) {
            throw new StateFormatException(this + ": " + bin.available() + " bytes of the virtio device's state left"
                    + " unread");
        }
    }
}
