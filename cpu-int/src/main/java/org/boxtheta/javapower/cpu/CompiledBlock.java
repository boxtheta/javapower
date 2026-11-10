package org.boxtheta.javapower.cpu;

import org.boxtheta.javapower.core.CpuState;

/**
 * A straight-line run of guest instructions translated to JVM bytecode by {@link BlockCompiler}.
 * Subclasses are generated hidden classes; see {@link BlockCompiler} for what {@link #run} does.
 */
abstract class CompiledBlock {
    /** {@link #run} result: the block ran (fully or up to an early exit) and {@code s.pc} is the next instruction. */
    static final int CONTINUE = 0;
    /** {@link #run} result: an instruction raised an interrupt, which has been delivered. */
    static final int FAULTED = 1;

    /**
     * Execute the block from its first instruction, with exactly the effects the interpreter's
     * run loop would have instruction by instruction. Returns {@link #CONTINUE} or {@link #FAULTED}.
     */
    abstract int run(CpuState s, Interpreter in);
}
