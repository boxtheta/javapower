package org.boxtheta.javapower.cpu;

import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.Op;

/**
 * Registers this module's handlers into a {@link DecodeTable}. Handlers the interpreter executes
 * inline (see {@link Interpreter#kindOf}) are registered wrapped in a {@link Kind}, which carries
 * the interpreter's dispatch number, and the others in a {@link Local}; called through
 * {@link Op#exec} either behaves exactly like the handler it wraps.
 */
final class Reg {
    private final DecodeTable t;
    /** Every registered name and the op registered for it (as tagged), for tests. */
    final java.util.Map<String, Op> registered = new java.util.HashMap<>();

    Reg(DecodeTable t) {
        this.t = t;
    }

    /** An {@link Op} tagged with the interpreter's inline dispatch number for it. */
    record Kind(int kind, Op op) implements Op {
        @Override
        public void exec(org.boxtheta.javapower.core.CpuState s, int insn) {
            op.exec(s, insn);
        }
    }

    /**
     * One of this module's handlers that the run loop does not execute inline. It may change
     * state the run loop caches (MSR, timers, idle), so a compiled block ends after it.
     */
    record Local(Op op) implements Op {
        @Override
        public void exec(org.boxtheta.javapower.core.CpuState s, int insn) {
            op.exec(s, insn);
        }
    }

    private Op tag(String name, Op op) {
        int k = Interpreter.kindOf(name);
        // Fields.INVALID stays unwrapped: it only raises an illegal-instruction interrupt.
        Op r = k != 0 ? new Kind(k, op) : op == Fields.INVALID ? op : new Local(op);
        registered.put(name, r);
        return r;
    }

    void add(int mask, int match, String name, Op op) {
        t.add(mask, match, name, tag(name, op));
    }

    void addPrimary(int primary, String name, Op op) {
        t.addPrimary(primary, name, tag(name, op));
    }

    void addX(int primary, int xo, String name, Op op) {
        t.addX(primary, xo, name, tag(name, op));
    }

    void addXO(int primary, int xo, String name, Op op) {
        t.addXO(primary, xo, name, tag(name, op));
    }

    void addA(int primary, int xo, String name, Op op) {
        t.addA(primary, xo, name, tag(name, op));
    }
}
