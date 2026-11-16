package org.boxtheta.javapower.fpvec;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SplittableRandom;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PpcException;

/**
 * Randomized differential check of {@link VmxInsns} against the element-by-element reference
 * {@link VmxRef}: every instruction the reference registers runs on identical states in both, and
 * every VR, GPR, CR, VSCR and the exception raised must agree. Operands mix random bits with lane
 * patterns built from edge values of every lane width (zero, one, all ones, the signed limits and
 * their neighbours), operands related to each other (equal, differing in a few lanes, off by one
 * per lane), and register fields drawn often from four registers, so the target frequently
 * aliases a source.
 */
final class VmxDiff {
    static final DecodeTable REF = new DecodeTable(), NEW = new DecodeTable();

    static {
        VmxRef.INSTANCE.register(REF);
        VmxInsns.INSTANCE.register(NEW);
    }

    /** Cases and mismatches per mnemonic, and the first few mismatches described. */
    final Map<String, long[]> counts = new LinkedHashMap<>();
    final StringBuilder report = new StringBuilder();
    long cases, mismatches;

    private final SplittableRandom r;
    private final CpuState x = new CpuState(), y = new CpuState();

    VmxDiff(long seed) {
        r = new SplittableRandom(seed);
        for (int i = 0; i < 32; i++) x.gpr[i] = r.nextLong();
        for (int i = 32; i < 64; i++) {
            x.vsrHi[i] = val();
            x.vsrLo[i] = val();
        }
        sync();
    }

    /** Runs {@code n} cases of every reference instruction (mtvscr and mfvscr included). */
    void runAll(int n) {
        for (DecodeTable.Entry e : VmxRef.ENTRIES) run(e, n);
    }

    void run(DecodeTable.Entry e, int n) {
        long[] c = counts.computeIfAbsent(e.name(), k -> new long[2]);
        for (int k = 0; k < n; k++) {
            int insn = insn(e);
            if (!REF.lookup(insn).name().equals(e.name())) {
                continue; // a word of a more specific pattern, which has its own turn
            }
            DecodeTable.Entry ne = NEW.lookup(insn);
            if (ne == null || !ne.name().equals(e.name())) {
                c[1]++;
                mismatches++;
                fail(e.name(), insn, "decodes to " + (ne == null ? "nothing" : ne.name()), null);
                continue;
            }
            setUp(insn);
            long[] in = inputs(x, insn);
            PpcException ex = exec(e, x, insn), ey = exec(ne, y, insn);
            String d = diff(ex, ey);
            c[0]++;
            cases++;
            if (d != null) {
                c[1]++;
                mismatches++;
                fail(e.name(), insn, d, describe(in));
                sync();
            }
        }
    }

    private void fail(String name, int insn, String d, String in) {
        if (mismatches <= 20) {
            report.append(String.format("%n  %s %08x: %s%s", name, insn, d, in == null ? "" : "\n     in: " + in));
        }
    }

    private static PpcException exec(DecodeTable.Entry e, CpuState s, int insn) {
        try {
            e.op().exec(s, insn);
            return null;
        } catch (PpcException p) {
            return p;
        }
    }

    // ------------------------------------------------------------------ inputs

    /** A random instruction word of the entry, register fields often from 0..3. */
    private int insn(DecodeTable.Entry e) {
        int w = e.match() | (r.nextInt() & ~e.mask());
        for (int sh = 6; sh <= 21; sh += 5) {
            int f = 31 << sh;
            if ((e.mask() & f) == 0 && r.nextBoolean()) w = (w & ~f) | (r.nextInt(4) << sh);
        }
        return w;
    }

    private void setUp(int insn) {
        int t = 32 + ((insn >>> 21) & 31), a = 32 + ((insn >>> 16) & 31), b = 32 + ((insn >>> 11) & 31);
        int c = 32 + ((insn >>> 6) & 31);
        for (int v : new int[] {t, a, b, c}) {
            x.vsrHi[v] = val();
            x.vsrLo[v] = val();
        }
        relate(a, b);
        if (r.nextInt(4) == 0) relate(b, c);
        x.gpr[(insn >>> 16) & 31] = r.nextInt(4) == 0 ? r.nextInt(32) : r.nextLong();
        x.vscr = switch (r.nextInt(4)) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 0x10000;
            default -> r.nextInt();
        };
        x.cr = r.nextInt();
        x.msr = Msr.SF | (r.nextInt(64) == 0 ? 0 : Msr.VEC);
        sync();
    }

    /** Sometimes make VR q a variation of VR p. */
    private void relate(int p, int q) {
        switch (r.nextInt(7)) {
            case 0 -> { // equal
                x.vsrHi[q] = x.vsrHi[p];
                x.vsrLo[q] = x.vsrLo[p];
            }
            case 1 -> { // equal except in a few random bits or bytes
                x.vsrHi[q] = x.vsrHi[p] ^ sparse();
                x.vsrLo[q] = x.vsrLo[p] ^ sparse();
            }
            case 2 -> { // one more or less in some lanes of some width
                int w = 8 << r.nextInt(4);
                x.vsrHi[q] = nudge(x.vsrHi[p], w, 0);
                x.vsrLo[q] = nudge(x.vsrLo[p], w, 0);
            }
            case 3 -> { // one more or one less in every lane: all-true and all-false compares
                int w = 8 << r.nextInt(4), d = r.nextBoolean() ? 1 : -1;
                x.vsrHi[q] = nudge(x.vsrHi[p], w, d);
                x.vsrLo[q] = nudge(x.vsrLo[p], w, d);
            }
            default -> { }
        }
    }

    private long sparse() {
        return switch (r.nextInt(3)) {
            case 0 -> 0;
            case 1 -> 1L << r.nextInt(64);
            default -> 0xffL << (8 * r.nextInt(8));
        };
    }

    /** v with some w-bit lanes changed by -1, 0 or 1, or (d != 0) every lane changed by d. */
    private long nudge(long v, int w, int d) {
        long m = w == 64 ? -1L : (1L << w) - 1;
        long out = 0;
        for (int j = 0; j < 64; j += w) {
            long e = (v >>> j) & m;
            if (d != 0) {
                e = (e + d) & m;
            } else if (r.nextBoolean()) {
                e = (e + r.nextInt(-1, 2)) & m;
            }
            out |= e << j;
        }
        return out;
    }

    /** A doubleword: random bits, or lanes of one width built from edge values. */
    long val() {
        switch (r.nextInt(10)) {
            case 0: case 1: return r.nextLong();
            case 2: return 0;
            case 3: return -1;
            case 4: return r.nextLong() & 0x1f1f1f1f1f1f1f1fL; // small byte values: indices, counts
            case 5: return r.nextLong() & 0x0101010101010101L * (1 << r.nextInt(8)); // one bit per byte
            default: {
                int w = 8 << r.nextInt(4);
                long m = w == 64 ? -1L : (1L << w) - 1, min = 1L << (w - 1);
                long out = 0;
                for (int j = 0; j < 64; j += w) {
                    long e = switch (r.nextInt(10)) {
                        case 0 -> 0;
                        case 1 -> 1;
                        case 2 -> m;
                        case 3 -> min - 1;
                        case 4 -> min;
                        case 5 -> min - 2;
                        case 6 -> min + 1;
                        case 7 -> m - 1;
                        case 8 -> r.nextInt(-4, 5);
                        default -> r.nextLong();
                    };
                    out |= (e & m) << j;
                }
                return out;
            }
        }
    }

    // ------------------------------------------------------------------ comparison

    /** Makes y a copy of x. */
    private void sync() {
        System.arraycopy(x.vsrHi, 0, y.vsrHi, 0, 64);
        System.arraycopy(x.vsrLo, 0, y.vsrLo, 0, 64);
        System.arraycopy(x.gpr, 0, y.gpr, 0, 32);
        y.vscr = x.vscr;
        y.cr = x.cr;
        y.msr = x.msr;
    }

    /** x ran the reference, y the new code: null if they agree. */
    private String diff(PpcException ex, PpcException ey) {
        if (ex != ey) {
            return "exception " + (ex == null ? "none" : ex.vector) + " (ref) vs " + (ey == null ? "none" : ey.vector);
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 64; i++) {
            if (x.vsrHi[i] != y.vsrHi[i] || x.vsrLo[i] != y.vsrLo[i]) {
                sb.append(String.format(" vsr%d %016x%016x (ref) vs %016x%016x", i, x.vsrHi[i], x.vsrLo[i], y.vsrHi[i], y.vsrLo[i]));
            }
        }
        if (!Arrays.equals(x.gpr, y.gpr)) {
            for (int i = 0; i < 32; i++) {
                if (x.gpr[i] != y.gpr[i]) sb.append(String.format(" r%d %x (ref) vs %x", i, x.gpr[i], y.gpr[i]));
            }
        }
        if (x.vscr != y.vscr) sb.append(String.format(" vscr %x (ref) vs %x", x.vscr, y.vscr));
        if (x.cr != y.cr) sb.append(String.format(" cr %08x (ref) vs %08x", x.cr, y.cr));
        return sb.isEmpty() ? null : sb.toString().trim();
    }

    /** VRT, VRA, VRB, VRC (high and low), GPR[RA] and VSCR. */
    private static long[] inputs(CpuState s, int insn) {
        long[] v = new long[10];
        for (int k = 0; k < 4; k++) {
            int n = 32 + ((insn >>> (21 - 5 * k)) & 31);
            v[2 * k] = s.vsrHi[n];
            v[2 * k + 1] = s.vsrLo[n];
        }
        v[8] = s.gpr[(insn >>> 16) & 31];
        v[9] = s.vscr;
        return v;
    }

    private static String describe(long[] v) {
        return String.format("t=%016x%016x a=%016x%016x b=%016x%016x c=%016x%016x r[ra]=%x vscr=%x",
                v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9]);
    }
}
