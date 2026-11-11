package org.boxtheta.javapower.papr;

import java.lang.invoke.VarHandle;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The PAPR interpartition logical LAN ({@code l-lan@...}, compatible "IBM,l-lan"): a virtual
 * Ethernet adapter, QEMU's {@code spapr-vlan}, driven by Linux's {@code ibmveth}, FreeBSD's
 * {@code llan} and SLOF's {@code veth}. Its far end is a {@link NetBackend}.
 *
 * <p>The guest registers the device with H_REGISTER_LOGICAL_LAN, naming three areas reached
 * through the device's DMA window: a 4 KiB buffer list page, a 4 KiB filter list page and the
 * receive queue, a ring of 16-byte entries. It posts receive buffers with
 * H_ADD_LOGICAL_LAN_BUFFER; the device keeps them in up to five pools of equal-sized buffers,
 * as QEMU does, and puts each received frame into the smallest buffer that holds it, 8 bytes
 * in, the buffer's first 8 bytes being the guest's handle. It then writes a receive queue entry
 * (control byte with the valid and toggle bits, offset 8, length, handle) and pulses the
 * interrupt if H_VIO_SIGNAL enabled it. H_SEND_LOGICAL_LAN gathers up to six buffers into one
 * frame for the backend. Hypercall results, including those for bad arguments, are QEMU's.
 *
 * <p>Two things QEMU leaves out are modelled: H_MULTICAST_CTRL keeps the multicast reception
 * and filtering state and a filter table (QEMU accepts the call and ignores it), and frames to
 * multicast addresses the guest did not ask for are not delivered. After registration multicast
 * reception is on and filtering off, so a driver that never changes them, like FreeBSD's,
 * receives what it would under QEMU. Unicast frames to other addresses are delivered, as in QEMU:
 * which frames reach a port is the backend's (the network's) business. The device tree
 * advertises no filter table entries ({@code ibm,mac-address-filters} is 0, as in QEMU), so
 * Linux disables filtering whenever it wants any multicast address.
 *
 * <p>Threading: the receive state (registration, buffer pools, receive queue position, MAC,
 * filters, frames waiting for buffers) is guarded by the device lock. The backend's thread
 * delivers frames into guest memory itself, holding only that lock and the leaf locks below it,
 * so receiving works whatever the CPUs are doing and never waits for one. Transmission holds a
 * separate lock while it gathers a frame and calls {@link NetBackend#transmit}, so a slow backend
 * delays only other CPUs sending on the same device, never receive-side hypercalls.
 */
public final class LLan extends VioDevice {
    /** Largest frame either way, in bytes (QEMU's MAX_PACKET_SIZE). */
    public static final int MAX_FRAME = 65536;
    /** Frames that may wait for receive buffers; more are dropped. */
    public static final int QUEUE_FRAMES = 512;
    /** Bytes that may wait for receive buffers; more are dropped. */
    static final int QUEUE_BYTES = 2 << 20;
    /** Most network devices a machine may have (each takes a VIO unit address and interrupt). */
    static final int MAX_DEVICES = 64;
    /** DMA window, as QEMU's (256 MiB). */
    static final long DMA_WINDOW = 0x10000000L;
    /** First default MAC address, QEMU's 52:54:00:12:34:56. */
    static final long DEFAULT_MAC = 0x525400123456L;

    // Buffer descriptors: valid and toggle bits, 24-bit length, 32-bit DMA address.
    static final long BD_VALID = 1L << 63;
    static final long BD_TOGGLE = 1L << 62;
    static final long BD_LEN_MASK = 0x00ffffff00000000L;
    static final long BD_ADDR_MASK = 0xffffffffL;
    // Receive queue entry control byte.
    static final int RXQC_TOGGLE = 0x80;
    static final int RXQC_VALID = 0x40;
    // Buffer list page: receive queue descriptor, filter list descriptor, dropped-frame counter.
    static final int PAGE = 4096;
    static final int RXQ_BD_OFF = 0;
    static final int FILTER_BD_OFF = 8;
    static final int RX_BDS_OFF = 16;
    static final int DROPPED_OFF = PAGE - 8;
    static final int RXQ_ALIGN = 16;
    static final int POOLS = 5;
    static final int POOL_MAX_BDS = 4096;
    // H_MULTICAST_CTRL flags (Linux's ibmveth names) and filter table operations.
    static final long MC_RECV_MODIFY = 0x80000;
    static final long MC_FILTER_MODIFY = 0x40000;
    static final long MC_RECV_ENABLE = 0x20000;
    static final long MC_FILTER_ENABLE = 0x10000;
    static final int MC_ADD = 1;
    static final int MC_REMOVE = 2;
    static final int MC_CLEAR = 3;
    static final int MC_TABLE_MAX = 256;
    static final long MAC_MASK = 0xffffffffffffL;
    private static final long BROADCAST = MAC_MASK;

    /** How long queued frames wait for a fitting buffer after the guest last posted one. */
    static final long HOLD_NANOS = 2_000_000;

    private final NetBackend backend;
    private final long permMac;
    private final Object txLock = new Object();
    long holdNanos = HOLD_NANOS;

    // Guarded by the device lock; `open` and `closed` are also read without it by H_SEND_LOGICAL_LAN.
    private volatile boolean open;
    private long bufList;
    private long rxqPtr;
    private int rxBufs;
    private long mac;
    private boolean mcastRecv = true;
    private boolean mcastFilter;
    private boolean mcastOverflow;
    private final long[] mcastTable = new long[MC_TABLE_MAX];
    private int mcastCount;
    private Pool[] pools = newPools();
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private int queuedBytes;
    private long lastAdd;
    private boolean frozen;
    private volatile boolean closed;
    private long dropped;

    /** Receive buffers of one size, used last in, first out (QEMU's RxBufPool). */
    private static final class Pool {
        int size = Integer.MAX_VALUE; // unused pools sort last
        int count;
        final long[] bds = new long[POOL_MAX_BDS];
    }

    LLan(NetBackend backend, long mac) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.permMac = mac & MAC_MASK;
        this.mac = this.permMac;
    }

    /**
     * The devices for the configured networks, in order, with their MAC addresses: the
     * configured one, or else the first of 52:54:00:12:34:56, ...:57, ... that no other device of
     * the machine uses (QEMU's default scheme).
     *
     * @throws IllegalArgumentException if two devices are given the same address or there are
     *     more than {@value #MAX_DEVICES}
     */
    static List<LLan> create(List<MachineConfig.Network> cfg) {
        if (cfg.size() > MAX_DEVICES) {
            throw new IllegalArgumentException("at most " + MAX_DEVICES + " network devices, got " + cfg.size());
        }
        Set<Long> used = new HashSet<>();
        for (MachineConfig.Network n : cfg) {
            if (n.mac != null && !used.add(macToLong(n.mac))) {
                throw new IllegalArgumentException("two network devices have MAC address " + formatMac(macToLong(n.mac)));
            }
        }
        List<LLan> r = new ArrayList<>();
        long next = DEFAULT_MAC;
        for (MachineConfig.Network n : cfg) {
            long m;
            if (n.mac != null) {
                m = macToLong(n.mac);
            } else {
                while (used.contains(next)) {
                    next++;
                }
                m = next;
                used.add(m);
            }
            r.add(new LLan(n.backend, m));
        }
        return r;
    }

    @Override
    public String dtName() {
        return "l-lan";
    }

    @Override
    public String dtType() {
        return "network";
    }

    @Override
    public String dtCompatible() {
        return "IBM,l-lan";
    }

    @Override
    public long dmaWindowSize() {
        return DMA_WINDOW;
    }

    @Override
    public long signalMask() {
        return 1;
    }

    /**
     * {@code ibm,mac-address-filters} and {@code local-mac-address}. Like QEMU, the address is
     * six bytes when it is locally administered unicast and otherwise padded to eight with two
     * leading zeros, the form Linux before 3.10 expects from old PowerVM firmware.
     */
    @Override
    protected void addDeviceProps(Fdt.Node node) {
        node.setCell("ibm,mac-address-filters", 0);
        byte[] m = macBytes(permMac);
        if ((m[0] & 3) == 2) {
            node.set("local-mac-address", m);
        } else {
            byte[] padded = new byte[8];
            System.arraycopy(m, 0, padded, 2, 6);
            node.set("local-mac-address", padded);
        }
    }

    // ---- embedder API -------------------------------------------------------------------------

    /** The backend this device was configured with. */
    public NetBackend backend() {
        return backend;
    }

    /** The MAC address the device was configured with (its {@code local-mac-address}). */
    public byte[] permanentMacAddress() {
        return macBytes(permMac);
    }

    /**
     * The MAC address the guest uses now: the permanent one, or the one it set with
     * H_CHANGE_LOGICAL_LAN_MAC since it last opened the device. Any thread.
     */
    public byte[] macAddress() {
        synchronized (lock()) {
            return macBytes(mac);
        }
    }

    /** Whether the guest's driver has the device open (registered) and can receive. */
    public boolean isOpen() {
        return open;
    }

    /**
     * Frames dropped on the host side since the machine was built: handed over while the guest
     * did not have the device open, while the queue was full or while the machine was being saved
     * or loaded. Frames the guest had no fitting buffer for are counted by the guest's driver.
     */
    public long droppedFrames() {
        synchronized (lock()) {
            return dropped;
        }
    }

    long permanentMac() {
        return permMac;
    }

    /** The permanent MAC address: a saved state belongs to a machine whose device has the same one. */
    @Override
    public String hardwareIdentity() {
        return "mac=" + formatMac(permMac);
    }

    // ---- backend ------------------------------------------------------------------------------

    void start() {
        backend.start(new NetBackend.FrameSink() {
            @Override
            public boolean receive(byte[] frame, int off, int len) {
                return LLan.this.receive(frame, off, len);
            }

            @Override
            public void error(String key, String detail) {
                log().once(key, detail);
            }
        });
    }

    /** Close the backend; from now on frames from it are dropped. */
    void closeBackend() {
        synchronized (lock()) {
            closed = true;
            clearQueue();
        }
        backend.close(); // without the device lock: the backend may wait for its own threads
    }

    /** A frame from the backend (any thread); see {@link NetBackend.FrameSink#receive}. */
    boolean receive(byte[] b, int off, int len) {
        Objects.checkFromIndexSize(off, len, b.length);
        if (len > MAX_FRAME) {
            log().once("llan-frame-too-long", nodeName() + ": dropped a frame of " + len + " bytes from the backend");
            synchronized (lock()) {
                dropped++;
            }
            return false;
        }
        byte[] f = Arrays.copyOfRange(b, off, off + len);
        synchronized (lock()) {
            if (closed || frozen || !open || queue.size() >= QUEUE_FRAMES || queuedBytes + len > QUEUE_BYTES) {
                dropped++;
                return false;
            }
            queue.addLast(f);
            queuedBytes += len;
            drain(System.nanoTime());
            return true;
        }
    }

    /** Timer work (from {@link Machine#pump}): give up on queued frames no buffer fits. */
    void pump() {
        synchronized (lock()) {
            if (!queue.isEmpty()) {
                drain(System.nanoTime());
            }
        }
    }

    /**
     * Stop delivering frames until {@link #thaw}: frames waiting are dropped, and frames handed
     * over meanwhile are refused. Used while the machine is saved or loaded, so that guest memory
     * and the device state written to (or read from) the state stay consistent.
     */
    void freeze() {
        synchronized (lock()) {
            frozen = true;
            clearQueue();
        }
    }

    void thaw() {
        synchronized (lock()) {
            frozen = false;
        }
    }

    private void clearQueue() {
        queue.clear();
        queuedBytes = 0;
    }

    /**
     * Deliver waiting frames, in order, while buffers last. A frame no posted buffer holds waits
     * for one while the guest keeps posting buffers (QEMU gives it 500 us of virtual time before
     * flushing its queue; here it is {@link #holdNanos} of host time after the last
     * H_ADD_LOGICAL_LAN_BUFFER) and is then dropped and counted in the guest's dropped-frame
     * counter. With no buffer at all, frames wait. Called with the device lock held.
     */
    private void drain(long now) {
        if (!open || frozen || closed) {
            return;
        }
        while (!queue.isEmpty()) {
            byte[] f = queue.peekFirst();
            if (!accepts(f)) {
                pop();
                continue;
            }
            if (rxBufs == 0) {
                return;
            }
            long bd = takeBuffer(f.length);
            if (bd == 0) {
                if (now - lastAdd < holdNanos) {
                    return;
                }
                pop();
                countGuestDrop();
                continue;
            }
            pop();
            deliver(f, bd);
        }
    }

    private void pop() {
        queuedBytes -= queue.pollFirst().length;
    }

    /** The multicast filter: whether a frame with this destination is delivered. */
    private boolean accepts(byte[] f) {
        if (f.length < 6 || (f[0] & 1) == 0) {
            return true;
        }
        long dst = macAt(f, 0);
        if (dst == BROADCAST) {
            return true;
        }
        if (!mcastRecv) {
            return false;
        }
        if (!mcastFilter || mcastOverflow) {
            return true;
        }
        for (int i = 0; i < mcastCount; i++) {
            if (mcastTable[i] == dst) {
                return true;
            }
        }
        return false;
    }

    /** Take the buffer for a frame of {@code len} bytes: the smallest pool that fits, last in first. */
    private long takeBuffer(int len) {
        for (Pool p : pools) {
            if (p.count > 0 && (long) p.size >= len + 8L) {
                long bd = p.bds[--p.count];
                p.bds[p.count] = 0;
                return bd;
            }
        }
        return 0;
    }

    /** Write a frame into a buffer and post its receive queue entry (QEMU's spapr_vlan_receive). */
    private void deliver(byte[] f, long bd) {
        rxBufs--;
        long addr = bd & BD_ADDR_MASK;
        if (!dmaWrite(addr + 8, f, 0, f.length)) {
            return; // the buffer is lost, as in QEMU
        }
        long rxqBd = readBe64(bufList + RXQ_BD_OFF);
        int control = RXQC_TOGGLE | RXQC_VALID;
        if ((rxqBd & BD_TOGGLE) != 0) {
            control ^= RXQC_TOGGLE;
        }
        long handle = readBe64(addr);
        long entry = (rxqBd & BD_ADDR_MASK) + rxqPtr;
        writeBe(entry + 8, handle, 8);
        writeBe(entry + 4, f.length, 4);
        writeBe(entry + 2, 8, 2);
        // The control byte makes the entry valid: everything before it must be visible first to
        // a CPU thread polling the queue.
        VarHandle.releaseFence();
        writeBe(entry, control, 1);
        rxqPtr += 16;
        if (rxqPtr >= bdLen(rxqBd)) {
            rxqPtr = 0;
            writeBe(bufList + RXQ_BD_OFF, rxqBd ^ BD_TOGGLE, 8);
        }
        if ((signalState() & 1) != 0) {
            pulseIrq();
        }
    }

    /** One more frame for the counter in the last doubleword of the buffer list page. */
    private void countGuestDrop() {
        long a = bufList + DROPPED_OFF;
        writeBe(a, readBe64(a) + 1, 8);
    }

    // ---- hypercalls ---------------------------------------------------------------------------

    /** H_REGISTER_LOGICAL_LAN. The MAC address argument is ignored, as in QEMU. */
    long register(long bufListArg, long recQueue, long filterList) {
        synchronized (lock()) {
            if (open) {
                log().once("llan-register-twice", nodeName() + ": H_REGISTER_LOGICAL_LAN without H_FREE_LOGICAL_LAN");
                return H.RESOURCE;
            }
            if (!checkBd(validBd(bufListArg, PAGE), PAGE)) {
                return H.PARAMETER;
            }
            long filterBd = validBd(filterList, PAGE);
            if (!checkBd(filterBd, PAGE)) {
                return H.PARAMETER;
            }
            if ((recQueue & BD_VALID) == 0 || !checkBd(recQueue, RXQ_ALIGN)) {
                return H.PARAMETER;
            }
            bufList = bufListArg;
            setSignalState(0);
            recQueue &= ~BD_TOGGLE;
            writeBe(bufList + RXQ_BD_OFF, recQueue, 8);
            writeBe(bufList + FILTER_BD_OFF, filterBd, 8);
            dmaFill(bufList + RX_BDS_OFF, 0, PAGE - RX_BDS_OFF);
            rxBufs = 0;
            rxqPtr = 0;
            dmaFill(recQueue & BD_ADDR_MASK, 0, (int) bdLen(recQueue));
            resetFilters();
            lastAdd = System.nanoTime();
            open = true;
            return H.SUCCESS;
        }
    }

    /** H_FREE_LOGICAL_LAN. */
    long free() {
        synchronized (lock()) {
            if (!open) {
                return H.RESOURCE;
            }
            resetLan();
            return H.SUCCESS;
        }
    }

    /** H_ADD_LOGICAL_LAN_BUFFER. */
    long addBuffer(long bd) {
        synchronized (lock()) {
            if (!checkBd(bd, 4) || bdLen(bd) < 16) {
                return H.PARAMETER;
            }
            if (!open) {
                return H.RESOURCE;
            }
            long r = addToPool(bd);
            if (r != H.SUCCESS) {
                return r;
            }
            rxBufs++;
            lastAdd = System.nanoTime();
            if (!queue.isEmpty()) {
                drain(lastAdd);
            }
            return H.SUCCESS;
        }
    }

    /** Put a buffer into the pool of its size, making a pool for a new size if one is free. */
    private long addToPool(long bd) {
        int size = (int) bdLen(bd);
        Pool p = poolOfSize(size);
        if (p == null) {
            // Reuse an empty pool, the last first (an unused one if any: they sort last).
            for (int i = POOLS - 1; i >= 0; i--) {
                if (pools[i].count == 0) {
                    pools[i].size = size;
                    Arrays.sort(pools, Comparator.comparingInt(q -> q.size));
                    p = poolOfSize(size);
                    break;
                }
            }
        }
        if (p == null || p.count >= POOL_MAX_BDS) {
            return H.RESOURCE;
        }
        p.bds[p.count++] = bd;
        return H.SUCCESS;
    }

    private Pool poolOfSize(int size) {
        for (Pool p : pools) {
            if (p.size == size) {
                return p;
            }
        }
        return null;
    }

    /**
     * H_SEND_LOGICAL_LAN: up to six buffer descriptors, the frame being the valid ones up to the
     * first invalid one. Continuation tokens (frames longer than six buffers) are not supported,
     * as in QEMU.
     */
    long send(long[] bds, long continueToken) {
        if (!open) {
            return H.DROPPED;
        }
        if (continueToken != 0) {
            log().once("llan-continue-token", nodeName() + ": H_SEND_LOGICAL_LAN with a continuation token");
            return H.HARDWARE;
        }
        int n = 0;
        long total = 0;
        while (n < bds.length && (bds[n] & BD_VALID) != 0) {
            total += bdLen(bds[n]);
            n++;
        }
        if (total == 0) {
            return H.SUCCESS;
        }
        if (total > MAX_FRAME) {
            return H.RESOURCE;
        }
        byte[] f = new byte[(int) total];
        int off = 0;
        for (int i = 0; i < n; i++) {
            int len = (int) bdLen(bds[i]);
            if (!dmaRead(bds[i] & BD_ADDR_MASK, f, off, len)) {
                return H.DEST_PARM;
            }
            off += len;
        }
        synchronized (txLock) {
            if (closed) {
                return H.SUCCESS;
            }
            try {
                backend.transmit(f);
            } catch (RuntimeException e) {
                log().once("llan-transmit-failed", nodeName() + ": the backend failed: " + e);
            }
        }
        return H.SUCCESS;
    }

    /**
     * H_MULTICAST_CTRL: change multicast reception and filtering, then add, remove or clear a
     * filter table entry. Always H_SUCCESS, as in QEMU. A table that overflows stops filtering
     * (every multicast frame is delivered) until it is cleared.
     */
    long multicastCtrl(long flags, long address) {
        synchronized (lock()) {
            if ((flags & MC_RECV_MODIFY) != 0) {
                mcastRecv = (flags & MC_RECV_ENABLE) != 0;
            }
            if ((flags & MC_FILTER_MODIFY) != 0) {
                mcastFilter = (flags & MC_FILTER_ENABLE) != 0;
            }
            long a = address & MAC_MASK;
            int at = -1;
            for (int i = 0; i < mcastCount; i++) {
                if (mcastTable[i] == a) {
                    at = i;
                }
            }
            switch ((int) (flags & 3)) {
                case MC_ADD -> {
                    if (at < 0) {
                        if (mcastCount < MC_TABLE_MAX) {
                            mcastTable[mcastCount++] = a;
                        } else {
                            mcastOverflow = true;
                        }
                    }
                }
                case MC_REMOVE -> {
                    if (at >= 0) {
                        mcastTable[at] = mcastTable[--mcastCount];
                    }
                }
                case MC_CLEAR -> {
                    mcastCount = 0;
                    mcastOverflow = false;
                }
                default -> {
                }
            }
            return H.SUCCESS;
        }
    }

    /** H_CHANGE_LOGICAL_LAN_MAC: the low 48 bits of the argument become the current address. */
    long changeMac(long address) {
        synchronized (lock()) {
            mac = address & MAC_MASK;
            return H.SUCCESS;
        }
    }

    // ---- reset and saved state ------------------------------------------------------------------

    /** H_FREE_LOGICAL_LAN and device reset (QEMU's spapr_vlan_reset). */
    private void resetLan() {
        open = false;
        bufList = 0;
        rxBufs = 0;
        rxqPtr = 0;
        pools = newPools();
        mac = permMac;
        resetFilters();
        clearQueue();
    }

    private void resetFilters() {
        mcastRecv = true;
        mcastFilter = false;
        mcastOverflow = false;
        mcastCount = 0;
    }

    private static Pool[] newPools() {
        Pool[] p = new Pool[POOLS];
        for (int i = 0; i < POOLS; i++) {
            p[i] = new Pool();
        }
        return p;
    }

    @Override
    protected void resetDevice() {
        resetLan();
        frozen = false;
    }

    @Override
    protected void saveDevice(java.io.DataOutput d) throws java.io.IOException {
        d.writeBoolean(open);
        d.writeLong(bufList);
        d.writeLong(rxqPtr);
        d.writeInt(rxBufs);
        d.writeLong(mac);
        d.writeBoolean(mcastRecv);
        d.writeBoolean(mcastFilter);
        d.writeBoolean(mcastOverflow);
        d.writeInt(mcastCount);
        for (int i = 0; i < mcastCount; i++) {
            d.writeLong(mcastTable[i]);
        }
        for (Pool p : pools) {
            d.writeInt(p.size);
            d.writeInt(p.count);
            for (int i = 0; i < p.count; i++) {
                d.writeLong(p.bds[i]);
            }
        }
    }

    @Override
    protected void loadDevice(org.boxtheta.javapower.core.StateInput.Record r) throws java.io.IOException {
        boolean o = r.readBoolean();
        long bl = r.readLong();
        long ptr = r.readLong();
        int bufs = r.readInt("l-lan receive buffers", 0, POOLS * POOL_MAX_BDS);
        long m = r.readLong();
        boolean recv = r.readBoolean();
        boolean filter = r.readBoolean();
        boolean overflow = r.readBoolean();
        int n = r.readInt("l-lan multicast filters", 0, MC_TABLE_MAX);
        long[] table = new long[MC_TABLE_MAX];
        for (int i = 0; i < n; i++) {
            table[i] = r.readLong() & MAC_MASK;
        }
        Pool[] ps = newPools();
        int sum = 0;
        for (Pool p : ps) {
            p.size = r.readInt();
            p.count = r.readInt("l-lan pool buffers", 0, POOL_MAX_BDS);
            if (p.size != Integer.MAX_VALUE && (p.size < 16 || p.size > (int) (BD_LEN_MASK >>> 32))) {
                throw r.corrupt("l-lan pool of " + p.size + "-byte buffers");
            }
            for (int i = 0; i < p.count; i++) {
                p.bds[i] = r.readLong();
            }
            sum += p.count;
        }
        if (sum != bufs || ptr < 0 || ptr > (BD_LEN_MASK >>> 32)) {
            throw r.corrupt("l-lan receive state");
        }
        open = o;
        bufList = bl;
        rxqPtr = ptr;
        rxBufs = bufs;
        mac = m & MAC_MASK;
        mcastRecv = recv;
        mcastFilter = filter;
        mcastOverflow = overflow;
        mcastCount = n;
        System.arraycopy(table, 0, mcastTable, 0, MC_TABLE_MAX);
        pools = ps;
        clearQueue();
        lastAdd = System.nanoTime();
    }

    // ---- DMA helpers --------------------------------------------------------------------------

    static long validBd(long addr, long len) {
        return BD_VALID | ((len << 32) & BD_LEN_MASK) | (addr & BD_ADDR_MASK);
    }

    static long bdLen(long bd) {
        return (bd & BD_LEN_MASK) >>> 32;
    }

    /** QEMU's check_bd: aligned address and length, and the whole range mapped both ways. */
    private boolean checkBd(long bd, long align) {
        long addr = bd & BD_ADDR_MASK;
        long len = bdLen(bd);
        if (addr % align != 0 || len % align != 0) {
            return false;
        }
        return dmaValid(addr, len, true) && dmaValid(addr, len, false);
    }

    /** Whether a DMA of {@code len} bytes at {@code ioba} in the given direction would succeed. */
    private boolean dmaValid(long ioba, long len, boolean deviceWrites) {
        TceTable t = tce();
        org.boxtheta.javapower.core.PhysicalMemory m = machine().memory();
        while (len > 0) {
            long chunk = Math.min(len, TceTable.PAGE_SIZE - (ioba & ~TceTable.PAGE_MASK));
            long ra = t.translate(ioba, deviceWrites);
            if (ra < 0 || !m.contains(ra, chunk)) {
                return false;
            }
            ioba += chunk;
            len -= chunk;
        }
        return true;
    }

    /** A big-endian doubleword by DMA, 0 if it cannot be read. */
    private long readBe64(long ioba) {
        byte[] b = new byte[8];
        if (!dmaRead(ioba, b, 0, 8)) {
            return 0;
        }
        long v = 0;
        for (byte x : b) {
            v = (v << 8) | (x & 0xff);
        }
        return v;
    }

    /** Store the low {@code size} bytes of {@code v} big-endian by DMA; faults are ignored, as in QEMU. */
    private void writeBe(long ioba, long v, int size) {
        byte[] b = new byte[size];
        for (int i = size - 1; i >= 0; i--) {
            b[i] = (byte) v;
            v >>>= 8;
        }
        dmaWrite(ioba, b, 0, size);
    }

    // ---- MAC addresses --------------------------------------------------------------------------

    static long macToLong(byte[] m) {
        return macAt(m, 0);
    }

    private static long macAt(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 6; i++) {
            v = (v << 8) | (b[off + i] & 0xff);
        }
        return v;
    }

    static byte[] macBytes(long v) {
        byte[] b = new byte[6];
        for (int i = 5; i >= 0; i--) {
            b[i] = (byte) v;
            v >>>= 8;
        }
        return b;
    }

    static String formatMac(long v) {
        byte[] b = macBytes(v);
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(String.format("%02x", b[i] & 0xff));
        }
        return sb.toString();
    }
}
