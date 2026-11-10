package org.boxtheta.javapower.fpvec;

/**
 * Host-double fast paths for binary64 arithmetic in round-to-nearest-even. A fast path is taken
 * only when operands are normal and the result is far enough from the overflow and underflow
 * thresholds that (a) the host result is the IEEE result and (b) the rounding error can be
 * recovered exactly (TwoSum / FMA residual), which gives the inexact flag. Everything else
 * returns {@link #MISS} and goes through {@link SoftFloat}.
 */
final class FastFp {
    private FastFp() {}

    /** Sentinel: never a fast-path result (fast paths never produce NaNs). */
    static final long MISS = 0x7ff8dead0000beefL;

    private static boolean normal(long x) {
        int e = (int) (x >>> 52) & 0x7ff;
        return e != 0 && e != 0x7ff;
    }

    /** Result exponent field must leave room for exact error terms and no overflow/underflow. */
    private static boolean safe(long r) {
        int e = (int) (r >>> 52) & 0x7ff;
        return e >= 120 && e <= 2045;
    }

    /** op: 0 add, 1 sub, 2 mul, 3 div. */
    static long arith2(int op, long a, long b) {
        if (!normal(a) || !normal(b)) {
            return MISS;
        }
        if (op == 3 && ((a >>> 52) & 0x7ff) < 120) {
            return MISS; // keep the division residual a - r*b clear of the subnormal range
        }
        double x = Double.longBitsToDouble(a), y = Double.longBitsToDouble(b);
        double r = switch (op) {
            case 0 -> x + y;
            case 1 -> x - y;
            case 2 -> x * y;
            default -> x / y;
        };
        long rb = Double.doubleToRawLongBits(r);
        if (!safe(rb)) {
            return MISS;
        }
        return rb;
    }

    static boolean inexact2(int op, long a, long b, long rbits) {
        double x = Double.longBitsToDouble(a), y = Double.longBitsToDouble(b);
        double r = Double.longBitsToDouble(rbits);
        switch (op) {
            case 0, 1 -> {
                if (op == 1) y = -y;
                double bb = r - x;
                double err = (x - (r - bb)) + (y - bb);
                return err != 0;
            }
            case 2 -> {
                return Math.fma(x, y, -r) != 0;
            }
            default -> {
                return Math.fma(-r, y, x) != 0;
            }
        }
    }
}
