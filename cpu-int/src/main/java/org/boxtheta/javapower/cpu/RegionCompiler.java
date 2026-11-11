package org.boxtheta.javapower.cpu;

import static java.lang.constant.ConstantDescs.CD_boolean;
import static java.lang.constant.ConstantDescs.CD_int;
import static java.lang.constant.ConstantDescs.CD_long;
import static java.lang.constant.ConstantDescs.CD_void;

import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.DynamicConstantDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.boxtheta.javapower.core.Op;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.core.Spr;
import org.boxtheta.javapower.cpu.internal.asm.Label;

/**
 * The second compilation tier: translates a <em>region</em> of guest code (every basic block
 * reachable from an entry point through direct branches and fall-through without leaving the
 * entry's 4 KiB page) into one JVM method, so loops inside a page run as JVM loops.
 *
 * <h2>What the generated code does</h2>
 * Guest state the region uses (GPRs, CR, LR, CTR, the XER bits, CFAR) lives in JVM locals: loaded
 * on entry, written back on every exit. Common integer, branch, CR and load/store instructions
 * are generated directly; their semantics mirror the handlers in {@link Alu}, {@link LoadStore},
 * {@link BranchInsns} and {@link Interpreter} exactly. Loads and stores take the software TLB's
 * hit path inline ({@link JitRt#xlate}) and fall back to the MMU's full path for everything else
 * (misses, page-crossing accesses, faults), so TLB contents evolve exactly as when interpreting.
 * Every other instruction calls its handler, as the run loop would: locals the handler may read
 * are written back first, {@code pc}, {@code npc} and {@code icount} are set, and afterwards the
 * cached values are treated as stale and reloaded before their next use.
 *
 * <h2>Exactness</h2>
 * <ul>
 * <li>Instruction count and chunk budget: every basic block starts with a check that it fits
 * before {@link Interpreter#chunkEnd}; if not, the region exits and the run loop interprets the
 * rest of the chunk. Asynchronous interrupts and decrementer timing therefore happen at exactly
 * the instruction counts they would when interpreting.
 * <li>After a handler call the region exits if the handler changed {@code npc} (a branch,
 * {@code rfid}), set {@link Interpreter#recheck} (MSR, timer, TLB changes: the run loop ends its
 * chunk there too) or stored into the region's page (the MMU's store guard). Directly generated
 * stores check the page they hit: a store into translated code (any CPU's, see
 * {@link PhysicalMemory#watchCode}) or into the region's own page ends the region after it.
 * <li>A fault (a {@link org.boxtheta.javapower.core.PpcException} from a slow path or handler)
 * is caught by one handler that writes back the locals, sets {@code pc} and {@code icount} of
 * the faulting instruction from tables indexed by a site number, and delivers it as the run loop
 * does. Directly generated instructions write their results only after everything that can
 * fault, so the written-back state is the state before the faulting instruction.
 * <li>Regions never contain {@code isync} or another context-synchronising instruction except as
 * their last instruction, so a CPU sees code changed by another CPU (which advances the page's
 * code generation) at the latest after its next context-synchronising instruction.
 * </ul>
 *
 * <p>A region is compiled for one mode: MSR[LE], MSR[SF], MSR[DR] and MSR[PR] (see {@link #mode})
 * and one real page; the run loop uses it only when entering at the same effective address,
 * real address and mode. Generated code holds no per-CPU state, so a region can be run by any
 * CPU of the domain.
 */
final class RegionCompiler {
    private RegionCompiler() {}

    // Cached values: GPR 0..31, then these.
    static final int CR = 32;
    static final int LR = 33;
    static final int CTR = 34;
    static final int SO = 35;
    static final int OV = 36;
    static final int OV32 = 37;
    static final int CA = 38;
    static final int CA32 = 39;
    static final int CFAR = 40;
    static final int NVAL = 41;

    /** Mode bits of a region: the MSR bits its code depends on. */
    static final int MODE_LE = 1;
    static final int MODE_SF = 2;
    static final int MODE_DR = 4;
    static final int MODE_PR = 8;

    /** Region mode for MSR value {@code msr}. */
    static int mode(long msr) {
        return ((msr & org.boxtheta.javapower.core.Msr.LE) != 0 ? MODE_LE : 0)
                | (msr < 0 ? MODE_SF : 0)
                | ((msr & org.boxtheta.javapower.core.Msr.DR) != 0 ? MODE_DR : 0)
                | ((msr & org.boxtheta.javapower.core.Msr.PR) != 0 ? MODE_PR : 0);
    }

    /** Most instructions in one region. */
    static final int MAX_INSNS = 200;
    /** Largest generated method: the JVM does not JIT-compile methods over 8000 bytes of bytecode. */
    static final int MAX_CODE = 7600;

    private static final ClassDesc CD_CPU = ClassDesc.of("org.boxtheta.javapower.core.CpuState");
    private static final ClassDesc CD_OP = ClassDesc.of("org.boxtheta.javapower.core.Op");
    private static final ClassDesc CD_PPCEX = ClassDesc.of("org.boxtheta.javapower.core.PpcException");
    private static final ClassDesc CD_PM = ClassDesc.of("org.boxtheta.javapower.core.PhysicalMemory");
    private static final ClassDesc CD_INTERP = ClassDesc.of("org.boxtheta.javapower.cpu.Interpreter");
    private static final ClassDesc CD_MMU = ClassDesc.of("org.boxtheta.javapower.cpu.RadixMmu");
    private static final ClassDesc CD_RT = ClassDesc.of("org.boxtheta.javapower.cpu.JitRt");
    private static final ClassDesc CD_RAM_ACCESS = ClassDesc.of("org.boxtheta.javapower.cpu.RamAccess");
    private static final ClassDesc CD_REGION = ClassDesc.of("org.boxtheta.javapower.cpu.Region");
    private static final ClassDesc CD_LONG = ClassDesc.of("java.lang.Long");
    private static final ClassDesc CD_INT = ClassDesc.of("java.lang.Integer");
    private static final ClassDesc CD_MATH = ClassDesc.of("java.lang.Math");
    private static final ClassDesc CD_LARR = CD_long.arrayType();
    private static final ClassDesc CD_IARR = CD_int.arrayType();
    private static final MethodTypeDesc MTD_HANDLER = MethodTypeDesc.of(CD_void, CD_CPU, CD_int);
    private static final MethodTypeDesc MTD_RUN = MethodTypeDesc.of(CD_int, CD_CPU, CD_INTERP);
    private static final MethodTypeDesc MTD_FAULT = MethodTypeDesc.of(CD_void, CD_PPCEX);
    private static final MethodTypeDesc MTD_J_J = MethodTypeDesc.of(CD_long, CD_long);
    private static final MethodTypeDesc MTD_J_JJ = MethodTypeDesc.of(CD_long, CD_long, CD_long);
    private static final MethodTypeDesc MTD_J_JI = MethodTypeDesc.of(CD_long, CD_long, CD_int);
    private static final MethodTypeDesc MTD_I_JI = MethodTypeDesc.of(CD_int, CD_long, CD_int);
    private static final MethodTypeDesc MTD_I_JJ = MethodTypeDesc.of(CD_int, CD_long, CD_long);
    private static final MethodTypeDesc MTD_I_J = MethodTypeDesc.of(CD_int, CD_long);
    private static final MethodTypeDesc MTD_CR0 = MethodTypeDesc.of(CD_int, CD_int, CD_long, CD_int);
    private static final MethodTypeDesc MTD_CMP = MethodTypeDesc.of(CD_int, CD_int, CD_int, CD_long, CD_long, CD_int);
    private static final MethodTypeDesc MTD_XLATE =
            MethodTypeDesc.of(CD_long, CD_LARR, CD_LARR, CD_IARR, CD_long, CD_int, CD_int, CD_int);
    private static final MethodTypeDesc MTD_LD = MethodTypeDesc.of(CD_long, RamAccess.TYPE, CD_long);
    private static final MethodTypeDesc MTD_ST =
            MethodTypeDesc.of(CD_boolean, RamAccess.TYPE, CD_PM, CD_long, CD_long, CD_long);
    private static final MethodTypeDesc MTD_RD = MethodTypeDesc.of(CD_long, CD_MMU, CD_long);
    private static final MethodTypeDesc MTD_WR = MethodTypeDesc.of(CD_void, CD_MMU, CD_long, CD_long);
    private static final MethodTypeDesc MTD_MFOCRF = MethodTypeDesc.of(CD_long, CD_int, CD_int, CD_long);

    // Basic block terminators.
    private static final int T_FALL = 0; // falls through to `fall` (the next address)
    private static final int T_B = 1; // unconditional direct branch to `taken`
    private static final int T_BC = 2; // conditional direct branch: `taken` or `fall`
    private static final int T_BCLR = 3; // bclr: LR or `fall`
    private static final int T_BCCTR = 4; // bcctr: CTR or `fall`
    private static final int T_STOP = 5; // ends with an instruction after which the region exits

    /** Instructions after which a region always ends (context synchronising or rarely continuing). */
    private static final java.util.Set<String> STOPPERS = java.util.Set.of("isync", "sc", "scv", "rfid", "hrfid",
            "rfscv", "rfebb", "mtmsr", "mtmsrd", "wait", "stop", "tlbie", "tlbiel", "tlbsync", "slbia", "bctar");
    /** This module's handlers that touch no cached value and cannot fault (no write-back needed). */
    private static final java.util.Set<String> PURE = java.util.Set.of("sync", "eieio", "isync");

    private static boolean isPure(Insn in) {
        return in.name != null && PURE.contains(in.name);
    }

    static final class Insn {
        final long pc;
        final int word;
        final int slot;
        final Op op;
        final String name;
        /** Generated directly (else a handler call). */
        boolean nat;
        long reads;
        long writes;
        /** A directly generated instruction that can fault (a memory access). */
        boolean mem;

        Insn(long pc, int word, int slot, Op op, String name) {
            this.pc = pc;
            this.word = word;
            this.slot = slot;
            this.op = op;
            this.name = name;
        }
    }

    static final class BB {
        final long start;
        final List<Insn> insns = new ArrayList<>();
        int term = T_FALL;
        /** T_BCLR / T_BCCTR: conditional (has a fall-through successor). */
        boolean cond;
        /** T_B / T_BC: the target is an absolute address (AA=1), never inside the region. */
        boolean takenAbs;
        long taken;
        long fall;
        long dirtyIn;
        Label label;

        BB(long start) {
            this.start = start;
        }

        long end() {
            return start + 4L * insns.size();
        }
    }

    /** What the interpreter provides to a compile: decoding and code words. */
    interface Source {
        /** Dispatch slot of {@code insn} (as the run loop decodes it). */
        int slot(int insn);

        /** Handler of dispatch slot {@code slot}. */
        Op op(int slot);

        /** Registered name of the instruction, or null if it is unimplemented. */
        String name(int insn);

        /** The instruction word at offset {@code off} of the page (architectural order). */
        int word(int off);
    }

    /** A compiled region and what the run loop needs to know about it. */
    record Result(CompiledBlock code, int entryLen, int insns, int spanLo, int spanHi) {}

    /**
     * Compile the region entered at effective address {@code ea} (real address {@code pa}) in
     * mode {@code mode}. Returns null if nothing worth compiling was found.
     */
    static Result compile(long ea, long pa, int mode, Source src, PhysicalMemory pm, int maxInsns) {
        int limit = maxInsns;
        while (true) {
            Result r = tryCompile(ea, pa, mode, src, pm, limit);
            if (r != null || limit < 16) {
                return r;
            }
            limit /= 2;
        }
    }

    /** One attempt with at most {@code limit} instructions; null if the method came out too large. */
    private static Result tryCompile(long ea, long pa, int mode, Source src, PhysicalMemory pm, int limit) {
        boolean sf = (mode & MODE_SF) != 0;
        long pageEa = ea & ~0xfffL;
        Map<Long, BB> bbs = discover(ea, pageEa, sf, src, limit);
        BB entry = bbs.get(ea);
        int total = 0;
        int lo = 0x1000;
        int hi = 0;
        for (BB b : bbs.values()) {
            total += b.insns.size();
            lo = Math.min(lo, (int) (b.start - pageEa));
            hi = Math.max(hi, (int) (b.end() - pageEa));
        }
        if (total < 2) {
            return null;
        }
        Gen g = new Gen(ea, pa, mode, bbs, entry, pm);
        byte[] bytes = g.build();
        if (g.cb.codeLength() > MAX_CODE) {
            return null;
        }
        CompiledBlock code;
        try {
            MethodHandles.Lookup hl = MethodHandles.lookup().defineHiddenClassWithClassData(bytes, g.classData, true);
            code = (CompiledBlock) hl.findConstructor(hl.lookupClass(), MethodType.methodType(void.class)).invoke();
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IllegalStateException("cannot define compiled region", e);
        }
        return new Result(code, entry.insns.size(), total, lo, hi);
    }

    // ================================================================== discovery

    private static long mask(boolean sf, long a) {
        return sf ? a : a & 0xffffffffL;
    }

    /** Find the basic blocks reachable from {@code ea} within its page, at most {@code limit} instructions. */
    static Map<Long, BB> discover(long ea, long pageEa, boolean sf, Source src, int limit) {
        TreeMap<Long, BB> bbs = new TreeMap<>();
        Deque<Long> work = new ArrayDeque<>();
        work.add(ea);
        int total = 0;
        while (!work.isEmpty()) {
            long start = work.poll();
            if (bbs.containsKey(start) || (start & ~0xfffL) != pageEa) {
                continue;
            }
            Map.Entry<Long, BB> fl = bbs.floorEntry(start);
            if (fl != null && start < fl.getValue().end()) {
                split(bbs, fl.getValue(), start);
                continue;
            }
            if (total >= limit) {
                continue; // stays an exit
            }
            BB b = new BB(start);
            bbs.put(start, b);
            long pc = start;
            while (true) {
                int off = (int) (pc - pageEa);
                int word = src.word(off);
                int slot = src.slot(word);
                String name = src.name(word);
                Insn in = new Insn(pc, word, slot, src.op(slot), name);
                analyze(in, sf);
                b.insns.add(in);
                total++;
                long next = mask(sf, pc + 4);
                b.fall = next;
                int t = terminator(in, pc, sf);
                if (t != T_FALL) {
                    b.term = t;
                    if (t == T_B || t == T_BC) {
                        b.taken = branchTarget(in, pc, sf);
                        b.takenAbs = (word & 2) != 0;
                        if (!b.takenAbs) {
                            work.add(b.taken);
                        }
                    }
                    if (t == T_BCLR || t == T_BCCTR) {
                        int bo = (word >>> 21) & 31;
                        b.cond = (bo & 0x14) != 0x14;
                    }
                    if (t == T_BC || ((t == T_BCLR || t == T_BCCTR) && b.cond)) {
                        work.add(next);
                    }
                    break;
                }
                if ((next & ~0xfffL) != pageEa || total >= limit) {
                    break; // page end or size limit: falls through to an exit
                }
                if (bbs.containsKey(next)) {
                    break; // falls into an existing block
                }
                pc = next;
            }
            if (b.term == T_FALL) {
                work.add(b.fall);
            }
        }
        // Keep discovery order stable for layout: entry first, then by address.
        Map<Long, BB> ordered = new java.util.LinkedHashMap<>();
        ordered.put(ea, bbs.get(ea));
        for (Map.Entry<Long, BB> e : bbs.entrySet()) {
            ordered.putIfAbsent(e.getKey(), e.getValue());
        }
        return ordered;
    }

    private static void split(TreeMap<Long, BB> bbs, BB b, long at) {
        BB tail = new BB(at);
        int k = (int) ((at - b.start) / 4);
        tail.insns.addAll(b.insns.subList(k, b.insns.size()));
        b.insns.subList(k, b.insns.size()).clear();
        tail.term = b.term;
        tail.cond = b.cond;
        tail.taken = b.taken;
        tail.takenAbs = b.takenAbs;
        tail.fall = b.fall;
        b.term = T_FALL;
        b.fall = at;
        bbs.put(at, tail);
    }

    private static int terminator(Insn in, long pc, boolean sf) {
        String n = in.name;
        if (n == null || in.op == Fields.INVALID) {
            return T_STOP;
        }
        switch (n) {
            case "b" -> {
                return T_B;
            }
            case "bc" -> {
                int bo = (in.word >>> 21) & 31;
                return (bo & 0x14) == 0x14 ? T_B : T_BC;
            }
            case "bclr" -> {
                return T_BCLR;
            }
            case "bcctr" -> {
                return T_BCCTR;
            }
            default -> {
                return STOPPERS.contains(n) ? T_STOP : T_FALL;
            }
        }
    }

    private static long branchTarget(Insn in, long pc, boolean sf) {
        int i = in.word;
        long t;
        if (in.name.equals("b")) {
            long li = ((i << 6) >> 6) & ~3L;
            t = (i & 2) != 0 ? li : pc + li;
        } else {
            long bd = (short) (i & 0xfffc);
            t = (i & 2) != 0 ? bd : pc + bd;
        }
        return mask(sf, t);
    }

    // ================================================================== footprints

    private static long g(int r) {
        return 1L << r;
    }

    private static long gOr0(int r) {
        return r == 0 ? 0 : 1L << r;
    }

    private static int rt(int i) {
        return (i >>> 21) & 31;
    }

    private static int ra(int i) {
        return (i >>> 16) & 31;
    }

    private static int rb(int i) {
        return (i >>> 11) & 31;
    }

    private static boolean rc(int i) {
        return (i & 1) != 0;
    }

    private static boolean oe(int i) {
        return (i & 0x400) != 0;
    }

    /** Decide whether {@code in} is generated directly and record the values it reads and writes. */
    static void analyze(Insn in, boolean sf) {
        String n = in.name;
        int i = in.word;
        if (n == null || !(in.op instanceof Reg.Kind || in.op instanceof Reg.Local)) {
            return;
        }
        long r;
        long w;
        long rcR = rc(i) ? g(SO) : 0;
        long rcW = rc(i) ? g(CR) : 0;
        switch (n) {
            case "addi", "addis" -> {
                r = gOr0(ra(i));
                w = g(rt(i));
            }
            case "add", "subf", "mulld", "mullw", "mulhd", "mulhdu", "mulhw", "mulhwu", "divd", "divdu", "divw",
                    "divwu" -> {
                if (oe(i) && !n.startsWith("mulh")) {
                    return;
                }
                r = g(ra(i)) | g(rb(i)) | rcR;
                w = g(rt(i)) | rcW;
            }
            case "modsd", "modud", "modsw", "moduw" -> {
                r = g(ra(i)) | g(rb(i));
                w = g(rt(i));
            }
            case "neg" -> {
                if (oe(i)) {
                    return;
                }
                r = g(ra(i)) | rcR;
                w = g(rt(i)) | rcW;
            }
            case "mulli" -> {
                r = g(ra(i));
                w = g(rt(i));
            }
            case "addic", "subfic" -> {
                r = g(ra(i));
                w = g(rt(i)) | g(CA) | g(CA32);
            }
            case "addc", "subfc" -> {
                if (oe(i)) {
                    return;
                }
                r = g(ra(i)) | g(rb(i)) | rcR;
                w = g(rt(i)) | g(CA) | g(CA32) | rcW;
            }
            case "adde", "subfe" -> {
                if (oe(i)) {
                    return;
                }
                r = g(ra(i)) | g(rb(i)) | g(CA) | rcR;
                w = g(rt(i)) | g(CA) | g(CA32) | rcW;
            }
            case "addze", "addme", "subfze", "subfme" -> {
                if (oe(i)) {
                    return;
                }
                r = g(ra(i)) | g(CA) | rcR;
                w = g(rt(i)) | g(CA) | g(CA32) | rcW;
            }
            case "and", "andc", "or", "orc", "xor", "nand", "nor", "eqv", "slw", "srw", "sld", "srd", "rlwnm",
                    "rldcl", "rldcr" -> {
                r = g(rt(i)) | g(rb(i)) | rcR;
                w = g(ra(i)) | rcW;
            }
            case "ori", "oris", "xori", "xoris" -> {
                r = g(rt(i));
                w = g(ra(i));
            }
            case "andi.", "andis." -> {
                r = g(rt(i)) | g(SO);
                w = g(ra(i)) | g(CR);
            }
            case "extsb", "extsh", "extsw", "cntlzw", "cntlzd", "cnttzw", "cnttzd", "rlwinm", "rldicl", "rldicr",
                    "rldic", "extswsli" -> {
                r = g(rt(i)) | rcR;
                w = g(ra(i)) | rcW;
            }
            case "popcntd" -> {
                r = g(rt(i));
                w = g(ra(i));
            }
            case "rlwimi", "rldimi" -> {
                r = g(rt(i)) | g(ra(i)) | rcR;
                w = g(ra(i)) | rcW;
            }
            case "sraw" -> {
                r = g(rt(i)) | g(rb(i)) | rcR;
                w = g(ra(i)) | g(CA) | g(CA32) | rcW;
            }
            case "srawi", "srad", "sradi" -> {
                r = g(rt(i)) | (n.equals("srad") ? g(rb(i)) : 0) | rcR;
                w = g(ra(i)) | g(CA) | g(CA32) | rcW;
            }
            case "cmp", "cmpl" -> {
                r = g(ra(i)) | g(rb(i)) | g(SO) | g(CR);
                w = g(CR);
            }
            case "cmpi", "cmpli" -> {
                r = g(ra(i)) | g(SO) | g(CR);
                w = g(CR);
            }
            case "isel" -> {
                r = gOr0(ra(i)) | g(rb(i)) | g(CR);
                w = g(rt(i));
            }
            case "crand", "crandc", "creqv", "crnand", "crnor", "cror", "crorc", "crxor", "mcrf" -> {
                r = g(CR);
                w = g(CR);
            }
            case "mfcr" -> {
                r = g(CR) | g(rt(i));
                w = g(rt(i));
            }
            case "mtcrf" -> {
                r = g(CR) | g(rt(i));
                w = g(CR);
            }
            case "mfspr" -> {
                int spr = Fields.spr(i);
                if (spr != Spr.LR && spr != Spr.CTR) {
                    return;
                }
                r = g(spr == Spr.LR ? LR : CTR);
                w = g(rt(i));
            }
            case "mtspr" -> {
                int spr = Fields.spr(i);
                if (spr != Spr.LR && spr != Spr.CTR) {
                    return;
                }
                r = g(rt(i));
                w = g(spr == Spr.LR ? LR : CTR);
            }
            case "b" -> {
                r = 0;
                w = g(CFAR) | ((i & 1) != 0 ? g(LR) : 0);
            }
            case "bc", "bclr", "bcctr" -> {
                int bo = rt(i);
                r = ((bo & 0x10) == 0 ? g(CR) : 0) | ((bo & 4) == 0 ? g(CTR) : 0)
                        | (n.equals("bclr") ? g(LR) : 0) | (n.equals("bcctr") ? g(CTR) : 0);
                w = g(CFAR) | ((i & 1) != 0 ? g(LR) : 0) | ((bo & 4) == 0 ? g(CTR) : 0);
            }
            case "dcbt", "dcbtst", "dss", "dst", "dstst" -> {
                r = 0;
                w = 0;
            }
            default -> {
                MemOp m = memOp(n);
                if (m == null) {
                    return;
                }
                int ra = ra(i);
                if (m.update && (ra == 0 || (!m.store && ra == rt(i)))) {
                    return; // invalid form: the handler raises it
                }
                r = (m.update ? g(ra) : gOr0(ra)) | (m.indexed ? g(rb(i)) : 0) | (m.store ? g(rt(i)) : 0);
                w = (m.store ? 0 : g(rt(i))) | (m.update ? g(ra) : 0);
                in.mem = true;
            }
        }
        in.nat = true;
        in.reads = r;
        in.writes = w;
    }

    /** A directly generated load or store. ext: 0 zero-extend, 1 sign-extend (loads). */
    record MemOp(int size, boolean store, boolean indexed, boolean update, boolean sign, boolean ds, boolean swap) {}

    private static final Map<String, MemOp> MEM_OPS = new HashMap<>();

    static {
        String[][] loads = {
            {"lbz", "1", "0", "0"}, {"lhz", "2", "0", "0"}, {"lha", "2", "1", "0"}, {"lwz", "4", "0", "0"},
            {"lwa", "4", "1", "1"}, {"ld", "8", "0", "1"},
        };
        for (String[] l : loads) {
            int size = Integer.parseInt(l[1]);
            boolean sign = l[2].equals("1");
            boolean ds = l[3].equals("1");
            MEM_OPS.put(l[0], new MemOp(size, false, false, false, sign, ds, false));
            String x = l[0].equals("lwa") ? "lwax" : l[0] + "x";
            MEM_OPS.put(x, new MemOp(size, false, true, false, sign, false, false));
            if (!l[0].equals("lwa")) {
                MEM_OPS.put(l[0] + "u", new MemOp(size, false, false, true, sign, ds, false));
            }
            MEM_OPS.put(l[0].equals("lwa") ? "lwaux" : l[0] + "ux", new MemOp(size, false, true, true, sign, false, false));
        }
        String[][] stores = {{"stb", "1", "0"}, {"sth", "2", "0"}, {"stw", "4", "0"}, {"std", "8", "1"}};
        for (String[] st : stores) {
            int size = Integer.parseInt(st[1]);
            boolean ds = st[2].equals("1");
            MEM_OPS.put(st[0], new MemOp(size, true, false, false, false, ds, false));
            MEM_OPS.put(st[0] + "x", new MemOp(size, true, true, false, false, false, false));
            MEM_OPS.put(st[0] + "u", new MemOp(size, true, false, true, false, ds, false));
            MEM_OPS.put(st[0] + "ux", new MemOp(size, true, true, true, false, false, false));
        }
        MEM_OPS.put("lhbrx", new MemOp(2, false, true, false, false, false, true));
        MEM_OPS.put("lwbrx", new MemOp(4, false, true, false, false, false, true));
        MEM_OPS.put("ldbrx", new MemOp(8, false, true, false, false, false, true));
        MEM_OPS.put("sthbrx", new MemOp(2, true, true, false, false, false, true));
        MEM_OPS.put("stwbrx", new MemOp(4, true, true, false, false, false, true));
        MEM_OPS.put("stdbrx", new MemOp(8, true, true, false, false, false, true));
    }

    static MemOp memOp(String name) {
        return MEM_OPS.get(name);
    }

    // ================================================================== code generation

    /** Bytecode generator for one region. */
    private static final class Gen {
        final long ea;
        /** Effective address of the page the region was compiled from; code uses pageBase + offset. */
        final long pageEa;
        final long pagePa;
        final int mode;
        final boolean sf;
        final boolean le;
        final boolean dr;
        final Map<Long, BB> bbs;
        final BB entry;
        final PhysicalMemory pm;
        final List<Object> classData = new ArrayList<>();
        final List<Long> sitePc = new ArrayList<>();
        final List<Integer> siteOff = new ArrayList<>();

        CodeEmitter cb;
        long used;
        long written;
        boolean hasMem;
        final int[] slot = new int[NVAL];
        int gprs;
        /** Local: the effective address of the page at run time (from s.pc at entry). */
        int pb;
        int ic;
        int end;
        int npc;
        int at;
        int wb;
        int mmu;
        int tag;
        int addend;
        int epoch;
        int tEa;
        int tPa;
        int tV;
        int tHit;
        /** Per-instruction codegen state within the current block. */
        long dirty;
        long stale;
        Label tail;
        final List<Runnable> stubs = new ArrayList<>();

        static final int CD_RAM_IDX = 0;
        static final int CD_PM_IDX = 1;
        static final int CD_PCS_IDX = 2;
        static final int CD_OFFS_IDX = 3;

        Gen(long ea, long pa, int mode, Map<Long, BB> bbs, BB entry, PhysicalMemory pm) {
            this.ea = ea;
            this.pageEa = ea & ~0xfffL;
            this.pagePa = pa & ~0xfffL;
            this.mode = mode;
            this.sf = (mode & MODE_SF) != 0;
            this.le = (mode & MODE_LE) != 0;
            this.dr = (mode & MODE_DR) != 0;
            this.bbs = bbs;
            this.entry = entry;
            this.pm = pm;
            classData.add(RamAccess.backing(pm));
            classData.add(pm);
            classData.add(null); // site pcs, filled in after generation
            classData.add(null); // site offsets
        }

        byte[] build() {
            for (BB b : bbs.values()) {
                for (Insn in : b.insns) {
                    if (in.nat) {
                        used |= in.reads | in.writes;
                        written |= in.writes;
                        hasMem |= in.mem;
                    }
                }
            }
            dataflow();
            cb = new CodeEmitter(CD_REGION, MTD_RUN);
            body();
            return cb.finish();
        }

        // ---------------------------------------------------------- dataflow

        /** Values possibly dirty (local newer than CpuState) at each block's entry. */
        void dataflow() {
            boolean changed = true;
            for (BB b : bbs.values()) {
                b.dirtyIn = 0;
            }
            while (changed) {
                changed = false;
                for (BB b : bbs.values()) {
                    long out = b.dirtyIn;
                    for (Insn in : b.insns) {
                        if (in.nat) {
                            out |= in.writes;
                        } else if (!isPure(in)) {
                            out = 0;
                        }
                    }
                    for (long succ : successors(b)) {
                        BB s = bbs.get(succ);
                        if (s != null && (s.dirtyIn | out) != s.dirtyIn) {
                            s.dirtyIn |= out;
                            changed = true;
                        }
                    }
                }
            }
        }

        List<Long> successors(BB b) {
            List<Long> r = new ArrayList<>(2);
            switch (b.term) {
                case T_FALL -> r.add(b.fall);
                case T_B -> {
                    if (!b.takenAbs) {
                        r.add(b.taken);
                    }
                }
                case T_BC -> {
                    if (!b.takenAbs) {
                        r.add(b.taken);
                    }
                    r.add(b.fall);
                }
                case T_BCLR, T_BCCTR -> {
                    if (b.cond) {
                        r.add(b.fall);
                    }
                }
                default -> {}
            }
            return r;
        }

        // ---------------------------------------------------------- locals

        boolean isInt(int v) {
            return v == CR || (v >= SO && v <= CA32);
        }

        void allocLocals() {
            gprs = cb.allocateLocal(CodeEmitter.REFERENCE);
            pb = cb.allocateLocal(CodeEmitter.LONG);
            for (int v = 0; v < NVAL; v++) {
                slot[v] = (used & g(v)) != 0 ? cb.allocateLocal(isInt(v) ? CodeEmitter.INT : CodeEmitter.LONG) : -1;
            }
            ic = cb.allocateLocal(CodeEmitter.LONG);
            end = cb.allocateLocal(CodeEmitter.LONG);
            npc = cb.allocateLocal(CodeEmitter.LONG);
            at = cb.allocateLocal(CodeEmitter.INT);
            wb = cb.allocateLocal(CodeEmitter.INT);
            tEa = cb.allocateLocal(CodeEmitter.LONG);
            tPa = cb.allocateLocal(CodeEmitter.LONG);
            tV = cb.allocateLocal(CodeEmitter.LONG);
            tHit = cb.allocateLocal(CodeEmitter.INT);
            if (hasMem) {
                mmu = cb.allocateLocal(CodeEmitter.REFERENCE);
                tag = cb.allocateLocal(CodeEmitter.REFERENCE);
                addend = cb.allocateLocal(CodeEmitter.REFERENCE);
                epoch = cb.allocateLocal(CodeEmitter.REFERENCE);
            }
        }

        /** Push CpuState's value {@code v}. */
        void fromState(int v) {
            switch (v) {
                case CR -> cb.aload(1).getfield(CD_CPU, "cr", CD_int);
                case LR -> cb.aload(1).getfield(CD_CPU, "lr", CD_long);
                case CTR -> cb.aload(1).getfield(CD_CPU, "ctr", CD_long);
                case SO -> cb.aload(1).getfield(CD_CPU, "so", CD_int);
                case OV -> cb.aload(1).getfield(CD_CPU, "ov", CD_int);
                case OV32 -> cb.aload(1).getfield(CD_CPU, "ov32", CD_int);
                case CA -> cb.aload(1).getfield(CD_CPU, "ca", CD_int);
                case CA32 -> cb.aload(1).getfield(CD_CPU, "ca32", CD_int);
                case CFAR -> cb.aload(1).getfield(CD_CPU, "spr", CD_LARR).loadConstant(Spr.CFAR).laload();
                default -> cb.aload(gprs).loadConstant(v).laload();
            }
        }

        void reload(int v) {
            fromState(v);
            if (isInt(v)) {
                cb.istore(slot[v]);
            } else {
                cb.lstore(slot[v]);
            }
        }

        void writeback(int v) {
            switch (v) {
                case CR -> cb.aload(1).iload(slot[v]).putfield(CD_CPU, "cr", CD_int);
                case LR -> cb.aload(1).lload(slot[v]).putfield(CD_CPU, "lr", CD_long);
                case CTR -> cb.aload(1).lload(slot[v]).putfield(CD_CPU, "ctr", CD_long);
                case SO -> cb.aload(1).iload(slot[v]).putfield(CD_CPU, "so", CD_int);
                case OV -> cb.aload(1).iload(slot[v]).putfield(CD_CPU, "ov", CD_int);
                case OV32 -> cb.aload(1).iload(slot[v]).putfield(CD_CPU, "ov32", CD_int);
                case CA -> cb.aload(1).iload(slot[v]).putfield(CD_CPU, "ca", CD_int);
                case CA32 -> cb.aload(1).iload(slot[v]).putfield(CD_CPU, "ca32", CD_int);
                case CFAR -> cb.aload(1).getfield(CD_CPU, "spr", CD_LARR).loadConstant(Spr.CFAR).lload(slot[v])
                        .lastore();
                default -> cb.aload(gprs).loadConstant(v).lload(slot[v]).lastore();
            }
        }

        void writebackAll(long m) {
            for (int v = 0; v < NVAL; v++) {
                if ((m & g(v)) != 0) {
                    writeback(v);
                }
            }
        }

        void reloadAll(long m) {
            for (int v = 0; v < NVAL; v++) {
                if ((m & g(v)) != 0) {
                    reload(v);
                }
            }
        }

        /** Make the locals of {@code m} current (reload those a handler call made stale). */
        void fresh(long m) {
            long s = stale & m;
            if (s != 0) {
                reloadAll(s);
                stale &= ~s;
            }
        }

        /** Push value {@code v} (fresh). */
        void get(int v) {
            if (v < 32 && slot[v] < 0) {
                throw new IllegalStateException("value " + v + " not in the footprint");
            }
            fresh(g(v));
            if (isInt(v)) {
                cb.iload(slot[v]);
            } else {
                cb.lload(slot[v]);
            }
        }

        /** Pop into value {@code v}. */
        void set(int v) {
            if (slot[v] < 0) {
                throw new IllegalStateException("value " + v + " not in the footprint");
            }
            if (isInt(v)) {
                cb.istore(slot[v]);
            } else {
                cb.lstore(slot[v]);
            }
            dirty |= g(v);
            stale &= ~g(v);
        }

        /** Push (RA|0). */
        void getOr0(int r) {
            if (r == 0) {
                cb.lconst_0();
            } else {
                get(r);
            }
        }

        void truncate() {
            if (!sf) {
                cb.loadConstant(0xffffffffL).land();
            }
        }

        /** Push the run-time address of compile-time address {@code a} (relative to the page). */
        void pushPc(long a) {
            cb.lload(pb);
            long d = a - pageEa;
            if (d != 0) {
                cb.loadConstant(d).ladd();
                truncate();
            }
        }

        void cdConst(int idx, ClassDesc type) {
            cb.loadConstant(DynamicConstantDesc.ofNamed(ConstantDescs.BSM_CLASS_DATA_AT, ConstantDescs.DEFAULT_NAME,
                    type, idx));
        }

        int addData(Object o) {
            classData.add(o);
            return classData.size() - 1;
        }

        /** Register a fault site for instruction {@code k} of block {@code b}; returns its number. */
        int site(Insn in, int k) {
            sitePc.add(in.pc - pageEa);
            siteOff.add(k);
            return sitePc.size() - 1;
        }

        /**
         * Before something that can fault: record the site and make sure the fault handler can
         * write the locals back (no stale values) or need not (nothing dirty).
         */
        void faultSite(Insn in, int k) {
            int n = site(in, k);
            if (dirty == 0) {
                cb.loadConstant(n).istore(at).iconst_0().istore(wb);
            } else {
                fresh(used);
                cb.loadConstant(n).istore(at).iconst_1().istore(wb);
            }
        }

        // ---------------------------------------------------------- method body

        void body() {
            allocLocals();
            tail = cb.newLabel();
            Label fault = cb.newLabel();
            Label tryStart = cb.newLabel();
            Label tryEnd = cb.newLabel();
            cb.exceptionCatch(tryStart, tryEnd, fault, CD_PPCEX);
            for (BB b : bbs.values()) {
                b.label = cb.newLabel();
            }
            // Prologue: load the footprint, the budget and the TLB.
            cb.aload(1).getfield(CD_CPU, "gpr", CD_LARR).astore(gprs);
            // Position independence: every address is the page's run-time address plus an offset.
            cb.aload(1).getfield(CD_CPU, "pc", CD_long).loadConstant(~0xfffL).land().lstore(pb);
            for (int v = 0; v < NVAL; v++) {
                if (slot[v] >= 0) {
                    reload(v);
                }
            }
            cb.aload(1).getfield(CD_CPU, "icount", CD_long).lstore(ic);
            cb.aload(2).getfield(CD_INTERP, "chunkEnd", CD_long).lstore(end);
            cb.lconst_0().lstore(npc);
            cb.iconst_0().istore(at);
            cb.iconst_0().istore(wb);
            cb.lconst_0().lstore(tEa);
            cb.lconst_0().lstore(tPa);
            cb.lconst_0().lstore(tV);
            cb.iconst_0().istore(tHit);
            if (hasMem) {
                cb.aload(2).getfield(CD_INTERP, "mmu", CD_MMU).astore(mmu);
                cb.aload(mmu).getfield(CD_MMU, "tag", CD_LARR).astore(tag);
                cb.aload(mmu).getfield(CD_MMU, "addend", CD_LARR).astore(addend);
                cb.aload(mmu).getfield(CD_MMU, "epoch", CD_IARR).astore(epoch);
            }
            cb.labelBinding(tryStart);
            for (BB b : bbs.values()) {
                block(b);
            }
            for (Runnable r : stubs) {
                r.run();
            }
            cb.labelBinding(tryEnd);
            // Exit: pc = npc, icount = ic. Values were written back by the jumping site.
            cb.labelBinding(tail);
            cb.aload(1).lload(ic).putfield(CD_CPU, "icount", CD_long);
            cb.aload(1).lload(npc).putfield(CD_CPU, "pc", CD_long);
            cb.aload(1).lload(npc).putfield(CD_CPU, "npc", CD_long);
            cb.iconst_0().ireturn();
            // Fault: write back, then pc/icount of the faulting instruction, then deliver.
            cb.labelBinding(fault);
            int ex = cb.allocateLocal(CodeEmitter.REFERENCE);
            cb.astore(ex);
            Label noWb = cb.newLabel();
            cb.iload(wb).ifeq(noWb);
            writebackAll(written);
            cb.labelBinding(noWb);
            long[] pcs = sitePc.stream().mapToLong(Long::longValue).toArray();
            int[] offs = siteOff.stream().mapToInt(Integer::intValue).toArray();
            classData.set(CD_PCS_IDX, pcs);
            classData.set(CD_OFFS_IDX, offs);
            cb.aload(1).lload(pb);
            cdConst(CD_PCS_IDX, CD_LARR);
            cb.iload(at).laload().ladd();
            truncate();
            cb.putfield(CD_CPU, "pc", CD_long);
            cb.aload(1).lload(pb);
            cdConst(CD_PCS_IDX, CD_LARR);
            cb.iload(at).laload().loadConstant(4L).ladd().ladd();
            truncate();
            cb.putfield(CD_CPU, "npc", CD_long);
            cb.aload(1).lload(ic);
            cdConst(CD_OFFS_IDX, CD_IARR);
            cb.iload(at).iaload().i2l().ladd().putfield(CD_CPU, "icount", CD_long);
            cb.aload(2).aload(ex).invokevirtual(CD_INTERP, "blockFault", MTD_FAULT);
            cb.iconst_1().ireturn();
        }

        /** Leave the region: write back dirty values, set npc from the stack (a long), go to the tail. */
        void exitWithNpcOnStack() {
            cb.lstore(npc);
            writebackAll(dirty);
            cb.goto_(tail);
        }

        void exitTo(long target) {
            pushPc(target);
            exitWithNpcOnStack();
        }

        void exitToAbsolute(long target) {
            cb.loadConstant(target);
            exitWithNpcOnStack();
        }

        /** ic += n. */
        void addIc(int n) {
            cb.lload(ic).loadConstant((long) n).ladd().lstore(ic);
        }

        void jumpTaken(BB b) {
            if (b.takenAbs) {
                exitToAbsolute(b.taken);
            } else {
                jumpOrExit(b.taken);
            }
        }

        void jumpOrExit(long target) {
            BB t = bbs.get(target);
            if (t != null) {
                cb.goto_(t.label);
            } else {
                exitTo(target);
            }
        }

        void block(BB b) {
            cb.labelBinding(b.label);
            dirty = b.dirtyIn;
            stale = 0;
            int len = b.insns.size();
            // Budget: the whole block must fit before the end of the chunk.
            Label ok = cb.newLabel();
            cb.lload(ic).loadConstant((long) len).ladd().lload(end).lcmp().ifle(ok);
            exitTo(b.start);
            cb.labelBinding(ok);
            int last = b.term == T_FALL ? len : len - 1;
            for (int k = 0; k < last; k++) {
                insn(b, b.insns.get(k), k);
            }
            switch (b.term) {
                case T_FALL -> {
                    fresh(used);
                    addIc(len);
                    jumpOrExit(b.fall);
                }
                case T_STOP -> {
                    Insn in = b.insns.get(len - 1);
                    if (in.nat) {
                        insn(b, in, len - 1);
                        addIc(len);
                        exitTo(b.fall);
                    } else {
                        call(b, in, len - 1, true);
                    }
                }
                default -> branch(b, b.insns.get(len - 1), len);
            }
        }

        // ---------------------------------------------------------- handler calls

        /**
         * Call the handler of {@code in} (instruction {@code k} of {@code b}) as the run loop
         * would. Afterwards the region leaves if the handler branched, asked for a recheck or
         * stored into this page; with {@code last}, it always leaves (to {@code s.npc}).
         */
        void call(BB b, Insn in, int k, boolean last) {
            long npc4 = mask(sf, in.pc + 4);
            boolean pure = isPure(in);
            if (!pure) {
                writebackAll(dirty);
                dirty = 0;
            }
            cb.aload(1);
            pushPc(in.pc);
            cb.putfield(CD_CPU, "pc", CD_long);
            cb.aload(1);
            pushPc(npc4);
            cb.putfield(CD_CPU, "npc", CD_long);
            cb.aload(1).lload(ic).loadConstant((long) k).ladd().putfield(CD_CPU, "icount", CD_long);
            int n = site(in, k);
            cb.loadConstant(n).istore(at);
            if (dirty == 0) {
                cb.iconst_0().istore(wb);
            } else {
                // A pure handler with dirty locals: they are current (no stale values).
                fresh(used);
                cb.iconst_1().istore(wb);
            }
            int slotNo = in.slot;
            if (slotNo < Interpreter.INLINE_HANDLERS.length) {
                String h = Interpreter.INLINE_HANDLERS[slotNo];
                ClassDesc owner = ClassDesc.of("org.boxtheta.javapower.cpu." + h.substring(0, h.indexOf('.')));
                cb.aload(1).loadConstant(in.word).invokestatic(owner, h.substring(h.indexOf('.') + 1), MTD_HANDLER);
            } else if (slotNo < Interpreter.FIRST_DYNAMIC) {
                cb.aload(1).loadConstant(in.word).invokestatic(CD_INTERP, Interpreter.specialHandler(slotNo),
                        MTD_HANDLER);
            } else {
                cdConst(addData(in.op), CD_OP);
                cb.aload(1).loadConstant(in.word).invokeinterface(CD_OP, "exec", MTD_HANDLER);
            }
            if (!pure) {
                stale = used;
            }
            if (last) {
                // pc = s.npc; everything was written back (or nothing is dirty).
                addIc(k + 1);
                if (dirty != 0) {
                    writebackAll(dirty);
                }
                cb.aload(1).getfield(CD_CPU, "npc", CD_long).lstore(npc);
                cb.goto_(tail);
                return;
            }
            Label leave = cb.newLabel();
            Label cont = cb.newLabel();
            if (!pure) {
                cb.aload(1).getfield(CD_CPU, "npc", CD_long);
                pushPc(npc4);
                cb.lcmp().ifne(leave);
                cb.aload(2).getfield(CD_INTERP, "mmu", CD_MMU).getfield(CD_MMU, "guardHit", CD_boolean).ifne(leave);
            }
            cb.aload(2).getfield(CD_INTERP, "recheck", CD_boolean).ifeq(cont);
            cb.labelBinding(leave);
            addIc(k + 1);
            if (dirty != 0) {
                writebackAll(dirty);
            }
            cb.aload(1).getfield(CD_CPU, "npc", CD_long).lstore(npc);
            cb.goto_(tail);
            cb.labelBinding(cont);
        }

        // ---------------------------------------------------------- instructions

        void insn(BB b, Insn in, int k) {
            if (!in.nat) {
                call(b, in, k, false);
                return;
            }
            if (in.mem) {
                mem(b, in, k);
                return;
            }
            alu(in);
        }

        void cr0(int v) {
            // stack: long result (already stored); recompute from the local v
            get(CR);
            cb.lload(slot[v]);
            get(SO);
            cb.invokestatic(CD_RT, sf ? "cr0" : "cr0w", MTD_CR0);
            set(CR);
        }

        void helper(String m) {
            cb.invokestatic(CD_RT, m, MTD_J_JJ);
        }

        void alu(Insn in) {
            int i = in.word;
            int rt = rt(i);
            int ra = ra(i);
            int rb = rb(i);
            boolean rcb = rc(i);
            int dst = ra; // X-form logical results go to RA
            switch (in.name) {
                case "addi" -> {
                    getOr0(ra);
                    cb.loadConstant((long) (short) i).ladd();
                    set(rt);
                    return;
                }
                case "addis" -> {
                    getOr0(ra);
                    cb.loadConstant(((long) (short) i) << 16).ladd();
                    set(rt);
                    return;
                }
                case "add" -> {
                    get(ra);
                    get(rb);
                    cb.ladd();
                    dst = rt;
                    set(dst);
                }
                case "subf" -> {
                    get(rb);
                    get(ra);
                    cb.lsub();
                    dst = rt;
                    set(dst);
                }
                case "neg" -> {
                    get(ra);
                    cb.lneg();
                    dst = rt;
                    set(dst);
                }
                case "mulld" -> {
                    get(ra);
                    get(rb);
                    cb.lmul();
                    dst = rt;
                    set(dst);
                }
                case "mullw" -> {
                    get(ra);
                    cb.l2i().i2l();
                    get(rb);
                    cb.l2i().i2l().lmul();
                    dst = rt;
                    set(dst);
                }
                case "mulli" -> {
                    get(ra);
                    cb.loadConstant((long) (short) i).lmul();
                    set(rt);
                    return;
                }
                case "mulhd" -> {
                    get(ra);
                    get(rb);
                    cb.invokestatic(CD_MATH, "multiplyHigh", MTD_J_JJ);
                    dst = rt;
                    set(dst);
                }
                case "mulhdu" -> {
                    get(ra);
                    get(rb);
                    cb.invokestatic(CD_MATH, "unsignedMultiplyHigh", MTD_J_JJ);
                    dst = rt;
                    set(dst);
                }
                case "mulhw", "mulhwu", "divd", "divdu", "divw", "divwu" -> {
                    get(ra);
                    get(rb);
                    helper(in.name);
                    dst = rt;
                    set(dst);
                }
                case "modsd", "modud", "modsw", "moduw" -> {
                    get(ra);
                    get(rb);
                    helper(in.name);
                    set(rt);
                    return;
                }
                case "addic" -> {
                    // r = a + si; CA from a, si, r
                    get(ra);
                    cb.lstore(tV);
                    long si = (short) i;
                    cb.lload(tV).loadConstant(si).ladd().lstore(tPa);
                    carry(tV, si, tPa);
                    cb.lload(tPa);
                    set(rt);
                    return;
                }
                case "subfic" -> {
                    // r = ~a + si + 1
                    get(ra);
                    cb.loadConstant(-1L).lxor().lstore(tV);
                    long si = (short) i;
                    cb.lload(tV).loadConstant(si).ladd().lconst_1().ladd().lstore(tPa);
                    carry(tV, si, tPa);
                    cb.lload(tPa);
                    set(rt);
                    return;
                }
                case "addc", "adde", "addze", "addme", "subfc", "subfe", "subfze", "subfme" -> {
                    boolean sub = in.name.startsWith("subf");
                    String op = sub ? in.name.substring(4) : in.name.substring(3);
                    // a
                    get(ra);
                    if (sub) {
                        cb.loadConstant(-1L).lxor();
                    }
                    cb.lstore(tV);
                    // b
                    switch (op) {
                        case "c", "e" -> get(rb);
                        case "ze" -> cb.lconst_0();
                        default -> cb.loadConstant(-1L); // me
                    }
                    cb.lstore(tEa);
                    // r = a + b + cin
                    cb.lload(tV).lload(tEa).ladd();
                    if (op.equals("c")) {
                        if (sub) {
                            cb.lconst_1().ladd();
                        }
                    } else {
                        get(CA);
                        cb.i2l().ladd();
                    }
                    cb.lstore(tPa);
                    // CA32, CA
                    cb.lload(tV).lload(tEa).lload(tPa).invokestatic(CD_RT, "ca32", MethodTypeDesc.of(CD_int, CD_long,
                            CD_long, CD_long));
                    set(CA32);
                    if (sf) {
                        cb.lload(tV).lload(tEa).lload(tPa).invokestatic(CD_RT, "ca64", MethodTypeDesc.of(CD_int,
                                CD_long, CD_long, CD_long));
                    } else {
                        get(CA32);
                    }
                    set(CA);
                    cb.lload(tPa);
                    dst = rt;
                    set(dst);
                }
                case "and" -> logical2(rt, rb, ra, 0);
                case "andc" -> logical2(rt, rb, ra, 1);
                case "or" -> logical2(rt, rb, ra, 2);
                case "orc" -> logical2(rt, rb, ra, 3);
                case "xor" -> logical2(rt, rb, ra, 4);
                case "nand" -> logical2(rt, rb, ra, 5);
                case "nor" -> logical2(rt, rb, ra, 6);
                case "eqv" -> logical2(rt, rb, ra, 7);
                case "ori", "oris", "xori", "xoris", "andi.", "andis." -> {
                    long ui = i & 0xffffL;
                    if (in.name.endsWith("is") || in.name.equals("andis.")) {
                        ui <<= 16;
                    }
                    get(rt);
                    cb.loadConstant(ui);
                    if (in.name.startsWith("or")) {
                        cb.lor();
                    } else if (in.name.startsWith("xor")) {
                        cb.lxor();
                    } else {
                        cb.land();
                    }
                    set(ra);
                    if (in.name.startsWith("and")) {
                        cr0(ra);
                    }
                    return;
                }
                case "extsb" -> {
                    get(rt);
                    cb.l2i().i2b().i2l();
                    dst = ra;
                    set(dst);
                }
                case "extsh" -> {
                    get(rt);
                    cb.l2i().i2s().i2l();
                    dst = ra;
                    set(dst);
                }
                case "extsw" -> {
                    get(rt);
                    cb.l2i().i2l();
                    dst = ra;
                    set(dst);
                }
                case "cntlzw" -> {
                    get(rt);
                    cb.l2i().invokestatic(CD_INT, "numberOfLeadingZeros", MethodTypeDesc.of(CD_int, CD_int)).i2l();
                    dst = ra;
                    set(dst);
                }
                case "cnttzw" -> {
                    get(rt);
                    cb.l2i().invokestatic(CD_INT, "numberOfTrailingZeros", MethodTypeDesc.of(CD_int, CD_int)).i2l();
                    dst = ra;
                    set(dst);
                }
                case "cntlzd" -> {
                    get(rt);
                    cb.invokestatic(CD_LONG, "numberOfLeadingZeros", MTD_I_J).i2l();
                    dst = ra;
                    set(dst);
                }
                case "cnttzd" -> {
                    get(rt);
                    cb.invokestatic(CD_LONG, "numberOfTrailingZeros", MTD_I_J).i2l();
                    dst = ra;
                    set(dst);
                }
                case "popcntd" -> {
                    get(rt);
                    cb.invokestatic(CD_LONG, "bitCount", MTD_I_J).i2l();
                    set(ra);
                    return;
                }
                case "slw", "srw", "sld", "srd" -> {
                    get(rt);
                    get(rb);
                    helper(in.name);
                    dst = ra;
                    set(dst);
                }
                case "sraw", "srawi", "srad", "sradi" -> {
                    boolean w = in.name.startsWith("sraw");
                    int n;
                    if (in.name.equals("sraw")) {
                        get(rb);
                        cb.l2i().loadConstant(0x3f).iand().istore(tHit);
                    } else if (in.name.equals("srad")) {
                        get(rb);
                        cb.l2i().loadConstant(0x7f).iand().istore(tHit);
                    } else {
                        n = in.name.equals("srawi") ? rb : (rb | ((i & 2) << 4));
                        cb.loadConstant(n).istore(tHit);
                    }
                    get(rt);
                    cb.lstore(tV);
                    cb.lload(tV).iload(tHit).invokestatic(CD_RT, w ? "srawCa" : "sradCa", MTD_I_JI);
                    cb.dup();
                    set(CA);
                    set(CA32);
                    cb.lload(tV).iload(tHit).invokestatic(CD_RT, w ? "sraw" : "srad", MTD_J_JI);
                    dst = ra;
                    set(dst);
                }
                case "extswsli" -> {
                    int n = rb | ((i & 2) << 4);
                    get(rt);
                    cb.l2i().i2l().loadConstant(n).lshl();
                    dst = ra;
                    set(dst);
                }
                case "rlwinm", "rlwnm", "rlwimi" -> {
                    int mb = (i >>> 6) & 31;
                    int me = (i >>> 1) & 31;
                    long m = Alu.mask(mb + 32, me + 32);
                    get(rt);
                    if (in.name.equals("rlwnm")) {
                        get(rb);
                        cb.l2i().loadConstant(31).iand();
                    } else {
                        cb.loadConstant(rb);
                    }
                    cb.invokestatic(CD_RT, "rotl32", MTD_J_JI).loadConstant(m).land();
                    if (in.name.equals("rlwimi")) {
                        get(ra);
                        cb.loadConstant(~m).land().lor();
                    }
                    dst = ra;
                    set(dst);
                }
                case "rldicl", "rldicr", "rldic", "rldimi", "rldcl", "rldcr" -> {
                    int mbe = ((i >>> 6) & 0x1f) | (i & 0x20);
                    boolean dyn = in.name.startsWith("rldc");
                    int sh = rb | ((i & 2) << 4);
                    long m = switch (in.name) {
                        case "rldicl", "rldcl" -> Alu.mask(mbe, 63);
                        case "rldicr", "rldcr" -> Alu.mask(0, mbe);
                        default -> Alu.mask(mbe, 63 - sh); // rldic, rldimi
                    };
                    get(rt);
                    if (dyn) {
                        get(rb);
                        cb.l2i().loadConstant(63).iand();
                    } else {
                        cb.loadConstant(sh);
                    }
                    cb.invokestatic(CD_LONG, "rotateLeft", MTD_J_JI).loadConstant(m).land();
                    if (in.name.equals("rldimi")) {
                        get(ra);
                        cb.loadConstant(~m).land().lor();
                    }
                    dst = ra;
                    set(dst);
                }
                case "cmp", "cmpl", "cmpi", "cmpli" -> {
                    boolean l = (i & 0x00200000) != 0;
                    boolean unsigned = in.name.startsWith("cmpl");
                    int bf = (i >>> 23) & 7;
                    get(CR);
                    cb.loadConstant(bf);
                    get(ra);
                    if (!l) {
                        if (unsigned) {
                            cb.loadConstant(0xffffffffL).land();
                        } else {
                            cb.l2i().i2l();
                        }
                    }
                    if (in.name.endsWith("i")) {
                        cb.loadConstant(unsigned ? (long) (i & 0xffff) : (long) (short) i);
                    } else {
                        get(rb);
                        if (!l) {
                            if (unsigned) {
                                cb.loadConstant(0xffffffffL).land();
                            } else {
                                cb.l2i().i2l();
                            }
                        }
                    }
                    get(SO);
                    cb.invokestatic(CD_RT, unsigned ? "cmpl" : "cmp", MTD_CMP);
                    set(CR);
                    return;
                }
                case "isel" -> {
                    int bc = (i >>> 6) & 31;
                    Label no = cb.newLabel();
                    Label done = cb.newLabel();
                    fresh(gOr0(ra) | g(rb) | g(CR));
                    cb.iload(slot[CR]).loadConstant(1 << (31 - bc)).iand().ifeq(no);
                    getOr0(ra);
                    cb.goto_(done);
                    cb.labelBinding(no);
                    get(rb);
                    cb.labelBinding(done);
                    set(rt);
                    return;
                }
                case "crand", "crandc", "creqv", "crnand", "crnor", "cror", "crorc", "crxor" -> {
                    int bt = rt;
                    // bits as 0/1 ints
                    get(CR);
                    cb.loadConstant(31 - ra).iushr().iconst_1().iand();
                    get(CR);
                    cb.loadConstant(31 - rb).iushr().iconst_1().iand();
                    switch (in.name) {
                        case "crand" -> cb.iand();
                        case "crandc" -> cb.iconst_m1().ixor().iand();
                        case "creqv" -> cb.ixor().iconst_m1().ixor();
                        case "crnand" -> cb.iand().iconst_m1().ixor();
                        case "crnor" -> cb.ior().iconst_m1().ixor();
                        case "cror" -> cb.ior();
                        case "crorc" -> cb.iconst_m1().ixor().ior();
                        default -> cb.ixor();
                    }
                    // cr = (cr & ~m) | ((v & 1) << (31 - bt))
                    cb.iconst_1().iand().loadConstant(31 - bt).ishl();
                    get(CR);
                    cb.loadConstant(~(1 << (31 - bt))).iand().ior();
                    set(CR);
                    return;
                }
                case "mcrf" -> {
                    int bf = (i >>> 23) & 7;
                    int bfa = (i >>> 18) & 7;
                    int sh = (7 - bf) * 4;
                    int sha = (7 - bfa) * 4;
                    get(CR);
                    cb.loadConstant(sha).iushr().loadConstant(0xf).iand().loadConstant(sh).ishl();
                    get(CR);
                    cb.loadConstant(~(0xf << sh)).iand().ior();
                    set(CR);
                    return;
                }
                case "mfcr" -> {
                    if ((i & 0x00100000) != 0) {
                        get(CR);
                        cb.loadConstant((i >>> 12) & 0xff);
                        get(rt);
                        cb.invokestatic(CD_RT, "mfocrf", MTD_MFOCRF);
                    } else {
                        get(CR);
                        cb.i2l().loadConstant(0xffffffffL).land();
                    }
                    set(rt);
                    return;
                }
                case "mtcrf" -> {
                    int fxm = (i >>> 12) & 0xff;
                    if ((i & 0x00100000) != 0 && (fxm == 0 || (fxm & (fxm - 1)) != 0)) {
                        return; // mtocrf with a mask that is not one-hot: no change
                    }
                    int msk = 0;
                    for (int n = 0; n < 8; n++) {
                        if ((fxm & (1 << n)) != 0) {
                            msk |= 0xf << (4 * n);
                        }
                    }
                    get(CR);
                    cb.loadConstant(~msk).iand();
                    get(rt);
                    cb.l2i().loadConstant(msk).iand().ior();
                    set(CR);
                    return;
                }
                case "mfspr" -> {
                    get(Fields.spr(i) == Spr.LR ? LR : CTR);
                    set(rt);
                    return;
                }
                case "mtspr" -> {
                    get(rt);
                    set(Fields.spr(i) == Spr.LR ? LR : CTR);
                    return;
                }
                case "dcbt", "dcbtst", "dss", "dst", "dstst" -> {
                    return;
                }
                default -> throw new IllegalStateException("no generator for " + in.name);
            }
            if (rcb) {
                cr0(dst);
            }
        }

        /** CA32/CA of r = a + b (+ cin), a in local la, b constant, r in local lr. */
        void carry(int la, long b, int lr) {
            cb.lload(la).loadConstant(b).lload(lr).invokestatic(CD_RT, "ca32", MethodTypeDesc.of(CD_int, CD_long,
                    CD_long, CD_long));
            set(CA32);
            if (sf) {
                cb.lload(la).loadConstant(b).lload(lr).invokestatic(CD_RT, "ca64", MethodTypeDesc.of(CD_int, CD_long,
                        CD_long, CD_long));
            } else {
                get(CA32);
            }
            set(CA);
        }

        /** X-form logical ra = rs op rb, CR0 if Rc. */
        void logical2(int rs, int rb, int ra, int op) {
            get(rs);
            get(rb);
            switch (op) {
                case 0 -> cb.land();
                case 1 -> cb.loadConstant(-1L).lxor().land();
                case 2 -> cb.lor();
                case 3 -> cb.loadConstant(-1L).lxor().lor();
                case 4 -> cb.lxor();
                case 5 -> cb.land().loadConstant(-1L).lxor();
                case 6 -> cb.lor().loadConstant(-1L).lxor();
                default -> cb.lxor().loadConstant(-1L).lxor();
            }
            set(ra);
        }

        // ---------------------------------------------------------- loads and stores

        void mem(BB b, Insn in, int k) {
            MemOp m = memOp(in.name);
            int i = in.word;
            int rt = rt(i);
            int ra = ra(i);
            // Effective address into tEa.
            if (m.update) {
                get(ra);
            } else {
                getOr0(ra);
            }
            if (m.indexed) {
                get(rb(i));
                cb.ladd();
            } else {
                long d = m.ds ? (short) (i & 0xfffc) : (short) i;
                if (d != 0) {
                    cb.loadConstant(d).ladd();
                }
            }
            truncate();
            cb.lstore(tEa);
            if (m.store) {
                get(rt);
                cb.lstore(tV);
            }
            faultSite(in, k);
            boolean order = m.swap ? !le : le;
            int bits = 8 * m.size;
            String sfx = m.size == 1 ? "8" : bits + (order ? "le" : "be");
            Label slow = cb.newLabel();
            Label done = cb.newLabel();
            // Fast path: real address or -1.
            if (dr) {
                cb.aload(tag).aload(addend).aload(epoch).lload(tEa)
                        .loadConstant((mode & MODE_PR) != 0 ? 2 : 0)
                        .loadConstant(m.store ? RadixMmu.STORE : RadixMmu.LOAD)
                        .loadConstant(m.size)
                        .invokestatic(CD_RT, "xlate", MTD_XLATE);
            } else {
                cb.lload(tEa).loadConstant(pm.size() - m.size).invokestatic(CD_RT, "real", MTD_J_JJ);
            }
            cb.dup2().lstore(tPa).loadConstant(-1L).lcmp().ifeq(slow);
            if (!m.store) {
                cdConst(CD_RAM_IDX, RamAccess.TYPE);
                cb.lload(tPa).invokestatic(CD_RAM_ACCESS, "ld" + sfx, MTD_LD);
                cb.labelBinding(done);
                // stack: raw value (zero-extended for 8/16, sign-extended 32, 64)
                if (m.size == 4 && !m.sign) {
                    cb.loadConstant(0xffffffffL).land();
                } else if (m.size == 2 && m.sign) {
                    cb.l2i().i2s().i2l();
                }
                if (m.swap) {
                    // byte-reversed loads: the other byte order, zero-extended
                    if (m.size == 4) {
                        cb.loadConstant(0xffffffffL).land();
                    }
                }
                set(rt);
                if (m.update) {
                    cb.lload(tEa);
                    set(ra);
                }
                int rd = bits;
                stubs.add(() -> {
                    cb.labelBinding(slow);
                    cb.aload(mmu).lload(tEa).invokestatic(CD_RT, "rd" + rd, MTD_RD);
                    if (m.swap) {
                        // The MMU returns the guest-order value; reverse it.
                        swapValue(m.size);
                    }
                    cb.goto_(done);
                });
            } else {
                cdConst(CD_RAM_IDX, RamAccess.TYPE);
                cdConst(CD_PM_IDX, CD_PM);
                cb.lload(tPa).lload(tV).loadConstant(pagePa).invokestatic(CD_RAM_ACCESS, "st" + sfx, MTD_ST);
                cb.istore(tHit);
                cb.labelBinding(done);
                if (m.update) {
                    cb.lload(tEa);
                    set(ra);
                }
                // A store into translated code or this page: leave after this instruction.
                Label cont = cb.newLabel();
                cb.iload(tHit).ifeq(cont);
                long target = mask(sf, in.pc + 4);
                long dirtyAtExit = dirty;
                int kk = k;
                Label exit = cb.newLabel();
                cb.goto_(exit);
                stubs.add(() -> {
                    cb.labelBinding(exit);
                    addIc(kk + 1);
                    pushPc(target);
                    cb.lstore(npc);
                    writebackAll(dirtyAtExit);
                    cb.goto_(tail);
                });
                cb.labelBinding(cont);
                int wr = bits;
                stubs.add(() -> {
                    cb.labelBinding(slow);
                    cb.aload(mmu).lload(tEa).lload(tV);
                    if (m.swap) {
                        swapValue(m.size);
                    }
                    cb.invokestatic(CD_RT, "wr" + wr, MTD_WR);
                    cb.aload(mmu).getfield(CD_MMU, "guardHit", CD_boolean).istore(tHit);
                    cb.goto_(done);
                });
            }
        }

        /** Reverse the bytes of the {@code size}-byte value (a long) on the stack, zero-extended. */
        void swapValue(int size) {
            switch (size) {
                case 2 -> cb.l2i().invokestatic(CD_INT, "reverseBytes", MethodTypeDesc.of(CD_int, CD_int))
                        .loadConstant(16).iushr().i2l();
                case 4 -> cb.l2i().invokestatic(CD_INT, "reverseBytes", MethodTypeDesc.of(CD_int, CD_int)).i2l()
                        .loadConstant(0xffffffffL).land();
                case 8 -> cb.invokestatic(CD_LONG, "reverseBytes", MTD_J_J);
                default -> {}
            }
        }

        // ---------------------------------------------------------- branches

        /** The block's final branch (instruction {@code len-1}): evaluate, then jump or exit. */
        void branch(BB b, Insn in, int len) {
            int i = in.word;
            boolean lk = (i & 1) != 0;
            long next = mask(sf, in.pc + 4);
            fresh(used);
            addIc(len);
            switch (in.name) {
                case "b" -> {
                    if (lk) {
                        pushPc(next);
                        set(LR);
                    }
                    pushPc(in.pc);
                    set(CFAR);
                    jumpTaken(b);
                }
                case "bc" -> {
                    int bo = rt(i);
                    Label notTaken = cb.newLabel();
                    if ((bo & 0x14) == 0x14) {
                        // branch always (decoded as T_B)
                        if (lk) {
                            pushPc(next);
                            set(LR);
                        }
                        pushPc(in.pc);
                        set(CFAR);
                        jumpTaken(b);
                        return;
                    }
                    cond(i, notTaken);
                    if (lk) {
                        pushPc(next);
                        set(LR);
                    }
                    long dirtyNT = dirty;
                    // taken
                    pushPc(in.pc);
                    set(CFAR);
                    jumpTaken(b);
                    cb.labelBinding(notTaken);
                    dirty = dirtyNT;
                    if (lk) {
                        // link happens on both paths
                        pushPc(next);
                        set(LR);
                    }
                    jumpOrExit(b.fall);
                }
                case "bclr", "bcctr" -> {
                    boolean ctrForm = in.name.equals("bcctr");
                    int bo = rt(i);
                    // target before link
                    get(ctrForm ? CTR : LR);
                    cb.lstore(tEa);
                    Label notTaken = cb.newLabel();
                    if (ctrForm) {
                        condCtr(i, notTaken);
                    } else if ((bo & 0x14) != 0x14) {
                        cond(i, notTaken);
                    }
                    if (lk) {
                        pushPc(next);
                        set(LR);
                    }
                    long dirtyNT = dirty;
                    pushPc(in.pc);
                    set(CFAR);
                    cb.lload(tEa);
                    truncate();
                    cb.loadConstant(~3L).land();
                    exitWithNpcOnStack();
                    if (b.cond) {
                        cb.labelBinding(notTaken);
                        dirty = dirtyNT;
                        if (lk) {
                            pushPc(next);
                            set(LR);
                        }
                        jumpOrExit(b.fall);
                    }
                }
                default -> throw new IllegalStateException("not a branch: " + in.name);
            }
        }

        /** bc/bclr condition (BranchInsns.cond): jump to {@code notTaken} if not taken; decrements CTR. */
        void cond(int i, Label notTaken) {
            int bo = rt(i);
            int bi = ra(i);
            if ((bo & 4) == 0) {
                get(CTR);
                cb.lconst_1().lsub();
                set(CTR);
                get(CTR);
                truncate();
                cb.lconst_0().lcmp();
                // ctrOk = (c != 0) != ((bo & 2) != 0)
                if ((bo & 2) != 0) {
                    cb.ifne(notTaken); // taken needs c == 0
                } else {
                    cb.ifeq(notTaken); // taken needs c != 0
                }
            }
            if ((bo & 0x10) == 0) {
                get(CR);
                cb.loadConstant(1 << (31 - bi)).iand();
                if (((bo >>> 3) & 1) != 0) {
                    cb.ifeq(notTaken);
                } else {
                    cb.ifne(notTaken);
                }
            }
        }

        /** bcctr's condition (BranchInsns.bcctr): CTR tested before it is decremented. */
        void condCtr(int i, Label notTaken) {
            int bo = rt(i);
            int bi = ra(i);
            if ((bo & 4) == 0) {
                get(CTR);
                truncate();
                cb.lconst_0().lcmp();
                // taken = (c != 0) == ((bo & 2) == 0)
                if ((bo & 2) == 0) {
                    cb.ifeq(notTaken);
                } else {
                    cb.ifne(notTaken);
                }
                get(CTR);
                cb.lconst_1().lsub();
                set(CTR);
            }
            if ((bo & 0x10) == 0) {
                get(CR);
                cb.loadConstant(1 << (31 - bi)).iand();
                if (((bo >>> 3) & 1) != 0) {
                    cb.ifeq(notTaken);
                } else {
                    cb.ifne(notTaken);
                }
            }
        }
    }
}
