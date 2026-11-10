package javapower.fpvec;

import static javapower.fpvec.Fpscr.*;

import javapower.core.CpuState;
import javapower.core.DecodeTable;
import javapower.core.InsnSet;

/**
 * Scalar floating-point instructions (Book I chapter 4): arithmetic, FMA, estimates, conversions,
 * compares, moves, FPSCR access and FP loads/stores.
 *
 * <p>FPR n is {@code vsrHi[n]}; writing an FPR zeroes doubleword 1 of the VSR (ISA 3.0 leaves it
 * undefined; this matches QEMU).
 */
public final class FpInsns implements InsnSet {
    public static final FpInsns INSTANCE = new FpInsns();

    static int rt(int i) { return (i >>> 21) & 31; }
    static int ra(int i) { return (i >>> 16) & 31; }
    static int rb(int i) { return (i >>> 11) & 31; }
    static int rc(int i) { return (i >>> 6) & 31; }

    static void setFpr(CpuState s, int r, long v) {
        s.vsrHi[r] = v;
        s.vsrLo[r] = 0;
    }

    /** Common completion for arithmetic producing a binary64-container result. */
    static void finish(CpuState s, int insn, long r, int flags) {
        pre(s, flags);
        setFpr(s, rt(insn), r);
        setFprf(s, classD(r));
        post(s, flags);
        if ((insn & 1) != 0) {
            setCr1(s);
        }
    }

    // ------------------------------------------------------------------ arithmetic

    private static final int ADD = 0, SUB = 1, MUL = 2, DIV = 3;

    static void arith2(CpuState s, int insn, int op, int fmt) {
        checkFp(s);
        long a = s.vsrHi[ra(insn)];
        long b = op == MUL ? s.vsrHi[rc(insn)] : s.vsrHi[rb(insn)];
        if (fmt == SoftFloat.D && (s.fpscr & 3) == 0) {
            long r = FastFp.arith2(op, a, b);
            if (r != FastFp.MISS) {
                finish(s, insn, r, FastFp.inexact2(op, a, b, r) ? SoftFloat.XX : 0);
                return;
            }
        }
        SoftFloat sf = SoftFloat.get();
        sf.resetFp(s.fpscr);
        sf.unpackD(0, a);
        sf.unpackD(1, b);
        long r = switch (op) {
            case ADD -> sf.add(fmt, false);
            case SUB -> sf.add(fmt, true);
            case MUL -> sf.mul(fmt);
            default -> sf.div(fmt);
        };
        finish(s, insn, r, sf.flags);
    }

    static void fma(CpuState s, int insn, int fmt, boolean negAdd, boolean negRes) {
        checkFp(s);
        long a = s.vsrHi[ra(insn)], b = s.vsrHi[rb(insn)], c = s.vsrHi[rc(insn)];
        SoftFloat sf = SoftFloat.get();
        sf.resetFp(s.fpscr);
        sf.unpackD(0, a);
        sf.unpackD(1, b);
        sf.unpackD(2, c);
        long r = sf.fma(fmt, negAdd, negRes);
        finish(s, insn, r, sf.flags);
    }

    static void fsqrt(CpuState s, int insn, int fmt) {
        checkFp(s);
        SoftFloat sf = SoftFloat.get();
        sf.resetFp(s.fpscr);
        sf.unpackD(0, s.vsrHi[rb(insn)]);
        long r = sf.sqrt(fmt);
        finish(s, insn, r, sf.flags);
    }

    /** fre/fres: QEMU computes the exact reciprocal; a zero operand yields +-0.5 when ZE=0. */
    static void fre(CpuState s, int insn, int fmt) {
        checkFp(s);
        long b = s.vsrHi[rb(insn)];
        SoftFloat sf = SoftFloat.get();
        sf.resetFp(s.fpscr);
        sf.unpackD(0, 0x3ff0000000000000L);
        sf.unpackD(1, b);
        long r = sf.div(fmt);
        int fl = sf.flags;
        if ((fl & SoftFloat.ZX) != 0) {
            r = (b & (1L << 63)) | 0x3fe0000000000000L;
        }
        finish(s, insn, r, fl);
    }

    /** frsqrte/frsqrtes: QEMU computes 1/sqrt(x) with a binary64 sqrt followed by a division. */
    static void frsqrte(CpuState s, int insn, int fmt) {
        checkFp(s);
        long b = s.vsrHi[rb(insn)];
        SoftFloat sf = SoftFloat.get();
        sf.resetFp(s.fpscr);
        sf.unpackD(0, b);
        long q = sf.sqrt(SoftFloat.D);
        sf.unpackD(0, 0x3ff0000000000000L);
        sf.unpackD(1, q);
        long r = sf.div(fmt);
        finish(s, insn, r, sf.flags);
    }

    static void fsel(CpuState s, int insn) {
        checkFp(s);
        long a = s.vsrHi[ra(insn)];
        boolean nan = (a & 0x7fffffffffffffffL) > 0x7ff0000000000000L;
        boolean ge = !nan && (a >= 0 || a == Long.MIN_VALUE);
        setFpr(s, rt(insn), ge ? s.vsrHi[rc(insn)] : s.vsrHi[rb(insn)]);
        if ((insn & 1) != 0) {
            setCr1(s);
        }
    }

    // ------------------------------------------------------------------ rounding and conversion

    static void frsp(CpuState s, int insn) {
        checkFp(s);
        long b = s.vsrHi[rb(insn)];
        SoftFloat sf = SoftFloat.get();
        sf.resetFp(s.fpscr);
        sf.unpackD(0, b);
        // float64 -> float32 (re-bias wraps the 8-bit exponent) -> DOUBLE(), as QEMU does.
        long r = sf.convert(SoftFloat.S);
        r = sf.classOf(0) >= SoftFloat.QNAN ? (b | (1L << 51)) & ~0x1fffffffL : toDouble((int) r);
        finish(s, insn, r, sf.flags);
    }

    /** fri[nzpm]: round to integral; never sets XX and clears FI. */
    static void fri(CpuState s, int insn, int mode) {
        checkFp(s);
        SoftFloat sf = SoftFloat.get();
        sf.resetFp(s.fpscr);
        sf.unpackD(0, s.vsrHi[rb(insn)]);
        long r = sf.roundToInt(SoftFloat.D, mode);
        finish(s, insn, r, sf.flags & ~SoftFloat.XX);
    }

    /**
     * fcti*: convert to integer. FPRF is not changed (QEMU) except by VXCVI with VE=0. A NaN gives
     * the "NaN value" (0x80000000 zero-extended for fctiw), out-of-range values saturate; 32-bit
     * signed results are sign-extended to 64 bits, unsigned ones zero-extended.
     */
    static void fcti(CpuState s, int insn, int bits, boolean signed, boolean trunc) {
        checkFp(s);
        SoftFloat sf = SoftFloat.get();
        sf.resetFp(s.fpscr);
        int mode = trunc ? SoftFloat.RZ : rmode(s);
        sf.unpackD(0, s.vsrHi[rb(insn)]);
        long r = sf.toInt(bits, signed, mode);
        int fl = sf.flags;
        if ((fl & SoftFloat.VXCVI) != 0) {
            invalidCvt(s, (fl & SoftFloat.VXSNAN) != 0);
            if (sf.nanCvt) {
                r = !signed ? 0 : bits == 32 ? 0x80000000L : Long.MIN_VALUE;
            }
        }
        setFpr(s, rt(insn), r);
        post(s, fl);
        if ((insn & 1) != 0) {
            setCr1(s);
        }
    }

    static void fcfid(CpuState s, int insn, int fmt, boolean signed, boolean fprf) {
        checkFp(s);
        SoftFloat sf = SoftFloat.get();
        sf.resetFp(s.fpscr);
        long r = sf.fromInt(fmt, 0, s.vsrHi[rb(insn)], signed, false);
        post(s, sf.flags); // QEMU checks status inside the helper: a trap leaves the target alone
        setFpr(s, rt(insn), r);
        if (fprf) {
            setFprf(s, classD(r));
        }
        if ((insn & 1) != 0) {
            setCr1(s);
        }
    }

    // ------------------------------------------------------------------ compare / test

    static void fcmp(CpuState s, int insn, boolean ordered) {
        checkFp(s);
        long a = s.vsrHi[ra(insn)], b = s.vsrHi[rb(insn)];
        SoftFloat sf = SoftFloat.get();
        sf.unpackD(0, a);
        sf.unpackD(1, b);
        int c = sf.compare();
        int bf = (insn >>> 23) & 7;
        s.fpscr = (s.fpscr & ~FPCC) | ((long) c << 12);
        s.setCrField(bf, c);
        boolean deferred = false;
        if (c == SoftFloat.CMP_UN) {
            if (ordered) {
                // VXVC: FPCC becomes C|FU whatever VE says; an enabled trap is deferred.
                long f = (s.fpscr & ~FPCC) | C | FU | VXVC | VX | FX;
                if ((f & VE) != 0) {
                    f |= FEX;
                    deferred = true;
                }
                s.fpscr = f;
            }
            if (sf.isSnan(0) || sf.isSnan(1)) {
                if (invalid(s, VXSNAN, false, false) && trapsEnabled(s)) {
                    throw javapower.core.PpcException.fpEnabled();
                }
            }
        }
        s.fpscr &= ~FI;
        if (deferred && trapsEnabled(s)) {
            throw javapower.core.PpcException.fpEnabled();
        }
    }

    static void ftdiv(CpuState s, int insn) {
        checkFp(s);
        long a = s.vsrHi[ra(insn)], b = s.vsrHi[rb(insn)];
        s.setCrField((insn >>> 23) & 7, tdiv(a, b));
    }

    static void ftsqrt(CpuState s, int insn) {
        checkFp(s);
        s.setCrField((insn >>> 23) & 7, tsqrt(s.vsrHi[rb(insn)]));
    }

    static boolean isInfD(long x) { return (x & 0x7fffffffffffffffL) == 0x7ff0000000000000L; }
    static boolean isNanD(long x) { return (x & 0x7fffffffffffffffL) > 0x7ff0000000000000L; }
    static boolean isZeroD(long x) { return (x & 0x7fffffffffffffffL) == 0; }
    static int expD(long x) { return (int) ((x >>> 52) & 0x7ff) - 1023; }

    static int tdiv(long a, long b) {
        boolean fe = false, fg = false;
        if (isInfD(a) || isInfD(b) || isZeroD(b)) {
            fe = fg = true;
        } else {
            int ea = expD(a), eb = expD(b);
            if (isNanD(a) || isNanD(b)) {
                fe = true;
            } else if (eb <= -1022 || eb >= 1021) {
                fe = true;
            } else if (!isZeroD(a) && (ea - eb >= 1023 || ea - eb <= -1021 || ea <= -970)) {
                fe = true;
            }
            if ((b & 0x7ff0000000000000L) == 0) {
                fg = true;
            }
        }
        return 8 | (fg ? 4 : 0) | (fe ? 2 : 0);
    }

    static int tsqrt(long b) {
        boolean fe = false, fg = false;
        if (isInfD(b) || isZeroD(b)) {
            fe = fg = true;
        } else {
            int eb = expD(b);
            if (isNanD(b) || b < 0 || eb <= -1022 + 52) {
                fe = true;
            }
            if ((b & 0x7ff0000000000000L) == 0) {
                fg = true;
            }
        }
        return 8 | (fg ? 4 : 0) | (fe ? 2 : 0);
    }

    // ------------------------------------------------------------------ moves

    static void fmove(CpuState s, int insn, int kind) {
        checkFp(s);
        long b = s.vsrHi[rb(insn)];
        long r = switch (kind) {
            case 0 -> b;
            case 1 -> b ^ Long.MIN_VALUE;
            case 2 -> b & Long.MAX_VALUE;
            case 3 -> b | Long.MIN_VALUE;
            case 4 -> (s.vsrHi[ra(insn)] & Long.MIN_VALUE) | (b & Long.MAX_VALUE); // fcpsgn
            case 5 -> (s.vsrHi[ra(insn)] & 0xffffffff00000000L) | (b >>> 32); // fmrgew
            default -> (s.vsrHi[ra(insn)] << 32) | (b & 0xffffffffL); // fmrgow
        };
        setFpr(s, rt(insn), r);
        if (kind <= 4 && (insn & 1) != 0) {
            setCr1(s);
        }
    }

    // ------------------------------------------------------------------ FPSCR access

    static void mffsFamily(CpuState s, int insn) {
        checkFp(s);
        int t = rt(insn);
        long f = s.fpscr;
        final long ctl = DRN | ENABLES | NI | RN;
        switch ((insn >>> 16) & 31) {
            case 0 -> { // mffs
                setFpr(s, t, f);
                if ((insn & 1) != 0) setCr1(s);
            }
            case 1 -> { // mffsce
                setFpr(s, t, f);
                store(s, f & ~ENABLES);
            }
            case 20 -> { // mffscdrn
                long b = s.vsrHi[rb(insn)];
                setFpr(s, t, f & ctl);
                store(s, (f & ~DRN) | (b & DRN));
            }
            case 21 -> { // mffscdrni
                setFpr(s, t, f & ctl);
                store(s, (f & ~DRN) | ((long) ((insn >>> 11) & 7) << 32));
            }
            case 22 -> { // mffscrn
                long b = s.vsrHi[rb(insn)];
                setFpr(s, t, f & ctl);
                store(s, (f & ~RN) | (b & RN));
            }
            case 23 -> { // mffscrni
                setFpr(s, t, f & ctl);
                store(s, (f & ~RN) | ((insn >>> 11) & 3));
            }
            case 24 -> // mffsl
                setFpr(s, t, f & (ctl | FR | FI | FPRF));
            default -> throw javapower.core.PpcException.illegal();
        }
    }

    static void mtfsf(CpuState s, int insn) {
        checkFp(s);
        int flm = (insn >>> 17) & 0xff;
        boolean l = ((insn >>> 25) & 1) != 0;
        int w = (insn >>> 16) & 1;
        long mask;
        if (l) {
            mask = -1L;
        } else {
            mask = 0;
            int nib = flm << (w * 8);
            for (int i = 0; i < 16; i++) {
                if ((nib & (1 << i)) != 0) mask |= 0xfL << (4 * i);
            }
        }
        long v = s.vsrHi[rb(insn)];
        store(s, (v & mask) | (s.fpscr & ~mask));
        if ((insn & 1) != 0) setCr1(s);
        checkStatus(s);
    }

    static void mtfsfi(CpuState s, int insn) {
        checkFp(s);
        int bf = (insn >>> 23) & 7;
        int w = (insn >>> 16) & 1;
        long u = (insn >>> 12) & 0xf;
        int sh = 8 * w + 7 - bf;
        long mask = 0xfL << (4 * sh);
        store(s, (s.fpscr & ~mask) | (u << (4 * sh)));
        if ((insn & 1) != 0) setCr1(s);
        checkStatus(s);
    }

    static void mtfsb(CpuState s, int insn, boolean one) {
        checkFp(s);
        int bit = 31 - rt(insn);
        long m = 1L << bit;
        if (one) {
            if (m != FEX && m != VX && m != NI && (s.fpscr & m) == 0) {
                store(s, s.fpscr | m);
            }
        } else if (m != FEX && m != VX && (s.fpscr & m) != 0) {
            store(s, s.fpscr & ~m);
        }
        if ((insn & 1) != 0) setCr1(s);
        if (one) checkStatus(s);
    }

    static void mcrfs(CpuState s, int insn) {
        checkFp(s);
        int bf = (insn >>> 23) & 7;
        int bfa = (insn >>> 18) & 7;
        int shift = 4 * (7 - bfa);
        s.setCrField(bf, (int) (s.fpscr >>> shift) & 0xf);
        long clr = (0xfL << shift) & EX_CLEAR;
        store(s, s.fpscr & ~clr);
    }

    // ------------------------------------------------------------------ loads and stores

    static long eaD(CpuState s, int insn) {
        int a = ra(insn);
        long ea = (a == 0 ? 0 : s.gpr[a]) + (short) insn;
        return s.is64() ? ea : ea & 0xffffffffL;
    }

    static long eaDS(CpuState s, int insn) {
        int a = ra(insn);
        long ea = (a == 0 ? 0 : s.gpr[a]) + (short) (insn & ~3);
        return s.is64() ? ea : ea & 0xffffffffL;
    }

    static long eaX(CpuState s, int insn) {
        int a = ra(insn);
        long ea = (a == 0 ? 0 : s.gpr[a]) + s.gpr[rb(insn)];
        return s.is64() ? ea : ea & 0xffffffffL;
    }

    /** Load kinds: 0 lfs, 1 lfd, 2 lfiwax, 3 lfiwzx. */
    static void load(CpuState s, int insn, int kind, boolean xform, boolean update) {
        checkFp(s);
        if (update && ra(insn) == 0) throw javapower.core.PpcException.illegal();
        long ea = xform ? eaX(s, insn) : eaD(s, insn);
        long v = switch (kind) {
            case 0 -> toDouble(s.mem.read32(ea));
            case 1 -> s.mem.read64(ea);
            case 2 -> (long) s.mem.read32(ea);
            default -> s.mem.read32(ea) & 0xffffffffL;
        };
        setFpr(s, rt(insn), v);
        if (update) s.gpr[ra(insn)] = ea;
    }

    /** Store kinds: 0 stfs, 1 stfd, 2 stfiwx. */
    static void stf(CpuState s, int insn, int kind, boolean xform, boolean update) {
        checkFp(s);
        if (update && ra(insn) == 0) throw javapower.core.PpcException.illegal();
        long ea = xform ? eaX(s, insn) : eaD(s, insn);
        long v = s.vsrHi[rt(insn)];
        switch (kind) {
            case 0 -> s.mem.write32(ea, toSingle(v));
            case 1 -> s.mem.write64(ea, v);
            default -> s.mem.write32(ea, (int) v);
        }
        if (update) s.gpr[ra(insn)] = ea;
    }

    static void lfdp(CpuState s, int insn, boolean xform) {
        checkFp(s);
        int t = rt(insn);
        if ((t & 1) != 0) throw javapower.core.PpcException.illegal();
        long ea = xform ? eaX(s, insn) : eaDS(s, insn);
        long v0 = s.mem.read64(ea);
        long v1 = s.mem.read64(ea + 8);
        if (s.littleEndian()) {
            setFpr(s, t, v1);
            setFpr(s, t + 1, v0);
        } else {
            setFpr(s, t, v0);
            setFpr(s, t + 1, v1);
        }
    }

    static void stfdp(CpuState s, int insn, boolean xform) {
        checkFp(s);
        int t = rt(insn);
        if ((t & 1) != 0) throw javapower.core.PpcException.illegal();
        long ea = xform ? eaX(s, insn) : eaDS(s, insn);
        long hi = s.vsrHi[t], lo = s.vsrHi[t + 1];
        if (s.littleEndian()) {
            s.mem.write64(ea, lo);
            s.mem.write64(ea + 8, hi);
        } else {
            s.mem.write64(ea, hi);
            s.mem.write64(ea + 8, lo);
        }
    }

    // ------------------------------------------------------------------ registration

    @Override
    public void register(DecodeTable t) {
        final int D = SoftFloat.D, DS = SoftFloat.DS;
        for (int p : new int[] {63, 59}) {
            int fmt = p == 63 ? D : DS;
            String sfx = p == 63 ? "" : "s";
            t.addA(p, 21, "fadd" + sfx, (s, i) -> arith2(s, i, ADD, fmt));
            t.addA(p, 20, "fsub" + sfx, (s, i) -> arith2(s, i, SUB, fmt));
            t.addA(p, 25, "fmul" + sfx, (s, i) -> arith2(s, i, MUL, fmt));
            t.addA(p, 18, "fdiv" + sfx, (s, i) -> arith2(s, i, DIV, fmt));
            t.addA(p, 22, "fsqrt" + sfx, (s, i) -> fsqrt(s, i, fmt));
            t.addA(p, 24, "fre" + sfx, (s, i) -> fre(s, i, fmt));
            t.addA(p, 26, "frsqrte" + sfx, (s, i) -> frsqrte(s, i, fmt));
            t.addA(p, 29, "fmadd" + sfx, (s, i) -> fma(s, i, fmt, false, false));
            t.addA(p, 28, "fmsub" + sfx, (s, i) -> fma(s, i, fmt, true, false));
            t.addA(p, 31, "fnmadd" + sfx, (s, i) -> fma(s, i, fmt, false, true));
            t.addA(p, 30, "fnmsub" + sfx, (s, i) -> fma(s, i, fmt, true, true));
        }
        t.addA(63, 23, "fsel", FpInsns::fsel);

        t.addX(63, 12, "frsp", FpInsns::frsp);
        t.addX(63, 392, "frin", (s, i) -> fri(s, i, SoftFloat.RNA));
        t.addX(63, 424, "friz", (s, i) -> fri(s, i, SoftFloat.RZ));
        t.addX(63, 456, "frip", (s, i) -> fri(s, i, SoftFloat.RP));
        t.addX(63, 488, "frim", (s, i) -> fri(s, i, SoftFloat.RM));
        t.addX(63, 14, "fctiw", (s, i) -> fcti(s, i, 32, true, false));
        t.addX(63, 15, "fctiwz", (s, i) -> fcti(s, i, 32, true, true));
        t.addX(63, 142, "fctiwu", (s, i) -> fcti(s, i, 32, false, false));
        t.addX(63, 143, "fctiwuz", (s, i) -> fcti(s, i, 32, false, true));
        t.addX(63, 814, "fctid", (s, i) -> fcti(s, i, 64, true, false));
        t.addX(63, 815, "fctidz", (s, i) -> fcti(s, i, 64, true, true));
        t.addX(63, 942, "fctidu", (s, i) -> fcti(s, i, 64, false, false));
        t.addX(63, 943, "fctiduz", (s, i) -> fcti(s, i, 64, false, true));
        t.addX(63, 846, "fcfid", (s, i) -> fcfid(s, i, D, true, true));
        t.addX(63, 974, "fcfidu", (s, i) -> fcfid(s, i, D, false, false));
        t.addX(59, 846, "fcfids", (s, i) -> fcfid(s, i, DS, true, false));
        t.addX(59, 974, "fcfidus", (s, i) -> fcfid(s, i, DS, false, false));

        t.addX(63, 0, "fcmpu", (s, i) -> fcmp(s, i, false));
        t.addX(63, 32, "fcmpo", (s, i) -> fcmp(s, i, true));
        t.addX(63, 128, "ftdiv", FpInsns::ftdiv);
        t.addX(63, 160, "ftsqrt", FpInsns::ftsqrt);

        t.addX(63, 72, "fmr", (s, i) -> fmove(s, i, 0));
        t.addX(63, 40, "fneg", (s, i) -> fmove(s, i, 1));
        t.addX(63, 264, "fabs", (s, i) -> fmove(s, i, 2));
        t.addX(63, 136, "fnabs", (s, i) -> fmove(s, i, 3));
        t.addX(63, 8, "fcpsgn", (s, i) -> fmove(s, i, 4));
        t.addX(63, 966, "fmrgew", (s, i) -> fmove(s, i, 5));
        t.addX(63, 838, "fmrgow", (s, i) -> fmove(s, i, 6));

        String[] mffs = {"mffs", "mffsce", "mffscdrn", "mffscdrni", "mffscrn", "mffscrni", "mffsl"};
        int[] mffsEo = {0, 1, 20, 21, 22, 23, 24};
        for (int k = 0; k < mffs.length; k++) {
            // mffs keeps its Rc bit; the ISA 3.0 variants have none
            t.add(k == 0 ? 0xfc1f07fe : 0xfc1f07ff, (63 << 26) | (mffsEo[k] << 16) | (583 << 1), mffs[k], FpInsns::mffsFamily);
        }
        t.addX(63, 711, "mtfsf", FpInsns::mtfsf);
        t.addX(63, 134, "mtfsfi", FpInsns::mtfsfi);
        t.addX(63, 70, "mtfsb0", (s, i) -> mtfsb(s, i, false));
        t.addX(63, 38, "mtfsb1", (s, i) -> mtfsb(s, i, true));
        t.addX(63, 64, "mcrfs", FpInsns::mcrfs);

        t.addPrimary(48, "lfs", (s, i) -> load(s, i, 0, false, false));
        t.addPrimary(49, "lfsu", (s, i) -> load(s, i, 0, false, true));
        t.addPrimary(50, "lfd", (s, i) -> load(s, i, 1, false, false));
        t.addPrimary(51, "lfdu", (s, i) -> load(s, i, 1, false, true));
        t.addPrimary(52, "stfs", (s, i) -> stf(s, i, 0, false, false));
        t.addPrimary(53, "stfsu", (s, i) -> stf(s, i, 0, false, true));
        t.addPrimary(54, "stfd", (s, i) -> stf(s, i, 1, false, false));
        t.addPrimary(55, "stfdu", (s, i) -> stf(s, i, 1, false, true));
        t.addX(31, 535, "lfsx", (s, i) -> load(s, i, 0, true, false));
        t.addX(31, 567, "lfsux", (s, i) -> load(s, i, 0, true, true));
        t.addX(31, 599, "lfdx", (s, i) -> load(s, i, 1, true, false));
        t.addX(31, 631, "lfdux", (s, i) -> load(s, i, 1, true, true));
        t.addX(31, 663, "stfsx", (s, i) -> stf(s, i, 0, true, false));
        t.addX(31, 695, "stfsux", (s, i) -> stf(s, i, 0, true, true));
        t.addX(31, 727, "stfdx", (s, i) -> stf(s, i, 1, true, false));
        t.addX(31, 759, "stfdux", (s, i) -> stf(s, i, 1, true, true));
        t.addX(31, 855, "lfiwax", (s, i) -> load(s, i, 2, true, false));
        t.addX(31, 887, "lfiwzx", (s, i) -> load(s, i, 3, true, false));
        t.addX(31, 983, "stfiwx", (s, i) -> stf(s, i, 2, true, false));
        t.addX(31, 791, "lfdpx", (s, i) -> lfdp(s, i, true));
        t.addX(31, 919, "stfdpx", (s, i) -> stfdp(s, i, true));
        // lfdp/stfdp are DS-form with XO=0 in the low two bits (57/58 and 61/62 are shared).
        t.add(0xfc000003, 57 << 26, "lfdp", (s, i) -> lfdp(s, i, false));
        t.add(0xfc000003, 61 << 26, "stfdp", (s, i) -> stfdp(s, i, false));
    }
}
