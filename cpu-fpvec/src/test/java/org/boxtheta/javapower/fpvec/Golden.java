package org.boxtheta.javapower.fpvec;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PpcException;

/**
 * Replays golden vectors produced by cpu-fpvec/difftest (QEMU, user mode, little-endian). The
 * default state below must match gen.py.
 */
final class Golden {
    static final long MEMBASE = 0x30000000L;
    static final int MEMSIZE = 512;

    record Case(int insn, String asm, String in, String out) {
        String mnemonic() {
            String a = asm;
            int h = a.indexOf("# ");
            if (h >= 0) a = a.substring(h + 2);
            int sp = a.indexOf(' ');
            return sp < 0 ? a : a.substring(0, sp);
        }
    }

    static long defaultGpr(int n) {
        return (0x0101010101010101L * (n + 1)) ^ 0x8000000000000000L;
    }

    static long defaultVsrHi(int n) {
        return 0x5A5A000000001234L | ((long) n << 32);
    }

    static long defaultVsrLo(int n) {
        return 0xA5A5000000005678L | ((long) n << 32);
    }

    static byte[] defaultMem() {
        byte[] b = new byte[MEMSIZE];
        for (int i = 0; i < MEMSIZE; i++) b[i] = (byte) (i * 37 + 11);
        return b;
    }

    static List<Case> load(String group) throws IOException {
        InputStream raw = Golden.class.getResourceAsStream("/golden/" + group + ".txt.gz");
        if (raw == null) throw new IOException("missing golden file " + group);
        List<Case> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] p = line.split("\\|", -1);
                out.add(new Case(Integer.parseUnsignedInt(p[0], 16), p[1], p[2], p[3]));
            }
        }
        return out;
    }

    /** Machine state snapshot used for both expectation and comparison. */
    static final class State {
        long fpscr;
        int cr, vscr;
        final long[] gpr = new long[32];
        final long[] hi = new long[64], lo = new long[64];
        byte[] mem = defaultMem();
        int sig;

        State() {
            for (int i = 0; i < 32; i++) gpr[i] = defaultGpr(i);
            for (int i = 0; i < 64; i++) {
                hi[i] = defaultVsrHi(i);
                lo[i] = defaultVsrLo(i);
            }
        }

        State copy() {
            State s = new State();
            s.fpscr = fpscr;
            s.cr = cr;
            s.vscr = vscr;
            System.arraycopy(gpr, 0, s.gpr, 0, 32);
            System.arraycopy(hi, 0, s.hi, 0, 64);
            System.arraycopy(lo, 0, s.lo, 0, 64);
            s.mem = mem.clone();
            s.sig = sig;
            return s;
        }

        void apply(String fields) {
            for (String f : fields.trim().split(" +")) {
                if (f.isEmpty()) continue;
                int eq = f.indexOf('=');
                String k = f.substring(0, eq), v = f.substring(eq + 1);
                switch (k.charAt(0)) {
                    case 'f' -> fpscr = Long.parseUnsignedLong(v, 16);
                    case 'c' -> cr = (int) Long.parseLong(v, 16);
                    case 's' -> {
                        if (k.equals("sig")) sig = Integer.parseInt(v);
                        else vscr = (int) Long.parseLong(v, 16);
                    }
                    case 'r' -> gpr[Integer.parseInt(k.substring(1))] = Long.parseUnsignedLong(v, 16);
                    case 'v' -> {
                        int n = Integer.parseInt(k.substring(1));
                        hi[n] = Long.parseUnsignedLong(v.substring(0, 16), 16);
                        lo[n] = Long.parseUnsignedLong(v.substring(16), 16);
                    }
                    case 'm' -> {
                        int off = Integer.parseInt(k.substring(1));
                        for (int i = 0; i < v.length() / 2; i++) {
                            mem[off + i] = (byte) Integer.parseInt(v.substring(2 * i, 2 * i + 2), 16);
                        }
                    }
                    default -> throw new IllegalArgumentException(f);
                }
            }
        }

        String diff(State o) {
            StringBuilder sb = new StringBuilder();
            // On a signal, QEMU's signal frame keeps only FPSCR bits 32:63 (DRN is lost on sigreturn).
            long fm = sig != 0 ? 0xffffffffL : -1L;
            if ((fpscr & fm) != (o.fpscr & fm)) sb.append(String.format(" fpscr exp=%x got=%x", fpscr, o.fpscr));
            if (cr != o.cr) sb.append(String.format(" cr exp=%08x got=%08x", cr, o.cr));
            if (vscr != o.vscr) sb.append(String.format(" vscr exp=%x got=%x", vscr, o.vscr));
            if (sig != o.sig) sb.append(String.format(" sig exp=%d got=%d", sig, o.sig));
            for (int i = 0; i < 32; i++) {
                if (i == 1 || i == 2 || i > 12) continue;
                if (gpr[i] != o.gpr[i]) sb.append(String.format(" r%d exp=%x got=%x", i, gpr[i], o.gpr[i]));
            }
            for (int i = 0; i < 64; i++) {
                if (hi[i] != o.hi[i] || lo[i] != o.lo[i]) {
                    sb.append(String.format(" v%d exp=%016x%016x got=%016x%016x", i, hi[i], lo[i], o.hi[i], o.lo[i]));
                }
            }
            if (!Arrays.equals(mem, o.mem)) {
                for (int i = 0; i < MEMSIZE; i++) {
                    if (mem[i] != o.mem[i]) {
                        sb.append(String.format(" mem@%d exp=%02x got=%02x", i, mem[i] & 0xff, o.mem[i] & 0xff));
                        break;
                    }
                }
            }
            return sb.toString();
        }
    }

    static int sigOf(PpcException e) {
        if (e.vector == PpcException.PROGRAM) {
            if ((e.srr1 & PpcException.SRR1_FP) != 0) return 8;
            return 4;
        }
        if (e.vector == PpcException.DSI) return 11;
        if (e.vector == PpcException.ALIGNMENT) return 7;
        return 1000 + e.vector;
    }

    /** Run one case; returns null on success or a description of the mismatch. */
    static String run(DecodeTable table, Case c, boolean bigEndian) {
        State in = new State();
        in.apply(c.in());
        State exp = in.copy();
        exp.apply(c.out());

        CpuState s = new CpuState();
        s.msr = Msr.SF | (bigEndian ? 0 : Msr.LE) | Msr.FP | Msr.VEC | Msr.VSX | Msr.FE0 | Msr.FE1 | Msr.PR;
        TestMemory m = new TestMemory(s, MEMBASE, MEMSIZE);
        System.arraycopy(in.mem, 0, m.bytes, 0, MEMSIZE);
        s.mem = m;
        s.fpscr = in.fpscr;
        s.cr = in.cr;
        s.vscr = in.vscr;
        System.arraycopy(in.gpr, 0, s.gpr, 0, 32);
        System.arraycopy(in.hi, 0, s.vsrHi, 0, 64);
        System.arraycopy(in.lo, 0, s.vsrLo, 0, 64);
        s.pc = 0x1000;
        s.npc = 0x1004;

        State got = new State();
        DecodeTable.Entry e = table.lookup(c.insn());
        if (e == null) {
            got.sig = 4;
        } else {
            try {
                e.op().exec(s, c.insn());
            } catch (PpcException ex) {
                got.sig = sigOf(ex);
            } catch (RuntimeException ex) {
                return "threw " + ex;
            }
        }
        got.fpscr = s.fpscr;
        got.cr = s.cr;
        got.vscr = s.vscr;
        System.arraycopy(s.gpr, 0, got.gpr, 0, 32);
        System.arraycopy(s.vsrHi, 0, got.hi, 0, 64);
        System.arraycopy(s.vsrLo, 0, got.lo, 0, 64);
        got.mem = m.bytes.clone();
        String d = exp.diff(got);
        if (d.isEmpty()) return null;
        return (e == null ? "[undecoded] " : "") + d.trim();
    }
}
