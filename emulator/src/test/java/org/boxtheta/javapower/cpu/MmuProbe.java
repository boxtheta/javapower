package org.boxtheta.javapower.cpu;

/**
 * Test access to the parts of a {@link RadixMmu} its package keeps to itself, for tests of other
 * modules' instructions against the real MMU: the store guard of compiled blocks, the software
 * TLB, the walk count and the access type of the last machine check.
 */
public final class MmuProbe {
    private MmuProbe() {}

    /** Arm the store guard for real addresses [lo, hi) and clear its hit flag. */
    public static void guard(RadixMmu m, long lo, long hi) {
        m.guardLo = lo;
        m.guardHi = hi;
        m.guardHit = false;
    }

    public static boolean guardHit(RadixMmu m) {
        return m.guardHit;
    }

    /** A copy of the TLB (tags and addends) and the epochs. */
    public static long[] tlb(RadixMmu m) {
        long[] t = m.tlb.clone();
        long[] r = new long[t.length + m.epoch.length];
        System.arraycopy(t, 0, r, 0, t.length);
        for (int k = 0; k < m.epoch.length; k++) {
            r[t.length + k] = m.epoch[k];
        }
        return r;
    }

    public static long walks(RadixMmu m) {
        return m.walks;
    }

    public static int mcType(RadixMmu m) {
        return m.mcType;
    }
}
