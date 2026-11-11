package org.boxtheta.javapower.cpu;

import java.util.List;
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
 * <p>Instructions are fetched from guest memory on every execution. Decoding is a pure function of
 * the instruction word, so decoded results are cached keyed by the word itself and the caches
 * never go stale: self-modifying code, DMA into code pages and MSR[LE] switches need no
 * invalidation. The first-level cache is indexed by the instruction's real address and validated
 * by comparing the fetched word; misses fall back to a cache indexed by a hash of the word, then
 * to the {@link DecodeTable}.
 *
 * <p>This module's own handlers are executed inline from a switch in the run loop (see
 * {@link Reg}), so the JIT can inline them; other handlers are called through {@link Op#exec}.
 *
 * <p>Hot guest code is translated to JVM bytecode in two tiers (on by default, see
 * {@link #compileBlocks}): straight-line blocks that call the instruction handlers
 * ({@link BlockCompiler}), and, for entries that stay hot, regions of a page with guest registers in
 * JVM locals and inline memory access ({@link RegionCompiler}). Compiled code runs only where it
 * is exactly equivalent to interpreting: its page's code generation (see
 * {@link PhysicalMemory#watchCode}) must not have changed since its words were last checked
 * (otherwise they are compared again), its first block must fit before the end of the current
 * chunk, and it stops early after a store into its own code. Results, instruction counts and
 * interrupt timing are therefore identical with and without it. Compiled code does not depend on
 * its effective address and is shared by the CPUs of a domain ({@link #shareCode}).
 *
 * <p>Asynchronous interrupts (external, decrementer) are checked between chunks of at most
 * {@value #CHUNK} instructions and whenever an instruction changes state that affects them
 * (MSR, DEC, hcalls).
 *
 * <h2>Several CPUs</h2>
 * A multi-CPU machine has one interpreter per CPU, all built on one {@link CpuDomain} (shared
 * memory, timebase and TLB invalidation), each run by its own host thread. What an interpreter
 * caches (decode caches, its table of compiled entries, TLB) is private to it and touched only by
 * its thread; compiled code itself is immutable and shared through the domain. Guest memory is
 * shared without locks: loads and stores are plain host accesses,
 * the guest's barriers are host fences, and store-conditional and the atomic memory operations
 * are host compare-and-swaps (see {@link LoadStore}). Another CPU's stores to code (and device
 * DMA) are seen at once by the interpreter (it compares the fetched word) and by compiled code at
 * its next entry, which is at the latest the next context-synchronising instruction or interrupt:
 * compiled code never spans one. A store that races with another CPU's compile of the same page
 * may go unnoticed until the guest's {@code icbi}, as the architecture allows.
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

    /**
     * Second-level decode cache: entries indexed by a hash of the instruction word, two ways per
     * set. Sized for a whole Linux userland's worth of distinct instruction words: a miss costs
     * a linear scan of a {@link DecodeTable} bucket (hundreds of entries for opcodes 31 and 60).
     */
    private static final int DCACHE_BITS = 17;
    /** First-level decode cache: entries indexed by real address bits 2.., i.e. 512 KiB of code. */
    private static final int LCACHE_BITS = 17;
    static final int CHUNK = 4096;
    /** Longest single park while idle, so stop requests are noticed. */
    private static final long MAX_PARK_NS = 50_000_000L;

    final CpuState s;
    final RadixMmu mmu;
    private final PhysicalMemory pm;
    private final DecodeTable table;
    private final Clock clock;
    /** The CPUs this one shares time, TLB invalidation and wide atomics with. */
    final CpuDomain domain;

    // Cross-CPU state (see CpuDomain). outside: not executing guest instructions (idle, parked,
    // in a hypercall or not in run()); written by the owner, read by CPUs completing a tlbie.
    volatile boolean outside = true;
    /** Last global TLB flush generation this CPU has applied. */
    volatile long tlbAck;
    /** A tlbie was executed; the next ptesync or tlbsync completes it on every CPU. */
    boolean globalFlushPending;
    /** Between Hypervisor.cpuExecEnter and cpuExecExit. */
    private boolean executing;
    /** Source of {@code darn}; owned by this CPU's thread. */
    final java.util.SplittableRandom random;
    /** lqarx: the second doubleword it observed (the first is CpuState.resVal). */
    long resValLo;

    // Decode caches. An entry is (instruction word << 32) | dispatch slot; a slot below
    // FIRST_DYNAMIC is a K_* inline handler, any slot indexes ops[]. Entries are only ever compared
    // by word, so they never need invalidation; both are pre-filled with the entry for word 0.
    private final long[] lcache = new long[1 << LCACHE_BITS];
    private final long[] dcache = new long[1 << DCACHE_BITS];
    /** Handler for each dispatch slot (inline slots hold the registered, tagged op). */
    private Op[] ops = new Op[FIRST_DYNAMIC + 64];
    private int nextSlot = FIRST_DYNAMIC;
    private final Map<Op, Integer> slotOf = new java.util.IdentityHashMap<>();

    /** Set by handlers that change state the run loop caches (MSR, TLB, timers); ends the chunk. */
    boolean recheck;
    /** Set by {@code wait}: idle until an interrupt is pending. */
    boolean waiting;

    /** DETERMINISTIC clock only: virtual nanoseconds per instruction = {@code 1 << icountShift}. */
    public volatile int icountShift = 0;
    /** Optional per-instruction hook; null for full speed. */
    public volatile TraceHook trace;
    /** Optional interrupt hook. */
    public volatile InterruptHook interruptHook;
    /** Optional hook for unimplemented instructions (also counted, see {@link #unimplementedCounts}). */
    public volatile UnimplementedHook unimplementedHook;

    private final Map<String, LongAdder> unimplemented = new ConcurrentHashMap<>();
    /** Handler of instructions without one; static so compiled code that calls it can be shared. */
    private static final Op UNIMPLEMENTED_OP = (st, insn) -> Interpreter.of(st).unimplemented(st, insn);

    // Decrementer: DEC = decNext - TB. Pending is level-triggered and latched (QEMU book3s model):
    // raised when DEC reaches 0 or a negative value is written, lowered when a non-negative
    // value is written.
    //
    // Reaching 0 is noticed no sooner than DEC_MIN_INSNS instructions after the write, unless the
    // CPU idles. In QEMU an expiry is raised by a host timer callback, so the vCPU always runs on
    // for a while after an mtdec whose deadline is already past; without that slack, a guest
    // whose timer handler takes longer than the period it re-arms (FreeBSD's timerfd with a 1 us
    // interval) takes the next decrementer interrupt right after each rfid and never returns to
    // the interrupted code. A negative value written still raises it at once, as in QEMU.
    static final long DEC_MIN_INSNS = CHUNK;
    private long decNext;
    private boolean decPending;
    /** A non-negative value was written and DEC has not reached zero since. */
    private boolean decArmed;
    /** icount before which an armed decrementer does not become pending while executing. */
    private long decEarliest;
    /** DETERMINISTIC: icount at which the decrementer becomes pending (Long.MAX_VALUE if never). */
    private long decDueIcount = Long.MAX_VALUE;
    /** DETERMINISTIC: icount at which DEC reaches 0, without the DEC_MIN_INSNS floor (idle wake-up). */
    private long decZeroIcount = Long.MAX_VALUE;
    /** Timebase offset (TB = virtual-time ticks + tbOffset). */
    private long tbOffset;

    private long lastProcTable;

    /** Statistics, for tests and tuning: first-level decode cache misses, full decodes. */
    long l1Misses;
    long decodes;

    // Run-loop state for the current chunk (see chunk()).
    /** icount at which the current chunk ends; read by compiled regions for their budget checks. */
    long chunkEnd;
    private boolean fetchLe;
    private long pcMask;
    private TraceHook hook;
    /** Effective page of the current instruction page and its real base; valid for one chunk. */
    private long fetchPage;
    private long fetchBase;

    /** Deterministic clock, single CPU. */
    public Interpreter(CpuState s, PhysicalMemory mem, DecodeTable table) {
        this(s, mem, table, Clock.DETERMINISTIC);
    }

    /** A single CPU with its own {@link CpuDomain}. */
    public Interpreter(CpuState s, PhysicalMemory mem, DecodeTable table, Clock clock) {
        this(s, new CpuDomain(mem, clock), table);
    }

    /**
     * A CPU of a multi-CPU machine: every interpreter of {@code domain} shares its memory and
     * timebase. Each must run on its own thread. {@code table} may be shared; it must be complete.
     */
    public Interpreter(CpuState s, CpuDomain domain, DecodeTable table) {
        PhysicalMemory mem = domain.memory();
        this.s = s;
        this.pm = mem;
        this.table = table;
        this.domain = domain;
        this.clock = domain.clock();
        this.mmu = new RadixMmu(s, mem);
        mmu.owner = this;
        s.mem = mmu;
        lastProcTable = s.procTable;
        long e0 = decodeEntry(0);
        java.util.Arrays.fill(lcache, e0);
        java.util.Arrays.fill(dcache, e0);
        if (s.spr[Spr.PVR] == 0) {
            s.spr[Spr.PVR] = Spr.PVR_POWER9;
        }
        storeDec(0xffffffffL);
        int ordinal = domain.register(this);
        this.random = new java.util.SplittableRandom(domain.seedFor(ordinal));
    }

    public RadixMmu mmu() {
        return mmu;
    }

    public Clock clock() {
        return clock;
    }

    public CpuDomain domain() {
        return domain;
    }

    public CpuState state() {
        return s;
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
        globalFlushPending = false;
        tbOffset = 0;
        storeDec(0xffffffffL);
        mmu.flushAll();
        recheck = true;
    }

    // ================================================================== time

    /** Current virtual time in nanoseconds. */
    long nowNs() {
        return clock == Clock.DETERMINISTIC ? s.icount << icountShift : domain.wallNs();
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
        decZeroIcount = Long.MAX_VALUE;
        if (signed < 0) {
            decPending = true;
            decArmed = false;
        } else {
            decPending = false;
            decArmed = true;
            decEarliest = s.icount + DEC_MIN_INSNS;
            if (clock == Clock.DETERMINISTIC) {
                long dueNs = tbToNsCeil(decNext - tbOffset);
                decZeroIcount = icountShift == 0 ? dueNs
                        : ((dueNs + (1L << icountShift) - 1) >>> icountShift);
                decDueIcount = Math.max(decZeroIcount, decEarliest);
            }
        }
        recheck = true;
    }

    /** Whether a decrementer interrupt is pending (level, latched until DEC is written). */
    public boolean decPending() {
        if (decArmed) {
            boolean due = clock == Clock.DETERMINISTIC ? s.icount >= decDueIcount
                    : s.icount >= decEarliest && tb() >= decNext;
            if (due) {
                decPending = true;
                decArmed = false;
                decDueIcount = Long.MAX_VALUE;
                decZeroIcount = Long.MAX_VALUE;
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
     * number executed. {@code s.stopRequested} is left set for the caller to clear.
     *
     * <p>The loop honours the platform's contract (see {@link CpuState}): it parks while
     * {@code halted} is set, runs work posted with {@link CpuState#runOnCpu} between chunks and
     * while idle or parked, idles after H_CEDE or {@code wait} until an interrupt is pending or
     * the CPU is prodded, and tells the hypervisor when it stops and resumes executing
     * ({@link org.boxtheta.javapower.core.Hypervisor#cpuExecExit}, {@code cpuExecEnter}).
     * Changes other threads make ({@code extIrq}, {@code stopRequested}, {@code halted}, posted
     * work, TLB flushes) are seen at the next chunk boundary, at most {@value #CHUNK}
     * instructions later, or at once when the CPU is idle or parked.
     */
    public long run(long maxInsns) {
        Thread prev = s.runner;
        s.runner = Thread.currentThread();
        long done = 0;
        try {
            while (done < maxInsns && !s.stopRequested) {
                if (s.halted) {
                    parkHalted();
                    continue;
                }
                if (s.workPending) {
                    runPostedWork();
                    continue;
                }
                if (s.ceded || waiting) {
                    if (!idle()) {
                        break;
                    }
                    continue;
                }
                if (!executing) {
                    enterGuest();
                }
                if (domain.tlbGeneration.get() != tlbAck) {
                    applyTlbGeneration();
                }
                checkAsync();
                if (s.stopRequested) {
                    break;
                }
                long budget = Math.min(CHUNK, maxInsns - done);
                done += chunk(budget);
            }
        } finally {
            leaveGuest();
            s.runner = prev;
        }
        return done;
    }

    /** About to execute guest instructions (again): see the TLB protocol in {@link CpuDomain}. */
    private void enterGuest() {
        executing = true;
        outside = false; // volatile: ordered before the caller's read of the TLB generation
        var h = s.hv;
        if (h != null) {
            h.cpuExecEnter(s);
        }
    }

    /** Stop executing guest instructions (idle, park, return from run). Idempotent. */
    private void leaveGuest() {
        if (!executing) {
            return;
        }
        executing = false;
        outside = true;
        var h = s.hv;
        if (h != null) {
            h.cpuExecExit(s);
        }
    }

    /** Flush the software TLB if another CPU completed a global flush since the last one seen. */
    void applyTlbGeneration() {
        long g = domain.tlbGeneration.get();
        if (g != tlbAck) {
            mmu.flushAll();
            recheck = true;
            tlbAck = g;
        }
    }

    /** ptesync / tlbsync after a tlbie: invalidate every CPU's TLB before continuing. */
    void completeGlobalFlush() {
        globalFlushPending = false;
        domain.shootdown(this);
    }

    /**
     * Run work posted with {@link CpuState#runOnCpu}, then drop everything cached from the state
     * it may have changed (MSR, LPCR, PIDR, process table, pc): the TLB and the next chunk's
     * fetch state.
     */
    private void runPostedWork() {
        s.runPostedWork();
        mmu.flushAll();
        lastProcTable = s.procTable;
        recheck = true;
    }

    /**
     * RTAS-stopped: execute nothing and take no interrupts until another thread clears
     * {@code halted} (start-cpu) or asks the loop to stop. Posted work still runs, in order.
     */
    private void parkHalted() {
        leaveGuest();
        while (s.halted && !s.stopRequested) {
            if (s.workPending) {
                runPostedWork();
            } else {
                LockSupport.parkNanos(this, MAX_PARK_NS);
            }
        }
        waiting = false;
    }

    /**
     * Deliver a system reset interrupt at 0x100 (H_SIGNAL_SYS_RESET, the watchdog's dump-restart
     * action). Call on this CPU's own thread, e.g. from work posted with
     * {@link CpuState#runOnCpu}. Ends an H_CEDE or {@code wait} idle; like QEMU for a ceded
     * pseries vCPU, SRR1 carries no wake-up reason.
     */
    public void systemReset() {
        s.ceded = false;
        waiting = false;
        deliver(0x100, s.pc, 0);
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
        long start = s.icount;
        long end = start + budget;
        // Stop early when the decrementer becomes due, if it could be delivered.
        if ((s.msr & Msr.EE) != 0 && decDueIcount < end) {
            end = Math.max(decDueIcount, start + 1);
        }
        // Loop invariants live in fields rather than locals: the loop body makes calls, and C2
        // spills and reloads every live local around the whole loop when it does.
        chunkEnd = end;
        fetchLe = (s.msr & Msr.LE) != 0;
        pcMask = s.msr < 0 ? -1L : 0xffffffffL;
        hook = trace;
        fetchPage = -1;
        jitOn = compileBlocks && hook == null;
        blockMode = RegionCompiler.mode(s.msr);
        // Trace interrupts (MSR[SE], MSR[BE]) are decided by the MSR before each instruction, and
        // any MSR change ends the chunk: with either bit set, run one instruction per chunk.
        traceMsr = s.msr & (MSR_SE | MSR_BE);
        if (traceMsr != 0) {
            chunkEnd = start + 1;
            jitOn = false;
        }
        // Whether pc may start a compiled block: true at a branch target and after a block.
        boolean blockStart = true;
        while (true) {
            long pc = s.pc;
            if ((pc & ~0xfffL) != fetchPage && !newFetchPage(pc)) {
                return s.icount - start;
            }
            long pa = fetchBase | (pc & 0xfff);
            if (blockStart && jitOn) {
                int r = runBlock(pc, pa);
                if (r != NO_BLOCK) {
                    if (r == CompiledBlock.FAULTED || s.icount >= chunkEnd || recheck) {
                        return s.icount - start;
                    }
                    continue;
                }
            }
            int insn = fetchLe ? pm.read32le(pa) : pm.read32be(pa);
            int li = (int) (pa >>> 2) & ((1 << LCACHE_BITS) - 1);
            long entry = lcache[li];
            if ((int) (entry >>> 32) != insn) {
                entry = lookupEntry(insn);
                lcache[li] = entry;
            }
            s.npc = (pc + 4) & pcMask;
            if (hook != null) {
                hook.onInsn(s, pc, insn);
            }
            try {
                switch ((int) entry) {
                    // BEGIN GENERATED CASES
                    case K_LD -> LoadStore.ld(s, insn);
                    case K_STD -> LoadStore.std(s, insn);
                    case K_ADDI -> Alu.addi(s, insn);
                    case K_BC -> BranchInsns.bc(s, insn);
                    case K_OR -> Alu.or(s, insn);
                    case K_ADD -> Alu.add(s, insn);
                    case K_CMPI -> Alu.cmpi(s, insn);
                    case K_ORI -> Alu.ori(s, insn);
                    case K_RLWINM -> Alu.rlwinm(s, insn);
                    case K_B -> BranchInsns.b(s, insn);
                    case K_LWZ -> LoadStore.lwz(s, insn);
                    case K_BCCTR -> BranchInsns.bcctr(s, insn);
                    case K_XOR -> Alu.xor(s, insn);
                    case K_RLDICL -> Alu.rldicl(s, insn);
                    case K_BCLR -> BranchInsns.bclr(s, insn);
                    case K_ADDIS -> Alu.addis(s, insn);
                    case K_MTSPR -> SprAccess.mtspr(s, insn);
                    case K_MFSPR -> SprAccess.mfspr(s, insn);
                    case K_STW -> LoadStore.stw(s, insn);
                    case K_ANDI_DOT -> Alu.andi(s, insn);
                    case K_LBZ -> LoadStore.lbz(s, insn);
                    case K_STDU -> LoadStore.stdu(s, insn);
                    case K_AND -> Alu.and(s, insn);
                    case K_CMPL -> Alu.cmpl(s, insn);
                    case K_CMPLI -> Alu.cmpli(s, insn);
                    case K_SUBF -> Alu.subf(s, insn);
                    case K_RLDICR -> Alu.rldicr(s, insn);
                    case K_CMP -> Alu.cmp(s, insn);
                    case K_EXTSW -> Alu.extsw(s, insn);
                    case K_LDX -> LoadStore.ldx(s, insn);
                    case K_MULLD -> Alu.mulld(s, insn);
                    case K_STB -> LoadStore.stb(s, insn);
                    case K_STBU -> LoadStore.stbu(s, insn);
                    case K_MTCRF -> BranchInsns.mtcrf(s, insn);
                    case K_SYNC -> SystemInsns.sync(s, insn);
                    case K_SRD -> Alu.srd(s, insn);
                    case K_LHZ -> LoadStore.lhz(s, insn);
                    case K_NEG -> Alu.neg(s, insn);
                    case K_STDX -> LoadStore.stdx(s, insn);
                    case K_RLDIC -> Alu.rldic(s, insn);
                    case K_SLD -> Alu.sld(s, insn);
                    case K_ANDC -> Alu.andc(s, insn);
                    case K_SUBFE -> Alu.subfe(s, insn);
                    case K_SUBFC -> Alu.subfc(s, insn);
                    case K_RLDIMI -> Alu.rldimi(s, insn);
                    case K_LWZX -> LoadStore.lwzx(s, insn);
                    case K_LBZX -> LoadStore.lbzx(s, insn);
                    case K_STWU -> LoadStore.stwu(s, insn);
                    case K_STH -> LoadStore.sth(s, insn);
                    case K_MFCR -> BranchInsns.mfcr(s, insn);
                    case K_ISEL -> Alu.isel(s, insn);
                    case K_NOR -> Alu.nor(s, insn);
                    case K_EXTSB -> Alu.extsb(s, insn);
                    case K_EXTSH -> Alu.extsh(s, insn);
                    case K_LWA -> LoadStore.lwa(s, insn);
                    case K_LHA -> LoadStore.lha(s, insn);
                    case K_LHZX -> LoadStore.lhzx(s, insn);
                    case K_CROR -> BranchInsns.cror(s, insn);
                    case K_CRXOR -> BranchInsns.crxor(s, insn);
                    case K_STWX -> LoadStore.stwx(s, insn);
                    case K_STBX -> LoadStore.stbx(s, insn);
                    case K_LDU -> LoadStore.ldu(s, insn);
                    case K_LWZU -> LoadStore.lwzu(s, insn);
                    case K_LBZU -> LoadStore.lbzu(s, insn);
                    case K_SRADI -> Alu.sradi(s, insn);
                    case K_SRAD -> Alu.srad(s, insn);
                    case K_SRAWI -> Alu.srawi(s, insn);
                    case K_SRAW -> Alu.sraw(s, insn);
                    case K_SLW -> Alu.slw(s, insn);
                    case K_SRW -> Alu.srw(s, insn);
                    case K_CNTLZD -> Alu.cntlzd(s, insn);
                    case K_CNTLZW -> Alu.cntlzw(s, insn);
                    case K_MULLW -> Alu.mullw(s, insn);
                    case K_MULLI -> Alu.mulli(s, insn);
                    case K_DIVD -> Alu.divd(s, insn);
                    case K_DIVDU -> Alu.divdu(s, insn);
                    case K_DIVW -> Alu.divw(s, insn);
                    case K_DIVWU -> Alu.divwu(s, insn);
                    case K_XORI -> Alu.xori(s, insn);
                    case K_ORIS -> Alu.oris(s, insn);
                    case K_XORIS -> Alu.xoris(s, insn);
                    case K_ANDIS_DOT -> Alu.andis(s, insn);
                    case K_SUBFIC -> Alu.subfic(s, insn);
                    case K_ADDIC -> Alu.addic(s, insn);
                    case K_ADDZE -> Alu.addze(s, insn);
                    case K_ADDE -> Alu.adde(s, insn);
                    case K_ADDC -> Alu.addc(s, insn);
                    case K_ADDME -> Alu.addme(s, insn);
                    case K_SUBFME -> Alu.subfme(s, insn);
                    case K_CRAND -> BranchInsns.crand(s, insn);
                    case K_CRANDC -> BranchInsns.crandc(s, insn);
                    case K_CRNOR -> BranchInsns.crnor(s, insn);
                    case K_CRNAND -> BranchInsns.crnand(s, insn);
                    case K_CREQV -> BranchInsns.creqv(s, insn);
                    case K_CRORC -> BranchInsns.crorc(s, insn);
                    case K_MCRF -> BranchInsns.mcrf(s, insn);
                    case K_MFMSR -> SystemInsns.mfmsr(s, insn);
                    case K_MTMSRD -> SystemInsns.mtmsrd(s, insn);
                    case K_ISYNC -> SystemInsns.isync(s, insn);
                    case K_DCBT -> SystemInsns.nop(s, insn);
                    case K_DCBTST -> SystemInsns.nop(s, insn);
                    case K_EIEIO -> SystemInsns.eieio(s, insn);
                    case K_LDARX -> LoadStore.ldarx(s, insn);
                    case K_STDCX_DOT -> LoadStore.stdcx(s, insn);
                    case K_LWARX -> LoadStore.lwarx(s, insn);
                    case K_STWCX_DOT -> LoadStore.stwcx(s, insn);
                    case K_LWBRX -> LoadStore.lwbrx(s, insn);
                    case K_STWBRX -> LoadStore.stwbrx(s, insn);
                    case K_LDBRX -> LoadStore.ldbrx(s, insn);
                    case K_STDBRX -> LoadStore.stdbrx(s, insn);
                    case K_LHBRX -> LoadStore.lhbrx(s, insn);
                    case K_STHBRX -> LoadStore.sthbrx(s, insn);
                    case K_LDUX -> LoadStore.ldux(s, insn);
                    case K_LWZUX -> LoadStore.lwzux(s, insn);
                    case K_LBZUX -> LoadStore.lbzux(s, insn);
                    case K_STDUX -> LoadStore.stdux(s, insn);
                    case K_STWUX -> LoadStore.stwux(s, insn);
                    case K_STBUX -> LoadStore.stbux(s, insn);
                    case K_LHZU -> LoadStore.lhzu(s, insn);
                    case K_LHAU -> LoadStore.lhau(s, insn);
                    case K_STHU -> LoadStore.sthu(s, insn);
                    case K_STHX -> LoadStore.sthx(s, insn);
                    case K_LHAX -> LoadStore.lhax(s, insn);
                    case K_LWAX -> LoadStore.lwax(s, insn);
                    case K_LWAUX -> LoadStore.lwaux(s, insn);
                    case K_POPCNTD -> Alu.popcntd(s, insn);
                    case K_CNTTZD -> Alu.cnttzd(s, insn);
                    case K_CNTTZW -> Alu.cnttzw(s, insn);
                    case K_MULHD -> Alu.mulhd(s, insn);
                    case K_MULHDU -> Alu.mulhdu(s, insn);
                    case K_MULHW -> Alu.mulhw(s, insn);
                    case K_MULHWU -> Alu.mulhwu(s, insn);
                    case K_EQV -> Alu.eqv(s, insn);
                    case K_ORC -> Alu.orc(s, insn);
                    case K_NAND -> Alu.nand(s, insn);
                    case K_EXTSWSLI -> Alu.extswsli(s, insn);
                    case K_RLDCL -> Alu.rldcl(s, insn);
                    case K_RLWNM -> Alu.rlwnm(s, insn);
                    case K_DCBZ -> SystemInsns.dcbz(s, insn);
                    case K_DCBF -> SystemInsns.dcbfst(s, insn);
                    case K_DCBST -> SystemInsns.dcbfst(s, insn);
                    case K_ICBI -> SystemInsns.icbi(s, insn);
                    case K_SC -> SystemInsns.sc(s, insn);
                    case K_RFID -> SystemInsns.rfid(s, insn);
                    case K_TW -> Alu.tw(s, insn);
                    case K_TD -> Alu.td(s, insn);
                    case K_TWI -> Alu.twi(s, insn);
                    case K_TDI -> Alu.tdi(s, insn);
                    case K_MFTB -> SprAccess.mfspr(s, insn);
                    case K_SETB -> Alu.setb(s, insn);
                    case K_CMPB -> Alu.cmpb(s, insn);
                    case K_MODUD -> Alu.modud(s, insn);
                    case K_MODUW -> Alu.moduw(s, insn);
                    case K_MODSD -> Alu.modsd(s, insn);
                    case K_MODSW -> Alu.modsw(s, insn);
                    case K_DSS -> SystemInsns.nop(s, insn);
                    // END GENERATED CASES
                    case K_MFLR -> mflr(s, insn);
                    case K_MFCTR -> mfctr(s, insn);
                    case K_MTLR -> mtlr(s, insn);
                    case K_MTCTR -> mtctr(s, insn);
                    default -> ops[(int) entry].exec(s, insn);
                }
            } catch (PpcException e) {
                s.icount++;
                deliverSync(e);
                return s.icount - start;
            }
            long npc = s.npc;
            s.pc = npc;
            if (++s.icount >= chunkEnd || recheck) {
                if (traceMsr != 0) {
                    traceAfter(pc, insn);
                }
                return s.icount - start;
            }
            blockStart = npc != ((pc + 4) & pcMask);
        }
    }

    // ================================================================== trace interrupts

    /** MSR[SE] (ISA bit 53): single-step trace. */
    static final long MSR_SE = 1L << 10;
    /** MSR[BE] (ISA bit 54): branch trace. */
    static final long MSR_BE = 1L << 9;
    private static final int SIAR = 796;
    /** MSR[SE] | MSR[BE] at the start of the current chunk (which then holds one instruction). */
    private long traceMsr;

    /**
     * After {@code insn} at {@code pc} completed without an interrupt: take a trace interrupt if
     * MSR[SE] was set (except after sc, whose interrupt or hypercall stands in for it, and the
     * rfi class, as QEMU does) or MSR[BE] was set and it was a branch, taken or not. SRR0 is the
     * next instruction, SRR1 has ISA bit 33 set, SIAR holds {@code pc}.
     */
    private void traceAfter(long pc, int insn) {
        int po = insn >>> 26;
        int xo = (insn >>> 1) & 0x3ff;
        boolean trace;
        if ((traceMsr & MSR_SE) != 0) {
            boolean rfi = po == 19 && (xo == 18 || xo == 274 || xo == 82 || xo == 146);
            trace = po != 17 && !rfi;
        } else {
            trace = po == 16 || po == 18 || (po == 19 && (xo == 16 || xo == 528 || xo == 560));
        }
        if (trace && !s.ceded && !waiting) {
            s.spr[SIAR] = pc;
            deliver(PpcException.TRACE, s.pc, 1L << 30);
        }
    }

    // ================================================================== compiled blocks

    /** {@link #runBlock} result: no compiled block was run. */
    private static final int NO_BLOCK = -1;
    private static final int BLOCK_TABLE_BITS = 17;

    /**
     * Translate hot guest code to JVM bytecode. Compiled code behaves exactly like the
     * interpreter; this only trades compile time for speed. Not used while a {@link #trace} hook
     * is installed. Off: everything is interpreted (both tiers below are off).
     */
    public volatile boolean compileBlocks = true;
    /**
     * Second tier (see {@link RegionCompiler}): compile whole regions of a page with guest
     * registers in JVM locals and inline memory access. Off: hot code is compiled by the first
     * tier only ({@link BlockCompiler}: straight-line blocks calling the instruction handlers).
     */
    public volatile boolean compileRegions = true;
    /**
     * Executions of a block's entry before it is compiled (tests use 1). Compiled code costs JVM
     * compile time and instruction-cache footprint; kernels and userland run a flat profile of
     * thousands of warm blocks, where compiling less is faster (measured on both guests).
     */
    int blockThreshold = 20_000;
    /**
     * Executions of a block's entry before it is compiled as a second-tier region. At or below
     * {@link #blockThreshold}, hot entries become regions directly; above it, they first run as
     * first-tier blocks (cheap to compile) and are promoted when they reach this count.
     */
    int regionThreshold = 200_000;
    /** Largest region, in instructions (smaller regions compile faster; see {@link RegionCompiler#MAX_INSNS}). */
    int regionMaxInsns = 64;
    /**
     * Share compiled code with the other CPUs of the {@link CpuDomain} (see
     * {@link CpuDomain#compiledCode}). Off: this CPU compiles everything it runs itself.
     */
    public volatile boolean shareCode = true;
    /** Statistics, for tests and tuning. */
    long blocksCompiled;
    long blocksRun;
    long regionsCompiled;
    long regionInsns;
    long revalidations;
    /** Compiled code found in the domain's cache instead of being compiled. */
    long sharedHits;
    /** Compiles of code (real address, mode, tier) this CPU had compiled before. */
    long dupCompiles;
    private final java.util.Set<Long> compiledAt = new java.util.HashSet<>();

    private boolean jitOn;
    /** Block key bits for the current chunk: the MSR bits compiled code depends on (RegionCompiler.mode). */
    private int blockMode;
    private final Block[] blocks = new Block[1 << BLOCK_TABLE_BITS];
    /** Execution counts of block entries without a table entry, by table index (collisions add up). */
    private final int[] heat = new int[1 << BLOCK_TABLE_BITS];

    /**
     * Compiled code for one entry point (real address, mode, tier) and the code words it was
     * built from. Immutable but for {@link #gen}; shared by the CPUs of a domain (see
     * {@link CpuDomain#compiledCode}). The code does not depend on the effective address it
     * runs at.
     */
    static final class Code {
        final CompiledBlock run;
        final boolean region;
        final long pa;
        /** First tier: the number of instructions; region: the length of its entry block. */
        final int n;
        /** First tier: the real address just past the block (the store guard's end). */
        final long paEnd;
        /** The words the code covers, as raw memory (little-endian ints) from rawPa. */
        final long rawPa;
        final int[] rawWords;
        /** Code generation of the page when the words were last known to be current. */
        volatile int gen;
        /**
         * {@link PhysicalMemory#codeWriteCount} when the code was last found current: while it
         * still has this value, no page's generation has changed and the check can be skipped.
         */
        volatile int checkedAt = -1;

        Code(CompiledBlock run, boolean region, long pa, int n, long paEnd, long rawPa, int[] rawWords, int gen) {
            this.run = run;
            this.region = region;
            this.pa = pa;
            this.n = n;
            this.paEnd = paEnd;
            this.rawPa = rawPa;
            this.rawWords = rawWords;
            this.gen = gen;
        }
    }

    /** A hot block entry of this CPU (effective and real address, mode) and its compiled code. */
    private static final class Block {
        final long ea;
        final long pa;
        final int mode;
        int count;
        Code code;
        boolean uncompilable;
        /** Region compilation failed or is not wanted for this entry. */
        boolean noRegion;
        /** Runs of the first-tier code, counting towards promotion to a region. */
        int hot;

        Block(long ea, long pa, int mode) {
            this.ea = ea;
            this.pa = pa;
            this.mode = mode;
        }
    }

    /** Key of compiled code in the domain's cache: real address, mode, tier. */
    private static long codeKey(long pa, int mode, boolean region) {
        return (pa << 5) | ((long) mode << 1) | (region ? 1 : 0);
    }

    /**
     * At an instruction that may start a block (effective address {@code pc}, real address
     * {@code pa}, page already translated by the run loop): count it, compile it once hot, and run
     * its compiled code if that is exactly equivalent to interpreting from here, i.e. the code
     * words in memory are still the ones it was built from and the code's first block fits before
     * the end of the chunk. Returns {@link #NO_BLOCK} or the code's {@link CompiledBlock#run} result.
     */
    private int runBlock(long pc, long pa) {
        int h = ((int) (pa >>> 2) ^ (int) (pc >>> 14)) & ((1 << BLOCK_TABLE_BITS) - 1);
        Block b = blocks[h];
        if (b == null || b.pa != pa || b.ea != pc || b.mode != blockMode) {
            int ht = ++heat[h];
            if (ht < blockThreshold) {
                // Now and then, look for code another CPU (or this one, at another address) compiled.
                if ((ht & 63) != 0 || !shareCode || (b = adoptShared(pc, pa, h)) == null) {
                    return NO_BLOCK;
                }
            } else {
                heat[h] = 0;
                b = new Block(pc, pa, blockMode);
                blocks[h] = b;
                b.count = blockThreshold - 1;
            }
        }
        Code c = b.code;
        if (c != null) {
            if (s.icount + c.n > chunkEnd) {
                return NO_BLOCK;
            }
            if (!isCurrent(c)) {
                // The guest changed the code: rebuild it from the current words.
                domain.dropCode(codeKey(pa, b.mode, c.region), c);
                b.code = null;
                c = null;
            } else if (!c.region && !b.noRegion && ++b.hot >= regionThreshold - blockThreshold && compileRegions) {
                // Still hot after running as a first-tier block: promote it to a region.
                b.hot = 0;
                Code r = regionFor(b);
                if (r != null) {
                    b.code = c = r;
                    if (s.icount + c.n > chunkEnd) {
                        return NO_BLOCK;
                    }
                } else {
                    b.noRegion = true;
                }
            }
        }
        if (c == null) {
            if (b.uncompilable || ++b.count < blockThreshold || (c = compileBlock(b)) == null) {
                return NO_BLOCK;
            }
            if (!isCurrent(c)) {
                return NO_BLOCK; // changed while being compiled (another thread): try again later
            }
            b.code = c;
            if (s.icount + c.n > chunkEnd) {
                return NO_BLOCK;
            }
        }
        // A RuntimeException escaping run() leaves the guard set; that can only make later
        // blocks stop early, which is always safe.
        RadixMmu m = mmu;
        if (c.region) {
            m.guardLo = pa & ~0xfffL; // a region stops after any store into its page
            m.guardHi = (pa & ~0xfffL) + 0x1000;
        } else {
            m.guardLo = pa;
            m.guardHi = c.paEnd;
        }
        blocksRun++;
        int r = c.run.run(s, this);
        m.guardLo = 0;
        m.guardHi = 0;
        m.guardHit = false;
        return r;
    }

    /** Install a table entry for code the domain already holds for this entry, if any is current. */
    private Block adoptShared(long pc, long pa, int h) {
        Code c = null;
        if (compileRegions) {
            c = currentShared(codeKey(pa, blockMode, true));
        }
        if (c == null) {
            c = currentShared(codeKey(pa, blockMode, false));
        }
        if (c == null) {
            return null;
        }
        sharedHits++;
        heat[h] = 0;
        Block b = new Block(pc, pa, blockMode);
        b.code = c;
        b.count = blockThreshold;
        blocks[h] = b;
        return b;
    }

    /** The domain's code for {@code key} if its words are still current, else null (and dropped). */
    private Code currentShared(long key) {
        if (!shareCode) {
            return null;
        }
        Code c = domain.findCode(key);
        if (c == null) {
            return null;
        }
        if (isCurrent(c)) {
            return c;
        }
        domain.dropCode(key, c);
        return null;
    }

    /**
     * Whether {@code c}'s code words are still those in memory: nothing translated was written
     * since the last check, or its page's generation is unchanged, or its words compare equal.
     */
    private boolean isCurrent(Code c) {
        int w = pm.codeWriteCount();
        if (c.checkedAt == w) {
            return true;
        }
        if (pm.codeGeneration(c.pa) == c.gen || revalidate(c)) {
            c.checkedAt = w;
            return true;
        }
        return false;
    }

    /**
     * The page of {@code c} was written since its words were last checked: watch the page again
     * and compare the words. Returns whether the compiled code is still current.
     */
    private boolean revalidate(Code c) {
        revalidations++;
        int g = pm.watchCode(c.pa);
        if (codeMatches(c)) {
            c.gen = g; // published only once the words are known to match
            return true;
        }
        return false;
    }

    /** Whether memory still holds the words {@code c} was compiled from (compared as raw memory). */
    private boolean codeMatches(Code c) {
        int[] w = c.rawWords;
        long a = c.rawPa;
        for (int j = 0; j < w.length; j++) {
            if (pm.read32le(a + 4L * j) != w[j]) {
                return false;
            }
        }
        return true;
    }

    /** Instruction word from a raw (little-endian) memory word, in this chunk's fetch order. */
    private int fetchOrder(int raw) {
        return fetchLe ? raw : Integer.reverseBytes(raw);
    }

    /** Code for a hot entry: a region directly, or a first-tier block (found in the domain or compiled). */
    private Code compileBlock(Block b) {
        if (compileRegions && !b.noRegion && regionThreshold <= blockThreshold) {
            Code r = regionFor(b);
            if (r != null) {
                return r;
            }
            b.noRegion = true;
        }
        long key = codeKey(b.pa, b.mode, false);
        Code c = currentShared(key);
        if (c != null) {
            sharedHits++;
            return c;
        }
        c = compileFirstTier(b);
        if (c == null) {
            b.uncompilable = true;
            return null;
        }
        return publish(key, c);
    }

    /** The region for {@code b}'s entry, from the domain or compiled; null if none can be built. */
    private Code regionFor(Block b) {
        long key = codeKey(b.pa, b.mode, true);
        Code c = currentShared(key);
        if (c != null) {
            sharedHits++;
            return c;
        }
        c = compileRegion(b);
        return c == null ? null : publish(key, c);
    }

    private Code publish(long key, Code c) {
        if (compiledAt.size() > 1 << 18) {
            compiledAt.clear(); // statistics only: keep it bounded
        }
        if (!compiledAt.add(key)) {
            dupCompiles++;
        }
        if (!shareCode) {
            return c;
        }
        Code got = domain.publishCode(key, c);
        if (got != c && !isCurrent(got)) {
            domain.replaceCode(key, got, c); // another CPU's, but stale
            return c;
        }
        return got;
    }

    /** Decode the block at {@code b.pa} (up to a terminator, the page end or the size limit) and compile it. */
    private Code compileFirstTier(Block b) {
        long pa = b.pa;
        // Watch the page before reading the code: any later write to it advances the generation.
        int gen = pm.watchCode(pa);
        long room = Math.min(0x1000 - (pa & 0xfff), pm.size() - pa) / 4;
        int max = (int) Math.min(BlockCompiler.MAX_LENGTH, room);
        int[] raw = new int[max];
        int[] words = new int[max];
        int[] slots = new int[max];
        int n = 0;
        while (n < max) {
            raw[n] = pm.read32le(pa + 4L * n);
            int insn = fetchOrder(raw[n]);
            int slot = (int) lookupEntry(insn);
            words[n] = insn;
            slots[n] = slot;
            n++;
            if ((BlockCompiler.flags(slot, ops[slot]) & BlockCompiler.CONT) == 0) {
                break;
            }
        }
        if (n < 2) {
            return null;
        }
        long mask = (b.mode & RegionCompiler.MODE_SF) != 0 ? -1L : 0xffffffffL;
        CompiledBlock run = BlockCompiler.compile(b.ea, mask, words, slots, n, ops);
        blocksCompiled++;
        return new Code(run, false, pa, n, pa + 4L * n, pa, java.util.Arrays.copyOf(raw, n), gen);
    }

    /**
     * Compile the second-tier region entered at {@code b}: snapshot its page (after watching it,
     * so any later write advances the page's code generation), compile from the snapshot, keep
     * the part of the snapshot the region covers for revalidation.
     */
    private Code compileRegion(Block b) {
        long page = b.pa & ~0xfffL;
        if (page + 0x1000 > pm.size()) {
            return null;
        }
        int gen = pm.watchCode(page);
        int[] raw = new int[1024];
        for (int j = 0; j < 1024; j++) {
            raw[j] = pm.read32le(page + 4L * j);
        }
        RegionCompiler.Source src = new RegionCompiler.Source() {
            @Override
            public int slot(int insn) {
                return (int) lookupEntry(insn);
            }

            @Override
            public Op op(int slot) {
                return ops[slot];
            }

            @Override
            public String name(int insn) {
                DecodeTable.Entry e = table.lookup(insn);
                return e == null ? null : e.name();
            }

            @Override
            public int word(int off) {
                return fetchOrder(raw[off >>> 2]);
            }
        };
        RegionCompiler.Result r = RegionCompiler.compile(b.ea, b.pa, b.mode, src, pm, regionMaxInsns);
        if (r == null) {
            return null;
        }
        regionsCompiled++;
        regionInsns += r.insns();
        return new Code(r.code(), true, b.pa, r.entryLen(), 0, page + r.spanLo(),
                java.util.Arrays.copyOfRange(raw, r.spanLo() >>> 2, r.spanHi() >>> 2), gen);
    }

    /** A compiled block's instruction raised an interrupt: what the run loop does in that case. */
    void blockFault(PpcException e) {
        s.icount++;
        deliverSync(e);
    }

    /**
     * Translate the page of instruction address {@code pc} for fetching. On a fault, delivers the
     * interrupt (the instruction counts as executed) and returns false.
     */
    private boolean newFetchPage(long pc) {
        try {
            fetchBase = mmu.translateFetch(pc) & ~0xfffL;
            fetchPage = pc & ~0xfffL;
            return true;
        } catch (PpcException e) {
            s.icount++;
            deliverSync(e);
            return false;
        }
    }

    /** Decode-cache entry for {@code insn}, from the second-level cache or the decode table. */
    private long lookupEntry(int insn) {
        l1Misses++;
        // Two-way set: way 0 holds the most recently filled or hit entry.
        int h = ((insn * 0x9E3779B1) >>> (32 - DCACHE_BITS)) & ~1;
        long[] dc = dcache;
        long e0 = dc[h];
        if ((int) (e0 >>> 32) == insn) {
            return e0;
        }
        long e1 = dc[h + 1];
        if ((int) (e1 >>> 32) != insn) {
            e1 = decodeEntry(insn);
        }
        dc[h] = e1;
        dc[h + 1] = e0;
        return e1;
    }

    private long decodeEntry(int insn) {
        decodes++;
        return ((long) insn << 32) | specialize(slot(decode(insn)), insn);
    }

    /**
     * Narrow an inline handler to a cheaper equivalent where a field of the instruction word
     * decides the outcome: SPR moves of LR and CTR, which every privilege state may access
     * (they take the plain-register paths of {@link SprAccess}).
     */
    private static int specialize(int k, int insn) {
        if (k == K_MFSPR || k == K_MTSPR) {
            int spr = Fields.spr(insn);
            if (spr == Spr.LR) {
                return k == K_MFSPR ? K_MFLR : K_MTLR;
            }
            if (spr == Spr.CTR) {
                return k == K_MFSPR ? K_MFCTR : K_MTCTR;
            }
        }
        return k;
    }

    /** The dispatch slot of {@code op}: its inline kind if it has one, else a slot in ops[]. */
    private int slot(Op op) {
        if (op instanceof Reg.Kind kind) {
            ops[kind.kind()] = op;
            return kind.kind();
        }
        Integer known = slotOf.get(op);
        if (known != null) {
            return known;
        }
        int k = nextSlot++;
        if (k == ops.length) {
            ops = java.util.Arrays.copyOf(ops, k * 2);
        }
        ops[k] = op;
        slotOf.put(op, k);
        return k;
    }

    private Op decode(int insn) {
        DecodeTable.Entry e = table.lookup(insn);
        return e == null ? UNIMPLEMENTED_OP : e.op();
    }

    // ================================================================== inline dispatch

    // Dispatch numbers of the handlers the run loop executes inline, roughly hottest first: the JIT
    // inlines switch arms in order until its budget runs out. Generated from the registrations;
    // INLINE_NAMES[K_X] is the name the handler is registered under.
    // BEGIN GENERATED CONSTANTS
    private static final int K_LD = 1;
    private static final int K_STD = 2;
    private static final int K_ADDI = 3;
    private static final int K_BC = 4;
    private static final int K_OR = 5;
    private static final int K_ADD = 6;
    private static final int K_CMPI = 7;
    private static final int K_ORI = 8;
    private static final int K_RLWINM = 9;
    private static final int K_B = 10;
    private static final int K_LWZ = 11;
    private static final int K_BCCTR = 12;
    private static final int K_XOR = 13;
    private static final int K_RLDICL = 14;
    private static final int K_BCLR = 15;
    private static final int K_ADDIS = 16;
    private static final int K_MTSPR = 17;
    private static final int K_MFSPR = 18;
    private static final int K_STW = 19;
    private static final int K_ANDI_DOT = 20;
    private static final int K_LBZ = 21;
    private static final int K_STDU = 22;
    private static final int K_AND = 23;
    private static final int K_CMPL = 24;
    private static final int K_CMPLI = 25;
    private static final int K_SUBF = 26;
    private static final int K_RLDICR = 27;
    private static final int K_CMP = 28;
    private static final int K_EXTSW = 29;
    private static final int K_LDX = 30;
    private static final int K_MULLD = 31;
    private static final int K_STB = 32;
    private static final int K_STBU = 33;
    private static final int K_MTCRF = 34;
    private static final int K_SYNC = 35;
    private static final int K_SRD = 36;
    private static final int K_LHZ = 37;
    private static final int K_NEG = 38;
    private static final int K_STDX = 39;
    private static final int K_RLDIC = 40;
    private static final int K_SLD = 41;
    private static final int K_ANDC = 42;
    private static final int K_SUBFE = 43;
    private static final int K_SUBFC = 44;
    private static final int K_RLDIMI = 45;
    private static final int K_LWZX = 46;
    private static final int K_LBZX = 47;
    private static final int K_STWU = 48;
    private static final int K_STH = 49;
    private static final int K_MFCR = 50;
    private static final int K_ISEL = 51;
    private static final int K_NOR = 52;
    private static final int K_EXTSB = 53;
    private static final int K_EXTSH = 54;
    private static final int K_LWA = 55;
    private static final int K_LHA = 56;
    private static final int K_LHZX = 57;
    private static final int K_CROR = 58;
    private static final int K_CRXOR = 59;
    private static final int K_STWX = 60;
    private static final int K_STBX = 61;
    private static final int K_LDU = 62;
    private static final int K_LWZU = 63;
    private static final int K_LBZU = 64;
    private static final int K_SRADI = 65;
    private static final int K_SRAD = 66;
    private static final int K_SRAWI = 67;
    private static final int K_SRAW = 68;
    private static final int K_SLW = 69;
    private static final int K_SRW = 70;
    private static final int K_CNTLZD = 71;
    private static final int K_CNTLZW = 72;
    private static final int K_MULLW = 73;
    private static final int K_MULLI = 74;
    private static final int K_DIVD = 75;
    private static final int K_DIVDU = 76;
    private static final int K_DIVW = 77;
    private static final int K_DIVWU = 78;
    private static final int K_XORI = 79;
    private static final int K_ORIS = 80;
    private static final int K_XORIS = 81;
    private static final int K_ANDIS_DOT = 82;
    private static final int K_SUBFIC = 83;
    private static final int K_ADDIC = 84;
    private static final int K_ADDZE = 85;
    private static final int K_ADDE = 86;
    private static final int K_ADDC = 87;
    private static final int K_ADDME = 88;
    private static final int K_SUBFME = 89;
    private static final int K_CRAND = 90;
    private static final int K_CRANDC = 91;
    private static final int K_CRNOR = 92;
    private static final int K_CRNAND = 93;
    private static final int K_CREQV = 94;
    private static final int K_CRORC = 95;
    private static final int K_MCRF = 96;
    private static final int K_MFMSR = 97;
    private static final int K_MTMSRD = 98;
    private static final int K_ISYNC = 99;
    private static final int K_DCBT = 100;
    private static final int K_DCBTST = 101;
    private static final int K_EIEIO = 102;
    private static final int K_LDARX = 103;
    private static final int K_STDCX_DOT = 104;
    private static final int K_LWARX = 105;
    private static final int K_STWCX_DOT = 106;
    private static final int K_LWBRX = 107;
    private static final int K_STWBRX = 108;
    private static final int K_LDBRX = 109;
    private static final int K_STDBRX = 110;
    private static final int K_LHBRX = 111;
    private static final int K_STHBRX = 112;
    private static final int K_LDUX = 113;
    private static final int K_LWZUX = 114;
    private static final int K_LBZUX = 115;
    private static final int K_STDUX = 116;
    private static final int K_STWUX = 117;
    private static final int K_STBUX = 118;
    private static final int K_LHZU = 119;
    private static final int K_LHAU = 120;
    private static final int K_STHU = 121;
    private static final int K_STHX = 122;
    private static final int K_LHAX = 123;
    private static final int K_LWAX = 124;
    private static final int K_LWAUX = 125;
    private static final int K_POPCNTD = 126;
    private static final int K_CNTTZD = 127;
    private static final int K_CNTTZW = 128;
    private static final int K_MULHD = 129;
    private static final int K_MULHDU = 130;
    private static final int K_MULHW = 131;
    private static final int K_MULHWU = 132;
    private static final int K_EQV = 133;
    private static final int K_ORC = 134;
    private static final int K_NAND = 135;
    private static final int K_EXTSWSLI = 136;
    private static final int K_RLDCL = 137;
    private static final int K_RLWNM = 138;
    private static final int K_DCBZ = 139;
    private static final int K_DCBF = 140;
    private static final int K_DCBST = 141;
    private static final int K_ICBI = 142;
    private static final int K_SC = 143;
    private static final int K_RFID = 144;
    private static final int K_TW = 145;
    private static final int K_TD = 146;
    private static final int K_TWI = 147;
    private static final int K_TDI = 148;
    private static final int K_MFTB = 149;
    private static final int K_SETB = 150;
    private static final int K_CMPB = 151;
    private static final int K_MODUD = 152;
    private static final int K_MODUW = 153;
    private static final int K_MODSD = 154;
    private static final int K_MODSW = 155;
    private static final int K_DSS = 156;
    static final int NUM_INLINE = 156;
    // END GENERATED CONSTANTS
    private static final String[] INLINE_NAMES = {
        // BEGIN GENERATED NAMES
        null, "ld", "std", "addi", "bc", "or", "add", "cmpi", "ori", "rlwinm", "b", "lwz", "bcctr",
        "xor", "rldicl", "bclr", "addis", "mtspr", "mfspr", "stw", "andi.", "lbz", "stdu", "and",
        "cmpl", "cmpli", "subf", "rldicr", "cmp", "extsw", "ldx", "mulld", "stb", "stbu", "mtcrf",
        "sync", "srd", "lhz", "neg", "stdx", "rldic", "sld", "andc", "subfe", "subfc", "rldimi",
        "lwzx", "lbzx", "stwu", "sth", "mfcr", "isel", "nor", "extsb", "extsh", "lwa", "lha", "lhzx",
        "cror", "crxor", "stwx", "stbx", "ldu", "lwzu", "lbzu", "sradi", "srad", "srawi", "sraw",
        "slw", "srw", "cntlzd", "cntlzw", "mullw", "mulli", "divd", "divdu", "divw", "divwu", "xori",
        "oris", "xoris", "andis.", "subfic", "addic", "addze", "adde", "addc", "addme", "subfme",
        "crand", "crandc", "crnor", "crnand", "creqv", "crorc", "mcrf", "mfmsr", "mtmsrd", "isync",
        "dcbt", "dcbtst", "eieio", "ldarx", "stdcx.", "lwarx", "stwcx.", "lwbrx", "stwbrx", "ldbrx",
        "stdbrx", "lhbrx", "sthbrx", "ldux", "lwzux", "lbzux", "stdux", "stwux", "stbux", "lhzu",
        "lhau", "sthu", "sthx", "lhax", "lwax", "lwaux", "popcntd", "cnttzd", "cnttzw", "mulhd",
        "mulhdu", "mulhw", "mulhwu", "eqv", "orc", "nand", "extswsli", "rldcl", "rlwnm", "dcbz",
        "dcbf", "dcbst", "icbi", "sc", "rfid", "tw", "td", "twi", "tdi", "mftb", "setb", "cmpb",
        "modud", "moduw", "modsd", "modsw", "dss"
        // END GENERATED NAMES
    };
    /** The static method ("Class.method") behind each inline handler, for the block compiler. */
    static final String[] INLINE_HANDLERS = {
        // BEGIN GENERATED HANDLERS
        null, "LoadStore.ld", "LoadStore.std", "Alu.addi", "BranchInsns.bc", "Alu.or", "Alu.add",
        "Alu.cmpi", "Alu.ori", "Alu.rlwinm", "BranchInsns.b", "LoadStore.lwz", "BranchInsns.bcctr",
        "Alu.xor", "Alu.rldicl", "BranchInsns.bclr", "Alu.addis", "SprAccess.mtspr",
        "SprAccess.mfspr", "LoadStore.stw", "Alu.andi", "LoadStore.lbz", "LoadStore.stdu", "Alu.and",
        "Alu.cmpl", "Alu.cmpli", "Alu.subf", "Alu.rldicr", "Alu.cmp", "Alu.extsw", "LoadStore.ldx",
        "Alu.mulld", "LoadStore.stb", "LoadStore.stbu", "BranchInsns.mtcrf", "SystemInsns.sync",
        "Alu.srd", "LoadStore.lhz", "Alu.neg", "LoadStore.stdx", "Alu.rldic", "Alu.sld", "Alu.andc",
        "Alu.subfe", "Alu.subfc", "Alu.rldimi", "LoadStore.lwzx", "LoadStore.lbzx", "LoadStore.stwu",
        "LoadStore.sth", "BranchInsns.mfcr", "Alu.isel", "Alu.nor", "Alu.extsb", "Alu.extsh",
        "LoadStore.lwa", "LoadStore.lha", "LoadStore.lhzx", "BranchInsns.cror", "BranchInsns.crxor",
        "LoadStore.stwx", "LoadStore.stbx", "LoadStore.ldu", "LoadStore.lwzu", "LoadStore.lbzu",
        "Alu.sradi", "Alu.srad", "Alu.srawi", "Alu.sraw", "Alu.slw", "Alu.srw", "Alu.cntlzd",
        "Alu.cntlzw", "Alu.mullw", "Alu.mulli", "Alu.divd", "Alu.divdu", "Alu.divw", "Alu.divwu",
        "Alu.xori", "Alu.oris", "Alu.xoris", "Alu.andis", "Alu.subfic", "Alu.addic", "Alu.addze",
        "Alu.adde", "Alu.addc", "Alu.addme", "Alu.subfme", "BranchInsns.crand", "BranchInsns.crandc",
        "BranchInsns.crnor", "BranchInsns.crnand", "BranchInsns.creqv", "BranchInsns.crorc",
        "BranchInsns.mcrf", "SystemInsns.mfmsr", "SystemInsns.mtmsrd", "SystemInsns.isync",
        "SystemInsns.nop", "SystemInsns.nop", "SystemInsns.eieio", "LoadStore.ldarx",
        "LoadStore.stdcx", "LoadStore.lwarx", "LoadStore.stwcx", "LoadStore.lwbrx",
        "LoadStore.stwbrx", "LoadStore.ldbrx", "LoadStore.stdbrx", "LoadStore.lhbrx",
        "LoadStore.sthbrx", "LoadStore.ldux", "LoadStore.lwzux", "LoadStore.lbzux",
        "LoadStore.stdux", "LoadStore.stwux", "LoadStore.stbux", "LoadStore.lhzu", "LoadStore.lhau",
        "LoadStore.sthu", "LoadStore.sthx", "LoadStore.lhax", "LoadStore.lwax", "LoadStore.lwaux",
        "Alu.popcntd", "Alu.cnttzd", "Alu.cnttzw", "Alu.mulhd", "Alu.mulhdu", "Alu.mulhw",
        "Alu.mulhwu", "Alu.eqv", "Alu.orc", "Alu.nand", "Alu.extswsli", "Alu.rldcl", "Alu.rlwnm",
        "SystemInsns.dcbz", "SystemInsns.dcbfst", "SystemInsns.dcbfst", "SystemInsns.icbi",
        "SystemInsns.sc", "SystemInsns.rfid", "Alu.tw", "Alu.td", "Alu.twi", "Alu.tdi",
        "SprAccess.mfspr", "Alu.setb", "Alu.cmpb", "Alu.modud", "Alu.moduw", "Alu.modsd",
        "Alu.modsw", "SystemInsns.nop"
        // END GENERATED HANDLERS
    };
    // Specialisations picked at decode time (see specialize); not registered handlers.
    static final int K_MFLR = NUM_INLINE + 1;
    static final int K_MFCTR = NUM_INLINE + 2;
    static final int K_MTLR = NUM_INLINE + 3;
    static final int K_MTCTR = NUM_INLINE + 4;
    /** First dispatch slot that is not an inline handler. */
    static final int FIRST_DYNAMIC = K_MTCTR + 1;

    // Handlers of the specialisations: the plain-register paths of SprAccess for LR and CTR.
    static void mflr(CpuState s, int i) {
        s.gpr[(i >>> 21) & 31] = s.lr;
    }

    static void mfctr(CpuState s, int i) {
        s.gpr[(i >>> 21) & 31] = s.ctr;
    }

    static void mtlr(CpuState s, int i) {
        s.lr = s.gpr[(i >>> 21) & 31];
    }

    static void mtctr(CpuState s, int i) {
        s.ctr = s.gpr[(i >>> 21) & 31];
    }

    /** Name of the static handler of specialisation {@code k}, for the block compiler. */
    static String specialHandler(int k) {
        return switch (k) {
            case K_MFLR -> "mflr";
            case K_MFCTR -> "mfctr";
            case K_MTLR -> "mtlr";
            case K_MTCTR -> "mtctr";
            default -> throw new IllegalArgumentException("not a specialisation: " + k);
        };
    }
    private static final Map<String, Integer> INLINE_KIND = new java.util.HashMap<>();

    static {
        if (INLINE_NAMES.length != NUM_INLINE + 1) {
            throw new AssertionError("inline dispatch tables out of step");
        }
        for (int k = 1; k < INLINE_NAMES.length; k++) {
            INLINE_KIND.put(INLINE_NAMES[k], k);
        }
    }

    /** Inline dispatch number of the handler registered as {@code name}, or 0 if it has none. */
    static int kindOf(String name) {
        Integer k = INLINE_KIND.get(name);
        return k == null ? 0 : k;
    }

    /** Names of the handlers executed inline, by dispatch number (index 0 unused). For tests. */
    static List<String> inlineNames() {
        return List.of(java.util.Arrays.copyOfRange(INLINE_NAMES, 1, INLINE_NAMES.length));
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
        leaveGuest();
        // Nothing runs while idle, so the DEC_MIN_INSNS slack no longer applies.
        decEarliest = Math.min(decEarliest, s.icount);
        if (decZeroIcount != Long.MAX_VALUE) {
            decDueIcount = Math.max(decZeroIcount, s.icount);
        }
        while (true) {
            if (s.stopRequested) {
                return false;
            }
            if (s.halted || s.workPending) {
                return true; // the run loop parks or runs the work, then idles again if still ceded
            }
            if (s.prodded || wakeupPending()) {
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
                long wait = decWakes ? Math.min(domain.hostNs(nsUntilDec()), MAX_PARK_NS) : MAX_PARK_NS;
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
        // Inside the hypervisor this CPU uses no translations: CPUs completing a tlbie need not
        // wait for it (the run loop checks for flushes before the next instruction).
        outside = true;
        try {
            s.hv.hcall(s);
        } finally {
            outside = !executing;
        }
        recheck = true;
    }
}
