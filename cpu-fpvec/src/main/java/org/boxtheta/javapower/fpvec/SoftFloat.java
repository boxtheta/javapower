package org.boxtheta.javapower.fpvec;

/**
 * Software IEEE 754 binary floating point with exact exception reporting, for binary16, binary32,
 * binary64, binary128 and "binary64 container rounded to binary32" (the PowerPC single-precision
 * arithmetic result format).
 *
 * <p>One mutable instance per thread ({@link #get()}); every operation is allocation-free. Operands
 * are unpacked into slots 0..2 (sign, class, exponent, 113-bit significand with the leading bit at
 * bit 112), the exact result is formed in a 256-bit accumulator and rounded once by
 * {@link #round}. Exception flags accumulate in {@link #flags} using FPSCR bit positions.
 *
 * <p>Behaviour follows the PowerPC conventions (and QEMU's model of them): tininess is detected
 * before rounding, NaN operands propagate in operand order (a, then b, then c) and are quieted,
 * the default NaN is positive, and 0*inf+qNaN returns the qNaN while still signalling VXIMZ.
 */
public final class SoftFloat {
    // Rounding modes (FPSCR[RN] encoding) plus round-half-away-from-zero.
    public static final int RN = 0, RZ = 1, RP = 2, RM = 3, RNA = 4, RODD = 5;

    // Formats. DS = binary64 container with binary32 precision and exponent range.
    public static final int S = 0, D = 1, Q = 2, DS = 3, H = 4;
    /** binary128 container rounded to 64-bit precision (QEMU's float128 -> floatx80 -> float128). */
    public static final int QX = 5;
    private static final int[] PREC = {24, 53, 113, 24, 11, 64};
    private static final int[] EMAX = {127, 1023, 16383, 127, 15, 16383};
    private static final int[] EMIN = {-126, -1022, -16382, -126, -14, -16382};

    // Flags, at their FPSCR bit positions (Java numbering).
    public static final int OX = 1 << 28, UX = 1 << 27, ZX = 1 << 26, XX = 1 << 25;
    public static final int VXSNAN = 1 << 24, VXISI = 1 << 23, VXIDI = 1 << 22, VXZDZ = 1 << 21;
    public static final int VXIMZ = 1 << 20, VXVC = 1 << 19, FR = 1 << 18;
    public static final int VXSQRT = 1 << 9, VXCVI = 1 << 8;
    /** Result was tiny before rounding (whether or not it was exact). Not an FPSCR bit. */
    public static final int TINY = 1;
    public static final int INVALID = VXSNAN | VXISI | VXIDI | VXZDZ | VXIMZ | VXVC | VXSQRT | VXCVI;

    // Operand classes.
    public static final int ZERO = 0, FIN = 1, INF = 2, QNAN = 3, SNAN = 4;

    private static final ThreadLocal<SoftFloat> TL = ThreadLocal.withInitial(SoftFloat::new);

    public static SoftFloat get() {
        return TL.get();
    }

    public int flags;
    public int mode;
    /** Flush denormal inputs/outputs to zero (VMX with VSCR[NJ]=1). */
    public boolean ftz;
    /** Result of quad-precision operations (and the high/low of any 128-bit integer result). */
    public long qh, ql;

    // Operand slots.
    final int[] sg = new int[3];
    final int[] cl = new int[3];
    final int[] ex = new int[3];
    final long[] mh = new long[3];
    final long[] ml = new long[3];
    /** For NaN operands: the quieted NaN in the operand's container format. */
    final long[] nh = new long[3];
    final long[] nl = new long[3];
    /** Container format each slot was unpacked from (for NaN format conversion). */
    final int[] fm = new int[3];

    // 256-bit accumulators.
    private long w3, w2, w1, w0;
    private long x3, x2, x1, x0;
    // Round input / output.
    private long rh, rl;

    /** Exponent re-bias on overflow/underflow (FPSCR[OE]/[UE] in QEMU's fp_status). */
    public boolean rebiasO, rebiasU;

    /** Plain IEEE state: no flush-to-zero, no re-bias. */
    public void reset(int rmode) {
        flags = 0;
        mode = rmode;
        ftz = false;
        rebiasO = false;
        rebiasU = false;
    }

    /** State for scalar FP and VSX: rounding mode and re-bias controls taken from the FPSCR. */
    public void resetFp(long fpscr) {
        flags = 0;
        mode = (int) fpscr & 3;
        ftz = false;
        rebiasO = (fpscr & Fpscr.OE) != 0;
        rebiasU = (fpscr & Fpscr.UE) != 0;
    }

    /** State for VMX floating point: round-to-nearest, NJ selects flush-to-zero (QEMU vec_status). */
    public void resetVec(int vscr) {
        flags = 0;
        mode = RN;
        ftz = (vscr & 0x10000) != 0;
        rebiasO = false;
        rebiasU = false;
    }

    // ------------------------------------------------------------------ unpacking

    /** Unpack a value of container format {@code fmt} (S/H in the low bits of {@code lo}; Q uses hi:lo). */
    public void unpack(int slot, int fmt, long hi, long lo) {
        switch (fmt) {
            case D, DS -> unpackD(slot, lo);
            case S -> unpackS(slot, (int) lo);
            case Q -> unpackQ(slot, hi, lo);
            case H -> unpackH(slot, (int) lo);
            default -> throw new IllegalArgumentException();
        }
    }

    public void unpackD(int i, long x) {
        fm[i] = D;
        sg[i] = (int) (x >>> 63);
        int e = (int) (x >>> 52) & 0x7ff;
        long f = x & 0xfffffffffffffL;
        if (e == 0x7ff) {
            if (f == 0) {
                cl[i] = INF;
            } else {
                cl[i] = (f & (1L << 51)) != 0 ? QNAN : SNAN;
                nh[i] = 0;
                nl[i] = x | (1L << 51);
            }
        } else if (e == 0) {
            if (f == 0 || ftz) {
                cl[i] = ZERO;
            } else {
                cl[i] = FIN;
                int msb = 63 - Long.numberOfLeadingZeros(f);
                ex[i] = -1022 - (52 - msb);
                place(i, f, msb);
            }
        } else {
            cl[i] = FIN;
            ex[i] = e - 1023;
            place(i, f | (1L << 52), 52);
        }
    }

    public void unpackS(int i, int w) {
        fm[i] = S;
        sg[i] = w >>> 31;
        int e = (w >>> 23) & 0xff;
        long f = w & 0x7fffff;
        if (e == 0xff) {
            if (f == 0) {
                cl[i] = INF;
            } else {
                cl[i] = (f & (1L << 22)) != 0 ? QNAN : SNAN;
                nh[i] = 0;
                nl[i] = (w | (1 << 22)) & 0xffffffffL;
            }
        } else if (e == 0) {
            if (f == 0 || ftz) {
                cl[i] = ZERO;
            } else {
                cl[i] = FIN;
                int msb = 63 - Long.numberOfLeadingZeros(f);
                ex[i] = -126 - (23 - msb);
                place(i, f, msb);
            }
        } else {
            cl[i] = FIN;
            ex[i] = e - 127;
            place(i, f | (1L << 23), 23);
        }
    }

    public void unpackH(int i, int w) {
        fm[i] = H;
        sg[i] = (w >>> 15) & 1;
        int e = (w >>> 10) & 0x1f;
        long f = w & 0x3ff;
        if (e == 0x1f) {
            if (f == 0) {
                cl[i] = INF;
            } else {
                cl[i] = (f & (1L << 9)) != 0 ? QNAN : SNAN;
                nh[i] = 0;
                nl[i] = (w | (1 << 9)) & 0xffffL;
            }
        } else if (e == 0) {
            if (f == 0) {
                cl[i] = ZERO;
            } else {
                cl[i] = FIN;
                int msb = 63 - Long.numberOfLeadingZeros(f);
                ex[i] = -14 - (10 - msb);
                place(i, f, msb);
            }
        } else {
            cl[i] = FIN;
            ex[i] = e - 15;
            place(i, f | (1L << 10), 10);
        }
    }

    public void unpackQ(int i, long hi, long lo) {
        fm[i] = Q;
        sg[i] = (int) (hi >>> 63);
        int e = (int) (hi >>> 48) & 0x7fff;
        long fh = hi & 0xffffffffffffL;
        if (e == 0x7fff) {
            if ((fh | lo) == 0) {
                cl[i] = INF;
            } else {
                cl[i] = (fh & (1L << 47)) != 0 ? QNAN : SNAN;
                nh[i] = hi | (1L << 47);
                nl[i] = lo;
            }
        } else if (e == 0) {
            if ((fh | lo) == 0) {
                cl[i] = ZERO;
            } else {
                cl[i] = FIN;
                int msb = fh != 0 ? 127 - Long.numberOfLeadingZeros(fh) : 63 - Long.numberOfLeadingZeros(lo);
                ex[i] = -16382 - (112 - msb);
                int sh = 112 - msb;
                shl128(fh, lo, sh);
                mh[i] = rh;
                ml[i] = rl;
            }
        } else {
            cl[i] = FIN;
            ex[i] = e - 16383;
            mh[i] = fh | (1L << 48);
            ml[i] = lo;
        }
    }

    /** Set a finite slot directly: value = (-1)^sign * m * 2^(e - msb(m)), m != 0, unsigned. */
    public void setFinite(int i, int sign, long m, int e) {
        sg[i] = sign;
        cl[i] = FIN;
        int msb = 63 - Long.numberOfLeadingZeros(m);
        ex[i] = e;
        place(i, m, msb);
    }

    private void place(int i, long m, int msb) {
        int sh = 112 - msb;
        if (sh >= 64) {
            mh[i] = m << (sh - 64);
            ml[i] = 0;
        } else {
            mh[i] = m >>> (64 - sh);
            ml[i] = m << sh;
        }
    }

    public int cls(int i) {
        return cl[i];
    }

    public int sign(int i) {
        return sg[i];
    }

    public int exp(int i) {
        return ex[i];
    }

    public static boolean isNan(int c) {
        return c >= QNAN;
    }

    // ------------------------------------------------------------------ 128-bit helpers

    private void shl128(long h, long l, int n) {
        if (n == 0) {
            rh = h;
            rl = l;
        } else if (n >= 128) {
            rh = 0;
            rl = 0;
        } else if (n >= 64) {
            rh = l << (n - 64);
            rl = 0;
        } else {
            rh = (h << n) | (l >>> (64 - n));
            rl = l << n;
        }
    }

    /** Shift right with the shifted-out bits ORed into bit 0. */
    private void shr128s(long h, long l, int n) {
        if (n == 0) {
            rh = h;
            rl = l;
        } else if (n >= 128) {
            rh = 0;
            rl = (h | l) != 0 ? 1 : 0;
        } else if (n >= 64) {
            long lost = l | (n > 64 ? h << (128 - n) : 0);
            rl = (n == 64 ? h : h >>> (n - 64)) | (lost != 0 ? 1 : 0);
            rh = 0;
        } else {
            long lost = l << (64 - n);
            rl = (l >>> n) | (h << (64 - n)) | (lost != 0 ? 1 : 0);
            rh = h >>> n;
        }
    }

    // ------------------------------------------------------------------ 256-bit accumulator

    private static int msb256(long a3, long a2, long a1, long a0) {
        if (a3 != 0) return 255 - Long.numberOfLeadingZeros(a3);
        if (a2 != 0) return 191 - Long.numberOfLeadingZeros(a2);
        if (a1 != 0) return 127 - Long.numberOfLeadingZeros(a1);
        if (a0 != 0) return 63 - Long.numberOfLeadingZeros(a0);
        return -1;
    }

    /** w = w << n (n < 256). */
    private void wShl(int n) {
        while (n >= 64) {
            w3 = w2; w2 = w1; w1 = w0; w0 = 0;
            n -= 64;
        }
        if (n > 0) {
            w3 = (w3 << n) | (w2 >>> (64 - n));
            w2 = (w2 << n) | (w1 >>> (64 - n));
            w1 = (w1 << n) | (w0 >>> (64 - n));
            w0 = w0 << n;
        }
    }

    /** x = x >> n with sticky into bit 0. */
    private void xShrSticky(int n) {
        if (n <= 0) return;
        if (n >= 256) {
            boolean nz = (x3 | x2 | x1 | x0) != 0;
            x3 = x2 = x1 = 0;
            x0 = nz ? 1 : 0;
            return;
        }
        boolean sticky = false;
        while (n >= 64) {
            sticky |= x0 != 0;
            x0 = x1; x1 = x2; x2 = x3; x3 = 0;
            n -= 64;
        }
        if (n > 0) {
            sticky |= (x0 << (64 - n)) != 0;
            x0 = (x0 >>> n) | (x1 << (64 - n));
            x1 = (x1 >>> n) | (x2 << (64 - n));
            x2 = (x2 >>> n) | (x3 << (64 - n));
            x3 = x3 >>> n;
        }
        if (sticky) x0 |= 1;
    }

    private void xShl(int n) {
        while (n >= 64) {
            x3 = x2; x2 = x1; x1 = x0; x0 = 0;
            n -= 64;
        }
        if (n > 0) {
            x3 = (x3 << n) | (x2 >>> (64 - n));
            x2 = (x2 << n) | (x1 >>> (64 - n));
            x1 = (x1 << n) | (x0 >>> (64 - n));
            x0 = x0 << n;
        }
    }

    /**
     * Convert the 256-bit value w (nonzero), whose bit {@code msb} is the leading one and whose value
     * is w * 2^scale, into the round input: rh:rl with leading one at bit 125 and sticky in bit 0.
     * Returns the exponent of the leading bit.
     */
    private int wToRound(int scale) {
        int msb = msb256(w3, w2, w1, w0);
        int e = scale + msb;
        if (msb > 125) {
            x3 = w3; x2 = w2; x1 = w1; x0 = w0;
            xShrSticky(msb - 125);
            rh = x1;
            rl = x0;
        } else {
            x3 = w3; x2 = w2; x1 = w1; x0 = w0;
            xShl(125 - msb);
            rh = x1;
            rl = x0;
        }
        return e;
    }

    // ------------------------------------------------------------------ rounding and packing

    /** Exponent re-bias applied by enabled overflow/underflow (3/4 of 2^E, as QEMU's softfloat). */
    private static final int[] REBIAS = {192, 1536, 24576, 192, 24, 24576};

    // Results of roundBits.
    private long kh, kl;
    private boolean rInexact;

    /**
     * Round (rh:rl, leading one at or below bit 125, sticky in bit 0) to {@code p} significant bit
     * positions counted from bit 125 down, under {@link #mode}. Leaves the kept bits in kh:kl and
     * returns true on carry out of the top (kept == 2^p).
     */
    private boolean roundBits(long h, long l, int p, int sign) {
        int drop = 126 - p; // >= 13
        boolean half;
        boolean below;
        if (drop - 1 >= 64) {
            half = ((h >>> (drop - 1 - 64)) & 1) != 0;
            below = l != 0 || (drop - 1 - 64 > 0 && (h << (128 - (drop - 1))) != 0);
            kh = 0;
            kl = drop == 64 ? h : h >>> (drop - 64);
        } else {
            half = ((l >>> (drop - 1)) & 1) != 0;
            below = drop - 1 > 0 && (l << (64 - (drop - 1))) != 0;
            kl = (l >>> drop) | (h << (64 - drop));
            kh = h >>> drop;
        }
        rInexact = half || below;
        boolean up = switch (mode) {
            case RN -> half && (below || (kl & 1) != 0);
            case RNA -> half;
            case RZ -> false;
            case RP -> rInexact && sign == 0;
            case RM -> rInexact && sign != 0;
            default -> rInexact && (kl & 1) == 0; // round to odd
        };
        if (up) {
            kl++;
            if (kl == 0) kh++;
            return p >= 64 ? (kh >>> (p - 64)) != 0 : (kl >>> p) != 0;
        }
        return false;
    }

    /**
     * Round (-1)^sign * (rh:rl) * 2^(e - 125) (leading one at bit 125, sticky folded into bit 0) to
     * format {@code fmt} under {@link #mode}, set flags, and return the packed result. Mirrors the
     * PowerPC configuration of QEMU's softfloat: tininess before rounding, exponent re-bias when
     * {@link #rebiasO}/{@link #rebiasU} are set (FPSCR[OE]/[UE]), and flush-to-zero decided after
     * rounding when {@link #ftz} is set (VMX with VSCR[NJ]=1).
     */
    private long round(int fmt, int sign, int e) {
        int p = PREC[fmt];
        int emin = EMIN[fmt];
        long h = rh, l = rl;
        boolean tiny = e < emin;
        if (tiny && rebiasU) {
            flags |= UX;
            if (roundBits(h, l, p, sign)) {
                shiftKeptRight(p);
                e++;
            }
            if (rInexact) flags |= XX;
            return packRebias(fmt, sign, e + REBIAS[fmt]);
        }
        if (tiny) {
            flags |= TINY;
            if (ftz) {
                // Tininess after rounding: only a value that rounds up to 2^emin escapes the flush.
                boolean carry = roundBits(h, l, p, sign);
                if (!(carry && e + 1 == emin)) {
                    return packZero(fmt, sign);
                }
            }
            shr128s(h, l, emin - e);
            h = rh;
            l = rl;
            e = emin;
        }
        if (roundBits(h, l, p, sign)) {
            shiftKeptRight(p);
            e++;
        }
        if (rInexact) {
            flags |= XX;
            if (tiny) flags |= UX;
        }
        if (e > EMAX[fmt]) {
            flags |= OX;
            if (rebiasO) {
                return packRebias(fmt, sign, e - REBIAS[fmt]);
            }
            flags |= XX;
            boolean toInf = switch (mode) {
                case RN, RNA -> true;
                case RP -> sign == 0;
                case RM -> sign != 0;
                default -> false;
            };
            return toInf ? packInf(fmt, sign) : packMax(fmt, sign);
        }
        return pack(fmt, sign, e, kh, kl);
    }

    /** After a carry out of the top, kept == 2^p: renormalize to p bits. */
    private void shiftKeptRight(int p) {
        if (p >= 64) {
            kl = (kl >>> 1) | (kh << 63);
            kh >>>= 1;
        } else {
            kl >>>= 1;
        }
    }

    /** Pack a normal significand kh:kl with unbiased exponent e whose biased field wraps (re-bias). */
    private long packRebias(int fmt, int s, int e) {
        switch (fmt) {
            case D:
                return ((long) s << 63) | ((long) ((e + 1023) & 0x7ff) << 52) | (kl & 0xfffffffffffffL);
            case S:
                return ((long) s << 31) | ((long) ((e + 127) & 0xff) << 23) | (kl & 0x7fffff);
            case H:
                return ((long) s << 15) | ((long) ((e + 15) & 0x1f) << 10) | (kl & 0x3ff);
            case Q:
                qh = ((long) s << 63) | ((long) ((e + 16383) & 0x7fff) << 48) | (kh & 0xffffffffffffL);
                ql = kl;
                return qh;
            case QX:
                qh = ((long) s << 63) | ((long) ((e + 16383) & 0x7fff) << 48) | ((kl >>> 15) & 0xffffffffffffL);
                ql = kl << 49;
                return qh;
            default: { // DS: binary32 exponent field re-expressed in the binary64 container
                int b32 = e + 127;
                int field = b32 == 0 ? 897 : (b32 + 896) & 0x7ff;
                return ((long) s << 63) | ((long) field << 52) | ((kl & 0x7fffff) << 29);
            }
        }
    }

    /** Pack (-1)^s * k * 2^(e-(p-1)) where k < 2^p; k has bit p-1 set unless denormal/zero. */
    private long pack(int fmt, int s, int e, long kh, long kl) {
        int p = PREC[fmt];
        boolean normal = p > 64 ? ((kh >>> (p - 1 - 64)) & 1) != 0 : ((kl >>> (p - 1)) & 1) != 0;
        switch (fmt) {
            case D: {
                long bits = normal ? ((long) (e + 1023) << 52) | (kl & 0xfffffffffffffL) : kl;
                return ((long) s << 63) | bits;
            }
            case S: {
                long bits = normal ? ((long) (e + 127) << 23) | (kl & 0x7fffff) : kl;
                return ((long) s << 31) | bits;
            }
            case H: {
                long bits = normal ? ((long) (e + 15) << 10) | (kl & 0x3ff) : kl;
                return ((long) s << 15) | bits;
            }
            case Q: {
                long hi = normal ? ((long) (e + 16383) << 48) | (kh & 0xffffffffffffL) : kh;
                qh = ((long) s << 63) | hi;
                ql = kl;
                return qh;
            }
            case QX: {
                long fh = kl >>> 15, fl = kl << 49; // 64-bit significand into the 113-bit one
                long hi = normal ? ((long) (e + 16383) << 48) | (fh & 0xffffffffffffL) : fh;
                qh = ((long) s << 63) | hi;
                ql = fl;
                return qh;
            }
            default: { // DS: binary32-precision value in binary64 container (always a normal double)
                if (kl == 0) return (long) s << 63;
                int msb = 63 - Long.numberOfLeadingZeros(kl);
                int de = e - (p - 1) + msb;
                long frac = (kl << (52 - msb)) & 0xfffffffffffffL;
                return ((long) s << 63) | ((long) (de + 1023) << 52) | frac;
            }
        }
    }

    public long packZero(int fmt, int s) {
        return switch (fmt) {
            case S -> (long) s << 31;
            case H -> (long) s << 15;
            case Q, QX -> {
                qh = (long) s << 63;
                ql = 0;
                yield qh;
            }
            default -> (long) s << 63;
        };
    }

    public long packInf(int fmt, int s) {
        return switch (fmt) {
            case S -> ((long) s << 31) | 0x7f800000L;
            case H -> ((long) s << 15) | 0x7c00L;
            case Q, QX -> {
                qh = ((long) s << 63) | 0x7fff000000000000L;
                ql = 0;
                yield qh;
            }
            default -> ((long) s << 63) | 0x7ff0000000000000L;
        };
    }

    private long packMax(int fmt, int s) {
        return switch (fmt) {
            case S -> ((long) s << 31) | 0x7f7fffffL;
            case H -> ((long) s << 15) | 0x7bffL;
            case Q -> {
                qh = ((long) s << 63) | 0x7ffeffffffffffffL;
                ql = -1L;
                yield qh;
            }
            case QX -> {
                qh = ((long) s << 63) | 0x7ffeffffffffffffL;
                ql = 0xfffe000000000000L;
                yield qh;
            }
            case DS -> ((long) s << 63) | 0x47efffffe0000000L;
            default -> ((long) s << 63) | 0x7fefffffffffffffL;
        };
    }

    public long defaultNan(int fmt) {
        return switch (fmt) {
            case S -> 0x7fc00000L;
            case H -> 0x7e00L;
            case Q, QX -> {
                qh = 0x7fff800000000000L;
                ql = 0;
                yield qh;
            }
            default -> 0x7ff8000000000000L;
        };
    }

    private long nanOf(int fmt, int i) {
        if (fmt == Q || fmt == QX) {
            qh = nh[i];
            ql = nl[i];
            return qh;
        }
        if (fmt == DS) {
            return nl[i] & ~0x1fffffffL; // the NaN passes through binary32 precision
        }
        return nl[i];
    }

    /** Round the finite value in slot i to format fmt (used by conversions and x+0). */
    private long roundSlot(int fmt, int i) {
        rh = (mh[i] << 13) | (ml[i] >>> 51);
        rl = ml[i] << 13;
        return round(fmt, sg[i], ex[i]);
    }

    // ------------------------------------------------------------------ arithmetic

    /** NaN propagation for two operands in order (i, j). Returns true and sets res if a NaN is present. */
    private boolean nan2(int fmt, int i, int j) {
        boolean ni = cl[i] >= QNAN, nj = cl[j] >= QNAN;
        if (!ni && !nj) return false;
        if (cl[i] == SNAN || cl[j] == SNAN) flags |= VXSNAN;
        res = nanOf(fmt, ni ? i : j);
        return true;
    }

    private long res;

    /** a + b (b negated if {@code sub}). */
    public long add(int fmt, boolean sub) {
        if (sub && cl[1] < QNAN) sg[1] ^= 1;
        if (nan2(fmt, 0, 1)) return res;
        int ca = cl[0], cb = cl[1];
        if (ca == INF || cb == INF) {
            if (ca == INF && cb == INF && sg[0] != sg[1]) {
                flags |= VXISI;
                return defaultNan(fmt);
            }
            return packInf(fmt, ca == INF ? sg[0] : sg[1]);
        }
        if (ca == ZERO && cb == ZERO) {
            int s = sg[0] == sg[1] ? sg[0] : (mode == RM ? 1 : 0);
            return packZero(fmt, s);
        }
        if (cb == ZERO) return roundSlot(fmt, 0);
        if (ca == ZERO) return roundSlot(fmt, 1);
        // w = |a| at scale ex-112, x = |b|
        w3 = 0; w2 = 0; w1 = mh[0]; w0 = ml[0];
        x3 = 0; x2 = 0; x1 = mh[1]; x0 = ml[1];
        return addWX(fmt, sg[0], ex[0] - 112, sg[1], ex[1] - 112);
    }

    /**
     * Add w*2^ws (sign sw) and x*2^xs (sign sx), both nonzero, and round. Destroys w and x.
     */
    private long addWX(int fmt, int sw, int ws, int sx, int xs) {
        // Normalize both to leading one at bit 250.
        int mw = msb256(w3, w2, w1, w0);
        int mx = msb256(x3, x2, x1, x0);
        wShl(250 - mw);
        ws -= 250 - mw;
        xShl(250 - mx);
        xs -= 250 - mx;
        // Ensure w has the larger magnitude.
        boolean swap = xs > ws || (xs == ws && cmpXW() > 0);
        if (swap) {
            long t;
            t = w3; w3 = x3; x3 = t;
            t = w2; w2 = x2; x2 = t;
            t = w1; w1 = x1; x1 = t;
            t = w0; w0 = x0; x0 = t;
            int ti = ws; ws = xs; xs = ti;
            ti = sw; sw = sx; sx = ti;
        }
        xShrSticky(ws - xs);
        if (sw == sx) {
            long c;
            long s0 = w0 + x0;
            c = Long.compareUnsigned(s0, w0) < 0 ? 1 : 0;
            long s1 = w1 + x1 + c;
            c = (c == 0 ? Long.compareUnsigned(s1, w1) < 0 : Long.compareUnsigned(s1, w1) <= 0) ? 1 : 0;
            long s2 = w2 + x2 + c;
            c = (c == 0 ? Long.compareUnsigned(s2, w2) < 0 : Long.compareUnsigned(s2, w2) <= 0) ? 1 : 0;
            w3 = w3 + x3 + c;
            w2 = s2; w1 = s1; w0 = s0;
        } else {
            long b;
            long d0 = w0 - x0;
            b = Long.compareUnsigned(w0, x0) < 0 ? 1 : 0;
            long d1 = w1 - x1 - b;
            b = (b == 0 ? Long.compareUnsigned(w1, x1) < 0 : Long.compareUnsigned(w1, x1) <= 0) ? 1 : 0;
            long d2 = w2 - x2 - b;
            b = (b == 0 ? Long.compareUnsigned(w2, x2) < 0 : Long.compareUnsigned(w2, x2) <= 0) ? 1 : 0;
            w3 = w3 - x3 - b;
            w2 = d2; w1 = d1; w0 = d0;
            if ((w3 | w2 | w1 | w0) == 0) {
                return packZero(fmt, mode == RM ? 1 : 0);
            }
        }
        int e = wToRound(ws);
        return round(fmt, sw, e);
    }

    private int cmpXW() {
        int c = Long.compareUnsigned(x3, w3);
        if (c != 0) return c;
        c = Long.compareUnsigned(x2, w2);
        if (c != 0) return c;
        c = Long.compareUnsigned(x1, w1);
        if (c != 0) return c;
        return Long.compareUnsigned(x0, w0);
    }

    /** w = m(i) * m(j), exact 226-bit product. */
    private void mulSlots(int i, int j) {
        long ah = mh[i], al = ml[i], bh = mh[j], bl = ml[j];
        // (ah*2^64 + al) * (bh*2^64 + bl)
        long p00h = Math.unsignedMultiplyHigh(al, bl), p00l = al * bl;
        long p01h = Math.unsignedMultiplyHigh(al, bh), p01l = al * bh;
        long p10h = Math.unsignedMultiplyHigh(ah, bl), p10l = ah * bl;
        long p11h = Math.unsignedMultiplyHigh(ah, bh), p11l = ah * bh;
        long r0 = p00l;
        long r1 = p00h;
        long r2 = 0;
        long r3 = 0;
        long t = r1 + p01l;
        long c = Long.compareUnsigned(t, r1) < 0 ? 1 : 0;
        r1 = t;
        r2 += c;
        t = r1 + p10l;
        c = Long.compareUnsigned(t, r1) < 0 ? 1 : 0;
        r1 = t;
        r2 += c;
        t = r2 + p01h;
        c = Long.compareUnsigned(t, r2) < 0 ? 1 : 0;
        r2 = t;
        r3 += c;
        t = r2 + p10h;
        c = Long.compareUnsigned(t, r2) < 0 ? 1 : 0;
        r2 = t;
        r3 += c;
        t = r2 + p11l;
        c = Long.compareUnsigned(t, r2) < 0 ? 1 : 0;
        r2 = t;
        r3 += c;
        r3 += p11h;
        w3 = r3; w2 = r2; w1 = r1; w0 = r0;
    }

    public long mul(int fmt) {
        if (nan2(fmt, 0, 1)) return res;
        int ca = cl[0], cb = cl[1];
        int s = sg[0] ^ sg[1];
        if (ca == INF || cb == INF) {
            if (ca == ZERO || cb == ZERO) {
                flags |= VXIMZ;
                return defaultNan(fmt);
            }
            return packInf(fmt, s);
        }
        if (ca == ZERO || cb == ZERO) return packZero(fmt, s);
        mulSlots(0, 1);
        int e = wToRound(ex[0] + ex[1] - 224);
        return round(fmt, s, e);
    }

    public long div(int fmt) {
        if (nan2(fmt, 0, 1)) return res;
        int ca = cl[0], cb = cl[1];
        int s = sg[0] ^ sg[1];
        if (ca == INF) {
            if (cb == INF) {
                flags |= VXIDI;
                return defaultNan(fmt);
            }
            return packInf(fmt, s);
        }
        if (cb == INF) return packZero(fmt, s);
        if (cb == ZERO) {
            if (ca == ZERO) {
                flags |= VXZDZ;
                return defaultNan(fmt);
            }
            flags |= ZX;
            return packInf(fmt, s);
        }
        if (ca == ZERO) return packZero(fmt, s);
        // Restoring division producing n = p+3 quotient bits (leading one at n-1 or n-2).
        int n = PREC[fmt] + 3;
        long remh = mh[0], reml = ml[0];
        long dh = mh[1], dl = ml[1];
        int e = ex[0] - ex[1];
        if (Long.compareUnsigned(remh, dh) < 0 || (remh == dh && Long.compareUnsigned(reml, dl) < 0)) {
            // a < b: first quotient bit would be 0; pre-shift so the leading one is first.
            remh = (remh << 1) | (reml >>> 63);
            reml <<= 1;
            e--;
        }
        long qh = 0, ql = 0;
        for (int k = 0; k < n; k++) {
            qh = (qh << 1) | (ql >>> 63);
            ql <<= 1;
            if (Long.compareUnsigned(remh, dh) > 0 || (remh == dh && Long.compareUnsigned(reml, dl) >= 0)) {
                long nl = reml - dl;
                remh = remh - dh - (Long.compareUnsigned(reml, dl) < 0 ? 1 : 0);
                reml = nl;
                ql |= 1;
            }
            remh = (remh << 1) | (reml >>> 63);
            reml <<= 1;
        }
        // quotient leading one at bit n-1
        shl128(qh, ql, 125 - (n - 1));
        if ((remh | reml) != 0) rl |= 1;
        return round(fmt, s, e);
    }

    public long sqrt(int fmt) {
        int c = cl[0];
        if (c >= QNAN) {
            if (c == SNAN) flags |= VXSNAN;
            return nanOf(fmt, 0);
        }
        if (c == ZERO) return packZero(fmt, sg[0]);
        if (sg[0] != 0) {
            flags |= VXSQRT;
            return defaultNan(fmt);
        }
        if (c == INF) return packInf(fmt, 0);
        // radicand = m * 2^(e-112); make exponent even.
        long mhv = mh[0], mlv = ml[0];
        int e = ex[0];
        if ((e & 1) != 0) {
            e -= 1; // radicand shifted left one below: value in [2,4), integer bits at 113:112
        }
        // Digit-by-digit square root over pairs of bits starting at (113,112) down.
        int n = PREC[fmt] + 2;
        long rooth = 0, rootl = 0, remh = 0, reml = 0;
        int bitpos = 113;
        long srcH = mhv, srcL = mlv;
        if ((ex[0] & 1) != 0) {
            // shift radicand left by one so pairs (113,112) hold the [2,4) integer part
            srcH = (srcH << 1) | (srcL >>> 63);
            srcL <<= 1;
        }
        for (int k = 0; k < n; k++) {
            int b = bitpos - 2 * k; // high bit of the pair
            long two;
            if (b >= 1) {
                two = b - 1 >= 64 ? (srcH >>> (b - 1 - 64)) & 3
                        : b >= 64 ? ((srcH & 1) << 1) | (srcL >>> 63)
                        : (srcL >>> (b - 1)) & 3;
            } else {
                two = 0;
            }
            // rem = rem*4 + two
            remh = (remh << 2) | (reml >>> 62);
            reml = (reml << 2) | two;
            // trial = root*4 + 1
            long th = (rooth << 2) | (rootl >>> 62);
            long tl = (rootl << 2) | 1;
            rooth = (rooth << 1) | (rootl >>> 63);
            rootl <<= 1;
            if (Long.compareUnsigned(remh, th) > 0 || (remh == th && Long.compareUnsigned(reml, tl) >= 0)) {
                long nl = reml - tl;
                remh = remh - th - (Long.compareUnsigned(reml, tl) < 0 ? 1 : 0);
                reml = nl;
                rootl |= 1;
            }
        }
        // Remaining radicand bits below the consumed pairs contribute to the sticky.
        int lowest = bitpos - 2 * n + 1; // lowest consumed bit position
        boolean rest = false;
        if (lowest > 0) {
            if (lowest >= 64) rest = srcL != 0 || (lowest > 64 && (srcH << (128 - lowest)) != 0);
            else rest = (srcL << (64 - lowest)) != 0;
        }
        shl128(rooth, rootl, 125 - (n - 1));
        if ((remh | reml) != 0 || rest) rl |= 1;
        return round(fmt, 0, e / 2);
    }

    /**
     * Fused multiply-add on slots: (a * c) + b with slot 0 = a, slot 1 = b, slot 2 = c (the PowerPC
     * operand naming). {@code negAdd} negates the addend; {@code negRes} negates the exact result
     * before rounding (so directed rounding applies to the negated value). NaN results are never
     * negated.
     */
    public long fma(int fmt, boolean negAdd, boolean negRes) {
        long r = fma0(fmt, negAdd);
        if (negRes && !isNanBits(fmt, r)) {
            // QEMU negates after rounding (directed rounding sees the un-negated value).
            if (fmt == Q || fmt == QX) {
                qh ^= Long.MIN_VALUE;
                return qh;
            }
            r ^= signBit(fmt);
        }
        return r;
    }

    private long fma0(int fmt, boolean negAdd) {
        int ca = cl[0], cb = cl[1], cc = cl[2];
        boolean infZero = (ca == INF && cc == ZERO) || (ca == ZERO && cc == INF);
        if (ca >= QNAN || cb >= QNAN || cc >= QNAN) {
            if (ca == SNAN || cb == SNAN || cc == SNAN) flags |= VXSNAN;
            if (infZero) flags |= VXIMZ;
            return nanOf(fmt, ca >= QNAN ? 0 : cb >= QNAN ? 1 : 2);
        }
        if (infZero) {
            flags |= VXIMZ;
            return defaultNan(fmt);
        }
        int sp = sg[0] ^ sg[2];
        int sb = sg[1] ^ (negAdd ? 1 : 0);
        if (ca == INF || cc == INF) {
            if (cb == INF && sb != sp) {
                flags |= VXISI;
                return defaultNan(fmt);
            }
            return packInf(fmt, sp);
        }
        if (cb == INF) return packInf(fmt, sb);
        if (ca == ZERO || cc == ZERO) {
            if (cb == ZERO) {
                int s = sp == sb ? sp : (mode == RM ? 1 : 0);
                return packZero(fmt, s);
            }
            sg[1] = sb;
            return roundSlot(fmt, 1);
        }
        mulSlots(0, 2);
        if (cb == ZERO) {
            int e = wToRound(ex[0] + ex[2] - 224);
            return round(fmt, sp, e);
        }
        x3 = 0; x2 = 0; x1 = mh[1]; x0 = ml[1];
        return addWX(fmt, sp, ex[0] + ex[2] - 224, sb, ex[1] - 112);
    }

    /** Whether {@code r} (or qh:ql for Q) is a NaN encoding in format fmt. */
    public boolean isNanResult(int fmt, long r) {
        return isNanBits(fmt, r);
    }

    public static long signBitOf(int fmt) {
        return signBit(fmt);
    }

    private boolean isNanBits(int fmt, long r) {
        return switch (fmt) {
            case S -> (r & 0x7fffffffL) > 0x7f800000L;
            case H -> (r & 0x7fffL) > 0x7c00L;
            case Q, QX -> (qh & 0x7fff000000000000L) == 0x7fff000000000000L
                    && ((qh & 0xffffffffffffL) | ql) != 0;
            default -> (r & 0x7fffffffffffffffL) > 0x7ff0000000000000L;
        };
    }

    private static long signBit(int fmt) {
        return switch (fmt) {
            case S -> 1L << 31;
            case H -> 1L << 15;
            default -> 1L << 63;
        };
    }

    // ------------------------------------------------------------------ conversions

    /** Round slot 0 (any class) to format fmt; NaNs are converted (quieted) by the caller-supplied rule. */
    public long convert(int fmt) {
        int c = cl[0];
        if (c >= QNAN) {
            if (c == SNAN) flags |= VXSNAN;
            return Long.MIN_VALUE; // caller handles NaN conversion
        }
        if (c == ZERO) return packZero(fmt, sg[0]);
        if (c == INF) return packInf(fmt, sg[0]);
        return roundSlot(fmt, 0);
    }

    /** Round a finite slot to an integral value in the same format (mode given explicitly). */
    public long roundToInt(int fmt, int rmode) {
        int c = cl[0];
        if (c >= QNAN) {
            if (c == SNAN) flags |= VXSNAN;
            return nanOf(fmt, 0);
        }
        if (c == ZERO) return packZero(fmt, sg[0]);
        if (c == INF) return packInf(fmt, sg[0]);
        int e = ex[0];
        if (e >= PREC[fmt] - 1) return roundSlot(fmt, 0); // already integral
        int saved = mode;
        mode = rmode;
        // Round the significand to (e+1) integer bits by treating it as a format with that precision.
        long hm = mh[0], lm = ml[0];
        // value = m * 2^(e-112); integer part has e+1 bits (e >= 0) or none (e < 0).
        int fracBits = 112 - e; // number of fraction bits (> 0)
        long kh, kl;
        boolean half, below;
        if (fracBits > 113) {
            kh = 0;
            kl = 0;
            half = false;
            below = true;
        } else if (fracBits == 113) {
            kh = 0;
            kl = 0;
            half = true; // leading bit at 112 is the 1/2 position
            below = (hm & 0xffffffffffffL) != 0 || lm != 0;
        } else {
            // kept = m >> fracBits ; half = bit fracBits-1 ; below = bits below
            if (fracBits >= 64) {
                kl = hm >>> (fracBits - 64);
                kh = 0;
                half = ((fracBits - 1 >= 64 ? hm >>> (fracBits - 1 - 64) : lm >>> 63) & 1) != 0;
                below = fracBits - 1 >= 64 ? (lm != 0 || (fracBits - 1 > 64 && (hm << (128 - (fracBits - 1))) != 0))
                        : (lm << 1) != 0;
            } else {
                kl = (lm >>> fracBits) | (hm << (64 - fracBits));
                kh = hm >>> fracBits;
                half = ((lm >>> (fracBits - 1)) & 1) != 0;
                below = fracBits > 1 && (lm << (65 - fracBits)) != 0;
            }
        }
        boolean inexact = half || below;
        boolean up;
        switch (mode) {
            case RN -> up = half && (below || (kl & 1) != 0);
            case RNA -> up = half;
            case RZ -> up = false;
            case RP -> up = inexact && sg[0] == 0;
            default -> up = inexact && sg[0] != 0;
        }
        mode = saved;
        if (up) {
            kl++;
            if (kl == 0) kh++;
        }
        if (inexact) flags |= XX;
        if ((kh | kl) == 0) return packZero(fmt, sg[0]);
        int msb = kh != 0 ? 127 - Long.numberOfLeadingZeros(kh) : 63 - Long.numberOfLeadingZeros(kl);
        shl128(kh, kl, 125 - msb);
        int savedFlags = flags;
        long r = round(fmt, sg[0], msb);
        flags = savedFlags;
        return r;
    }

    /**
     * Convert slot 0 to an integer of {@code bits} (32/64/128) bits, signed or unsigned, rounding with
     * {@code rmode}. Out-of-range values and NaNs raise VXCVI and saturate (NaN gives the minimum
     * signed value or zero for unsigned; see {@link #cvtNanResult}). For 128-bit results the value is
     * in qh:ql and the return value is ql.
     */
    public long toInt(int bits, boolean signed, int rmode) {
        int c = cl[0];
        int s = sg[0];
        if (c >= QNAN) {
            if (c == SNAN) flags |= VXSNAN;
            flags |= VXCVI;
            nanCvt = true;
            return satInt(bits, signed, signed ? 1 : 0, true);
        }
        nanCvt = false;
        if (c == INF) {
            flags |= VXCVI;
            return satInt(bits, signed, s, false);
        }
        if (c == ZERO) {
            qh = 0;
            ql = 0;
            return 0;
        }
        int e = ex[0];
        long hm = mh[0], lm = ml[0];
        long kh, kl;
        boolean half, below;
        int fracBits = 112 - e;
        if (fracBits <= 0) {
            // |x| >= 2^112: certainly out of range for <= 64 bits; for 128 bits shift left
            if (bits < 128 || e >= 128) {
                flags |= VXCVI;
                return satInt(bits, signed, s, false);
            }
            shl128(hm, lm, -fracBits);
            kh = rh;
            kl = rl;
            half = false;
            below = false;
        } else if (fracBits > 113) {
            kh = 0;
            kl = 0;
            half = false;
            below = true;
        } else if (fracBits == 113) {
            kh = 0;
            kl = 0;
            half = true;
            below = (hm & 0xffffffffffffL) != 0 || lm != 0;
        } else if (fracBits >= 64) {
            kl = hm >>> (fracBits - 64);
            if (fracBits == 64) kl = hm;
            kh = 0;
            half = ((fracBits - 1 >= 64 ? hm >>> (fracBits - 1 - 64) : lm >>> 63) & 1) != 0;
            below = fracBits - 1 >= 64 ? (lm != 0 || (fracBits - 1 > 64 && (hm << (128 - (fracBits - 1))) != 0))
                    : (lm << 1) != 0;
        } else {
            kl = (lm >>> fracBits) | (hm << (64 - fracBits));
            kh = hm >>> fracBits;
            half = ((lm >>> (fracBits - 1)) & 1) != 0;
            below = fracBits > 1 && (lm << (65 - fracBits)) != 0;
        }
        boolean inexact = half || below;
        boolean up = switch (rmode) {
            case RN -> half && (below || (kl & 1) != 0);
            case RNA -> half;
            case RZ -> false;
            case RP -> inexact && s == 0;
            default -> inexact && s != 0;
        };
        if (up) {
            kl++;
            if (kl == 0) kh++;
        }
        // range check on magnitude k
        boolean ovf;
        if (bits == 128) {
            if (signed) {
                ovf = s == 0 ? kh < 0 : (Long.compareUnsigned(kh, 1L << 63) > 0 || (kh == 1L << 63 && kl != 0));
            } else {
                ovf = s != 0 && (kh | kl) != 0;
            }
        } else if (kh != 0) {
            ovf = true;
        } else if (bits == 64) {
            if (signed) {
                ovf = s == 0 ? kl < 0 : Long.compareUnsigned(kl, 1L << 63) > 0;
            } else {
                ovf = s != 0 && kl != 0;
            }
        } else {
            if (signed) {
                ovf = s == 0 ? Long.compareUnsigned(kl, 0x7fffffffL) > 0 : Long.compareUnsigned(kl, 0x80000000L) > 0;
            } else {
                ovf = (s != 0 && kl != 0) || Long.compareUnsigned(kl, 0xffffffffL) > 0;
            }
        }
        if (ovf) {
            flags |= VXCVI;
            return satInt(bits, signed, s, false);
        }
        if (inexact) flags |= XX;
        if (s != 0) {
            kl = -kl;
            kh = ~kh + (kl == 0 ? 1 : 0);
        }
        qh = kh;
        ql = kl;
        if (bits == 32) return signed ? (long) (int) kl : kl & 0xffffffffL;
        return kl;
    }

    /** Set by {@link #toInt} when the operand was a NaN. */
    public boolean nanCvt;

    private long satInt(int bits, boolean signed, int neg, boolean nan) {
        if (bits == 128) {
            if (signed) {
                qh = neg != 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
                ql = neg != 0 ? 0 : -1L;
            } else {
                qh = neg != 0 || nan ? 0 : -1L;
                ql = qh;
            }
            return ql;
        }
        long r;
        if (bits == 64) {
            r = signed ? (neg != 0 ? Long.MIN_VALUE : Long.MAX_VALUE) : (neg != 0 || nan ? 0 : -1L);
        } else {
            r = signed ? (neg != 0 ? 0xffffffff80000000L : 0x7fffffffL) : (neg != 0 || nan ? 0 : 0xffffffffL);
        }
        return r;
    }

    /** Signed/unsigned 64-bit (or 128-bit via hi) integer to format fmt. */
    public long fromInt(int fmt, long hi, long lo, boolean signed, boolean is128) {
        int s = 0;
        if (!is128) hi = signed && lo < 0 ? -1L : 0;
        if (signed && hi < 0) {
            s = 1;
            lo = -lo;
            hi = ~hi + (lo == 0 ? 1 : 0);
        }
        if ((hi | lo) == 0) return packZero(fmt, 0);
        w3 = 0; w2 = 0; w1 = hi; w0 = lo;
        int e = wToRound(0);
        return round(fmt, s, e);
    }

    // ------------------------------------------------------------------ misc operations

    /**
     * The value in slot i, of any class, delivered in format fmt the way QEMU's round_pack does:
     * finite values are rounded (with re-bias / flush-to-zero), NaNs are quieted and converted
     * between formats by truncating the payload.
     */
    public long deliver(int fmt, int i) {
        switch (cl[i]) {
            case ZERO:
                return packZero(fmt, sg[i]);
            case INF:
                return packInf(fmt, sg[i]);
            case FIN:
                return roundSlot(fmt, i);
            default:
                if (cl[i] == SNAN) flags |= VXSNAN;
                return nanConvert(i, fmt);
        }
    }

    /** The quieted NaN of slot i converted to format fmt (payload truncated or zero-extended). */
    public long nanConvert(int i, int fmt) {
        // Left-align the fraction (without the implicit bit) in fh:fl.
        long fh, fl;
        long sign;
        switch (fm[i]) {
            case S -> { fh = nl[i] << 41; fl = 0; sign = (nl[i] >>> 31) & 1; }
            case H -> { fh = nl[i] << 54; fl = 0; sign = (nl[i] >>> 15) & 1; }
            case Q -> { fh = (nh[i] << 16) | (nl[i] >>> 48); fl = nl[i] << 16; sign = nh[i] >>> 63; }
            default -> { fh = nl[i] << 12; fl = 0; sign = nl[i] >>> 63; }
        }
        switch (fmt) {
            case S:
                return (sign << 31) | 0x7f800000L | (fh >>> 41);
            case H:
                return (sign << 15) | 0x7c00L | (fh >>> 54);
            case Q:
            case QX:
                qh = (sign << 63) | 0x7fff000000000000L | (fh >>> 16);
                ql = (fh << 48) | (fl >>> 16);
                return qh;
            case DS:
                return ((sign << 63) | 0x7ff0000000000000L | (fh >>> 12)) & ~0x1fffffffL;
            default:
                return (sign << 63) | 0x7ff0000000000000L | (fh >>> 12);
        }
    }

    /** Multiply the finite value in slot i by 2^n (QEMU's scalbn on the unrounded parts). */
    public void scale(int i, int n) {
        if (cl[i] == FIN) {
            ex[i] += n;
        }
    }

    /**
     * QEMU's minmax on slots 0 (a) and 1 (b). {@code isnum}: IEEE minNum/maxNum (a quiet NaN
     * loses against a number); otherwise any NaN propagates. Equal values return a.
     */
    public long minmax(int fmt, boolean max, boolean isnum) {
        int ca = cl[0], cb = cl[1];
        if (ca >= QNAN || cb >= QNAN) {
            boolean snan = ca == SNAN || cb == SNAN;
            if (isnum && !snan && !(ca >= QNAN && cb >= QNAN)) {
                return deliver(fmt, ca >= QNAN ? 1 : 0);
            }
            if (nan2(fmt, 0, 1)) return res;
        }
        int cmp;
        if (sg[0] != sg[1]) {
            cmp = sg[0] != 0 ? -1 : 1;
        } else {
            cmp = cmpMag();
            if (sg[0] != 0) cmp = -cmp;
        }
        if (!max) cmp = -cmp;
        return deliver(fmt, cmp < 0 ? 1 : 0);
    }

    // ------------------------------------------------------------------ comparison

    public static final int CMP_LT = 8, CMP_GT = 4, CMP_EQ = 2, CMP_UN = 1;

    /** Compare slot 0 with slot 1 (no flags raised except by the caller). */
    public int compare() {
        int ca = cl[0], cb = cl[1];
        if (ca >= QNAN || cb >= QNAN) return CMP_UN;
        int r = cmpMag();
        if (ca == ZERO && cb == ZERO) return CMP_EQ;
        if (sg[0] != sg[1]) return sg[0] != 0 ? CMP_LT : CMP_GT;
        if (r == 0) return CMP_EQ;
        boolean lt = (r < 0) ^ (sg[0] != 0);
        return lt ? CMP_LT : CMP_GT;
    }

    /** Signed comparison of magnitudes |a| vs |b| for non-NaN slots 0, 1. */
    private int cmpMag() {
        int ca = cl[0], cb = cl[1];
        if (ca != cb) {
            // ZERO < FIN < INF
            return Integer.compare(ca, cb);
        }
        if (ca != FIN) return 0;
        if (ex[0] != ex[1]) return Integer.compare(ex[0], ex[1]);
        int c = Long.compareUnsigned(mh[0], mh[1]);
        if (c != 0) return c;
        return Long.compareUnsigned(ml[0], ml[1]);
    }

    public boolean isSnan(int i) {
        return cl[i] == SNAN;
    }

    /** The quieted NaN of slot i in its own container format (Q: also sets qh/ql). */
    public long quietNan(int i, int fmt) {
        return nanOf(fmt, i);
    }

    public int classOf(int i) {
        return cl[i];
    }
}
