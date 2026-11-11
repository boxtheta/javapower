package org.boxtheta.javapower.core;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Guest real memory: one contiguous RAM region starting at real address 0. Accessors take real
 * addresses and an explicit byte order; out-of-range accesses throw
 * {@link IndexOutOfBoundsException} (callers that can be handed a bad address by the guest check
 * {@link #contains} first).
 *
 * <h2>Backing store</h2>
 * RAM is direct (off-heap) memory, so it counts against the JVM's direct memory limit
 * ({@code -XX:MaxDirectMemorySize}, by default the maximum heap size), which must exceed the
 * guest RAM of every machine alive at once. The constructor throws {@link OutOfMemoryError} with
 * an explanation when the RAM cannot be allocated. The memory is released when this object
 * becomes unreachable. This class is multi-release: on Java 22 and later (this version) RAM is
 * one {@link MemorySegment}; on Java 21 it is held on the Java heap, in {@code byte[]} chunks
 * (and needs {@code -Xmx} rather than direct memory). The behaviour is the same.
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
    private static final ValueLayout.OfShort S_BE = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfShort S_LE = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt I_BE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfInt I_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong L_BE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong L_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle I_NATIVE = ValueLayout.JAVA_INT.varHandle();
    private static final VarHandle L_NATIVE = ValueLayout.JAVA_LONG.varHandle();

    private final MemorySegment ram;

    /**
     * Allocate {@code size} bytes of zeroed guest RAM.
     *
     * @throws IllegalArgumentException if {@code size} is negative or too large to track
     * @throws OutOfMemoryError if the direct memory limit or the host cannot accommodate the RAM
     */
    public PhysicalMemory(long size) {
        super(size);
        try {
            this.ram = Arena.ofAuto().allocate(size, 4096);
        } catch (OutOfMemoryError e) {
            OutOfMemoryError err = new OutOfMemoryError(noMemory(size, e));
            err.initCause(e);
            throw err;
        }
    }

    /**
     * For javapower's own execution engines only, not part of the supported API: the object
     * holding the RAM, whose type depends on the Java version (here the {@code MemorySegment}).
     * Writes made through it bypass code-write tracking.
     */
    @Override
    public Object jitBacking() {
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

    // ---- raw primitives for the compare-and-swaps ----

    @Override
    boolean casNative32(long a, int expect, int update) {
        return I_NATIVE.compareAndSet(ram, a, expect, update);
    }

    @Override
    boolean casNative64(long a, long expect, long update) {
        return L_NATIVE.compareAndSet(ram, a, expect, update);
    }

    @Override
    int getVolatileNative32(long a) {
        return (int) I_NATIVE.getVolatile(ram, a);
    }
}
