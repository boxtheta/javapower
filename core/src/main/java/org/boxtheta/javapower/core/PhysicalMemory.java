package org.boxtheta.javapower.core;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Guest real memory: one contiguous RAM region starting at real address 0, held off-heap so it can
 * exceed 2 GiB. Accessors take real addresses and an explicit byte order; out-of-range accesses
 * throw {@link IndexOutOfBoundsException} (callers that can be handed a bad address by the guest
 * check {@link #contains} first).
 *
 * <p>Shared by the CPUs and by device models doing DMA. Plain accesses are not synchronised and
 * only as atomic as the host makes aligned accesses of their size; the {@code cas*} methods are
 * atomic read-modify-writes for store-conditional and atomic memory operations.
 */
public final class PhysicalMemory {
    private static final ValueLayout.OfShort S_BE = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfShort S_LE = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt I_BE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfInt I_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong L_BE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong L_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private final MemorySegment ram;
    private final long size;

    public PhysicalMemory(long size) {
        this.size = size;
        this.ram = Arena.ofAuto().allocate(size, 4096);
    }

    public long size() {
        return size;
    }

    public boolean contains(long addr, long len) {
        return addr >= 0 && len >= 0 && addr <= size - len;
    }

    /** The backing segment, for engines that want to inline accesses. */
    public MemorySegment segment() {
        return ram;
    }

    public int read8(long a) { return ram.get(ValueLayout.JAVA_BYTE, a) & 0xff; }
    public int read16be(long a) { return ram.get(S_BE, a) & 0xffff; }
    public int read16le(long a) { return ram.get(S_LE, a) & 0xffff; }
    public int read32be(long a) { return ram.get(I_BE, a); }
    public int read32le(long a) { return ram.get(I_LE, a); }
    public long read64be(long a) { return ram.get(L_BE, a); }
    public long read64le(long a) { return ram.get(L_LE, a); }

    public void write8(long a, int v) { ram.set(ValueLayout.JAVA_BYTE, a, (byte) v); }
    public void write16be(long a, int v) { ram.set(S_BE, a, (short) v); }
    public void write16le(long a, int v) { ram.set(S_LE, a, (short) v); }
    public void write32be(long a, int v) { ram.set(I_BE, a, v); }
    public void write32le(long a, int v) { ram.set(I_LE, a, v); }
    public void write64be(long a, long v) { ram.set(L_BE, a, v); }
    public void write64le(long a, long v) { ram.set(L_LE, a, v); }

    public void read(long addr, byte[] dst, int off, int len) {
        MemorySegment.copy(ram, ValueLayout.JAVA_BYTE, addr, dst, off, len);
    }

    public void write(long addr, byte[] src, int off, int len) {
        MemorySegment.copy(src, off, ram, ValueLayout.JAVA_BYTE, addr, len);
    }

    public void fill(long addr, long len, byte value) {
        ram.asSlice(addr, len).fill(value);
    }

    // ---- atomic compare-and-swap, for store-conditional and atomic memory operations ----------
    //
    // Each compares the naturally aligned value at {@code a} (interpreted in the stated byte
    // order) with {@code expect} and, if equal, replaces it with {@code update}, atomically with
    // respect to every other compare-and-swap and to plain accesses of the same bytes from other
    // threads. They are full memory barriers. Returns whether the swap happened. A misaligned
    // address throws IllegalArgumentException. The 8- and 16-bit forms swap the enclosing aligned
    // word and retry if only its other bytes changed.

    private static final ValueLayout.OfInt I_NATIVE = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong L_NATIVE = ValueLayout.JAVA_LONG;
    private static final java.lang.invoke.VarHandle I_VH = I_NATIVE.varHandle();
    private static final java.lang.invoke.VarHandle L_VH = L_NATIVE.varHandle();
    private static final boolean NATIVE_BE = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN;

    private static void checkAligned(long a, int size) {
        if ((a & (size - 1)) != 0) {
            throw new IllegalArgumentException("misaligned " + size + "-byte atomic access at 0x" + Long.toHexString(a));
        }
    }

    private boolean casRaw32(long a, int expectRaw, int updateRaw) {
        return (boolean) I_VH.compareAndSet(ram, a, expectRaw, updateRaw);
    }

    private boolean casRaw64(long a, long expectRaw, long updateRaw) {
        return (boolean) L_VH.compareAndSet(ram, a, expectRaw, updateRaw);
    }

    public boolean cas32be(long a, int expect, int update) {
        checkAligned(a, 4);
        return NATIVE_BE ? casRaw32(a, expect, update)
                : casRaw32(a, Integer.reverseBytes(expect), Integer.reverseBytes(update));
    }

    public boolean cas32le(long a, int expect, int update) {
        checkAligned(a, 4);
        return NATIVE_BE ? casRaw32(a, Integer.reverseBytes(expect), Integer.reverseBytes(update))
                : casRaw32(a, expect, update);
    }

    public boolean cas64be(long a, long expect, long update) {
        checkAligned(a, 8);
        return NATIVE_BE ? casRaw64(a, expect, update)
                : casRaw64(a, Long.reverseBytes(expect), Long.reverseBytes(update));
    }

    public boolean cas64le(long a, long expect, long update) {
        checkAligned(a, 8);
        return NATIVE_BE ? casRaw64(a, Long.reverseBytes(expect), Long.reverseBytes(update))
                : casRaw64(a, expect, update);
    }

    /** Byte compare-and-swap ({@code expect} and {@code update} are taken modulo 256). */
    public boolean cas8(long a, int expect, int update) {
        return casSub(a & ~3L, (int) (a & 3) * 8, 0xff, expect, update);
    }

    public boolean cas16be(long a, int expect, int update) {
        checkAligned(a, 2);
        return casSub(a & ~3L, (int) (a & 3) * 8, 0xffff, Short.reverseBytes((short) expect) & 0xffff,
                Short.reverseBytes((short) update) & 0xffff);
    }

    public boolean cas16le(long a, int expect, int update) {
        checkAligned(a, 2);
        return casSub(a & ~3L, (int) (a & 3) * 8, 0xffff, expect, update);
    }

    /**
     * Swap the field of {@code mask} at byte offset {@code bitOff / 8} of the aligned word at
     * {@code w}. The field values are given in memory (little-endian byte) order.
     */
    private boolean casSub(long w, int bitOff, int mask, int expect, int update) {
        // Work on the word as a little-endian value so byte k of memory is bits 8k..8k+7.
        expect &= mask;
        update &= mask;
        while (true) {
            int raw = (int) I_VH.getVolatile(ram, w);
            int le = NATIVE_BE ? Integer.reverseBytes(raw) : raw;
            if (((le >>> bitOff) & mask) != expect) {
                return false;
            }
            int nle = (le & ~(mask << bitOff)) | (update << bitOff);
            int nraw = NATIVE_BE ? Integer.reverseBytes(nle) : nle;
            if (casRaw32(w, raw, nraw)) {
                return true;
            }
        }
    }
}
