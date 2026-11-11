package org.boxtheta.javapower.cpu;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.boxtheta.javapower.core.PhysicalMemory;

/**
 * The CPUs of one machine and what they share besides guest memory: the timebase, TLB
 * invalidation broadcast ({@code tlbie}) and the lock behind the atomic operations wider than the
 * host can do in one step. Each {@link Interpreter} belongs to exactly one domain; an interpreter
 * built without one gets a private domain (a single-CPU machine).
 *
 * <h2>Time</h2>
 * All CPUs of a domain read one timebase. With {@link Interpreter.Clock#WALL} it follows the
 * host's monotonic clock from the moment the domain was created, divided by {@link #timeScale}
 * (1 = real time, 4 = the guest sees one second for every four host seconds), so guest timeouts
 * that assume real hardware speed do not fire spuriously. {@link Interpreter.Clock#DETERMINISTIC}
 * derives time from the instruction count of the CPU that reads it; CPUs running in parallel
 * cannot share such a clock, so a deterministic domain holds one CPU only.
 *
 * <h2>TLB invalidation</h2>
 * {@code tlbie} on one CPU marks a global flush pending; the next {@code ptesync} or
 * {@code tlbsync} on that CPU completes it: it advances {@link #tlbGeneration} and waits until
 * every other CPU of the domain has flushed its software TLB, or is outside guest code (idle,
 * parked, inside a hypercall, not running), in which case it flushes before executing its next
 * instruction. CPUs look for new generations between instruction chunks, so a waiter is held up
 * at most one chunk of each other CPU.
 *
 * <p>Thread safety: all methods may be called from any thread. Interpreters register themselves
 * on construction; build them all before any of them runs.
 */
public final class CpuDomain {
    private final PhysicalMemory memory;
    private final Interpreter.Clock clock;
    private final double timeScale;
    private final double invScale;
    /**
     * Host time at which the wall clock read zero. Written only by {@link #loadState}, while no
     * CPU of the domain runs (the threads that run them later are started after it).
     */
    private long wallBase = System.nanoTime();
    private final long seed;

    /** Generation of global TLB flushes; see the class comment. */
    final AtomicLong tlbGeneration = new AtomicLong();
    private volatile Interpreter[] cpus = new Interpreter[0];
    /** Serialises the quadword and two-location atomic operations (see LoadStore). */
    final Object wideAtomicLock = new Object();

    /**
     * Compiled code shared by the CPUs: entries are keyed by real address, mode and tier (see
     * {@link Interpreter.Code}) and validated against the page's code generation by every user,
     * so code compiled by one CPU, or for one process, serves all. Bounded: when it reaches
     * {@link #MAX_SHARED_CODE} entries it is emptied (CPUs keep the code they hold).
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, Interpreter.Code> compiledCode =
            new java.util.concurrent.ConcurrentHashMap<>();
    static final int MAX_SHARED_CODE = 1 << 17;

    final LongAdder shootdowns = new LongAdder();
    final LongAdder shootdownWaitNs = new LongAdder();

    /** A wall-clock domain at real time. */
    public CpuDomain(PhysicalMemory memory) {
        this(memory, Interpreter.Clock.WALL, 1.0);
    }

    public CpuDomain(PhysicalMemory memory, Interpreter.Clock clock) {
        this(memory, clock, 1.0);
    }

    /**
     * @param timeScale WALL only: host nanoseconds per guest nanosecond, at least 1/1024 (values
     *     above 1 slow guest time down). Must be 1 for {@link Interpreter.Clock#DETERMINISTIC}.
     */
    public CpuDomain(PhysicalMemory memory, Interpreter.Clock clock, double timeScale) {
        this(memory, clock, timeScale, System.nanoTime() ^ 0x5DEECE66DL);
    }

    /**
     * @param seed seed of the CPUs' random number generators ({@code darn}); fix it for
     *     reproducible runs
     */
    public CpuDomain(PhysicalMemory memory, Interpreter.Clock clock, double timeScale, long seed) {
        if (!(timeScale >= 1.0 / 1024) || Double.isInfinite(timeScale)) {
            throw new IllegalArgumentException("time scale must be a finite number >= 1/1024: " + timeScale);
        }
        if (clock == Interpreter.Clock.DETERMINISTIC && timeScale != 1.0) {
            throw new IllegalArgumentException("the time scale applies to the wall clock only");
        }
        this.memory = memory;
        this.clock = clock;
        this.timeScale = timeScale;
        this.invScale = 1.0 / timeScale;
        this.seed = seed;
    }

    public PhysicalMemory memory() {
        return memory;
    }

    public Interpreter.Clock clock() {
        return clock;
    }

    /** Host time per unit of guest time (WALL clock); 1 is real time. */
    public double timeScale() {
        return timeScale;
    }

    /** The interpreters of this domain, in registration order. */
    public List<Interpreter> cpus() {
        return List.of(cpus);
    }

    /** Global TLB flushes completed so far (statistics). */
    public long tlbShootdowns() {
        return shootdowns.sum();
    }

    /** Total time CPUs spent waiting for other CPUs to flush (statistics), in nanoseconds. */
    public long tlbShootdownWaitNanos() {
        return shootdownWaitNs.sum();
    }

    synchronized int register(Interpreter in) {
        Interpreter[] cur = cpus;
        if (clock == Interpreter.Clock.DETERMINISTIC && cur.length > 0) {
            throw new IllegalStateException(
                    "the deterministic clock is per CPU: a deterministic domain holds one CPU");
        }
        Interpreter[] n = java.util.Arrays.copyOf(cur, cur.length + 1);
        n[cur.length] = in;
        cpus = n;
        return cur.length;
    }

    long seedFor(int ordinal) {
        return seed + 0x9E3779B97F4A7C15L * (ordinal + 1);
    }

    /** Guest nanoseconds of the wall clock. */
    long wallNs() {
        long host = System.nanoTime() - wallBase;
        return timeScale == 1.0 ? host : (long) (host * invScale);
    }

    /** Host nanoseconds that correspond to {@code guestNs} of wall-clock guest time (for parking). */
    long hostNs(long guestNs) {
        if (timeScale == 1.0 || guestNs == Long.MAX_VALUE) {
            return guestNs;
        }
        double h = guestNs * timeScale;
        return h >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) h;
    }

    /**
     * Complete a global TLB flush issued by {@code self}: publish a new generation, flush
     * {@code self}, and wait until every other CPU has flushed or is outside guest code. While
     * waiting, {@code self} keeps acknowledging flushes from other CPUs so two CPUs flushing at
     * once cannot wait for each other.
     */
    void shootdown(Interpreter self) {
        long g = tlbGeneration.incrementAndGet();
        self.applyTlbGeneration();
        Interpreter[] all = cpus;
        if (all.length == 1) {
            shootdowns.increment();
            return;
        }
        long t0 = 0;
        for (Interpreter other : all) {
            if (other == self) {
                continue;
            }
            int spins = 0;
            while (other.tlbAck < g && !other.outside) {
                if (t0 == 0) {
                    t0 = System.nanoTime();
                }
                self.applyTlbGeneration();
                if (++spins < 200) {
                    Thread.onSpinWait();
                } else {
                    Thread.yield();
                }
            }
        }
        shootdowns.increment();
        if (t0 != 0) {
            shootdownWaitNs.add(System.nanoTime() - t0);
        }
    }

    // ---- saved state ----------------------------------------------------------------------------

    /**
     * Write the state of every CPU of the domain as one {@code "CPUS"} record: the domain's guest
     * time, then each CPU's state in registration order (see {@link Interpreter}'s
     * {@code saveState}: registers, SPRs, timebase, decrementer, reservation, idle and stopped
     * state). Work posted to a CPU is run first. Compiled code, decode caches and TLBs are not
     * saved. No CPU of the domain may be running.
     */
    public void saveState(org.boxtheta.javapower.core.StateOutput out) throws java.io.IOException {
        Interpreter[] all = cpus;
        long now = wallNs();
        out.record("CPUS", d -> {
            d.writeInt(all.length);
            d.writeLong(now);
            for (Interpreter in : all) {
                in.saveState(d, now);
            }
        });
    }

    /**
     * Read the record written by {@link #saveState} into the CPUs of this domain. Guest time
     * resumes where it stopped: the wall clock (if this domain uses it) continues from the saved
     * guest time, and each CPU's timebase from its saved value, so no time passes for the guest
     * while the state is saved. The shared compiled-code cache is emptied. No CPU of the domain
     * may be running.
     *
     * @throws org.boxtheta.javapower.core.StateMismatchException if the state holds another
     *     number of CPUs (nothing is changed then)
     * @throws org.boxtheta.javapower.core.StateFormatException if the record is corrupt; the CPUs
     *     are then partly loaded
     */
    public void loadState(org.boxtheta.javapower.core.StateInput in) throws java.io.IOException {
        org.boxtheta.javapower.core.StateInput.Record r = in.record("CPUS");
        Interpreter[] all = cpus;
        int n = r.readInt();
        if (n != all.length) {
            throw new org.boxtheta.javapower.core.StateMismatchException("the machine state has " + n
                    + " CPUs; this machine has " + all.length);
        }
        long now = r.readLong();
        if (now < 0) {
            throw r.corrupt("guest time " + now);
        }
        wallBase = System.nanoTime() - hostNs(now);
        compiledCode.clear();
        for (Interpreter cpu : all) {
            cpu.loadState(r, now);
        }
    }

    Interpreter.Code findCode(long key) {
        return compiledCode.get(key);
    }

    /** Publish {@code c} under {@code key}; returns the code already there if another CPU won the race. */
    Interpreter.Code publishCode(long key, Interpreter.Code c) {
        if (compiledCode.size() >= MAX_SHARED_CODE) {
            compiledCode.clear();
        }
        Interpreter.Code prev = compiledCode.putIfAbsent(key, c);
        return prev != null ? prev : c;
    }

    void replaceCode(long key, Interpreter.Code old, Interpreter.Code c) {
        compiledCode.replace(key, old, c);
    }

    void dropCode(long key, Interpreter.Code c) {
        compiledCode.remove(key, c);
    }

    /** Number of compiled code entries shared by the CPUs (statistics). */
    public int sharedCodeEntries() {
        return compiledCode.size();
    }

    /** For tests and diagnostics: per-CPU acknowledged TLB generations. */
    List<Long> acks() {
        List<Long> r = new ArrayList<>();
        for (Interpreter i : cpus) {
            r.add(i.tlbAck);
        }
        return r;
    }
}
