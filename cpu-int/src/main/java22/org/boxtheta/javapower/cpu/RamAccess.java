package org.boxtheta.javapower.cpu;

import java.lang.constant.ClassDesc;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
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

    /** Type of {@link #backing}. */
    static final ClassDesc TYPE = ClassDesc.of("java.lang.foreign.MemorySegment");

    /** The object generated code passes to these helpers for RAM {@code pm}. */
    static Object backing(PhysicalMemory pm) {
        return (MemorySegment) pm.jitBacking();
    }

    static long ld8(Object m, long pa) {
        return ((MemorySegment) m).get(ValueLayout.JAVA_BYTE, pa) & 0xffL;
    }

    static long ld16le(Object m, long pa) {
        return ((MemorySegment) m).get(S_LE, pa) & 0xffffL;
    }

    static long ld16be(Object m, long pa) {
        return ((MemorySegment) m).get(S_BE, pa) & 0xffffL;
    }

    static long ld32le(Object m, long pa) {
        return ((MemorySegment) m).get(I_LE, pa);
    }

    static long ld32be(Object m, long pa) {
        return ((MemorySegment) m).get(I_BE, pa);
    }

    static long ld64le(Object m, long pa) {
        return ((MemorySegment) m).get(L_LE, pa);
    }

    static long ld64be(Object m, long pa) {
        return ((MemorySegment) m).get(L_BE, pa);
    }

    static void st8(Object m, long pa, long v) {
        ((MemorySegment) m).set(ValueLayout.JAVA_BYTE, pa, (byte) v);
    }

    static void st16le(Object m, long pa, long v) {
        ((MemorySegment) m).set(S_LE, pa, (short) v);
    }

    static void st16be(Object m, long pa, long v) {
        ((MemorySegment) m).set(S_BE, pa, (short) v);
    }

    static void st32le(Object m, long pa, long v) {
        ((MemorySegment) m).set(I_LE, pa, (int) v);
    }

    static void st32be(Object m, long pa, long v) {
        ((MemorySegment) m).set(I_BE, pa, (int) v);
    }

    static void st64le(Object m, long pa, long v) {
        ((MemorySegment) m).set(L_LE, pa, v);
    }

    static void st64be(Object m, long pa, long v) {
        ((MemorySegment) m).set(L_BE, pa, v);
    }
}
