package javapower.fpvec;

import static javapower.fpvec.Fpscr.*;

import javapower.core.CpuState;
import javapower.core.DecodeTable;
import javapower.core.InsnSet;
import javapower.core.Op;
import javapower.core.PpcException;

/**
 * VSX instructions of ISA 2.06-3.0 except quad precision ({@link QpInsns}): scalar and vector
 * binary64/binary32 arithmetic, fused multiply-add, compares, min/max, conversions, rounding,
 * estimates, test data class, exponent/significand manipulation, logical, permute, splat, moves
 * between VSRs and GPRs, and the VSX loads and stores.
 *
 * <p>FPSCR handling mirrors QEMU's VSX helpers: per element, the operation runs with fresh flags
 * and invalid-operation / zero-divide are reported (an enabled one traps immediately, before the
 * target is written); after all elements the target is written and overflow/underflow/inexact of
 * all elements are reported together. Scalar forms set FPRF and FI, vector forms set neither.
 */
public final class VsxInsns implements InsnSet {
    public static final VsxInsns INSTANCE = new VsxInsns();

    private VsxInsns() {}

    static int xt(int i) { return ((i >>> 21) & 31) | ((i & 1) << 5); }
    static int xa(int i) { return ((i >>> 16) & 31) | ((i & 4) << 3); }
    static int xb(int i) { return ((i >>> 11) & 31) | ((i & 2) << 4); }
    static int xc(int i) { return ((i >>> 6) & 31) | ((i & 8) << 2); }

    static void checkVsx(CpuState s) {
        V.checkVsx(s);
    }

    /** VSX if the register is a VSR 0-31, VMX otherwise (instructions that QEMU checks that way). */
    static void checkVsxOrVec(CpuState s, int reg) {
        if (reg < 32) V.checkVsx(s); else V.checkVec(s);
    }

    /** FP if the register is VSR 0-31, VMX otherwise (mfvsrd and friends). */
    static void checkFpOrVec(CpuState s, int reg) {
        if (reg < 32) Fpscr.checkFp(s); else V.checkVec(s);
    }

    // ------------------------------------------------------------------ element FP framework

    /** One element computation: operands are raw element bits; returns the raw result bits. */
    @FunctionalInterface interface E {
        long f(SoftFloat sf, long a, long b, long c);
    }

    static final int D = SoftFloat.D, S = SoftFloat.S, DS = SoftFloat.DS;

    /** Invalid-cause selection used by QEMU's float_invalid_op_* helpers. */
    static final int INV_ARITH = 0, INV_NONE = 1;

    /**
     * Run {@code op} on {@code nels} elements of {@code esize} bytes. {@code scalar}: dw0 only,
     * dw1 of the target cleared, FPRF/FI set (QEMU's sfifprf). {@code zx}: report zero-divide.
     * {@code r2sp}: round the binary64 result to single afterwards (QEMU's do_frsp).
     * {@code invalidMode}: INV_ARITH reports the invalid cause, INV_NONE ignores invalid flags.
     */
    static void elems(CpuState s, int t, long ah, long al, long bh, long bl, long ch, long cl,
                      int esize, int nels, boolean scalar, boolean zx, boolean r2sp, int invalidMode, E op) {
        SoftFloat sf = SoftFloat.get();
        int all = 0;
        long rh = 0, rl = 0;
        int fprf = -1;
        for (int i = 0; i < nels; i++) {
            long a = V.get(ah, al, esize, i), b = V.get(bh, bl, esize, i), c = V.get(ch, cl, esize, i);
            sf.resetFp(s.fpscr);
            long r = op.f(sf, a, b, c);
            int fl = sf.flags;
            all |= fl;
            if (invalidMode == INV_ARITH && (fl & SoftFloat.INVALID) != 0) {
                pre(s, fl & SoftFloat.INVALID, scalar);
            }
            if (zx && (fl & SoftFloat.ZX) != 0) {
                zeroDivide(s);
            }
            if (r2sp) {
                r = frsp(s, sf, r);
                all |= sf.flags;
            }
            if (scalar) {
                fprf = esize == 8 ? classD(r) : classS((int) r);
            }
            long m = V.mask(esize);
            long v = (r & m) << V.shift(esize, i);
            if (V.inHi(esize, i)) rh |= v; else rl |= v;
        }
        if (fprf >= 0) setFprf(s, fprf);
        V.set(s, t, rh, rl);
        post(s, all, scalar);
    }

    /** QEMU's do_frsp on a binary64 value: float64 -> float32 -> DOUBLE (flags left in sf.flags). */
    static long frsp(CpuState s, SoftFloat sf, long x) {
        sf.resetFp(s.fpscr);
        sf.unpackD(0, x);
        long r = sf.convert(S);
        if (sf.classOf(0) >= SoftFloat.QNAN) {
            if (sf.classOf(0) == SoftFloat.SNAN) vxsnan(s);
            return (x | (1L << 51)) & ~0x1fffffffL;
        }
        return toDouble((int) r);
    }

    static void binary(CpuState s, int insn, int fmt, int esize, int nels, boolean scalar, boolean r2sp, int op) {
        checkVsx(s);
        int a = xa(insn), b = xb(insn);
        if (scalar && !r2sp && (s.fpscr & 3) == 0) {
            // Same conditions as the scalar FP fast path: normal operands, result far from the
            // overflow/underflow thresholds, round-to-nearest; only XX/FI/FPRF can change.
            long x = s.vsrHi[a], y = s.vsrHi[b];
            long r = FastFp.arith2(op, x, y);
            if (r != FastFp.MISS) {
                int fl = FastFp.inexact2(op, x, y, r) ? SoftFloat.XX : 0;
                setFprf(s, classD(r));
                V.set(s, xt(insn), r, 0);
                post(s, fl, true);
                return;
            }
        }
        int n = scalar ? 1 : nels;
        elems(s, xt(insn), s.vsrHi[a], s.vsrLo[a], s.vsrHi[b], s.vsrLo[b], 0, 0, esize, n, scalar,
                op == 3, r2sp, INV_ARITH, (sf, x, y, z) -> {
                    sf.unpack(0, fmt, 0, x);
                    sf.unpack(1, fmt, 0, y);
                    return switch (op) {
                        case 0 -> sf.add(fmt, false);
                        case 1 -> sf.add(fmt, true);
                        case 2 -> sf.mul(fmt);
                        default -> sf.div(fmt);
                    };
                });
    }

    /** sqrt (op 0), reciprocal estimate (1), reciprocal square root estimate (2). */
    static void unary(CpuState s, int insn, int fmt, int esize, int nels, boolean scalar, boolean r2sp, int op) {
        checkVsx(s);
        int b = xb(insn);
        int n = scalar ? 1 : nels;
        long one = fmt == S ? 0x3f800000L : 0x3ff0000000000000L;
        if (op == 1) {
            // VSX_RE: an SNaN input reports VXSNAN up front; the division's own invalid and
            // zero-divide flags are not reported.
            SoftFloat sf = SoftFloat.get();
            for (int i = 0; i < n; i++) {
                long x = V.get(s.vsrHi[b], s.vsrLo[b], esize, i);
                sf.unpack(0, fmt, 0, x);
                if (sf.isSnan(0)) vxsnan(s);
            }
        }
        elems(s, xt(insn), 0, 0, s.vsrHi[b], s.vsrLo[b], 0, 0, esize, n, scalar, false, r2sp,
                op == 1 ? INV_NONE : INV_ARITH, (sf, x, y, z) -> {
                    if (op == 0) {
                        sf.unpack(0, fmt, 0, y);
                        return sf.sqrt(fmt);
                    }
                    long d = y;
                    if (op == 2) {
                        sf.unpack(0, fmt, 0, y);
                        d = sf.sqrt(fmt);
                    }
                    sf.unpack(0, fmt, 0, one);
                    sf.unpack(1, fmt, 0, d);
                    long r = sf.div(fmt);
                    sf.flags &= ~SoftFloat.ZX;
                    return r;
                });
    }

    /**
     * Multiply-add. A form: XT = XA*XB + XT; M form: XT = XA*XT + XB. QEMU's operand order for NaN
     * propagation is (multiplicand XA, addend, other multiplicand).
     */
    static void madd(CpuState s, int insn, int fmt, int esize, int nels, boolean scalar, boolean mform,
                     boolean negAdd, boolean negRes) {
        checkVsx(s);
        int a = xa(insn), b = xb(insn), t = xt(insn);
        int add = mform ? b : t, mul2 = mform ? t : b;
        int n = scalar ? 1 : nels;
        elems(s, t, s.vsrHi[a], s.vsrLo[a], s.vsrHi[add], s.vsrLo[add], s.vsrHi[mul2], s.vsrLo[mul2],
                esize, n, scalar, false, false, INV_ARITH, (sf, x, y, z) -> {
                    sf.unpack(0, fmt, 0, x);
                    sf.unpack(1, fmt, 0, y);
                    sf.unpack(2, fmt, 0, z);
                    return sf.fma(fmt, negAdd, negRes);
                });
    }

    // ------------------------------------------------------------------ compares

    /** xscmp{eq,ge,gt}dp: kind 0 eq (quiet), 1 ge, 2 gt (signalling). */
    static void xscmp(CpuState s, int insn, int kind) {
        checkVsx(s);
        long a = s.vsrHi[xa(insn)], b = s.vsrHi[xb(insn)];
        SoftFloat sf = SoftFloat.get();
        sf.unpackD(0, a);
        sf.unpackD(1, b);
        int rel = sf.compare();
        boolean nan = rel == SoftFloat.CMP_UN;
        boolean snan = sf.isSnan(0) || sf.isSnan(1);
        boolean r = switch (kind) {
            case 0 -> rel == SoftFloat.CMP_EQ;
            case 1 -> rel == SoftFloat.CMP_GT || rel == SoftFloat.CMP_EQ;
            default -> rel == SoftFloat.CMP_GT;
        };
        boolean invalid = kind == 0 ? snan : nan;
        boolean deferred = false;
        if (invalid) {
            boolean vxvc = kind != 0;
            if (snan) {
                vxsnan(s);
                vxvc &= (s.fpscr & VE) == 0;
            }
            if (vxvc) deferred = Fpscr.vxvc(s, false);
        }
        V.set(s, xt(insn), r ? -1L : 0, 0);
        if (deferred && trapsEnabled(s)) throw PpcException.fpEnabled();
    }

    static void xscmpexpdp(CpuState s, int insn) {
        checkVsx(s);
        long a = s.vsrHi[xa(insn)], b = s.vsrHi[xb(insn)];
        int cc;
        if (FpInsns.isNanD(a) || FpInsns.isNanD(b)) {
            cc = 1;
        } else {
            int ea = (int) (a >>> 52) & 0x7ff, eb = (int) (b >>> 52) & 0x7ff;
            cc = ea < eb ? 8 : ea > eb ? 4 : 2;
        }
        s.fpscr = (s.fpscr & ~FPCC) | ((long) cc << 12);
        s.setCrField((insn >>> 23) & 7, cc);
    }

    /** xscmpodp / xscmpudp. */
    static void xscmpou(CpuState s, int insn, boolean ordered) {
        checkVsx(s);
        long a = s.vsrHi[xa(insn)], b = s.vsrHi[xb(insn)];
        SoftFloat sf = SoftFloat.get();
        sf.unpackD(0, a);
        sf.unpackD(1, b);
        int cc = sf.compare();
        boolean snanF = false, vxvcF = false;
        if (cc == SoftFloat.CMP_UN) {
            if (sf.isSnan(0) || sf.isSnan(1)) {
                snanF = true;
                vxvcF = (s.fpscr & VE) == 0 && ordered;
            } else {
                vxvcF = ordered;
            }
        }
        s.fpscr = (s.fpscr & ~FPCC) | ((long) cc << 12);
        s.setCrField((insn >>> 23) & 7, cc);
        if (snanF) vxsnan(s);
        boolean deferred = vxvcF && Fpscr.vxvc(s, false);
        if (deferred && trapsEnabled(s)) throw PpcException.fpEnabled();
    }

    /** xvcmp{eq,ge,gt,ne}{dp,sp}; the Rc form sets CR6. No status check (QEMU). */
    static void xvcmp(CpuState s, int insn, int esize, int kind) {
        checkVsx(s);
        int a = xa(insn), b = xb(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], bh = s.vsrHi[b], bl = s.vsrLo[b];
        int n = 16 / esize;
        int fmt = esize == 8 ? D : S;
        SoftFloat sf = SoftFloat.get();
        boolean allTrue = true, allFalse = true;
        long rh = 0, rl = 0;
        for (int i = 0; i < n; i++) {
            sf.unpack(0, fmt, 0, V.get(ah, al, esize, i));
            sf.unpack(1, fmt, 0, V.get(bh, bl, esize, i));
            int rel = sf.compare();
            boolean res;
            if (rel == SoftFloat.CMP_UN) {
                if (sf.isSnan(0) || sf.isSnan(1)) vxsnan(s);
                if (kind == 1 || kind == 2) Fpscr.vxvc(s, false);
                res = false;
                allTrue = false;
            } else {
                boolean c = switch (kind) {
                    case 0 -> rel == SoftFloat.CMP_EQ;
                    case 1 -> rel != SoftFloat.CMP_LT;
                    case 2 -> rel == SoftFloat.CMP_GT;
                    default -> rel != SoftFloat.CMP_EQ;
                };
                res = c;
                if (c) allFalse = false; else allTrue = false;
            }
            if (res) {
                long v = V.mask(esize) << V.shift(esize, i);
                if (V.inHi(esize, i)) rh |= v; else rl |= v;
            }
        }
        V.set(s, xt(insn), rh, rl);
        if ((insn & 0x400) != 0) {
            s.setCrField(6, (allTrue ? 8 : 0) | (allFalse ? 2 : 0));
        }
    }

    // ------------------------------------------------------------------ min / max

    /** xsmaxdp/xsmindp/xv{max,min}{dp,sp}: IEEE maxNum/minNum, VXSNAN for any SNaN operand. */
    static void minmax(CpuState s, int insn, int esize, int nels, boolean max) {
        checkVsx(s);
        int a = xa(insn), b = xb(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], bh = s.vsrHi[b], bl = s.vsrLo[b];
        int fmt = esize == 8 ? D : S;
        SoftFloat sf = SoftFloat.get();
        int all = 0;
        long rh = 0, rl = 0;
        for (int i = 0; i < nels; i++) {
            sf.resetFp(s.fpscr);
            sf.unpack(0, fmt, 0, V.get(ah, al, esize, i));
            sf.unpack(1, fmt, 0, V.get(bh, bl, esize, i));
            boolean snan = sf.isSnan(0) || sf.isSnan(1);
            long r = sf.minmax(fmt, max, true);
            all |= sf.flags;
            if (snan) vxsnan(s);
            long v = (r & V.mask(esize)) << V.shift(esize, i);
            if (V.inHi(esize, i)) rh |= v; else rl |= v;
        }
        V.set(s, xt(insn), rh, rl);
        post(s, all, false);
    }

    /** xsmaxcdp / xsmincdp. */
    static void minmaxC(CpuState s, int insn, boolean max) {
        checkVsx(s);
        long a = s.vsrHi[xa(insn)], b = s.vsrHi[xb(insn)];
        SoftFloat sf = SoftFloat.get();
        sf.unpackD(0, a);
        sf.unpackD(1, b);
        int rel = sf.compare();
        boolean first = max ? (rel == SoftFloat.CMP_GT || rel == SoftFloat.CMP_EQ) : rel == SoftFloat.CMP_LT;
        long r;
        if (first) {
            r = a;
        } else {
            r = b;
            if (sf.isSnan(0) || sf.isSnan(1)) vxsnan(s);
        }
        V.set(s, xt(insn), r, 0);
    }

    /** xsmaxjdp / xsminjdp. */
    static void minmaxJ(CpuState s, int insn, boolean max) {
        checkVsx(s);
        long a = s.vsrHi[xa(insn)], b = s.vsrHi[xb(insn)];
        boolean snanF = false;
        long r;
        if (FpInsns.isNanD(a)) {
            snanF = (a & (1L << 51)) == 0;
            r = a;
        } else if (FpInsns.isNanD(b)) {
            snanF = (b & (1L << 51)) == 0;
            r = b;
        } else if (FpInsns.isZeroD(a) && FpInsns.isZeroD(b)) {
            if (max) {
                r = (a >= 0 || b >= 0) ? 0 : Long.MIN_VALUE;
            } else {
                r = (a < 0 || b < 0) ? Long.MIN_VALUE : 0;
            }
        } else {
            SoftFloat sf = SoftFloat.get();
            sf.unpackD(0, a);
            sf.unpackD(1, b);
            boolean lt = sf.compare() == SoftFloat.CMP_LT;
            r = (max ? !lt : lt) ? a : b;
        }
        boolean vex = (s.fpscr & VE) != 0 && snanF;
        if (snanF) vxsnan(s);
        if (!vex) V.set(s, xt(insn), r, 0);
    }

    // ------------------------------------------------------------------ conversions

    /** Float to float: kind 0 xscvdpsp, 1 xvcvdpsp, 2 xscvspdp, 3 xvcvspdp, 4 xscvdphp, 5 xscvhpdp, 6 xvcvsphp, 7 xvcvhpsp. */
    static void cvtff(CpuState s, int insn, int kind) {
        checkVsx(s);
        int b = xb(insn);
        long bh = s.vsrHi[b], bl = s.vsrLo[b];
        SoftFloat sf = SoftFloat.get();
        sf.resetFp(s.fpscr);
        boolean scalar = kind == 0 || kind == 2 || kind == 4 || kind == 5;
        int fprf = -1;
        long rh = 0, rl = 0;
        switch (kind) {
            case 0, 1 -> { // binary64 dw i -> binary32 in words 2i and 2i+1
                for (int i = 0; i < (kind == 0 ? 1 : 2); i++) {
                    sf.unpackD(0, i == 0 ? bh : bl);
                    long r = sf.deliver(S, 0) & 0xffffffffL;
                    if (sf.isSnan(0)) vxsnan(s);
                    if (kind == 0) fprf = classS((int) r);
                    long w = (r << 32) | r;
                    if (i == 0) rh = w; else rl = w;
                }
            }
            case 2, 3 -> { // binary32 word 2i -> binary64 dw i
                for (int i = 0; i < (kind == 2 ? 1 : 2); i++) {
                    sf.unpackS(0, V.w(bh, bl, 2 * i));
                    long r = sf.deliver(D, 0);
                    if (sf.isSnan(0)) vxsnan(s);
                    if (kind == 2) fprf = classD(r);
                    if (i == 0) rh = r; else rl = r;
                }
            }
            case 4 -> { // binary64 dw0 -> binary16 in halfword 3
                sf.unpackD(0, bh);
                long r = sf.deliver(SoftFloat.H, 0) & 0xffff;
                if (sf.isSnan(0)) vxsnan(s);
                fprf = classH((int) r);
                rh = r;
            }
            case 5 -> { // binary16 halfword 3 -> binary64 dw0
                sf.unpackH(0, V.h(bh, bl, 3));
                long r = sf.deliver(D, 0);
                if (sf.isSnan(0)) vxsnan(s);
                fprf = classD(r);
                rh = r;
            }
            case 6 -> { // binary32 word i -> binary16 in halfword 2i+1
                for (int i = 0; i < 4; i++) {
                    sf.unpackS(0, V.w(bh, bl, i));
                    long r = sf.deliver(SoftFloat.H, 0) & 0xffff;
                    if (sf.isSnan(0)) vxsnan(s);
                    if (i < 2) rh |= r << (32 - 32 * i); else rl |= r << (32 - 32 * (i - 2));
                }
            }
            default -> { // binary16 halfword 2i+1 -> binary32 word i
                for (int i = 0; i < 4; i++) {
                    sf.unpackH(0, V.h(bh, bl, 2 * i + 1));
                    long r = sf.deliver(S, 0) & 0xffffffffL;
                    if (sf.isSnan(0)) vxsnan(s);
                    if (i < 2) rh |= r << (32 - 32 * i); else rl |= r << (32 - 32 * (i - 2));
                }
            }
        }
        if (fprf >= 0) setFprf(s, fprf);
        V.set(s, xt(insn), rh, rl);
        post(s, sf.flags, scalar);
    }

    /**
     * Float to integer with truncation. src/dst element sizes in bytes; {@code dup}: the 32-bit
     * result is duplicated into both words of its doubleword (xs/xvcvdp[su]xws).
     */
    static void cvtfi(CpuState s, int insn, int srcSize, int dstSize, boolean signed, int nels, boolean scalar, boolean dup) {
        checkVsx(s);
        int b = xb(insn);
        long bh = s.vsrHi[b], bl = s.vsrLo[b];
        SoftFloat sf = SoftFloat.get();
        int all = 0;
        long rh = 0, rl = 0;
        for (int i = 0; i < nels; i++) {
            // source element: dw i (8), word i (4->4) or word 2i (4->8)
            int si = srcSize == 4 && dstSize == 8 ? 2 * i : i;
            long x = V.get(bh, bl, srcSize, si);
            sf.resetFp(s.fpscr);
            sf.unpack(0, srcSize == 8 ? D : S, 0, x);
            long r = sf.toInt(dstSize * 8, signed, SoftFloat.RZ);
            int fl = sf.flags;
            all |= fl;
            if ((fl & SoftFloat.VXCVI) != 0) {
                invalidCvt(s, (fl & SoftFloat.VXSNAN) != 0, false);
                if (sf.nanCvt) {
                    r = !signed ? 0 : dstSize == 4 ? 0x80000000L : Long.MIN_VALUE;
                }
            }
            if (dup) {
                long w = r & 0xffffffffL;
                long v = (w << 32) | w;
                if (i == 0) rh = v; else rl = v;
            } else {
                // destination element index: word 2i... for 8->4 not used (dup covers it)
                long v = (r & V.mask(dstSize)) << V.shift(dstSize, i);
                if (V.inHi(dstSize, i)) rh |= v; else rl |= v;
            }
        }
        V.set(s, xt(insn), rh, rl);
        post(s, all & ~SoftFloat.INVALID, scalar);
    }

    /**
     * Integer to float. kind: srcSize/dstSize; {@code r2sp} rounds the binary64 result to single
     * (xscv[su]xdsp); {@code dup} duplicates a binary32 result into both words of its doubleword.
     */
    static void cvtif(CpuState s, int insn, int srcSize, int dstSize, boolean signed, int nels, boolean scalar,
                      boolean r2sp, boolean dup) {
        checkVsx(s);
        int b = xb(insn);
        long bh = s.vsrHi[b], bl = s.vsrLo[b];
        SoftFloat sf = SoftFloat.get();
        int all = 0;
        int fprf = -1;
        long rh = 0, rl = 0;
        for (int i = 0; i < nels; i++) {
            int si = srcSize == 4 && dstSize == 8 ? 2 * i : i;
            long x = V.get(bh, bl, srcSize, si);
            if (signed && srcSize == 4) x = (int) x;
            sf.resetFp(s.fpscr);
            int fmt = dstSize == 8 ? D : S;
            long r = sf.fromInt(fmt, 0, x, signed, false);
            all |= sf.flags;
            if (r2sp) {
                r = frsp(s, sf, r);
                all |= sf.flags;
            }
            if (scalar) fprf = classD(r);
            if (dup) {
                long w = r & 0xffffffffL;
                long v = (w << 32) | w;
                if (i == 0) rh = v; else rl = v;
            } else {
                long v = (r & V.mask(dstSize)) << V.shift(dstSize, i);
                if (V.inHi(dstSize, i)) rh |= v; else rl |= v;
            }
        }
        if (fprf >= 0) setFprf(s, fprf);
        V.set(s, xt(insn), rh, rl);
        post(s, all, scalar);
    }

    /** Round to integral: rmode -1 = current mode (XX reported), otherwise XX suppressed. */
    static void round(CpuState s, int insn, int esize, int nels, boolean scalar, int rmode) {
        checkVsx(s);
        int b = xb(insn);
        long bh = s.vsrHi[b], bl = s.vsrLo[b];
        int fmt = esize == 8 ? D : S;
        SoftFloat sf = SoftFloat.get();
        sf.resetFp(s.fpscr);
        int mode = rmode < 0 ? (int) s.fpscr & 3 : rmode;
        int fprf = -1;
        long rh = 0, rl = 0;
        for (int i = 0; i < nels; i++) {
            long x = V.get(bh, bl, esize, i);
            sf.unpack(0, fmt, 0, x);
            long r;
            if (sf.isSnan(0)) {
                vxsnan(s);
                r = esize == 8 ? x | (1L << 51) : x | (1L << 22);
            } else {
                r = sf.roundToInt(fmt, mode);
            }
            if (scalar) fprf = classD(r);
            long v = (r & V.mask(esize)) << V.shift(esize, i);
            if (V.inHi(esize, i)) rh |= v; else rl |= v;
        }
        int fl = sf.flags & ~SoftFloat.INVALID;
        if (rmode >= 0) fl &= ~SoftFloat.XX;
        if (fprf >= 0) setFprf(s, fprf);
        V.set(s, xt(insn), rh, rl);
        post(s, fl, scalar);
    }

    static void xsrsp(CpuState s, int insn) {
        checkVsx(s);
        SoftFloat sf = SoftFloat.get();
        long r = frsp(s, sf, s.vsrHi[xb(insn)]);
        setFprf(s, classD(r));
        post(s, sf.flags, true); // inside QEMU's helper: a trap leaves the target alone
        V.set(s, xt(insn), r, 0);
    }

    /** xscvdpspn: non-signalling conversion, replicated into both words of dw0. */
    static void xscvdpspn(CpuState s, int insn) {
        checkVsx(s);
        long xb = s.vsrHi[xb(insn)];
        long sign = xb >>> 63;
        long exp = (xb >>> 52) & 0x7ff;
        long frac = (xb & 0xfffffffffffffL) | 0x10000000000000L;
        if (exp == 0 && (frac & 0xfffffffffffffL) != 0) {
            exp = 1;
            frac &= ~(1L << 53);
        }
        if (exp < 897 && frac != 0) {
            frac = 897 - exp > 63 ? 0 : frac >>> (897 - exp);
            exp = 896;
        }
        long r = (sign << 31) | (((exp >>> 10) & 1) << 30) | ((exp & 0x7f) << 23) | ((frac >>> 29) & 0x7fffff);
        V.set(s, xt(insn), (r << 32) | r, 0);
    }

    // ------------------------------------------------------------------ test for software divide / sqrt

    static void tdiv(CpuState s, int insn, int esize, int nels) {
        checkVsx(s);
        int a = xa(insn), b = xb(insn);
        boolean fe = false, fg = false;
        int emin = esize == 8 ? -1022 : -126, emax = esize == 8 ? 1023 : 127, nbits = esize == 8 ? 52 : 23;
        for (int i = 0; i < nels; i++) {
            long x = V.get(s.vsrHi[a], s.vsrLo[a], esize, i), y = V.get(s.vsrHi[b], s.vsrLo[b], esize, i);
            Cls ca = new Cls(x, esize), cb = new Cls(y, esize);
            if (ca.inf || cb.inf || cb.zero) {
                fe = fg = true;
            } else {
                if (ca.nan || cb.nan) {
                    fe = true;
                } else if (cb.e <= emin || cb.e >= emax - 2) {
                    fe = true;
                } else if (!ca.zero && (ca.e - cb.e >= emax || ca.e - cb.e <= emin + 1 || ca.e <= emin + nbits)) {
                    fe = true;
                }
                if (cb.zeroOrDenormal) fg = true;
            }
        }
        s.setCrField((insn >>> 23) & 7, 8 | (fg ? 4 : 0) | (fe ? 2 : 0));
    }

    static void tsqrt(CpuState s, int insn, int esize, int nels) {
        checkVsx(s);
        int b = xb(insn);
        boolean fe = false, fg = false;
        int emin = esize == 8 ? -1022 : -126, nbits = esize == 8 ? 52 : 23;
        for (int i = 0; i < nels; i++) {
            Cls cb = new Cls(V.get(s.vsrHi[b], s.vsrLo[b], esize, i), esize);
            if (cb.inf || cb.zero) {
                fe = fg = true;
            } else {
                if (cb.nan || cb.neg || cb.e <= emin + nbits) fe = true;
                if (cb.zeroOrDenormal) fg = true;
            }
        }
        s.setCrField((insn >>> 23) & 7, 8 | (fg ? 4 : 0) | (fe ? 2 : 0));
    }

    /** Classification of a binary64 or binary32 bit pattern. */
    static final class Cls {
        final boolean neg, zero, inf, nan, snan, zeroOrDenormal;
        final int e;

        Cls(long x, int esize) {
            if (esize == 8) {
                neg = x < 0;
                long a = x & Long.MAX_VALUE;
                int ef = (int) (a >>> 52);
                e = ef - 1023;
                zero = a == 0;
                inf = a == 0x7ff0000000000000L;
                nan = a > 0x7ff0000000000000L;
                snan = nan && (a & (1L << 51)) == 0;
                zeroOrDenormal = ef == 0;
            } else {
                int w = (int) x;
                neg = w < 0;
                int a = w & 0x7fffffff;
                int ef = a >>> 23;
                e = ef - 127;
                zero = a == 0;
                inf = a == 0x7f800000;
                nan = a > 0x7f800000;
                snan = nan && (a & (1 << 22)) == 0;
                zeroOrDenormal = ef == 0;
            }
        }

        /** QEMU's tstdc match against the 7-bit DCMX mask. */
        boolean match(int dcmx) {
            int sign = neg ? 1 : 0;
            if (nan) return ((dcmx >>> 6) & 1) != 0;
            if (inf) return ((dcmx >>> (4 + 1 - sign)) & 1) != 0;
            if (zero) return ((dcmx >>> (2 + 1 - sign)) & 1) != 0;
            if (zeroOrDenormal) return ((dcmx >>> (1 - sign)) & 1) != 0;
            return false;
        }
    }

    static void xvtstdc(CpuState s, int insn, int esize) {
        checkVsx(s);
        int b = xb(insn);
        int dcmx = (((insn >>> 6) & 1) << 6) | (((insn >>> 2) & 1) << 5) | ((insn >>> 16) & 31);
        long rh = 0, rl = 0;
        int n = 16 / esize;
        for (int i = 0; i < n; i++) {
            if (new Cls(V.get(s.vsrHi[b], s.vsrLo[b], esize, i), esize).match(dcmx)) {
                long v = V.mask(esize) << V.shift(esize, i);
                if (V.inHi(esize, i)) rh |= v; else rl |= v;
            }
        }
        V.set(s, xt(insn), rh, rl);
    }

    static void xststdc(CpuState s, int insn, boolean single) {
        checkVsx(s);
        long x = s.vsrHi[xb(insn)];
        int dcmx = (insn >>> 16) & 0x7f;
        Cls c = new Cls(x, 8);
        boolean match = c.match(dcmx);
        int cc;
        if (single) {
            int exp = (int) (x >>> 52) & 0x7ff;
            boolean notSp = x != toDouble(toSingle(x));
            match |= exp > 0 && exp < 0x381;
            cc = (c.neg ? 8 : 0) | (match ? 2 : 0) | (notSp ? 1 : 0);
        } else {
            cc = (c.neg ? 8 : 0) | (match ? 2 : 0);
        }
        s.fpscr = (s.fpscr & ~FPCC) | ((long) cc << 12);
        s.setCrField((insn >>> 23) & 7, cc);
    }

    // ------------------------------------------------------------------ permute and logical

    static void xxpermdi(CpuState s, int insn) {
        checkVsx(s);
        int dm = (insn >>> 8) & 3;
        int a = xa(insn), b = xb(insn);
        long hi = (dm & 2) == 0 ? s.vsrHi[a] : s.vsrLo[a];
        long lo = (dm & 1) == 0 ? s.vsrHi[b] : s.vsrLo[b];
        V.set(s, xt(insn), hi, lo);
    }

    static void xxsldwi(CpuState s, int insn) {
        checkVsx(s);
        int shw = (insn >>> 8) & 3;
        int a = xa(insn), b = xb(insn);
        long[] r = VmxInsns.shl256(s.vsrHi[a], s.vsrLo[a], s.vsrHi[b], s.vsrLo[b], 32 * shw);
        V.set(s, xt(insn), r[0], r[1]);
    }

    static void xxmrg(CpuState s, int insn, boolean low) {
        checkVsx(s);
        long a = low ? s.vsrLo[xa(insn)] : s.vsrHi[xa(insn)];
        long b = low ? s.vsrLo[xb(insn)] : s.vsrHi[xb(insn)];
        long m = 0xffffffffL;
        V.set(s, xt(insn), (a & ~m) | (b >>> 32), (a << 32) | (b & m));
    }

    static void xxspltw(CpuState s, int insn) {
        checkVsx(s);
        int b = xb(insn);
        int uim = (insn >>> 16) & 3;
        long w = V.w(s.vsrHi[b], s.vsrLo[b], uim) & 0xffffffffL;
        long r = (w << 32) | w;
        V.set(s, xt(insn), r, r);
    }

    static void xxperm(CpuState s, int insn, boolean reverse) {
        checkVsx(s);
        int a = xa(insn), t = xt(insn), b = xb(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], th = s.vsrHi[t], tl = s.vsrLo[t];
        long ch = s.vsrHi[b], cl = s.vsrLo[b];
        long rh = 0, rl = 0;
        for (int i = 0; i < 16; i++) {
            int sel = V.b(ch, cl, i);
            int idx = reverse ? 31 - (sel & 31) : sel & 31;
            int v = idx < 16 ? V.b(ah, al, idx) : V.b(th, tl, idx - 16);
            if (i < 8) rh |= (long) v << (56 - 8 * i); else rl |= (long) v << (56 - 8 * (i - 8));
        }
        V.set(s, t, rh, rl);
    }

    static void xxextractuw(CpuState s, int insn) {
        checkVsx(s);
        int uim = (insn >>> 16) & 15;
        int b = xb(insn);
        long v = 0;
        for (int i = 0; i < 4; i++) v = (v << 8) | V.b(s.vsrHi[b], s.vsrLo[b], (uim + i) % 16);
        V.set(s, xt(insn), v, 0);
    }

    static void xxinsertw(CpuState s, int insn) {
        checkVsx(s);
        int uim = (insn >>> 16) & 15;
        int b = xb(insn), t = xt(insn);
        long src = s.vsrHi[b];
        byte[] r = VmxInsns.toBytes(s.vsrHi[t], s.vsrLo[t]);
        for (int i = 0; i < 4 && uim + i < 16; i++) r[uim + i] = (byte) (src >>> (24 - 8 * i));
        V.set(s, t, VmxInsns.fromBytes(r, 0), VmxInsns.fromBytes(r, 8));
    }

    @FunctionalInterface interface L2 { long f(long a, long b); }

    static void logical(CpuState s, int insn, L2 f) {
        checkVsx(s);
        int a = xa(insn), b = xb(insn);
        V.set(s, xt(insn), f.f(s.vsrHi[a], s.vsrHi[b]), f.f(s.vsrLo[a], s.vsrLo[b]));
    }

    static void xxsel(CpuState s, int insn) {
        checkVsx(s);
        int a = xa(insn), b = xb(insn), c = xc(insn);
        V.set(s, xt(insn), (s.vsrHi[b] & s.vsrHi[c]) | (s.vsrHi[a] & ~s.vsrHi[c]),
                (s.vsrLo[b] & s.vsrLo[c]) | (s.vsrLo[a] & ~s.vsrLo[c]));
    }

    @FunctionalInterface interface L1 { long f(long b); }

    /** Apply f to both doublewords of XB. */
    static void dw1(CpuState s, int insn, L1 f) {
        checkVsx(s);
        int b = xb(insn);
        V.set(s, xt(insn), f.f(s.vsrHi[b]), f.f(s.vsrLo[b]));
    }

    /** Scalar move on dw0, dw1 of the target cleared. */
    static void scalarMove(CpuState s, int insn, L2 f) {
        checkVsx(s);
        V.set(s, xt(insn), f.f(s.vsrHi[xa(insn)], s.vsrHi[xb(insn)]), 0);
    }

    static long xsigdp(long x) {
        long e = (x >>> 52) & 0x7ff;
        return (x & 0xfffffffffffffL) | (e == 0 || e == 2047 ? 0 : 1L << 52);
    }

    static long xsigsp2(long x) {
        long r = 0;
        for (int k = 0; k < 2; k++) {
            int w = (int) (x >>> (32 - 32 * k));
            int e = (w >>> 23) & 0xff;
            int f = w & 0x7fffff;
            int v = e != 0 && e != 255 ? f | 0x800000 : f;
            r |= (v & 0xffffffffL) << (32 - 32 * k);
        }
        return r;
    }

    // ------------------------------------------------------------------ moves between GPRs and VSRs

    static void mfvsr(CpuState s, int insn, int kind) {
        int xs = xt(insn);
        switch (kind) {
            case 0 -> { // mfvsrd
                checkFpOrVec(s, xs);
                s.gpr[V.ra(insn)] = s.vsrHi[xs];
            }
            case 1 -> { // mfvsrwz
                checkFpOrVec(s, xs);
                s.gpr[V.ra(insn)] = s.vsrHi[xs] & 0xffffffffL;
            }
            default -> { // mfvsrld
                checkVsxOrVec(s, xs);
                s.gpr[V.ra(insn)] = s.vsrLo[xs];
            }
        }
    }

    static void mtvsr(CpuState s, int insn, int kind) {
        int t = xt(insn);
        long ra = s.gpr[V.ra(insn)];
        switch (kind) {
            case 0 -> { // mtvsrd
                checkFpOrVec(s, t);
                s.vsrHi[t] = ra;
            }
            case 1 -> { // mtvsrwa
                checkFpOrVec(s, t);
                s.vsrHi[t] = (int) ra;
            }
            case 2 -> { // mtvsrwz
                checkFpOrVec(s, t);
                s.vsrHi[t] = ra & 0xffffffffL;
            }
            case 3 -> { // mtvsrdd
                checkVsxOrVec(s, t);
                V.set(s, t, V.ra(insn) == 0 ? 0 : ra, s.gpr[V.rb(insn)]);
            }
            default -> { // mtvsrws
                checkVsxOrVec(s, t);
                long w = ra & 0xffffffffL;
                long v = (w << 32) | w;
                V.set(s, t, v, v);
            }
        }
    }

    // ------------------------------------------------------------------ loads and stores

    static long eaX(CpuState s, int insn) {
        int a = V.ra(insn);
        long ea = (a == 0 ? 0 : s.gpr[a]) + s.gpr[V.rb(insn)];
        return s.is64() ? ea : ea & 0xffffffffL;
    }

    static long eaRa(CpuState s, int insn) {
        int a = V.ra(insn);
        long ea = a == 0 ? 0 : s.gpr[a];
        return s.is64() ? ea : ea & 0xffffffffL;
    }

    static long eaDisp(CpuState s, int insn, long disp) {
        int a = V.ra(insn);
        long ea = (a == 0 ? 0 : s.gpr[a]) + disp;
        return s.is64() ? ea : ea & 0xffffffffL;
    }

    static long addr(CpuState s, long ea, long n) {
        long r = ea + n;
        return s.is64() ? r : r & 0xffffffffL;
    }

    /** Fault (DSI) before any byte is written if either end of the access is not writable. */
    static void probeStore(CpuState s, long ea, int len) {
        s.mem.translateData(ea, true);
        s.mem.translateData(addr(s, ea, len - 1), true);
    }

    /** Full 16-byte load in the mode's byte order (lxv, lxvx, lvx-style). */
    static void ld128(CpuState s, int t, long ea) {
        long hi, lo;
        if (s.littleEndian()) {
            lo = s.mem.read64(ea);
            hi = s.mem.read64(addr(s, ea, 8));
        } else {
            hi = s.mem.read64(ea);
            lo = s.mem.read64(addr(s, ea, 8));
        }
        V.set(s, t, hi, lo);
    }

    static void st128(CpuState s, int t, long ea) {
        probeStore(s, ea, 16);
        long hi = s.vsrHi[t], lo = s.vsrLo[t];
        if (s.littleEndian()) {
            s.mem.write64(ea, lo);
            s.mem.write64(addr(s, ea, 8), hi);
        } else {
            s.mem.write64(ea, hi);
            s.mem.write64(addr(s, ea, 8), lo);
        }
    }

    /** Big-endian doubleword access regardless of MSR[LE]. */
    static long readBE64(CpuState s, long ea) {
        long v = s.mem.read64(ea);
        return s.littleEndian() ? Long.reverseBytes(v) : v;
    }

    static void writeBE64(CpuState s, long ea, long v) {
        s.mem.write64(ea, s.littleEndian() ? Long.reverseBytes(v) : v);
    }

    /** lxvd2x (0), lxvw4x (1), lxvh8x (2), lxvb16x (3). */
    static void lxvElems(CpuState s, int insn, int kind) {
        checkVsx(s);
        long ea = eaX(s, insn);
        long hi, lo;
        switch (kind) {
            case 0 -> {
                hi = s.mem.read64(ea);
                lo = s.mem.read64(addr(s, ea, 8));
            }
            case 1 -> {
                hi = s.mem.read64(ea);
                lo = s.mem.read64(addr(s, ea, 8));
                if (s.littleEndian()) {
                    hi = (hi >>> 32) | (hi << 32);
                    lo = (lo >>> 32) | (lo << 32);
                }
            }
            case 2 -> {
                hi = readBE64(s, ea);
                lo = readBE64(s, addr(s, ea, 8));
                if (s.littleEndian()) {
                    hi = V.bswapElems(hi, 2);
                    lo = V.bswapElems(lo, 2);
                }
            }
            default -> {
                hi = readBE64(s, ea);
                lo = readBE64(s, addr(s, ea, 8));
            }
        }
        V.set(s, xt(insn), hi, lo);
    }

    static void stxvElems(CpuState s, int insn, int kind) {
        checkVsx(s);
        long ea = eaX(s, insn);
        int t = xt(insn);
        long hi = s.vsrHi[t], lo = s.vsrLo[t];
        probeStore(s, ea, 16);
        switch (kind) {
            case 0 -> {
                s.mem.write64(ea, hi);
                s.mem.write64(addr(s, ea, 8), lo);
            }
            case 1 -> {
                if (s.littleEndian()) {
                    hi = (hi >>> 32) | (hi << 32);
                    lo = (lo >>> 32) | (lo << 32);
                }
                s.mem.write64(ea, hi);
                s.mem.write64(addr(s, ea, 8), lo);
            }
            case 2 -> {
                if (s.littleEndian()) {
                    hi = V.bswapElems(hi, 2);
                    lo = V.bswapElems(lo, 2);
                }
                writeBE64(s, ea, hi);
                writeBE64(s, addr(s, ea, 8), lo);
            }
            default -> {
                writeBE64(s, ea, hi);
                writeBE64(s, addr(s, ea, 8), lo);
            }
        }
    }

    /** Scalar loads into dw0 (dw1 untouched, as QEMU): kind 0 lxsdx, 1 lxsiwax, 2 lxsiwzx, 3 lxsspx, 4 lxsibzx, 5 lxsihzx. */
    static void lxs(CpuState s, int insn, int kind) {
        checkVsx(s);
        long ea = eaX(s, insn);
        long v = switch (kind) {
            case 0 -> s.mem.read64(ea);
            case 1 -> (long) s.mem.read32(ea);
            case 2 -> s.mem.read32(ea) & 0xffffffffL;
            case 3 -> toDouble(s.mem.read32(ea));
            case 4 -> s.mem.read8(ea);
            default -> s.mem.read16(ea);
        };
        s.vsrHi[xt(insn)] = v;
    }

    /** Scalar stores from dw0: kind 0 stxsdx, 1 stxsiwx, 2 stxsspx, 3 stxsibx, 4 stxsihx. */
    static void stxs(CpuState s, int insn, int kind) {
        checkVsx(s);
        long ea = eaX(s, insn);
        long v = s.vsrHi[xt(insn)];
        switch (kind) {
            case 0 -> s.mem.write64(ea, v);
            case 1 -> s.mem.write32(ea, (int) v);
            case 2 -> s.mem.write32(ea, toSingle(v));
            case 3 -> s.mem.write8(ea, (int) v);
            default -> s.mem.write16(ea, (int) v);
        }
    }

    static void lxvdsx(CpuState s, int insn) {
        checkVsx(s);
        long v = s.mem.read64(eaX(s, insn));
        V.set(s, xt(insn), v, v);
    }

    static void lxvwsx(CpuState s, int insn) {
        int t = xt(insn);
        checkVsxOrVec(s, t);
        long w = s.mem.read32(eaX(s, insn)) & 0xffffffffL;
        long v = (w << 32) | w;
        V.set(s, t, v, v);
    }

    static void lxvx(CpuState s, int insn) {
        int t = xt(insn);
        checkVsxOrVec(s, t);
        ld128(s, t, eaX(s, insn));
    }

    static void stxvx(CpuState s, int insn) {
        int t = xt(insn);
        checkVsxOrVec(s, t);
        st128(s, t, eaX(s, insn));
    }

    /** lxvl / lxvll: load GPR[RB] bits 0:7 bytes (at most 16). */
    static void lxvl(CpuState s, int insn, boolean leftJustified) {
        int t = xt(insn);
        checkVsxOrVec(s, t);
        long ea = eaRa(s, insn);
        int nb = (int) (s.gpr[V.rb(insn)] >>> 56) & 0xff;
        byte[] r = new byte[16];
        if (nb != 0) {
            nb = Math.min(nb, 16);
            boolean le = s.littleEndian() && !leftJustified;
            for (int i = 0; i < nb; i++) {
                int idx = le ? 15 - i : i;
                r[idx] = (byte) s.mem.read8(ea);
                ea = addr(s, ea, 1);
            }
        }
        V.set(s, t, VmxInsns.fromBytes(r, 0), VmxInsns.fromBytes(r, 8));
    }

    static void stxvl(CpuState s, int insn, boolean leftJustified) {
        int t = xt(insn);
        checkVsxOrVec(s, t);
        long ea = eaRa(s, insn);
        int nb = (int) (s.gpr[V.rb(insn)] >>> 56) & 0xff;
        if (nb == 0) return;
        nb = Math.min(nb, 16);
        byte[] r = VmxInsns.toBytes(s.vsrHi[t], s.vsrLo[t]);
        boolean le = s.littleEndian() && !leftJustified;
        for (int i = 0; i < nb; i++) {
            int idx = le ? 15 - i : i;
            s.mem.write8(ea, r[idx]);
            ea = addr(s, ea, 1);
        }
    }

    // DS/DQ forms (primary 57 / 61)
    static void lxsd(CpuState s, int insn) {
        checkVsx(s);
        long ea = eaDisp(s, insn, (short) (insn & ~3));
        V.set(s, 32 + V.rt(insn), s.mem.read64(ea), 0);
    }

    static void lxssp(CpuState s, int insn) {
        V.checkVec(s);
        long ea = eaDisp(s, insn, (short) (insn & ~3));
        V.set(s, 32 + V.rt(insn), toDouble(s.mem.read32(ea)), 0);
    }

    static void stxsd(CpuState s, int insn) {
        V.checkVec(s);
        long ea = eaDisp(s, insn, (short) (insn & ~3));
        s.mem.write64(ea, s.vsrHi[32 + V.rt(insn)]);
    }

    static void stxssp(CpuState s, int insn) {
        V.checkVec(s);
        long ea = eaDisp(s, insn, (short) (insn & ~3));
        s.mem.write32(ea, toSingle(s.vsrHi[32 + V.rt(insn)]));
    }

    static int dqT(int insn) {
        return ((insn >>> 21) & 31) | (((insn >>> 3) & 1) << 5);
    }

    static void lxv(CpuState s, int insn) {
        int t = dqT(insn);
        checkVsxOrVec(s, t);
        ld128(s, t, eaDisp(s, insn, (short) (insn & ~15)));
    }

    static void stxv(CpuState s, int insn) {
        int t = dqT(insn);
        checkVsxOrVec(s, t);
        st128(s, t, eaDisp(s, insn, (short) (insn & ~15)));
    }

    // ------------------------------------------------------------------ registration

    private static final int P60 = 60 << 26;

    private static void xx3(DecodeTable t, int xo, String name, Op op) {
        t.add(0xfc0007f8, P60 | (xo << 3), name, op);
    }

    private static void xx2(DecodeTable t, int xo, String name, Op op) {
        t.add(0xfc0007fc, P60 | (xo << 2), name, op);
    }

    /** XX2 form with an opcode extension in bits 11:15. */
    private static void xx2eo(DecodeTable t, int xo, int eo, String name, Op op) {
        t.add(0xfc1f07fc, P60 | (eo << 16) | (xo << 2), name, op);
    }

    /** XX3 form whose bit 21 is the Rc bit (vector compares). */
    private static void xx3rc(DecodeTable t, int xo7, String name, Op op) {
        t.add(0xfc0003f8, P60 | (xo7 << 3), name, op);
    }

    private static void x31(DecodeTable t, int xo, String name, Op op) {
        t.addX(31, xo, name, op);
    }

    @Override
    public void register(DecodeTable t) {
        // ---- arithmetic
        String[] ar = {"add", "sub", "mul", "div"};
        for (int op = 0; op < 4; op++) {
            int o = op;
            xx3(t, 32 + 8 * op, "xs" + ar[op] + "dp", (s, i) -> binary(s, i, D, 8, 1, true, false, o));
            xx3(t, 8 * op, "xs" + ar[op] + "sp", (s, i) -> binary(s, i, D, 8, 1, true, true, o));
            xx3(t, 96 + 8 * op, "xv" + ar[op] + "dp", (s, i) -> binary(s, i, D, 8, 2, false, false, o));
            xx3(t, 64 + 8 * op, "xv" + ar[op] + "sp", (s, i) -> binary(s, i, S, 4, 4, false, false, o));
        }
        xx2(t, 75, "xssqrtdp", (s, i) -> unary(s, i, D, 8, 1, true, false, 0));
        xx2(t, 11, "xssqrtsp", (s, i) -> unary(s, i, D, 8, 1, true, true, 0));
        xx2(t, 203, "xvsqrtdp", (s, i) -> unary(s, i, D, 8, 2, false, false, 0));
        xx2(t, 139, "xvsqrtsp", (s, i) -> unary(s, i, S, 4, 4, false, false, 0));
        xx2(t, 90, "xsredp", (s, i) -> unary(s, i, D, 8, 1, true, false, 1));
        xx2(t, 26, "xsresp", (s, i) -> unary(s, i, D, 8, 1, true, true, 1));
        xx2(t, 218, "xvredp", (s, i) -> unary(s, i, D, 8, 2, false, false, 1));
        xx2(t, 154, "xvresp", (s, i) -> unary(s, i, S, 4, 4, false, false, 1));
        xx2(t, 74, "xsrsqrtedp", (s, i) -> unary(s, i, D, 8, 1, true, false, 2));
        xx2(t, 10, "xsrsqrtesp", (s, i) -> unary(s, i, D, 8, 1, true, true, 2));
        xx2(t, 202, "xvrsqrtedp", (s, i) -> unary(s, i, D, 8, 2, false, false, 2));
        xx2(t, 138, "xvrsqrtesp", (s, i) -> unary(s, i, S, 4, 4, false, false, 2));
        // ---- multiply-add: base XO for the A form; the M form is base + 8
        String[] mn = {"madd", "msub", "nmadd", "nmsub"};
        int[] xsdp = {33, 49, 161, 177}, xssp = {1, 17, 129, 145}, xvdp = {97, 113, 225, 241}, xvsp = {65, 81, 193, 209};
        for (int k = 0; k < 4; k++) {
            boolean negAdd = (k & 1) != 0, negRes = k >= 2;
            for (int m = 0; m < 2; m++) {
                boolean mform = m == 1;
                String f = mform ? "m" : "a";
                xx3(t, xsdp[k] + 8 * m, "xs" + mn[k] + f + "dp", (s, i) -> madd(s, i, D, 8, 1, true, mform, negAdd, negRes));
                xx3(t, xssp[k] + 8 * m, "xs" + mn[k] + f + "sp", (s, i) -> madd(s, i, DS, 8, 1, true, mform, negAdd, negRes));
                xx3(t, xvdp[k] + 8 * m, "xv" + mn[k] + f + "dp", (s, i) -> madd(s, i, D, 8, 2, false, mform, negAdd, negRes));
                xx3(t, xvsp[k] + 8 * m, "xv" + mn[k] + f + "sp", (s, i) -> madd(s, i, S, 4, 4, false, mform, negAdd, negRes));
            }
        }
        // ---- compares
        xx3(t, 3, "xscmpeqdp", (s, i) -> xscmp(s, i, 0));
        xx3(t, 19, "xscmpgedp", (s, i) -> xscmp(s, i, 1));
        xx3(t, 11, "xscmpgtdp", (s, i) -> xscmp(s, i, 2));
        t.add(0xfc0007f8, P60 | (59 << 3), "xscmpexpdp", VsxInsns::xscmpexpdp);
        t.add(0xfc0007f9, P60 | (43 << 3), "xscmpodp", (s, i) -> xscmpou(s, i, true));
        t.add(0xfc0007f9, P60 | (35 << 3), "xscmpudp", (s, i) -> xscmpou(s, i, false));
        xx3rc(t, 67, "xvcmpeqsp", (s, i) -> xvcmp(s, i, 4, 0));
        xx3rc(t, 75, "xvcmpgtsp", (s, i) -> xvcmp(s, i, 4, 2));
        xx3rc(t, 83, "xvcmpgesp", (s, i) -> xvcmp(s, i, 4, 1));
        xx3rc(t, 91, "xvcmpnesp", (s, i) -> xvcmp(s, i, 4, 3));
        xx3rc(t, 99, "xvcmpeqdp", (s, i) -> xvcmp(s, i, 8, 0));
        xx3rc(t, 107, "xvcmpgtdp", (s, i) -> xvcmp(s, i, 8, 2));
        xx3rc(t, 115, "xvcmpgedp", (s, i) -> xvcmp(s, i, 8, 1));
        xx3rc(t, 123, "xvcmpnedp", (s, i) -> xvcmp(s, i, 8, 3));
        // ---- min / max
        xx3(t, 160, "xsmaxdp", (s, i) -> minmax(s, i, 8, 1, true));
        xx3(t, 168, "xsmindp", (s, i) -> minmax(s, i, 8, 1, false));
        xx3(t, 224, "xvmaxdp", (s, i) -> minmax(s, i, 8, 2, true));
        xx3(t, 232, "xvmindp", (s, i) -> minmax(s, i, 8, 2, false));
        xx3(t, 192, "xvmaxsp", (s, i) -> minmax(s, i, 4, 4, true));
        xx3(t, 200, "xvminsp", (s, i) -> minmax(s, i, 4, 4, false));
        xx3(t, 128, "xsmaxcdp", (s, i) -> minmaxC(s, i, true));
        xx3(t, 136, "xsmincdp", (s, i) -> minmaxC(s, i, false));
        xx3(t, 144, "xsmaxjdp", (s, i) -> minmaxJ(s, i, true));
        xx3(t, 152, "xsminjdp", (s, i) -> minmaxJ(s, i, false));
        // ---- test for software divide / sqrt
        t.add(0xfc0007f8, P60 | (61 << 3), "xstdivdp", (s, i) -> tdiv(s, i, 8, 1));
        t.add(0xfc0007f8, P60 | (125 << 3), "xvtdivdp", (s, i) -> tdiv(s, i, 8, 2));
        t.add(0xfc0007f8, P60 | (93 << 3), "xvtdivsp", (s, i) -> tdiv(s, i, 4, 4));
        t.add(0xfc0007fc, P60 | (106 << 2), "xstsqrtdp", (s, i) -> tsqrt(s, i, 8, 1));
        t.add(0xfc0007fc, P60 | (234 << 2), "xvtsqrtdp", (s, i) -> tsqrt(s, i, 8, 2));
        t.add(0xfc0007fc, P60 | (170 << 2), "xvtsqrtsp", (s, i) -> tsqrt(s, i, 4, 4));
        // ---- conversions between floating formats
        xx2(t, 265, "xscvdpsp", (s, i) -> cvtff(s, i, 0));
        xx2(t, 393, "xvcvdpsp", (s, i) -> cvtff(s, i, 1));
        xx2(t, 329, "xscvspdp", (s, i) -> cvtff(s, i, 2));
        xx2(t, 457, "xvcvspdp", (s, i) -> cvtff(s, i, 3));
        xx2eo(t, 347, 17, "xscvdphp", (s, i) -> cvtff(s, i, 4));
        xx2eo(t, 347, 16, "xscvhpdp", (s, i) -> cvtff(s, i, 5));
        xx2eo(t, 475, 25, "xvcvsphp", (s, i) -> cvtff(s, i, 6));
        xx2eo(t, 475, 24, "xvcvhpsp", (s, i) -> cvtff(s, i, 7));
        xx2(t, 267, "xscvdpspn", VsxInsns::xscvdpspn);
        xx2(t, 331, "xscvspdpn", (s, i) -> {
            checkVsx(s);
            V.set(s, xt(i), toDouble((int) (s.vsrHi[xb(i)] >>> 32)), 0);
        });
        xx2(t, 281, "xsrsp", VsxInsns::xsrsp);
        // ---- float to integer (truncating)
        xx2(t, 344, "xscvdpsxds", (s, i) -> cvtfi(s, i, 8, 8, true, 1, true, false));
        xx2(t, 328, "xscvdpuxds", (s, i) -> cvtfi(s, i, 8, 8, false, 1, true, false));
        xx2(t, 88, "xscvdpsxws", (s, i) -> cvtfi(s, i, 8, 4, true, 1, true, true));
        xx2(t, 72, "xscvdpuxws", (s, i) -> cvtfi(s, i, 8, 4, false, 1, true, true));
        xx2(t, 472, "xvcvdpsxds", (s, i) -> cvtfi(s, i, 8, 8, true, 2, false, false));
        xx2(t, 456, "xvcvdpuxds", (s, i) -> cvtfi(s, i, 8, 8, false, 2, false, false));
        xx2(t, 216, "xvcvdpsxws", (s, i) -> cvtfi(s, i, 8, 4, true, 2, false, true));
        xx2(t, 200, "xvcvdpuxws", (s, i) -> cvtfi(s, i, 8, 4, false, 2, false, true));
        xx2(t, 408, "xvcvspsxds", (s, i) -> cvtfi(s, i, 4, 8, true, 2, false, false));
        xx2(t, 392, "xvcvspuxds", (s, i) -> cvtfi(s, i, 4, 8, false, 2, false, false));
        xx2(t, 152, "xvcvspsxws", (s, i) -> cvtfi(s, i, 4, 4, true, 4, false, false));
        xx2(t, 136, "xvcvspuxws", (s, i) -> cvtfi(s, i, 4, 4, false, 4, false, false));
        // ---- integer to float
        xx2(t, 376, "xscvsxddp", (s, i) -> cvtif(s, i, 8, 8, true, 1, true, false, false));
        xx2(t, 360, "xscvuxddp", (s, i) -> cvtif(s, i, 8, 8, false, 1, true, false, false));
        xx2(t, 312, "xscvsxdsp", (s, i) -> cvtif(s, i, 8, 8, true, 1, true, true, false));
        xx2(t, 296, "xscvuxdsp", (s, i) -> cvtif(s, i, 8, 8, false, 1, true, true, false));
        xx2(t, 504, "xvcvsxddp", (s, i) -> cvtif(s, i, 8, 8, true, 2, false, false, false));
        xx2(t, 488, "xvcvuxddp", (s, i) -> cvtif(s, i, 8, 8, false, 2, false, false, false));
        xx2(t, 248, "xvcvsxwdp", (s, i) -> cvtif(s, i, 4, 8, true, 2, false, false, false));
        xx2(t, 232, "xvcvuxwdp", (s, i) -> cvtif(s, i, 4, 8, false, 2, false, false, false));
        xx2(t, 184, "xvcvsxwsp", (s, i) -> cvtif(s, i, 4, 4, true, 4, false, false, false));
        xx2(t, 168, "xvcvuxwsp", (s, i) -> cvtif(s, i, 4, 4, false, 4, false, false, false));
        xx2(t, 440, "xvcvsxdsp", (s, i) -> cvtif(s, i, 8, 4, true, 2, false, false, true));
        xx2(t, 424, "xvcvuxdsp", (s, i) -> cvtif(s, i, 8, 4, false, 2, false, false, true));
        // ---- round to integral
        int[] rm = {SoftFloat.RNA, -1, SoftFloat.RM, SoftFloat.RP, SoftFloat.RZ};
        String[] rs = {"", "c", "m", "p", "z"};
        int[] xsr = {73, 107, 121, 105, 89}, xvdr = {201, 235, 249, 233, 217}, xvsr = {137, 171, 185, 169, 153};
        for (int k = 0; k < 5; k++) {
            int mode = rm[k];
            xx2(t, xsr[k], "xsrdpi" + rs[k], (s, i) -> round(s, i, 8, 1, true, mode));
            xx2(t, xvdr[k], "xvrdpi" + rs[k], (s, i) -> round(s, i, 8, 2, false, mode));
            xx2(t, xvsr[k], "xvrspi" + rs[k], (s, i) -> round(s, i, 4, 4, false, mode));
        }
        // ---- test data class
        t.add(0xfc0007b8, P60 | 0x7a8, "xvtstdcdp", (s, i) -> xvtstdc(s, i, 8));
        t.add(0xfc0007b8, P60 | 0x6a8, "xvtstdcsp", (s, i) -> xvtstdc(s, i, 4));
        t.add(0xfc0007fc, P60 | (298 << 2), "xststdcsp", (s, i) -> xststdc(s, i, true));
        t.add(0xfc0007fc, P60 | (362 << 2), "xststdcdp", (s, i) -> xststdc(s, i, false));
        // ---- sign manipulation
        xx2(t, 345, "xsabsdp", (s, i) -> scalarMove(s, i, (a, b) -> b & Long.MAX_VALUE));
        xx2(t, 361, "xsnabsdp", (s, i) -> scalarMove(s, i, (a, b) -> b | Long.MIN_VALUE));
        xx2(t, 377, "xsnegdp", (s, i) -> scalarMove(s, i, (a, b) -> b ^ Long.MIN_VALUE));
        xx3(t, 176, "xscpsgndp", (s, i) -> scalarMove(s, i, (a, b) -> (a & Long.MIN_VALUE) | (b & Long.MAX_VALUE)));
        long sp = 0x8000000080000000L;
        xx2eo(t, 473, 0, "xvabsdp", (s, i) -> dw1(s, i, b -> b & Long.MAX_VALUE));
        xx2eo(t, 489, 0, "xvnabsdp", (s, i) -> dw1(s, i, b -> b | Long.MIN_VALUE));
        xx2eo(t, 505, 0, "xvnegdp", (s, i) -> dw1(s, i, b -> b ^ Long.MIN_VALUE));
        xx2eo(t, 409, 0, "xvabssp", (s, i) -> dw1(s, i, b -> b & ~sp));
        xx2eo(t, 425, 0, "xvnabssp", (s, i) -> dw1(s, i, b -> b | sp));
        xx2eo(t, 441, 0, "xvnegsp", (s, i) -> dw1(s, i, b -> b ^ sp));
        xx3(t, 240, "xvcpsgndp", (s, i) -> logical(s, i, (a, b) -> (a & Long.MIN_VALUE) | (b & Long.MAX_VALUE)));
        xx3(t, 208, "xvcpsgnsp", (s, i) -> logical(s, i, (a, b) -> (a & sp) | (b & ~sp)));
        // ---- exponent / significand
        t.add(0xfc1f07fc, P60 | (347 << 2), "xsxexpdp", (s, i) -> {
            checkVsx(s);
            s.gpr[V.rt(i)] = (s.vsrHi[xb(i)] >>> 52) & 0x7ff;
        });
        t.add(0xfc1f07fc, P60 | (1 << 16) | (347 << 2), "xsxsigdp", (s, i) -> {
            checkVsx(s);
            s.gpr[V.rt(i)] = xsigdp(s.vsrHi[xb(i)]);
        });
        t.add(0xfc0007fe, P60 | (918 << 1), "xsiexpdp", (s, i) -> {
            checkVsx(s);
            long ra = s.gpr[V.ra(i)], rb = s.gpr[V.rb(i)];
            V.set(s, xt(i), (ra & 0x800fffffffffffffL) | ((rb & 0x7ff) << 52), 0);
        });
        xx3(t, 216, "xviexpsp", (s, i) -> logical(s, i, (a, b) -> (a & 0x807fffff807fffffL) | ((b & 0xff000000ffL) << 23)));
        xx3(t, 248, "xviexpdp", (s, i) -> logical(s, i, (a, b) -> (a & 0x800fffffffffffffL) | ((b & 0x7ff) << 52)));
        xx2eo(t, 475, 0, "xvxexpdp", (s, i) -> dw1(s, i, b -> (b >>> 52) & 0x7ff));
        xx2eo(t, 475, 1, "xvxsigdp", (s, i) -> dw1(s, i, VsxInsns::xsigdp));
        xx2eo(t, 475, 8, "xvxexpsp", (s, i) -> dw1(s, i, b -> (b >>> 23) & 0xff000000ffL));
        xx2eo(t, 475, 9, "xvxsigsp", (s, i) -> dw1(s, i, VsxInsns::xsigsp2));
        // ---- byte reverse
        xx2eo(t, 475, 7, "xxbrh", (s, i) -> dw1(s, i, b -> V.bswapElems(b, 2)));
        xx2eo(t, 475, 15, "xxbrw", (s, i) -> dw1(s, i, b -> V.bswapElems(b, 4)));
        xx2eo(t, 475, 23, "xxbrd", (s, i) -> dw1(s, i, Long::reverseBytes));
        xx2eo(t, 475, 31, "xxbrq", (s, i) -> {
            checkVsx(s);
            int b = xb(i);
            V.set(s, xt(i), Long.reverseBytes(s.vsrLo[b]), Long.reverseBytes(s.vsrHi[b]));
        });
        // ---- logical
        xx3(t, 130, "xxland", (s, i) -> logical(s, i, (a, b) -> a & b));
        xx3(t, 138, "xxlandc", (s, i) -> logical(s, i, (a, b) -> a & ~b));
        xx3(t, 146, "xxlor", (s, i) -> logical(s, i, (a, b) -> a | b));
        xx3(t, 154, "xxlxor", (s, i) -> logical(s, i, (a, b) -> a ^ b));
        xx3(t, 162, "xxlnor", (s, i) -> logical(s, i, (a, b) -> ~(a | b)));
        xx3(t, 170, "xxlorc", (s, i) -> logical(s, i, (a, b) -> a | ~b));
        xx3(t, 178, "xxlnand", (s, i) -> logical(s, i, (a, b) -> ~(a & b)));
        xx3(t, 186, "xxleqv", (s, i) -> logical(s, i, (a, b) -> ~(a ^ b)));
        t.add(0xfc000030, P60 | 0x30, "xxsel", VsxInsns::xxsel);
        // ---- permute / merge / splat / extract / insert
        t.add(0xfc0004f8, P60 | 0x50, "xxpermdi", VsxInsns::xxpermdi);
        t.add(0xfc0004f8, P60 | 0x10, "xxsldwi", VsxInsns::xxsldwi);
        xx3(t, 18, "xxmrghw", (s, i) -> xxmrg(s, i, false));
        xx3(t, 50, "xxmrglw", (s, i) -> xxmrg(s, i, true));
        xx3(t, 26, "xxperm", (s, i) -> xxperm(s, i, false));
        xx3(t, 58, "xxpermr", (s, i) -> xxperm(s, i, true));
        xx2(t, 164, "xxspltw", VsxInsns::xxspltw);
        t.add(0xfc1807fe, P60 | (360 << 1), "xxspltib", (s, i) -> {
            int x = xt(i);
            checkVsxOrVec(s, x);
            long v = ((i >>> 11) & 0xff) * 0x0101010101010101L;
            V.set(s, x, v, v);
        });
        xx2(t, 165, "xxextractuw", VsxInsns::xxextractuw);
        xx2(t, 181, "xxinsertw", VsxInsns::xxinsertw);
        // ---- GPR <-> VSR moves (primary 31)
        x31(t, 51, "mfvsrd", (s, i) -> mfvsr(s, i, 0));
        x31(t, 115, "mfvsrwz", (s, i) -> mfvsr(s, i, 1));
        x31(t, 307, "mfvsrld", (s, i) -> mfvsr(s, i, 2));
        x31(t, 179, "mtvsrd", (s, i) -> mtvsr(s, i, 0));
        x31(t, 211, "mtvsrwa", (s, i) -> mtvsr(s, i, 1));
        x31(t, 243, "mtvsrwz", (s, i) -> mtvsr(s, i, 2));
        x31(t, 435, "mtvsrdd", (s, i) -> mtvsr(s, i, 3));
        x31(t, 403, "mtvsrws", (s, i) -> mtvsr(s, i, 4));
        // ---- loads and stores (primary 31, X form; TX in bit 31)
        x31(t, 588, "lxsdx", (s, i) -> lxs(s, i, 0));
        x31(t, 76, "lxsiwax", (s, i) -> lxs(s, i, 1));
        x31(t, 12, "lxsiwzx", (s, i) -> lxs(s, i, 2));
        x31(t, 524, "lxsspx", (s, i) -> lxs(s, i, 3));
        x31(t, 781, "lxsibzx", (s, i) -> lxs(s, i, 4));
        x31(t, 813, "lxsihzx", (s, i) -> lxs(s, i, 5));
        x31(t, 716, "stxsdx", (s, i) -> stxs(s, i, 0));
        x31(t, 140, "stxsiwx", (s, i) -> stxs(s, i, 1));
        x31(t, 652, "stxsspx", (s, i) -> stxs(s, i, 2));
        x31(t, 909, "stxsibx", (s, i) -> stxs(s, i, 3));
        x31(t, 941, "stxsihx", (s, i) -> stxs(s, i, 4));
        x31(t, 844, "lxvd2x", (s, i) -> lxvElems(s, i, 0));
        x31(t, 780, "lxvw4x", (s, i) -> lxvElems(s, i, 1));
        x31(t, 812, "lxvh8x", (s, i) -> lxvElems(s, i, 2));
        x31(t, 876, "lxvb16x", (s, i) -> lxvElems(s, i, 3));
        x31(t, 972, "stxvd2x", (s, i) -> stxvElems(s, i, 0));
        x31(t, 908, "stxvw4x", (s, i) -> stxvElems(s, i, 1));
        x31(t, 940, "stxvh8x", (s, i) -> stxvElems(s, i, 2));
        x31(t, 1004, "stxvb16x", (s, i) -> stxvElems(s, i, 3));
        x31(t, 332, "lxvdsx", VsxInsns::lxvdsx);
        x31(t, 364, "lxvwsx", VsxInsns::lxvwsx);
        x31(t, 268, "lxvx", VsxInsns::lxvx);
        x31(t, 300, "lxvx", VsxInsns::lxvx);
        x31(t, 396, "stxvx", VsxInsns::stxvx);
        x31(t, 269, "lxvl", (s, i) -> lxvl(s, i, false));
        x31(t, 301, "lxvll", (s, i) -> lxvl(s, i, true));
        x31(t, 397, "stxvl", (s, i) -> stxvl(s, i, false));
        x31(t, 429, "stxvll", (s, i) -> stxvl(s, i, true));
        // ---- DS / DQ forms
        t.add(0xfc000003, (57 << 26) | 2, "lxsd", VsxInsns::lxsd);
        t.add(0xfc000003, (57 << 26) | 3, "lxssp", VsxInsns::lxssp);
        t.add(0xfc000003, (61 << 26) | 2, "stxsd", VsxInsns::stxsd);
        t.add(0xfc000003, (61 << 26) | 3, "stxssp", VsxInsns::stxssp);
        t.add(0xfc000007, (61 << 26) | 1, "lxv", VsxInsns::lxv);
        t.add(0xfc000007, (61 << 26) | 5, "stxv", VsxInsns::stxv);
    }

}
