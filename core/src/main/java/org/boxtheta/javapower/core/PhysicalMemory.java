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
 *
 * <h2>Code-write tracking</h2>
 * Execution engines that translate guest code ahead of executing it need to know when that code
 * changes. Every 4 KiB page has a <em>code generation</em>: an engine about to translate code
 * from a page calls {@link #watchCode}, which flags the page and returns its generation, and
 * keeps its translation only while {@link #codeGeneration} still returns that value. Every write
 * through this class (the plain accessors, {@link #write}, {@link #fill} and the
 * compare-and-swaps) to a flagged page advances its generation and clears the flag; so does
 * {@link #invalidateCode}, which engines call for writes they make to the segment directly and
 * for instruction-cache invalidations. A write racing with a {@code watchCode} on another thread
 * may go unnoticed, exactly as a store by another processor may: the guest's own
 * cross-modification protocol ({@code icbi}, then a context-synchronising instruction on the
 * executing CPU) is what makes the new code visible, and engines implement {@code icbi} with
 * {@link #invalidateCode}.
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
    /** Per 4 KiB page: nonzero while some engine holds code translated from it (see the class comment). */
    private final byte[] codePages;
    /** Per 4 KiB page: the code generation. */
    private final int[] codeGens;
    /** Advanced after every advance of any page's code generation (see {@link #codeWriteCount}). */
    private final java.util.concurrent.atomic.AtomicInteger codeWrites = new java.util.concurrent.atomic.AtomicInteger();

    public PhysicalMemory(long size) {
        this.size = size;
        this.ram = Arena.ofAuto().allocate(size, 4096);
        int pages = (int) Math.min(Integer.MAX_VALUE - 8, (size + 4095) >>> CODE_PAGE_SHIFT);
        this.codePages = new byte[pages];
        this.codeGens = new int[pages];
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

    public void write8(long a, int v) { ram.set(ValueLayout.JAVA_BYTE, a, (byte) v); wrote(a, 1); }
    public void write16be(long a, int v) { ram.set(S_BE, a, (short) v); wrote(a, 2); }
    public void write16le(long a, int v) { ram.set(S_LE, a, (short) v); wrote(a, 2); }
    public void write32be(long a, int v) { ram.set(I_BE, a, v); wrote(a, 4); }
    public void write32le(long a, int v) { ram.set(I_LE, a, v); wrote(a, 4); }
    public void write64be(long a, long v) { ram.set(L_BE, a, v); wrote(a, 8); }
    public void write64le(long a, long v) { ram.set(L_LE, a, v); wrote(a, 8); }

    public void read(long addr, byte[] dst, int off, int len) {
        MemorySegment.copy(ram, ValueLayout.JAVA_BYTE, addr, dst, off, len);
    }

    public void write(long addr, byte[] src, int off, int len) {
        MemorySegment.copy(src, off, ram, ValueLayout.JAVA_BYTE, addr, len);
        if (len > 0) {
            wroteRange(addr, len);
        }
    }

    public void fill(long addr, long len, byte value) {
        ram.asSlice(addr, len).fill(value);
        if (len > 0) {
            wroteRange(addr, len);
        }
    }

    // ---- code-write tracking (see the class comment) ------------------------------------------

    /** log2 of the code-write tracking granule (4 KiB). */
    public static final int CODE_PAGE_SHIFT = 12;
    private static final java.lang.invoke.VarHandle CODE_PAGE =
            java.lang.invoke.MethodHandles.arrayElementVarHandle(byte[].class);
    private static final java.lang.invoke.VarHandle CODE_GEN =
            java.lang.invoke.MethodHandles.arrayElementVarHandle(int[].class);

    /**
     * Flag the page holding real address {@code addr} as holding translated code and return its
     * code generation. Call before reading the code to translate; the translation is current
     * while {@link #codeGeneration} returns the value returned here. A full fence: the flag is
     * visible to other threads before any later read of the code.
     */
    public int watchCode(long addr) {
        int p = (int) (addr >>> CODE_PAGE_SHIFT);
        CODE_PAGE.setVolatile(codePages, p, (byte) 1);
        java.lang.invoke.VarHandle.fullFence();
        return (int) CODE_GEN.getVolatile(codeGens, p);
    }

    /** The code generation of the page holding real address {@code addr} (a plain read). */
    public int codeGeneration(long addr) {
        return codeGens[(int) (addr >>> CODE_PAGE_SHIFT)];
    }

    /**
     * A counter advanced after every advance of any page's code generation. While it has not
     * changed, no page's generation has: an engine that checked its code against the page
     * generations when the counter had some value may skip the check while it still has it.
     * A volatile read.
     */
    public int codeWriteCount() {
        return codeWrites.get();
    }

    /** Whether the page holding real address {@code addr} is flagged as holding translated code. */
    public boolean isCodePage(long addr) {
        return codePages[(int) (addr >>> CODE_PAGE_SHIFT)] != 0;
    }

    /**
     * Advance the code generation of every page that {@code [addr, addr + len)} touches, flagged
     * or not, and clear their flags: translations of code in them are no longer current. For
     * writes made to {@link #segment} directly and for instruction-cache invalidation.
     */
    public void invalidateCode(long addr, long len) {
        if (len <= 0) {
            return;
        }
        int last = (int) ((addr + len - 1) >>> CODE_PAGE_SHIFT);
        for (int p = (int) (addr >>> CODE_PAGE_SHIFT); p <= last; p++) {
            codePages[p] = 0;
            CODE_GEN.getAndAdd(codeGens, p, 1);
        }
        codeWrites.incrementAndGet();
    }

    /** After a write of {@code n} (1..8) bytes at {@code a}: advance the generation of flagged pages. */
    private void wrote(long a, int n) {
        byte[] cp = codePages;
        int p0 = (int) (a >>> CODE_PAGE_SHIFT);
        int p1 = (int) ((a + n - 1) >>> CODE_PAGE_SHIFT);
        if ((cp[p0] | cp[p1]) != 0) {
            codeWritten(p0, p1);
        }
    }

    private void wroteRange(long a, long len) {
        byte[] cp = codePages;
        int last = (int) ((a + len - 1) >>> CODE_PAGE_SHIFT);
        for (int p = (int) (a >>> CODE_PAGE_SHIFT); p <= last; p++) {
            if (cp[p] != 0) {
                codeWritten(p, last);
                return;
            }
        }
    }

    private void codeWritten(int first, int last) {
        for (int p = first; p <= last; p++) {
            if (codePages[p] != 0) {
                codePages[p] = 0;
                CODE_GEN.getAndAdd(codeGens, p, 1);
            }
        }
        codeWrites.incrementAndGet();
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
        boolean ok = (boolean) I_VH.compareAndSet(ram, a, expectRaw, updateRaw);
        if (ok) {
            wrote(a, 4);
        }
        return ok;
    }

    private boolean casRaw64(long a, long expectRaw, long updateRaw) {
        boolean ok = (boolean) L_VH.compareAndSet(ram, a, expectRaw, updateRaw);
        if (ok) {
            wrote(a, 8);
        }
        return ok;
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
