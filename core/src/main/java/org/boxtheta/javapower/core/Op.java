package org.boxtheta.javapower.core;

/**
 * Executes one decoded instruction. On entry {@code s.pc} is the instruction address and
 * {@code s.npc} is {@code pc + 4}; branches assign {@code s.npc}. The instruction word is always
 * passed in architectural (big-endian field) order regardless of MSR[LE].
 *
 * <p>Implementations must be stateless and thread-safe: one instance serves every CPU.
 */
@FunctionalInterface
public interface Op {
    void exec(CpuState s, int insn);
}
