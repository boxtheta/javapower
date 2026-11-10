package org.boxtheta.javapower.fpvec;

import static org.boxtheta.javapower.fpvec.V.*;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.InsnSet;
import org.boxtheta.javapower.core.Op;

/**
 * VMX (Altivec) instructions of ISA 2.07 and 3.0 (POWER9): integer, logical, permute, pack and
 * unpack, compares, floating point (VSCR[NJ] flush-to-zero, round-to-nearest, no exceptions),
 * crypto, and the VMX loads/stores. VR n is VSR 32+n.
 *
 * <p>Semantics follow QEMU's {@code target/ppc} (verified by the golden vectors), including its
 * choices where the ISA leaves results undefined.
 */
public final class VmxInsns implements InsnSet {
    public static final VmxInsns INSTANCE = new VmxInsns();

    private VmxInsns() {}

    @FunctionalInterface interface L1 { long f(long b); }
    @FunctionalInterface interface L2 { long f(long a, long b); }
    @FunctionalInterface interface L3 { long f(long a, long b, long c); }

    static final int VSCR_SAT = 1;

    static void setSat(CpuState s) {
        s.vscr |= VSCR_SAT;
    }

    // ------------------------------------------------------------------ element-wise helpers

    /** r[i] = f(a[i], b[i]) on elements of {@code size} bytes, signed or unsigned extraction. */
    static void map2(CpuState s, int insn, int size, boolean sgn, L2 f) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], bh = s.vsrHi[b], bl = s.vsrLo[b];
        if (size == 8) {
            set(s, 32 + rt(insn), f.f(ah, bh), f.f(al, bl));
            return;
        }
        long m = mask(size);
        long rh = 0, rl = 0;
        int n = 16 / size, half = n / 2;
        for (int i = 0; i < n; i++) {
            long x = sgn ? gets(ah, al, size, i) : get(ah, al, size, i);
            long y = sgn ? gets(bh, bl, size, i) : get(bh, bl, size, i);
            long r = (f.f(x, y) & m) << shift(size, i);
            if (i < half) rh |= r; else rl |= r;
        }
        set(s, 32 + rt(insn), rh, rl);
    }

    /** r[i] = f(b[i]). */
    static void map1(CpuState s, int insn, int size, boolean sgn, L1 f) {
        checkVec(s);
        int b = 32 + rb(insn);
        long bh = s.vsrHi[b], bl = s.vsrLo[b];
        if (size == 8) {
            set(s, 32 + rt(insn), f.f(bh), f.f(bl));
            return;
        }
        long m = mask(size);
        long rh = 0, rl = 0;
        int n = 16 / size, half = n / 2;
        for (int i = 0; i < n; i++) {
            long y = sgn ? gets(bh, bl, size, i) : get(bh, bl, size, i);
            long r = (f.f(y) & m) << shift(size, i);
            if (i < half) rh |= r; else rl |= r;
        }
        set(s, 32 + rt(insn), rh, rl);
    }

    /** r[i] = f(a[i], b[i], c[i]) (VA form). */
    static void map3(CpuState s, int insn, int size, boolean sgn, L3 f) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn), c = 32 + rc(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], bh = s.vsrHi[b], bl = s.vsrLo[b];
        long ch = s.vsrHi[c], cl = s.vsrLo[c];
        long m = mask(size);
        long rh = 0, rl = 0;
        int n = 16 / size, half = n / 2;
        for (int i = 0; i < n; i++) {
            long x = sgn ? gets(ah, al, size, i) : get(ah, al, size, i);
            long y = sgn ? gets(bh, bl, size, i) : get(bh, bl, size, i);
            long z = sgn ? gets(ch, cl, size, i) : get(ch, cl, size, i);
            long r = (f.f(x, y, z) & m) << shift(size, i);
            if (i < half) rh |= r; else rl |= r;
        }
        set(s, 32 + rt(insn), rh, rl);
    }

    static long clampS(long v, int size, boolean[] sat) {
        long max = (1L << (8 * size - 1)) - 1, min = -max - 1;
        if (v > max) { sat[0] = true; return max; }
        if (v < min) { sat[0] = true; return min; }
        return v;
    }

    static long clampU(long v, int size, boolean[] sat) {
        long max = (1L << (8 * size)) - 1;
        if (v < 0) { sat[0] = true; return 0; }
        if (v > max) { sat[0] = true; return max; }
        return v;
    }

    /** Saturating element op: f gives the exact result, clamped to the signed/unsigned range. */
    static void map2sat(CpuState s, int insn, int size, boolean sgnIn, boolean sgnOut, L2 f) {
        boolean[] sat = new boolean[1];
        map2(s, insn, size, sgnIn, (x, y) -> sgnOut ? clampS(f.f(x, y), size, sat) : clampU(f.f(x, y), size, sat));
        if (sat[0]) setSat(s);
    }

    // ------------------------------------------------------------------ 128-bit helpers

    static void quad(CpuState s, int insn, Quad op) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn), c = 32 + rc(insn);
        op.f(s, s.vsrHi[a], s.vsrLo[a], s.vsrHi[b], s.vsrLo[b], s.vsrLo[c] & 1, 32 + rt(insn));
    }

    @FunctionalInterface interface Quad {
        void f(CpuState s, long ah, long al, long bh, long bl, long cin, int t);
    }

    /** (ah:al) + (bh:bl) + cin; result to t (or the carry out when {@code carryOut}). */
    static void add128(CpuState s, long ah, long al, long bh, long bl, long cin, int t, boolean carryOut) {
        long lo = al + bl;
        long c1 = Long.compareUnsigned(lo, al) < 0 ? 1 : 0;
        long lo2 = lo + cin;
        long c2 = Long.compareUnsigned(lo2, lo) < 0 ? 1 : 0;
        long hi = ah + bh;
        long c3 = Long.compareUnsigned(hi, ah) < 0 ? 1 : 0;
        long hi2 = hi + c1 + c2;
        long c4 = Long.compareUnsigned(hi2, hi) < 0 ? 1 : 0;
        if (carryOut) {
            set(s, t, 0, c3 | c4);
        } else {
            set(s, t, hi2, lo2);
        }
    }

    // ------------------------------------------------------------------ bit tricks

    /** Carry-less multiply of two 64-bit values; returns the high half, low half in {@link #clLo}. */
    static long clmulHi(long a, long b, long[] lo) {
        long h = 0, l = 0;
        for (int i = 0; i < 64; i++) {
            if (((b >>> i) & 1) != 0) {
                l ^= a << i;
                if (i != 0) h ^= a >>> (64 - i);
            }
        }
        lo[0] = l;
        return h;
    }

    static long clmulLow(long a, long b) {
        long l = 0;
        for (int i = 0; i < 64; i++) {
            if (((b >>> i) & 1) != 0) l ^= a << i;
        }
        return l;
    }

    static int mask32(int start, int end) {
        int ret;
        if (start == 0) {
            ret = 0xffffffff << ((31 - end) & 31);
        } else if (end == 31) {
            ret = 0xffffffff >>> (start & 31);
        } else {
            ret = (0xffffffff >>> (start & 31)) ^ ((0xffffffff >>> (end & 31)) >>> 1);
            if (start > end) return ~ret;
        }
        return ret;
    }

    static long mask64(int start, int end) {
        long ret;
        if (start == 0) {
            ret = -1L << (63 - end);
        } else if (end == 63) {
            ret = -1L >>> start;
        } else {
            ret = (-1L >>> start) ^ ((-1L >>> end) >>> 1);
            if (start > end) return ~ret;
        }
        return ret;
    }

    // ------------------------------------------------------------------ multiply / sum

    /** vmul[eo][su][bhw]: products of even (odd) elements into double-width lanes. */
    static void mulEO(CpuState s, int insn, int size, boolean sgn, boolean odd) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], bh = s.vsrHi[b], bl = s.vsrLo[b];
        int n = 16 / (2 * size), half = n / 2;
        long rh = 0, rl = 0;
        for (int i = 0; i < n; i++) {
            int e = 2 * i + (odd ? 1 : 0);
            long x = sgn ? gets(ah, al, size, e) : get(ah, al, size, e);
            long y = sgn ? gets(bh, bl, size, e) : get(bh, bl, size, e);
            long p = x * y;
            if (size == 4) {
                if (i == 0) rh = p; else rl = p;
                continue;
            }
            long r = (p & mask(2 * size)) << shift(2 * size, i);
            if (i < half) rh |= r; else rl |= r;
        }
        set(s, 32 + rt(insn), rh, rl);
    }

    /** vmsum*: per word, sum of size-byte products plus c, modulo or saturated. */
    static void msum(CpuState s, int insn, int size, boolean sa, boolean sb, int satMode) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn), c = 32 + rc(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], bh = s.vsrHi[b], bl = s.vsrLo[b];
        long ch = s.vsrHi[c], cl = s.vsrLo[c];
        int per = 4 / size;
        long rh = 0, rl = 0;
        boolean[] sat = new boolean[1];
        for (int w = 0; w < 4; w++) {
            long sum = satMode == 1 ? (long) V.w(ch, cl, w) : satMode == 2 ? V.w(ch, cl, w) & 0xffffffffL
                    : V.w(ch, cl, w);
            for (int j = 0; j < per; j++) {
                int e = w * per + j;
                long x = sa ? gets(ah, al, size, e) : get(ah, al, size, e);
                long y = sb ? gets(bh, bl, size, e) : get(bh, bl, size, e);
                sum += x * y;
            }
            long r = satMode == 1 ? clampS(sum, 4, sat) : satMode == 2 ? clampU(sum, 4, sat) : sum;
            r = (r & 0xffffffffL) << (w % 2 == 0 ? 32 : 0);
            if (w < 2) rh |= r; else rl |= r;
        }
        set(s, 32 + rt(insn), rh, rl);
        if (sat[0]) setSat(s);
    }

    static void vmsumudm(CpuState s, int insn) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn), c = 32 + rc(insn);
        long p0h = Math.unsignedMultiplyHigh(s.vsrHi[a], s.vsrHi[b]), p0l = s.vsrHi[a] * s.vsrHi[b];
        long p1h = Math.unsignedMultiplyHigh(s.vsrLo[a], s.vsrLo[b]), p1l = s.vsrLo[a] * s.vsrLo[b];
        long lo = p0l + p1l;
        long hi = p0h + p1h + (Long.compareUnsigned(lo, p0l) < 0 ? 1 : 0);
        long lo2 = lo + s.vsrLo[c];
        hi += s.vsrHi[c] + (Long.compareUnsigned(lo2, lo) < 0 ? 1 : 0);
        set(s, 32 + rt(insn), hi, lo2);
    }

    static void vsum(CpuState s, int insn, int kind) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], bh = s.vsrHi[b], bl = s.vsrLo[b];
        boolean[] sat = new boolean[1];
        long[] r = new long[4];
        switch (kind) {
            case 0 -> { // vsumsws
                long t = V.w(bh, bl, 3);
                for (int i = 0; i < 4; i++) t += V.w(ah, al, i);
                r[3] = clampS(t, 4, sat);
            }
            case 1 -> { // vsum2sws
                for (int i = 0; i < 2; i++) {
                    long t = V.w(bh, bl, 2 * i + 1);
                    t += V.w(ah, al, 2 * i) + (long) V.w(ah, al, 2 * i + 1);
                    r[2 * i + 1] = clampS(t, 4, sat);
                }
            }
            case 2 -> { // vsum4sbs
                for (int i = 0; i < 4; i++) {
                    long t = V.w(bh, bl, i);
                    for (int j = 0; j < 4; j++) t += (byte) V.b(ah, al, 4 * i + j);
                    r[i] = clampS(t, 4, sat);
                }
            }
            case 3 -> { // vsum4shs
                for (int i = 0; i < 4; i++) {
                    long t = V.w(bh, bl, i);
                    t += (short) V.h(ah, al, 2 * i) + (short) V.h(ah, al, 2 * i + 1);
                    r[i] = clampS(t, 4, sat);
                }
            }
            default -> { // vsum4ubs
                for (int i = 0; i < 4; i++) {
                    long t = V.w(bh, bl, i) & 0xffffffffL;
                    for (int j = 0; j < 4; j++) t += V.b(ah, al, 4 * i + j);
                    r[i] = clampU(t, 4, sat);
                }
            }
        }
        set(s, 32 + rt(insn), (r[0] << 32) | (r[1] & 0xffffffffL), (r[2] << 32) | (r[3] & 0xffffffffL));
        if (sat[0]) setSat(s);
    }

    static void mhadd(CpuState s, int insn, int kind) {
        boolean[] sat = new boolean[1];
        map3(s, insn, 2, true, (x, y, z) -> switch (kind) {
            case 0 -> clampS(z + ((int) (x * y) >> 15), 2, sat); // vmhaddshs
            case 1 -> clampS(z + ((int) (x * y + 0x4000) >> 15), 2, sat); // vmhraddshs
            default -> x * y + z; // vmladduhm
        });
        if (sat[0]) setSat(s);
    }

    // ------------------------------------------------------------------ permute

    static void vperm(CpuState s, int insn, int kind) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn), c = 32 + rc(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], bh = s.vsrHi[b], bl = s.vsrLo[b];
        long ch = s.vsrHi[c], cl = s.vsrLo[c];
        long rh = 0, rl = 0;
        for (int i = 0; i < 16; i++) {
            int sel = V.b(ch, cl, i);
            int v;
            if (kind == 2) { // vpermxor
                v = V.b(ah, al, sel >>> 4) ^ V.b(bh, bl, sel & 15);
            } else {
                int idx = kind == 1 ? 31 - (sel & 31) : sel & 31;
                v = idx < 16 ? V.b(ah, al, idx) : V.b(bh, bl, idx - 16);
            }
            if (i < 8) rh |= (long) v << (56 - 8 * i); else rl |= (long) v << (56 - 8 * (i - 8));
        }
        set(s, 32 + rt(insn), rh, rl);
    }

    static void vsldoi(CpuState s, int insn) {
        checkVec(s);
        int sh = (insn >>> 6) & 15;
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], bh = s.vsrHi[b], bl = s.vsrLo[b];
        long[] r = shl256(ah, al, bh, bl, 8 * sh);
        set(s, 32 + rt(insn), r[0], r[1]);
    }

    /** The top 128 bits of (ah:al:bh:bl) << n, n in [0, 128]. */
    static long[] shl256(long ah, long al, long bh, long bl, int n) {
        long[] w = {ah, al, bh, bl};
        int q = n / 64, r = n % 64;
        long[] out = new long[2];
        for (int i = 0; i < 2; i++) {
            long x = q + i < 4 ? w[q + i] : 0;
            long y = q + i + 1 < 4 ? w[q + i + 1] : 0;
            out[i] = r == 0 ? x : (x << r) | (y >>> (64 - r));
        }
        return out;
    }

    static void vsel(CpuState s, int insn) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn), c = 32 + rc(insn);
        set(s, 32 + rt(insn), (s.vsrHi[b] & s.vsrHi[c]) | (s.vsrHi[a] & ~s.vsrHi[c]),
                (s.vsrLo[b] & s.vsrLo[c]) | (s.vsrLo[a] & ~s.vsrLo[c]));
    }

    static void merge(CpuState s, int insn, int size, boolean low) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], bh = s.vsrHi[b], bl = s.vsrLo[b];
        int n = 16 / size, half = n / 2;
        int ofs = low ? half : 0;
        long rh = 0, rl = 0;
        for (int i = 0; i < half; i++) {
            long x = get(ah, al, size, i + ofs), y = get(bh, bl, size, i + ofs);
            int e0 = 2 * i, e1 = 2 * i + 1;
            long r0 = x << shift(size, e0), r1 = y << shift(size, e1);
            if (e0 < half) rh |= r0; else rl |= r0;
            if (e1 < half) rh |= r1; else rl |= r1;
        }
        set(s, 32 + rt(insn), rh, rl);
    }

    static void mergeEO(CpuState s, int insn, boolean odd) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long m = 0xffffffffL;
        if (odd) {
            set(s, 32 + rt(insn), (s.vsrHi[a] << 32) | (s.vsrHi[b] & m), (s.vsrLo[a] << 32) | (s.vsrLo[b] & m));
        } else {
            set(s, 32 + rt(insn), (s.vsrHi[a] & ~m) | (s.vsrHi[b] >>> 32), (s.vsrLo[a] & ~m) | (s.vsrLo[b] >>> 32));
        }
    }

    static long splat(long v, int size) {
        return switch (size) {
            case 1 -> (v & 0xff) * 0x0101010101010101L;
            case 2 -> (v & 0xffff) * 0x0001000100010001L;
            case 4 -> (v & 0xffffffffL) * 0x0000000100000001L;
            default -> v;
        };
    }

    static void vsplt(CpuState s, int insn, int size) {
        checkVec(s);
        int b = 32 + rb(insn);
        int uim = ((insn >>> 16) & 31) * size & 15;
        long v = get(s.vsrHi[b], s.vsrLo[b], size, uim / size);
        long r = splat(v, size);
        set(s, 32 + rt(insn), r, r);
    }

    static void vspltis(CpuState s, int insn, int size) {
        checkVec(s);
        long simm = ((insn >>> 16) & 31) << 27 >> 27;
        long r = splat(simm, size);
        set(s, 32 + rt(insn), r, r);
    }

    static void vextract(CpuState s, int insn, int size) {
        checkVec(s);
        int uim = (insn >>> 16) & 15;
        if (uim > 16 - size) uim = 0;
        int b = 32 + rb(insn);
        long v = 0;
        for (int i = 0; i < size; i++) v = (v << 8) | V.b(s.vsrHi[b], s.vsrLo[b], uim + i);
        set(s, 32 + rt(insn), v, 0);
    }

    static void vinsert(CpuState s, int insn, int size) {
        checkVec(s);
        int uim = (insn >>> 16) & 15;
        if (uim > 16 - size) return;
        int b = 32 + rb(insn), t = 32 + rt(insn);
        long src = s.vsrHi[b];
        byte[] r = toBytes(s.vsrHi[t], s.vsrLo[t]);
        for (int i = 0; i < size; i++) r[uim + i] = (byte) (src >>> (8 * (size - 1 - i)));
        set(s, t, fromBytes(r, 0), fromBytes(r, 8));
    }

    static byte[] toBytes(long hi, long lo) {
        byte[] r = new byte[16];
        for (int i = 0; i < 8; i++) {
            r[i] = (byte) (hi >>> (56 - 8 * i));
            r[8 + i] = (byte) (lo >>> (56 - 8 * i));
        }
        return r;
    }

    static long fromBytes(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xff);
        return v;
    }

    /** vextu[bhw][lr]x: element of VRB at byte index GPR[RA] & 15, into GPR[RT]. */
    static void vextux(CpuState s, int insn, int size, boolean left) {
        checkVec(s);
        int index = (int) (s.gpr[ra(insn)] & 15) * 8;
        int bits = size * 8;
        if (left) index = 128 - index - bits;
        // QEMU shifts the signed 128-bit value; an out-of-range (negative) index wraps mod 128.
        index &= 127;
        int b = 32 + rb(insn);
        long hi = s.vsrHi[b], lo = s.vsrLo[b];
        long v = index == 0 ? lo : index < 64 ? (lo >>> index) | (hi << (64 - index)) : hi >> (index - 64);
        s.gpr[rt(insn)] = v & ((1L << bits) - 1);
    }

    // ------------------------------------------------------------------ pack / unpack

    static void pack(CpuState s, int insn, int from, boolean sgnIn, boolean sgnOut, boolean doSat) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], bh = s.vsrHi[b], bl = s.vsrLo[b];
        int to = from / 2;
        int n = 16 / from;
        boolean[] sat = new boolean[1];
        long rh = 0, rl = 0;
        int outN = 16 / to, half = outN / 2;
        for (int i = 0; i < 2 * n; i++) {
            long x = i < n ? (sgnIn ? gets(ah, al, from, i) : get(ah, al, from, i))
                    : (sgnIn ? gets(bh, bl, from, i - n) : get(bh, bl, from, i - n));
            long v;
            if (!doSat) {
                v = x;
            } else if (sgnOut) {
                v = clampS(x, to, sat);
            } else if (!sgnIn && from == 8) {
                // u64 -> u32 saturation: x is the raw doubleword
                v = Long.compareUnsigned(x, 0xffffffffL) > 0 ? sat(sat, 0xffffffffL) : x;
            } else {
                v = clampU(x, to, sat);
            }
            long r = (v & mask(to)) << shift(to, i);
            if (i < half) rh |= r; else rl |= r;
        }
        set(s, 32 + rt(insn), rh, rl);
        if (sat[0]) setSat(s);
    }

    private static long sat(boolean[] sat, long v) {
        sat[0] = true;
        return v;
    }

    static void unpack(CpuState s, int insn, int from, boolean low) {
        checkVec(s);
        int b = 32 + rb(insn);
        long bh = s.vsrHi[b], bl = s.vsrLo[b];
        int to = from * 2;
        int n = 16 / to, half = n / 2;
        int ofs = low ? n : 0;
        long rh = 0, rl = 0;
        for (int i = 0; i < n; i++) {
            long v = gets(bh, bl, from, i + ofs);
            long r = (v & mask(to)) << shift(to, i);
            if (i < half) rh |= r; else rl |= r;
        }
        set(s, 32 + rt(insn), rh, rl);
    }

    static void vpkpx(CpuState s, int insn) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long rh = 0, rl = 0;
        for (int i = 0; i < 8; i++) {
            int e = i < 4 ? V.w(s.vsrHi[a], s.vsrLo[a], i) : V.w(s.vsrHi[b], s.vsrLo[b], i - 4);
            long p = ((e >>> 9) & 0xfc00) | ((e >>> 6) & 0x3e0) | ((e >>> 3) & 0x1f);
            if (i < 4) rh |= p << (48 - 16 * i); else rl |= p << (48 - 16 * (i - 4));
        }
        set(s, 32 + rt(insn), rh, rl);
    }

    static void vupkpx(CpuState s, int insn, boolean low) {
        checkVec(s);
        int b = 32 + rb(insn);
        long[] r = new long[4];
        for (int i = 0; i < 4; i++) {
            int e = V.h(s.vsrHi[b], s.vsrLo[b], i + (low ? 4 : 0));
            long aa = (e >>> 15) != 0 ? 0xff : 0;
            r[i] = (aa << 24) | ((long) ((e >>> 10) & 0x1f) << 16) | ((long) ((e >>> 5) & 0x1f) << 8) | (e & 0x1f);
        }
        set(s, 32 + rt(insn), (r[0] << 32) | r[1], (r[2] << 32) | r[3]);
    }

    // ------------------------------------------------------------------ shifts

    static void vsl(CpuState s, int insn, boolean right) {
        checkVec(s);
        int a = 32 + ra(insn);
        int sh = (int) (s.vsrLo[32 + rb(insn)] & 7);
        long hi = s.vsrHi[a], lo = s.vsrLo[a];
        if (sh == 0) {
            set(s, 32 + rt(insn), hi, lo);
        } else if (right) {
            set(s, 32 + rt(insn), hi >>> sh, (lo >>> sh) | (hi << (64 - sh)));
        } else {
            set(s, 32 + rt(insn), (hi << sh) | (lo >>> (64 - sh)), lo << sh);
        }
    }

    static void vslo(CpuState s, int insn, boolean right) {
        checkVec(s);
        int a = 32 + ra(insn);
        int sh = 8 * ((int) (s.vsrLo[32 + rb(insn)] >>> 3) & 15);
        long hi = s.vsrHi[a], lo = s.vsrLo[a];
        long rh, rl;
        if (sh == 0) {
            rh = hi;
            rl = lo;
        } else if (right) {
            if (sh >= 64) { rh = 0; rl = hi >>> (sh - 64); } else { rh = hi >>> sh; rl = (lo >>> sh) | (hi << (64 - sh)); }
        } else {
            if (sh >= 64) { rl = 0; rh = lo << (sh - 64); } else { rl = lo << sh; rh = (hi << sh) | (lo >>> (64 - sh)); }
        }
        set(s, 32 + rt(insn), rh, rl);
    }

    static void vslv(CpuState s, int insn, boolean right) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], bh = s.vsrHi[b], bl = s.vsrLo[b];
        long rh = 0, rl = 0;
        for (int i = 0; i < 16; i++) {
            int sh = V.b(bh, bl, i) & 7;
            int v;
            if (right) {
                int bytes = ((i > 0 ? V.b(ah, al, i - 1) : 0) << 8) | V.b(ah, al, i);
                v = (bytes >>> sh) & 0xff;
            } else {
                int bytes = (V.b(ah, al, i) << 8) | (i < 15 ? V.b(ah, al, i + 1) : 0);
                v = ((bytes << sh) >>> 8) & 0xff;
            }
            if (i < 8) rh |= (long) v << (56 - 8 * i); else rl |= (long) v << (56 - 8 * (i - 8));
        }
        set(s, 32 + rt(insn), rh, rl);
    }

    static void vrlmi(CpuState s, int insn, int size, boolean insert) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn), t = 32 + rt(insn);
        long[] src1 = {s.vsrHi[a], s.vsrLo[a]}, src2 = {s.vsrHi[b], s.vsrLo[b]}, src3 = {s.vsrHi[t], s.vsrLo[t]};
        long[] r = new long[2];
        for (int d = 0; d < 2; d++) {
            if (size == 8) {
                long x2 = src2[d];
                int shift = (int) x2 & 63, end = (int) (x2 >>> 8) & 63, begin = (int) (x2 >>> 16) & 63;
                long rot = Long.rotateLeft(src1[d], shift);
                long m = mask64(begin, end);
                r[d] = insert ? (rot & m) | (src3[d] & ~m) : rot & m;
            } else {
                long acc = 0;
                for (int k = 0; k < 2; k++) {
                    int sh = 32 - 32 * k;
                    int x1 = (int) (src1[d] >>> sh), x2 = (int) (src2[d] >>> sh), x3 = (int) (src3[d] >>> sh);
                    // QEMU (as built for x86) behaves as if the word fields were 5 bits wide.
                    int shift = x2 & 31, end = (x2 >>> 8) & 31, begin = (x2 >>> 16) & 31;
                    int rot = Integer.rotateLeft(x1, shift);
                    int m = mask32(begin, end);
                    int v = insert ? (rot & m) | (x3 & ~m) : rot & m;
                    acc |= (v & 0xffffffffL) << sh;
                }
                r[d] = acc;
            }
        }
        set(s, t, r[0], r[1]);
    }

    // ------------------------------------------------------------------ bit counting / gather

    static long clz(long v, int size) {
        int bits = 8 * size;
        return v == 0 ? bits : Long.numberOfLeadingZeros(v) - (64 - bits);
    }

    static long ctz(long v, int size) {
        int bits = 8 * size;
        return v == 0 ? bits : Long.numberOfTrailingZeros(v);
    }

    static void vgbbd(CpuState s, int insn) {
        checkVec(s);
        int b = 32 + rb(insn);
        set(s, 32 + rt(insn), gbbd(s.vsrHi[b]), gbbd(s.vsrLo[b]));
    }

    /** Bit-matrix transpose of a doubleword viewed as 8x8 bits (byte i bit j -> byte j bit i). */
    static long gbbd(long x) {
        long r = 0;
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                // ISA numbering: bit j of byte i is (63 - (8*i + j)) in Java numbering
                long bit = (x >>> (63 - (8 * i + j))) & 1;
                r |= bit << (63 - (8 * j + i));
            }
        }
        return r;
    }

    static void vbpermq(CpuState s, int insn) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long perm = 0;
        for (int i = 0; i < 16; i++) {
            int index = V.b(s.vsrHi[b], s.vsrLo[b], i);
            if (index < 128) {
                long w = index < 64 ? s.vsrHi[a] : s.vsrLo[a];
                if (((w >>> (63 - (index & 63))) & 1) != 0) perm |= 0x8000 >>> i;
            }
        }
        set(s, 32 + rt(insn), perm, 0);
    }

    static void vbpermd(CpuState s, int insn) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long[] av = {s.vsrHi[a], s.vsrLo[a]};
        long[] r = new long[2];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 8; j++) {
                int index = V.b(s.vsrHi[b], s.vsrLo[b], 8 * i + j);
                if (index < 64 && ((av[i] >>> (63 - index)) & 1) != 0) r[i] |= 0x80 >>> j;
            }
        }
        set(s, 32 + rt(insn), r[0], r[1]);
    }

    static void vpmsum(CpuState s, int insn, int size) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long[] av = {s.vsrHi[a], s.vsrLo[a]}, bv = {s.vsrHi[b], s.vsrLo[b]};
        if (size == 8) {
            long[] lo0 = new long[1], lo1 = new long[1];
            long h0 = clmulHi(av[0], bv[0], lo0), h1 = clmulHi(av[1], bv[1], lo1);
            set(s, 32 + rt(insn), h0 ^ h1, lo0[0] ^ lo1[0]);
            return;
        }
        long[] r = new long[2];
        int bits = 8 * size;
        long m = mask(size);
        for (int d = 0; d < 2; d++) {
            long acc = 0;
            int n = 8 / size;
            for (int k = 0; k < n; k += 2) {
                // pair (k, k+1) within the doubleword -> 2*bits-wide product lane
                long x0 = (av[d] >>> (64 - bits * (k + 1))) & m, y0 = (bv[d] >>> (64 - bits * (k + 1))) & m;
                long x1 = (av[d] >>> (64 - bits * (k + 2))) & m, y1 = (bv[d] >>> (64 - bits * (k + 2))) & m;
                long p = clmulLow(x0, y0) ^ clmulLow(x1, y1);
                int lane = k / 2;
                int sh = 64 - 2 * bits * (lane + 1);
                acc |= (2 * bits == 64 ? p : p & ((1L << (2 * bits)) - 1)) << sh;
            }
            r[d] = acc;
        }
        set(s, 32 + rt(insn), r[0], r[1]);
    }

    static long parity(long v, int size) {
        long p = 0;
        for (int i = 0; i < size; i++) p ^= (v >>> (8 * i)) & 1;
        return p;
    }

    /** vclzlsbb / vctzlsbb: count leading/trailing bytes whose least-significant bit is 0. */
    static void lsbb(CpuState s, int insn, boolean trailing) {
        checkVec(s);
        int b = 32 + rb(insn);
        int count = 0;
        for (int k = 0; k < 16; k++) {
            int i = trailing ? 15 - k : k;
            if ((V.b(s.vsrHi[b], s.vsrLo[b], i) & 1) != 0) break;
            count++;
        }
        s.gpr[rt(insn)] = count;
    }

    // ------------------------------------------------------------------ compares

    @FunctionalInterface interface Cmp { boolean f(long a, long b); }

    static void vcmp(CpuState s, int insn, int size, boolean sgn, Cmp c) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], bh = s.vsrHi[b], bl = s.vsrLo[b];
        long m = mask(size);
        long rh = 0, rl = 0;
        int n = 16 / size, half = n / 2;
        boolean all = true, none = true;
        for (int i = 0; i < n; i++) {
            long x = sgn ? gets(ah, al, size, i) : get(ah, al, size, i);
            long y = sgn ? gets(bh, bl, size, i) : get(bh, bl, size, i);
            if (c.f(x, y)) {
                none = false;
                long r = m << shift(size, i);
                if (size == 8) r = -1L;
                if (i < half) rh |= r; else rl |= r;
            } else {
                all = false;
            }
        }
        set(s, 32 + rt(insn), rh, rl);
        if ((insn & 0x400) != 0) {
            s.setCrField(6, (all ? 8 : 0) | (none ? 2 : 0));
        }
    }

    // ------------------------------------------------------------------ floating point

    @FunctionalInterface interface F2 { long f(SoftFloat sf); }

    /** Element-wise binary32 operation under vec_status (slots 0.. hold the operands). */
    static void fp(CpuState s, int insn, int nsrc, F2 op) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn), c = 32 + rc(insn);
        SoftFloat sf = SoftFloat.get();
        long rh = 0, rl = 0;
        for (int i = 0; i < 4; i++) {
            sf.resetVec(s.vscr);
            if (nsrc == 3) {
                sf.unpackS(0, V.w(s.vsrHi[a], s.vsrLo[a], i));
                sf.unpackS(1, V.w(s.vsrHi[b], s.vsrLo[b], i));
                sf.unpackS(2, V.w(s.vsrHi[c], s.vsrLo[c], i));
            } else if (nsrc == 2) {
                sf.unpackS(0, V.w(s.vsrHi[a], s.vsrLo[a], i));
                sf.unpackS(1, V.w(s.vsrHi[b], s.vsrLo[b], i));
            } else {
                sf.unpackS(0, V.w(s.vsrHi[b], s.vsrLo[b], i));
            }
            long r = op.f(sf) & 0xffffffffL;
            if (i < 2) rh |= r << (32 - 32 * i); else rl |= r << (32 - 32 * (i - 2));
        }
        set(s, 32 + rt(insn), rh, rl);
    }

    static final int ONE_S = 0x3f800000;

    static void vrecip(CpuState s, int insn, boolean rsqrt) {
        checkVec(s);
        int b = 32 + rb(insn);
        SoftFloat sf = SoftFloat.get();
        long rh = 0, rl = 0;
        for (int i = 0; i < 4; i++) {
            sf.resetVec(s.vscr);
            int x = V.w(s.vsrHi[b], s.vsrLo[b], i);
            int d = x;
            if (rsqrt) {
                sf.unpackS(0, x);
                d = (int) sf.sqrt(SoftFloat.S);
            }
            sf.unpackS(0, ONE_S);
            sf.unpackS(1, d);
            long r = sf.div(SoftFloat.S) & 0xffffffffL;
            if (i < 2) rh |= r << (32 - 32 * i); else rl |= r << (32 - 32 * (i - 2));
        }
        set(s, 32 + rt(insn), rh, rl);
    }

    static void vcf(CpuState s, int insn, boolean signed) {
        checkVec(s);
        int b = 32 + rb(insn);
        int uim = (insn >>> 16) & 31;
        SoftFloat sf = SoftFloat.get();
        long rh = 0, rl = 0;
        for (int i = 0; i < 4; i++) {
            sf.resetVec(s.vscr);
            int x = V.w(s.vsrHi[b], s.vsrLo[b], i);
            long f = sf.fromInt(SoftFloat.S, 0, signed ? x : x & 0xffffffffL, signed, false);
            sf.unpackS(0, (int) f);
            sf.scale(0, -uim);
            long r = sf.deliver(SoftFloat.S, 0) & 0xffffffffL;
            if (i < 2) rh |= r << (32 - 32 * i); else rl |= r << (32 - 32 * (i - 2));
        }
        set(s, 32 + rt(insn), rh, rl);
    }

    static void vct(CpuState s, int insn, boolean signed) {
        checkVec(s);
        int b = 32 + rb(insn);
        int uim = (insn >>> 16) & 31;
        SoftFloat sf = SoftFloat.get();
        boolean[] sat = new boolean[1];
        long rh = 0, rl = 0;
        for (int i = 0; i < 4; i++) {
            sf.resetVec(s.vscr);
            int x = V.w(s.vsrHi[b], s.vsrLo[b], i);
            long r;
            if ((x & 0x7fffffff) > 0x7f800000) {
                r = 0;
            } else {
                sf.unpackS(0, x);
                sf.scale(0, uim);
                long j = sf.toInt(64, true, SoftFloat.RZ);
                r = signed ? clampS(j, 4, sat) : clampU(j, 4, sat);
            }
            r &= 0xffffffffL;
            if (i < 2) rh |= r << (32 - 32 * i); else rl |= r << (32 - 32 * (i - 2));
        }
        set(s, 32 + rt(insn), rh, rl);
        if (sat[0]) setSat(s);
    }

    /** vcmp*fp: quiet compares (NJ flushes denormal inputs). kind: 0 eq, 1 ge, 2 gt, 3 bounds. */
    static void vcmpfp(CpuState s, int insn, int kind) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        SoftFloat sf = SoftFloat.get();
        long rh = 0, rl = 0;
        boolean all = true, none = true, allIn = true;
        for (int i = 0; i < 4; i++) {
            sf.resetVec(s.vscr);
            int x = V.w(s.vsrHi[a], s.vsrLo[a], i), y = V.w(s.vsrHi[b], s.vsrLo[b], i);
            sf.unpackS(0, x);
            sf.unpackS(1, y);
            int rel = sf.compare();
            long r;
            if (kind == 3) {
                if (rel == SoftFloat.CMP_UN) {
                    r = 0xc0000000L;
                    allIn = false;
                } else {
                    sf.unpackS(1, y ^ 0x80000000);
                    int ge = sf.compare();
                    boolean le = rel != SoftFloat.CMP_GT;
                    boolean gee = ge != SoftFloat.CMP_LT;
                    r = ((le ? 0L : 1L) << 31) | ((gee ? 0L : 1L) << 30);
                    if (!le || !gee) allIn = false;
                }
            } else {
                boolean t = rel != SoftFloat.CMP_UN && switch (kind) {
                    case 0 -> rel == SoftFloat.CMP_EQ;
                    case 1 -> rel != SoftFloat.CMP_LT;
                    default -> rel == SoftFloat.CMP_GT;
                };
                r = t ? 0xffffffffL : 0;
                if (t) none = false; else all = false;
            }
            if (i < 2) rh |= r << (32 - 32 * i); else rl |= r << (32 - 32 * (i - 2));
        }
        set(s, 32 + rt(insn), rh, rl);
        if ((insn & 0x400) != 0) {
            s.setCrField(6, kind == 3 ? (allIn ? 2 : 0) : (all ? 8 : 0) | (none ? 2 : 0));
        }
    }

    // ------------------------------------------------------------------ crypto

    static void aes(CpuState s, int insn, int kind) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        byte[] st = toBytes(s.vsrHi[a], s.vsrLo[a]);
        byte[] r = switch (kind) {
            case 0 -> Aes.cipher(st, true);
            case 1 -> Aes.cipher(st, false);
            case 2, 3 -> Aes.ncipher(st);
            default -> Aes.sbox(st);
        };
        long rh = fromBytes(r, 0), rl = fromBytes(r, 8);
        if (kind == 2) {
            // vncipher: InvMixColumns(InvSubBytes(InvShiftRows(state)) ^ key)
            byte[] x = toBytes(rh ^ s.vsrHi[b], rl ^ s.vsrLo[b]);
            byte[] y = Aes.invMixColumns(x);
            set(s, 32 + rt(insn), fromBytes(y, 0), fromBytes(y, 8));
            return;
        }
        if (kind != 4) {
            rh ^= s.vsrHi[b];
            rl ^= s.vsrLo[b];
        }
        set(s, 32 + rt(insn), rh, rl);
    }

    static void vshasigma(CpuState s, int insn, boolean dw) {
        checkVec(s);
        int a = 32 + ra(insn);
        int st = (insn >>> 15) & 1, six = (insn >>> 11) & 15;
        long hi = s.vsrHi[a], lo = s.vsrLo[a];
        if (dw) {
            long[] in = {hi, lo}, r = new long[2];
            for (int i = 0; i < 2; i++) {
                long x = in[i];
                boolean bit = (six & (0x8 >>> (2 * i))) != 0;
                if (st == 0) {
                    r[i] = !bit ? Long.rotateRight(x, 1) ^ Long.rotateRight(x, 8) ^ (x >>> 7)
                            : Long.rotateRight(x, 19) ^ Long.rotateRight(x, 61) ^ (x >>> 6);
                } else {
                    r[i] = !bit ? Long.rotateRight(x, 28) ^ Long.rotateRight(x, 34) ^ Long.rotateRight(x, 39)
                            : Long.rotateRight(x, 14) ^ Long.rotateRight(x, 18) ^ Long.rotateRight(x, 41);
                }
            }
            set(s, 32 + rt(insn), r[0], r[1]);
        } else {
            long[] r = new long[4];
            for (int i = 0; i < 4; i++) {
                int x = V.w(hi, lo, i);
                boolean bit = (six & (0x8 >>> i)) != 0;
                int v;
                if (st == 0) {
                    v = !bit ? Integer.rotateRight(x, 7) ^ Integer.rotateRight(x, 18) ^ (x >>> 3)
                            : Integer.rotateRight(x, 17) ^ Integer.rotateRight(x, 19) ^ (x >>> 10);
                } else {
                    v = !bit ? Integer.rotateRight(x, 2) ^ Integer.rotateRight(x, 13) ^ Integer.rotateRight(x, 22)
                            : Integer.rotateRight(x, 6) ^ Integer.rotateRight(x, 11) ^ Integer.rotateRight(x, 25);
                }
                r[i] = v & 0xffffffffL;
            }
            set(s, 32 + rt(insn), (r[0] << 32) | r[1], (r[2] << 32) | r[3]);
        }
    }

    // ------------------------------------------------------------------ vmul10

    static void vmul10(CpuState s, int insn, boolean addCin, boolean retCarry) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a];
        long cin = addCin ? s.vsrLo[b] & 0xf : 0;
        // (ah:al) * 10 + cin as a 132-bit value
        long pl = al * 10, plh = Math.unsignedMultiplyHigh(al, 10);
        long lo = pl + cin;
        long c0 = Long.compareUnsigned(lo, pl) < 0 ? 1 : 0;
        long ph = ah * 10, phh = Math.unsignedMultiplyHigh(ah, 10);
        long mid = ph + plh;
        long c1 = Long.compareUnsigned(mid, ph) < 0 ? 1 : 0;
        long mid2 = mid + c0;
        long c2 = Long.compareUnsigned(mid2, mid) < 0 ? 1 : 0;
        long top = phh + c1 + c2;
        if (retCarry) {
            set(s, 32 + rt(insn), 0, top);
        } else {
            set(s, 32 + rt(insn), mid2, lo);
        }
    }

    // ------------------------------------------------------------------ loads and stores

    static long ea(CpuState s, int insn) {
        int a = ra(insn);
        long ea = (a == 0 ? 0 : s.gpr[a]) + s.gpr[rb(insn)];
        return s.is64() ? ea : ea & 0xffffffffL;
    }

    static void lvx(CpuState s, int insn) {
        checkVec(s);
        long ea = ea(s, insn) & ~15L;
        long hi, lo;
        if (s.littleEndian()) {
            lo = s.mem.read64(ea);
            hi = s.mem.read64(ea + 8);
        } else {
            hi = s.mem.read64(ea);
            lo = s.mem.read64(ea + 8);
        }
        set(s, 32 + rt(insn), hi, lo);
    }

    static void stvx(CpuState s, int insn) {
        checkVec(s);
        long ea = ea(s, insn) & ~15L;
        int t = 32 + rt(insn);
        long hi = s.vsrHi[t], lo = s.vsrLo[t];
        s.mem.translateData(ea + 15, true);
        if (s.littleEndian()) {
            s.mem.write64(ea, lo);
            s.mem.write64(ea + 8, hi);
        } else {
            s.mem.write64(ea, hi);
            s.mem.write64(ea + 8, lo);
        }
    }

    static void lve(CpuState s, int insn, int size) {
        checkVec(s);
        long ea = ea(s, insn) & ~(long) (size - 1);
        int n = 16 / size;
        int index = (int) (ea & 15) / size;
        if (s.littleEndian()) index = n - 1 - index;
        long v = switch (size) {
            case 1 -> s.mem.read8(ea);
            case 2 -> s.mem.read16(ea);
            default -> s.mem.read32(ea) & 0xffffffffL;
        };
        int t = 32 + rt(insn);
        long sh = shift(size, index);
        long m = mask(size) << sh;
        if (inHi(size, index)) s.vsrHi[t] = (s.vsrHi[t] & ~m) | (v << sh);
        else s.vsrLo[t] = (s.vsrLo[t] & ~m) | (v << sh);
    }

    static void stve(CpuState s, int insn, int size) {
        checkVec(s);
        long ea = ea(s, insn) & ~(long) (size - 1);
        int n = 16 / size;
        int index = (int) (ea & 15) / size;
        if (s.littleEndian()) index = n - 1 - index;
        int t = 32 + rt(insn);
        long v = get(s.vsrHi[t], s.vsrLo[t], size, index);
        switch (size) {
            case 1 -> s.mem.write8(ea, (int) v);
            case 2 -> s.mem.write16(ea, (int) v);
            default -> s.mem.write32(ea, (int) v);
        }
    }

    static void lvs(CpuState s, int insn, boolean right) {
        checkVec(s);
        long sh = (ea(s, insn) & 15) * 0x0101010101010101L;
        if (right) {
            set(s, 32 + rt(insn), 0x1011121314151617L - sh, 0x18191a1b1c1d1e1fL - sh);
        } else {
            set(s, 32 + rt(insn), 0x0001020304050607L + sh, 0x08090a0b0c0d0e0fL + sh);
        }
    }

    // ------------------------------------------------------------------ registration

    private static void vx(DecodeTable t, int xo, String name, Op op) {
        t.add(0xfc0007ff, (4 << 26) | xo, name, op);
    }

    /** VX form whose VA field is an opcode extension (or must be zero). */
    private static void vxa(DecodeTable t, int xo, int va, String name, Op op) {
        t.add(0xfc1f07ff, (4 << 26) | (va << 16) | xo, name, op);
    }

    /** VC form: the Rc bit (0x400) selects the record form. */
    private static void vc(DecodeTable t, int xo, String name, Op op) {
        t.add(0xfc0003ff, (4 << 26) | xo, name, op);
    }

    private static void va(DecodeTable t, int xo, String name, Op op) {
        t.add(0xfc00003f, (4 << 26) | xo, name, op);
    }

    private static void x31(DecodeTable t, int xo, String name, Op op) {
        t.addX(31, xo, name, op);
    }

    @Override
    public void register(DecodeTable t) {
        // ---- modulo add/sub
        int[] sizes = {1, 2, 4, 8};
        String[] sfx = {"b", "h", "w", "d"};
        for (int k = 0; k < 4; k++) {
            int sz = sizes[k];
            vx(t, 64 * k, "vaddu" + sfx[k] + "m", (s, i) -> map2(s, i, sz, false, Long::sum));
            vx(t, 1024 + 64 * k, "vsubu" + sfx[k] + "m", (s, i) -> map2(s, i, sz, false, (x, y) -> x - y));
        }
        // ---- saturating add/sub (b, h, w)
        for (int k = 0; k < 3; k++) {
            int sz = sizes[k];
            vx(t, 512 + 64 * k, "vaddu" + sfx[k] + "s", (s, i) -> map2sat(s, i, sz, false, false, Long::sum));
            vx(t, 768 + 64 * k, "vadds" + sfx[k] + "s", (s, i) -> map2sat(s, i, sz, true, true, Long::sum));
            vx(t, 1536 + 64 * k, "vsubu" + sfx[k] + "s", (s, i) -> map2sat(s, i, sz, false, false, (x, y) -> x - y));
            vx(t, 1792 + 64 * k, "vsubs" + sfx[k] + "s", (s, i) -> map2sat(s, i, sz, true, true, (x, y) -> x - y));
        }
        vx(t, 384, "vaddcuw", (s, i) -> map2(s, i, 4, false, (x, y) -> (x + y) >>> 32));
        vx(t, 1408, "vsubcuw", (s, i) -> map2(s, i, 4, false, (x, y) -> x >= y ? 1 : 0));
        // ---- quadword arithmetic
        vx(t, 256, "vadduqm", (s, i) -> quad(s, i, (st, ah, al, bh, bl, c, d) -> add128(st, ah, al, bh, bl, 0, d, false)));
        vx(t, 320, "vaddcuq", (s, i) -> quad(s, i, (st, ah, al, bh, bl, c, d) -> add128(st, ah, al, bh, bl, 0, d, true)));
        vx(t, 1280, "vsubuqm", (s, i) -> quad(s, i, (st, ah, al, bh, bl, c, d) -> add128(st, ah, al, ~bh, ~bl, 1, d, false)));
        vx(t, 1344, "vsubcuq", (s, i) -> quad(s, i, (st, ah, al, bh, bl, c, d) -> add128(st, ah, al, ~bh, ~bl, 1, d, true)));
        va(t, 60, "vaddeuqm", (s, i) -> quad(s, i, (st, ah, al, bh, bl, c, d) -> add128(st, ah, al, bh, bl, c, d, false)));
        va(t, 61, "vaddecuq", (s, i) -> quad(s, i, (st, ah, al, bh, bl, c, d) -> add128(st, ah, al, bh, bl, c, d, true)));
        va(t, 62, "vsubeuqm", (s, i) -> quad(s, i, (st, ah, al, bh, bl, c, d) -> add128(st, ah, al, ~bh, ~bl, c, d, false)));
        va(t, 63, "vsubecuq", (s, i) -> quad(s, i, (st, ah, al, bh, bl, c, d) -> add128(st, ah, al, ~bh, ~bl, c, d, true)));
        // ---- min / max / avg / absdiff
        for (int k = 0; k < 4; k++) {
            int sz = sizes[k];
            vx(t, 2 + 64 * k, "vmaxu" + sfx[k], (s, i) -> map2(s, i, sz, false, (x, y) -> Long.compareUnsigned(x, y) > 0 ? x : y));
            vx(t, 258 + 64 * k, "vmaxs" + sfx[k], (s, i) -> map2(s, i, sz, true, Math::max));
            vx(t, 514 + 64 * k, "vminu" + sfx[k], (s, i) -> map2(s, i, sz, false, (x, y) -> Long.compareUnsigned(x, y) < 0 ? x : y));
            vx(t, 770 + 64 * k, "vmins" + sfx[k], (s, i) -> map2(s, i, sz, true, Math::min));
        }
        for (int k = 0; k < 3; k++) {
            int sz = sizes[k];
            vx(t, 1026 + 64 * k, "vavgu" + sfx[k], (s, i) -> map2(s, i, sz, false, (x, y) -> (x + y + 1) >> 1));
            vx(t, 1282 + 64 * k, "vavgs" + sfx[k], (s, i) -> map2(s, i, sz, true, (x, y) -> (x + y + 1) >> 1));
            vx(t, 1027 + 64 * k, "vabsdu" + sfx[k], (s, i) -> map2(s, i, sz, false, (x, y) -> Math.abs(x - y)));
        }
        // ---- multiply
        for (int k = 0; k < 3; k++) {
            int sz = sizes[k];
            vx(t, 8 + 64 * k, "vmulou" + sfx[k], (s, i) -> mulEO(s, i, sz, false, true));
            vx(t, 264 + 64 * k, "vmulos" + sfx[k], (s, i) -> mulEO(s, i, sz, true, true));
            vx(t, 520 + 64 * k, "vmuleu" + sfx[k], (s, i) -> mulEO(s, i, sz, false, false));
            vx(t, 776 + 64 * k, "vmules" + sfx[k], (s, i) -> mulEO(s, i, sz, true, false));
        }
        vx(t, 137, "vmuluwm", (s, i) -> map2(s, i, 4, false, (x, y) -> x * y));
        va(t, 32, "vmhaddshs", (s, i) -> mhadd(s, i, 0));
        va(t, 33, "vmhraddshs", (s, i) -> mhadd(s, i, 1));
        va(t, 34, "vmladduhm", (s, i) -> mhadd(s, i, 2));
        va(t, 35, "vmsumudm", VmxInsns::vmsumudm);
        va(t, 36, "vmsumubm", (s, i) -> msum(s, i, 1, false, false, 0));
        va(t, 37, "vmsummbm", (s, i) -> msum(s, i, 1, true, false, 0));
        va(t, 38, "vmsumuhm", (s, i) -> msum(s, i, 2, false, false, 0));
        va(t, 39, "vmsumuhs", (s, i) -> msum(s, i, 2, false, false, 2));
        va(t, 40, "vmsumshm", (s, i) -> msum(s, i, 2, true, true, 0));
        va(t, 41, "vmsumshs", (s, i) -> msum(s, i, 2, true, true, 1));
        vx(t, 1928, "vsumsws", (s, i) -> vsum(s, i, 0));
        vx(t, 1672, "vsum2sws", (s, i) -> vsum(s, i, 1));
        vx(t, 1800, "vsum4sbs", (s, i) -> vsum(s, i, 2));
        vx(t, 1608, "vsum4shs", (s, i) -> vsum(s, i, 3));
        vx(t, 1544, "vsum4ubs", (s, i) -> vsum(s, i, 4));
        vx(t, 1032, "vpmsumb", (s, i) -> vpmsum(s, i, 1));
        vx(t, 1096, "vpmsumh", (s, i) -> vpmsum(s, i, 2));
        vx(t, 1160, "vpmsumw", (s, i) -> vpmsum(s, i, 4));
        vx(t, 1224, "vpmsumd", (s, i) -> vpmsum(s, i, 8));
        t.add(0xfc00ffff, (4 << 26) | 513, "vmul10uq", (s, i) -> vmul10(s, i, false, false));
        vx(t, 577, "vmul10euq", (s, i) -> vmul10(s, i, true, false));
        vx(t, 1, "vmul10cuq", (s, i) -> vmul10(s, i, false, true));
        vx(t, 65, "vmul10ecuq", (s, i) -> vmul10(s, i, true, true));
        // ---- logical
        vx(t, 1028, "vand", (s, i) -> map2(s, i, 8, false, (x, y) -> x & y));
        vx(t, 1092, "vandc", (s, i) -> map2(s, i, 8, false, (x, y) -> x & ~y));
        vx(t, 1156, "vor", (s, i) -> map2(s, i, 8, false, (x, y) -> x | y));
        vx(t, 1220, "vxor", (s, i) -> map2(s, i, 8, false, (x, y) -> x ^ y));
        vx(t, 1284, "vnor", (s, i) -> map2(s, i, 8, false, (x, y) -> ~(x | y)));
        vx(t, 1348, "vorc", (s, i) -> map2(s, i, 8, false, (x, y) -> x | ~y));
        vx(t, 1412, "vnand", (s, i) -> map2(s, i, 8, false, (x, y) -> ~(x & y)));
        vx(t, 1668, "veqv", (s, i) -> map2(s, i, 8, false, (x, y) -> ~(x ^ y)));
        va(t, 42, "vsel", VmxInsns::vsel);
        // ---- shifts and rotates
        for (int k = 0; k < 4; k++) {
            int sz = sizes[k], bits = 8 * sz;
            vx(t, 4 + 64 * k, "vrl" + sfx[k], (s, i) -> map2(s, i, sz, false, (x, y) -> {
                int n = (int) (y & (bits - 1));
                return n == 0 ? x : (x << n) | (x >>> (bits - n));
            }));
        }
        int[] slXo = {260, 324, 388, 1476}, srXo = {516, 580, 644, 1732}, sraXo = {772, 836, 900, 964};
        for (int k = 0; k < 4; k++) {
            int sz = sizes[k], bits = 8 * sz;
            vx(t, slXo[k], "vsl" + sfx[k], (s, i) -> map2(s, i, sz, false, (x, y) -> x << (y & (bits - 1))));
            vx(t, srXo[k], "vsr" + sfx[k], (s, i) -> map2(s, i, sz, false, (x, y) -> x >>> (y & (bits - 1))));
            vx(t, sraXo[k], "vsra" + sfx[k], (s, i) -> map2(s, i, sz, true, (x, y) -> x >> (y & (bits - 1))));
        }
        vx(t, 452, "vsl", (s, i) -> vsl(s, i, false));
        vx(t, 708, "vsr", (s, i) -> vsl(s, i, true));
        vx(t, 1036, "vslo", (s, i) -> vslo(s, i, false));
        vx(t, 1100, "vsro", (s, i) -> vslo(s, i, true));
        vx(t, 1860, "vslv", (s, i) -> vslv(s, i, false));
        vx(t, 1796, "vsrv", (s, i) -> vslv(s, i, true));
        vx(t, 133, "vrlwmi", (s, i) -> vrlmi(s, i, 4, true));
        vx(t, 197, "vrldmi", (s, i) -> vrlmi(s, i, 8, true));
        vx(t, 389, "vrlwnm", (s, i) -> vrlmi(s, i, 4, false));
        vx(t, 453, "vrldnm", (s, i) -> vrlmi(s, i, 8, false));
        // ---- counting, parity, negate, extend (VX with VA opcode extension, XO 1538)
        for (int k = 0; k < 4; k++) {
            int sz = sizes[k];
            vxa(t, 1794 + 64 * k, 0, "vclz" + sfx[k], (s, i) -> map1(s, i, sz, false, v -> clz(v, sz)));
            vxa(t, 1795 + 64 * k, 0, "vpopcnt" + sfx[k], (s, i) -> map1(s, i, sz, false, Long::bitCount));
            vxa(t, 1538, 28 + k, "vctz" + sfx[k], (s, i) -> map1(s, i, sz, false, v -> ctz(v, sz)));
        }
        vxa(t, 1538, 0, "vclzlsbb", (s, i) -> lsbb(s, i, false));
        vxa(t, 1538, 1, "vctzlsbb", (s, i) -> lsbb(s, i, true));
        vxa(t, 1538, 6, "vnegw", (s, i) -> map1(s, i, 4, false, v -> -v));
        vxa(t, 1538, 7, "vnegd", (s, i) -> map1(s, i, 8, false, v -> -v));
        vxa(t, 1538, 8, "vprtybw", (s, i) -> map1(s, i, 4, false, v -> parity(v, 4)));
        vxa(t, 1538, 9, "vprtybd", (s, i) -> map1(s, i, 8, false, v -> parity(v, 8)));
        vxa(t, 1538, 10, "vprtybq", (s, i) -> {
            checkVec(s);
            int b = 32 + rb(i);
            set(s, 32 + rt(i), 0, parity(s.vsrHi[b], 8) ^ parity(s.vsrLo[b], 8));
        });
        vxa(t, 1538, 16, "vextsb2w", (s, i) -> map1(s, i, 4, false, v -> (byte) v));
        vxa(t, 1538, 17, "vextsh2w", (s, i) -> map1(s, i, 4, false, v -> (short) v));
        vxa(t, 1538, 24, "vextsb2d", (s, i) -> map1(s, i, 8, false, v -> (byte) v));
        vxa(t, 1538, 25, "vextsh2d", (s, i) -> map1(s, i, 8, false, v -> (short) v));
        vxa(t, 1538, 26, "vextsw2d", (s, i) -> map1(s, i, 8, false, v -> (int) v));
        vxa(t, 1292, 0, "vgbbd", VmxInsns::vgbbd);
        vx(t, 1356, "vbpermq", VmxInsns::vbpermq);
        vx(t, 1484, "vbpermd", VmxInsns::vbpermd);
        // ---- permute, merge, splat, extract/insert
        va(t, 43, "vperm", (s, i) -> vperm(s, i, 0));
        va(t, 59, "vpermr", (s, i) -> vperm(s, i, 1));
        va(t, 45, "vpermxor", (s, i) -> vperm(s, i, 2));
        t.add(0xfc00043f, (4 << 26) | 44, "vsldoi", VmxInsns::vsldoi);
        vx(t, 12, "vmrghb", (s, i) -> merge(s, i, 1, false));
        vx(t, 76, "vmrghh", (s, i) -> merge(s, i, 2, false));
        vx(t, 140, "vmrghw", (s, i) -> merge(s, i, 4, false));
        vx(t, 268, "vmrglb", (s, i) -> merge(s, i, 1, true));
        vx(t, 332, "vmrglh", (s, i) -> merge(s, i, 2, true));
        vx(t, 396, "vmrglw", (s, i) -> merge(s, i, 4, true));
        vx(t, 1932, "vmrgew", (s, i) -> mergeEO(s, i, false));
        vx(t, 1676, "vmrgow", (s, i) -> mergeEO(s, i, true));
        vx(t, 524, "vspltb", (s, i) -> vsplt(s, i, 1));
        vx(t, 588, "vsplth", (s, i) -> vsplt(s, i, 2));
        vx(t, 652, "vspltw", (s, i) -> vsplt(s, i, 4));
        vx(t, 780, "vspltisb", (s, i) -> vspltis(s, i, 1));
        vx(t, 844, "vspltish", (s, i) -> vspltis(s, i, 2));
        vx(t, 908, "vspltisw", (s, i) -> vspltis(s, i, 4));
        t.add(0xfc1007ff, (4 << 26) | 525, "vextractub", (s, i) -> vextract(s, i, 1));
        t.add(0xfc1007ff, (4 << 26) | 589, "vextractuh", (s, i) -> vextract(s, i, 2));
        t.add(0xfc1007ff, (4 << 26) | 653, "vextractuw", (s, i) -> vextract(s, i, 4));
        t.add(0xfc1007ff, (4 << 26) | 717, "vextractd", (s, i) -> vextract(s, i, 8));
        t.add(0xfc0007ff, (4 << 26) | 781, "vinsertb", (s, i) -> vinsert(s, i, 1));
        t.add(0xfc0007ff, (4 << 26) | 845, "vinserth", (s, i) -> vinsert(s, i, 2));
        t.add(0xfc0007ff, (4 << 26) | 909, "vinsertw", (s, i) -> vinsert(s, i, 4));
        t.add(0xfc0007ff, (4 << 26) | 973, "vinsertd", (s, i) -> vinsert(s, i, 8));
        vx(t, 1549, "vextublx", (s, i) -> vextux(s, i, 1, true));
        vx(t, 1613, "vextuhlx", (s, i) -> vextux(s, i, 2, true));
        vx(t, 1677, "vextuwlx", (s, i) -> vextux(s, i, 4, true));
        vx(t, 1805, "vextubrx", (s, i) -> vextux(s, i, 1, false));
        vx(t, 1869, "vextuhrx", (s, i) -> vextux(s, i, 2, false));
        vx(t, 1933, "vextuwrx", (s, i) -> vextux(s, i, 4, false));
        // ---- pack / unpack
        vx(t, 14, "vpkuhum", (s, i) -> pack(s, i, 2, false, false, false));
        vx(t, 78, "vpkuwum", (s, i) -> pack(s, i, 4, false, false, false));
        vx(t, 1102, "vpkudum", (s, i) -> pack(s, i, 8, false, false, false));
        vx(t, 142, "vpkuhus", (s, i) -> pack(s, i, 2, false, false, true));
        vx(t, 206, "vpkuwus", (s, i) -> pack(s, i, 4, false, false, true));
        vx(t, 1230, "vpkudus", (s, i) -> pack(s, i, 8, false, false, true));
        vx(t, 270, "vpkshus", (s, i) -> pack(s, i, 2, true, false, true));
        vx(t, 334, "vpkswus", (s, i) -> pack(s, i, 4, true, false, true));
        vx(t, 1358, "vpksdus", (s, i) -> pack(s, i, 8, true, false, true));
        vx(t, 398, "vpkshss", (s, i) -> pack(s, i, 2, true, true, true));
        vx(t, 462, "vpkswss", (s, i) -> pack(s, i, 4, true, true, true));
        vx(t, 1486, "vpksdss", (s, i) -> pack(s, i, 8, true, true, true));
        vx(t, 782, "vpkpx", VmxInsns::vpkpx);
        vxa(t, 526, 0, "vupkhsb", (s, i) -> unpack(s, i, 1, false));
        vxa(t, 590, 0, "vupkhsh", (s, i) -> unpack(s, i, 2, false));
        vx(t, 1614, "vupkhsw", (s, i) -> unpack(s, i, 4, false));
        vxa(t, 654, 0, "vupklsb", (s, i) -> unpack(s, i, 1, true));
        vxa(t, 718, 0, "vupklsh", (s, i) -> unpack(s, i, 2, true));
        vx(t, 1742, "vupklsw", (s, i) -> unpack(s, i, 4, true));
        vxa(t, 846, 0, "vupkhpx", (s, i) -> vupkpx(s, i, false));
        vxa(t, 974, 0, "vupklpx", (s, i) -> vupkpx(s, i, true));
        // ---- integer compares
        int[] eqXo = {6, 70, 134, 199};
        int[] gtuXo = {518, 582, 646, 711};
        int[] gtsXo = {774, 838, 902, 967};
        for (int k = 0; k < 4; k++) {
            int sz = sizes[k];
            vc(t, eqXo[k], "vcmpequ" + sfx[k], (s, i) -> vcmp(s, i, sz, false, (x, y) -> x == y));
            vc(t, gtuXo[k], "vcmpgtu" + sfx[k], (s, i) -> vcmp(s, i, sz, false, (x, y) -> Long.compareUnsigned(x, y) > 0));
            vc(t, gtsXo[k], "vcmpgts" + sfx[k], (s, i) -> vcmp(s, i, sz, true, (x, y) -> x > y));
        }
        for (int k = 0; k < 3; k++) {
            int sz = sizes[k];
            vc(t, 7 + 64 * k, "vcmpne" + sfx[k], (s, i) -> vcmp(s, i, sz, false, (x, y) -> x != y));
            vc(t, 263 + 64 * k, "vcmpnez" + sfx[k], (s, i) -> vcmp(s, i, sz, false, (x, y) -> x == 0 || y == 0 || x != y));
        }
        // ---- floating point
        vx(t, 10, "vaddfp", (s, i) -> fp(s, i, 2, sf -> sf.add(SoftFloat.S, false)));
        vx(t, 74, "vsubfp", (s, i) -> fp(s, i, 2, sf -> sf.add(SoftFloat.S, true)));
        vx(t, 1034, "vmaxfp", (s, i) -> fp(s, i, 2, sf -> sf.minmax(SoftFloat.S, true, false)));
        vx(t, 1098, "vminfp", (s, i) -> fp(s, i, 2, sf -> sf.minmax(SoftFloat.S, false, false)));
        // vmaddfp: a*c + b ; vnmsubfp: -(a*c - b)
        va(t, 46, "vmaddfp", (s, i) -> fp(s, i, 3, sf -> sf.fma(SoftFloat.S, false, false)));
        va(t, 47, "vnmsubfp", (s, i) -> fp(s, i, 3, sf -> sf.fma(SoftFloat.S, true, true)));
        vxa(t, 266, 0, "vrefp", (s, i) -> vrecip(s, i, false));
        vxa(t, 330, 0, "vrsqrtefp", (s, i) -> vrecip(s, i, true));
        vxa(t, 522, 0, "vrfin", (s, i) -> fp(s, i, 1, sf -> sf.roundToInt(SoftFloat.S, SoftFloat.RN)));
        vxa(t, 586, 0, "vrfiz", (s, i) -> fp(s, i, 1, sf -> sf.roundToInt(SoftFloat.S, SoftFloat.RZ)));
        vxa(t, 650, 0, "vrfip", (s, i) -> fp(s, i, 1, sf -> sf.roundToInt(SoftFloat.S, SoftFloat.RP)));
        vxa(t, 714, 0, "vrfim", (s, i) -> fp(s, i, 1, sf -> sf.roundToInt(SoftFloat.S, SoftFloat.RM)));
        vx(t, 778, "vcfux", (s, i) -> vcf(s, i, false));
        vx(t, 842, "vcfsx", (s, i) -> vcf(s, i, true));
        vx(t, 906, "vctuxs", (s, i) -> vct(s, i, false));
        vx(t, 970, "vctsxs", (s, i) -> vct(s, i, true));
        vc(t, 198, "vcmpeqfp", (s, i) -> vcmpfp(s, i, 0));
        vc(t, 454, "vcmpgefp", (s, i) -> vcmpfp(s, i, 1));
        vc(t, 710, "vcmpgtfp", (s, i) -> vcmpfp(s, i, 2));
        vc(t, 966, "vcmpbfp", (s, i) -> vcmpfp(s, i, 3));
        // ---- crypto
        vx(t, 1288, "vcipher", (s, i) -> aes(s, i, 0));
        vx(t, 1289, "vcipherlast", (s, i) -> aes(s, i, 1));
        vx(t, 1352, "vncipher", (s, i) -> aes(s, i, 2));
        vx(t, 1353, "vncipherlast", (s, i) -> aes(s, i, 3));
        t.add(0xfc00ffff, (4 << 26) | 1480, "vsbox", (s, i) -> aes(s, i, 4));
        vx(t, 1666, "vshasigmaw", (s, i) -> vshasigma(s, i, false));
        vx(t, 1730, "vshasigmad", (s, i) -> vshasigma(s, i, true));
        // ---- VSCR
        t.add(0xfc1fffff, (4 << 26) | 1540, "mfvscr", (s, i) -> {
            checkVec(s);
            set(s, 32 + rt(i), 0, s.vscr & 0xffffffffL);
        });
        t.add(0xffff07ff, (4 << 26) | 1604, "mtvscr", (s, i) -> {
            checkVec(s);
            s.vscr = (int) s.vsrLo[32 + rb(i)];
        });
        // ---- loads and stores
        x31(t, 103, "lvx", VmxInsns::lvx);
        x31(t, 359, "lvxl", VmxInsns::lvx);
        x31(t, 231, "stvx", VmxInsns::stvx);
        x31(t, 487, "stvxl", VmxInsns::stvx);
        x31(t, 7, "lvebx", (s, i) -> lve(s, i, 1));
        x31(t, 39, "lvehx", (s, i) -> lve(s, i, 2));
        x31(t, 71, "lvewx", (s, i) -> lve(s, i, 4));
        x31(t, 135, "stvebx", (s, i) -> stve(s, i, 1));
        x31(t, 167, "stvehx", (s, i) -> stve(s, i, 2));
        x31(t, 199, "stvewx", (s, i) -> stve(s, i, 4));
        x31(t, 6, "lvsl", (s, i) -> lvs(s, i, false));
        x31(t, 38, "lvsr", (s, i) -> lvs(s, i, true));
    }

}
