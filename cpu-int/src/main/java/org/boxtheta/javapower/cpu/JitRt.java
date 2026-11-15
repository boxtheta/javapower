package org.boxtheta.javapower.cpu;

import org.boxtheta.javapower.core.PhysicalMemory;

/**
 * Small static helpers called by code {@link RegionCompiler} generates. They are written so the
 * JIT inlines them into the region's method: value computations whose operands are guest
 * registers held in JVM locals, loads and stores (the software TLB's hit path, then guest RAM
 * through {@link RamAccess}), and a region's rare exits.
 * Semantics mirror the instruction handlers ({@link Alu}, {@link LoadStore}) exactly.
 */
final class JitRt {
    private JitRt() {}

    private static final int TLB_MASK = (1 << RadixMmu.TLB_BITS) - 1;

    // ------------------------------------------------------------------ translation fast paths

    /**
     * The software TLB's hit path for a data access of {@code size} bytes at {@code ea} (already
     * truncated to the computation mode) with MSR[DR]=1: the real address, or -1 if the access
     * crosses a page or misses (the caller then takes the MMU's full path, which does exactly
     * what the interpreter would). {@code ctxBase} is 2 in problem state, else 0; {@code type}
     * is {@link RadixMmu#LOAD} or {@link RadixMmu#STORE}.
     *
     * <p>One comparison covers both: the tag is the page of the access's first byte, compared
     * with the page of its last. Indices are masked with the array lengths (powers of two, so
     * the masks change nothing), which lets the JIT drop the range checks.
     */
    static long xlate(long[] tlb, int[] epoch, long ea, int ctxBase, int type, int size) {
        int ctx = ctxBase | (int) (ea >>> 63);
        int e = ((((ctx * 3 + type) << RadixMmu.TLB_BITS) | ((int) (ea >>> 12) & TLB_MASK)) << 1) & (tlb.length - 1);
        if (tlb[e] != (((ea + (size - 1)) & ~0xfffL) | epoch[ctx & (epoch.length - 1)])) {
            return -1;
        }
        return tlb[(e + 1) & (tlb.length - 1)] + ea;
    }

    /** Real-mode (MSR[DR]=0) address of an access ending at or below {@code limit}, or -1. */
    static long real(long ea, long limit) {
        long pa = ea & 0x0FFFFFFFFFFFFFFFL;
        return pa > limit ? -1 : pa;
    }

    // ------------------------------------------------------------------ loads and stores
    //
    // A region's loads and stores, one method per size, byte order in memory (le, be) and
    // translation mode (the names ending in r are for real mode, MSR[DR]=0). ram is
    // RamAccess.backing, code the code page flags (PhysicalMemory.jitCodePages), ea is already
    // truncated, ctx is xlate's ctxBase, limit is the last real address at which the access still
    // fits in RAM, and swap says that the byte order is the other one than the guest's (the
    // byte-reversed instructions). mask is the RAM size minus one if that size is a power of
    // two, else -1: masked with it, a real address in RAM is unchanged, and the JIT knows the
    // access to be within the RAM's backing store (see PhysicalMemory.jitBacking) and makes it
    // without a bounds check.
    //
    // Everything a region cannot do directly goes to the MMU's full path, here rather than in the
    // generated code: the branch to it is then profiled once for all regions instead of once per
    // access of every region, where the JIT would take it for never taken until it first is.
    // For the same reason there is a method per mode and byte order rather than one that
    // branches on them: the JIT decides what to inline from how often each call in these methods
    // ran while they were profiled, which for a call made in one mode only would depend on the
    // modes the guest was in at the time.
    //
    // Loads return the value zero-extended (8, 16 bits) or sign-extended (32 bits). Stores
    // return whether the region must stop after the instruction, which is when the store went
    // into the region's own page (the MMU's store guard, set for the region by the run loop).
    // A store goes directly to RAM only outside pages that hold translated code and outside the
    // region's page (real address page); the MMU's path does the code-write tracking.

    /**
     * Whether a store at real address {@code pa} (in RAM) must take the MMU's path: see above.
     * {@code code} is {@link PhysicalMemory#jitCodePages}.
     */
    private static boolean tracked(byte[] code, long pa, long page) {
        return code[(int) (pa >>> 12) & (code.length - 1)] != 0 || ((pa ^ page) >>> 12) == 0;
    }

    static long ld8(Object ram, RadixMmu m, long[] tlb, int[] epoch, long ea, int ctx, long mask) {
        long pa = xlate(tlb, epoch, ea, ctx, RadixMmu.LOAD, 1);
        return pa == -1 ? loadSlow(m, ea, 1, false) : RamAccess.ld8(ram, pa & mask);
    }

    static long ld16le(Object ram, RadixMmu m, long[] tlb, int[] epoch, long ea, int ctx, long mask, boolean swap) {
        long pa = xlate(tlb, epoch, ea, ctx, RadixMmu.LOAD, 2);
        return pa == -1 ? loadSlow(m, ea, 2, swap) : RamAccess.ld16le(ram, pa & mask);
    }

    static long ld16be(Object ram, RadixMmu m, long[] tlb, int[] epoch, long ea, int ctx, long mask, boolean swap) {
        long pa = xlate(tlb, epoch, ea, ctx, RadixMmu.LOAD, 2);
        return pa == -1 ? loadSlow(m, ea, 2, swap) : RamAccess.ld16be(ram, pa & mask);
    }

    static long ld32le(Object ram, RadixMmu m, long[] tlb, int[] epoch, long ea, int ctx, long mask, boolean swap) {
        long pa = xlate(tlb, epoch, ea, ctx, RadixMmu.LOAD, 4);
        return pa == -1 ? loadSlow(m, ea, 4, swap) : RamAccess.ld32le(ram, pa & mask);
    }

    static long ld32be(Object ram, RadixMmu m, long[] tlb, int[] epoch, long ea, int ctx, long mask, boolean swap) {
        long pa = xlate(tlb, epoch, ea, ctx, RadixMmu.LOAD, 4);
        return pa == -1 ? loadSlow(m, ea, 4, swap) : RamAccess.ld32be(ram, pa & mask);
    }

    static long ld64le(Object ram, RadixMmu m, long[] tlb, int[] epoch, long ea, int ctx, long mask, boolean swap) {
        long pa = xlate(tlb, epoch, ea, ctx, RadixMmu.LOAD, 8);
        return pa == -1 ? loadSlow(m, ea, 8, swap) : RamAccess.ld64le(ram, pa & mask);
    }

    static long ld64be(Object ram, RadixMmu m, long[] tlb, int[] epoch, long ea, int ctx, long mask, boolean swap) {
        long pa = xlate(tlb, epoch, ea, ctx, RadixMmu.LOAD, 8);
        return pa == -1 ? loadSlow(m, ea, 8, swap) : RamAccess.ld64be(ram, pa & mask);
    }

    static boolean st8(Object ram, byte[] code, RadixMmu m, long[] tlb, int[] epoch, long ea, int ctx, long mask, long v,
            long page) {
        long pa = xlate(tlb, epoch, ea, ctx, RadixMmu.STORE, 1);
        if (pa == -1 || tracked(code, pa, page)) {
            return storeSlow(m, ea, v, 1, false);
        }
        RamAccess.st8(ram, pa & mask, v);
        return false;
    }

    static boolean st16le(Object ram, byte[] code, RadixMmu m, long[] tlb, int[] epoch, long ea, int ctx, long mask, long v,
            long page, boolean swap) {
        long pa = xlate(tlb, epoch, ea, ctx, RadixMmu.STORE, 2);
        if (pa == -1 || tracked(code, pa, page)) {
            return storeSlow(m, ea, v, 2, swap);
        }
        RamAccess.st16le(ram, pa & mask, v);
        return false;
    }

    static boolean st16be(Object ram, byte[] code, RadixMmu m, long[] tlb, int[] epoch, long ea, int ctx, long mask, long v,
            long page, boolean swap) {
        long pa = xlate(tlb, epoch, ea, ctx, RadixMmu.STORE, 2);
        if (pa == -1 || tracked(code, pa, page)) {
            return storeSlow(m, ea, v, 2, swap);
        }
        RamAccess.st16be(ram, pa & mask, v);
        return false;
    }

    static boolean st32le(Object ram, byte[] code, RadixMmu m, long[] tlb, int[] epoch, long ea, int ctx, long mask, long v,
            long page, boolean swap) {
        long pa = xlate(tlb, epoch, ea, ctx, RadixMmu.STORE, 4);
        if (pa == -1 || tracked(code, pa, page)) {
            return storeSlow(m, ea, v, 4, swap);
        }
        RamAccess.st32le(ram, pa & mask, v);
        return false;
    }

    static boolean st32be(Object ram, byte[] code, RadixMmu m, long[] tlb, int[] epoch, long ea, int ctx, long mask, long v,
            long page, boolean swap) {
        long pa = xlate(tlb, epoch, ea, ctx, RadixMmu.STORE, 4);
        if (pa == -1 || tracked(code, pa, page)) {
            return storeSlow(m, ea, v, 4, swap);
        }
        RamAccess.st32be(ram, pa & mask, v);
        return false;
    }

    static boolean st64le(Object ram, byte[] code, RadixMmu m, long[] tlb, int[] epoch, long ea, int ctx, long mask, long v,
            long page, boolean swap) {
        long pa = xlate(tlb, epoch, ea, ctx, RadixMmu.STORE, 8);
        if (pa == -1 || tracked(code, pa, page)) {
            return storeSlow(m, ea, v, 8, swap);
        }
        RamAccess.st64le(ram, pa & mask, v);
        return false;
    }

    static boolean st64be(Object ram, byte[] code, RadixMmu m, long[] tlb, int[] epoch, long ea, int ctx, long mask, long v,
            long page, boolean swap) {
        long pa = xlate(tlb, epoch, ea, ctx, RadixMmu.STORE, 8);
        if (pa == -1 || tracked(code, pa, page)) {
            return storeSlow(m, ea, v, 8, swap);
        }
        RamAccess.st64be(ram, pa & mask, v);
        return false;
    }

    static long ld8r(Object ram, RadixMmu m, long ea, long limit, long mask) {
        long pa = real(ea, limit);
        return pa == -1 ? loadSlow(m, ea, 1, false) : RamAccess.ld8(ram, pa & mask);
    }

    static long ld16ler(Object ram, RadixMmu m, long ea, long limit, long mask, boolean swap) {
        long pa = real(ea, limit);
        return pa == -1 ? loadSlow(m, ea, 2, swap) : RamAccess.ld16le(ram, pa & mask);
    }

    static long ld16ber(Object ram, RadixMmu m, long ea, long limit, long mask, boolean swap) {
        long pa = real(ea, limit);
        return pa == -1 ? loadSlow(m, ea, 2, swap) : RamAccess.ld16be(ram, pa & mask);
    }

    static long ld32ler(Object ram, RadixMmu m, long ea, long limit, long mask, boolean swap) {
        long pa = real(ea, limit);
        return pa == -1 ? loadSlow(m, ea, 4, swap) : RamAccess.ld32le(ram, pa & mask);
    }

    static long ld32ber(Object ram, RadixMmu m, long ea, long limit, long mask, boolean swap) {
        long pa = real(ea, limit);
        return pa == -1 ? loadSlow(m, ea, 4, swap) : RamAccess.ld32be(ram, pa & mask);
    }

    static long ld64ler(Object ram, RadixMmu m, long ea, long limit, long mask, boolean swap) {
        long pa = real(ea, limit);
        return pa == -1 ? loadSlow(m, ea, 8, swap) : RamAccess.ld64le(ram, pa & mask);
    }

    static long ld64ber(Object ram, RadixMmu m, long ea, long limit, long mask, boolean swap) {
        long pa = real(ea, limit);
        return pa == -1 ? loadSlow(m, ea, 8, swap) : RamAccess.ld64be(ram, pa & mask);
    }

    static boolean st8r(Object ram, byte[] code, RadixMmu m, long ea, long limit, long mask, long v,
            long page) {
        long pa = real(ea, limit);
        if (pa == -1 || tracked(code, pa, page)) {
            return storeSlow(m, ea, v, 1, false);
        }
        RamAccess.st8(ram, pa & mask, v);
        return false;
    }

    static boolean st16ler(Object ram, byte[] code, RadixMmu m, long ea, long limit, long mask, long v,
            long page, boolean swap) {
        long pa = real(ea, limit);
        if (pa == -1 || tracked(code, pa, page)) {
            return storeSlow(m, ea, v, 2, swap);
        }
        RamAccess.st16le(ram, pa & mask, v);
        return false;
    }

    static boolean st16ber(Object ram, byte[] code, RadixMmu m, long ea, long limit, long mask, long v,
            long page, boolean swap) {
        long pa = real(ea, limit);
        if (pa == -1 || tracked(code, pa, page)) {
            return storeSlow(m, ea, v, 2, swap);
        }
        RamAccess.st16be(ram, pa & mask, v);
        return false;
    }

    static boolean st32ler(Object ram, byte[] code, RadixMmu m, long ea, long limit, long mask, long v,
            long page, boolean swap) {
        long pa = real(ea, limit);
        if (pa == -1 || tracked(code, pa, page)) {
            return storeSlow(m, ea, v, 4, swap);
        }
        RamAccess.st32le(ram, pa & mask, v);
        return false;
    }

    static boolean st32ber(Object ram, byte[] code, RadixMmu m, long ea, long limit, long mask, long v,
            long page, boolean swap) {
        long pa = real(ea, limit);
        if (pa == -1 || tracked(code, pa, page)) {
            return storeSlow(m, ea, v, 4, swap);
        }
        RamAccess.st32be(ram, pa & mask, v);
        return false;
    }

    static boolean st64ler(Object ram, byte[] code, RadixMmu m, long ea, long limit, long mask, long v,
            long page, boolean swap) {
        long pa = real(ea, limit);
        if (pa == -1 || tracked(code, pa, page)) {
            return storeSlow(m, ea, v, 8, swap);
        }
        RamAccess.st64le(ram, pa & mask, v);
        return false;
    }

    static boolean st64ber(Object ram, byte[] code, RadixMmu m, long ea, long limit, long mask, long v,
            long page, boolean swap) {
        long pa = real(ea, limit);
        if (pa == -1 || tracked(code, pa, page)) {
            return storeSlow(m, ea, v, 8, swap);
        }
        RamAccess.st64be(ram, pa & mask, v);
        return false;
    }

    // The MMU's full access path (page crossing, TLB miss, faults, MMIO, tracked stores); ea is
    // already truncated. One method per direction, large enough that the JIT calls rather than
    // inlines them: they are rare, and inlined they would be most of a region's code.

    /** Load {@code size} bytes at {@code ea} in the guest's byte order, reversed with {@code swap}. */
    static long loadSlow(RadixMmu m, long ea, int size, boolean swap) {
        switch (size) {
            case 1:
                return m.read8(ea);
            case 2: {
                int v = m.read16(ea);
                return swap ? Integer.reverseBytes(v) >>> 16 : v;
            }
            case 4: {
                int v = m.read32(ea);
                return swap ? Integer.reverseBytes(v) : v;
            }
            default: {
                long v = m.read64(ea);
                return swap ? Long.reverseBytes(v) : v;
            }
        }
    }

    /**
     * Store the low {@code size} bytes of {@code v} at {@code ea} in the guest's byte order,
     * reversed with {@code swap}. Returns whether the store hit the MMU's store guard.
     */
    static boolean storeSlow(RadixMmu m, long ea, long v, int size, boolean swap) {
        switch (size) {
            case 1:
                m.write8(ea, (int) v);
                break;
            case 2:
                m.write16(ea, swap ? Integer.reverseBytes((int) v) >>> 16 : (int) v);
                break;
            case 4:
                m.write32(ea, swap ? Integer.reverseBytes((int) v) : (int) v);
                break;
            default:
                m.write64(ea, swap ? Long.reverseBytes(v) : v);
                break;
        }
        return m.guardHit;
    }

    // ------------------------------------------------------------------ leaving a region

    /**
     * Thrown by {@link #budget} and {@link #leaveIf} and caught by the region that called them,
     * which then leaves: its rare exits. There is one instance, without a stack trace. An
     * exception rather than a branch in the generated code for the reason given above for the
     * MMU's path: the JIT compiles a branch that a region has not taken yet as a trap, and
     * recompiles the region when it first is; the condition here is tested in one place for all
     * regions, and the JIT turns the throw into a jump to the region's handler.
     */
    static final class Leave extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Leave() {
            super(null, null, false, false);
        }
    }

    static final Leave LEAVE = new Leave();

    /** Leave unless a block of {@code len} instructions fits before the end of the chunk. */
    static void budget(long ic, long len, long end) {
        if (ic + len > end) {
            throw LEAVE;
        }
    }

    /** Leave after an instruction whose store hit the region's page. */
    static void leaveIf(boolean stop) {
        if (stop) {
            throw LEAVE;
        }
    }

    // ------------------------------------------------------------------ values

    /** CR with field 0 set from {@code r} as a 64-bit signed result, plus SO. */
    static int cr0(int cr, long r, int so) {
        return (cr & 0x0fffffff) | (((r < 0 ? 8 : r > 0 ? 4 : 2) | so) << 28);
    }

    /** CR with field 0 set from {@code r} as a 32-bit signed result (MSR[SF]=0), plus SO. */
    static int cr0w(int cr, long r, int so) {
        return cr0(cr, (int) r, so);
    }

    /** CR with field {@code bf} replaced by a signed comparison of {@code a} and {@code b}, plus SO. */
    static int cmp(int cr, int bf, long a, long b, int so) {
        int sh = (7 - bf) * 4;
        int v = (a < b ? 8 : a > b ? 4 : 2) | so;
        return (cr & ~(0xf << sh)) | (v << sh);
    }

    /** As {@link #cmp}, unsigned. */
    static int cmpl(int cr, int bf, long a, long b, int so) {
        int sh = (7 - bf) * 4;
        int c = Long.compareUnsigned(a, b);
        int v = (c < 0 ? 8 : c > 0 ? 4 : 2) | so;
        return (cr & ~(0xf << sh)) | (v << sh);
    }

    /** Carry out of bit 0 (64-bit) of a + b (+ cin) giving r. */
    static int ca64(long a, long b, long r) {
        return (int) (((a & b) | ((a | b) & ~r)) >>> 63);
    }

    /** Carry out of bit 32 (the 32-bit carry) of a + b (+ cin) giving r. */
    static int ca32(long a, long b, long r) {
        return (int) ((a ^ b ^ r) >>> 32) & 1;
    }

    static long rotl32(long v, int n) {
        long w = v & 0xffffffffL;
        w |= w << 32;
        return Long.rotateLeft(w, n);
    }

    static long slw(long rs, long rb) {
        int n = (int) rb & 0x3f;
        return n > 31 ? 0 : ((rs << n) & 0xffffffffL);
    }

    static long srw(long rs, long rb) {
        int n = (int) rb & 0x3f;
        return n > 31 ? 0 : ((rs & 0xffffffffL) >>> n);
    }

    static long sld(long rs, long rb) {
        int n = (int) rb & 0x7f;
        return n > 63 ? 0 : rs << n;
    }

    static long srd(long rs, long rb) {
        int n = (int) rb & 0x7f;
        return n > 63 ? 0 : rs >>> n;
    }

    /** sraw/srawi result for shift {@code n} (0..63). */
    static long sraw(long rs, int n) {
        int v = (int) rs;
        return n < 32 ? v >> n : v >> 31;
    }

    /** sraw/srawi carry. */
    static int srawCa(long rs, int n) {
        int v = (int) rs;
        if (n < 32) {
            return (v < 0 && (v & ((1 << n) - 1)) != 0) ? 1 : 0;
        }
        return v < 0 ? 1 : 0;
    }

    /** srad/sradi result for shift {@code n} (0..127). */
    static long srad(long rs, int n) {
        return n < 64 ? rs >> n : rs >> 63;
    }

    static int sradCa(long rs, int n) {
        if (n < 64) {
            return (rs < 0 && (rs & ((1L << n) - 1)) != 0) ? 1 : 0;
        }
        return rs < 0 ? 1 : 0;
    }

    static long divw(long ra, long rb) {
        int a = (int) ra;
        int b = (int) rb;
        boolean bad = b == 0 || (a == Integer.MIN_VALUE && b == -1);
        return (a / (bad ? 1 : b)) & 0xffffffffL;
    }

    static long divwu(long ra, long rb) {
        int a = (int) ra;
        int b = (int) rb;
        return Integer.divideUnsigned(a, b == 0 ? 1 : b) & 0xffffffffL;
    }

    static long divd(long a, long b) {
        boolean bad = b == 0 || (a == Long.MIN_VALUE && b == -1);
        return a / (bad ? 1 : b);
    }

    static long divdu(long a, long b) {
        return Long.divideUnsigned(a, b == 0 ? 1 : b);
    }

    static long modsw(long ra, long rb) {
        int a = (int) ra;
        int b = (int) rb;
        boolean bad = b == 0 || (a == Integer.MIN_VALUE && b == -1);
        return a % (bad ? 1 : b);
    }

    static long moduw(long ra, long rb) {
        int a = (int) ra;
        int b = (int) rb;
        return Integer.remainderUnsigned(a, b == 0 ? 1 : b) & 0xffffffffL;
    }

    static long modsd(long a, long b) {
        boolean bad = b == 0 || (a == Long.MIN_VALUE && b == -1);
        return a % (bad ? 1 : b);
    }

    static long modud(long a, long b) {
        return Long.remainderUnsigned(a, b == 0 ? 1 : b);
    }

    static long mulhw(long a, long b) {
        return ((long) (int) a * (long) (int) b) >>> 32;
    }

    static long mulhwu(long a, long b) {
        return ((a & 0xffffffffL) * (b & 0xffffffffL)) >>> 32;
    }

    /** mfocrf with a one-hot field mask: the selected field, else {@code old} (unchanged). */
    static long mfocrf(int cr, int fxm, long old) {
        if (fxm != 0 && (fxm & (fxm - 1)) == 0) {
            int sh = Integer.numberOfTrailingZeros(fxm) * 4;
            return (long) (cr & (0xf << sh)) & 0xffffffffL;
        }
        return old;
    }
}
