package org.boxtheta.javapower.cpu;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import org.boxtheta.javapower.core.PhysicalMemory;

/**
 * Small static helpers called by code {@link RegionCompiler} generates. They are written so the
 * JIT inlines them into the region's method: value computations whose operands are guest
 * registers held in JVM locals, the software TLB's hit path, and raw guest RAM accesses.
 * Semantics mirror the instruction handlers ({@link Alu}, {@link LoadStore}) exactly.
 */
final class JitRt {
    private JitRt() {}

    private static final ValueLayout.OfShort S_BE = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfShort S_LE = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt I_BE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfInt I_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong L_BE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong L_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static final int TLB_MASK = (1 << RadixMmu.TLB_BITS) - 1;

    // ------------------------------------------------------------------ translation fast paths

    /**
     * The software TLB's hit path for a data access of {@code size} bytes at {@code ea} (already
     * truncated to the computation mode) with MSR[DR]=1: the real address, or -1 if the access
     * crosses a page or misses (the caller then takes the MMU's full path, which does exactly
     * what the interpreter would). {@code ctxBase} is 2 in problem state, else 0; {@code type}
     * is {@link RadixMmu#LOAD} or {@link RadixMmu#STORE}.
     */
    static long xlate(long[] tag, long[] addend, int[] epoch, long ea, int ctxBase, int type, int size) {
        if ((ea & 0xfff) > 0x1000 - size) {
            return -1;
        }
        int ctx = ctxBase | (int) (ea >>> 63);
        int slot = ((ctx * 3 + type) << RadixMmu.TLB_BITS) | ((int) (ea >>> 12) & TLB_MASK);
        if (tag[slot] != ((ea & ~0xfffL) | epoch[ctx])) {
            return -1;
        }
        return addend[slot] + ea;
    }

    /** Real-mode (MSR[DR]=0) address of an access ending at or below {@code limit}, or -1. */
    static long real(long ea, long limit) {
        long pa = ea & 0x0FFFFFFFFFFFFFFFL;
        return pa > limit ? -1 : pa;
    }

    // ------------------------------------------------------------------ raw RAM

    static long ld8(MemorySegment m, long pa) {
        return m.get(ValueLayout.JAVA_BYTE, pa) & 0xffL;
    }

    static long ld16le(MemorySegment m, long pa) {
        return m.get(S_LE, pa) & 0xffffL;
    }

    static long ld16be(MemorySegment m, long pa) {
        return m.get(S_BE, pa) & 0xffffL;
    }

    /** Sign-extended; callers zero-extend where the instruction does. */
    static long ld32le(MemorySegment m, long pa) {
        return m.get(I_LE, pa);
    }

    static long ld32be(MemorySegment m, long pa) {
        return m.get(I_BE, pa);
    }

    static long ld64le(MemorySegment m, long pa) {
        return m.get(L_LE, pa);
    }

    static long ld64be(MemorySegment m, long pa) {
        return m.get(L_BE, pa);
    }

    static void st8(MemorySegment m, long pa, long v) {
        m.set(ValueLayout.JAVA_BYTE, pa, (byte) v);
    }

    static void st16le(MemorySegment m, long pa, long v) {
        m.set(S_LE, pa, (short) v);
    }

    static void st16be(MemorySegment m, long pa, long v) {
        m.set(S_BE, pa, (short) v);
    }

    static void st32le(MemorySegment m, long pa, long v) {
        m.set(I_LE, pa, (int) v);
    }

    static void st32be(MemorySegment m, long pa, long v) {
        m.set(I_BE, pa, (int) v);
    }

    static void st64le(MemorySegment m, long pa, long v) {
        m.set(L_LE, pa, v);
    }

    static void st64be(MemorySegment m, long pa, long v) {
        m.set(L_BE, pa, v);
    }

    /**
     * After a direct store of {@code size} bytes at {@code pa}: tell {@code pm} if the page holds
     * translated code, and return whether the region must stop after this instruction (the store
     * hit translated code, possibly its own page {@code page}).
     */
    static boolean storedCode(PhysicalMemory pm, long pa, int size, long page) {
        boolean code = pm.isCodePage(pa);
        if (code) {
            pm.invalidateCode(pa, size);
        }
        return code || ((pa ^ page) >>> 12) == 0;
    }

    // ------------------------------------------------------------------ MMU slow paths
    // The MMU's full access path (page crossing, TLB miss, faults); ea is already truncated.

    static long rd8(RadixMmu m, long ea) {
        return m.read8(ea);
    }

    static long rd16(RadixMmu m, long ea) {
        return m.read16(ea);
    }

    static long rd32(RadixMmu m, long ea) {
        return m.read32(ea);
    }

    static long rd64(RadixMmu m, long ea) {
        return m.read64(ea);
    }

    static void wr8(RadixMmu m, long ea, long v) {
        m.write8(ea, (int) v);
    }

    static void wr16(RadixMmu m, long ea, long v) {
        m.write16(ea, (int) v);
    }

    static void wr32(RadixMmu m, long ea, long v) {
        m.write32(ea, (int) v);
    }

    static void wr64(RadixMmu m, long ea, long v) {
        m.write64(ea, v);
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
