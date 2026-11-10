package org.boxtheta.javapower.app;

import java.nio.file.Path;
import java.util.Map;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.cpu.IntInsns;
import org.boxtheta.javapower.cpu.Interpreter;
import org.boxtheta.javapower.fpvec.FpuSets;
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

        try (Machine m = new Machine(cfg)) {
            DecodeTable table = new DecodeTable();
            table.addAll(new IntInsns());
            FpuSets.registerAll(table);
            CpuState cpu = new CpuState();
            m.attachCpu(cpu);
            Interpreter interp = new Interpreter(cpu, m.memory(), table, clock);
            interp.icountShift = icountShift;
            interp.unimplementedHook = (s, pc, insn, key) ->
                    System.err.printf("[cpu] unimplemented %s insn=%08x pc=%x msr=%x%n", key, insn, pc, s.msr);
            if (logInterrupts) {
                interp.interruptHook = (s, vector, srr0) ->
                        System.err.printf("[cpu] interrupt %x srr0=%x icount=%d%n", vector, srr0, s.icount);
            }

            int statusTicks = statusSecs * 10;
            Thread pump = Thread.ofPlatform().daemon().name("pump").start(() -> {
                try {
                    long last = 0;
                    for (int tick = 1; ; tick++) {
                        Thread.sleep(100);
                        m.pump();
                        if (statusTicks > 0 && tick % statusTicks == 0) {
                            // Racy snapshot of the CPU thread's state; good enough for progress reports.
                            long ic = cpu.icount;
                            System.err.printf("[status] icount=%d (+%d) pc=%x msr=%x lr=%x ceded=%b pid=%x%n",
                                    ic, ic - last, cpu.pc, cpu.msr, cpu.lr, cpu.ceded,
                                    cpu.spr[org.boxtheta.javapower.core.Spr.PIDR]);
                            last = ic;
                        }
                    }
                } catch (InterruptedException e) {
                    // exit
                }
            });

            long remaining = maxInsns;
            while (true) {
                interp.resetPapr();
                m.reset(cpu);
                long before = cpu.icount;
                interp.run(remaining);
                remaining -= cpu.icount - before;
                if (cpu.stopRequested && m.shutdownReason() == Machine.ShutdownReason.REBOOT && remaining > 0) {
                    cpu.stopRequested = false;
                    continue;
                }
                break;
            }
            pump.interrupt();
            System.out.flush();
            System.err.printf("%n[javapower] stopped: reason=%s %s icount=%d pc=%x msr=%x%n",
                    m.shutdownReason(), m.shutdownMessage() == null ? "" : m.shutdownMessage(),
                    cpu.icount, cpu.pc, cpu.msr);
            for (Map.Entry<String, Long> e : interp.unimplementedCounts().entrySet()) {
                System.err.printf("[javapower] unimplemented %s x%d%n", e.getKey(), e.getValue());
            }
        }
    }
}
