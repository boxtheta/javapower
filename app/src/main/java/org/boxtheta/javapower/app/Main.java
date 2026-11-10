package org.boxtheta.javapower.app;

import java.nio.file.Path;
import java.util.Map;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.Emulator;
import org.boxtheta.javapower.core.Spr;
import org.boxtheta.javapower.cpu.Interpreter;
import org.boxtheta.javapower.papr.Machine;
import org.boxtheta.javapower.papr.MachineConfig;
import org.boxtheta.javapower.papr.StdioCharBackend;

/** Command-line front end: boots a pseries guest with the console on stdin/stdout. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        MachineConfig cfg = new MachineConfig().console(new StdioCharBackend());
        Interpreter.Clock clock = Interpreter.Clock.WALL;
        long maxInsns = Long.MAX_VALUE;
        int icountShift = 0;
        boolean logInterrupts = false;
        int statusSecs = 0;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--ram" -> cfg.ram(Long.parseLong(args[++i]) << 20);
                case "--firmware" -> cfg.firmware(Path.of(args[++i]));
                case "--disk" -> cfg.addDisk(Path.of(args[++i]));
                case "--disk4k" -> cfg.addDisk(Path.of(args[++i])).blockSize(4096).physicalBlockSize(4096);
                case "--cdrom" -> cfg.addCdrom(Path.of(args[++i]));
                case "--deterministic" -> clock = Interpreter.Clock.DETERMINISTIC;
                case "--icount-shift" -> icountShift = Integer.parseInt(args[++i]);
                case "--max-insns" -> maxInsns = Long.parseLong(args[++i]);
                case "--log-interrupts" -> logInterrupts = true;
                case "--status-secs" -> statusSecs = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("usage: javapower [--ram MiB] [--firmware slof.bin] [--disk img]"
                            + " [--disk4k img] [--cdrom iso] [--deterministic] [--icount-shift n]"
                            + " [--max-insns n] [--log-interrupts] [--status-secs n]");
                    return;
                }
            }
        }

        try (Emulator emu = new Emulator(cfg, clock)) {
            CpuState cpu = emu.cpu();
            Interpreter interp = emu.interpreter();
            interp.icountShift = icountShift;
            interp.unimplementedHook = (s, pc, insn, key) ->
                    System.err.printf("[cpu] unimplemented %s insn=%08x pc=%x msr=%x%n", key, insn, pc, s.msr);
            if (logInterrupts) {
                interp.interruptHook = (s, vector, srr0) ->
                        System.err.printf("[cpu] interrupt %x srr0=%x icount=%d%n", vector, srr0, s.icount);
            }
            if (statusSecs > 0) {
                long periodMs = statusSecs * 1000L;
                Thread.ofPlatform().daemon().name("status").start(() -> {
                    try {
                        long last = 0;
                        while (true) {
                            Thread.sleep(periodMs);
                            // Racy snapshot of the CPU thread's state; good enough for progress reports.
                            long ic = cpu.icount;
                            System.err.printf("[status] icount=%d (+%d) pc=%x msr=%x lr=%x ceded=%b pid=%x%n",
                                    ic, ic - last, cpu.pc, cpu.msr, cpu.lr, cpu.ceded, cpu.spr[Spr.PIDR]);
                            last = ic;
                        }
                    } catch (InterruptedException e) {
                        // exit
                    }
                });
            }

            Machine.ShutdownReason why = emu.run(maxInsns);
            Machine m = emu.machine();
            System.out.flush();
            System.err.printf("%n[javapower] stopped: reason=%s %s icount=%d pc=%x msr=%x%n",
                    why, m.shutdownMessage() == null ? "" : m.shutdownMessage(), cpu.icount, cpu.pc, cpu.msr);
            for (Map.Entry<String, Long> e : emu.unimplementedInstructions().entrySet()) {
                System.err.printf("[javapower] unimplemented %s x%d%n", e.getKey(), e.getValue());
            }
        }
    }
}
