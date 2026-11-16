package org.boxtheta.javapower.fpvec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.Op;
import org.boxtheta.javapower.core.PpcException;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * Differential test of the {@link FastFp} paths: every instruction that has one runs on random
 * states with the fast paths on and off ({@link FastFp#on}); the VSRs, FPSCR, CR, VSCR and the
 * interrupt taken must be identical. Operands are drawn to hit the hard cases (subnormals,
 * zeros, infinities, NaNs with payloads, values near the overflow and underflow thresholds of
 * both precisions, exact and halfway results, cancellation, very different exponents), under
 * all rounding modes and random exception enables, FE0/FE1 and record bits.
 *
 * <p>{@code main} runs the same check at a larger scale (a soak):
 * {@code FastFpTest <cases per instruction> <seed> [name,... | k/n]}.
 */
class FastFpTest {
    static final long SEED = 0x6a36a8d7L;
    static final int CASES = 25_000;

    /** Operand kinds. */
    enum K {
        /** binary64 operands in FPRs / VSRs (scalar and binary64 vector forms). */
        D,
        /** binary32 elements in VSRs or VRs. */
        S,
        /** 64-bit integers (fcfid, xscv[su]xd*, xvcv[su]xd*). */
        INT,
        /** 32-bit integer elements (xvcv[su]xw*). */
        INT32,
        /** binary64 values for conversion to integer. */
        CVT,
        /** binary32 elements for conversion to integer. */
        CVTS
    }

    /** Register file: 0 FPR fields (RA/RB/RC/RT), 1 VSX fields, 2 VMX fields. */
    record Insn(String name, int word, K kind, int regs) {}

    static final List<Insn> INSNS = new ArrayList<>();

    static void fp(String name, int word, K k) { INSNS.add(new Insn(name, word, k, 0)); }
    static void vsx(String name, int word, K k) { INSNS.add(new Insn(name, word, k, 1)); }
    static void vmx(String name, int word, K k) { INSNS.add(new Insn(name, word, k, 2)); }

    static int xx3(int xo) { return (60 << 26) | (xo << 3); }
    static int xx2(int xo) { return (60 << 26) | (xo << 2); }

    static {
        String[] ar = {"add", "sub", "mul", "div"};
        int[] arXo = {21, 20, 25, 18};
        for (int p : new int[] {63, 59}) {
            String sfx = p == 63 ? "" : "s";
            for (int k = 0; k < 4; k++) fp("f" + ar[k] + sfx, (p << 26) | (arXo[k] << 1), K.D);
            fp("fsqrt" + sfx, (p << 26) | (22 << 1), K.D);
            fp("fre" + sfx, (p << 26) | (24 << 1), K.D);
            fp("frsqrte" + sfx, (p << 26) | (26 << 1), K.D);
            String[] fm = {"fmadd", "fmsub", "fnmadd", "fnmsub"};
            int[] fmXo = {29, 28, 31, 30};
            for (int k = 0; k < 4; k++) fp(fm[k] + sfx, (p << 26) | (fmXo[k] << 1), K.D);
        }
        fp("frsp", (63 << 26) | (12 << 1), K.D);
        String[] cti = {"fctiw", "fctiwz", "fctiwu", "fctiwuz", "fctid", "fctidz", "fctidu", "fctiduz"};
        int[] ctiXo = {14, 15, 142, 143, 814, 815, 942, 943};
        for (int k = 0; k < cti.length; k++) fp(cti[k], (63 << 26) | (ctiXo[k] << 1), K.CVT);
        fp("fcfid", (63 << 26) | (846 << 1), K.INT);
        fp("fcfidu", (63 << 26) | (974 << 1), K.INT);
        fp("fcfids", (59 << 26) | (846 << 1), K.INT);
        fp("fcfidus", (59 << 26) | (974 << 1), K.INT);
        fp("fcmpu", 63 << 26, K.D);
        fp("fcmpo", (63 << 26) | (32 << 1), K.D);

        for (int op = 0; op < 4; op++) {
            vsx("xs" + ar[op] + "dp", xx3(32 + 8 * op), K.D);
            vsx("xs" + ar[op] + "sp", xx3(8 * op), K.D);
            vsx("xv" + ar[op] + "dp", xx3(96 + 8 * op), K.D);
            vsx("xv" + ar[op] + "sp", xx3(64 + 8 * op), K.S);
        }
        vsx("xssqrtdp", xx2(75), K.D);
        vsx("xssqrtsp", xx2(11), K.D);
        vsx("xvsqrtdp", xx2(203), K.D);
        vsx("xvsqrtsp", xx2(139), K.S);
        vsx("xsredp", xx2(90), K.D);
        vsx("xsresp", xx2(26), K.D);
        vsx("xvredp", xx2(218), K.D);
        vsx("xvresp", xx2(154), K.S);
        String[] mn = {"madd", "msub", "nmadd", "nmsub"};
        int[] xsdp = {33, 49, 161, 177}, xssp = {1, 17, 129, 145}, xvdp = {97, 113, 225, 241}, xvsp = {65, 81, 193, 209};
        for (int k = 0; k < 4; k++) {
            for (int m = 0; m < 2; m++) {
                String f = m == 1 ? "m" : "a";
                vsx("xs" + mn[k] + f + "dp", xx3(xsdp[k] + 8 * m), K.D);
                vsx("xs" + mn[k] + f + "sp", xx3(xssp[k] + 8 * m), K.D);
                vsx("xv" + mn[k] + f + "dp", xx3(xvdp[k] + 8 * m), K.D);
                vsx("xv" + mn[k] + f + "sp", xx3(xvsp[k] + 8 * m), K.S);
            }
        }
        vsx("xscmpeqdp", xx3(3), K.D);
        vsx("xscmpgedp", xx3(19), K.D);
        vsx("xscmpgtdp", xx3(11), K.D);
        vsx("xscmpodp", xx3(43), K.D);
        vsx("xscmpudp", xx3(35), K.D);
        vsx("xsrsp", xx2(281), K.D);
        String[] fi = {"xscvdpsxds", "xscvdpuxds", "xscvdpsxws", "xscvdpuxws", "xvcvdpsxds", "xvcvdpuxds", "xvcvdpsxws", "xvcvdpuxws"};
        int[] fiXo = {344, 328, 88, 72, 472, 456, 216, 200};
        for (int k = 0; k < fi.length; k++) vsx(fi[k], xx2(fiXo[k]), K.CVT);
        String[] fis = {"xvcvspsxds", "xvcvspuxds", "xvcvspsxws", "xvcvspuxws"};
        int[] fisXo = {408, 392, 152, 136};
        for (int k = 0; k < fis.length; k++) vsx(fis[k], xx2(fisXo[k]), K.CVTS);
        String[] ifn = {"xscvsxddp", "xscvuxddp", "xscvsxdsp", "xscvuxdsp", "xvcvsxddp", "xvcvuxddp", "xvcvsxdsp", "xvcvuxdsp"};
        int[] ifXo = {376, 360, 312, 296, 504, 488, 440, 424};
        for (int k = 0; k < ifn.length; k++) vsx(ifn[k], xx2(ifXo[k]), K.INT);
        String[] if32 = {"xvcvsxwdp", "xvcvuxwdp", "xvcvsxwsp", "xvcvuxwsp"};
        int[] if32Xo = {248, 232, 184, 168};
        for (int k = 0; k < if32.length; k++) vsx(if32[k], xx2(if32Xo[k]), K.INT32);

        vmx("vaddfp", (4 << 26) | 10, K.S);
        vmx("vsubfp", (4 << 26) | 74, K.S);
        vmx("vmaxfp", (4 << 26) | 1034, K.S);
        vmx("vminfp", (4 << 26) | 1098, K.S);
        vmx("vmaddfp", (4 << 26) | 46, K.S);
        vmx("vnmsubfp", (4 << 26) | 47, K.S);
    }

    static final DecodeTable T = GoldenTest.table();

    // ------------------------------------------------------------------ operand generators

    SplittableRandom r = new SplittableRandom(SEED);

    static FastFpTest seeded(long seed) {
        FastFpTest t = new FastFpTest();
        t.r = new SplittableRandom(seed);
        return t;
    }

    long genD() {
        long sign = r.nextBoolean() ? Long.MIN_VALUE : 0;
        long frac = r.nextLong() & 0xfffffffffffffL;
        switch (r.nextInt(18)) {
            case 0: return r.nextLong();
            case 1: case 2: case 3: return sign | ((long) (1023 + r.nextInt(-8, 9)) << 52) | frac;
            case 4: return sign | ((long) r.nextInt(1, 2047) << 52) | frac;
            case 5: return sign | (frac >>> r.nextInt(0, 53)); // subnormal or zero
            case 6: return sign;
            case 7: return sign | 0x7ff0000000000000L;
            case 8: return sign | 0x7ff8000000000000L | (frac >>> r.nextInt(1, 53)); // QNaN with payload
            case 9: return sign | 0x7ff0000000000000L | Math.max(1, (frac & 0x7ffffffffffffL) >>> r.nextInt(0, 51)); // SNaN
            case 10: { // around the binary64 and binary32 limits
                long[] base = {0x0010000000000000L, 0x7fefffffffffffffL, 0x3810000000000000L, 0x47efffffe0000000L,
                    0x3800000000000000L, 0x3690000000000000L, 0x47f0000000000000L, 0x3ff0000000000000L};
                return sign | (base[r.nextInt(base.length)] + r.nextInt(-3, 4));
            }
            case 11: { // small integers and halves
                double d = r.nextInt(-1000, 1000) / (r.nextBoolean() ? 1.0 : 2.0);
                return Double.doubleToRawLongBits(d);
            }
            case 12: case 13: // binary32-representable
                return Fpscr.toDouble(r.nextInt(4) == 0 ? r.nextInt() : genS());
            case 14: { // a binary32 value plus half a binary32 ulp (a midpoint), give or take a binary64 ulp
                long v = Fpscr.toDouble(normS()) | (1L << 28);
                return v + r.nextInt(-1, 2);
            }
            case 15: { // an exact square (for sqrt), possibly scaled by a power of four
                long k = r.nextLong(1, 1L << 26);
                double d = Math.scalb((double) k * k, 2 * r.nextInt(-200, 200));
                return Double.doubleToRawLongBits(d) + (r.nextInt(4) == 0 ? r.nextInt(-1, 2) : 0);
            }
            default: { // exponents near the binary64 or binary32 limits
                int e = switch (r.nextInt(4)) {
                    case 0 -> r.nextInt(1, 130);
                    case 1 -> r.nextInt(1950, 2047);
                    case 2 -> 897 + r.nextInt(-60, 30);
                    default -> 1150 + r.nextInt(-30, 30);
                };
                return sign | ((long) e << 52) | (r.nextBoolean() ? frac : frac & ~0x1fffffffL);
            }
        }
    }

    /** A value related to a: cancellation, ulp-level differences, thresholds for its product or quotient. */
    long relatedD(long a) {
        int ea = (int) (a >>> 52) & 0x7ff;
        long sign = r.nextBoolean() ? Long.MIN_VALUE : 0;
        long frac = r.nextLong() & 0xfffffffffffffL;
        return switch (r.nextInt(11)) {
            case 0 -> a ^ Long.MIN_VALUE;
            case 1 -> (a ^ Long.MIN_VALUE) + r.nextInt(-4, 5);
            case 2 -> a + r.nextInt(-4, 5);
            case 3, 4 -> { // half a binary64 or binary32 ulp of a, give or take
                int e = ea - (r.nextBoolean() ? 53 : 24) + r.nextInt(-1, 2);
                long f = switch (r.nextInt(3)) {
                    case 0 -> 0;
                    case 1 -> r.nextInt(1, 4);
                    default -> frac;
                };
                yield e > 0 && e < 2047 ? sign | ((long) e << 52) | f : genD();
            }
            case 5 -> { // product or quotient near 2^-1022
                int e = Math.floorMod(1023 + (-1022 - (ea - 1023)) + r.nextInt(-2, 3), 2047);
                yield sign | ((long) e << 52) | frac;
            }
            case 6 -> { // near 2^-126 / 2^-125
                int e = Math.floorMod(1023 + (-126 - (ea - 1023)) + r.nextInt(-2, 3), 2047);
                yield sign | ((long) e << 52) | (r.nextBoolean() ? frac : frac & ~0x1fffffffL);
            }
            case 7 -> { // near 2^1024 / 2^128
                int target = r.nextBoolean() ? 1023 : 127;
                int e = Math.floorMod(1023 + (target - (ea - 1023)) + r.nextInt(-2, 2), 2047);
                yield sign | ((long) e << 52) | frac;
            }
            case 8 -> { // the binary32 rounding of a, or a nearby binary32 value
                yield (a & ~0x1fffffffL) + ((long) r.nextInt(-2, 3) << 29);
            }
            default -> genD();
        };
    }

    /** -(x*y), give or take a few ulps: massive cancellation in x*y + z. */
    long cancelD(long x, long y) {
        double p = Double.longBitsToDouble(x) * Double.longBitsToDouble(y);
        if (!Double.isFinite(p) || p == 0) return genD();
        return (Double.doubleToRawLongBits(-p) + (r.nextInt(3) == 0 ? r.nextInt(-3, 4) : 0)) & (r.nextInt(8) == 0 ? ~0x1fffffffL : -1L);
    }

    int normS() {
        return (r.nextBoolean() ? Integer.MIN_VALUE : 0) | (r.nextInt(1, 255) << 23) | (r.nextInt() & 0x7fffff);
    }

    /**
     * Mostly normal binary32 elements for this case, so that all four elements of a vector often
     * qualify for the fast path (which needs every element to).
     */
    boolean tame;

    int genS() {
        int sign = r.nextBoolean() ? Integer.MIN_VALUE : 0;
        int frac = r.nextInt() & 0x7fffff;
        if (tame && r.nextInt(6) != 0) {
            sign = r.nextInt(4) == 0 ? Integer.MIN_VALUE : 0; // mostly positive: sqrt
            return sign | ((r.nextInt(8) == 0 ? r.nextInt(1, 255) : 127 + r.nextInt(-20, 21)) << 23) | frac;
        }
        return switch (r.nextInt(11)) {
            case 0 -> r.nextInt();
            case 1, 2, 3 -> sign | ((127 + r.nextInt(-10, 11)) << 23) | frac;
            case 4 -> sign | (frac >>> r.nextInt(24)); // subnormal or zero
            case 5 -> sign;
            case 6 -> sign | 0x7f800000;
            case 7 -> sign | 0x7fc00000 | (frac >>> r.nextInt(1, 23)); // QNaN
            case 8 -> sign | 0x7f800000 | Math.max(1, (frac & 0x3fffff) >>> r.nextInt(22)); // SNaN
            case 9 -> sign | ((r.nextBoolean() ? r.nextInt(1, 30) : r.nextInt(220, 255)) << 23) | frac;
            default -> Float.floatToRawIntBits(r.nextInt(-300, 300) / (r.nextBoolean() ? 1f : 4f));
        };
    }

    int relatedS(int a) {
        int ea = (a >>> 23) & 0xff;
        int sign = r.nextBoolean() ? Integer.MIN_VALUE : 0;
        return switch (r.nextInt(tame ? 9 : 7)) {
            case 0 -> a ^ Integer.MIN_VALUE;
            case 1 -> (a ^ Integer.MIN_VALUE) + r.nextInt(-3, 4);
            case 2 -> a + r.nextInt(-3, 4);
            case 3 -> { // half an ulp of a, give or take
                int e = ea - 24 + r.nextInt(-1, 2);
                yield e > 0 && e < 255 ? sign | (e << 23) | (r.nextBoolean() ? 0 : r.nextInt(1, 4)) : genS();
            }
            case 4 -> { // product near 2^-126, or overflow
                int target = r.nextBoolean() ? -126 : 127;
                int e = Math.floorMod(127 + (target - (ea - 127)) + r.nextInt(-2, 3), 255);
                yield sign | (e << 23) | (r.nextInt() & 0x7fffff);
            }
            default -> genS();
        };
    }

    int cancelS(int x, int y) {
        float p = Float.intBitsToFloat(x) * Float.intBitsToFloat(y);
        if (!Float.isFinite(p) || p == 0) return genS();
        return Float.floatToRawIntBits(-p) + (r.nextBoolean() ? r.nextInt(-2, 3) : 0);
    }

    long genInt() {
        return switch (r.nextInt(9)) {
            case 0 -> r.nextLong();
            case 1 -> r.nextInt();
            case 2 -> r.nextInt(-100000, 100000);
            case 3 -> (1L << r.nextInt(20, 63)) + r.nextInt(-3, 4);
            case 4 -> -(1L << r.nextInt(20, 64)) + r.nextInt(-3, 4);
            case 5 -> { // 25 or 54 significant bits ending in 1: exact halfway cases
                int bits = r.nextBoolean() ? 25 : 54;
                long m = (r.nextLong() >>> (64 - bits)) | (1L << (bits - 1)) | 1;
                yield (m << r.nextInt(0, 64 - bits)) * (r.nextBoolean() ? 1 : -1);
            }
            case 6 -> Long.MAX_VALUE - r.nextInt(0, 2000);
            case 7 -> Long.MIN_VALUE + r.nextInt(0, 2000);
            default -> r.nextLong() >> r.nextInt(64);
        };
    }

    long genCvt() {
        switch (r.nextInt(6)) {
            case 0: return genD();
            case 1: {
                double lim = switch (r.nextInt(4)) {
                    case 0 -> 2147483648.0;
                    case 1 -> 4294967296.0;
                    case 2 -> 9.223372036854775807E18;
                    default -> 1.8446744073709552E19;
                };
                double d = (r.nextBoolean() ? lim : -lim) + r.nextInt(-3, 4) * (lim > 1e10 ? 1024.0 : 0.25);
                return Double.doubleToRawLongBits(d) + (r.nextInt(3) == 0 ? r.nextInt(-2, 3) : 0);
            }
            case 2: return Double.doubleToRawLongBits(r.nextInt(-100000, 100000) + 0.5);
            case 3: return Double.doubleToRawLongBits((r.nextDouble() - 0.5) * Math.scalb(1.0, r.nextInt(0, 70)));
            case 4: return Double.doubleToRawLongBits(r.nextInt(-3, 4) * 0.5 + (r.nextBoolean() ? 0 : Math.ulp(0.5) * r.nextInt(-2, 3)));
            default: return Double.doubleToRawLongBits(r.nextInt(-1000, 1000) + r.nextInt(4) * 0.25);
        }
    }

    int genCvtS() {
        long d = genCvt();
        return r.nextInt(4) == 0 ? genS() : Float.floatToRawIntBits((float) Double.longBitsToDouble(d));
    }

    // ------------------------------------------------------------------ states

    /** The operand registers of {@code insn}: a, b, c and t (VSR numbers). */
    static int[] operands(Insn in, int insn) {
        return switch (in.regs) {
            case 0 -> new int[] {(insn >>> 16) & 31, (insn >>> 11) & 31, (insn >>> 6) & 31, (insn >>> 21) & 31};
            case 1 -> new int[] {VsxInsns.xa(insn), VsxInsns.xb(insn), VsxInsns.xc(insn), VsxInsns.xt(insn)};
            default -> new int[] {32 + ((insn >>> 16) & 31), 32 + ((insn >>> 11) & 31), 32 + ((insn >>> 6) & 31), 32 + ((insn >>> 21) & 31)};
        };
    }

    long word(K k) {
        return switch (k) {
            case D -> genD();
            case S -> ((long) genS() << 32) | (genS() & 0xffffffffL);
            case INT -> genInt();
            case INT32 -> ((long) (int) genInt() << 32) | ((int) genInt() & 0xffffffffL);
            case CVT -> genCvt();
            case CVTS -> ((long) genCvtS() << 32) | (genCvtS() & 0xffffffffL);
        };
    }

    long related(K k, long a) {
        if (k == K.D) return relatedD(a);
        if (k == K.S) return ((long) relatedS((int) (a >>> 32)) << 32) | (relatedS((int) a) & 0xffffffffL);
        return word(k);
    }

    long cancel(K k, long x, long y) {
        if (k == K.D) return cancelD(x, y);
        if (k == K.S) {
            return ((long) cancelS((int) (x >>> 32), (int) (y >>> 32)) << 32) | (cancelS((int) x, (int) y) & 0xffffffffL);
        }
        return word(k);
    }

    CpuState state(Insn in, int insn) {
        CpuState s = new CpuState();
        for (int i = 0; i < 64; i++) {
            s.vsrHi[i] = 0x5555000000000000L + i;
            s.vsrLo[i] = 0x6666000000000000L + i;
        }
        int[] ops = operands(in, insn);
        tame = r.nextInt(3) != 0;
        long[][] v = new long[4][2];
        for (int k = 0; k < 4; k++) {
            for (int h = 0; h < 2; h++) {
                v[k][h] = word(in.kind);
                if (k > 0 && r.nextInt(3) == 0) v[k][h] = related(in.kind, v[r.nextInt(k)][h]);
            }
        }
        if (r.nextInt(3) == 0) { // cancellation between one operand and the product of two others
            int z = r.nextInt(4), x = (z + 1 + r.nextInt(3)) & 3, y = (x + 1 + r.nextInt(3)) & 3;
            if (y == z) y = (y + 1) & 3;
            for (int h = 0; h < 2; h++) v[z][h] = cancel(in.kind, v[x][h], v[y][h]);
        }
        for (int k = 3; k >= 0; k--) { // a register named twice gets the first operand's value
            s.vsrHi[ops[k]] = v[k][0];
            s.vsrLo[ops[k]] = v[k][1];
        }
        s.msr = Msr.SF | Msr.FP | Msr.VEC | Msr.VSX | (r.nextInt(4) == 0 ? Msr.FE0 | Msr.FE1 : 0);
        long f = r.nextInt(4); // RN
        for (long en : new long[] {Fpscr.VE, Fpscr.OE, Fpscr.UE, Fpscr.ZE, Fpscr.XE, Fpscr.NI}) {
            if (r.nextInt(4) == 0) f |= en;
        }
        if (r.nextInt(3) == 0) {
            f |= r.nextLong() & (Fpscr.FX | Fpscr.OX | Fpscr.UX | Fpscr.ZX | Fpscr.XX | Fpscr.VX_CAUSES | Fpscr.FI
                    | Fpscr.FR | Fpscr.FPRF);
        }
        Fpscr.store(s, f);
        s.cr = r.nextInt();
        s.vscr = r.nextInt() & 0x10001;
        return s;
    }

    static CpuState copy(CpuState a) {
        CpuState b = new CpuState();
        System.arraycopy(a.vsrHi, 0, b.vsrHi, 0, 64);
        System.arraycopy(a.vsrLo, 0, b.vsrLo, 0, 64);
        b.msr = a.msr;
        b.fpscr = a.fpscr;
        b.cr = a.cr;
        b.vscr = a.vscr;
        return b;
    }

    static String run(Op op, CpuState s, int insn, boolean fast) {
        boolean was = FastFp.on;
        FastFp.on = fast;
        try {
            op.exec(s, insn);
            return "ok";
        } catch (PpcException e) {
            return "interrupt " + Integer.toHexString(e.vector) + " " + Long.toHexString(e.srr1);
        } finally {
            FastFp.on = was;
        }
    }

    static String diff(CpuState a, CpuState b) {
        StringBuilder sb = new StringBuilder();
        if (a.fpscr != b.fpscr) sb.append(String.format(" fpscr soft=%x fast=%x", a.fpscr, b.fpscr));
        if (a.cr != b.cr) sb.append(String.format(" cr soft=%08x fast=%08x", a.cr, b.cr));
        if (a.vscr != b.vscr) sb.append(String.format(" vscr soft=%x fast=%x", a.vscr, b.vscr));
        for (int i = 0; i < 64; i++) {
            if (a.vsrHi[i] != b.vsrHi[i] || a.vsrLo[i] != b.vsrLo[i]) {
                sb.append(String.format(" vsr%d soft=%016x:%016x fast=%016x:%016x", i, a.vsrHi[i], a.vsrLo[i], b.vsrHi[i], b.vsrLo[i]));
            }
        }
        return sb.toString();
    }

    static String describe(Insn in, int insn, CpuState s) {
        StringBuilder sb = new StringBuilder(String.format("%s %08x fpscr=%x fe=%d vscr=%x", in.name, insn, s.fpscr,
                (s.msr & (Msr.FE0 | Msr.FE1)) != 0 ? 1 : 0, s.vscr));
        for (int k : operands(in, insn)) sb.append(String.format(" vsr%d=%016x:%016x", k, s.vsrHi[k], s.vsrLo[k]));
        return sb.toString();
    }

    /** Run {@code cases} random cases of {@code in}; returns the mismatch count, examples in {@code out}. */
    long check(Insn in, long cases, StringBuilder out) {
        DecodeTable.Entry e = T.lookup(in.word);
        assertEquals(in.name, e == null ? null : e.name(), "decode of " + in.name);
        long bad = 0;
        for (long c = 0; c < cases; c++) {
            int insn = e.match() | (r.nextInt() & ~e.mask());
            if (T.lookup(insn) != e) {
                continue;
            }
            CpuState soft = state(in, insn);
            CpuState input = copy(soft), fast = copy(soft);
            String rs = run(e.op(), soft, insn, false), rf = run(e.op(), fast, insn, true);
            String d = (rs.equals(rf) ? "" : " soft: " + rs + " fast: " + rf) + diff(soft, fast);
            if (!d.isEmpty()) {
                if (bad < 5) out.append("\n  ").append(describe(in, insn, input)).append("\n    =>").append(d);
                bad++;
            }
        }
        return bad;
    }

    @TestFactory
    Stream<DynamicTest> fastPathsMatchSoftFloat() {
        return INSNS.stream().map(in -> DynamicTest.dynamicTest(in.name, () -> {
            StringBuilder sb = new StringBuilder();
            long bad = seeded(SEED ^ in.name.hashCode()).check(in, CASES, sb);
            if (bad > 0) fail(bad + "/" + CASES + " mismatches" + sb);
        }));
    }

    // ------------------------------------------------------------------ host independence

    /** The Dekker variants used without a hardware FMA give the same error signs as Math.fma. */
    @Test
    void splitErrorsMatchFma() {
        int n = 0;
        for (int i = 0; i < 400_000; i++) {
            long a = genD(), b0 = r.nextInt(3) == 0 ? relatedD(a) : genD();
            long b = r.nextInt(4) == 0 ? b0 & ~0xffffffL : b0; // short significands: more exact products
            double x = Double.longBitsToDouble(a), y = Double.longBitsToDouble(b);
            long p = FastFp.mul(a, b);
            if (p != FastFp.MISS) {
                double ref = Math.signum(Math.fma(x, y, -Double.longBitsToDouble(p)));
                double split = FastFp.mulErrSplit(x, y, Double.longBitsToDouble(p));
                if (!Double.isNaN(split)) {
                    assertEquals(ref, Math.signum(split), () -> String.format("mul %016x %016x", a, b));
                    n++;
                }
            }
            long q = FastFp.div(a, b);
            if (q != FastFp.MISS) {
                double rq = Double.longBitsToDouble(q);
                double ref = Math.signum(Math.fma(-rq, y, x));
                double split = FastFp.divResidualSplit(x, y, rq);
                if (!Double.isNaN(split)) {
                    assertEquals(ref, Math.signum(split), () -> String.format("div %016x %016x", a, b));
                    n++;
                }
            }
            long rt = FastFp.sqrt(a);
            if (rt != FastFp.MISS) {
                double rr = Double.longBitsToDouble(rt);
                double split = FastFp.sqrtErrSplit(x, rr);
                if (!Double.isNaN(split)) {
                    assertEquals(Math.signum(Math.fma(-rr, rr, x)), Math.signum(split), () -> String.format("sqrt %016x", a));
                    n++;
                }
            }
        }
        assertTrue(n > 300_000, "only " + n + " cases");
    }

    /** With an exact product, a*c + b and TwoSum agree with Math.fma and ErrFma. */
    @Test
    void exactProductFmaMatchesErrFma() {
        int n = 0;
        for (int i = 0; i < 400_000; i++) {
            long a = genD() & ~0x7ffffffL, c = genD() & ~0x7ffffffL; // 26-bit significands
            long b = r.nextInt(3) == 0 ? cancelD(a, c) : r.nextBoolean() ? relatedD(a) : genD();
            assertTrue(!FastFp.normal(a) || !FastFp.normal(c) || FastFp.exactProduct(a, c));
            long rn = FastFp.fma(a, c, b);
            if (rn == FastFp.MISS) continue;
            double x = Double.longBitsToDouble(a), y = Double.longBitsToDouble(c), z = Double.longBitsToDouble(b);
            assertEquals(Double.doubleToRawLongBits(Math.fma(x, y, z)), rn);
            assertEquals(Math.signum(FastFp.errFma(x, y, z, Double.longBitsToDouble(rn))), Math.signum(FastFp.fmaErr(a, c, b, rn)),
                    () -> String.format("fma %016x %016x %016x", a, c, b));
            n++;
        }
        assertTrue(n > 100_000, "only " + n + " cases");
    }

    /** The fast paths are taken for ordinary operands (so the differential test tests them). */
    @Test
    void fastPathsApply() {
        long one = 0x3ff0000000000000L, third = Double.doubleToRawLongBits(1.0 / 3);
        for (int mode = 0; mode < 4; mode++) {
            for (int op = 0; op < 4; op++) {
                long rn = FastFp.rn2(op, third, one + 12345);
                assertNotEquals(FastFp.MISS, FastFp.roundD(rn, FastFp.err2(op, third, one + 12345, rn), mode));
                assertNotEquals(FastFp.MISS, FastFp.roundS(rn, FastFp.err2(op, third, one + 12345, rn), mode));
                assertTrue(FastFp.arithS(op, 0x3eaaaaab, 0x3f800001, mode) >= 0);
            }
            long rn = FastFp.fma(third, third, one);
            assertNotEquals(FastFp.MISS, rn);
            assertNotEquals(FastFp.MISS, FastFp.roundD(rn, FastFp.fmaErr(third, third, one, rn), mode));
        }
    }

    // ------------------------------------------------------------------ soak

    public static void main(String[] args) {
        long cases = Long.parseLong(args[0]);
        long seed = Long.parseLong(args[1]);
        // optional third argument: instruction names (a,b,...) or a share of the list (k/n)
        String sel = args.length > 2 ? args[2] : null;
        List<String> only = sel != null && !sel.matches("\\d+/\\d+") ? List.of(sel.split(",")) : null;
        int part = sel != null && only == null ? Integer.parseInt(sel.split("/")[0]) : 0;
        int parts = sel != null && only == null ? Integer.parseInt(sel.split("/")[1]) : 1;
        int index = 0;
        long total = 0, totalBad = 0;
        System.out.println("hostFma=" + FastFp.HOST_FMA + " java=" + System.getProperty("java.version") + " arch="
                + System.getProperty("os.arch"));
        for (Insn in : INSNS) {
            if ((only != null && !only.contains(in.name)) || index++ % parts != part) continue;
            long t0 = System.nanoTime();
            StringBuilder sb = new StringBuilder();
            long bad = seeded(seed ^ in.name.hashCode()).check(in, cases, sb);
            System.out.printf("%-12s cases %d mismatches %d (%.1f s)%s%n", in.name, cases, bad, (System.nanoTime() - t0) / 1e9, sb);
            total += cases;
            totalBad += bad;
        }
        System.out.println("TOTAL cases " + total + " mismatches " + totalBad);
    }
}
