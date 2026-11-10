package org.boxtheta.javapower.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps 32-bit instruction words to {@link Op}s by (mask, match) patterns.
 *
 * <p>Lookup is a linear scan within the primary-opcode bucket, most specific pattern first; it is
 * meant to be called once per instruction location and the result cached by the execution engine.
 */
public final class DecodeTable {
    /** A registered pattern: {@code (insn & mask) == match}. */
    public record Entry(int mask, int match, String name, Op op) {}

    private static final int PRIMARY_MASK = 0xfc000000;

    private final List<List<Entry>> buckets = new ArrayList<>();
    private Entry[][] frozen;

    public DecodeTable() {
        for (int i = 0; i < 64; i++) {
            buckets.add(new ArrayList<>());
        }
    }

    /**
     * Register an instruction. {@code mask} must include the whole primary opcode field.
     *
     * @throws IllegalArgumentException if an identical pattern is already registered
     */
    public void add(int mask, int match, String name, Op op) {
        if ((mask & PRIMARY_MASK) != PRIMARY_MASK) {
            throw new IllegalArgumentException(name + ": mask must cover the primary opcode");
        }
        if ((match & ~mask) != 0) {
            throw new IllegalArgumentException(name + ": match has bits outside mask");
        }
        List<Entry> b = buckets.get(match >>> 26);
        for (Entry e : b) {
            if (e.mask == mask && e.match == match) {
                throw new IllegalArgumentException(name + " duplicates " + e.name);
            }
        }
        b.add(new Entry(mask, match, name, op));
        frozen = null;
    }

    /** Primary opcode only (D-form and friends). */
    public void addPrimary(int primary, String name, Op op) {
        add(PRIMARY_MASK, primary << 26, name, op);
    }

    /** X/XL/XFX-form: primary opcode plus the 10-bit extended opcode in ISA bits 21:30. */
    public void addX(int primary, int xo, String name, Op op) {
        add(PRIMARY_MASK | (0x3ff << 1), (primary << 26) | (xo << 1), name, op);
    }

    /** XO-form: primary opcode plus the 9-bit extended opcode in ISA bits 22:30 (OE bit is not matched). */
    public void addXO(int primary, int xo, String name, Op op) {
        add(PRIMARY_MASK | (0x1ff << 1), (primary << 26) | (xo << 1), name, op);
    }

    /** A-form: primary opcode plus the 5-bit extended opcode in ISA bits 26:30. */
    public void addA(int primary, int xo, String name, Op op) {
        add(PRIMARY_MASK | (0x1f << 1), (primary << 26) | (xo << 1), name, op);
    }

    public void addAll(InsnSet set) {
        set.register(this);
    }

    /** The entry matching {@code insn}, or null if the instruction is not implemented. */
    public Entry lookup(int insn) {
        Entry[][] f = frozen;
        if (f == null) {
            f = freeze();
        }
        for (Entry e : f[insn >>> 26]) {
            if ((insn & e.mask) == e.match) {
                return e;
            }
        }
        return null;
    }

    public int size() {
        int n = 0;
        for (List<Entry> b : buckets) {
            n += b.size();
        }
        return n;
    }

    private Entry[][] freeze() {
        Entry[][] f = new Entry[64][];
        for (int i = 0; i < 64; i++) {
            List<Entry> b = new ArrayList<>(buckets.get(i));
            // Most specific (most mask bits) first, so reserved-bit variants win over generic ones.
            b.sort((x, y) -> Integer.bitCount(y.mask) - Integer.bitCount(x.mask));
            f[i] = b.toArray(new Entry[0]);
        }
        frozen = f;
        return f;
    }
}
