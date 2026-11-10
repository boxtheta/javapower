package javapower.core;

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
 * <p>Shared by the CPU and by device models doing DMA; plain accesses are not atomic.
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
}
