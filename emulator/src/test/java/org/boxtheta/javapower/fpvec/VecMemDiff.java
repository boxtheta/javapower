package org.boxtheta.javapower.fpvec;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.MmioBus;
import org.boxtheta.javapower.core.MmioHandler;
import org.boxtheta.javapower.core.MmioRegion;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.core.PpcException;
import org.boxtheta.javapower.core.Spr;
import org.boxtheta.javapower.cpu.MmuProbe;
import org.boxtheta.javapower.cpu.RadixMmu;

/**
 * Randomized differential check of the VMX and VSX load and store handlers against the
 * reference {@link LdStRef} (the handlers before they took the MMU's fast path), on two
 * machines with real RAM and a real {@link RadixMmu}: every form runs on identical states in
 * both, in both byte orders and computation modes, with translation on (radix, in quadrants 0
 * and 3, supervisor and problem state) and off, with the vector facilities sometimes off, at
 * aligned and unaligned addresses that lie in an ordinary page, cross into the next one, or
 * touch an unmapped, read-only, unassigned (machine check), MMIO or no-change-bit page, a page
 * flagged as holding translated code, or the page of the compiled-block store guard. The TLB
 * is warm most of the time (the fast path) and flushed now and then (the walk). Afterwards
 * the exception (vector, DAR, DSISR, SRR1, machine check type), all registers, guest RAM, the
 * MMIO accesses made, the code generations, the store guard's flag and the TLB must agree.
 */
final class VecMemDiff {
    static final long RAM = 0x80000;
    // Effective (and, identity-mapped, real) pages of the data window.
    static final long DATA = 0x10000;
    static final long UNMAPPED = 0x14000;
    static final long READONLY = 0x15000;
    static final long CODE = 0x16000;
    static final long GUARD = 0x17000;
    static final long UNASSIGNED_EA = 0x18000; // maps to real 0x88000, past RAM
    static final long MMIO_EA = 0x19000; // maps to real MMIO_PA
    static final long NOCHANGE = 0x1c000; // PTE without C: stores fault
    static final long END = 0x1d000;
    static final long MMIO_PA = 0x90000;
    static final long UNASSIGNED_PA = 0x88000;
    static final long ROOT = 0x60000;
    static final long T1 = 0x70000;
    static final long T2 = 0x71000;
    static final long T3 = 0x72000;
    static final long PRTB = 0x73000;

    static final DecodeTable NEW = new DecodeTable();

    static {
        FpuSets.registerAll(NEW);
    }

    /** One machine: RAM, MMU, MMIO log. */
    static final class M {
        final PhysicalMemory pm = new PhysicalMemory(RAM);
        final CpuState s = new CpuState();
        final MmioBus bus = new MmioBus();
        final RadixMmu mmu;
        final StringBuilder mmio = new StringBuilder();
        /** Code generation, code write count and walks before the case. */
        long gen, writes, walks;

        /** The changes of those during the case. */
        String deltas() {
            return (pm.codeGeneration(CODE) - gen) + "/" + (pm.codeWriteCount() - writes) + "/"
                    + (MmuProbe.walks(mmu) - walks);
        }

        M(byte[] init) {
            bus.map(new MmioRegion("t", MMIO_PA, 0x1000, ByteOrder.BIG_ENDIAN, new MmioHandler() {
                @Override
                public long read(long offset, int size) {
                    mmio.append('r').append(offset).append('/').append(size).append(' ');
                    return 0x0123456789abcdefL * (offset + 1) >>> (64 - 8 * size);
                }

                @Override
                public void write(long offset, int size, long value) {
                    mmio.append('w').append(offset).append('/').append(size).append('=')
                            .append(Long.toHexString(value)).append(' ');
                }
            }));
            mmu = new RadixMmu(s, pm, bus);
            s.mem = mmu;
            pm.write(0, init, 0, init.length);
            s.procTable = PRTB;
            s.spr[Spr.LPCR] |= Spr.LPCR_HR | Spr.LPCR_UPRT;
            long rw = 0x2 | 0x4; // EAA RW, R
            long leaf = 0x8000000000000000L | 0x4000000000000000L | 0x100 | 0x80 | rw;
            pm.write64be(PRTB, (1L << 62) | 0xA0 | ROOT | 13);
            pm.write64be(ROOT, 0x8000000000000000L | T1 | 9);
            pm.write64be(T1, 0x8000000000000000L | T2 | 9);
            pm.write64be(T2, 0x8000000000000000L | T3 | 9);
            for (long ea = DATA; ea < END; ea += 0x1000) {
                long pte = leaf | ea;
                if (ea == UNMAPPED) {
                    pte = 0;
                } else if (ea == READONLY) {
                    pte &= ~0x2L;
                } else if (ea == UNASSIGNED_EA) {
                    pte = leaf | UNASSIGNED_PA;
                } else if (ea == MMIO_EA) {
                    pte = leaf | MMIO_PA;
                } else if (ea == NOCHANGE) {
                    pte &= ~0x80L;
                }
                pm.write64be(T3 + 8 * (ea >>> 12), pte);
            }
            MmuProbe.guard(mmu, GUARD, GUARD + 0x1000);
        }

        byte[] ram() {
            byte[] b = new byte[(int) RAM];
            pm.read(0, b, 0, b.length);
            return b;
        }

        byte[] window() {
            byte[] b = new byte[(int) (END - DATA) + 0x1000];
            pm.read(DATA, b, 0, (int) (END - DATA));
            pm.read(RAM - 0x1000, b, (int) (END - DATA), 0x1000);
            return b;
        }
    }

    final Map<String, long[]> counts = new LinkedHashMap<>();
    final StringBuilder report = new StringBuilder();
    long cases, mismatches, faults, fullChecks;

    private final SplittableRandom r;
    private final M x, y;

    VecMemDiff(long seed) {
        r = new SplittableRandom(seed);
        byte[] init = new byte[(int) RAM];
        for (int k = 0; k < init.length; k += 8) {
            long v = r.nextLong();
            for (int j = 0; j < 8; j++) init[k + j] = (byte) (v >>> (8 * j));
        }
        x = new M(init);
        y = new M(init);
    }

    void runAll(int n) {
        for (DecodeTable.Entry e : LdStRef.ENTRIES) run(e, n);
        fullCheck("end");
    }

    void run(DecodeTable.Entry e, int n) {
        String key = e.name();
        for (DecodeTable.Entry o : LdStRef.ENTRIES) {
            if (o == e) break;
            if (o.name().equals(e.name())) key = e.name() + "@" + Integer.toHexString(e.match());
        }
        long[] c = counts.computeIfAbsent(key, k -> new long[3]);
        for (int k = 0; k < n; k++) {
            int insn = setUp(e);
            DecodeTable.Entry ne = NEW.lookup(insn);
            if (ne == null || !ne.name().equals(e.name())) {
                c[1]++;
                mismatches++;
                fail(e.name(), insn, "decodes to " + (ne == null ? "nothing" : ne.name()));
                continue;
            }
            String before = describe(insn);
            PpcException ex = exec(e, x, insn), ey = exec(ne, y, insn);
            c[0]++;
            cases++;
            if (ex != null) {
                c[2]++;
                faults++;
            }
            String d = diff(ex, ey, (cases & 63) == 0);
            if (d != null) {
                c[1]++;
                mismatches++;
                fail(e.name(), insn, d + "\n     before: " + before);
                resync();
            }
        }
    }

    private static PpcException exec(DecodeTable.Entry e, M m, int insn) {
        try {
            e.op().exec(m.s, insn);
            return null;
        } catch (PpcException p) {
            return p;
        }
    }

    private void fail(String name, int insn, String d) {
        if (mismatches <= 20) {
            report.append(String.format("%n  %s %08x: %s", name, insn, d));
        }
    }

    // ------------------------------------------------------------------ cases

    /** Size of the access of a form (lxvl and friends: decided by the length in RB). */
    private static int size(String n) {
        return switch (n) {
            case "lvebx", "stvebx", "lxsibzx", "stxsibx" -> 1;
            case "lvehx", "stvehx", "lxsihzx", "stxsihx" -> 2;
            case "lvewx", "stvewx", "lxsiwax", "lxsiwzx", "lxsspx", "stxsiwx", "stxsspx", "lxvwsx", "lxssp",
                    "stxssp" -> 4;
            case "lxsdx", "stxsdx", "lxvdsx", "lxsd", "stxsd" -> 8;
            default -> 16;
        };
    }

    /** A target address for an access of {@code n} bytes, and whether translation is on. */
    private long target(int n, boolean dr) {
        int kind = r.nextInt(16);
        long page = DATA + 0x1000L * r.nextInt(4); // ordinary pages
        long off = r.nextBoolean() ? r.nextInt(0x1000 / 16) * 16L : r.nextInt(0x1000);
        long crossing = 0x1000 - 1 - r.nextInt(Math.max(1, n - 1)); // starts n-1..1 bytes before the end
        return switch (kind) {
            case 0, 1, 2, 3 -> page + off;
            case 4 -> page + crossing; // into the next ordinary page
            case 5 -> UNMAPPED - 0x1000 + crossing;
            case 6 -> READONLY - 0x1000 + crossing; // from unmapped (a fault on its first part) into read-only
            case 7 -> (r.nextBoolean() ? UNMAPPED : READONLY) + off;
            case 8 -> r.nextBoolean() ? CODE + off : CODE - 0x1000 + crossing;
            case 9 -> r.nextBoolean() ? GUARD + off : GUARD + crossing;
            case 10 -> dr ? (r.nextBoolean() ? MMIO_EA : UNASSIGNED_EA) + (r.nextBoolean() ? off & ~7 : off)
                    : (r.nextBoolean() ? MMIO_PA : UNASSIGNED_PA) + (r.nextBoolean() ? off & ~7 : off);
            case 11 -> dr ? MMIO_EA - 0x1000 + crossing : RAM - 0x1000 + crossing; // into MMIO / past RAM
            case 12 -> NOCHANGE + (r.nextBoolean() ? off : crossing);
            case 13 -> dr ? 0x1d000 + off : RAM - 0x1000 + off; // unmapped (no PTE) / last page of RAM
            default -> page + 0x1000 - 16 + r.nextInt(16);
        };
    }

    /** Set up both machines identically for a random case of {@code e}; returns the instruction. */
    private int setUp(DecodeTable.Entry e) {
        String n = e.name();
        int insn = e.match() | (r.nextInt() & ~e.mask());
        boolean le = r.nextBoolean(), sf = r.nextInt(4) != 0, dr = r.nextInt(3) != 0, pr = dr && r.nextInt(4) == 0;
        long msr = Msr.ME | (sf ? Msr.SF : 0) | (le ? Msr.LE : 0) | (dr ? Msr.IR | Msr.DR : 0) | (pr ? Msr.PR : 0)
                | Msr.FP | Msr.VEC | Msr.VSX;
        if (r.nextInt(24) == 0) msr &= ~Msr.VEC;
        if (r.nextInt(24) == 0) msr &= ~Msr.VSX;
        if (r.nextInt(48) == 0) msr &= ~Msr.FP;
        int size = size(n);
        boolean lenForm = n.startsWith("lxvl") || n.startsWith("stxvl");
        long nb = 0;
        if (lenForm) {
            int len = switch (r.nextInt(6)) {
                case 0 -> 0;
                case 1 -> 16;
                case 2 -> 17 + r.nextInt(239);
                default -> 1 + r.nextInt(15);
            };
            nb = ((long) len << 56) | (r.nextLong() >>> 8);
            size = Math.max(1, Math.min(len, 16));
        }
        long a = target(size, dr);
        if (dr && sf && r.nextInt(4) == 0) a |= 0xC000000000000000L; // quadrant 3
        CpuState s = x.s;
        Arrays.fill(s.gpr, 0);
        for (int k = 0; k < 32; k++) s.gpr[k] = r.nextLong();
        int ra = r.nextInt(4) == 0 ? 0 : 1 + r.nextInt(31);
        int rb = 1 + r.nextInt(31);
        long hiBits = sf ? 0 : r.nextLong() & 0xffffffff00000000L; // ignored in 32-bit mode
        boolean dq = (insn >>> 26) == 61 && (insn & 3) == 1; // lxv, stxv
        boolean ds = !dq && ((insn >>> 26) == 57 || (insn >>> 26) == 61);
        if (dq || ds) {
            long disp = dq ? (short) (r.nextInt() & 0xfff0) : (short) (r.nextInt() & 0xfffc);
            insn = (insn & ~(dq ? 0xfff0 : 0xfffc)) | (int) (disp & (dq ? 0xfff0 : 0xfffc));
            if (ra == 0) {
                ra = 1 + r.nextInt(31);
            }
            insn = (insn & ~(31 << 16)) | (ra << 16);
            s.gpr[ra] = a - disp + hiBits;
            if (r.nextInt(8) == 0) {
                insn &= ~(31 << 16); // RA = 0: the displacement alone
            }
        } else if (lenForm) {
            if (ra == 0) ra = 1 + r.nextInt(31);
            if (rb == ra) rb = ra % 31 + 1;
            insn = (insn & ~(31 << 16) & ~(31 << 11)) | (ra << 16) | (rb << 11);
            s.gpr[ra] = a + hiBits;
            s.gpr[rb] = nb;
        } else {
            insn = (insn & ~(31 << 16) & ~(31 << 11)) | (ra << 16) | (rb << 11);
            if (ra == 0) {
                s.gpr[rb] = a + hiBits;
            } else if (rb == ra) {
                s.gpr[ra] = (a >>> 1) + hiBits; // RA = RB: EA = 2 * GPR
                if ((a & 1) != 0) s.gpr[ra] = r.nextLong(); // odd: just a random address
            } else {
                long part = r.nextLong();
                s.gpr[ra] = part;
                s.gpr[rb] = a - part + hiBits;
            }
        }
        for (int v = 0; v < 64; v++) {
            if (r.nextInt(8) == 0) {
                s.vsrHi[v] = r.nextLong();
                s.vsrLo[v] = r.nextLong();
            }
        }
        s.msr = msr;
        s.pc = 0x1000;
        s.npc = 0x1004;
        if (r.nextInt(64) == 0) {
            x.mmu.flushAll();
            y.mmu.flushAll();
        }
        copy(x.s, y.s);
        for (M m : new M[] {x, y}) {
            m.pm.watchCode(CODE);
            MmuProbe.guard(m.mmu, GUARD, GUARD + 0x1000);
            m.mmio.setLength(0);
            m.gen = m.pm.codeGeneration(CODE);
            m.writes = m.pm.codeWriteCount();
            m.walks = MmuProbe.walks(m.mmu);
        }
        return insn;
    }

    private static void copy(CpuState a, CpuState b) {
        System.arraycopy(a.gpr, 0, b.gpr, 0, 32);
        System.arraycopy(a.vsrHi, 0, b.vsrHi, 0, 64);
        System.arraycopy(a.vsrLo, 0, b.vsrLo, 0, 64);
        b.msr = a.msr;
        b.pc = a.pc;
        b.npc = a.npc;
        b.cr = a.cr;
        b.fpscr = a.fpscr;
        b.vscr = a.vscr;
    }

    private String describe(int insn) {
        CpuState s = x.s;
        return String.format("msr=%x ra=%x rb=%x", s.msr, s.gpr[(insn >>> 16) & 31], s.gpr[(insn >>> 11) & 31]);
    }

    // ------------------------------------------------------------------ comparison

    private String diff(PpcException ex, PpcException ey, boolean full) {
        if ((ex == null) != (ey == null)) {
            return "exception " + ex + " vs " + ey;
        }
        if (ex != null) {
            if (ex.vector != ey.vector || ex.srr1 != ey.srr1 || ex.dar != ey.dar || ex.dsisr != ey.dsisr
                    || ex.setsDar != ey.setsDar || ex.aux != ey.aux) {
                return String.format("exception %x dar=%x dsisr=%x srr1=%x vs %x dar=%x dsisr=%x srr1=%x", ex.vector,
                        ex.dar, ex.dsisr, ex.srr1, ey.vector, ey.dar, ey.dsisr, ey.srr1);
            }
            if (ex.vector == PpcException.MACHINE_CHECK && MmuProbe.mcType(x.mmu) != MmuProbe.mcType(y.mmu)) {
                return "machine check type";
            }
        }
        CpuState a = x.s, b = y.s;
        for (int k = 0; k < 32; k++) {
            if (a.gpr[k] != b.gpr[k]) return String.format("r%d %x vs %x", k, a.gpr[k], b.gpr[k]);
        }
        for (int k = 0; k < 64; k++) {
            if (a.vsrHi[k] != b.vsrHi[k] || a.vsrLo[k] != b.vsrLo[k]) {
                return String.format("vsr%d %016x%016x vs %016x%016x", k, a.vsrHi[k], a.vsrLo[k], b.vsrHi[k],
                        b.vsrLo[k]);
            }
        }
        if (a.msr != b.msr || a.cr != b.cr || a.fpscr != b.fpscr || a.vscr != b.vscr || a.npc != b.npc) {
            return "msr/cr/fpscr/vscr/npc";
        }
        if (!Arrays.equals(x.window(), y.window())) {
            return "memory (data window): " + firstDiff(x.window(), y.window());
        }
        if (!x.mmio.toString().equals(y.mmio.toString())) {
            return "mmio [" + x.mmio + "] vs [" + y.mmio + "]";
        }
        if (MmuProbe.guardHit(x.mmu) != MmuProbe.guardHit(y.mmu)) {
            return "store guard " + MmuProbe.guardHit(x.mmu) + " vs " + MmuProbe.guardHit(y.mmu);
        }
        if (!x.deltas().equals(y.deltas())) {
            return "code generation / code writes / walks changed by " + x.deltas() + " vs " + y.deltas();
        }
        if (full) {
            return fullCheck(null);
        }
        return null;
    }

    /** Compare all of RAM and the TLB; with a label, a difference is recorded as a mismatch. */
    String fullCheck(String label) {
        fullChecks++;
        String d = null;
        if (!Arrays.equals(x.ram(), y.ram())) {
            d = "memory: " + firstDiff(x.ram(), y.ram());
        } else if (!Arrays.equals(MmuProbe.tlb(x.mmu), MmuProbe.tlb(y.mmu))) {
            d = "TLB contents";
        }
        if (d != null && label != null) {
            mismatches++;
            report.append("\n  ").append(label).append(": ").append(d);
        }
        return d;
    }

    private static String firstDiff(byte[] a, byte[] b) {
        int i = Arrays.mismatch(a, b);
        return String.format("at %x: %02x vs %02x", i, a[i] & 0xff, b[i] & 0xff);
    }

    /** After a mismatch: make y equal to x again so later cases are meaningful. */
    private void resync() {
        copy(x.s, y.s);
        byte[] ram = x.ram();
        y.pm.write(0, ram, 0, ram.length);
        x.mmu.flushAll();
        y.mmu.flushAll();
    }

    String summary() {
        List<String> l = new ArrayList<>();
        counts.forEach((k, v) -> l.add(k + ":" + v[0] + (v[2] > 0 ? "/" + v[2] + "f" : "") + (v[1] > 0 ? "!" + v[1] : "")));
        return cases + " cases (" + faults + " faulting), " + mismatches + " mismatches, " + fullChecks
                + " full RAM/TLB checks: " + String.join(" ", l);
    }
}
