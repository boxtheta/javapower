package org.boxtheta.javapower.fpvec;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PpcException;

/**
 * Element access on 128-bit registers held as two longs (hi = doubleword 0 = ISA bits 0:63).
 * Elements are numbered in ISA (big-endian) order: element 0 is the most significant.
 */
final class V {
    private V() {}

    static int rt(int i) { return (i >>> 21) & 31; }
    static int ra(int i) { return (i >>> 16) & 31; }
    static int rb(int i) { return (i >>> 11) & 31; }
    static int rc(int i) { return (i >>> 6) & 31; }

    static void checkVec(CpuState s) {
        if ((s.msr & Msr.VEC) == 0) {
            throw PpcException.vecUnavailable();
        }
    }

    static void checkVsx(CpuState s) {
        if ((s.msr & Msr.VSX) == 0) {
            throw PpcException.vsxUnavailable();
        }
    }

    /** Byte i (0..15), zero-extended. */
    static int b(long hi, long lo, int i) {
        long w = i < 8 ? hi : lo;
        return (int) (w >>> (56 - 8 * (i & 7))) & 0xff;
    }

    /** Halfword i (0..7), zero-extended. */
    static int h(long hi, long lo, int i) {
        long w = i < 4 ? hi : lo;
        return (int) (w >>> (48 - 16 * (i & 3))) & 0xffff;
    }

    /** Word i (0..3). */
    static int w(long hi, long lo, int i) {
        long w = i < 2 ? hi : lo;
        return (int) (w >>> (32 - 32 * (i & 1)));
    }

    /** Element i of {@code size} bytes, zero-extended (size 8 returns the doubleword). */
    static long get(long hi, long lo, int size, int i) {
        return switch (size) {
            case 1 -> b(hi, lo, i);
            case 2 -> h(hi, lo, i);
            case 4 -> w(hi, lo, i) & 0xffffffffL;
            default -> i == 0 ? hi : lo;
        };
    }

    /** Element i of {@code size} bytes, sign-extended. */
    static long gets(long hi, long lo, int size, int i) {
        return switch (size) {
            case 1 -> (byte) b(hi, lo, i);
            case 2 -> (short) h(hi, lo, i);
            case 4 -> w(hi, lo, i);
            default -> i == 0 ? hi : lo;
        };
    }

    /** Shift (within its doubleword) of element i of {@code size} bytes. */
    static int shift(int size, int i) {
        int per = 8 / size;
        return 8 * size * (per - 1 - (i % per));
    }

    static long mask(int size) {
        return size == 8 ? -1L : (1L << (8 * size)) - 1;
    }

    /** Whether element i of size bytes lives in the high doubleword. */
    static boolean inHi(int size, int i) {
        return i < 8 / size;
    }

    static void set(CpuState s, int vsr, long hi, long lo) {
        s.vsrHi[vsr] = hi;
        s.vsrLo[vsr] = lo;
    }

    /** Byte-reverse each element of {@code size} bytes within a doubleword. */
    static long bswapElems(long x, int size) {
        return switch (size) {
            case 1 -> x;
            case 2 -> ((x & 0x00ff00ff00ff00ffL) << 8) | ((x >>> 8) & 0x00ff00ff00ff00ffL);
            case 4 -> {
                long r = Long.reverseBytes(x);
                yield (r >>> 32) | (r << 32);
            }
            default -> Long.reverseBytes(x);
        };
    }
}
