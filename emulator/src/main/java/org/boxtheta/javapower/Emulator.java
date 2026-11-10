package org.boxtheta.javapower;

import java.io.IOException;
import java.util.Map;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.cpu.IntInsns;
import org.boxtheta.javapower.cpu.Interpreter;
import org.boxtheta.javapower.fpvec.FpuSets;
import org.boxtheta.javapower.papr.Machine;
import org.boxtheta.javapower.papr.MachineConfig;

/**
 * A complete POWER9 PAPR ({@code pseries}) virtual machine: one CPU with the full instruction
 * set, the machine model and its devices. This is the entry point for embedding the emulator.
 *
 * <pre>{@code
 * MachineConfig cfg = new MachineConfig().console(myConsole);
 * cfg.addCdrom(Path.of("freebsd-disc1.iso"));
 * try (Emulator emu = new Emulator(cfg)) {
 *     Machine.ShutdownReason why = emu.run();   // blocks until the guest powers off
 * }
 * }</pre>
 *
 * <p>{@link #run} executes the guest on the calling thread. {@link #stop} may be called from any
 * thread; everything else belongs to the thread that runs the guest, or to any thread while the
 * guest is not running. Instances are independent of each other.
 */
public final class Emulator implements AutoCloseable {
    /** How often device timers are serviced while the guest runs. */
    private static final long PUMP_INTERVAL_MS = 100;

    private final Machine machine;
    private final CpuState cpu = new CpuState();
    private final Interpreter interpreter;
    private boolean needsReset = true;
    private volatile boolean stopAsked;

    /** A machine driven by the host's wall clock. */
    public Emulator(MachineConfig config) throws IOException {
        this(config, Interpreter.Clock.WALL);
    }

    /**
     * @param clock {@link Interpreter.Clock#WALL} for interactive use, or
     *     {@link Interpreter.Clock#DETERMINISTIC} for guest time derived from the instruction count
     */
    public Emulator(MachineConfig config, Interpreter.Clock clock) throws IOException {
        machine = new Machine(config);
        DecodeTable table = new DecodeTable();
        table.addAll(new IntInsns());
        FpuSets.registerAll(table);
        machine.attachCpu(cpu);
        interpreter = new Interpreter(cpu, machine.memory(), table, clock);
    }

    /** The machine model: devices, guest memory, shutdown state. */
    public Machine machine() {
        return machine;
    }

    /** The CPU's architectural state. Read it only while the guest is not running. */
    public CpuState cpu() {
        return cpu;
    }

    /** The execution engine, for hooks (trace, interrupts, unimplemented instructions) and clocks. */
    public Interpreter interpreter() {
        return interpreter;
    }

    /** Instructions that raised a program interrupt because they are not implemented, with counts. */
    public Map<String, Long> unimplementedInstructions() {
        return interpreter.unimplementedCounts();
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
     * Run at most {@code maxInsns} instructions. Returns why the guest stopped, or
     * {@link Machine.ShutdownReason#NONE} if it was interrupted by the limit or by {@link #stop}; in
     * that case calling {@code run} again resumes where it left off.
     */
    public Machine.ShutdownReason run(long maxInsns) {
        Thread pump = Thread.ofPlatform().daemon().name("javapower-pump").start(() -> {
            try {
                while (true) {
                    Thread.sleep(PUMP_INTERVAL_MS);
                    machine.pump();
                }
            } catch (InterruptedException e) {
                // run() is returning
            }
        });
        try {
            long remaining = maxInsns;
            while (true) {
                if (needsReset) {
                    interpreter.resetPapr();
                    machine.reset(cpu);
                    needsReset = false;
                }
                if (stopAsked) {
                    stopAsked = false;
                    return Machine.ShutdownReason.NONE;
                }
                remaining -= interpreter.run(remaining);
                if (!cpu.stopRequested) {
                    return Machine.ShutdownReason.NONE; // instruction limit
                }
                cpu.stopRequested = false;
                if (stopAsked) {
                    stopAsked = false;
                    return Machine.ShutdownReason.NONE;
                }
                Machine.ShutdownReason why = machine.shutdownReason();
                needsReset = true;
                if (why != Machine.ShutdownReason.REBOOT || remaining <= 0) {
                    return why;
                }
            }
        } finally {
            pump.interrupt();
        }
    }

    /** Ask a running guest to pause at the next instruction boundary. Safe from any thread. */
    public void stop() {
        stopAsked = true;
        cpu.stopRequested = true;
        cpu.wake();
    }

    /** Release guest memory, disk images and the console. The guest must not be running. */
    @Override
    public void close() throws IOException {
        machine.close();
    }
}
