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

    // What the run loop checks before each run, kept here because the call reads this object
    // anyway (see Interpreter.runBlock). Set by the interpreter that owns the code; the code is
    // shared by the CPUs of a domain, which may see these fields late: their values then fail
    // the checks, which only sends the run loop the long way round.
    /** {@link #key} of the code's entry and mode, set once it may be run; 0 before. */
    long key;
    /** A region (else a first-tier block). */
    boolean region;
    /** First tier: the number of instructions; region: the length of its entry block. */
    int n;
    /** {@code PhysicalMemory.codeWriteCount} when the code was last found current. */
    int checkedAt = -1;

    /** The {@link #key} of code entered at real address {@code pa} in {@code mode}; never 0. */
    static long key(long pa, int mode) {
        return (pa << 5) | ((long) mode << 1) | 1;
    }

    /**
     * Execute the block from its first instruction, with exactly the effects the interpreter's
     * run loop would have instruction by instruction. Returns {@link #CONTINUE} or {@link #FAULTED}.
     */
    abstract int run(CpuState s, Interpreter in);
}
