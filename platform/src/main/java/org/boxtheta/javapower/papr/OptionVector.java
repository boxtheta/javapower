package org.boxtheta.javapower.papr;

import java.util.BitSet;
import org.boxtheta.javapower.core.PhysicalMemory;

/**
 * A PAPR client-architecture-support option vector, kept as a bit set in guest bit order: vector
 * byte {@code n} (1-based, as PAPR numbers them) bit {@code b} (0 = most significant bit of the
 * byte) is bit {@code (n - 1) * 8 + b}, the same numbering as QEMU's {@code OV_BIT(n, b)}.
 */
final class OptionVector {
    /** Largest vector length in bytes (not counting the length byte). */
    public static final int MAX_BYTES = 256;

    // Option vector 1.
    public static final int OV1_PPC_3_00 = bit(3, 0);
    // Option vector 5.
    public static final int OV5_DRCONF_MEMORY = bit(2, 2);
    public static final int OV5_FORM1_AFFINITY = bit(5, 0);
    public static final int OV5_FORM2_AFFINITY = bit(5, 2);
    public static final int OV5_HP_EVT = bit(6, 5);
    public static final int OV5_HPT_RESIZE = bit(6, 7);
    public static final int OV5_DRMEM_V2 = bit(22, 0);
    public static final int OV5_XIVE_BOTH = bit(23, 0);
    public static final int OV5_XIVE_EXPLOIT = bit(23, 1);
    public static final int OV5_MMU_BOTH = bit(24, 0);
    public static final int OV5_MMU_RADIX_300 = bit(24, 1);
    public static final int OV5_MMU_RADIX_GTSE = bit(26, 1);

    private final BitSet bits = new BitSet(MAX_BYTES * 8);

    /** Bit number of PAPR vector byte {@code byteNo} (1-based), bit {@code bitNo} (0 = MSB). */
    public static int bit(int byteNo, int bitNo) {
        return (byteNo - 1) * 8 + bitNo;
    }

    public OptionVector set(int bit) {
        bits.set(bit);
        return this;
    }

    public boolean test(int bit) {
        return bits.get(bit);
    }

    public boolean isEmpty() {
        return bits.isEmpty();
    }

    public OptionVector copy() {
        OptionVector o = new OptionVector();
        o.bits.or(bits);
        return o;
    }

    /** A new vector holding the bits set in both. */
    public OptionVector intersect(OptionVector other) {
        OptionVector o = copy();
        o.bits.and(other.bits);
        return o;
    }

    /** Set bits from the raw vector bytes (without the length byte). */
    public static OptionVector fromBytes(byte[] v) {
        OptionVector o = new OptionVector();
        for (int i = 0; i < v.length; i++) {
            for (int b = 0; b < 8; b++) {
                if ((v[i] & (0x80 >>> b)) != 0) {
                    o.bits.set(i * 8 + b);
                }
            }
        }
        return o;
    }

    /**
     * Encode for the device tree ({@code ibm,architecture-vec-5}): a length byte holding
     * (vector length - 1) followed by the vector, at least one byte long.
     */
    public byte[] toDtBytes() {
        int last = bits.length() - 1;
        int len = last < 0 ? 1 : last / 8 + 1;
        byte[] r = new byte[len + 1];
        r[0] = (byte) (len - 1);
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            r[1 + i / 8] |= (byte) (0x80 >>> (i % 8));
        }
        return r;
    }

    /**
     * Find option vector {@code vector} (1-based) in the table at real address {@code table} (a
     * count byte holding the number of vectors - 1, then each vector as a length byte holding its
     * length - 1 followed by its bytes) and parse it. Returns null if the table has fewer vectors
     * or runs outside memory.
     */
    public static OptionVector parse(PhysicalMemory mem, long table, int vector) {
        if (!mem.contains(table, 1)) {
            return null;
        }
        int count = mem.read8(table) + 1;
        if (vector > count) {
            return null;
        }
        long a = table + 1;
        for (int i = 0; i < vector - 1; i++) {
            if (!mem.contains(a, 1)) {
                return null;
            }
            a += mem.read8(a) + 1 + 1;
        }
        if (!mem.contains(a, 1)) {
            return null;
        }
        int len = mem.read8(a) + 1;
        if (!mem.contains(a + 1, len)) {
            return null;
        }
        byte[] v = new byte[len];
        mem.read(a + 1, v, 0, len);
        return fromBytes(v);
    }
}
