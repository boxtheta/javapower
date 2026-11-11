package org.boxtheta.javapower.app;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Random;
import org.boxtheta.javapower.Emulator;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.Spr;
import org.boxtheta.javapower.cpu.Interpreter;
import org.boxtheta.javapower.firmware.slof.SlofFirmware;
import org.boxtheta.javapower.papr.Machine;
import org.boxtheta.javapower.papr.MachineConfig;
import org.boxtheta.javapower.papr.PaprLog;
import org.boxtheta.javapower.papr.StdioCharBackend;

/** Command-line front end: boots a pseries guest with the console on stdin/stdout. */
public final class Main {
    private Main() {}

    /** Seed and time of day used by {@code --deterministic}, so runs repeat exactly. */
    private static final long DETERMINISTIC_SEED = 1;
    private static final Instant DETERMINISTIC_TIME = Instant.parse("2026-01-01T00:00:00Z");

    private static final String USAGE = "usage: javapower [--ram MiB] [--smp n] [--firmware slof.bin] [--disk img]"
            + " [--disk4k img] [--cdrom iso] [--clock-scale f] [--deterministic] [--icount-shift n]"
            + " [--max-insns n] [--log-interrupts] [--status-secs n] [--jit off|blocks|regions] [--no-code-sharing]";

    public static void main(String[] args) throws Exception {
        MachineConfig cfg = new MachineConfig().console(new StdioCharBackend())
                .logSink(PaprLog.printTo(System.err));
        Interpreter.Clock clock = Interpreter.Clock.WALL;
        long maxInsns = Long.MAX_VALUE;
        int icountShift = 0;
        double clockScale = 1.0;
        int smp = 1;
        boolean logInterrupts = false;
        int statusSecs = 0;
        String jit = "regions";
        boolean shareCode = true;
        Path firmware = null;
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--ram" -> cfg.ram(Long.parseLong(args[++i]) << 20);
                    case "--smp" -> smp = Integer.parseInt(args[++i]);
                    case "--firmware" -> firmware = Path.of(args[++i]);
                    case "--disk" -> cfg.addDisk(Path.of(args[++i]));
                    case "--disk4k" -> cfg.addDisk(Path.of(args[++i])).blockSize(4096).physicalBlockSize(4096);
                    case "--cdrom" -> cfg.addCdrom(Path.of(args[++i]));
                    case "--clock-scale" -> clockScale = Double.parseDouble(args[++i]);
                    case "--deterministic" -> clock = Interpreter.Clock.DETERMINISTIC;
                    case "--icount-shift" -> icountShift = Integer.parseInt(args[++i]);
                    case "--max-insns" -> maxInsns = Long.parseLong(args[++i]);
                    case "--log-interrupts" -> logInterrupts = true;
                    case "--status-secs" -> statusSecs = Integer.parseInt(args[++i]);
                    case "--jit" -> {
                        jit = args[++i];
                        if (!jit.equals("off") && !jit.equals("blocks") && !jit.equals("regions")) {
                            throw new IllegalArgumentException("--jit takes off, blocks or regions");
                        }
                    }
                    case "--no-code-sharing" -> shareCode = false;
                    default -> throw new IllegalArgumentException("unknown option " + args[i]);
                }
            }
            cfg.cpus(smp);
            if (clock == Interpreter.Clock.DETERMINISTIC && (smp != 1 || clockScale != 1.0)) {
                throw new IllegalArgumentException("--deterministic needs --smp 1 and no --clock-scale");
            }
        } catch (RuntimeException e) {
            System.err.println("javapower: " + e.getMessage());
            System.err.println(USAGE);
            return;
        }
        // The SLOF image bundled with the CLI unless --firmware names another.
        if (firmware != null) {
            cfg.firmware(firmware);
        } else {
            cfg.firmware(SlofFirmware.image());
        }
        Long seed = null;
        if (clock == Interpreter.Clock.DETERMINISTIC) {
            // Pin every source of entropy and time of day, so the same command repeats exactly.
            cfg.random(new Random(DETERMINISTIC_SEED)).clock(Clock.fixed(DETERMINISTIC_TIME, ZoneOffset.UTC));
            seed = DETERMINISTIC_SEED;
        }

        Emulator emulator;
        try {
            emulator = new Emulator(cfg, clock, clockScale, seed);
        } catch (OutOfMemoryError e) {
            System.err.println("javapower: " + e.getMessage());
            return;
        }
        try (Emulator emu = emulator) {
            for (Interpreter interp : emu.interpreters()) {
                int n = interp.state().index;
                interp.icountShift = icountShift;
                // Compilation tiers (for debugging; results are identical either way).
                interp.compileBlocks = !jit.equals("off");
                interp.compileRegions = jit.equals("regions");
                interp.shareCode = shareCode;
                interp.unimplementedHook = (s, pc, insn, key) -> System.err.printf(
                        "[cpu%d] unimplemented %s insn=%08x pc=%x msr=%x%n", n, key, insn, pc, s.msr);
                if (logInterrupts) {
                    interp.interruptHook = (s, vector, srr0) -> System.err.printf(
                            "[cpu%d] interrupt %x srr0=%x icount=%d%n", n, vector, srr0, s.icount);
                }
            }
            Thread status = statusSecs > 0 ? startStatus(emu, statusSecs) : null;
            Machine.ShutdownReason why;
            try {
                why = emu.run(maxInsns);
            } finally {
                if (status != null) {
                    status.interrupt();
                }
            }
            Machine m = emu.machine();
            System.out.flush();
            CpuState cpu = emu.cpu();
            long total = 0;
            for (CpuState c : emu.cpus()) {
                total += c.icount;
            }
            System.err.printf("%n[javapower] stopped: reason=%s %s icount=%d (all CPUs %d) pc=%x msr=%x%n",
                    why, m.shutdownMessage() == null ? "" : m.shutdownMessage(), cpu.icount, total, cpu.pc, cpu.msr);
            if (emu.cpuCount() > 1) {
                System.err.printf("[javapower] tlb shootdowns=%d wait=%dms%n", emu.domain().tlbShootdowns(),
                        emu.domain().tlbShootdownWaitNanos() / 1_000_000);
            }
            for (Map.Entry<String, Long> e : emu.unimplementedInstructions().entrySet()) {
                System.err.printf("[javapower] unimplemented %s x%d%n", e.getKey(), e.getValue());
            }
            // Hypercalls, RTAS tokens and other platform requests the guest made that we lack.
            for (Map.Entry<String, Long> e : emu.log().unimplementedCounts().entrySet()) {
                System.err.printf("[javapower] unimplemented %s x%d%n", e.getKey(), e.getValue());
            }
        }
    }

    /**
     * Progress report every {@code secs} seconds: one line per CPU with its instruction count,
     * rate and state (run, idle after H_CEDE, or stopped), plus a total. The snapshot is racy
     * (the CPU threads keep running), which is fine for progress reports.
     */
    private static Thread startStatus(Emulator emu, int secs) {
        long periodMs = secs * 1000L;
        int n = emu.cpuCount();
        return Thread.ofPlatform().daemon().name("status").start(() -> {
            long[] last = new long[n];
            long t0 = System.nanoTime();
            try {
                while (true) {
                    Thread.sleep(periodMs);
                    long elapsed = (System.nanoTime() - t0) / 1_000_000_000L;
                    long sum = 0;
                    long dsum = 0;
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < n; i++) {
                        CpuState c = emu.cpu(i);
                        long ic = c.icount;
                        long d = ic - last[i];
                        last[i] = ic;
                        sum += ic;
                        dsum += d;
                        String state = c.halted ? "stopped" : c.ceded ? "idle" : "run";
                        sb.append(String.format("[status] cpu%d icount=%d (+%d, %.1f MIPS) %s pc=%x msr=%x lr=%x pid=%x%n",
                                i, ic, d, d / (periodMs * 1000.0), state, c.pc, c.msr, c.lr, c.spr[Spr.PIDR]));
                    }
                    if (n > 1) {
                        sb.append(String.format("[status] t=%ds all icount=%d (+%d, %.1f MIPS)%n", elapsed, sum, dsum,
                                dsum / (periodMs * 1000.0)));
                    }
                    System.err.print(sb);
                }
            } catch (InterruptedException e) {
                // exit
            }
        });
    }
}
