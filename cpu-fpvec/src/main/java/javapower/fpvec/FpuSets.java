package javapower.fpvec;

import javapower.core.DecodeTable;
import javapower.core.InsnSet;

/**
 * Entry point for other modules: the instruction sets implemented by cpu-fpvec. Patterns are
 * disjoint from each other and from the integer instructions sharing primary opcodes 4, 31, 59,
 * 60, 61 and 63, so registration order does not matter.
 */
public final class FpuSets {
    private FpuSets() {}

    /** Scalar floating point (FPRs, FPSCR, FP loads/stores). */
    public static final InsnSet FP = FpInsns.INSTANCE;
    /** VMX / Altivec. */
    public static final InsnSet VMX = VmxInsns.INSTANCE;
    /** VSX (everything but quad precision). */
    public static final InsnSet VSX = VsxInsns.INSTANCE;

    /** ISA 3.0 binary128 (quad-precision) VSX instructions. */
    public static final InsnSet QP = QpInsns.INSTANCE;

    /** Every set above. */
    public static final InsnSet[] ALL = {FP, VMX, VSX, QP};

    public static void registerAll(DecodeTable t) {
        for (InsnSet s : ALL) {
            t.addAll(s);
        }
    }
}
