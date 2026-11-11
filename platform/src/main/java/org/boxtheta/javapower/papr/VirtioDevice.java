package org.boxtheta.javapower.papr;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Objects;

/**
 * The device-specific part of a virtio device (virtio 1.x): its device type, feature bits,
 * configuration space and virtqueues. A {@link VirtioPciDevice} puts it on the PCI host bridge:
 * {@code cfg.addPciDevice(new VirtioPciDevice(new MyDevice()))}. The transport handles device
 * status, feature negotiation, queue setup, notifications and interrupts; the device consumes
 * the guest's requests from its {@link Virtqueue}s and completes them.
 *
 * <h2>Threads</h2>
 * <ul>
 * <li>{@link #queueNotified}, {@link #readConfig}, {@link #writeConfig},
 *     {@link #featuresAccepted}, {@link #driverReady} and {@link #reset} (when the driver resets
 *     the device) run on the CPU thread that made the access (see below for one exception), with
 *     no lock of the transport or the machine held, so they may call anything of the transport
 *     and take the device's own locks. The CPU waits for them: hand longer work to a thread of
 *     your own.</li>
 * <li>{@link Virtqueue} and {@link VirtqueueRequest} methods, {@link #configChanged} and
 *     {@link #needsReset} may be called from any thread at any time, for example the embedder's
 *     own thread servicing the device. They are thread-safe; each takes the transport's lock
 *     briefly.</li>
 * <li>At a machine reset, a save and a load ({@link #reset}, {@link #saveState},
 *     {@link #loadState}) no CPU runs. The embedder must not use the device's queues
 *     concurrently with a save or a load either.</li>
 * </ul>
 *
 * <h2>Requests in flight</h2>
 * A request popped from a queue is the device's until it {@linkplain VirtqueueRequest#complete
 * completes} it. When the driver or the machine resets the device, requests still held become
 * stale: reading, writing or completing them does nothing (they return -1 or false), so a
 * device thread finishing late never touches memory the guest has reused. When the machine is
 * saved, requests popped and not yet completed are recorded as available again: after the
 * state is loaded, {@link Virtqueue#pop} returns them first, as new requests, and once the
 * machine runs again the device is told with a {@link #queueNotified} call for each queue that
 * has some, made from the thread that pumps the machine ({@code Machine.pump}; the
 * {@code Emulator}'s pump thread). Whatever the device had done with them is lost unless its own
 * {@link #saveState} keeps it. (The machine that was saved keeps running with its requests
 * unaffected.)
 */
public abstract class VirtioDevice {
    // Device status bits (virtio 1.1, 2.1).
    public static final int STATUS_ACKNOWLEDGE = 1;
    public static final int STATUS_DRIVER = 2;
    public static final int STATUS_DRIVER_OK = 4;
    public static final int STATUS_FEATURES_OK = 8;
    public static final int STATUS_DEVICE_NEEDS_RESET = 0x40;
    public static final int STATUS_FAILED = 0x80;

    /** Feature bit 28: the transport supports indirect descriptors. */
    public static final int F_INDIRECT_DESC = 28;
    /** Feature bit 29: the transport supports the used_event and avail_event fields. */
    public static final int F_EVENT_IDX = 29;
    /** Feature bit 32: virtio 1.x. The transport always offers it and requires it. */
    public static final int F_VERSION_1 = 32;
    /** Feature bits 24 to 40 belong to the transport; {@link #deviceFeatures} cannot offer them. */
    public static final long TRANSPORT_FEATURES = ((1L << 41) - 1) & ~((1L << 24) - 1);

    private final int deviceType;
    private final int[] queueSizes;
    VirtioPciDevice transport;

    /**
     * @param deviceType the virtio device id (1 network, 2 block, 4 entropy, 25 sound, ...)
     * @param queueSizes the largest size of each virtqueue, in order: powers of two from 1 to
     *     32768 (the driver may choose smaller ones)
     */
    protected VirtioDevice(int deviceType, int... queueSizes) {
        if (deviceType < 1 || deviceType > 0x3f) {
            throw new IllegalArgumentException("virtio device type " + deviceType);
        }
        if (queueSizes.length < 1 || queueSizes.length > VirtioPciDevice.MAX_QUEUES) {
            throw new IllegalArgumentException("a virtio device needs 1 to " + VirtioPciDevice.MAX_QUEUES + " queues");
        }
        for (int s : queueSizes) {
            if (s < 1 || s > 32768 || Integer.bitCount(s) != 1) {
                throw new IllegalArgumentException("virtqueue size " + s + " is not a power of two up to 32768");
            }
        }
        this.deviceType = deviceType;
        this.queueSizes = queueSizes.clone();
    }

    /** The virtio device id given to the constructor. */
    public final int deviceType() {
        return deviceType;
    }

    /** The number of virtqueues. */
    public final int queueCount() {
        return queueSizes.length;
    }

    final int maxQueueSize(int i) {
        return queueSizes[i];
    }

    // ---- what the device implements -------------------------------------------------------------

    /**
     * The device-specific feature bits the device offers (bits 0 to 23 and 41 and up). The
     * transport adds its own ({@link #F_VERSION_1}, {@link #F_INDIRECT_DESC},
     * {@link #F_EVENT_IDX}). Must return the same value every time. The default offers none.
     */
    protected long deviceFeatures() {
        return 0;
    }

    /** Size in bytes of the device-specific configuration space (at most 4096). Default 0. */
    protected int configSize() {
        return 0;
    }

    /**
     * A driver read of {@code size} (1, 2, 4 or 8) bytes at {@code offset} of the device
     * configuration space, which lies inside {@link #configSize}: the value, little-endian as
     * virtio specifies. The default reads 0.
     */
    protected long readConfig(int offset, int size) {
        return 0;
    }

    /** A driver write to the device configuration space. The default ignores it. */
    protected void writeConfig(int offset, int size, long value) {
    }

    /**
     * The driver set FEATURES_OK with {@code features}: what it wrote, less any bit that was not
     * offered. A driver should accept {@link #F_VERSION_1}; like QEMU, the transport does not
     * insist (FreeBSD's modern driver on a big-endian kernel negotiates no features at all).
     * Return false to refuse them: FEATURES_OK then stays clear and the driver gives up. The
     * default accepts.
     */
    protected boolean featuresAccepted(long features) {
        return true;
    }

    /** The driver set DRIVER_OK: the queues it uses are set up. The default does nothing. */
    protected void driverReady() {
    }

    /**
     * The guest kicked queue {@code q}: it has made requests available. The device may
     * {@linkplain Virtqueue#pop pop} them here or wake its own thread to do so. The default does
     * nothing.
     */
    protected void queueNotified(Virtqueue q) {
    }

    /**
     * The device was reset, by the driver (writing 0 to the status) or by a machine reset: its
     * queues are disabled, requests still held are stale, and the device returns to its initial
     * state. The default does nothing.
     */
    protected void reset() {
    }

    /** Write the device's own state into a saved machine; see {@link PciDevice#saveState}. */
    protected void saveState(DataOutput out) throws IOException {
    }

    /** Read what {@link #saveState} wrote; see {@link PciDevice#loadState}. */
    protected void loadState(DataInput in) throws IOException {
    }

    /**
     * What else a saved state must match besides the device type, the queue sizes, the feature
     * bits and the configuration size, which the transport compares. Default empty.
     */
    protected String hardwareDescription() {
        return "";
    }

    // ---- what the transport provides ----------------------------------------------------------

    private VirtioPciDevice transport() {
        VirtioPciDevice t = transport;
        if (t == null) {
            throw new IllegalStateException("the virtio device is not on a transport");
        }
        return t;
    }

    /** Virtqueue {@code index}. */
    public final Virtqueue queue(int index) {
        Objects.checkIndex(index, queueSizes.length);
        return transport().queue(index);
    }

    /** The features the driver accepted (valid once FEATURES_OK is set), 0 before. */
    public final long negotiatedFeatures() {
        return transport().negotiatedFeatures();
    }

    /** Whether the driver accepted feature {@code bit}. */
    public final boolean hasFeature(int bit) {
        return (negotiatedFeatures() & (1L << bit)) != 0;
    }

    /** The device status the driver wrote (with {@link #STATUS_DEVICE_NEEDS_RESET} if set). */
    public final int status() {
        return transport().status();
    }

    /**
     * The device configuration changed: the transport advances the configuration generation and
     * sends the driver a configuration change interrupt. Any thread.
     */
    public final void configChanged() {
        transport().configChanged();
    }

    /**
     * The device met an error it cannot recover from (for example a malformed request): set
     * DEVICE_NEEDS_RESET and interrupt the driver, which resets the device. Any thread.
     */
    public final void needsReset() {
        transport().needsReset();
    }
}
