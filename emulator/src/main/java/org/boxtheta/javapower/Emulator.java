package org.boxtheta.javapower;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.cpu.CpuDomain;
import org.boxtheta.javapower.cpu.IntInsns;
import org.boxtheta.javapower.cpu.Interpreter;
import org.boxtheta.javapower.fpvec.FpuSets;
import org.boxtheta.javapower.papr.Machine;
import org.boxtheta.javapower.papr.MachineConfig;
import org.boxtheta.javapower.papr.PaprLog;

/**
 * A complete POWER9 PAPR ({@code pseries}) virtual machine: {@link MachineConfig#cpus} CPUs with
 * the full instruction set, the machine model and its devices. This is the entry point for
 * embedding the emulator.
 *
 * <pre>{@code
 * MachineConfig cfg = new MachineConfig().cpus(4).console(myConsole)
 *         .logSink(PaprLog.printTo(System.err));        // default: System.Logger
 * cfg.addCdrom(Path.of("freebsd-disc1.iso"));
 * try (Emulator emu = new Emulator(cfg)) {
 *     Machine.ShutdownReason why = emu.run();   // blocks until the guest powers off
 *     System.out.println(emu.log().unimplementedCounts());
 * }
 * }</pre>
 *
 * <h2>Host resources</h2>
 * A machine owns host resources: open disk images (and their temporary copy-on-write overlays),
 * the NVRAM file, the console backend and its threads, and off-heap guest RAM. {@link #close}
 * releases all of them except the RAM, which the JVM frees once the emulator is unreachable. The
 * emulator never writes to {@code System.out} or {@code System.err} itself: guest console output
 * goes to the {@link MachineConfig#console console backend} and diagnostics to the
 * {@linkplain MachineConfig#logSink log sink}.
 *
 * <h2>Threads</h2>
 * {@link #run} executes CPU 0 on the calling thread and every other CPU on a host thread of its
 * own ({@code javapower-cpu-N}), all in parallel over the one guest memory; it starts those
 * threads and joins them before it returns, so no emulator thread outlives a {@code run} call. A
 * guest reboot stops and joins every CPU thread, resets the machine and starts them again.
 * The CPU threads are daemon threads. {@link #stop} and {@link #close} may be called from any
 * thread except the emulator's own; everything else belongs to the thread that calls
 * {@code run}, or to any thread while the guest is not running. Only one {@code run} call may be
 * active at a time. Instances are independent: several may run in one JVM, each with its own log.
 *
 * <h2>Time</h2>
 * With {@link Interpreter.Clock#WALL} (the default) all CPUs share one timebase that follows the
 * host clock, slowed down by {@code timeScale} (see {@link CpuDomain}). The deterministic clock
 * derives time from the instruction count and is only available with one CPU.
 */
public final class Emulator implements AutoCloseable {
    /** How often device timers are serviced while the guest runs. */
    private static final long PUMP_INTERVAL_MS = 100;

    private final Machine machine;
    private final CpuDomain domain;
    private final List<CpuState> cpus;
    private final List<Interpreter> interpreters;
    private boolean needsReset = true;
    private volatile boolean stopAsked;
    /** Held for the duration of {@link #run}; {@link #close} takes it to wait for a run to end. */
    private final ReentrantLock runLock = new ReentrantLock();
    private volatile boolean closed;

    /** A machine driven by the host's wall clock at real time. */
    public Emulator(MachineConfig config) throws IOException {
        this(config, Interpreter.Clock.WALL);
    }

    /**
     * @param clock {@link Interpreter.Clock#WALL} for interactive use, or
     *     {@link Interpreter.Clock#DETERMINISTIC} for guest time derived from the instruction count
     *     (one CPU only)
     */
    public Emulator(MachineConfig config, Interpreter.Clock clock) throws IOException {
        this(config, clock, 1.0);
    }

    /**
     * @param timeScale WALL clock only: host time per unit of guest time. 1 is real time; 4 makes
     *     a guest second last four host seconds, so timeouts written for real hardware hold when
     *     the emulator is slower than it
     */
    public Emulator(MachineConfig config, Interpreter.Clock clock, double timeScale) throws IOException {
        this(config, clock, timeScale, null);
    }

    /**
     * @param seed if not null, the seed of the CPUs' hardware random number generator
     *     ({@code darn}); fix it (together with {@link MachineConfig#random} and
     *     {@link MachineConfig#clock}) for reproducible deterministic runs
     */
    public Emulator(MachineConfig config, Interpreter.Clock clock, double timeScale, Long seed) throws IOException {
        machine = new Machine(config);
        try {
            if (clock == Interpreter.Clock.DETERMINISTIC && machine.cpuCount() > 1) {
                throw new IllegalArgumentException("the deterministic clock supports one CPU only");
            }
            domain = seed == null ? new CpuDomain(machine.memory(), clock, timeScale)
                    : new CpuDomain(machine.memory(), clock, timeScale, seed);
            DecodeTable table = new DecodeTable();
            table.addAll(new IntInsns());
            FpuSets.registerAll(table);
            int n = machine.cpuCount();
            List<CpuState> cs = new ArrayList<>(n);
            List<Interpreter> is = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                CpuState c = new CpuState();
                c.index = i;
                cs.add(c);
                is.add(new Interpreter(c, domain, table));
            }
            cpus = Collections.unmodifiableList(cs);
            interpreters = Collections.unmodifiableList(is);
            machine.attachCpus(cpus);
            machine.setSystemResetHandler(c -> interpreters.get(c.index).systemReset());
        } catch (RuntimeException | Error e) {
            try {
                machine.close();
            } catch (IOException ce) {
                e.addSuppressed(ce);
            }
            throw e;
        }
    }

    /** The machine model: devices, guest memory, shutdown state. */
    public Machine machine() {
        return machine;
    }

    /**
     * The machine's diagnostics ({@link Machine#log()}): change the sink, or read the
     * unimplemented hypercalls and RTAS calls the guest made.
     */
    public PaprLog log() {
        return machine.log();
    }

    /** What the CPUs share: timebase, TLB invalidation; statistics. */
    public CpuDomain domain() {
        return domain;
    }

    /** Number of guest CPUs. */
    public int cpuCount() {
        return cpus.size();
    }

    /** CPU 0's architectural state. Read it only while the guest is not running. */
    public CpuState cpu() {
        return cpus.get(0);
    }

    /** CPU {@code i}'s architectural state. Read it only while the guest is not running. */
    public CpuState cpu(int i) {
        return cpus.get(i);
    }

    /** Every CPU's state, by CPU number. */
    public List<CpuState> cpus() {
        return cpus;
    }

    /** CPU 0's execution engine, for hooks (trace, interrupts, unimplemented instructions) and clocks. */
    public Interpreter interpreter() {
        return interpreters.get(0);
    }

    /** CPU {@code i}'s execution engine. Hooks are per CPU; install them on each. */
    public Interpreter interpreter(int i) {
        return interpreters.get(i);
    }

    /** Every CPU's execution engine, by CPU number. */
    public List<Interpreter> interpreters() {
        return interpreters;
    }

    /** Instructions that raised a program interrupt because they are not implemented, with counts (all CPUs). */
    public Map<String, Long> unimplementedInstructions() {
        Map<String, Long> m = new TreeMap<>();
        for (Interpreter in : interpreters) {
            in.unimplementedCounts().forEach((k, v) -> m.merge(k, v, Long::sum));
        }
        return m;
    }

    /** Power-on reset: the next {@link #run} starts from firmware entry. */
    public void reset() {
        needsReset = true;
    }

    /** Run until the guest powers off or panics, or {@link #stop} is called. Guest reboots are followed. */
    public Machine.ShutdownReason run() {
        return run(Long.MAX_VALUE);
    }

    /**
     * Run until CPU 0 has executed at most {@code maxInsns} instructions (the other CPUs are then
     * stopped at their next instruction boundary). Returns why the guest stopped, or
     * {@link Machine.ShutdownReason#NONE} if it was interrupted by the limit or by {@link #stop};
     * in that case calling {@code run} again resumes where it left off. If a CPU thread or the
     * device timer thread fails with an exception, every CPU is stopped and the exception is
     * rethrown here. Every thread {@code run} starts has ended when it returns or throws.
     *
     * @throws IllegalStateException if the emulator is closed or another {@code run} is active
     */
    public Machine.ShutdownReason run(long maxInsns) {
        if (!runLock.tryLock()) {
            throw new IllegalStateException("the emulator is already running");
        }
        try {
            if (closed) {
                throw new IllegalStateException("the emulator is closed");
            }
            return runLocked(maxInsns);
        } finally {
            runLock.unlock();
        }
    }

    private Machine.ShutdownReason runLocked(long maxInsns) {
        AtomicReference<Throwable> pumpFailure = new AtomicReference<>();
        Thread pump = Thread.ofPlatform().daemon().name("javapower-pump").start(() -> {
            try {
                while (true) {
                    Thread.sleep(PUMP_INTERVAL_MS);
                    machine.pump();
                }
            } catch (InterruptedException e) {
                // run() is returning
            } catch (Throwable e) {
                pumpFailure.set(e);
                requestStopAll();
            }
        });
        try {
            return runLoop(maxInsns, pumpFailure);
        } finally {
            pump.interrupt();
            joinUninterruptibly(pump);
        }
    }

    private void rethrowPumpFailure(AtomicReference<Throwable> pumpFailure) {
        Throwable f = pumpFailure.get();
        if (f != null) {
            needsReset = false;
            rethrow(f, "device timer thread failed");
        }
    }

    private static void rethrow(Throwable f, String what) {
        if (f instanceof RuntimeException r) {
            throw r;
        }
        if (f instanceof Error err) {
            throw err;
        }
        throw new IllegalStateException(what, f);
    }

    private static void joinUninterruptibly(Thread t) {
        boolean interrupted = false;
        while (true) {
            try {
                t.join();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Machine.ShutdownReason runLoop(long maxInsns, AtomicReference<Throwable> pumpFailure) {
        long remaining = maxInsns;
        while (true) {
            if (stopAsked) {
                stopAsked = false;
                clearStopRequests();
                return Machine.ShutdownReason.NONE;
            }
            if (needsReset) {
                for (Interpreter in : interpreters) {
                    in.resetPapr();
                }
                machine.reset();
                needsReset = false;
            }
            remaining -= runAllCpus(remaining);
            rethrowPumpFailure(pumpFailure);
            Machine.ShutdownReason why = machine.shutdownReason();
            clearStopRequests();
            if (why != Machine.ShutdownReason.NONE) {
                needsReset = true;
                if (why == Machine.ShutdownReason.REBOOT && remaining > 0 && !stopAsked) {
                    continue;
                }
                stopAsked = false;
                return why;
            }
            // Instruction limit or stop().
            stopAsked = false;
            return Machine.ShutdownReason.NONE;
        }
    }

    /**
     * Run CPU 0 here and the others on their own threads until CPU 0 stops (limit or stop
     * request); then stop and join the others. Returns CPU 0's instruction count.
     */
    private long runAllCpus(long maxInsns) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        long done = 0;
        try {
            for (int i = 1; i < interpreters.size(); i++) {
                Interpreter in = interpreters.get(i);
                threads.add(Thread.ofPlatform().daemon().name("javapower-cpu-" + i).start(() -> {
                    try {
                        in.run();
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                        requestStopAll();
                    }
                }));
            }
            done = interpreters.get(0).run(maxInsns);
        } catch (Throwable e) {
            failure.compareAndSet(null, e);
        } finally {
            requestStopAll();
            for (Thread t : threads) {
                joinUninterruptibly(t);
            }
        }
        Throwable f = failure.get();
        if (f != null) {
            needsReset = false; // keep the state for inspection; reset() starts over
            rethrow(f, "CPU thread failed");
        }
        return done;
    }

    private void requestStopAll() {
        for (CpuState c : cpus) {
            c.stopRequested = true;
            c.wake();
        }
    }

    private void clearStopRequests() {
        for (CpuState c : cpus) {
            c.stopRequested = false;
        }
    }

    /** Ask a running guest to pause at the next instruction boundary of every CPU. Safe from any thread. */
    public void stop() {
        stopAsked = true;
        requestStopAll();
    }

    /**
     * Release the machine's host resources (see {@link Machine#close}). If the guest is running on
     * another thread, it is stopped first and this waits for {@link #run} to return. Idempotent;
     * after it, {@code run} throws.
     *
     * @throws IllegalStateException if called from inside {@code run} (a hook on CPU 0's thread)
     */
    @Override
    public void close() throws IOException {
        if (runLock.isHeldByCurrentThread()) {
            throw new IllegalStateException("close() called from inside run()");
        }
        closed = true;
        boolean interrupted = false;
        while (true) {
            stop();
            try {
                if (runLock.tryLock(10, TimeUnit.MILLISECONDS)) {
                    break;
                }
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        try {
            stopAsked = false;
            clearStopRequests();
            machine.close();
        } finally {
            runLock.unlock();
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
