package org.boxtheta.javapower.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The part of {@link PhysicalMemory} that does not depend on how RAM is held: size checks,
 * code-write tracking and the compare-and-swap logic. {@code PhysicalMemory} exists in two
 * versions (a multi-release jar: direct buffers on Java 21, a {@code MemorySegment} on Java 22
 * and later); both extend this class and supply the raw accesses. Everything public here is part
 * of {@code PhysicalMemory}'s API and documented there.
 */
abstract class MemoryBase {
    /** log2 of the code-write tracking granule (4 KiB). */
    public static final int CODE_PAGE_SHIFT = 12;

    private static final VarHandle CODE_PAGE = MethodHandles.arrayElementVarHandle(byte[].class);
    private static final VarHandle CODE_GEN = MethodHandles.arrayElementVarHandle(int[].class);

    final long size;
    /** Per 4 KiB page: nonzero while some engine holds code translated from it. */
    private final byte[] codePages;
    /** Per 4 KiB page: the code generation. */
    private final int[] codeGens;
    /** Advanced after every advance of any page's code generation (see {@link #codeWriteCount}). */
    private final AtomicInteger codeWrites = new AtomicInteger();

    MemoryBase(long size) {
        if (size < 0 || ((size + 4095) >>> CODE_PAGE_SHIFT) > Integer.MAX_VALUE - 8) {
            throw new IllegalArgumentException("guest RAM size out of range: " + size);
        }
        this.size = size;
        int pages = (int) ((size + 4095) >>> CODE_PAGE_SHIFT);
        this.codePages = new byte[pages];
        this.codeGens = new int[pages];
    }

    /** The message of the {@link OutOfMemoryError} thrown when RAM cannot be allocated. */
    static String noMemory(long size, Throwable cause) {
        return "cannot allocate " + (size >> 20) + " MiB of guest RAM: it is direct (off-heap) memory, so the JVM's"
                + " direct memory limit (-XX:MaxDirectMemorySize, by default the maximum heap size) must exceed the"
                + " RAM of all machines alive at once (" + cause.getMessage() + ")";
    }

    /** The RAM size in bytes. */
    public long size() {
        return size;
    }

    /** Whether {@code [addr, addr + len)} lies within RAM. */
    public boolean contains(long addr, long len) {
        return addr >= 0 && len >= 0 && addr <= size - len;
    }

    final void checkRange(long addr, long len) {
        if (!contains(addr, len)) {
            throw new IndexOutOfBoundsException("guest RAM range of " + len + " bytes at 0x" + Long.toHexString(addr));
        }
    }

    /**
     * For javapower's own execution engines only, not part of the supported API: the object
     * holding the RAM, whose type depends on the Java version this class was built for. The JIT
     * passes it to generated code as a constant.
     */
    public abstract Object jitBacking();

    // ---- code-write tracking --------------------------------------------------------------------

    /**
     * Flag the page holding real address {@code addr} as holding translated code and return its
     * code generation. Call before reading the code to translate; the translation is current
     * while {@link #codeGeneration} returns the value returned here. A full fence: the flag is
     * visible to other threads before any later read of the code.
     */
    public int watchCode(long addr) {
        int p = (int) (addr >>> CODE_PAGE_SHIFT);
        CODE_PAGE.setVolatile(codePages, p, (byte) 1);
        VarHandle.fullFence();
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
     * writes made to the backing store directly and for instruction-cache invalidation.
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
    final void wrote(long a, int n) {
        byte[] cp = codePages;
        int p0 = (int) (a >>> CODE_PAGE_SHIFT);
        int p1 = (int) ((a + n - 1) >>> CODE_PAGE_SHIFT);
        if ((cp[p0] | cp[p1]) != 0) {
            codeWritten(p0, p1);
        }
    }

    /** After a write of {@code len} (> 0) bytes at {@code a}. */
    final void wroteRange(long a, long len) {
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

    // ---- compare-and-swap -----------------------------------------------------------------------
    //
    // Each compares the naturally aligned value at {@code a} (interpreted in the stated byte
    // order) with {@code expect} and, if equal, replaces it with {@code update}, atomically with
    // respect to every other compare-and-swap and to plain accesses of the same bytes from other
    // threads. They are full memory barriers. Returns whether the swap happened. A misaligned
    // address throws IllegalArgumentException. The 8- and 16-bit forms swap the enclosing aligned
    // word and retry if only its other bytes changed.
    //
    // Raw primitives, in host byte order on naturally aligned addresses within RAM. A successful
    // swap goes through code-write tracking here.

    /** Atomically compare and set the aligned host-order int at {@code a}; full fence. */
    abstract boolean casNative32(long a, int expect, int update);

    /** Atomically compare and set the aligned host-order long at {@code a}; full fence. */
    abstract boolean casNative64(long a, long expect, long update);

    /** Volatile read of the aligned host-order int at {@code a}. */
    abstract int getVolatileNative32(long a);

    private static final boolean NATIVE_BE = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.BIG_ENDIAN;

    private static void checkAligned(long a, int size) {
        if ((a & (size - 1)) != 0) {
            throw new IllegalArgumentException("misaligned " + size + "-byte atomic access at 0x" + Long.toHexString(a));
        }
    }

    private boolean casRaw32(long a, int expectRaw, int updateRaw) {
        boolean ok = casNative32(a, expectRaw, updateRaw);
        if (ok) {
            wrote(a, 4);
        }
        return ok;
    }

    private boolean casRaw64(long a, long expectRaw, long updateRaw) {
        boolean ok = casNative64(a, expectRaw, updateRaw);
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
            int raw = getVolatileNative32(w);
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
