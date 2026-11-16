package org.boxtheta.javapower.fpvec;

/**
 * Lane arithmetic on a doubleword holding 8-, 16- or 32-bit elements (SIMD within a register),
 * for the VMX integer instructions, which apply it to both halves of a register. {@code w} is the
 * lane width in bits; callers pass a constant, so the lane masks fold.
 *
 * <p>Predicates ({@link #ltu}, {@link #lts}, {@link #eq}, the overflow tests) return only the top
 * bit of each lane where they hold; {@link #fill} widens that to the whole lane.
 */
final class Swar {
    private Swar() {}

    static final long H8 = 0x8080808080808080L, H16 = 0x8000800080008000L, H32 = 0x8000000080000000L;

    /** The top bit of every lane. */
    static long top(int w) {
        return w == 8 ? H8 : w == 16 ? H16 : H32;
    }

    /** The bottom bit of every lane. */
    static long lsb(int w) {
        return top(w) >>> (w - 1);
    }

    /** Whole lanes where {@code m} (top bits only) is set. */
    static long fill(long m, int w) {
        return m | (m - (m >>> (w - 1)));
    }

    /** x + y in each lane, modulo the lane width. */
    static long add(long x, long y, int w) {
        long h = top(w);
        return ((x & ~h) + (y & ~h)) ^ ((x ^ y) & h);
    }

    /** x - y in each lane, modulo the lane width. */
    static long sub(long x, long y, int w) {
        long h = top(w);
        return ((x | h) - (y & ~h)) ^ ((x ^ ~y) & h);
    }

    /** Lanes where x &lt; y, unsigned: the borrow out of each lane of x - y. */
    static long ltu(long x, long y, int w) {
        long d = sub(x, y, w);
        return ((~x & y) | (~(x ^ y) & d)) & top(w);
    }

    /** Lanes where x &lt; y, signed. */
    static long lts(long x, long y, int w) {
        long h = top(w);
        return ltu(x ^ h, y ^ h, w);
    }

    /** Lanes where x == y. */
    static long eq(long x, long y, int w) {
        long h = top(w), v = x ^ y;
        return ~(((v & ~h) + ~h) | v) & h;
    }

    static long minu(long x, long y, int w) {
        long m = fill(ltu(x, y, w), w);
        return (x & m) | (y & ~m);
    }

    static long maxu(long x, long y, int w) {
        long m = fill(ltu(x, y, w), w);
        return (y & m) | (x & ~m);
    }

    static long mins(long x, long y, int w) {
        long m = fill(lts(x, y, w), w);
        return (x & m) | (y & ~m);
    }

    static long maxs(long x, long y, int w) {
        long m = fill(lts(x, y, w), w);
        return (y & m) | (x & ~m);
    }

    /** (x + y + 1) >> 1 in each lane, unsigned: (x | y) - ((x ^ y) >> 1) never borrows. */
    static long avgu(long x, long y, int w) {
        return (x | y) - (((x ^ y) >>> 1) & ~top(w));
    }

    /** (x + y + 1) >> 1 in each lane, signed: the unsigned average of the biased values. */
    static long avgs(long x, long y, int w) {
        long h = top(w);
        return avgu(x ^ h, y ^ h, w) ^ h;
    }

    static long absdu(long x, long y, int w) {
        return sub(maxu(x, y, w), minu(x, y, w), w);
    }

    // ------------------------------------------------------------------ saturation

    /** Lanes where the unsigned x + y (wrapped result r) carried out. */
    static long ovAddU(long x, long y, long r, int w) {
        return ((x & y) | ((x | y) & ~r)) & top(w);
    }

    /** Lanes where the unsigned x - y (wrapped result r) borrowed. */
    static long ovSubU(long x, long y, long r, int w) {
        return ((~x & y) | (~(x ^ y) & r)) & top(w);
    }

    /** Lanes where the signed x + y (wrapped result r) overflowed. */
    static long ovAddS(long x, long y, long r, int w) {
        return (r ^ x) & (r ^ y) & top(w);
    }

    /** Lanes where the signed x - y (wrapped result r) overflowed. */
    static long ovSubS(long x, long y, long r, int w) {
        return (x ^ y) & (x ^ r) & top(w);
    }

    /** r with the lanes in {@code ov} replaced by the signed limit on the side of x's sign. */
    static long limitS(long r, long x, long ov, int w) {
        long h = top(w), m = fill(ov, w);
        long limit = ~h ^ fill(x & h, w); // MAX where x >= 0, MIN where x < 0
        return (r & ~m) | (limit & m);
    }

    // ------------------------------------------------------------------ per-lane shifts (8, 16)

    /** x << (n mod w) in each lane, n from the same lane of y; shifted by 1, 2, 4 (and 8). */
    static long shlv(long x, long y, int w) {
        long l = lsb(w), lane = (1L << w) - 1;
        for (int k = 1; k < w; k <<= 1) {
            long m = (y & l) * lane;
            long sh = (x << k) & ~(l * ((1L << k) - 1));
            x ^= (x ^ sh) & m;
            y >>>= 1;
        }
        return x;
    }

    /** x >>> (n mod w) in each lane. */
    static long shrv(long x, long y, int w) {
        long l = lsb(w), lane = (1L << w) - 1;
        for (int k = 1; k < w; k <<= 1) {
            long m = (y & l) * lane;
            long sh = (x >>> k) & ~(l * (((1L << k) - 1) << (w - k)));
            x ^= (x ^ sh) & m;
            y >>>= 1;
        }
        return x;
    }

    /** x >> (n mod w) in each lane: a logical shift of x with negative lanes complemented. */
    static long srav(long x, long y, int w) {
        long s = fill(x & top(w), w);
        return shrv(x ^ s, y, w) ^ s;
    }

    /** x rotated left by (n mod w) in each lane. */
    static long rlv(long x, long y, int w) {
        long l = lsb(w), lane = (1L << w) - 1;
        for (int k = 1; k < w; k <<= 1) {
            long m = (y & l) * lane;
            long low = l * ((1L << k) - 1);
            long rot = ((x << k) & ~low) | ((x >>> (w - k)) & low);
            x ^= (x ^ rot) & m;
            y >>>= 1;
        }
        return x;
    }

    /** Population count of each 8-, 16- or 32-bit lane. */
    static long popcnt(long x, int w) {
        x = x - ((x >>> 1) & 0x5555555555555555L);
        x = (x & 0x3333333333333333L) + ((x >>> 2) & 0x3333333333333333L);
        x = (x + (x >>> 4)) & 0x0f0f0f0f0f0f0f0fL;
        if (w == 8) return x;
        x = (x + (x >>> 8)) & 0x00ff00ff00ff00ffL;
        return w == 16 ? x : (x + (x >>> 16)) & 0x0000ffff0000ffffL;
    }

    // ------------------------------------------------------------------ carry-less multiply

    /**
     * Carry-less products of the w-bit values (w = 8 or 16) in the low halves of the 2w-bit lanes
     * of x and y, one partial product per bit of y, all lanes at once.
     */
    static long clmulLanes(long x, long y, int w) {
        long l = w == 8 ? 0x0001000100010001L : 0x0000000100000001L;
        long lane = (1L << (2 * w)) - 1;
        long r = 0;
        for (int i = 0; i < w; i++) {
            r ^= (x << i) & (((y >>> i) & l) * lane);
        }
        return r;
    }

    /**
     * 32 x 32 -> 64-bit carry-less product from four-way interleaved integer multiplies: with
     * operand bits three apart, at most eight partial products land on any bit of one residue
     * class mod 4, and their carries never reach the next bit of that class, so the bit is the
     * parity of the count.
     */
    static long clmul32(long x, long y) {
        long x0 = x & 0x11111111L, x1 = x & 0x22222222L, x2 = x & 0x44444444L, x3 = x & 0x88888888L;
        long y0 = y & 0x11111111L, y1 = y & 0x22222222L, y2 = y & 0x44444444L, y3 = y & 0x88888888L;
        long z0 = (x0 * y0) ^ (x1 * y3) ^ (x2 * y2) ^ (x3 * y1);
        long z1 = (x0 * y1) ^ (x1 * y0) ^ (x2 * y3) ^ (x3 * y2);
        long z2 = (x0 * y2) ^ (x1 * y1) ^ (x2 * y0) ^ (x3 * y3);
        long z3 = (x0 * y3) ^ (x1 * y2) ^ (x2 * y1) ^ (x3 * y0);
        return (z0 & 0x1111111111111111L) | (z1 & 0x2222222222222222L) | (z2 & 0x4444444444444444L)
                | (z3 & 0x8888888888888888L);
    }
}
