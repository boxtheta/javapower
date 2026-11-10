package javapower.app;

import java.nio.file.Path;
import java.util.Map;
import javapower.core.CpuState;
import javapower.core.DecodeTable;
import javapower.cpu.IntInsns;
import javapower.cpu.Interpreter;
import javapower.fpvec.FpuSets;
import javapower.papr.Machine;
import javapower.papr.MachineConfig;
import javapower.papr.StdioCharBackend;

/** Command-line front end: boots a pseries guest with the console on stdin/stdout. */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        MachineConfig cfg = new MachineConfig().console(new StdioCharBackend());
        Interpreter.Clock clock = Interpreter.Clock.WALL;
        long maxInsns = Long.MAX_VALUE;
        int icountShift = 0;
        boolean logInterrupts = false;
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
                default -> {
                    System.err.println("usage: javapower [--ram MiB] [--firmware slof.bin] [--disk img]"
                            + " [--disk4k img] [--cdrom iso] [--deterministic] [--icount-shift n]"
                            + " [--max-insns n] [--log-interrupts]");
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

            Thread pump = Thread.ofPlatform().daemon().name("pump").start(() -> {
                try {
                    while (true) {
                        Thread.sleep(100);
                        m.pump();
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
