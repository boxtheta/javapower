package org.boxtheta.javapower.cpu;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.core.Spr;

/**
 * Replays golden vectors produced by {@code cpu-int/difftest} (bare-metal firmware under
 * qemu-system-ppc64 -M pseries). The default state and machine setup here mirror gen.py and
 * harness.c: each case starts at {@code pc} with the given MSR (as if entered by rfid) and ends
 * at the first interrupt, whose vector and the full register state are compared.
 */
final class Golden {
    static final long SLOT = 0x00200000L;
    static final long WIN = 0x002FFF00L;
    static final int WINSIZE = 512;
    static final long PROC_TABLE = 0x400000L;
    static final long DAR_IN = 0x0000DA00DA00DA00L;
    static final long DEFAULT_LR = 0x1111111111111110L;
    static final long DEFAULT_CTR = 0x2222222222222222L;

    /** Case.spr[] order: SPR number for mtspr / mfspr. */
    static final int[] SPR_W = {29, 61, 157, 48, 153, 815, 17, 272, 275, 805, 806, 896, 256, 152, 274, 804};
    static final int[] SPR_R = {29, 61, 157, 48, 153, 815, 17, 272, 275, 805, 806, 896, 256, 136, 274, 804};
    static final String[] SPR_NAMES = {"AMR", "IAMR", "UAMOR", "PIDR", "FSCR", "TAR", "DSCR", "SPRG0",
        "SPRG3", "EBBRR", "BESCR", "PPR", "VRSAVE", "CTRL", "SPRG2", "EBBHR"};

    /**
     * LPCR as QEMU's pseries has it after reset, H_SET_MODE(LE) and H_REGISTER_PROC_TBL(radix, GTSE).
     * Cases from the big-endian harness carry {@code ile=0}: it skips H_SET_MODE(LE).
     */
    static final long LPCR = Interpreter.LPCR_LPES0 | Interpreter.LPCR_LPES1 | Interpreter.LPCR_EEE
            | Interpreter.LPCR_DEE | Interpreter.LPCR_OEE | Interpreter.LPCR_PDEE | Interpreter.LPCR_HDEE
            | Spr.LPCR_LD | Spr.LPCR_ILE | Spr.LPCR_UPRT | Spr.LPCR_HR | Spr.LPCR_GTSE;

    record Case(String group, String name, Map<String, String> in, Map<String, String> out, String inText) {}

    static long defaultGpr(int n) {
        return (0x0101010101010101L * (n + 1)) ^ 0x8000000000000000L;
    }

    static byte[] defaultMem() {
        byte[] b = new byte[WINSIZE];
        for (int i = 0; i < WINSIZE; i++) {
            b[i] = (byte) (i * 37 + 11);
        }
        return b;
    }

    static List<Case> load(String group) throws IOException {
        InputStream raw = Golden.class.getResourceAsStream("/golden-int/" + group + ".txt.gz");
        if (raw == null) {
            throw new IOException("missing golden file " + group);
        }
        List<Case> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] p = line.split("\\|", -1);
                out.add(new Case(group, p[0], tokens(p[1]), tokens(p[2]), p[1]));
            }
        }
        return out;
    }

    private static Map<String, String> tokens(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String t : s.trim().split(" +")) {
            if (t.isEmpty()) {
                continue;
            }
            int eq = t.indexOf('=');
            m.put(t.substring(0, eq), t.substring(eq + 1));
        }
        return m;
    }

    static long hex(String s) {
        return Long.parseUnsignedLong(s, 16);
    }

    /** One reusable machine: memory, CPU state and interpreter. */
    static final class Machine {
        final PhysicalMemory pm = new PhysicalMemory(8L << 20);
        final CpuState s = new CpuState();
        final Interpreter cpu;
        int lastVector;
        final List<Long> patched = new ArrayList<>();

        Machine(DecodeTable t) {
            // QEMU's spapr answers the opcodes the tests use (none valid) with H_FUNCTION.
            s.hv = st -> st.gpr[3] = -2;
            cpu = new Interpreter(s, pm, t);
            cpu.interruptHook = (st, vec, srr0) -> {
                lastVector = (int) st.pc;
                st.stopRequested = true;
            };
        }

        /** Run one case; returns null if it matches, else a description of the differences. */
        String run(Case c) {
            Map<String, String> in = c.in();
            cpu.resetPapr();
            s.spr[Spr.LPCR] = LPCR;
            s.procTable = PROC_TABLE;
            s.stopRequested = false;
            s.icount = 0;
            pm.fill(SLOT, 4096, (byte) 0);
            for (long a : patched) {
                pm.write64be(a, 0);
            }
            patched.clear();
            long msr = hex(in.get("msr"));
            long pc = hex(in.get("pc"));
            boolean le = (msr & Msr.LE) != 0;
            String[] code = in.get("code").split(",");
            for (int k = 0; k < code.length; k++) {
                int w = (int) hex(code[k]);
                if (le) {
                    pm.write32le(SLOT + 4L * k, w);
                } else {
                    pm.write32be(SLOT + 4L * k, w);
                }
            }
            for (int k = 0; k < 32; k++) {
                s.gpr[k] = defaultGpr(k);
            }
            s.cr = 0;
            s.setXer(0);
            s.lr = DEFAULT_LR;
            s.ctr = DEFAULT_CTR;
            long[] sprIn = new long[16];
            byte[] mem = defaultMem();
            for (Map.Entry<String, String> e : in.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                switch (k) {
                    case "cr" -> s.cr = (int) hex(v);
                    case "xer" -> s.setXer(hex(v));
                    case "lr" -> s.lr = hex(v);
                    case "ctr" -> s.ctr = hex(v);
                    case "msr", "pc", "code" -> {}
                    case "ail" -> s.spr[Spr.LPCR] |= hex(v) << Spr.LPCR_AIL_SHIFT;
                    case "ile" -> { // big-endian harness: interrupts delivered with LPCR[ILE]=0
                        if (hex(v) == 0) {
                            s.spr[Spr.LPCR] &= ~Spr.LPCR_ILE;
                        }
                    }
                    case "cfar" -> {}
                    default -> inputToken(k, v, sprIn, mem);
                }
            }
            pm.write(WIN, mem, 0, WINSIZE);
            // The harness writes the SPRs in supervisor state, then DAR, DSISR, DEC, SPRG1.
            s.msr = Interpreter.MSR_MASK & ~(Msr.PR | Msr.IR | Msr.DR | Msr.EE);
            for (int k = 0; k < 16; k++) {
                mtspr(SPR_W[k], sprIn[k]);
            }
            s.spr[Spr.SPRG1] = 0x5151515151515151L;
            if (in.containsKey("cfar")) {
                s.spr[Spr.CFAR] = hex(in.get("cfar"));
            }
            s.spr[Spr.DAR] = DAR_IN;
            s.spr[Spr.DSISR] = 0;
            cpu.storeDec(0x7fffffffL);
            s.spr[Spr.SRR0] = pc;
            s.spr[Spr.SRR1] = msr;
            // The harness enters the case by rfid, which sets EE, IR and DR along with PR.
            s.msr = (msr & Msr.PR) != 0 ? msr | Msr.EE | Msr.IR | Msr.DR : msr;
            s.pc = pc;
            s.resValid = false;
            cpu.mmu().flushAll();

            lastVector = -1;
            cpu.run(10_000);
            if (lastVector < 0) {
                return "no interrupt within 10000 instructions (pc=" + Long.toHexString(s.pc) + ")";
            }
            return compare(c, mem, sprIn);
        }

        private void inputToken(String k, String v, long[] sprIn, byte[] mem) {
            switch (k.charAt(0)) {
                case 'r' -> s.gpr[Integer.parseInt(k.substring(1))] = hex(v);
                case 's' -> sprIn[Integer.parseInt(k.substring(1))] = hex(v);
                case 'p' -> {
                    long a = hex(k.substring(1));
                    pm.write64be(a, hex(v));
                    patched.add(a);
                }
                case 'm' -> {
                    int off = Integer.parseInt(k.substring(1));
                    for (int b = 0; b < v.length() / 2; b++) {
                        mem[off + b] = (byte) Integer.parseInt(v.substring(2 * b, 2 * b + 2), 16);
                    }
                }
                default -> throw new IllegalArgumentException("unknown input " + k);
            }
        }

        private void mtspr(int n, long v) {
            long save = s.gpr[0];
            s.gpr[0] = v;
            SprAccess.mtspr(s, (31 << 26) | ((n & 0x1f) << 16) | ((n >>> 5) << 11) | (467 << 1));
            s.gpr[0] = save;
        }

        private long mfspr(int n) {
            long save = s.gpr[0];
            long msr = s.msr;
            s.msr &= ~Msr.PR;
            SprAccess.mfspr(s, (31 << 26) | ((n & 0x1f) << 16) | ((n >>> 5) << 11) | (339 << 1));
            long v = s.gpr[0];
            s.gpr[0] = save;
            s.msr = msr;
            return v;
        }

        private String compare(Case c, byte[] memIn, long[] sprIn) {
            Map<String, String> out = c.out();
            Set<String> ignore = new HashSet<>();
            if (out.containsKey("x")) {
                ignore.addAll(List.of(out.get("x").split(",")));
            }
            StringBuilder d = new StringBuilder();
            long[] gprExp = new long[32];
            long[] gprIn = new long[32];
            for (int k = 0; k < 32; k++) {
                gprIn[k] = c.in().containsKey("r" + k) ? hex(c.in().get("r" + k)) : defaultGpr(k);
                gprExp[k] = gprIn[k];
            }
            long lrIn = c.in().containsKey("lr") ? hex(c.in().get("lr")) : DEFAULT_LR;
            long ctrIn = c.in().containsKey("ctr") ? hex(c.in().get("ctr")) : DEFAULT_CTR;
            long lrExp = lrIn;
            long ctrExp = ctrIn;
            long[] sprExp = sprIn.clone();
            byte[] memExp = memIn.clone();
            Map<Long, Long> patchExp = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : out.entrySet()) {
                String k = e.getKey();
                String v = e.getValue();
                switch (k) {
                    case "vec", "srr0", "srr1", "dar", "dsisr", "hmsr", "cr", "xer", "x" -> {}
                    case "lr" -> lrExp = hex(v);
                    case "ctr" -> ctrExp = hex(v);
                    default -> {
                        switch (k.charAt(0)) {
                            case 'r' -> gprExp[Integer.parseInt(k.substring(1))] = hex(v);
                            case 's' -> sprExp[Integer.parseInt(k.substring(1))] = hex(v);
                            case 'q' -> patchExp.put(hex(k.substring(1)), hex(v));
                            case 'm' -> {
                                int off = Integer.parseInt(k.substring(1));
                                for (int b = 0; b < v.length() / 2; b++) {
                                    memExp[off + b] = (byte) Integer.parseInt(v.substring(2 * b, 2 * b + 2), 16);
                                }
                            }
                            default -> throw new IllegalArgumentException("unknown output " + k);
                        }
                    }
                }
            }
            check(d, ignore, "vec", hex(out.get("vec")), lastVector);
            check(d, ignore, "srr0", hex(out.get("srr0")), s.spr[Spr.SRR0]);
            check(d, ignore, "srr1", hex(out.get("srr1")), s.spr[Spr.SRR1]);
            check(d, ignore, "dar", hex(out.get("dar")), s.spr[Spr.DAR]);
            check(d, ignore, "dsisr", hex(out.get("dsisr")), s.spr[Spr.DSISR]);
            check(d, ignore, "hmsr", hex(out.get("hmsr")), s.msr);
            check(d, ignore, "cr", hex(out.get("cr")), s.cr & 0xffffffffL);
            check(d, ignore, "xer", hex(out.get("xer")), s.getXer());
            for (int k = 0; k < 32; k++) {
                check(d, ignore, "r" + k, gprExp[k], s.gpr[k]);
            }
            check(d, ignore, "lr", lrExp, s.lr);
            check(d, ignore, "ctr", ctrExp, s.ctr);
            for (int k = 0; k < 16; k++) {
                check(d, ignore, "s" + k + "(" + SPR_NAMES[k] + ")", sprExp[k], mfspr(SPR_R[k]));
            }
            for (Map.Entry<Long, Long> e : patchExp.entrySet()) {
                check(d, ignore, "q" + Long.toHexString(e.getKey()), e.getValue(), pm.read64be(e.getKey()));
            }
            byte[] memNow = new byte[WINSIZE];
            pm.read(WIN, memNow, 0, WINSIZE);
            for (int b = 0; b < WINSIZE; b++) {
                if (memNow[b] != memExp[b] && !ignore.contains("m")) {
                    d.append(String.format(" m%d: want %02x got %02x", b, memExp[b] & 0xff, memNow[b] & 0xff));
                    break;
                }
            }
            return d.isEmpty() ? null : d.toString();
        }

        private static void check(StringBuilder d, Set<String> ignore, String name, long want, long got) {
            String base = name.contains("(") ? name.substring(0, name.indexOf('(')) : name;
            if (want != got && !ignore.contains(base)) {
                d.append(String.format(" %s: want %x got %x", name, want, got));
            }
        }
    }
}
