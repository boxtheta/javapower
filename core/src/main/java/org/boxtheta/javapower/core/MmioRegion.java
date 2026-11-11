package org.boxtheta.javapower.core;

import java.nio.ByteOrder;
import java.util.Objects;

/**
 * A range of real addresses whose loads and stores go to an {@link MmioHandler} instead of
 * guest RAM, while it is mapped on an {@link MmioBus}. Immutable.
 *
 * <p>{@link #order() The byte order} is the device's: it says how the handler's values relate to
 * the bytes at the region's addresses (QEMU's {@code DEVICE_BIG_ENDIAN} and
 * {@code DEVICE_LITTLE_ENDIAN}). {@link #read} and {@link #write} convert between it and the
 * order the access is made in.
 */
public final class MmioRegion {
    private final String name;
    private final long base;
    private final long size;
    private final ByteOrder order;
    private final MmioHandler handler;
    private final boolean big;

    /**
     * @param name identifies the region in messages and saved machine states
     * @param base first real address, at least 0
     * @param size bytes, at least 1; {@code base + size} must not exceed {@link Long#MAX_VALUE}
     * @param order the byte order of the handler's values
     * @throws IllegalArgumentException if the range is empty or out of bounds
     */
    public MmioRegion(String name, long base, long size, ByteOrder order, MmioHandler handler) {
        this.name = Objects.requireNonNull(name, "name");
        this.order = Objects.requireNonNull(order, "order");
        this.handler = Objects.requireNonNull(handler, "handler");
        if (base < 0 || size <= 0 || base > Long.MAX_VALUE - size) {
            throw new IllegalArgumentException(String.format("MMIO region %s: %d bytes at 0x%x is not a range of real"
                    + " addresses", name, size, base));
        }
        this.base = base;
        this.size = size;
        this.big = order == ByteOrder.BIG_ENDIAN;
    }

    public String name() {
        return name;
    }

    public long base() {
        return base;
    }

    public long size() {
        return size;
    }

    /** The address after the last byte of the region. */
    public long end() {
        return base + size;
    }

    public ByteOrder order() {
        return order;
    }

    public MmioHandler handler() {
        return handler;
    }

    /** Whether the region and {@code [addr, addr + len)} share a byte. */
    public boolean overlaps(long addr, long len) {
        return len > 0 && addr < base + size && base < addr + len;
    }

    /**
     * Call the handler for a load of {@code size} (1, 2, 4 or 8) bytes at {@code offset} made in
     * byte order {@code order}: the value as that order reads the region's bytes, zero-extended.
     * {@link MmioHandler.BusError} passes through.
     */
    public long read(long offset, int size, ByteOrder order) {
        long v = handler.read(offset, size);
        if (size < 8) {
            v &= (1L << (size * 8)) - 1;
        }
        return (order == ByteOrder.BIG_ENDIAN) == big ? v : swap(v, size);
    }

    /**
     * Call the handler for a store of {@code size} (1, 2, 4 or 8) bytes at {@code offset} made in
     * byte order {@code order}, of the low {@code size * 8} bits of {@code value}.
     * {@link MmioHandler.BusError} passes through.
     */
    public void write(long offset, int size, long value, ByteOrder order) {
        long v = (order == ByteOrder.BIG_ENDIAN) == big ? value : swap(value, size);
        if (size < 8) {
            v &= (1L << (size * 8)) - 1;
        }
        handler.write(offset, size, v);
    }

    /** The low {@code size} bytes of {@code v} in the other order, zero-extended. */
    static long swap(long v, int size) {
        return switch (size) {
            case 1 -> v & 0xff;
            case 2 -> Short.reverseBytes((short) v) & 0xffffL;
            case 4 -> Integer.reverseBytes((int) v) & 0xffffffffL;
            default -> Long.reverseBytes(v);
        };
    }

    @Override
    public String toString() {
        return String.format("%s [0x%x, 0x%x) %s", name, base, base + size, big ? "big-endian" : "little-endian");
    }
}
