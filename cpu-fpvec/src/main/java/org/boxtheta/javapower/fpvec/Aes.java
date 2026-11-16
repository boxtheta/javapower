package org.boxtheta.javapower.fpvec;

/**
 * AES round primitives on the state in VR byte order: byte i is row i%4 of column i/4, so column c
 * is word c of the register with row 0 in its most significant byte.
 *
 * <p>The round functions take the four columns the output column's rows come from (ShiftRows
 * or InvShiftRows is the caller's choice of columns) and use tables that combine SubBytes with
 * MixColumns ({@link #TE}) and the InvMixColumns of one byte ({@link #TD}), the classic 32-bit
 * table implementation; a row other than 0 is the row-0 entry rotated.
 */
final class Aes {
    private Aes() {}

    static final int[] SBOX = new int[256];
    static final int[] INV = new int[256];
    /** MixColumns column of SubBytes(x) in row 0: (2s, s, s, 3s), row 0 in the top byte. */
    static final int[] TE = new int[256];
    /** InvMixColumns column of x in row 0: (14x, 9x, 13x, 11x). */
    static final int[] TD = new int[256];

    static {
        // Generate the S-box from the multiplicative inverse in GF(2^8) and the affine map.
        int p = 1, q = 1;
        do {
            p = p ^ ((p << 1) & 0xff) ^ ((p & 0x80) != 0 ? 0x1b : 0);
            q ^= q << 1;
            q ^= q << 2;
            q ^= q << 4;
            q &= 0xff;
            if ((q & 0x80) != 0) q ^= 0x09;
            int x = q ^ rotl8(q, 1) ^ rotl8(q, 2) ^ rotl8(q, 3) ^ rotl8(q, 4);
            SBOX[p] = (x ^ 0x63) & 0xff;
        } while (p != 1);
        SBOX[0] = 0x63;
        for (int i = 0; i < 256; i++) INV[SBOX[i]] = i;
        for (int x = 0; x < 256; x++) {
            int s = SBOX[x];
            TE[x] = (xtime(s) << 24) | (s << 16) | (s << 8) | (xtime(s) ^ s);
            TD[x] = (mul(x, 14) << 24) | (mul(x, 9) << 16) | (mul(x, 13) << 8) | mul(x, 11);
        }
    }

    private static int rotl8(int x, int n) {
        return ((x << n) | (x >>> (8 - n))) & 0xff;
    }

    static int xtime(int x) {
        return ((x << 1) ^ ((x & 0x80) != 0 ? 0x1b : 0)) & 0xff;
    }

    static int mul(int a, int b) {
        int r = 0;
        while (b != 0) {
            if ((b & 1) != 0) r ^= a;
            a = xtime(a);
            b >>>= 1;
        }
        return r;
    }

    /** MixColumns(SubBytes) of the column whose row r comes from row r of the r-th argument. */
    static int mix(int c0, int c1, int c2, int c3) {
        return TE[c0 >>> 24] ^ Integer.rotateRight(TE[(c1 >>> 16) & 0xff], 8)
                ^ Integer.rotateRight(TE[(c2 >>> 8) & 0xff], 16) ^ Integer.rotateRight(TE[c3 & 0xff], 24);
    }

    /** SubBytes of the column whose row r comes from row r of the r-th argument. */
    static int sub(int c0, int c1, int c2, int c3) {
        return (SBOX[c0 >>> 24] << 24) | (SBOX[(c1 >>> 16) & 0xff] << 16) | (SBOX[(c2 >>> 8) & 0xff] << 8)
                | SBOX[c3 & 0xff];
    }

    /** InvSubBytes of the column whose row r comes from row r of the r-th argument. */
    static int invSub(int c0, int c1, int c2, int c3) {
        return (INV[c0 >>> 24] << 24) | (INV[(c1 >>> 16) & 0xff] << 16) | (INV[(c2 >>> 8) & 0xff] << 8)
                | INV[c3 & 0xff];
    }

    static int invMix(int c) {
        return TD[c >>> 24] ^ Integer.rotateRight(TD[(c >>> 16) & 0xff], 8)
                ^ Integer.rotateRight(TD[(c >>> 8) & 0xff], 16) ^ Integer.rotateRight(TD[c & 0xff], 24);
    }
}
