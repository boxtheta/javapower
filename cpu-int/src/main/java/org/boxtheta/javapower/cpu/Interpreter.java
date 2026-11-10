package org.boxtheta.javapower.cpu;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.Op;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.core.PpcException;
import org.boxtheta.javapower.core.Spr;

/**
 * The execution engine for one POWER9 hardware thread of a PAPR guest (MSR[HV]=0, the emulator
 * is the hypervisor).
 *
 * <p>Owns the {@link RadixMmu} (installed as {@code s.mem}), the timebase and decrementer,
 * interrupt delivery and the idle (H_CEDE / {@code wait}) logic. Typical use:
 *
 * <pre>
 *   DecodeTable t = new DecodeTable();
 *   t.addAll(new IntInsns());              // plus the FP/vector sets
 *   CpuState s = new CpuState();
 *   s.hv = hypervisor;
 *   Interpreter cpu = new Interpreter(s, ram, t, Interpreter.Clock.WALL);
 *   cpu.resetPapr();                        // power-on state; then set pc, r3 ...
 *   cpu.run();                              // returns once s.stopRequested is set
 * </pre>
 *
 * <p>Instructions are fetched from guest memory on every execution and decoded through a cache
 * keyed by the instruction word itself (decoding is a pure function of the word), so the cache
 * never goes stale: self-modifying code, DMA into code pages and MSR[LE] switches need no
 * invalidation.
 *
 * <p>Asynchronous interrupts (external, decrementer) are checked between chunks of at most
 * {@value #CHUNK} instructions and whenever an instruction changes state that affects them
 * (MSR, DEC, hcalls).
 */
public final class Interpreter {
    /** Timebase frequency (Hz), as in the reference device tree. */
    public static final long TB_FREQ = 512_000_000L;
    /** Decrementer width with LPCR[LD]=1 ({@code ibm,dec-bits}). */
    public static final int LARGE_DEC_BITS = 56;

    /** Where virtual time comes from. */
    public enum Clock {
        /**
         * Time is {@code icount << icountShift} nanoseconds: fully reproducible, for lockstep
         * comparison with {@code qemu -icount shift=N,sleep=off}. When the guest idles, time jumps
         * straight to the next decrementer event.
         */
        DETERMINISTIC,
        /** Time follows the host's monotonic clock; idle periods park the host thread. */
        WALL
    }

    /** MSR bits a guest (MSR[HV]=0) can hold on POWER9 (QEMU's msr_mask without HV). */
    static final long MSR_MASK = Msr.SF | (1L << 32) /* TM */ | Msr.VEC | Msr.VSX | Msr.EE | Msr.PR
            | Msr.FP | Msr.ME | Msr.FE0 | (1L << 10) /* SE */ | (1L << 9) /* BE */ | Msr.FE1 | Msr.IR
            | Msr.DR | Msr.PMM | Msr.RI | Msr.LE;

    /** H_PRIVILEGE, returned in r3 for an hcall made from problem state. */
    static final long H_PRIVILEGE = -3;

    // LPCR power-saving exit enables (Java bit numbers of ISA bits 49..51).
    static final long LPCR_EEE = 1L << 14;
    static final long LPCR_DEE = 1L << 13;
    static final long LPCR_OEE = 1L << 12;
    static final long LPCR_PDEE = 1L << 16;
    static final long LPCR_HDEE = 1L << 15;
    static final long LPCR_LPES0 = 1L << 3;
    static final long LPCR_LPES1 = 1L << 2;
    static final int PSSCR = 855;
    static final int AMOR = 349;

    /** Per-instruction observer, e.g. for lockstep comparison. Called before the instruction executes. */
    @FunctionalInterface
    public interface TraceHook {
        void onInsn(CpuState s, long pc, int insn);
    }

    /** Observer of interrupt delivery. {@code s} already holds the new state (pc = vector). */
    @FunctionalInterface
    public interface InterruptHook {
        void onInterrupt(CpuState s, int vector, long srr0);
    }

    /** Observer of instructions that have no handler (they raise an illegal-instruction interrupt). */
    @FunctionalInterface
    public interface UnimplementedHook {
        void onUnimplemented(CpuState s, long pc, int insn, String opcode);
    }

    private static final ValueLayout.OfInt I_BE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);
    private static final ValueLayout.OfInt I_LE = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static final int DCACHE_BITS = 14;
    static final int CHUNK = 4096;
    /** Longest single park while idle, so stop requests are noticed. */
    private static final long MAX_PARK_NS = 50_000_000L;

    final CpuState s;
    final RadixMmu mmu;
    private final MemorySegment seg;
    private final DecodeTable table;
    private final Clock clock;
    private final long wallBase = System.nanoTime();

    // Decode cache keyed by instruction word (so it never needs invalidation).
    private final int[] dkeys = new int[1 << DCACHE_BITS];
    private final Op[] dops = new Op[1 << DCACHE_BITS];

    /** Set by handlers that change state the run loop caches (MSR, TLB, timers); ends the chunk. */
    boolean recheck;
    /** Set by {@code wait}: idle until an interrupt is pending. */
    boolean waiting;

    /** DETERMINISTIC clock only: virtual nanoseconds per instruction = 1 << icountShift. */
    public volatile int icountShift = 0;
    /** Optional per-instruction hook; null for full speed. */
    public volatile TraceHook trace;
    /** Optional interrupt hook. */
    public volatile InterruptHook interruptHook;
    /** Optional hook for unimplemented instructions (also counted, see {@link #unimplementedCounts}). */
    public volatile UnimplementedHook unimplementedHook;

    private final Map<String, LongAdder> unimplemented = new ConcurrentHashMap<>();
    private final Op unimplementedOp = this::unimplemented;

    // Decrementer: DEC = decNext - TB. Pending is level-triggered and latched (QEMU book3s model):
    // raised when DEC reaches 0 or a negative value is written, lowered when a non-negative
    // value is written.
    private long decNext;
    private boolean decPending;
    /** A non-negative value was written and DEC has not reached zero since. */
    private boolean decArmed;
    /** DETERMINISTIC: icount at which the decrementer reaches 0 (Long.MAX_VALUE if never). */
    private long decDueIcount = Long.MAX_VALUE;
    /** Timebase offset (TB = virtual-time ticks + tbOffset). */
    private long tbOffset;

    private long lastProcTable;

    // Fetch cache: real address of the current 4 KiB instruction page.
    private long fetchPage = -1;
    private long fetchBase;

    /** Deterministic clock. */
    public Interpreter(CpuState s, PhysicalMemory mem, DecodeTable table) {
        this(s, mem, table, Clock.DETERMINISTIC);
    }

    public Interpreter(CpuState s, PhysicalMemory mem, DecodeTable table, Clock clock) {
        this.s = s;
        this.seg = mem.segment();
        this.table = table;
        this.clock = clock;
        this.mmu = new RadixMmu(s, mem);
        mmu.owner = this;
        s.mem = mmu;
        lastProcTable = s.procTable;
        if (s.spr[Spr.PVR] == 0) {
            s.spr[Spr.PVR] = Spr.PVR_POWER9;
        }
        storeDec(0xffffffffL);
    }

    public RadixMmu mmu() {
        return mmu;
    }

    public Clock clock() {
        return clock;
    }

    /** The interpreter driving {@code s}, reached through the installed MMU. */
    static Interpreter of(CpuState s) {
        return ((RadixMmu) s.mem).owner;
    }

    /**
     * Reset architectural state to what QEMU's pseries machine gives a CPU at power-on: MSR =
     * ME|FP (32-bit, big-endian, real mode), all registers zero, PVR, LPCR = LPES0|LPES1|LD plus
     * the power-saving exit enables, PSSCR[EC], a full AMOR, DEC = 0xffffffff. The caller sets
     * pc and the entry registers.
     */
    public void resetPapr() {
        java.util.Arrays.fill(s.gpr, 0);
        java.util.Arrays.fill(s.spr, 0);
        s.lr = 0;
        s.ctr = 0;
        s.cr = 0;
        s.setXer(0);
        s.pc = 0;
        s.npc = 0;
        s.msr = Msr.ME | Msr.FP;
        s.spr[Spr.PVR] = Spr.PVR_POWER9;
        s.spr[Spr.LPCR] = LPCR_LPES0 | LPCR_LPES1 | LPCR_EEE | LPCR_DEE | LPCR_OEE | LPCR_PDEE
                | LPCR_HDEE | Spr.LPCR_LD;
        s.spr[PSSCR] = 1L << 20; // PSSCR[EC]
        s.spr[AMOR] = -1L;
        s.resValid = false;
        s.ceded = false;
        waiting = false;
        tbOffset = 0;
        storeDec(0xffffffffL);
        mmu.flushAll();
        recheck = true;
    }

    // ================================================================== time

    /** Current virtual time in nanoseconds. */
    long nowNs() {
        return clock == Clock.DETERMINISTIC ? s.icount << icountShift : System.nanoTime() - wallBase;
    }

    static long nsToTb(long ns) {
        // ns * 512e6 / 1e9 = ns * 64 / 125 (floor), without overflow for any realistic ns.
        return (ns / 125) * 64 + (ns % 125) * 64 / 125;
    }

    /** Smallest ns with nsToTb(ns) >= tb. */
    static long tbToNsCeil(long tb) {
        if (tb <= 0) {
            return 0;
        }
        return (tb / 64) * 125 + ((tb % 64) * 125 + 63) / 64;
    }

    /** Timebase register value. */
    public long tb() {
        return nsToTb(nowNs()) + tbOffset;
    }

    /** Set the timebase (TB is otherwise read-only to a guest). The decrementer keeps its value. */
    public void setTimebase(long value) {
        long dec = loadDec();
        tbOffset = value - nsToTb(nowNs());
        storeDec(dec);
    }

    private int decBits() {
        return (s.spr[Spr.LPCR] & Spr.LPCR_LD) != 0 ? LARGE_DEC_BITS : 32;
    }

    /** Value read by mfspr DEC: sign-extended with LPCR[LD]=1, else the low 32 bits. */
    public long loadDec() {
        long d = decNext - tb();
        int bits = decBits();
        if (bits == 32) {
            return d & 0xffffffffL;
        }
        return (d << (64 - bits)) >> (64 - bits);
    }

    /** mtspr DEC. */
    public void storeDec(long value) {
        int bits = decBits();
        long v = value & ((1L << bits) - 1);
        long signed = (v << (64 - bits)) >> (64 - bits);
        long now = tb();
        decNext = now + v;
        decDueIcount = Long.MAX_VALUE;
        if (signed < 0) {
            decPending = true;
            decArmed = false;
        } else {
            decPending = false;
            decArmed = true;
            if (clock == Clock.DETERMINISTIC) {
                long dueNs = tbToNsCeil(decNext - tbOffset);
                decDueIcount = icountShift == 0 ? dueNs
                        : ((dueNs + (1L << icountShift) - 1) >>> icountShift);
            }
        }
        recheck = true;
    }

    /** Whether a decrementer interrupt is pending (level, latched until DEC is written). */
    public boolean decPending() {
        if (decArmed) {
            boolean due = clock == Clock.DETERMINISTIC ? s.icount >= decDueIcount : tb() >= decNext;
            if (due) {
                decPending = true;
                decArmed = false;
                decDueIcount = Long.MAX_VALUE;
            }
        }
        return decPending;
    }

    /** Nanoseconds of virtual time until the decrementer reaches zero, or Long.MAX_VALUE. */
    private long nsUntilDec() {
        if (decPending) {
            return 0;
        }
        if (!decArmed) {
            return Long.MAX_VALUE;
        }
        long ticks = decNext - tb();
        if (ticks <= 0) {
            return 0;
        }
        return tbToNsCeil(ticks);
    }

    // ================================================================== run loop

    /** Run until {@code s.stopRequested} is set. */
    public void run() {
        run(Long.MAX_VALUE);
    }

    /**
     * Run at most {@code maxInsns} instructions (or until {@code s.stopRequested}). Instructions
     * that raise a synchronous interrupt count as executed; idle time does not. Returns the
     * number executed.
     */
    public long run(long maxInsns) {
        Thread prev = s.runner;
        s.runner = Thread.currentThread();
        long done = 0;
        try {
            while (done < maxInsns && !s.stopRequested) {
                if (s.ceded || waiting) {
                    if (!idle()) {
                        break;
                    }
                    continue;
                }
                checkAsync();
                if (s.stopRequested) {
                    break;
                }
                long budget = Math.min(CHUNK, maxInsns - done);
                done += chunk(budget);
            }
        } finally {
            s.runner = prev;
        }
        return done;
    }

    /** Deliver a pending asynchronous interrupt if MSR[EE] allows (QEMU POWER9 priority order). */
    private void checkAsync() {
        if (s.procTable != lastProcTable) {
            lastProcTable = s.procTable;
            mmu.flushAll();
        }
        if ((s.msr & Msr.EE) == 0) {
            return;
        }
        if (s.extIrq) {
            deliver(PpcException.EXTERNAL, s.pc, 0);
        } else if (decPending()) {
            deliver(PpcException.DECREMENTER, s.pc, 0);
        }
    }

    private long chunk(long budget) {
        final CpuState s = this.s;
        recheck = false;
        fetchPage = -1;
        long start = s.icount;
        long end = start + budget;
        // Stop early when the decrementer becomes due, if it could be delivered.
        if ((s.msr & Msr.EE) != 0 && decDueIcount < end) {
            end = Math.max(decDueIcount, start + 1);
        }
        final boolean le = (s.msr & Msr.LE) != 0;
        final long pcMask = s.msr < 0 ? -1L : 0xffffffffL;
        final TraceHook hook = trace;
        while (s.icount < end) {
            long pc = s.pc;
            int insn;
            try {
                long page = pc & ~0xfffL;
                if (page != fetchPage) {
                    long pa = mmu.translateFetch(pc);
                    fetchPage = page;
                    fetchBase = pa & ~0xfffL;
                }
                long pa = fetchBase | (pc & 0xfff);
                insn = le ? seg.get(I_LE, pa) : seg.get(I_BE, pa);
            } catch (PpcException e) {
                s.icount++;
                deliverSync(e);
                return s.icount - start;
            }
            int h = (insn * 0x9E3779B1) >>> (32 - DCACHE_BITS);
            Op op = dops[h];
            if (op == null || dkeys[h] != insn) {
                op = decode(insn);
                dops[h] = op;
                dkeys[h] = insn;
            }
            s.npc = (pc + 4) & pcMask;
            if (hook != null) {
                hook.onInsn(s, pc, insn);
            }
            try {
                op.exec(s, insn);
            } catch (PpcException e) {
                s.icount++;
                deliverSync(e);
                return s.icount - start;
            }
            s.pc = s.npc;
            s.icount++;
            if (recheck) {
                break;
            }
        }
        return s.icount - start;
    }

    private Op decode(int insn) {
        DecodeTable.Entry e = table.lookup(insn);
        return e == null ? unimplementedOp : e.op();
    }

    /** Name of the decoded instruction, for diagnostics ("unimplemented" if none). */
    public String disasmName(int insn) {
        DecodeTable.Entry e = table.lookup(insn);
        return e == null ? "unimplemented" : e.name();
    }

    /** Key naming the opcode of an instruction word: primary opcode and, if it has one, XO. */
    public static String opcodeKey(int insn) {
        int po = insn >>> 26;
        return switch (po) {
            case 4 -> String.format("4/%d (va %d)", (insn >>> 1) & 0x3ff, insn & 0x3f);
            case 19, 31, 63 -> String.format("%d/%d", po, (insn >>> 1) & 0x3ff);
            case 30 -> String.format("30/%d", (insn >>> 1) & 0xf);
            case 59 -> String.format("59/%d", (insn >>> 1) & 0x3ff);
            case 60 -> String.format("60/%d", (insn >>> 2) & 0x1ff);
            case 57, 58, 61, 62 -> String.format("%d/%d", po, insn & 3);
            default -> Integer.toString(po);
        };
    }

    private void unimplemented(CpuState st, int insn) {
        String key = opcodeKey(insn);
        unimplemented.computeIfAbsent(key, k -> new LongAdder()).increment();
        UnimplementedHook h = unimplementedHook;
        if (h != null) {
            h.onUnimplemented(st, st.pc, insn, key);
        }
        throw PpcException.illegal();
    }

    /** Execution counts of unimplemented instructions, by opcode key (see {@link #opcodeKey}). */
    public Map<String, Long> unimplementedCounts() {
        Map<String, Long> m = new TreeMap<>();
        unimplemented.forEach((k, v) -> m.put(k, v.sum()));
        return m;
    }

    // ================================================================== idle

    /**
     * Whether a pending interrupt ends a power-saving wait. With PSSCR[EC]=1 (as QEMU's pseries
     * sets it) the LPCR exit enables decide, regardless of MSR[EE]; otherwise any pending
     * interrupt does.
     */
    private boolean wakeupPending() {
        long lpcr = s.spr[Spr.LPCR];
        boolean ec = (s.spr[PSSCR] & (1L << 20)) != 0;
        boolean ext = s.extIrq && (!ec || (lpcr & LPCR_EEE) != 0);
        boolean dec = (!ec || (lpcr & LPCR_DEE) != 0) && decPending();
        return ext || dec;
    }

    /**
     * Idle until an interrupt is pending (H_CEDE / wait). Returns false if the run loop should
     * return because a stop was requested.
     */
    private boolean idle() {
        while (true) {
            if (s.stopRequested) {
                return false;
            }
            if (wakeupPending()) {
                s.ceded = false;
                waiting = false;
                return true;
            }
            boolean decWakes = (s.spr[PSSCR] & (1L << 20)) == 0 || (s.spr[Spr.LPCR] & LPCR_DEE) != 0;
            if (clock == Clock.DETERMINISTIC) {
                if (decWakes && decDueIcount != Long.MAX_VALUE) {
                    // Jump straight to the decrementer event.
                    s.icount = Math.max(s.icount, decDueIcount);
                    continue;
                }
                LockSupport.parkNanos(this, MAX_PARK_NS);
            } else {
                long wait = decWakes ? Math.min(nsUntilDec(), MAX_PARK_NS) : MAX_PARK_NS;
                if (wait > 0) {
                    LockSupport.parkNanos(this, wait);
                }
            }
        }
    }

    // ================================================================== interrupts

    private void deliverSync(PpcException e) {
        switch (e.vector) {
            case PpcException.DSI -> {
                s.spr[Spr.DAR] = e.dar;
                if (e.dsisr == RadixMmu.DSISR_SEGMENT_MARKER) {
                    deliver(PpcException.DSEG, s.pc, 0);
                } else {
                    s.spr[Spr.DSISR] = e.dsisr & 0xffffffffL;
                    deliver(PpcException.DSI, s.pc, 0);
                }
            }
            case PpcException.ISI -> {
                if (e.srr1 == RadixMmu.SRR1_SEGMENT_MARKER) {
                    deliver(PpcException.ISEG, s.pc, 0);
                } else {
                    deliver(PpcException.ISI, s.pc, e.srr1);
                }
            }
            case PpcException.ALIGNMENT -> {
                // ISA 3.0 dropped the DSISR update; only DAR is set.
                s.spr[Spr.DAR] = e.dar;
                deliver(PpcException.ALIGNMENT, s.pc, 0);
            }
            case PpcException.MACHINE_CHECK -> {
                // Unassigned real memory: QEMU's POWER9 codes by access type.
                long bits = e.srr1;
                switch (mmu.mcType) {
                    case RadixMmu.LOAD -> {
                        s.spr[Spr.DAR] = e.dar;
                        s.spr[Spr.DSISR] = RadixMmu.MC_DSISR_LOAD;
                        bits |= RadixMmu.MC_SRR1_LOAD;
                    }
                    case RadixMmu.STORE -> {
                        s.spr[Spr.DAR] = e.dar;
                        bits |= RadixMmu.MC_SRR1_STORE;
                    }
                    default -> bits |= RadixMmu.MC_SRR1_FETCH;
                }
                deliver(PpcException.MACHINE_CHECK, s.pc, bits);
            }
            case PpcException.SYSCALL -> deliver(PpcException.SYSCALL, s.npc, 0);
            case PpcException.SYSCALL_VECTORED -> deliverScv(e.aux);
            case PpcException.FAC_UNAVAILABLE -> {
                // QEMU ORs the interruption cause into FSCR[IC] (ISA bits 0:7).
                s.spr[Spr.FSCR] |= (long) (e.aux & 0xff) << 56;
                deliver(e.vector, s.pc, 0);
            }
            default -> deliver(e.vector, s.pc, e.srr1);
        }
    }

    /** Book3S interrupt delivery for a guest under a virtual hypervisor (QEMU powerpc_excp_books). */
    void deliver(int vector, long srr0, long srr1Bits) {
        long msr = s.msr;
        long srr1 = (msr & ~0x783f0000L) | srr1Bits;
        long newMsr = (msr & Msr.ME) | Msr.SF;
        long lpcr = s.spr[Spr.LPCR];
        if ((lpcr & Spr.LPCR_ILE) != 0) {
            newMsr |= Msr.LE;
        }
        long vec = vector;
        if (vector != PpcException.MACHINE_CHECK && vector != 0x100) {
            int ail = (int) ((lpcr & Spr.LPCR_AIL_MASK) >>> Spr.LPCR_AIL_SHIFT);
            if ((msr & Msr.IR) != 0 && (msr & Msr.DR) != 0 && ail >= 2) {
                newMsr |= Msr.IR | Msr.DR;
                vec |= ail == 2 ? 0x18000L : 0xc000000000004000L;
            }
        }
        s.spr[Spr.SRR0] = srr0;
        s.spr[Spr.SRR1] = srr1;
        enter(vec, newMsr);
        InterruptHook h = interruptHook;
        if (h != null) {
            h.onInterrupt(s, vector, srr0);
        }
    }

    private void deliverScv(int lev) {
        long msr = s.msr;
        long cur = msr & ~0x783f0000L;
        long newMsr = (msr & (Msr.ME | Msr.EE | Msr.RI)) | Msr.SF;
        long lpcr = s.spr[Spr.LPCR];
        if ((lpcr & Spr.LPCR_ILE) != 0) {
            newMsr |= Msr.LE;
        }
        long vec = 0x17000L + lev * 0x20L;
        int ail = (int) ((lpcr & Spr.LPCR_AIL_MASK) >>> Spr.LPCR_AIL_SHIFT);
        if ((msr & Msr.IR) != 0 && (msr & Msr.DR) != 0 && ail >= 2) {
            newMsr |= Msr.IR | Msr.DR;
            if (ail == 3) {
                vec = (vec & ~0x17000L) | 0xc000000000003000L;
            }
        }
        s.lr = s.npc;
        s.ctr = cur;
        enter(vec, newMsr);
        InterruptHook h = interruptHook;
        if (h != null) {
            h.onInterrupt(s, PpcException.SYSCALL_VECTORED, s.lr);
        }
    }

    private void enter(long vec, long newMsr) {
        mmu.syncFlush(); // interrupts are context synchronising
        s.msr = newMsr;
        s.pc = vec;
        s.npc = vec;
        s.resValid = false;
        recheck = true;
    }

    /**
     * Store a new MSR value as rfid/mtmsr do for a guest (QEMU hreg_store_msr): bits outside the
     * POWER9 mask are dropped, HV cannot be set, ME cannot change, PR=1 forces EE/IR/DR.
     */
    void storeMsr(long value) {
        value &= MSR_MASK;
        value = (value & ~Msr.ME) | (s.msr & Msr.ME);
        if ((value & Msr.PR) != 0) {
            value |= Msr.EE | Msr.IR | Msr.DR;
        }
        s.msr = value;
        recheck = true;
    }

    /** rfid / hrfid / rfscv. */
    void returnFromInterrupt(long nip, long msr) {
        if (msr >= 0) { // new MSR[SF] = 0
            nip &= 0xffffffffL;
        }
        s.npc = nip & ~3L;
        storeMsr(msr);
        s.resValid = false;
        mmu.syncFlush();
    }

    /** sc 1 from supervisor state: call the hypervisor and pick up any state it changed. */
    void hcall() {
        if ((s.msr & Msr.PR) != 0) {
            s.gpr[3] = H_PRIVILEGE;
            return;
        }
        s.hv.hcall(s);
        recheck = true;
    }
}
