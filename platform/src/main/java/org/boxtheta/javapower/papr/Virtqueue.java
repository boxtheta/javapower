package org.boxtheta.javapower.papr;

import java.lang.invoke.VarHandle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.boxtheta.javapower.core.PhysicalMemory;

/**
 * One split virtqueue of a {@link VirtioDevice} (virtio 1.1, 2.6): the device
 * {@linkplain #pop pops} the requests the driver made available and
 * {@linkplain VirtqueueRequest#complete completes} them. The rings lie in guest RAM at the
 * real addresses the driver gave; like QEMU's virtio devices by default, the transport does not
 * offer VIRTIO_F_ACCESS_PLATFORM, so the addresses in the rings and descriptors are guest real
 * addresses, not translated by the host bridge's IOMMU.
 *
 * <p>Thread-safe: every method may be called from any thread. All state is guarded by the
 * transport's lock, which each call holds only briefly (it reads or writes guest memory under
 * it, never calls the device).
 */
public final class Virtqueue {
    static final int DESC_F_NEXT = 1;
    static final int DESC_F_WRITE = 2;
    static final int DESC_F_INDIRECT = 4;
    static final int AVAIL_F_NO_INTERRUPT = 1;
    static final int USED_F_NO_NOTIFY = 1;

    private final VirtioPciDevice transport;
    private final Object lock;
    private PhysicalMemory mem;
    private final int index;
    private final int maxSize;

    // Driver-programmed setup (common configuration), guarded by lock.
    int size;
    long desc;
    long driver;
    long device;
    boolean enabled;

    // Ring state.
    private int lastAvail;
    private int usedIdx;
    private boolean notifications = true;
    /** Incremented at every reset: requests popped before it are stale. */
    int generation;
    /** Heads popped and not yet completed, in the order popped. */
    private final Set<Integer> inFlight = new LinkedHashSet<>();
    /** Heads to hand out again before new ones (requests in flight when the state was saved). */
    private final ArrayDeque<Integer> redeliver = new ArrayDeque<>();

    Virtqueue(VirtioPciDevice transport, Object lock, int index, int maxSize) {
        this.transport = transport;
        this.lock = lock;
        this.index = index;
        this.maxSize = maxSize;
        this.size = maxSize;
    }

    /** Whether the rings are big-endian (legacy byte order of a big-endian guest). Under the lock. */
    private boolean big() {
        return transport.ringsBigEndian();
    }

    private int r16(long a) {
        return big() ? mem.read16be(a) : mem.read16le(a);
    }

    private int r32(long a) {
        return big() ? mem.read32be(a) : mem.read32le(a);
    }

    private long r64(long a) {
        return big() ? mem.read64be(a) : mem.read64le(a);
    }

    private void w16(long a, int v) {
        if (big()) {
            mem.write16be(a, v);
        } else {
            mem.write16le(a, v);
        }
    }

    private void w32(long a, int v) {
        if (big()) {
            mem.write32be(a, v);
        } else {
            mem.write32le(a, v);
        }
    }

    /** The queue's number within its device. */
    public int index() {
        return index;
    }

    /** The largest size the device allows. */
    public int maxSize() {
        return maxSize;
    }

    /** The number of descriptors the driver chose (valid while {@link #isReady}). */
    public int size() {
        synchronized (lock) {
            return size;
        }
    }

    /** Whether the driver has enabled the queue and the device can use it. */
    public boolean isReady() {
        synchronized (lock) {
            return enabled && transport.usable();
        }
    }

    /**
     * Ask the driver to kick ({@link VirtioDevice#queueNotified}) when it makes requests
     * available ({@code true}, the default), or not to bother while the device polls the queue
     * anyway. A hint: the driver may kick regardless, and must be listened to again before the
     * device stops polling. With {@code true}, check for requests with {@link #pop} afterwards,
     * as some may have arrived while kicks were off.
     */
    public void setNotifications(boolean enabled) {
        synchronized (lock) {
            notifications = enabled;
            if (ready()) {
                writeNotifyHint();
            }
        }
    }

    private boolean ready() {
        return enabled && transport.usable();
    }

    private boolean eventIdx() {
        return (transport.negotiatedFeatures() & (1L << VirtioDevice.F_EVENT_IDX)) != 0;
    }

    private boolean indirect() {
        return (transport.negotiatedFeatures() & (1L << VirtioDevice.F_INDIRECT_DESC)) != 0;
    }

    private void writeNotifyHint() {
        if (eventIdx()) {
            if (notifications) {
                w16(device + 4 + 8L * size, lastAvail);
            }
        } else {
            int f = r16(device);
            w16(device, notifications ? f & ~USED_F_NO_NOTIFY : f | USED_F_NO_NOTIFY);
        }
    }

    /**
     * The next request the driver made available, or null if there is none (or the queue is not
     * ready). A malformed request (a descriptor chain that loops, is too long, or points outside
     * guest RAM) is not returned: the device is marked as needing a reset instead, as virtio
     * specifies, and null returned.
     */
    public VirtqueueRequest pop() {
        synchronized (lock) {
            if (!ready()) {
                return null;
            }
            int head;
            if (!redeliver.isEmpty()) {
                head = redeliver.poll();
            } else {
                int avail = availIdx();
                if (avail == lastAvail && eventIdx() && notifications) {
                    // Ask for a kick at the next request, then look again: one may have arrived.
                    w16(device + 4 + 8L * size, lastAvail);
                    VarHandle.fullFence();
                    avail = availIdx();
                }
                int pending = (avail - lastAvail) & 0xffff;
                if (pending == 0) {
                    return null;
                }
                if (pending > size) {
                    broken("available index moved by " + pending + " in a queue of " + size);
                    return null;
                }
                head = r16(driver + 4 + 2L * (lastAvail % size));
                lastAvail = (lastAvail + 1) & 0xffff;
                if (eventIdx() && notifications) {
                    w16(device + 4 + 8L * size, lastAvail);
                }
            }
            VirtqueueRequest r = parse(head);
            if (r != null) {
                inFlight.add(head);
            }
            return r;
        }
    }

    /** Whether requests saved in flight wait to be popped again. Under the lock. */
    boolean hasRedelivery() {
        return !redeliver.isEmpty();
    }

    /** Whether the driver has made requests available that {@link #pop} has not returned. */
    public boolean hasAvailable() {
        synchronized (lock) {
            return ready() && (!redeliver.isEmpty() || availIdx() != lastAvail);
        }
    }

    private int availIdx() {
        int v = r16(driver + 2);
        VarHandle.acquireFence();
        return v;
    }

    private void broken(String why) {
        transport.brokenQueue(this, why);
    }

    /** Read the chain at {@code head} into a request, or mark the device broken and return null. */
    private VirtqueueRequest parse(int head) {
        if (head >= size) {
            broken("descriptor head " + head + " outside a queue of " + size);
            return null;
        }
        List<long[]> segs = new ArrayList<>();
        long table = desc;
        int tableSize = size;
        int i = head;
        int seen = 0;
        boolean inIndirect = false;
        boolean sawWritable = false;
        while (true) {
            if (++seen > tableSize) {
                broken("descriptor chain loops");
                return null;
            }
            long d = table + 16L * i;
            long addr = r64(d);
            int len = r32(d + 8);
            int flags = r16(d + 12);
            int next = r16(d + 14);
            if ((flags & DESC_F_INDIRECT) != 0) {
                if (inIndirect || !indirect() || (flags & DESC_F_NEXT) != 0 || len == 0 || len % 16 != 0
                        || !mem.contains(addr, len & 0xffffffffL)) {
                    broken("bad indirect descriptor");
                    return null;
                }
                inIndirect = true;
                table = addr;
                tableSize = (int) ((len & 0xffffffffL) / 16);
                i = 0;
                seen = 0;
                continue;
            }
            long n = len & 0xffffffffL;
            if (n > 0 && !mem.contains(addr, n)) {
                broken(String.format("descriptor buffer 0x%x bytes at 0x%x is outside guest RAM", n, addr));
                return null;
            }
            boolean w = (flags & DESC_F_WRITE) != 0;
            if (!w && sawWritable) {
                broken("a device-readable buffer follows a device-writable one");
                return null;
            }
            sawWritable |= w;
            segs.add(new long[] {addr, n, w ? 1 : 0});
            if ((flags & DESC_F_NEXT) == 0) {
                break;
            }
            i = next;
            if (i >= tableSize) {
                broken("descriptor index " + i + " outside its table");
                return null;
            }
        }
        return new VirtqueueRequest(this, head, generation, segs);
    }

    // ---- request side (called by VirtqueueRequest) ------------------------------------------------

    Object lock() {
        return lock;
    }

    PhysicalMemory memory() {
        return mem;
    }

    /** The guest RAM of the machine the device was added to. Under the lock. */
    void setMemory(PhysicalMemory m) {
        mem = m;
    }

    /** Whether a request popped in {@code gen} may still be used. */
    boolean current(int gen, int head) {
        return gen == generation && ready() && inFlight.contains(head);
    }

    /** Put {@code head} on the used ring with {@code written} bytes; true if the driver wants an interrupt. */
    boolean pushUsed(int head, int written) {
        inFlight.remove(head);
        long e = device + 4 + 8L * (usedIdx % size);
        w32(e, head);
        w32(e + 4, written);
        int old = usedIdx;
        usedIdx = (usedIdx + 1) & 0xffff;
        VarHandle.releaseFence();
        w16(device + 2, usedIdx);
        VarHandle.fullFence();
        if (eventIdx()) {
            int event = r16(driver + 4 + 2L * size);
            return ((usedIdx - event - 1) & 0xffff) < ((usedIdx - old) & 0xffff);
        }
        return (r16(driver) & AVAIL_F_NO_INTERRUPT) == 0;
    }

    /** Interrupt the driver for a used buffer. Under the lock. */
    void usedInterrupt() {
        transport.interrupt(VirtioPciDevice.ISR_QUEUE);
    }

    // ---- transport side -------------------------------------------------------------------------

    /** The driver enabled the queue: start from the beginning of fresh rings. Under the lock. */
    void enable() {
        enabled = true;
        lastAvail = 0;
        usedIdx = 0;
        inFlight.clear();
        redeliver.clear();
    }

    /**
     * A legacy driver placed the rings at {@code pa}: the queue has its largest size, the
     * available ring follows the descriptor table, and the used ring starts at the next 4 KiB
     * boundary (the legacy layout). Under the lock.
     */
    void setLegacy(long pa) {
        size = maxSize;
        desc = pa;
        driver = pa + 16L * size;
        device = (driver + 6 + 2L * size + 4095) & ~4095L;
    }

    /** Device reset: the queue is disabled and back to its defaults. Under the lock. */
    void reset() {
        generation++;
        enabled = false;
        size = maxSize;
        desc = driver = device = 0;
        lastAvail = 0;
        usedIdx = 0;
        notifications = true;
        inFlight.clear();
        redeliver.clear();
    }

    /**
     * Whether the rings of a queue of {@code size} entries at these addresses lie in RAM and are
     * aligned as virtio requires (descriptor table 16 bytes, available ring 2, used ring 4).
     */
    boolean ringsValid() {
        return desc % 16 == 0 && driver % 2 == 0 && device % 4 == 0 && mem.contains(desc, 16L * size)
                && mem.contains(driver, 6 + 2L * size) && mem.contains(device, 6 + 8L * size);
    }

    void saveState(java.io.DataOutput out) throws java.io.IOException {
        out.writeInt(size);
        out.writeLong(desc);
        out.writeLong(driver);
        out.writeLong(device);
        out.writeBoolean(enabled);
        out.writeShort(lastAvail);
        out.writeShort(usedIdx);
        out.writeBoolean(notifications);
        // Requests in flight are handed out again after a load, before new ones.
        out.writeInt(redeliver.size() + inFlight.size());
        for (int h : redeliver) {
            out.writeShort(h);
        }
        for (int h : inFlight) {
            out.writeShort(h);
        }
    }

    void loadState(java.io.DataInput in) throws java.io.IOException {
        int s = in.readInt();
        long d = in.readLong();
        long dr = in.readLong();
        long dv = in.readLong();
        boolean en = in.readBoolean();
        int la = in.readUnsignedShort();
        int ui = in.readUnsignedShort();
        boolean notif = in.readBoolean();
        int n = in.readInt();
        if (s < 1 || s > maxSize || Integer.bitCount(s) != 1 || n < 0 || n > s) {
            throw new org.boxtheta.javapower.core.StateFormatException("virtqueue " + index + ": bad saved size " + s
                    + " or " + n + " requests");
        }
        ArrayDeque<Integer> again = new ArrayDeque<>();
        for (int k = 0; k < n; k++) {
            int h = in.readUnsignedShort();
            if (h >= s) {
                throw new org.boxtheta.javapower.core.StateFormatException("virtqueue " + index + ": saved request "
                        + h);
            }
            again.add(h);
        }
        generation++;
        size = s;
        desc = d;
        driver = dr;
        device = dv;
        enabled = en;
        lastAvail = la;
        usedIdx = ui;
        notifications = notif;
        inFlight.clear();
        redeliver.clear();
        redeliver.addAll(again);
    }

    @Override
    public String toString() {
        return "virtqueue " + index;
    }
}
