package org.boxtheta.javapower.cpu;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

import org.boxtheta.javapower.core.PhysicalMemory;

/**
 * Guest RAM accesses for code {@link RegionCompiler} generates (through {@link JitRt}'s loads and
 * stores), which the JIT inlines into the region's method. This class is multi-release, like
 * {@link PhysicalMemory}: the generated code holds {@link #backing} as a constant in the region's
 * class data and passes it to these helpers as an {@code Object}, so one {@link JitRt} serves
 * whichever version is loaded; {@link #TYPE} is its actual type.
 *
 * <p>Loads return the value zero-extended (8, 16 bits) or sign-extended (32 bits); callers
 * zero-extend where the instruction does. Stores write the low bytes of the value and nothing
 * else: there is no code-write tracking here, callers keep away from pages that need it.
 *
 * <p>This version (Java 21) accesses {@link PhysicalMemory}'s heap chunks ({@code byte[][]},
 * see {@link PhysicalMemory#jitBacking}) through byte-array views. An access that crosses from
 * one chunk into the next fails the view's bounds check and is redone byte by byte.
 */
final class RamAccess {
    private RamAccess() {}

    /** Type of {@link #backing}. */
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

    private static byte[] chunk(Object ram, long pa) {
        byte[][] c = (byte[][]) ram;
        return c[(int) Math.min(pa >>> SHIFT, Integer.MAX_VALUE)];
    }

    private static int index(long pa) {
        return BIAS + ((int) pa & MASK);
    }

    private static long slowBe(Object c, long pa, int n) {
        long v = 0;
        for (int k = 0; k < n; k++) {
            v = (v << 8) | (chunk(c, pa + k)[index(pa + k)] & 0xff);
        }
        return v;
    }

    private static void slowStoreBe(Object c, long pa, int n, long v) {
        for (int k = n - 1; k >= 0; k--, v >>>= 8) {
            chunk(c, pa + k)[index(pa + k)] = (byte) v;
        }
    }

    static long ld8(Object c, long pa) {
        return chunk(c, pa)[index(pa)] & 0xffL;
    }

    static long ld16le(Object c, long pa) {
        try {
            return (short) S_LE.get(chunk(c, pa), index(pa)) & 0xffffL;
        } catch (IndexOutOfBoundsException e) {
            return Short.reverseBytes((short) slowBe(c, pa, 2)) & 0xffffL;
        }
    }

    static long ld16be(Object c, long pa) {
        try {
            return (short) S_BE.get(chunk(c, pa), index(pa)) & 0xffffL;
        } catch (IndexOutOfBoundsException e) {
            return slowBe(c, pa, 2);
        }
    }

    static long ld32le(Object c, long pa) {
        try {
            return (int) I_LE.get(chunk(c, pa), index(pa));
        } catch (IndexOutOfBoundsException e) {
            return Integer.reverseBytes((int) slowBe(c, pa, 4));
        }
    }

    static long ld32be(Object c, long pa) {
        try {
            return (int) I_BE.get(chunk(c, pa), index(pa));
        } catch (IndexOutOfBoundsException e) {
            return (int) slowBe(c, pa, 4);
        }
    }

    static long ld64le(Object c, long pa) {
        try {
            return (long) L_LE.get(chunk(c, pa), index(pa));
        } catch (IndexOutOfBoundsException e) {
            return Long.reverseBytes(slowBe(c, pa, 8));
        }
    }

    static long ld64be(Object c, long pa) {
        try {
            return (long) L_BE.get(chunk(c, pa), index(pa));
        } catch (IndexOutOfBoundsException e) {
            return slowBe(c, pa, 8);
        }
    }

    static void st8(Object c, long pa, long v) {
        chunk(c, pa)[index(pa)] = (byte) v;
    }

    static void st16le(Object c, long pa, long v) {
        try {
            S_LE.set(chunk(c, pa), index(pa), (short) v);
        } catch (IndexOutOfBoundsException e) {
            slowStoreBe(c, pa, 2, Short.reverseBytes((short) v));
        }
    }

    static void st16be(Object c, long pa, long v) {
        try {
            S_BE.set(chunk(c, pa), index(pa), (short) v);
        } catch (IndexOutOfBoundsException e) {
            slowStoreBe(c, pa, 2, v);
        }
    }

    static void st32le(Object c, long pa, long v) {
        try {
            I_LE.set(chunk(c, pa), index(pa), (int) v);
        } catch (IndexOutOfBoundsException e) {
            slowStoreBe(c, pa, 4, Integer.reverseBytes((int) v));
        }
    }

    static void st32be(Object c, long pa, long v) {
        try {
            I_BE.set(chunk(c, pa), index(pa), (int) v);
        } catch (IndexOutOfBoundsException e) {
            slowStoreBe(c, pa, 4, v);
        }
    }

    static void st64le(Object c, long pa, long v) {
        try {
            L_LE.set(chunk(c, pa), index(pa), v);
        } catch (IndexOutOfBoundsException e) {
            slowStoreBe(c, pa, 8, Long.reverseBytes(v));
        }
    }

    static void st64be(Object c, long pa, long v) {
        try {
            L_BE.set(chunk(c, pa), index(pa), v);
        } catch (IndexOutOfBoundsException e) {
            slowStoreBe(c, pa, 8, v);
        }
    }
}
