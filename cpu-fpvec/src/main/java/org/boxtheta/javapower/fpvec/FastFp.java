package org.boxtheta.javapower.fpvec;

import java.lang.reflect.Method;

/**
 * Host-double fast paths for binary64 and binary32 arithmetic, bit-exact with {@link SoftFloat}.
 *
 * <p>Every fast path is split into three steps:
 * <ol>
 *   <li>An RN function ({@link #add}, {@link #mul}, {@link #div}, {@link #sqrt}, {@link #fma},
 *       {@link #fromLong}) checks that the operands are normal (for add also zero) and
 *       computes the binary64 round-to-nearest result {@code rn} of the exact value v with the
 *       host's arithmetic. It returns {@link #MISS} unless rn is far enough from the overflow
 *       and underflow thresholds that the error term of step 2 is exact (no intermediate result
 *       underflows or overflows).</li>
 *   <li>An error function ({@code addErr} and so on) returns a double with the sign of v - rn:
 *       zero exactly when rn is exact, NaN when the sign cannot be computed exactly on this host.
 *       It is computed with an error-free transformation (TwoSum, the FMA residual or Dekker's
 *       product, Boldo and Muller's ErrFma).</li>
 *   <li>{@link #roundD} or {@link #roundS} turns (rn, error) into the result of rounding v once
 *       to binary64 or to binary32 precision under any of the four FPSCR rounding modes, and
 *       returns MISS unless that result is a normal number far from the overflow and underflow
 *       thresholds. So the only exception a fast path can raise is inexact, which is
 *       {@link #inexactD} or {@link #inexactS}; overflow, underflow (and so the exponent re-bias
 *       of enabled OE/UE and the tininess rules), invalid operations, NaNs, zeros and
 *       subnormals all go to {@link SoftFloat}.</li>
 * </ol>
 *
 * <p>Single precision rounds the exact value once: rn and the sign of v - rn determine the
 * binary32 rounding of v exactly (the binary32 grid points and midpoints are binary64 numbers,
 * so v and rn lie between the same ones unless rn is one of them, and then the sign decides).
 *
 * <p>Round-to-nearest, the common case, is kept free of branches on the data where possible
 * (only the zero test of the error), since such branches mispredict on real operands.
 *
 * <p>Hosts without a hardware FMA ({@link #HOST_FMA} false, for example HotSpot with
 * {@code -XX:-UseFMA}, where {@link Math#fma} is a slow software routine) use Dekker's exact
 * product for the error terms instead, and a fused multiply-add whose product is not exact in
 * binary64 goes to SoftFloat. Both variants give the same signs, so results never depend on
 * the host.
 */
final class FastFp {
    private FastFp() {}

    /** Sentinel: never a fast-path result (fast paths never produce NaNs). */
    static final long MISS = 0x7ff8dead0000beefL;
    /** An error whose sign cannot be computed exactly on this host. */
    static final double UNKNOWN = Double.NaN;

    private static final long SIGN = Long.MIN_VALUE;
    /** Veltkamp's splitting constant, 2^27 + 1. */
    private static final double SPLIT = 134217729.0;

    /**
     * Whether {@link Math#fma} is a hardware instruction here. Read from HotSpot's UseFMA flag
     * (system property {@code javapower.fpvec.hostFma} overrides it); false when unknown.
     */
    static final boolean HOST_FMA = hostFma();

    /** Tests only: false sends every instruction to the SoftFloat path, the reference. */
    static boolean on = true;

    private static boolean hostFma() {
        String p = System.getProperty("javapower.fpvec.hostFma");
        if (p != null) {
            return Boolean.parseBoolean(p);
        }
        try {
            Class<?> factory = Class.forName("java.lang.management.ManagementFactory");
            Class<?> bean = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
            Object b = factory.getMethod("getPlatformMXBean", Class.class).invoke(null, bean);
            Object opt = bean.getMethod("getVMOption", String.class).invoke(b, "UseFMA");
            Method value = Class.forName("com.sun.management.VMOption").getMethod("getValue");
            return Boolean.parseBoolean((String) value.invoke(opt));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    static double d(long x) {
        return Double.longBitsToDouble(x);
    }

    static long bits(double x) {
        return Double.doubleToRawLongBits(x);
    }

    /** Biased exponent field. */
    static int ef(long x) {
        return (int) (x >>> 52) & 0x7ff;
    }

    static boolean normal(long x) {
        int e = ef(x);
        return e != 0 && e != 0x7ff;
    }

    /** An RN result in this range leaves room for exact error terms and is far from overflow. */
    private static boolean safe(long r) {
        int e = ef(r);
        return e >= 120 && e <= 2045;
    }

    // ------------------------------------------------------------------ RN results

    /** Normal or zero (x + 0 is common in compiled code, and exact). */
    private static boolean normalOrZero(long x) {
        int e = ef(x);
        return e != 0x7ff && (e != 0 || (x << 1) == 0);
    }

    /**
     * RN(a + b) for normal or zero operands; MISS otherwise or when the result is zero or near
     * the limits (so a zero operand gives the other operand, exactly, which TwoSum confirms).
     */
    static long add(long a, long b) {
        if (!normalOrZero(a) || !normalOrZero(b)) {
            return MISS;
        }
        long r = bits(d(a) + d(b));
        return safe(r) ? r : MISS;
    }

    static long mul(long a, long b) {
        if (!normal(a) || !normal(b)) {
            return MISS;
        }
        long r = bits(d(a) * d(b));
        return safe(r) ? r : MISS;
    }

    /** RN(a / b); the dividend must also be clear of the subnormal range (exact residual). */
    static long div(long a, long b) {
        if (!normal(a) || !normal(b) || ef(a) < 120) {
            return MISS;
        }
        long r = bits(d(a) / d(b));
        return safe(r) ? r : MISS;
    }

    /** RN(sqrt(b)) for positive normal b clear of the subnormal range (the result always is). */
    static long sqrt(long b) {
        if (b < 0 || ef(b) < 120 || ef(b) == 0x7ff) {
            return MISS;
        }
        return bits(Math.sqrt(d(b)));
    }

    /**
     * RN(a * c + b) with one rounding. The product must lie within about [2^-896, 2^956] and the
     * addend in [2^-903, 2^1023) or be zero (an accumulator's first step), which keeps every
     * term of the exact error clear of underflow and overflow. Without a hardware FMA only a
     * product that is exact in binary64 qualifies.
     */
    static long fma(long a, long c, long b) {
        if (!normal(a) || !normal(c)) {
            return MISS;
        }
        int ep = ef(a) + ef(c);
        int eb = ef(b);
        if (ep < 1150 || ep > 3000 || ((eb < 120 || eb > 2045) && (b << 1) != 0)) {
            return MISS; // a zero addend leaves the nonzero product: x*y + 0 = x*y
        }
        double x = d(a), y = d(c), z = d(b);
        double r;
        if (exactProduct(a, c)) {
            r = x * y + z; // the product is exact: one rounding
        } else if (HOST_FMA) {
            r = Math.fma(x, y, z);
        } else {
            return MISS;
        }
        long rb = bits(r);
        return safe(rb) ? rb : MISS;
    }

    /** Whether the significands of a and c have at most 53 significant bits together. */
    static boolean exactProduct(long a, long c) {
        return Long.numberOfTrailingZeros(a | (1L << 52)) + Long.numberOfTrailingZeros(c | (1L << 52)) >= 53;
    }

    /** RN of a nonzero 64-bit integer (unsigned: below 2^63 only); MISS for zero. */
    static long fromLong(long x, boolean signed) {
        if (x == 0 || (!signed && x < 0)) {
            return MISS;
        }
        return bits((double) x);
    }

    // ------------------------------------------------------------------ errors: sign of v - rn

    /** TwoSum (Knuth): the error of a + b, exactly. */
    static double addErr(long a, long b, long rn) {
        double x = d(a), y = d(b), r = d(rn);
        double bb = r - x;
        return (x - (r - bb)) + (y - bb);
    }

    static double mulErr(long a, long b, long rn) {
        double x = d(a), y = d(b), r = d(rn);
        if (HOST_FMA) {
            return Math.fma(x, y, -r);
        }
        return mulErrSplit(x, y, r);
    }

    /** Dekker's exact product: x*y - p = (((xh*yh - p) + xh*yl) + xl*yh) + xl*yl, p = RN(x*y). */
    static double mulErrSplit(double x, double y, double p) {
        if (Math.abs(x) >= 0x1p995 || Math.abs(y) >= 0x1p995) {
            return UNKNOWN; // the split would overflow
        }
        double c = SPLIT * x, xh = c - (c - x), xl = x - xh;
        c = SPLIT * y;
        double yh = c - (c - y), yl = y - yh;
        return (((xh * yh - p) + xh * yl) + xl * yh) + xl * yl;
    }

    /**
     * x/y - r has the sign of the residual x - r*y (exact in binary64) times the sign of y
     * (a multiplication by +-1, which is exact).
     */
    static double divErr(long a, long b, long rn) {
        double x = d(a), y = d(b), r = d(rn);
        double res = HOST_FMA ? Math.fma(-r, y, x) : divResidualSplit(x, y, r);
        return res * Math.copySign(1.0, y);
    }

    /** x - r*y with r*y = h + l exactly (Dekker); x - h is exact (Sterbenz), one rounding after. */
    static double divResidualSplit(double x, double y, double r) {
        if (Math.abs(r) >= 0x1p995 || Math.abs(y) >= 0x1p995 || Math.abs(x) >= 0x1p1022) {
            return UNKNOWN; // a split or a partial product (about x) would overflow
        }
        double c = SPLIT * r, rh = c - (c - r), rl = r - rh;
        c = SPLIT * y;
        double yh = c - (c - y), yl = y - yh;
        double h = r * y;
        double l = (((rh * yh - h) + rh * yl) + rl * yh) + rl * yl;
        return (x - h) - l;
    }

    /** sqrt(x) - r has the sign of x - r*r. */
    static double sqrtErr(long b, long rn) {
        double x = d(b), r = d(rn);
        if (HOST_FMA) {
            return Math.fma(-r, r, x);
        }
        return sqrtErrSplit(x, r);
    }

    static double sqrtErrSplit(double x, double r) {
        if (x >= 0x1p1022) {
            return UNKNOWN; // a partial product (about x) would overflow
        }
        double c = SPLIT * r, rh = c - (c - r), rl = r - rh;
        double h = r * r;
        double l = (((rh * rh - h) + rh * rl) + rl * rh) + rl * rl;
        return (x - h) - l;
    }

    /**
     * The error of (a*c + b) - rn: with an exact product, TwoSum; otherwise ErrFma, which needs
     * the hardware FMA (fma() takes no other case without one).
     */
    static double fmaErr(long a, long c, long b, long rn) {
        double x = d(a), y = d(c), z = d(b), r = d(rn);
        if (exactProduct(a, c)) {
            double p = x * y;
            double bb = r - p;
            return (p - (r - bb)) + (z - bb);
        }
        return HOST_FMA ? errFma(x, y, z, r) : UNKNOWN;
    }

    /**
     * Boldo and Muller's ErrFma ("Exact and approximated error of the FMA", IEEE TC 60(2), 2011):
     * x*y + z = r + r2 + r3 exactly for r = RN(x*y + z), with |r3| <= ulp(r2)/2, so RN(r2 + r3)
     * has the sign of the error and is zero only when it is.
     */
    static double errFma(double x, double y, double z, double r) {
        double u1 = x * y, u2 = Math.fma(x, y, -u1);
        double a1 = z + u2, bv = a1 - z, a2 = (z - (a1 - bv)) + (u2 - bv);
        double b1 = u1 + a1, bw = b1 - u1, b2 = (u1 - (b1 - bw)) + (a1 - bw);
        double g = (b1 - r) + b2;
        double r2 = g + a2, r3 = a2 - (r2 - g);
        return r2 + r3;
    }

    /** The error of the integer x converted by {@link #fromLong}: x - rn as a double. */
    static double fromLongErr(long x, long rn) {
        double r = d(rn);
        if (r >= 0x1p63) {
            return -1; // x < 2^63 (signed, or unsigned below 2^63)
        }
        return x - (long) r; // within 2^11 of each other: no overflow, exact
    }

    // ------------------------------------------------------------------ final rounding

    /**
     * The binary64 result of rounding v under {@code mode}, given rn = RN(v) as one of the RN
     * functions above returned it and err = v - rn (sign only). MISS when err is unknown or a
     * directed rounding leaves the safe range.
     */
    static long roundD(long rn, double err, int mode) {
        if (err != err) {
            return MISS;
        }
        if (mode == SoftFloat.RN || err == 0) {
            return rn;
        }
        boolean neg = rn < 0;
        boolean above = (err > 0) != neg; // |v| > |rn|
        boolean away = mode == SoftFloat.RP ? !neg : mode == SoftFloat.RM && neg;
        long r = rn;
        if (away) {
            if (above) r = rn + 1; // the next binary64 away from zero
        } else if (!above) {
            r = rn - 1; // the next binary64 toward zero
        }
        return safe(r) ? r : MISS;
    }

    static boolean inexactD(double err) {
        return err != 0;
    }

    private static final long ULP_S = 1L << 29, HALF_S = 1L << 28, LOW_S = ULP_S - 1;

    /**
     * The result of rounding v to binary32 precision under {@code mode}, in a binary64
     * container, given rn = RN(v) to binary64 and err = v - rn (sign only). MISS when err is
     * unknown or v or the result is outside [2^-125, 2^128): no binary32 underflow (tininess is
     * detected before rounding) or overflow.
     */
    static long roundS(long rn, double err, int mode) {
        int e = ef(rn);
        if (err != err || e < 898 || e > 1150) {
            return MISS;
        }
        long low = rn & LOW_S;
        long r;
        if (mode == SoftFloat.RN && (low != HALF_S || err == 0)) {
            // Not a binary32 midpoint, or exactly one (ties to even): the host's conversion.
            r = bits((double) (float) d(rn));
        } else {
            r = roundSlow(rn, err, mode, low);
        }
        int re = ef(r);
        return re >= 898 && re <= 1150 ? r : MISS;
    }

    /** {@link #roundS} for the directed modes and for an inexact rn at a binary32 midpoint. */
    private static long roundSlow(long rn, double err, int mode, long low) {
        boolean neg = rn < 0;
        long mag = rn & ~SIGN;
        long k = mag - low; // |rn| truncated to binary32 precision
        int d = err == 0 ? 0 : (err > 0) != neg ? 1 : -1; // sign of |v| - |rn|
        int cmp; // |v| - k against half a binary32 ulp
        if (low == 0) {
            if (d == 0) {
                return rn; // exact
            }
            if (d < 0) {
                k -= ULP_S; // |v| is just below k
                cmp = 1;
            } else {
                cmp = -1;
            }
        } else {
            cmp = low > HALF_S ? 1 : low < HALF_S ? -1 : d;
        }
        boolean up = switch (mode) {
            case SoftFloat.RN -> cmp > 0 || (cmp == 0 && (k & ULP_S) != 0);
            case SoftFloat.RZ -> false;
            case SoftFloat.RP -> !neg;
            default -> neg;
        };
        long r = up ? k + ULP_S : k;
        return neg ? r | SIGN : r;
    }

    static boolean inexactS(long rn, double err) {
        return err != 0 || (rn & LOW_S) != 0;
    }

    /** {@link #roundD} for SoftFloat.D, {@link #roundS} for SoftFloat.DS. */
    static long round(int fmt, long rn, double err, int mode) {
        return fmt == SoftFloat.D ? roundD(rn, err, mode) : roundS(rn, err, mode);
    }

    /** Whether a fast-path result in {@code fmt} (as {@link #round}) is inexact. */
    static boolean inexact(int fmt, long rn, double err) {
        return fmt == SoftFloat.D ? inexactD(err) : inexactS(rn, err);
    }

    /** The SoftFloat flags of a fast-path result: XX when inexact. */
    static int flags(int fmt, long rn, double err) {
        return inexact(fmt, rn, err) ? SoftFloat.XX : 0;
    }

    // ------------------------------------------------------------------ composite operations

    private static final long ONE = 0x3ff0000000000000L;

    /**
     * 1/sqrt(b) the way QEMU's estimate helpers compute it: the binary64 square root rounded
     * under {@code mode}, then the reciprocal rounded to {@code fmt}. MISS as for the steps.
     */
    static long rsqrt(int fmt, long b, int mode) {
        long rq = sqrt(b);
        long q = rq == MISS ? MISS : roundD(rq, sqrtErr(b, rq), mode);
        long rn = q == MISS ? MISS : div(ONE, q);
        return rn == MISS ? MISS : round(fmt, rn, divErr(ONE, q, rn), mode);
    }

    /** Flags of an {@link #rsqrt} result: XX when either rounding was inexact. */
    static int rsqrtFlags(int fmt, long b, int mode) {
        long rq = sqrt(b);
        double eq = sqrtErr(b, rq);
        long q = roundD(rq, eq, mode);
        long rn = div(ONE, q);
        return inexactD(eq) ? SoftFloat.XX : flags(fmt, rn, divErr(ONE, q, rn));
    }

    // ------------------------------------------------------------------ conversions and compares

    /**
     * {@link SoftFloat#toInt} for a value that rounds (under {@code mode}) to an integer in range
     * of the destination: no invalid operation, only inexact. MISS for NaNs, infinities and
     * out-of-range values, and for unsigned 64-bit results of 2^63 and above. 32-bit results are
     * sign- or zero-extended as SoftFloat returns them.
     */
    static long toInt(long b, int nbits, boolean signed, int mode) {
        double x = d(b);
        if (!(Math.abs(x) < 0x1p64)) {
            return MISS;
        }
        double t = switch (mode) {
            case SoftFloat.RN -> Math.rint(x);
            case SoftFloat.RZ -> x < 0 ? Math.ceil(x) : Math.floor(x);
            case SoftFloat.RP -> Math.ceil(x);
            default -> Math.floor(x);
        };
        double lo = !signed ? 0 : nbits == 32 ? -0x1p31 : -0x1p63;
        double hi = nbits == 32 ? (signed ? 0x1p31 : 0x1p32) : 0x1p63; // exclusive
        if (!(t >= lo && t < hi)) {
            return MISS;
        }
        long r = (long) t;
        return nbits == 32 && !signed ? r & 0xffffffffL : r;
    }

    /** Flags of a {@link #toInt} result r: XX unless the operand was that integer. */
    static int toIntFlags(long b, long r) {
        return (double) r != d(b) ? SoftFloat.XX : 0;
    }

    /** SoftFloat's compare result for operands that are not NaNs. */
    static int compare(long a, long b) {
        double x = d(a), y = d(b);
        return x < y ? SoftFloat.CMP_LT : x > y ? SoftFloat.CMP_GT : SoftFloat.CMP_EQ;
    }

    // ------------------------------------------------------------------ binary32 elements

    /**
     * A normal or zero binary32 operand widened to binary64, or MISS (subnormals, which VSCR[NJ]
     * would flush, infinities and NaNs). The RN functions take a zero only where it is exact.
     */
    static long widenS(int w) {
        int e = (w >>> 23) & 0xff;
        if (e == 0xff || (e == 0 && (w << 1) != 0)) {
            return MISS;
        }
        return Fpscr.toDouble(w);
    }

    /** A {@link #roundS} result as binary32 bits. */
    static int narrowS(long r) {
        return ((int) (r >>> 32) & 0x80000000) | ((ef(r) - 896) << 23) | ((int) (r >>> 29) & 0x7fffff);
    }

    /** Never a packed binary32 result (those are non-negative). */
    static final long MISS_S = -1L;

    /** A rounded binary32 result packed with its inexact flag: bit 32 set when inexact. */
    private static long packS(long rn, double err, int mode) {
        long r = roundS(rn, err, mode);
        if (r == MISS) {
            return MISS_S;
        }
        return (inexactS(rn, err) ? 1L << 32 : 0) | (narrowS(r) & 0xffffffffL);
    }

    /** Binary operation {@code op} (as {@link #rn2}) on binary32 operands, rounded once to binary32. */
    static long arithS(int op, int wa, int wb, int mode) {
        long a = widenS(wa), b = widenS(wb);
        if (a == MISS || b == MISS) {
            return MISS_S;
        }
        long rn = rn2(op, a, b);
        return rn == MISS ? MISS_S : packS(rn, err2(op, a, b, rn), mode);
    }

    /** wa * wc + wb (wb negated first if {@code negAdd}) on binary32 operands, one rounding. */
    static long fmaS(int wa, int wc, int wb, boolean negAdd, int mode) {
        long a = widenS(wa), c = widenS(wc), b = widenS(negAdd ? wb ^ 0x80000000 : wb);
        if (a == MISS || b == MISS || c == MISS) {
            return MISS_S;
        }
        long rn = fma(a, c, b);
        return rn == MISS ? MISS_S : packS(rn, fmaErr(a, c, b, rn), mode);
    }

    static long sqrtS(int wb, int mode) {
        long b = widenS(wb);
        long rn = b == MISS ? MISS : sqrt(b);
        return rn == MISS ? MISS_S : packS(rn, sqrtErr(b, rn), mode);
    }

    // ------------------------------------------------------------------ dispatch by operation

    /** Binary operations, as {@link FpInsns} numbers them: 0 add, 1 sub, 2 mul, 3 div. */
    static long rn2(int op, long a, long b) {
        return switch (op) {
            case 0 -> add(a, b);
            case 1 -> add(a, b ^ SIGN);
            case 2 -> mul(a, b);
            default -> div(a, b);
        };
    }

    static double err2(int op, long a, long b, long rn) {
        return switch (op) {
            case 0 -> addErr(a, b, rn);
            case 1 -> addErr(a, b ^ SIGN, rn);
            case 2 -> mulErr(a, b, rn);
            default -> divErr(a, b, rn);
        };
    }
}
