package org.boxtheta.javapower.fpvec;

import static org.boxtheta.javapower.fpvec.Fpscr.*;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.InsnSet;
import org.boxtheta.javapower.core.Op;
import org.boxtheta.javapower.core.PpcException;

/**
 * ISA 3.0 binary128 ("quad-precision") instructions. Operands are VRs (VSR 32+n). Instructions
 * with an Rc bit use round-to-odd when it is set. FPSCR handling follows QEMU's helpers.
 */
public final class QpInsns implements InsnSet {
    public static final QpInsns INSTANCE = new QpInsns();

    private QpInsns() {}

    static final int Q = SoftFloat.Q;

    static int vt(int i) { return 32 + ((i >>> 21) & 31); }
    static int va(int i) { return 32 + ((i >>> 16) & 31); }
    static int vb(int i) { return 32 + ((i >>> 11) & 31); }

    static void finishQ(CpuState s, int t, SoftFloat sf, boolean setFpcc, boolean zx) {
        long hi = sf.qh, lo = sf.ql;
        int fl = sf.flags;
        if ((fl & SoftFloat.INVALID) != 0) pre(s, fl & SoftFloat.INVALID, setFpcc);
        if (zx && (fl & SoftFloat.ZX) != 0) zeroDivide(s);
        setFprf(s, classQ(hi, lo));
        V.set(s, t, hi, lo);
        post(s, fl, true);
    }

    static SoftFloat start(CpuState s, int insn, boolean ro) {
        V.checkVsx(s);
        SoftFloat sf = SoftFloat.get();
        sf.resetFp(s.fpscr);
        if (ro && (insn & 1) != 0) sf.mode = SoftFloat.RODD;
        return sf;
    }

    static void arith(CpuState s, int insn, int op) {
        SoftFloat sf = start(s, insn, true);
        int a = va(insn), b = vb(insn);
        sf.unpackQ(0, s.vsrHi[a], s.vsrLo[a]);
        sf.unpackQ(1, s.vsrHi[b], s.vsrLo[b]);
        switch (op) {
            case 0 -> sf.add(Q, false);
            case 1 -> sf.add(Q, true);
            case 2 -> sf.mul(Q);
            default -> sf.div(Q);
        }
        finishQ(s, vt(insn), sf, true, op == 3);
    }

    static void sqrt(CpuState s, int insn) {
        SoftFloat sf = start(s, insn, true);
        int b = vb(insn);
        sf.unpackQ(0, s.vsrHi[b], s.vsrLo[b]);
        sf.sqrt(Q);
        finishQ(s, vt(insn), sf, true, false);
    }

    /** VRT = VRA*VRB + VRT (QEMU orders NaN propagation as VRA, VRT, VRB). */
    static void madd(CpuState s, int insn, boolean negAdd, boolean negRes, boolean roAllowed) {
        SoftFloat sf = start(s, insn, roAllowed);
        int a = va(insn), b = vb(insn), t = vt(insn);
        sf.unpackQ(0, s.vsrHi[a], s.vsrLo[a]);
        sf.unpackQ(1, s.vsrHi[t], s.vsrLo[t]);
        sf.unpackQ(2, s.vsrHi[b], s.vsrLo[b]);
        sf.fma(Q, negAdd, negRes);
        finishQ(s, t, sf, false, false);
    }

    static void cmp(CpuState s, int insn, boolean ordered) {
        V.checkVsx(s);
        int a = va(insn), b = vb(insn);
        SoftFloat sf = SoftFloat.get();
        sf.unpackQ(0, s.vsrHi[a], s.vsrLo[a]);
        sf.unpackQ(1, s.vsrHi[b], s.vsrLo[b]);
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

    static boolean isNanQ(long hi, long lo) {
        return ((hi >>> 48) & 0x7fff) == 0x7fff && ((hi & 0xffffffffffffL) | lo) != 0;
    }

    static void cmpexp(CpuState s, int insn) {
        V.checkVsx(s);
        int a = va(insn), b = vb(insn);
        int cc;
        if (isNanQ(s.vsrHi[a], s.vsrLo[a]) || isNanQ(s.vsrHi[b], s.vsrLo[b])) {
            cc = 1;
        } else {
            long ea = (s.vsrHi[a] >>> 48) & 0x7fff, eb = (s.vsrHi[b] >>> 48) & 0x7fff;
            cc = ea < eb ? 8 : ea > eb ? 4 : 2;
        }
        s.fpscr = (s.fpscr & ~FPCC) | ((long) cc << 12);
        s.setCrField((insn >>> 23) & 7, cc);
    }

    static void tstdc(CpuState s, int insn) {
        V.checkVsx(s);
        int b = vb(insn);
        long hi = s.vsrHi[b], lo = s.vsrLo[b];
        int dcmx = (insn >>> 16) & 0x7f;
        boolean neg = hi < 0;
        long a = hi & Long.MAX_VALUE;
        int e = (int) (a >>> 48);
        boolean fracZero = (a & 0xffffffffffffL) == 0 && lo == 0;
        int sign = neg ? 1 : 0;
        boolean match;
        if (e == 0x7fff && !fracZero) match = ((dcmx >>> 6) & 1) != 0;
        else if (e == 0x7fff) match = ((dcmx >>> (5 - sign)) & 1) != 0;
        else if (e == 0 && fracZero) match = ((dcmx >>> (3 - sign)) & 1) != 0;
        else if (e == 0) match = ((dcmx >>> (1 - sign)) & 1) != 0;
        else match = false;
        int cc = (neg ? 8 : 0) | (match ? 2 : 0);
        s.fpscr = (s.fpscr & ~FPCC) | ((long) cc << 12);
        s.setCrField((insn >>> 23) & 7, cc);
    }

    // ------------------------------------------------------------------ conversions

    static void cvtdpqp(CpuState s, int insn) {
        SoftFloat sf = start(s, insn, false);
        long x = s.vsrHi[vb(insn)];
        sf.unpackD(0, x);
        sf.deliver(Q, 0);
        if (sf.isSnan(0)) vxsnan(s);
        setFprf(s, classQ(sf.qh, sf.ql));
        V.set(s, vt(insn), sf.qh, sf.ql);
        post(s, sf.flags, true);
    }

    static void cvtqpdp(CpuState s, int insn) {
        SoftFloat sf = start(s, insn, true);
        int b = vb(insn);
        sf.unpackQ(0, s.vsrHi[b], s.vsrLo[b]);
        long r = sf.deliver(SoftFloat.D, 0);
        if (sf.isSnan(0)) vxsnan(s);
        setFprf(s, classD(r));
        V.set(s, vt(insn), r, 0);
        post(s, sf.flags, true);
    }

    /** xscvqp[su][dw]z. */
    static void cvtqpi(CpuState s, int insn, int bits, boolean signed) {
        SoftFloat sf = start(s, insn, false);
        int b = vb(insn);
        sf.unpackQ(0, s.vsrHi[b], s.vsrLo[b]);
        long r = sf.toInt(bits, signed, SoftFloat.RZ);
        int fl = sf.flags;
        if ((fl & SoftFloat.VXCVI) != 0) {
            invalidCvt(s, (fl & SoftFloat.VXSNAN) != 0, false);
            if (sf.nanCvt) {
                r = !signed ? 0 : bits == 32 ? 0xffffffff80000000L : Long.MIN_VALUE;
            }
        }
        V.set(s, vt(insn), r, 0);
        post(s, fl, true);
    }

    static void cvtiqp(CpuState s, int insn, boolean signed) {
        SoftFloat sf = start(s, insn, false);
        sf.fromInt(Q, 0, s.vsrHi[vb(insn)], signed, false);
        setFprf(s, classQ(sf.qh, sf.ql));
        V.set(s, vt(insn), sf.qh, sf.ql);
        post(s, sf.flags, true);
    }

    /** Rounding mode of xsrqpi/xsrqpxp from R and RMC (QEMU's mapping, including its RN quirk). */
    static int rqMode(CpuState s, int insn) {
        int r = (insn >>> 16) & 1, rmc = (insn >>> 9) & 3;
        if (r == 0) {
            if (rmc == 0) return SoftFloat.RNA;
            if (rmc == 3) {
                // QEMU passes FPSCR[RN] through as a softfloat rounding-mode enum value.
                return switch ((int) s.fpscr & 3) {
                    case 0 -> SoftFloat.RN;
                    case 1 -> SoftFloat.RM;
                    case 2 -> SoftFloat.RP;
                    default -> SoftFloat.RZ;
                };
            }
            return SoftFloat.RN;
        }
        return switch (rmc) {
            case 0 -> SoftFloat.RN;
            case 1 -> SoftFloat.RZ;
            case 2 -> SoftFloat.RP;
            default -> SoftFloat.RM;
        };
    }

    static void rqpi(CpuState s, int insn) {
        SoftFloat sf = start(s, insn, false);
        int b = vb(insn);
        sf.unpackQ(0, s.vsrHi[b], s.vsrLo[b]);
        sf.roundToInt(Q, rqMode(s, insn));
        int fl = sf.flags;
        if ((fl & SoftFloat.VXSNAN) != 0) vxsnan(s);
        if ((insn & 1) == 0) fl &= ~SoftFloat.XX;
        setFprf(s, classQ(sf.qh, sf.ql));
        post(s, fl, true);
        V.set(s, vt(insn), sf.qh, sf.ql);
    }

    static void rqpxp(CpuState s, int insn) {
        SoftFloat sf = start(s, insn, false);
        sf.mode = rqMode(s, insn);
        int b = vb(insn);
        sf.unpackQ(0, s.vsrHi[b], s.vsrLo[b]);
        sf.deliver(SoftFloat.QX, 0);
        long hi = sf.qh, lo = sf.ql;
        if (sf.classOf(0) >= SoftFloat.QNAN) {
            lo &= ~((1L << 49) - 1); // the payload passed through the 64-bit floatx80 significand
            if (sf.isSnan(0)) vxsnan(s);
        }
        setFprf(s, classQ(hi, lo));
        V.set(s, vt(insn), hi, lo);
        post(s, sf.flags, true);
    }

    // ------------------------------------------------------------------ moves

    static void move(CpuState s, int insn, int kind) {
        V.checkVsx(s);
        int b = vb(insn);
        long hi = s.vsrHi[b], lo = s.vsrLo[b];
        switch (kind) {
            case 0 -> hi &= Long.MAX_VALUE;
            case 1 -> hi |= Long.MIN_VALUE;
            case 2 -> hi ^= Long.MIN_VALUE;
            case 3 -> hi = (s.vsrHi[va(insn)] & Long.MIN_VALUE) | (hi & Long.MAX_VALUE);
            case 4 -> { // xsxexpqp
                hi = (hi >>> 48) & 0x7fff;
                lo = 0;
            }
            case 5 -> { // xsxsigqp
                long e = (hi >>> 48) & 0x7fff;
                hi = (hi & 0xffffffffffffL) | (e == 0 || e == 0x7fff ? 0 : 1L << 48);
            }
            default -> { // xsiexpqp
                int a = va(insn);
                hi = (s.vsrHi[a] & 0x8000ffffffffffffL) | ((hi & 0x7fff) << 48);
                lo = s.vsrLo[a];
            }
        }
        V.set(s, vt(insn), hi, lo);
    }

    // ------------------------------------------------------------------ registration

    private static final int P63 = 63 << 26;

    /** X form with the Rc bit free (round-to-odd variants). */
    private static void xr(DecodeTable t, int xo, String name, Op op) {
        t.add(0xfc0007fe, P63 | (xo << 1), name, op);
    }

    /** X form with Rc = 0. */
    private static void x0(DecodeTable t, int xo, String name, Op op) {
        t.add(0xfc0007ff, P63 | (xo << 1), name, op);
    }

    /** X form with an opcode extension in bits 11:15. */
    private static void xeo(DecodeTable t, int xo, int eo, boolean rc, String name, Op op) {
        t.add(rc ? 0xfc1f07fe : 0xfc1f07ff, P63 | (eo << 16) | (xo << 1), name, op);
    }

    @Override
    public void register(DecodeTable t) {
        xr(t, 4, "xsaddqp", (s, i) -> arith(s, i, 0));
        xr(t, 516, "xssubqp", (s, i) -> arith(s, i, 1));
        xr(t, 36, "xsmulqp", (s, i) -> arith(s, i, 2));
        xr(t, 548, "xsdivqp", (s, i) -> arith(s, i, 3));
        xeo(t, 804, 27, true, "xssqrtqp", QpInsns::sqrt);
        xr(t, 388, "xsmaddqp", (s, i) -> madd(s, i, false, false, true));
        xr(t, 420, "xsmsubqp", (s, i) -> madd(s, i, true, false, true));
        xr(t, 452, "xsnmaddqp", (s, i) -> madd(s, i, false, true, true));
        // QEMU's xsnmsubqpo does not round to odd.
        xr(t, 484, "xsnmsubqp", (s, i) -> madd(s, i, true, true, false));
        t.add(0xfc6007ff, P63 | (132 << 1), "xscmpoqp", (s, i) -> cmp(s, i, true));
        t.add(0xfc6007ff, P63 | (644 << 1), "xscmpuqp", (s, i) -> cmp(s, i, false));
        t.add(0xfc6007ff, P63 | (164 << 1), "xscmpexpqp", QpInsns::cmpexp);
        x0(t, 708, "xststdcqp", QpInsns::tstdc);
        xeo(t, 804, 0, false, "xsabsqp", (s, i) -> move(s, i, 0));
        xeo(t, 804, 8, false, "xsnabsqp", (s, i) -> move(s, i, 1));
        xeo(t, 804, 16, false, "xsnegqp", (s, i) -> move(s, i, 2));
        x0(t, 100, "xscpsgnqp", (s, i) -> move(s, i, 3));
        xeo(t, 804, 2, false, "xsxexpqp", (s, i) -> move(s, i, 4));
        xeo(t, 804, 18, false, "xsxsigqp", (s, i) -> move(s, i, 5));
        x0(t, 868, "xsiexpqp", (s, i) -> move(s, i, 6));
        xeo(t, 836, 22, false, "xscvdpqp", QpInsns::cvtdpqp);
        xeo(t, 836, 20, true, "xscvqpdp", QpInsns::cvtqpdp);
        xeo(t, 836, 25, false, "xscvqpsdz", (s, i) -> cvtqpi(s, i, 64, true));
        xeo(t, 836, 9, false, "xscvqpswz", (s, i) -> cvtqpi(s, i, 32, true));
        xeo(t, 836, 17, false, "xscvqpudz", (s, i) -> cvtqpi(s, i, 64, false));
        xeo(t, 836, 1, false, "xscvqpuwz", (s, i) -> cvtqpi(s, i, 32, false));
        xeo(t, 836, 10, false, "xscvsdqp", (s, i) -> cvtiqp(s, i, true));
        xeo(t, 836, 2, false, "xscvudqp", (s, i) -> cvtiqp(s, i, false));
        t.add(0xfc0001fe, P63 | (5 << 1), "xsrqpi", QpInsns::rqpi);
        t.add(0xfc0001fe, P63 | (37 << 1), "xsrqpxp", QpInsns::rqpxp);
    }
}
