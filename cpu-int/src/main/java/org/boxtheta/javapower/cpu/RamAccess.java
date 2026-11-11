package org.boxtheta.javapower.cpu;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

import org.boxtheta.javapower.core.PhysicalMemory;

/**
 * Guest RAM accesses for code {@link RegionCompiler} generates, which the JIT inlines into the
 * region's method. This class is multi-release, like {@link PhysicalMemory}: the generated code
 * passes {@link #backing} (held as a constant in the region's class data) to these helpers, and
 * {@link #TYPE} is its type, so the code is built for whichever version is loaded.
 *
 * <p>Loads return the value zero-extended (8, 16 bits) or sign-extended (32 bits); callers
 * zero-extend where the instruction does. Stores return whether the region must stop after the
 * instruction: the store hit translated code (any CPU's, which is then invalidated) or the
 * region's own page {@code page}.
 *
 * <p>This version (Java 21) accesses {@link PhysicalMemory}'s heap chunks ({@code byte[][]},
 * see {@link PhysicalMemory#jitBacking}) through byte-array views. An access that crosses from
 * one chunk into the next fails the view's bounds check and is redone byte by byte.
 */
final class RamAccess {
    private RamAccess() {}

    /** Type of {@link #backing}, as generated code declares it. */
    static final ClassDesc TYPE = ConstantDescs.CD_byte.arrayType(2);

    private static final int SHIFT = 30;
    private static final int MASK = (1 << SHIFT) - 1;
    private static final VarHandle S_BE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle S_LE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle I_BE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle I_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle L_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle L_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final int BIAS = bias();

    /**
     * Index of each chunk's first byte (see {@link PhysicalMemory#jitBacking}): the smallest index
     * at which 8-byte atomic accesses through a byte-array view are aligned, found the same way
     * PhysicalMemory finds it.
     */
    static int bias() {
        byte[] probe = new byte[16];
        for (int b = 0; b < 8; b++) {
            try {
                L_LE.compareAndSet(probe, b, 0L, 0L);
                return b;
            } catch (IllegalStateException e) {
                // misaligned: try the next index
            }
        }
        throw new ExceptionInInitializerError("no aligned index in a byte array");
    }

    /** The object generated code passes to these helpers for RAM {@code pm}. */
    static Object backing(PhysicalMemory pm) {
        return pm.jitBacking();
    }

    private static byte[] chunk(byte[][] c, long pa) {
        return c[(int) Math.min(pa >>> SHIFT, Integer.MAX_VALUE)];
    }

    private static int index(long pa) {
        return BIAS + ((int) pa & MASK);
    }

    private static long slowBe(byte[][] c, long pa, int n) {
        long v = 0;
        for (int k = 0; k < n; k++) {
            v = (v << 8) | (chunk(c, pa + k)[index(pa + k)] & 0xff);
        }
        return v;
    }

    private static void slowStoreBe(byte[][] c, long pa, int n, long v) {
        for (int k = n - 1; k >= 0; k--, v >>>= 8) {
            chunk(c, pa + k)[index(pa + k)] = (byte) v;
        }
    }

    static long ld8(byte[][] c, long pa) {
        return chunk(c, pa)[index(pa)] & 0xffL;
    }

    static long ld16le(byte[][] c, long pa) {
        try {
            return (short) S_LE.get(chunk(c, pa), index(pa)) & 0xffffL;
        } catch (IndexOutOfBoundsException e) {
            return Short.reverseBytes((short) slowBe(c, pa, 2)) & 0xffffL;
        }
    }

    static long ld16be(byte[][] c, long pa) {
        try {
            return (short) S_BE.get(chunk(c, pa), index(pa)) & 0xffffL;
        } catch (IndexOutOfBoundsException e) {
            return slowBe(c, pa, 2);
        }
    }

    static long ld32le(byte[][] c, long pa) {
        try {
            return (int) I_LE.get(chunk(c, pa), index(pa));
        } catch (IndexOutOfBoundsException e) {
            return Integer.reverseBytes((int) slowBe(c, pa, 4));
        }
    }

    static long ld32be(byte[][] c, long pa) {
        try {
            return (int) I_BE.get(chunk(c, pa), index(pa));
        } catch (IndexOutOfBoundsException e) {
            return (int) slowBe(c, pa, 4);
        }
    }

    static long ld64le(byte[][] c, long pa) {
        try {
            return (long) L_LE.get(chunk(c, pa), index(pa));
        } catch (IndexOutOfBoundsException e) {
            return Long.reverseBytes(slowBe(c, pa, 8));
        }
    }

    static long ld64be(byte[][] c, long pa) {
        try {
            return (long) L_BE.get(chunk(c, pa), index(pa));
        } catch (IndexOutOfBoundsException e) {
            return slowBe(c, pa, 8);
        }
    }

    /** After a direct store of {@code size} bytes at {@code pa}: track it, and say whether the region must stop. */
    private static boolean stored(PhysicalMemory pm, long pa, int size, long page) {
        boolean code = pm.isCodePage(pa);
        if (code) {
            pm.invalidateCode(pa, size);
        }
        return code || ((pa ^ page) >>> 12) == 0;
    }

    static boolean st8(byte[][] c, PhysicalMemory pm, long pa, long v, long page) {
        chunk(c, pa)[index(pa)] = (byte) v;
        return stored(pm, pa, 1, page);
    }

    static boolean st16le(byte[][] c, PhysicalMemory pm, long pa, long v, long page) {
        try {
            S_LE.set(chunk(c, pa), index(pa), (short) v);
        } catch (IndexOutOfBoundsException e) {
            slowStoreBe(c, pa, 2, Short.reverseBytes((short) v));
        }
        return stored(pm, pa, 2, page);
    }

    static boolean st16be(byte[][] c, PhysicalMemory pm, long pa, long v, long page) {
        try {
            S_BE.set(chunk(c, pa), index(pa), (short) v);
        } catch (IndexOutOfBoundsException e) {
            slowStoreBe(c, pa, 2, v);
        }
        return stored(pm, pa, 2, page);
    }

    static boolean st32le(byte[][] c, PhysicalMemory pm, long pa, long v, long page) {
        try {
            I_LE.set(chunk(c, pa), index(pa), (int) v);
        } catch (IndexOutOfBoundsException e) {
            slowStoreBe(c, pa, 4, Integer.reverseBytes((int) v));
        }
        return stored(pm, pa, 4, page);
    }

    static boolean st32be(byte[][] c, PhysicalMemory pm, long pa, long v, long page) {
        try {
            I_BE.set(chunk(c, pa), index(pa), (int) v);
        } catch (IndexOutOfBoundsException e) {
            slowStoreBe(c, pa, 4, v);
        }
        return stored(pm, pa, 4, page);
    }

    static boolean st64le(byte[][] c, PhysicalMemory pm, long pa, long v, long page) {
        try {
            L_LE.set(chunk(c, pa), index(pa), v);
        } catch (IndexOutOfBoundsException e) {
            slowStoreBe(c, pa, 8, Long.reverseBytes(v));
        }
        return stored(pm, pa, 8, page);
    }

    static boolean st64be(byte[][] c, PhysicalMemory pm, long pa, long v, long page) {
        try {
            L_BE.set(chunk(c, pa), index(pa), v);
        } catch (IndexOutOfBoundsException e) {
            slowStoreBe(c, pa, 8, v);
        }
        return stored(pm, pa, 8, page);
    }
}
