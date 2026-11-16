package org.boxtheta.javapower.fpvec;

import static org.boxtheta.javapower.fpvec.Swar.*;
import static org.boxtheta.javapower.fpvec.V.*;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
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
 *
 * <p>The integer instructions work on the two doublewords of a register at once, with lane
 * arithmetic ({@link Swar}) instead of element by element. Each handler hands its doubleword
 * operation to {@link #dw2} (and friends) as a method reference or a lambda that captures
 * nothing, so it is a constant the JIT inlines into the handler; a captured or shared function
 * object would leave one megamorphic call per element.
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

    // ------------------------------------------------------------------ doubleword helpers

    /** VRT = f(VRA, VRB), applied to each doubleword. */
    static void dw2(CpuState s, int insn, L2 f) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        set(s, 32 + rt(insn), f.f(s.vsrHi[a], s.vsrHi[b]), f.f(s.vsrLo[a], s.vsrLo[b]));
    }

    /** VRT = f(VRB), applied to each doubleword. */
    static void dw1(CpuState s, int insn, L1 f) {
        checkVec(s);
        int b = 32 + rb(insn);
        set(s, 32 + rt(insn), f.f(s.vsrHi[b]), f.f(s.vsrLo[b]));
    }

    /** A doubleword from its two words. */
    static long words(int hi, int lo) {
        return ((long) hi << 32) | (lo & 0xffffffffL);
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

    /** Saturating add or subtract of w-bit lanes; sets VSCR[SAT] if a lane saturated. */
    static void addSat(CpuState s, int insn, int w, boolean signed, boolean subtract) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], bh = s.vsrHi[b], bl = s.vsrLo[b];
        long rh = subtract ? sub(ah, bh, w) : add(ah, bh, w);
        long rl = subtract ? sub(al, bl, w) : add(al, bl, w);
        long oh, ol;
        if (signed) {
            oh = subtract ? ovSubS(ah, bh, rh, w) : ovAddS(ah, bh, rh, w);
            ol = subtract ? ovSubS(al, bl, rl, w) : ovAddS(al, bl, rl, w);
            rh = limitS(rh, ah, oh, w);
            rl = limitS(rl, al, ol, w);
        } else if (subtract) {
            oh = ovSubU(ah, bh, rh, w);
            ol = ovSubU(al, bl, rl, w);
            rh &= ~fill(oh, w);
            rl &= ~fill(ol, w);
        } else {
            oh = ovAddU(ah, bh, rh, w);
            ol = ovAddU(al, bl, rl, w);
            rh |= fill(oh, w);
            rl |= fill(ol, w);
        }
        set(s, 32 + rt(insn), rh, rl);
        if ((oh | ol) != 0) setSat(s);
    }

    // ------------------------------------------------------------------ 128-bit helpers

    /**
     * Quadword VRA + VRB, or VRA + ~VRB + 1 when subtracting; the extended forms add VRC's low bit
     * instead of the 0 or 1. The sum, or its carry out, goes to VRT.
     */
    static void addq(CpuState s, int insn, boolean subtract, boolean extended, boolean carryOut) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long bh = s.vsrHi[b], bl = s.vsrLo[b];
        if (subtract) {
            bh = ~bh;
            bl = ~bl;
        }
        long cin = extended ? s.vsrLo[32 + rc(insn)] & 1 : subtract ? 1 : 0;
        add128(s, s.vsrHi[a], s.vsrLo[a], bh, bl, cin, 32 + rt(insn), carryOut);
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

    // ------------------------------------------------------------------ word lanes

    static long rl32(long x, long y) {
        return words(Integer.rotateLeft((int) (x >>> 32), (int) (y >>> 32)), Integer.rotateLeft((int) x, (int) y));
    }

    static long sl32(long x, long y) {
        return words((int) (x >>> 32) << (int) (y >>> 32), (int) x << (int) y);
    }

    static long sr32(long x, long y) {
        return words((int) (x >>> 32) >>> (int) (y >>> 32), (int) x >>> (int) y);
    }

    static long sra32(long x, long y) {
        return words((int) (x >>> 32) >> (int) (y >>> 32), (int) x >> (int) y);
    }

    static long mul32(long x, long y) {
        return words((int) (x >>> 32) * (int) (y >>> 32), (int) x * (int) y);
    }

    // ------------------------------------------------------------------ multiply / sum

    /** vmul[eo][su][bhw]: products of even (odd) elements into double-width lanes. */
    static void mulEO(CpuState s, int insn, int size, boolean sgn, boolean odd) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn), w = 8 * size;
        set(s, 32 + rt(insn), mulEO(s.vsrHi[a], s.vsrHi[b], w, sgn, odd), mulEO(s.vsrLo[a], s.vsrLo[b], w, sgn, odd));
    }

    /** One doubleword of vmul[eo]: the even element of each 2w-bit lane is its upper half. */
    static long mulEO(long x, long y, int w, boolean sgn, boolean odd) {
        if (w == 32) {
            if (odd) return sgn ? (long) (int) x * (int) y : (x & 0xffffffffL) * (y & 0xffffffffL);
            return sgn ? (x >> 32) * (y >> 32) : (x >>> 32) * (y >>> 32);
        }
        long r = 0, m = (1L << w) - 1, wide = (1L << (2 * w)) - 1;
        for (int lane = 0; lane < 64; lane += 2 * w) {
            int sh = lane + (odd ? 0 : w);
            long p = sgn ? ((x >>> sh) << (64 - w) >> (64 - w)) * ((y >>> sh) << (64 - w) >> (64 - w))
                    : ((x >>> sh) & m) * ((y >>> sh) & m);
            r |= (p & wide) << lane;
        }
        return r;
    }

    /** vmsum*: per word, sum of size-byte products plus c, modulo or saturated (satMode 1 signed, 2 unsigned). */
    static void msum(CpuState s, int insn, int size, boolean sa, boolean sb, int satMode) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn), c = 32 + rc(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], bh = s.vsrHi[b], bl = s.vsrLo[b];
        long ch = s.vsrHi[c], cl = s.vsrLo[c];
        int w = 8 * size;
        long m = (1L << w) - 1;
        long rh = 0, rl = 0;
        boolean sat = false;
        for (int k = 0; k < 4; k++) {
            long x = k < 2 ? ah : al, y = k < 2 ? bh : bl, z = k < 2 ? ch : cl;
            int base = (k & 1) == 0 ? 32 : 0;
            long sum = satMode == 2 ? (z >>> base) & 0xffffffffL : (int) (z >>> base);
            for (int j = base; j < base + 32; j += w) {
                long e = sa ? (x >>> j) << (64 - w) >> (64 - w) : (x >>> j) & m;
                long f = sb ? (y >>> j) << (64 - w) >> (64 - w) : (y >>> j) & m;
                sum += e * f;
            }
            long r = sum;
            if (satMode == 1) {
                r = Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, sum));
            } else if (satMode == 2) {
                r = Math.min(0xffffffffL, sum);
            }
            sat |= r != sum;
            r = (r & 0xffffffffL) << base;
            if (k < 2) rh |= r; else rl |= r;
        }
        set(s, 32 + rt(insn), rh, rl);
        if (sat) setSat(s);
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

    /** vmhaddshs (kind 0), vmhraddshs (1), vmladduhm (2) on the eight halfwords. */
    static void mhadd(CpuState s, int insn, int kind) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn), c = 32 + rc(insn);
        long rh = 0, rl = 0;
        long[] hi = s.vsrHi, lo = s.vsrLo;
        boolean sat = false;
        for (int i = 0; i < 8; i++) {
            int sh = 48 - 16 * (i & 3);
            int x = (short) ((i < 4 ? hi[a] : lo[a]) >>> sh), y = (short) ((i < 4 ? hi[b] : lo[b]) >>> sh);
            int z = (short) ((i < 4 ? hi[c] : lo[c]) >>> sh);
            int v;
            if (kind == 2) {
                v = x * y + z;
            } else {
                int u = z + ((x * y + (kind == 1 ? 0x4000 : 0)) >> 15);
                v = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, u));
                sat |= v != u;
            }
            long r = (long) (v & 0xffff) << sh;
            if (i < 4) rh |= r; else rl |= r;
        }
        set(s, 32 + rt(insn), rh, rl);
        if (sat) setSat(s);
    }

    // ------------------------------------------------------------------ permute

    /** Per-thread scratch for vperm (VRA:VRB as 32 bytes, so a selector byte is an index) and vbpermq. */
    private static final ThreadLocal<byte[]> PERM = ThreadLocal.withInitial(() -> new byte[32]);
    private static final VarHandle BE64 = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /** vperm (kind 0), vpermr (1), vpermxor (2). */
    static void vperm(CpuState s, int insn, int kind) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn), c = 32 + rc(insn);
        long ch = s.vsrHi[c], cl = s.vsrLo[c];
        byte[] t = PERM.get();
        BE64.set(t, 0, s.vsrHi[a]);
        BE64.set(t, 8, s.vsrLo[a]);
        BE64.set(t, 16, s.vsrHi[b]);
        BE64.set(t, 24, s.vsrLo[b]);
        long rh = 0, rl = 0;
        if (kind == 0) {
            for (int i = 56; i >= 0; i -= 8) {
                rh = (rh << 8) | (t[(int) (ch >>> i) & 31] & 0xff);
                rl = (rl << 8) | (t[(int) (cl >>> i) & 31] & 0xff);
            }
        } else if (kind == 1) {
            for (int i = 56; i >= 0; i -= 8) {
                rh = (rh << 8) | (t[~(int) (ch >>> i) & 31] & 0xff);
                rl = (rl << 8) | (t[~(int) (cl >>> i) & 31] & 0xff);
            }
        } else {
            for (int i = 56; i >= 0; i -= 8) {
                rh = (rh << 8) | ((t[(int) (ch >>> (i + 4)) & 15] ^ t[16 + ((int) (ch >>> i) & 15)]) & 0xff);
                rl = (rl << 8) | ((t[(int) (cl >>> (i + 4)) & 15] ^ t[16 + ((int) (cl >>> i) & 15)]) & 0xff);
            }
        }
        set(s, 32 + rt(insn), rh, rl);
    }

    /** The top 64 bits of (x:y) << n, n in [0, 63]. */
    static long funnel(long x, long y, int n) {
        return (x << n) | (y >>> 1 >>> (63 - n));
    }

    static void vsldoi(CpuState s, int insn) {
        checkVec(s);
        int n = 8 * ((insn >>> 6) & 15);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], bh = s.vsrHi[b], bl = s.vsrLo[b];
        if (n < 64) {
            set(s, 32 + rt(insn), funnel(ah, al, n), funnel(al, bh, n));
        } else {
            set(s, 32 + rt(insn), funnel(al, bh, n - 64), funnel(bh, bl, n - 64));
        }
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
        int a = 32 + ra(insn), b = 32 + rb(insn), w = 8 * size;
        long x = low ? s.vsrLo[a] : s.vsrHi[a], y = low ? s.vsrLo[b] : s.vsrHi[b];
        set(s, 32 + rt(insn), (spread(x >>> 32, w) << w) | spread(y >>> 32, w),
                (spread(x & 0xffffffffL, w) << w) | spread(y & 0xffffffffL, w));
    }

    /** The w-bit elements of a 32-bit value, each moved to the low half of a 2w-bit lane. */
    static long spread(long v, int w) {
        if (w == 32) return v;
        v = (v | (v << 16)) & 0x0000ffff0000ffffL;
        if (w == 16) return v;
        return (v | (v << 8)) & 0x00ff00ff00ff00ffL;
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
        int b = 32 + rb(insn), n = 8 * uim;
        long hi = s.vsrHi[b], lo = s.vsrLo[b];
        long top = n < 64 ? funnel(hi, lo, n) : lo << (n - 64);
        set(s, 32 + rt(insn), top >>> (64 - 8 * size), 0);
    }

    static void vinsert(CpuState s, int insn, int size) {
        checkVec(s);
        int uim = (insn >>> 16) & 15;
        if (uim > 16 - size) return;
        int b = 32 + rb(insn), t = 32 + rt(insn);
        long m = mask(size), v = s.vsrHi[b] & m;
        int k = 128 - 8 * (uim + size); // the field's lowest bit, counting from the register's LSB
        long vh, vl, mh, ml;
        if (k >= 64) {
            vh = v << (k - 64);
            mh = m << (k - 64);
            vl = ml = 0;
        } else {
            vh = v >>> 1 >>> (63 - k);
            mh = m >>> 1 >>> (63 - k);
            vl = v << k;
            ml = m << k;
        }
        set(s, t, (s.vsrHi[t] & ~mh) | vh, (s.vsrLo[t] & ~ml) | vl);
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
        int win = 8 * from, w = win / 2;
        long m = (1L << w) - 1, wm = from == 8 ? -1L : (1L << win) - 1;
        long smax = m >>> 1, smin = -smax - 1;
        boolean sat = false;
        long rh = 0, rl = 0;
        for (int d = 0; d < 4; d++) {
            long x = d == 0 ? ah : d == 1 ? al : d == 2 ? bh : bl;
            long p = 0;
            for (int j = 64 - win; j >= 0; j -= win) {
                long e = sgnIn ? (x >>> j) << (64 - win) >> (64 - win) : (x >>> j) & wm;
                long v = e;
                if (doSat) {
                    if (sgnOut) {
                        v = Math.max(smin, Math.min(smax, e));
                    } else if (!sgnIn && from == 8) {
                        v = Long.compareUnsigned(e, m) > 0 ? m : e; // the raw doubleword, unsigned
                    } else {
                        v = Math.max(0, Math.min(m, e));
                    }
                    sat |= v != e;
                }
                p = (p << w) | (v & m);
            }
            if (d < 2) rh = (rh << 32) | p; else rl = (rl << 32) | p;
        }
        set(s, 32 + rt(insn), rh, rl);
        if (sat) setSat(s);
    }

    static void unpack(CpuState s, int insn, int from, boolean low) {
        checkVec(s);
        int b = 32 + rb(insn), win = 8 * from;
        long x = low ? s.vsrLo[b] : s.vsrHi[b];
        set(s, 32 + rt(insn), unpack(x >>> 32, win), unpack(x & 0xffffffffL, win));
    }

    /** The win-bit elements of a 32-bit value, each sign-extended to twice its width. */
    static long unpack(long v, int win) {
        long lane = win == 32 ? -1L : (1L << (2 * win)) - 1;
        long r = 0;
        for (int j = 32 - win; j >= 0; j -= win) {
            long e = (v >>> j) << (64 - win) >> (64 - win);
            r = (win == 32 ? 0 : r << (2 * win)) | (e & lane);
        }
        return r;
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
        long x = low ? s.vsrLo[b] : s.vsrHi[b];
        set(s, 32 + rt(insn), (upkpx(x >>> 48) << 32) | upkpx(x >>> 32), (upkpx(x >>> 16) << 32) | upkpx(x));
    }

    /** One 1/5/5/5 pixel (the low halfword of v) as an 8/8/8/8 word. */
    static long upkpx(long v) {
        int e = (int) v & 0xffff;
        long aa = (e >>> 15) != 0 ? 0xff : 0;
        return (aa << 24) | ((long) ((e >>> 10) & 0x1f) << 16) | ((long) ((e >>> 5) & 0x1f) << 8) | (e & 0x1f);
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
        set(s, t, rlmi(s.vsrHi[a], s.vsrHi[b], s.vsrHi[t], size, insert),
                rlmi(s.vsrLo[a], s.vsrLo[b], s.vsrLo[t], size, insert));
    }

    /** One doubleword of vrl[wd]mi / vrl[wd]nm. */
    static long rlmi(long src1, long src2, long src3, int size, boolean insert) {
        if (size == 8) {
            int shift = (int) src2 & 63, end = (int) (src2 >>> 8) & 63, begin = (int) (src2 >>> 16) & 63;
            long rot = Long.rotateLeft(src1, shift);
            long m = mask64(begin, end);
            return insert ? (rot & m) | (src3 & ~m) : rot & m;
        }
        long acc = 0;
        for (int sh = 32; sh >= 0; sh -= 32) {
            int x1 = (int) (src1 >>> sh), x2 = (int) (src2 >>> sh), x3 = (int) (src3 >>> sh);
            // QEMU (as built for x86) behaves as if the word fields were 5 bits wide.
            int shift = x2 & 31, end = (x2 >>> 8) & 31, begin = (x2 >>> 16) & 31;
            int rot = Integer.rotateLeft(x1, shift);
            int m = mask32(begin, end);
            int v = insert ? (rot & m) | (x3 & ~m) : rot & m;
            acc |= (v & 0xffffffffL) << sh;
        }
        return acc;
    }

    // ------------------------------------------------------------------ bit counting / gather

    /** Leading zeros of each w-bit lane (w = 8, 16). */
    static long clzLanes(long x, int w) {
        long r = 0, m = (1L << w) - 1;
        for (int j = 0; j < 64; j += w) {
            r |= (long) (Integer.numberOfLeadingZeros((int) ((x >>> j) & m)) - (32 - w)) << j;
        }
        return r;
    }

    /** Trailing zeros of each w-bit lane (w = 8, 16). */
    static long ctzLanes(long x, int w) {
        long r = 0, m = (1L << w) - 1;
        for (int j = 0; j < 64; j += w) {
            r |= (long) Integer.numberOfTrailingZeros((int) ((x >>> j) & m) | (1 << w)) << j;
        }
        return r;
    }

    /** Parity of the low bits of the bytes of each word. */
    static long prtybw(long x) {
        long t = x & 0x0101010101010101L;
        t ^= t >>> 16;
        t ^= t >>> 8;
        return t & 0x0000000100000001L;
    }

    /** Parity of the low bits of the bytes of a doubleword. */
    static long prtybd(long x) {
        long t = x & 0x0101010101010101L;
        t ^= t >>> 32;
        t ^= t >>> 16;
        t ^= t >>> 8;
        return t & 1;
    }

    static void vgbbd(CpuState s, int insn) {
        checkVec(s);
        int b = 32 + rb(insn);
        set(s, 32 + rt(insn), gbbd(s.vsrHi[b]), gbbd(s.vsrLo[b]));
    }

    /**
     * Bit-matrix transpose of a doubleword viewed as 8x8 bits (byte i bit j -> byte j bit i), by
     * swapping 1x1, 2x2 and 4x4 blocks across the diagonal.
     */
    static long gbbd(long x) {
        long t = (x ^ (x >>> 7)) & 0x00aa00aa00aa00aaL;
        x ^= t ^ (t << 7);
        t = (x ^ (x >>> 14)) & 0x0000cccc0000ccccL;
        x ^= t ^ (t << 14);
        t = (x ^ (x >>> 28)) & 0x00000000f0f0f0f0L;
        return x ^ t ^ (t << 28);
    }

    static void vbpermq(CpuState s, int insn) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], bh = s.vsrHi[b], bl = s.vsrLo[b];
        // The scratch holds VRA and two zero doublewords, so index / 64 picks the doubleword of
        // any index, 0 for 128 and up, with neither a branch (the indices are data) nor a select.
        byte[] t = PERM.get();
        BE64.set(t, 0, ah);
        BE64.set(t, 8, al);
        BE64.set(t, 16, 0L);
        BE64.set(t, 24, 0L);
        long perm = 0;
        for (int i = 0; i < 16; i++) {
            int index = (int) ((i < 8 ? bh : bl) >>> (56 - 8 * (i & 7))) & 0xff;
            long w = (long) BE64.get(t, (index >>> 3) & 24);
            perm |= ((w >>> (~index & 63)) & 1) << (15 - i);
        }
        set(s, 32 + rt(insn), perm, 0);
    }

    static void vbpermd(CpuState s, int insn) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        set(s, 32 + rt(insn), bpermd(s.vsrHi[a], s.vsrHi[b]), bpermd(s.vsrLo[a], s.vsrLo[b]));
    }

    static long bpermd(long x, long sel) {
        long r = 0;
        for (int j = 0; j < 8; j++) {
            int index = (int) (sel >>> (56 - 8 * j)) & 0xff;
            r |= ((x >>> (63 - (index & 63))) & 1 & (index < 64 ? 1 : 0)) << (7 - j);
        }
        return r;
    }

    static void vpmsum(CpuState s, int insn, int size) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long ah = s.vsrHi[a], al = s.vsrLo[a], bh = s.vsrHi[b], bl = s.vsrLo[b];
        if (size != 8) {
            set(s, 32 + rt(insn), pmsum(ah, bh, size), pmsum(al, bl, size));
            return;
        }
        // (ah * bh) ^ (al * bl), each product by Karatsuba over 32-bit halves.
        long rh = 0, rl = 0;
        for (int k = 0; k < 2; k++) {
            long x = k == 0 ? ah : al, y = k == 0 ? bh : bl;
            long x0 = x & 0xffffffffL, x1 = x >>> 32, y0 = y & 0xffffffffL, y1 = y >>> 32;
            long p0 = clmul32(x0, y0), p2 = clmul32(x1, y1);
            long p1 = clmul32(x0 ^ x1, y0 ^ y1) ^ p0 ^ p2;
            rl ^= p0 ^ (p1 << 32);
            rh ^= p2 ^ (p1 >>> 32);
        }
        set(s, 32 + rt(insn), rh, rl);
    }

    /** One doubleword of vpmsum[bhw]: each double-width lane is the xor of its two products. */
    static long pmsum(long x, long y, int size) {
        return switch (size) {
            case 1 -> clmulLanes((x >>> 8) & 0x00ff00ff00ff00ffL, (y >>> 8) & 0x00ff00ff00ff00ffL, 8)
                    ^ clmulLanes(x & 0x00ff00ff00ff00ffL, y & 0x00ff00ff00ff00ffL, 8);
            case 2 -> clmulLanes((x >>> 16) & 0x0000ffff0000ffffL, (y >>> 16) & 0x0000ffff0000ffffL, 16)
                    ^ clmulLanes(x & 0x0000ffff0000ffffL, y & 0x0000ffff0000ffffL, 16);
            default -> clmul32(x >>> 32, y >>> 32) ^ clmul32(x & 0xffffffffL, y & 0xffffffffL);
        };
    }

    /** vclzlsbb / vctzlsbb: count leading/trailing bytes whose least-significant bit is 0. */
    static void lsbb(CpuState s, int insn, boolean trailing) {
        checkVec(s);
        int b = 32 + rb(insn);
        long hi = s.vsrHi[b] & 0x0101010101010101L, lo = s.vsrLo[b] & 0x0101010101010101L;
        int n;
        if (trailing) {
            n = Long.numberOfTrailingZeros(lo) >>> 3;
            if (n == 8) n += Long.numberOfTrailingZeros(hi) >>> 3;
        } else {
            n = Long.numberOfLeadingZeros(hi) >>> 3;
            if (n == 8) n += Long.numberOfLeadingZeros(lo) >>> 3;
        }
        s.gpr[rt(insn)] = n;
    }

    // ------------------------------------------------------------------ compares

    /** Lanes where none of x, y is zero and x == y, inverted: vcmpnez. */
    static long nez(long x, long y, int w) {
        return fill((~eq(x, y, w) & top(w)) | eq(x, 0, w) | eq(y, 0, w), w);
    }

    /** Vector compare: f gives each doubleword's lane masks; the record form sets CR6. */
    static void vcmp(CpuState s, int insn, L2 f) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long rh = f.f(s.vsrHi[a], s.vsrHi[b]), rl = f.f(s.vsrLo[a], s.vsrLo[b]);
        set(s, 32 + rt(insn), rh, rl);
        if ((insn & 0x400) != 0) {
            s.setCrField(6, ((rh & rl) == -1L ? 8 : 0) | ((rh | rl) == 0 ? 2 : 0));
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

    /** vcipher / vcipherlast: ShiftRows (row r of column c from column c + r), SubBytes, MixColumns. */
    static void vcipher(CpuState s, int insn, boolean last) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long hi = s.vsrHi[a], lo = s.vsrLo[a];
        int c0 = (int) (hi >>> 32), c1 = (int) hi, c2 = (int) (lo >>> 32), c3 = (int) lo;
        long rh, rl;
        if (last) {
            rh = words(Aes.sub(c0, c1, c2, c3), Aes.sub(c1, c2, c3, c0));
            rl = words(Aes.sub(c2, c3, c0, c1), Aes.sub(c3, c0, c1, c2));
        } else {
            rh = words(Aes.mix(c0, c1, c2, c3), Aes.mix(c1, c2, c3, c0));
            rl = words(Aes.mix(c2, c3, c0, c1), Aes.mix(c3, c0, c1, c2));
        }
        set(s, 32 + rt(insn), rh ^ s.vsrHi[b], rl ^ s.vsrLo[b]);
    }

    /**
     * vncipher / vncipherlast: InvShiftRows (row r of column c from column c - r), InvSubBytes,
     * the round key, and (not last) InvMixColumns.
     */
    static void vncipher(CpuState s, int insn, boolean last) {
        checkVec(s);
        int a = 32 + ra(insn), b = 32 + rb(insn);
        long hi = s.vsrHi[a], lo = s.vsrLo[a];
        int c0 = (int) (hi >>> 32), c1 = (int) hi, c2 = (int) (lo >>> 32), c3 = (int) lo;
        long rh = words(Aes.invSub(c0, c3, c2, c1), Aes.invSub(c1, c0, c3, c2)) ^ s.vsrHi[b];
        long rl = words(Aes.invSub(c2, c1, c0, c3), Aes.invSub(c3, c2, c1, c0)) ^ s.vsrLo[b];
        if (!last) {
            rh = words(Aes.invMix((int) (rh >>> 32)), Aes.invMix((int) rh));
            rl = words(Aes.invMix((int) (rl >>> 32)), Aes.invMix((int) rl));
        }
        set(s, 32 + rt(insn), rh, rl);
    }

    static void vsbox(CpuState s, int insn) {
        checkVec(s);
        int a = 32 + ra(insn);
        long hi = s.vsrHi[a], lo = s.vsrLo[a];
        int c0 = (int) (hi >>> 32), c1 = (int) hi, c2 = (int) (lo >>> 32), c3 = (int) lo;
        set(s, 32 + rt(insn), words(Aes.sub(c0, c0, c0, c0), Aes.sub(c1, c1, c1, c1)),
                words(Aes.sub(c2, c2, c2, c2), Aes.sub(c3, c3, c3, c3)));
    }

    static void vshasigma(CpuState s, int insn, boolean dw) {
        checkVec(s);
        int a = 32 + ra(insn);
        boolean st = ((insn >>> 15) & 1) != 0;
        int six = (insn >>> 11) & 15;
        long hi = s.vsrHi[a], lo = s.vsrLo[a];
        if (dw) {
            set(s, 32 + rt(insn), sigma64(hi, st, (six & 8) != 0), sigma64(lo, st, (six & 2) != 0));
            return;
        }
        if (six == 0 || six == 15) {
            // What code uses: one function for all four words.
            set(s, 32 + rt(insn), sigma32x2(hi, st, six != 0), sigma32x2(lo, st, six != 0));
        } else {
            set(s, 32 + rt(insn), words(sigma32((int) (hi >>> 32), st, (six & 8) != 0), sigma32((int) hi, st, (six & 4) != 0)),
                    words(sigma32((int) (lo >>> 32), st, (six & 2) != 0), sigma32((int) lo, st, (six & 1) != 0)));
        }
    }

    /** sigma32 of both words of x. */
    static long sigma32x2(long x, boolean st, boolean bit) {
        return words(sigma32((int) (x >>> 32), st, bit), sigma32((int) x, st, bit));
    }

    /** SHA-256 sigma0/sigma1 (st false) or Sigma0/Sigma1 (st true). */
    static int sigma32(int x, boolean st, boolean bit) {
        if (!st) {
            return !bit ? Integer.rotateRight(x, 7) ^ Integer.rotateRight(x, 18) ^ (x >>> 3)
                    : Integer.rotateRight(x, 17) ^ Integer.rotateRight(x, 19) ^ (x >>> 10);
        }
        return !bit ? Integer.rotateRight(x, 2) ^ Integer.rotateRight(x, 13) ^ Integer.rotateRight(x, 22)
                : Integer.rotateRight(x, 6) ^ Integer.rotateRight(x, 11) ^ Integer.rotateRight(x, 25);
    }

    static long sigma64(long x, boolean st, boolean bit) {
        if (!st) {
            return !bit ? Long.rotateRight(x, 1) ^ Long.rotateRight(x, 8) ^ (x >>> 7)
                    : Long.rotateRight(x, 19) ^ Long.rotateRight(x, 61) ^ (x >>> 6);
        }
        return !bit ? Long.rotateRight(x, 28) ^ Long.rotateRight(x, 34) ^ Long.rotateRight(x, 39)
                : Long.rotateRight(x, 14) ^ Long.rotateRight(x, 18) ^ Long.rotateRight(x, 41);
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
        // Lane operations are written out per width so that each is a constant lambda (see the
        // class comment); sfx and the loops are for the instructions that take the width as data.
        String[] sfx = {"b", "h", "w", "d"};
        // ---- modulo add/sub
        vx(t, 0, "vaddubm", (s, i) -> dw2(s, i, (x, y) -> add(x, y, 8)));
        vx(t, 1024, "vsububm", (s, i) -> dw2(s, i, (x, y) -> sub(x, y, 8)));
        vx(t, 64, "vadduhm", (s, i) -> dw2(s, i, (x, y) -> add(x, y, 16)));
        vx(t, 1088, "vsubuhm", (s, i) -> dw2(s, i, (x, y) -> sub(x, y, 16)));
        vx(t, 128, "vadduwm", (s, i) -> dw2(s, i, (x, y) -> add(x, y, 32)));
        vx(t, 1152, "vsubuwm", (s, i) -> dw2(s, i, (x, y) -> sub(x, y, 32)));
        vx(t, 192, "vaddudm", (s, i) -> dw2(s, i, Long::sum));
        vx(t, 1216, "vsubudm", (s, i) -> dw2(s, i, (x, y) -> x - y));
        // ---- saturating add/sub (b, h, w)
        for (int k = 0; k < 3; k++) {
            int w = 8 << k;
            vx(t, 512 + 64 * k, "vaddu" + sfx[k] + "s", (s, i) -> addSat(s, i, w, false, false));
            vx(t, 768 + 64 * k, "vadds" + sfx[k] + "s", (s, i) -> addSat(s, i, w, true, false));
            vx(t, 1536 + 64 * k, "vsubu" + sfx[k] + "s", (s, i) -> addSat(s, i, w, false, true));
            vx(t, 1792 + 64 * k, "vsubs" + sfx[k] + "s", (s, i) -> addSat(s, i, w, true, true));
        }
        vx(t, 384, "vaddcuw", (s, i) -> dw2(s, i, (x, y) -> ovAddU(x, y, add(x, y, 32), 32) >>> 31));
        vx(t, 1408, "vsubcuw", (s, i) -> dw2(s, i, (x, y) -> (~ltu(x, y, 32) & H32) >>> 31));
        // ---- quadword arithmetic
        vx(t, 256, "vadduqm", (s, i) -> addq(s, i, false, false, false));
        vx(t, 320, "vaddcuq", (s, i) -> addq(s, i, false, false, true));
        vx(t, 1280, "vsubuqm", (s, i) -> addq(s, i, true, false, false));
        vx(t, 1344, "vsubcuq", (s, i) -> addq(s, i, true, false, true));
        va(t, 60, "vaddeuqm", (s, i) -> addq(s, i, false, true, false));
        va(t, 61, "vaddecuq", (s, i) -> addq(s, i, false, true, true));
        va(t, 62, "vsubeuqm", (s, i) -> addq(s, i, true, true, false));
        va(t, 63, "vsubecuq", (s, i) -> addq(s, i, true, true, true));
        // ---- min / max / avg / absdiff
        vx(t, 2, "vmaxub", (s, i) -> dw2(s, i, (x, y) -> maxu(x, y, 8)));
        vx(t, 258, "vmaxsb", (s, i) -> dw2(s, i, (x, y) -> maxs(x, y, 8)));
        vx(t, 514, "vminub", (s, i) -> dw2(s, i, (x, y) -> minu(x, y, 8)));
        vx(t, 770, "vminsb", (s, i) -> dw2(s, i, (x, y) -> mins(x, y, 8)));
        vx(t, 66, "vmaxuh", (s, i) -> dw2(s, i, (x, y) -> maxu(x, y, 16)));
        vx(t, 322, "vmaxsh", (s, i) -> dw2(s, i, (x, y) -> maxs(x, y, 16)));
        vx(t, 578, "vminuh", (s, i) -> dw2(s, i, (x, y) -> minu(x, y, 16)));
        vx(t, 834, "vminsh", (s, i) -> dw2(s, i, (x, y) -> mins(x, y, 16)));
        vx(t, 130, "vmaxuw", (s, i) -> dw2(s, i, (x, y) -> maxu(x, y, 32)));
        vx(t, 386, "vmaxsw", (s, i) -> dw2(s, i, (x, y) -> maxs(x, y, 32)));
        vx(t, 642, "vminuw", (s, i) -> dw2(s, i, (x, y) -> minu(x, y, 32)));
        vx(t, 898, "vminsw", (s, i) -> dw2(s, i, (x, y) -> mins(x, y, 32)));
        vx(t, 194, "vmaxud", (s, i) -> dw2(s, i, (x, y) -> Long.compareUnsigned(x, y) > 0 ? x : y));
        vx(t, 450, "vmaxsd", (s, i) -> dw2(s, i, Math::max));
        vx(t, 706, "vminud", (s, i) -> dw2(s, i, (x, y) -> Long.compareUnsigned(x, y) < 0 ? x : y));
        vx(t, 962, "vminsd", (s, i) -> dw2(s, i, Math::min));
        vx(t, 1026, "vavgub", (s, i) -> dw2(s, i, (x, y) -> avgu(x, y, 8)));
        vx(t, 1282, "vavgsb", (s, i) -> dw2(s, i, (x, y) -> avgs(x, y, 8)));
        vx(t, 1027, "vabsdub", (s, i) -> dw2(s, i, (x, y) -> absdu(x, y, 8)));
        vx(t, 1090, "vavguh", (s, i) -> dw2(s, i, (x, y) -> avgu(x, y, 16)));
        vx(t, 1346, "vavgsh", (s, i) -> dw2(s, i, (x, y) -> avgs(x, y, 16)));
        vx(t, 1091, "vabsduh", (s, i) -> dw2(s, i, (x, y) -> absdu(x, y, 16)));
        vx(t, 1154, "vavguw", (s, i) -> dw2(s, i, (x, y) -> avgu(x, y, 32)));
        vx(t, 1410, "vavgsw", (s, i) -> dw2(s, i, (x, y) -> avgs(x, y, 32)));
        vx(t, 1155, "vabsduw", (s, i) -> dw2(s, i, (x, y) -> absdu(x, y, 32)));
        // ---- multiply
        for (int k = 0; k < 3; k++) {
            int sz = 1 << k;
            vx(t, 8 + 64 * k, "vmulou" + sfx[k], (s, i) -> mulEO(s, i, sz, false, true));
            vx(t, 264 + 64 * k, "vmulos" + sfx[k], (s, i) -> mulEO(s, i, sz, true, true));
            vx(t, 520 + 64 * k, "vmuleu" + sfx[k], (s, i) -> mulEO(s, i, sz, false, false));
            vx(t, 776 + 64 * k, "vmules" + sfx[k], (s, i) -> mulEO(s, i, sz, true, false));
        }
        vx(t, 137, "vmuluwm", (s, i) -> dw2(s, i, VmxInsns::mul32));
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
        vx(t, 1028, "vand", (s, i) -> dw2(s, i, (x, y) -> x & y));
        vx(t, 1092, "vandc", (s, i) -> dw2(s, i, (x, y) -> x & ~y));
        vx(t, 1156, "vor", (s, i) -> dw2(s, i, (x, y) -> x | y));
        vx(t, 1220, "vxor", (s, i) -> dw2(s, i, (x, y) -> x ^ y));
        vx(t, 1284, "vnor", (s, i) -> dw2(s, i, (x, y) -> ~(x | y)));
        vx(t, 1348, "vorc", (s, i) -> dw2(s, i, (x, y) -> x | ~y));
        vx(t, 1412, "vnand", (s, i) -> dw2(s, i, (x, y) -> ~(x & y)));
        vx(t, 1668, "veqv", (s, i) -> dw2(s, i, (x, y) -> ~(x ^ y)));
        va(t, 42, "vsel", VmxInsns::vsel);
        // ---- shifts and rotates (8- and 16-bit lanes bit-serially, words and doublewords directly)
        vx(t, 4, "vrlb", (s, i) -> dw2(s, i, (x, y) -> rlv(x, y, 8)));
        vx(t, 68, "vrlh", (s, i) -> dw2(s, i, (x, y) -> rlv(x, y, 16)));
        vx(t, 132, "vrlw", (s, i) -> dw2(s, i, VmxInsns::rl32));
        vx(t, 196, "vrld", (s, i) -> dw2(s, i, (x, y) -> Long.rotateLeft(x, (int) y)));
        vx(t, 260, "vslb", (s, i) -> dw2(s, i, (x, y) -> shlv(x, y, 8)));
        vx(t, 516, "vsrb", (s, i) -> dw2(s, i, (x, y) -> shrv(x, y, 8)));
        vx(t, 772, "vsrab", (s, i) -> dw2(s, i, (x, y) -> srav(x, y, 8)));
        vx(t, 324, "vslh", (s, i) -> dw2(s, i, (x, y) -> shlv(x, y, 16)));
        vx(t, 580, "vsrh", (s, i) -> dw2(s, i, (x, y) -> shrv(x, y, 16)));
        vx(t, 836, "vsrah", (s, i) -> dw2(s, i, (x, y) -> srav(x, y, 16)));
        vx(t, 388, "vslw", (s, i) -> dw2(s, i, VmxInsns::sl32));
        vx(t, 644, "vsrw", (s, i) -> dw2(s, i, VmxInsns::sr32));
        vx(t, 900, "vsraw", (s, i) -> dw2(s, i, VmxInsns::sra32));
        vx(t, 1476, "vsld", (s, i) -> dw2(s, i, (x, y) -> x << y));
        vx(t, 1732, "vsrd", (s, i) -> dw2(s, i, (x, y) -> x >>> y));
        vx(t, 964, "vsrad", (s, i) -> dw2(s, i, (x, y) -> x >> y));
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
        vxa(t, 1794, 0, "vclzb", (s, i) -> dw1(s, i, x -> clzLanes(x, 8)));
        vxa(t, 1795, 0, "vpopcntb", (s, i) -> dw1(s, i, x -> popcnt(x, 8)));
        vxa(t, 1538, 28, "vctzb", (s, i) -> dw1(s, i, x -> ctzLanes(x, 8)));
        vxa(t, 1858, 0, "vclzh", (s, i) -> dw1(s, i, x -> clzLanes(x, 16)));
        vxa(t, 1859, 0, "vpopcnth", (s, i) -> dw1(s, i, x -> popcnt(x, 16)));
        vxa(t, 1538, 29, "vctzh", (s, i) -> dw1(s, i, x -> ctzLanes(x, 16)));
        vxa(t, 1922, 0, "vclzw", (s, i) -> dw1(s, i,
                x -> words(Integer.numberOfLeadingZeros((int) (x >>> 32)), Integer.numberOfLeadingZeros((int) x))));
        // Not Integer.bitCount on the two words: JDK 21's C2 on aarch64 (21.0.13) got the upper
        // word wrong in this handler, though C1, JDK 27 and x86 are right.
        vxa(t, 1923, 0, "vpopcntw", (s, i) -> dw1(s, i, x -> popcnt(x, 32)));
        vxa(t, 1538, 30, "vctzw", (s, i) -> dw1(s, i,
                x -> words(Integer.numberOfTrailingZeros((int) (x >>> 32)), Integer.numberOfTrailingZeros((int) x))));
        vxa(t, 1986, 0, "vclzd", (s, i) -> dw1(s, i, x -> Long.numberOfLeadingZeros(x)));
        vxa(t, 1987, 0, "vpopcntd", (s, i) -> dw1(s, i, x -> Long.bitCount(x)));
        vxa(t, 1538, 31, "vctzd", (s, i) -> dw1(s, i, x -> Long.numberOfTrailingZeros(x)));
        vxa(t, 1538, 0, "vclzlsbb", (s, i) -> lsbb(s, i, false));
        vxa(t, 1538, 1, "vctzlsbb", (s, i) -> lsbb(s, i, true));
        vxa(t, 1538, 6, "vnegw", (s, i) -> dw1(s, i, x -> sub(0, x, 32)));
        vxa(t, 1538, 7, "vnegd", (s, i) -> dw1(s, i, x -> -x));
        vxa(t, 1538, 8, "vprtybw", (s, i) -> dw1(s, i, VmxInsns::prtybw));
        vxa(t, 1538, 9, "vprtybd", (s, i) -> dw1(s, i, VmxInsns::prtybd));
        vxa(t, 1538, 10, "vprtybq", (s, i) -> {
            checkVec(s);
            int b = 32 + rb(i);
            set(s, 32 + rt(i), 0, prtybd(s.vsrHi[b]) ^ prtybd(s.vsrLo[b]));
        });
        vxa(t, 1538, 16, "vextsb2w", (s, i) -> dw1(s, i, x -> words((byte) (x >>> 32), (byte) x)));
        vxa(t, 1538, 17, "vextsh2w", (s, i) -> dw1(s, i, x -> words((short) (x >>> 32), (short) x)));
        vxa(t, 1538, 24, "vextsb2d", (s, i) -> dw1(s, i, x -> (byte) x));
        vxa(t, 1538, 25, "vextsh2d", (s, i) -> dw1(s, i, x -> (short) x));
        vxa(t, 1538, 26, "vextsw2d", (s, i) -> dw1(s, i, x -> (int) x));
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
        // ---- integer compares (lane masks; doublewords compared directly)
        vc(t, 6, "vcmpequb", (s, i) -> vcmp(s, i, (x, y) -> fill(eq(x, y, 8), 8)));
        vc(t, 518, "vcmpgtub", (s, i) -> vcmp(s, i, (x, y) -> fill(ltu(y, x, 8), 8)));
        vc(t, 774, "vcmpgtsb", (s, i) -> vcmp(s, i, (x, y) -> fill(lts(y, x, 8), 8)));
        vc(t, 70, "vcmpequh", (s, i) -> vcmp(s, i, (x, y) -> fill(eq(x, y, 16), 16)));
        vc(t, 582, "vcmpgtuh", (s, i) -> vcmp(s, i, (x, y) -> fill(ltu(y, x, 16), 16)));
        vc(t, 838, "vcmpgtsh", (s, i) -> vcmp(s, i, (x, y) -> fill(lts(y, x, 16), 16)));
        vc(t, 134, "vcmpequw", (s, i) -> vcmp(s, i, (x, y) -> fill(eq(x, y, 32), 32)));
        vc(t, 646, "vcmpgtuw", (s, i) -> vcmp(s, i, (x, y) -> fill(ltu(y, x, 32), 32)));
        vc(t, 902, "vcmpgtsw", (s, i) -> vcmp(s, i, (x, y) -> fill(lts(y, x, 32), 32)));
        vc(t, 199, "vcmpequd", (s, i) -> vcmp(s, i, (x, y) -> x == y ? -1L : 0));
        vc(t, 711, "vcmpgtud", (s, i) -> vcmp(s, i, (x, y) -> Long.compareUnsigned(x, y) > 0 ? -1L : 0));
        vc(t, 967, "vcmpgtsd", (s, i) -> vcmp(s, i, (x, y) -> x > y ? -1L : 0));
        vc(t, 7, "vcmpneb", (s, i) -> vcmp(s, i, (x, y) -> ~fill(eq(x, y, 8), 8)));
        vc(t, 263, "vcmpnezb", (s, i) -> vcmp(s, i, (x, y) -> nez(x, y, 8)));
        vc(t, 71, "vcmpneh", (s, i) -> vcmp(s, i, (x, y) -> ~fill(eq(x, y, 16), 16)));
        vc(t, 327, "vcmpnezh", (s, i) -> vcmp(s, i, (x, y) -> nez(x, y, 16)));
        vc(t, 135, "vcmpnew", (s, i) -> vcmp(s, i, (x, y) -> ~fill(eq(x, y, 32), 32)));
        vc(t, 391, "vcmpnezw", (s, i) -> vcmp(s, i, (x, y) -> nez(x, y, 32)));
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
        vx(t, 1288, "vcipher", (s, i) -> vcipher(s, i, false));
        vx(t, 1289, "vcipherlast", (s, i) -> vcipher(s, i, true));
        vx(t, 1352, "vncipher", (s, i) -> vncipher(s, i, false));
        vx(t, 1353, "vncipherlast", (s, i) -> vncipher(s, i, true));
        t.add(0xfc00ffff, (4 << 26) | 1480, "vsbox", VmxInsns::vsbox);
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
