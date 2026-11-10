package org.boxtheta.javapower.fpvec;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PpcException;

/**
 * FPSCR bit definitions and the exception-reporting sequence shared by scalar FP and VSX
 * instructions. Bit constants use Java numbering (ISA bit n of the 64-bit FPSCR is bit 63-n).
 *
 * <p>Model (identical to QEMU's, which the differential tests are generated from):
 * <ul>
 *   <li>Invalid-operation and zero-divide exceptions are reported first ({@link #pre}). They clear
 *       FR/FI; if the exception is enabled (VE/ZE) FEX is set, the target register and FPRF are not
 *       updated, and the program interrupt is taken immediately when MSR[FE0|FE1] != 0.</li>
 *   <li>Otherwise the target is written, FPRF is set, and overflow/underflow/inexact are reported
 *       ({@link #post}); an enabled one sets FEX and the interrupt is taken after the update.</li>
 *   <li>FR is never set (QEMU does not model it); FI reflects the inexact flag of the operation.</li>
 * </ul>
 */
public final class Fpscr {
    private Fpscr() {}

    public static final long FX = 1L << 31, FEX = 1L << 30, VX = 1L << 29, OX = 1L << 28, UX = 1L << 27;
    public static final long ZX = 1L << 26, XX = 1L << 25, VXSNAN = 1L << 24, VXISI = 1L << 23;
    public static final long VXIDI = 1L << 22, VXZDZ = 1L << 21, VXIMZ = 1L << 20, VXVC = 1L << 19;
    public static final long FR = 1L << 18, FI = 1L << 17, FPRF = 0x1fL << 12, FPCC = 0xfL << 12;
    public static final long C = 1L << 16, FL = 1L << 15, FG = 1L << 14, FE = 1L << 13, FU = 1L << 12;
    public static final long VXSOFT = 1L << 10, VXSQRT = 1L << 9, VXCVI = 1L << 8;
    public static final long VE = 1L << 7, OE = 1L << 6, UE = 1L << 5, ZE = 1L << 4, XE = 1L << 3;
    public static final long NI = 1L << 2, RN = 3L;
    public static final long DRN = 7L << 32;
    public static final long ENABLES = VE | OE | UE | ZE | XE;
    public static final long VX_CAUSES = VXSNAN | VXISI | VXIDI | VXZDZ | VXIMZ | VXVC | VXSOFT | VXSQRT | VXCVI;
    /** Bits writable by mtfsf/mtfsfi/mtfsb: everything but FEX, VX, ISA bit 52 and the reserved high bits. */
    public static final long MTFS_MASK = ~((0xfffffffL << 36) | (1L << 35) | FEX | VX | (1L << 11));
    /** Exception bits that mcrfs clears. */
    public static final long EX_CLEAR = FX | OX | UX | ZX | XX | VX_CAUSES;

    public static void checkFp(CpuState s) {
        if ((s.msr & Msr.FP) == 0) {
            throw PpcException.fpUnavailable();
        }
    }

    public static boolean trapsEnabled(CpuState s) {
        return (s.msr & (Msr.FE0 | Msr.FE1)) != 0;
    }

    public static int rmode(CpuState s) {
        return (int) s.fpscr & 3;
    }

    /**
     * Store a new FPSCR value through the architected mask, recomputing VX and FEX (as mtfsf does).
     */
    public static void store(CpuState s, long v) {
        v &= MTFS_MASK;
        if ((v & VX_CAUSES) != 0) {
            v |= VX;
        }
        if (((v >>> 25) & (v >>> 3) & 0x1f) != 0) {
            v |= FEX;
        }
        s.fpscr = v;
    }

    /** QEMU's float_invalid_op_vxsnan: VXSNAN, VX, FX; an enabled one traps immediately. */
    public static void vxsnan(CpuState s) {
        if (invalid(s, VXSNAN, false, false) && trapsEnabled(s)) {
            throw PpcException.fpEnabled();
        }
    }

    /** QEMU's float_invalid_op_vxvc: an enabled exception is deferred; returns whether it is pending. */
    public static boolean vxvc(CpuState s, boolean setFpcc) {
        long f = s.fpscr | VXVC | VX | FX;
        if (setFpcc) f = (f & ~FPCC) | C | FU;
        boolean pend = (f & VE) != 0;
        if (pend) f |= FEX;
        s.fpscr = f;
        return pend;
    }

    /** Raise the deferred enabled exception if any enabled exception bit is set (mtfsf and friends). */
    public static void checkStatus(CpuState s) {
        long f = s.fpscr;
        boolean pend = ((f & OX) != 0 && (f & OE) != 0) || ((f & UX) != 0 && (f & UE) != 0)
                || ((f & XX) != 0 && (f & XE) != 0) || ((f & ZX) != 0 && (f & ZE) != 0)
                || ((f & VE) != 0 && (f & VX_CAUSES) != 0);
        if (pend) {
            s.fpscr |= FEX;
            if (trapsEnabled(s)) {
                throw PpcException.fpEnabled();
            }
        }
    }

    /** Record invalid-operation cause bits (no trap). Returns true if VE is set. */
    public static boolean invalid(CpuState s, long causes, boolean clearFrFi, boolean setFpcc) {
        long f = s.fpscr | causes | VX | FX;
        if (clearFrFi) {
            f &= ~(FR | FI);
        }
        boolean ve = (f & VE) != 0;
        if (ve) {
            f |= FEX;
        } else if (setFpcc) {
            f = (f & ~FPCC) | C | FU;
        }
        s.fpscr = f;
        return ve;
    }

    /**
     * Report invalid-operation and zero-divide flags from a {@link SoftFloat} operation, as QEMU's
     * helpers do before the target is written: one invalid cause (arithmetic causes win over VXSNAN),
     * then zero-divide. An enabled exception sets FEX and, when MSR[FE0|FE1] != 0, raises the program
     * interrupt immediately (target and FPRF untouched). With MSR[FE0|FE1] = 0 QEMU goes on to write
     * the target, so callers always continue.
     */
    public static void pre(CpuState s, int flags) {
        pre(s, flags, true);
    }

    /** {@link #pre(CpuState, int)} with QEMU's set_fpcc argument (false for VSX vector forms). */
    public static void pre(CpuState s, int flags, boolean setFpcc) {
        if ((flags & SoftFloat.INVALID) != 0) {
            long causes = flags & SoftFloat.INVALID;
            if ((causes & SoftFloat.VXIMZ) != 0) {
                causes = SoftFloat.VXIMZ;
            } else if ((causes & SoftFloat.VXISI) != 0) {
                causes = SoftFloat.VXISI;
            } else if ((causes & SoftFloat.VXIDI) != 0) {
                causes = SoftFloat.VXIDI;
            } else if ((causes & SoftFloat.VXZDZ) != 0) {
                causes = SoftFloat.VXZDZ;
            } else if ((causes & SoftFloat.VXSQRT) != 0) {
                causes = SoftFloat.VXSQRT;
            } else {
                causes = SoftFloat.VXSNAN;
            }
            boolean arith = causes != SoftFloat.VXSNAN;
            if (invalid(s, causes, arith, arith && setFpcc) && trapsEnabled(s)) {
                throw PpcException.fpEnabled();
            }
        }
        if ((flags & SoftFloat.ZX) != 0) {
            zeroDivide(s);
        }
    }

    /** QEMU's float_zero_divide_excp. */
    public static void zeroDivide(CpuState s) {
        long f = (s.fpscr | ZX | FX) & ~(FR | FI);
        if ((f & ZE) != 0) {
            f |= FEX;
            s.fpscr = f;
            if (trapsEnabled(s)) {
                throw PpcException.fpEnabled();
            }
            return;
        }
        s.fpscr = f;
    }

    /** Invalid conversion (VXCVI, plus VXSNAN for a signalling input), QEMU's float_invalid_cvt. */
    public static void invalidCvt(CpuState s, boolean snan) {
        invalidCvt(s, snan, true);
    }

    public static void invalidCvt(CpuState s, boolean snan, boolean setFpcc) {
        if (snan) {
            s.fpscr |= VXSNAN;
        }
        if (invalid(s, VXCVI, true, setFpcc) && trapsEnabled(s)) {
            throw PpcException.fpEnabled();
        }
    }

    /**
     * Report overflow/underflow/inexact after the target has been written and set FI. Throws the
     * program interrupt for an enabled exception when MSR[FE0|FE1] != 0.
     */
    public static void post(CpuState s, int flags) {
        post(s, flags, true);
    }

    /** QEMU's do_float_check_status; {@code changeFi} false leaves FI alone (VSX vector forms). */
    public static void post(CpuState s, int flags, boolean changeFi) {
        long f = s.fpscr;
        boolean pending = false;
        if ((flags & SoftFloat.OX) != 0) {
            f |= OX | FX;
            if ((f & OE) != 0) {
                f |= FEX;
                pending = true;
            }
        } else if ((flags & SoftFloat.UX) != 0) {
            f |= UX | FX;
            if ((f & UE) != 0) {
                f |= FEX;
                pending = true;
            }
        }
        if ((flags & SoftFloat.XX) != 0) {
            f |= XX | FX;
            if (changeFi) f |= FI;
            if ((f & XE) != 0) {
                f |= FEX;
                pending = true;
            }
        } else if (changeFi) {
            f &= ~FI;
        }
        s.fpscr = f;
        if (pending && trapsEnabled(s)) {
            throw PpcException.fpEnabled();
        }
    }

    /** FPRF class code (C|FPCC) of a binary64 value. */
    public static int classD(long x) {
        boolean neg = x < 0;
        long a = x & 0x7fffffffffffffffL;
        if (a == 0) return neg ? 0x12 : 0x02;
        if (a >= 0x7ff0000000000000L) {
            if (a == 0x7ff0000000000000L) return neg ? 0x09 : 0x05;
            return (a & (1L << 51)) != 0 ? 0x11 : 0x00;
        }
        if (a < 0x0010000000000000L) return neg ? 0x18 : 0x14;
        return neg ? 0x08 : 0x04;
    }

    public static int classS(int x) {
        boolean neg = x < 0;
        int a = x & 0x7fffffff;
        if (a == 0) return neg ? 0x12 : 0x02;
        if (a >= 0x7f800000) {
            if (a == 0x7f800000) return neg ? 0x09 : 0x05;
            return (a & (1 << 22)) != 0 ? 0x11 : 0x00;
        }
        if (a < 0x00800000) return neg ? 0x18 : 0x14;
        return neg ? 0x08 : 0x04;
    }

    public static int classQ(long hi, long lo) {
        boolean neg = hi < 0;
        long a = hi & 0x7fffffffffffffffL;
        if ((a | lo) == 0) return neg ? 0x12 : 0x02;
        if ((a >>> 48) == 0x7fff) {
            if ((a & 0xffffffffffffL) == 0 && lo == 0) return neg ? 0x09 : 0x05;
            return (a & (1L << 47)) != 0 ? 0x11 : 0x00;
        }
        if ((a >>> 48) == 0) return neg ? 0x18 : 0x14;
        return neg ? 0x08 : 0x04;
    }

    public static int classH(int x) {
        boolean neg = (x & 0x8000) != 0;
        int a = x & 0x7fff;
        if (a == 0) return neg ? 0x12 : 0x02;
        if (a >= 0x7c00) {
            if (a == 0x7c00) return neg ? 0x09 : 0x05;
            return (a & (1 << 9)) != 0 ? 0x11 : 0x00;
        }
        if (a < 0x0400) return neg ? 0x18 : 0x14;
        return neg ? 0x08 : 0x04;
    }

    public static void setFprf(CpuState s, int cls) {
        s.fpscr = (s.fpscr & ~FPRF) | ((long) cls << 12);
    }

    public static void setCr1(CpuState s) {
        s.setCrField(1, (int) (s.fpscr >>> 28) & 0xf);
    }

    // ------------------------------------------------------------------ non-arithmetic conversions

    /** The ISA's DOUBLE(): exact widening of a binary32 bit pattern (keeps SNaNs signalling). */
    public static long toDouble(int w) {
        int abs = w & 0x7fffffff;
        long sign = (long) (w >>> 31) << 63;
        if (abs >= 0x00800000) {
            if (abs >= 0x7f800000) {
                return sign | 0x7ff0000000000000L | ((long) (w & 0x7fffff) << 29);
            }
            return sign | ((long) ((abs >>> 23) - 127 + 1023) << 52) | ((long) (w & 0x7fffff) << 29);
        }
        if (abs == 0) {
            return sign;
        }
        int msb = 31 - Integer.numberOfLeadingZeros(abs);
        int e = -126 - (23 - msb);
        long frac = ((long) abs << (52 - msb)) & 0xfffffffffffffL;
        return sign | ((long) (e + 1023) << 52) | frac;
    }

    /** The ISA's SINGLE(): non-arithmetic narrowing used by stores (truncates; denormalizes). */
    public static int toSingle(long x) {
        int exp = (int) (x >>> 52) & 0x7ff;
        if (exp > 896) {
            return (int) (((x >>> 62) & 3) << 30) | (int) ((x >>> 29) & 0x3fffffff);
        }
        int r = (int) (x >>> 63) << 31;
        if (exp >= 874) {
            long m = (1L << 52) | (x & 0xfffffffffffffL);
            r |= (int) (m >>> (896 + 30 - exp));
        }
        return r;
    }
}
