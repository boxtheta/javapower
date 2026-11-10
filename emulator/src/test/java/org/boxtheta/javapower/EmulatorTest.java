package org.boxtheta.javapower;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.boxtheta.javapower.cpu.Interpreter;
import org.boxtheta.javapower.papr.BufferCharBackend;
import org.boxtheta.javapower.papr.Machine;
import org.boxtheta.javapower.papr.MachineConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class EmulatorTest {
    /** Stock SLOF must reach its banner; this exercises CPU, MMU-off paths, hcalls and the console. */
    @Test
    void slofPrintsBanner() throws Exception {
        Path fw = MachineConfig.DEFAULT_FIRMWARE;
        Assumptions.assumeTrue(Files.isReadable(fw), "SLOF firmware not installed");
        BufferCharBackend console = new BufferCharBackend();
        MachineConfig cfg = new MachineConfig().ram(512L << 20).console(console);
        try (Emulator emu = new Emulator(cfg, Interpreter.Clock.DETERMINISTIC)) {
            Machine.ShutdownReason why = emu.run(400_000_000L);
            assertEquals(Machine.ShutdownReason.NONE, why);
            assertTrue(console.output().contains("SLOF"), console.output());
            assertTrue(emu.unimplementedInstructions().isEmpty(), emu.unimplementedInstructions().toString());
            // Resumable after the instruction limit.
            long before = emu.cpu().icount;
            emu.run(1_000_000L);
            assertEquals(before + 1_000_000L, emu.cpu().icount);
        }
    }
}
