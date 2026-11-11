package org.boxtheta.javapower.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Guest real memory: one contiguous RAM region starting at real address 0. Accessors take real
 * addresses and an explicit byte order; out-of-range accesses throw
 * {@link IndexOutOfBoundsException} (callers that can be handed a bad address by the guest check
 * {@link #contains} first).
 *
 * <h2>Backing store</h2>
 * This class is multi-release, with the same behaviour in both versions. On Java 22 and later RAM
 * is one off-heap {@code MemorySegment}, which counts against the JVM's direct memory limit
 * ({@code -XX:MaxDirectMemorySize}, by default the maximum heap size). On Java 21 (this version)
 * it is held on the Java heap in {@code byte[]} chunks of 1 GiB, so the maximum heap
 * ({@code -Xmx}) must leave room for it; the rare access that straddles two chunks is assembled
 * byte by byte. Either way the limit must exceed the guest RAM of every machine alive at once,
 * the constructor throws {@link OutOfMemoryError} with an explanation when the RAM cannot be
 * allocated, and the memory is released when this object becomes unreachable.
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
 * {@link #invalidateCode}, which engines call for writes they make to the backing store directly
 * and for instruction-cache invalidations. A write racing with a {@code watchCode} on another
 * thread may go unnoticed, exactly as a store by another processor may: the guest's own
 * cross-modification protocol ({@code icbi}, then a context-synchronising instruction on the
 * executing CPU) is what makes the new code visible, and engines implement {@code icbi} with
 * {@link #invalidateCode}.
 */
public final class PhysicalMemory extends MemoryBase {
    /** log2 of the chunk size. */
    private static final int CHUNK_SHIFT = 30;
    private static final int CHUNK_SIZE = 1 << CHUNK_SHIFT;
    private static final int CHUNK_MASK = CHUNK_SIZE - 1;

    private static final VarHandle S_BE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle S_LE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle I_BE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle I_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle L_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle L_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle I_NATIVE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.nativeOrder());
    private static final VarHandle L_NATIVE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.nativeOrder());

    /**
     * Index in every chunk array of the chunk's first byte. Atomic accesses through a byte-array
     * view need the element's host address to be aligned; arrays are 8-byte aligned objects but
     * their first element need not be (it depends on the object header layout), so each chunk
     * starts at the index that is.
     */
    static final int BIAS = alignedBias();

    /** The RAM: guest byte {@code a} is {@code chunks[a >>> CHUNK_SHIFT][BIAS + (a & CHUNK_MASK)]}. */
    private final byte[][] chunks;

    /**
     * Allocate {@code size} bytes of zeroed guest RAM.
     *
     * @throws IllegalArgumentException if {@code size} is negative or too large to track
     * @throws OutOfMemoryError if the Java heap cannot accommodate the RAM
     */
    public PhysicalMemory(long size) {
        super(size);
        int n = (int) ((size + CHUNK_MASK) >>> CHUNK_SHIFT);
        this.chunks = new byte[n][];
        try {
            for (int c = 0; c < n; c++) {
                chunks[c] = new byte[BIAS + (int) Math.min(CHUNK_SIZE, size - ((long) c << CHUNK_SHIFT))];
            }
        } catch (OutOfMemoryError e) {
            Arrays.fill(chunks, null);
            OutOfMemoryError err = new OutOfMemoryError("cannot allocate " + (size >> 20)
                    + " MiB of guest RAM on the Java heap (maximum heap " + (Runtime.getRuntime().maxMemory() >> 20)
                    + " MiB): on Java 21 guest RAM is held on the heap, so the JVM's -Xmx must exceed the guest RAM"
                    + " of all machines alive at once by the emulator's own needs (about 1 GiB), or run on Java 22"
                    + " or later, where RAM is off-heap");
            err.initCause(e);
            throw err;
        }
    }

    /** The smallest index of a {@code byte[]} at which 8-byte atomic view accesses are aligned. */
    private static int alignedBias() {
        byte[] probe = new byte[16];
        for (int b = 0; b < 8; b++) {
            try {
                L_NATIVE.compareAndSet(probe, b, 0L, 0L);
                return b;
            } catch (IllegalStateException e) {
                // misaligned: try the next index
            } catch (UnsupportedOperationException e) {
                break;
            }
        }
        throw new ExceptionInInitializerError("this JVM has no atomic access to byte arrays; on Java 22 and later the"
                + " multi-release jar provides the MemorySegment version of PhysicalMemory");
    }

    /**
     * For javapower's own execution engines only, not part of the supported API: the object
     * holding the RAM, whose type depends on the Java version. Here it is the {@code byte[][]}
     * chunks: guest byte {@code a} is element {@code b + (a & (2^30 - 1))} of chunk
     * {@code a >>> 30}, where {@code b} is the smallest index of a {@code byte[]} at which 8-byte
     * atomic byte-array view accesses are aligned (0 on HotSpot's usual object layout). Writes
     * made through it bypass code-write tracking.
     */
    @Override
    public Object jitBacking() {
        return chunks;
    }

    /** The chunk holding guest address {@code a}; out of range throws (negative addresses included). */
    private byte[] chunk(long a) {
        return chunks[(int) Math.min(a >>> CHUNK_SHIFT, Integer.MAX_VALUE)];
    }

    /** Whether an {@code n}-byte access at {@code a} crosses into the next chunk. */
    private static boolean straddles(long a, int n) {
        return ((int) a & CHUNK_MASK) > CHUNK_SIZE - n;
    }

    private static int index(long a) {
        return BIAS + ((int) a & CHUNK_MASK);
    }

    /** {@code n} bytes at {@code a} as a big-endian value, byte by byte (for chunk-straddling accesses). */
    private long slowReadBe(long a, int n) {
        long v = 0;
        for (int k = 0; k < n; k++) {
            v = (v << 8) | (chunk(a + k)[index(a + k)] & 0xff);
        }
        return v;
    }

    private void slowWriteBe(long a, int n, long v) {
        checkRange(a, n);
        for (int k = n - 1; k >= 0; k--, v >>>= 8) {
            chunk(a + k)[index(a + k)] = (byte) v;
        }
    }

    public int read8(long a) {
        return chunk(a)[index(a)] & 0xff;
    }

    public int read16be(long a) {
        byte[] c = chunk(a);
        return straddles(a, 2) ? (int) slowReadBe(a, 2) : (short) S_BE.get(c, index(a)) & 0xffff;
    }

    public int read16le(long a) {
        byte[] c = chunk(a);
        return straddles(a, 2) ? Short.reverseBytes((short) slowReadBe(a, 2)) & 0xffff
                : (short) S_LE.get(c, index(a)) & 0xffff;
    }

    public int read32be(long a) {
        byte[] c = chunk(a);
        return straddles(a, 4) ? (int) slowReadBe(a, 4) : (int) I_BE.get(c, index(a));
    }

    public int read32le(long a) {
        byte[] c = chunk(a);
        return straddles(a, 4) ? Integer.reverseBytes((int) slowReadBe(a, 4)) : (int) I_LE.get(c, index(a));
    }

    public long read64be(long a) {
        byte[] c = chunk(a);
        return straddles(a, 8) ? slowReadBe(a, 8) : (long) L_BE.get(c, index(a));
    }

    public long read64le(long a) {
        byte[] c = chunk(a);
        return straddles(a, 8) ? Long.reverseBytes(slowReadBe(a, 8)) : (long) L_LE.get(c, index(a));
    }

    public void write8(long a, int v) {
        chunk(a)[index(a)] = (byte) v;
        wrote(a, 1);
    }

    public void write16be(long a, int v) {
        byte[] c = chunk(a);
        if (straddles(a, 2)) {
            slowWriteBe(a, 2, v);
        } else {
            S_BE.set(c, index(a), (short) v);
        }
        wrote(a, 2);
    }

    public void write16le(long a, int v) {
        byte[] c = chunk(a);
        if (straddles(a, 2)) {
            slowWriteBe(a, 2, Short.reverseBytes((short) v));
        } else {
            S_LE.set(c, index(a), (short) v);
        }
        wrote(a, 2);
    }

    public void write32be(long a, int v) {
        byte[] c = chunk(a);
        if (straddles(a, 4)) {
            slowWriteBe(a, 4, v);
        } else {
            I_BE.set(c, index(a), v);
        }
        wrote(a, 4);
    }

    public void write32le(long a, int v) {
        byte[] c = chunk(a);
        if (straddles(a, 4)) {
            slowWriteBe(a, 4, Integer.reverseBytes(v));
        } else {
            I_LE.set(c, index(a), v);
        }
        wrote(a, 4);
    }

    public void write64be(long a, long v) {
        byte[] c = chunk(a);
        if (straddles(a, 8)) {
            slowWriteBe(a, 8, v);
        } else {
            L_BE.set(c, index(a), v);
        }
        wrote(a, 8);
    }

    public void write64le(long a, long v) {
        byte[] c = chunk(a);
        if (straddles(a, 8)) {
            slowWriteBe(a, 8, Long.reverseBytes(v));
        } else {
            L_LE.set(c, index(a), v);
        }
        wrote(a, 8);
    }

    public void read(long addr, byte[] dst, int off, int len) {
        checkRange(addr, len);
        java.util.Objects.checkFromIndexSize(off, len, dst.length);
        while (len > 0) {
            int n = (int) Math.min(len, CHUNK_SIZE - (addr & CHUNK_MASK));
            System.arraycopy(chunk(addr), index(addr), dst, off, n);
            addr += n;
            off += n;
            len -= n;
        }
    }

    public void write(long addr, byte[] src, int off, int len) {
        checkRange(addr, len);
        java.util.Objects.checkFromIndexSize(off, len, src.length);
        long a = addr;
        int o = off;
        int left = len;
        while (left > 0) {
            int n = (int) Math.min(left, CHUNK_SIZE - (a & CHUNK_MASK));
            System.arraycopy(src, o, chunk(a), index(a), n);
            a += n;
            o += n;
            left -= n;
        }
        if (len > 0) {
            wroteRange(addr, len);
        }
    }

    public void fill(long addr, long len, byte value) {
        checkRange(addr, len);
        long a = addr;
        long left = len;
        while (left > 0) {
            int n = (int) Math.min(left, CHUNK_SIZE - (a & CHUNK_MASK));
            Arrays.fill(chunk(a), index(a), index(a) + n, value);
            a += n;
            left -= n;
        }
        if (len > 0) {
            wroteRange(addr, len);
        }
    }

    // ---- raw primitives for the compare-and-swaps (aligned accesses never straddle a chunk) ----

    @Override
    boolean casNative32(long a, int expect, int update) {
        return I_NATIVE.compareAndSet(chunk(a), index(a), expect, update);
    }

    @Override
    boolean casNative64(long a, long expect, long update) {
        return L_NATIVE.compareAndSet(chunk(a), index(a), expect, update);
    }

    @Override
    int getVolatileNative32(long a) {
        return (int) I_NATIVE.getVolatile(chunk(a), index(a));
    }
}
