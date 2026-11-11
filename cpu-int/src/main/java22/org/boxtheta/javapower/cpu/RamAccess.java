package org.boxtheta.javapower.cpu;

import java.lang.constant.ClassDesc;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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
 * <p>This version (Java 22 and later) accesses {@link PhysicalMemory}'s {@code MemorySegment}
 * directly; held as a constant, its bounds and base address fold into each access.
 */
final class RamAccess {
    private RamAccess() {}

    private static final ValueLayout.OfShort S_BE = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfShort S_LE = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt I_BE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfInt I_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong L_BE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfLong L_LE = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /** Type of {@link #backing}, as generated code declares it. */
    static final ClassDesc TYPE = ClassDesc.of("java.lang.foreign.MemorySegment");

    /** The object generated code passes to these helpers for RAM {@code pm}. */
    static Object backing(PhysicalMemory pm) {
        return (MemorySegment) pm.jitBacking();
    }

    static long ld8(MemorySegment m, long pa) {
        return m.get(ValueLayout.JAVA_BYTE, pa) & 0xffL;
    }

    static long ld16le(MemorySegment m, long pa) {
        return m.get(S_LE, pa) & 0xffffL;
    }

    static long ld16be(MemorySegment m, long pa) {
        return m.get(S_BE, pa) & 0xffffL;
    }

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

    /**
     * After a direct store of {@code size} bytes at {@code pa}: tell {@code pm} if the page holds
     * translated code, and return whether the region must stop.
     */
    private static boolean stored(PhysicalMemory pm, long pa, int size, long page) {
        boolean code = pm.isCodePage(pa);
        if (code) {
            pm.invalidateCode(pa, size);
        }
        return code || ((pa ^ page) >>> 12) == 0;
    }

    static boolean st8(MemorySegment m, PhysicalMemory pm, long pa, long v, long page) {
        m.set(ValueLayout.JAVA_BYTE, pa, (byte) v);
        return stored(pm, pa, 1, page);
    }

    static boolean st16le(MemorySegment m, PhysicalMemory pm, long pa, long v, long page) {
        m.set(S_LE, pa, (short) v);
        return stored(pm, pa, 2, page);
    }

    static boolean st16be(MemorySegment m, PhysicalMemory pm, long pa, long v, long page) {
        m.set(S_BE, pa, (short) v);
        return stored(pm, pa, 2, page);
    }

    static boolean st32le(MemorySegment m, PhysicalMemory pm, long pa, long v, long page) {
        m.set(I_LE, pa, (int) v);
        return stored(pm, pa, 4, page);
    }

    static boolean st32be(MemorySegment m, PhysicalMemory pm, long pa, long v, long page) {
        m.set(I_BE, pa, (int) v);
        return stored(pm, pa, 4, page);
    }

    static boolean st64le(MemorySegment m, PhysicalMemory pm, long pa, long v, long page) {
        m.set(L_LE, pa, v);
        return stored(pm, pa, 8, page);
    }

    static boolean st64be(MemorySegment m, PhysicalMemory pm, long pa, long v, long page) {
        m.set(L_BE, pa, v);
        return stored(pm, pa, 8, page);
    }
}
