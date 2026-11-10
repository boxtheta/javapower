package org.boxtheta.javapower;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.boxtheta.javapower.papr.Machine;
import org.boxtheta.javapower.papr.MachineConfig;
import org.boxtheta.javapower.papr.PaprLog;
import org.boxtheta.javapower.papr.StdioCharBackend;
import org.junit.jupiter.api.Test;

/** Embedding lifecycle: close() from another thread stops the guest and releases every thread. */
class LifecycleTest {
    /** CPU 0 starts CPU 1, then both spin; CPU 0 also makes an unknown hypercall once. */
    static byte[] firmware() {
        Asm a = new Asm();
        a.org(0x100).b("main");
        SmpEmulatorTest.vectors(a);
        a.org(SmpEmulatorTest.MAIN).label("main");
        a.li32(4, SmpEmulatorTest.RTAS_BUF);
        a.li32(5, SmpEmulatorTest.RTAS_START_CPU).stw(5, 0, 4).li(5, 3).stw(5, 4, 4).li(5, 1).stw(5, 8, 4);
        a.li(5, 1).stw(5, 12, 4).li32(5, SmpEmulatorTest.SECONDARY).stw(5, 16, 4).stw(5, 20, 4);
        SmpEmulatorTest.rtas(a);
        a.li(3, 0x1234).sc1();
        a.label("spin").addi(6, 6, 1).b("spin");
        a.org(SmpEmulatorTest.SECONDARY).label("spin2").addi(6, 6, 1).b("spin2");
        return a.bytesBe();
    }

    @Test
    void closeFromAnotherThreadStopsTheGuestAndReleasesThreads() throws Exception {
        PipedOutputStream typer = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(typer);
        List<PaprLog.Entry> log = new CopyOnWriteArrayList<>();
        MachineConfig cfg = new MachineConfig().ram(256L << 20).cpus(2).firmware(firmware())
                .console(new StdioCharBackend(in, new ByteArrayOutputStream())).random(new Random(1))
                .logSink(log::add);
        Emulator emu = new Emulator(cfg);
        AtomicReference<Object> result = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                result.set(emu.run());
            } catch (Throwable e) {
                result.set(e);
            }
        });
        runner.start();
        long end = System.currentTimeMillis() + 5000;
        while (emu.cpu(1).icount == 0 && System.currentTimeMillis() < end) {
            Thread.sleep(10);
        }
        assertTrue(emu.cpu(1).icount > 0, "CPU 1 never ran");
        assertThrows(IllegalStateException.class, emu::run, "a second concurrent run must be refused");

        emu.close();
        runner.join(5000);
        assertFalse(runner.isAlive(), "run() did not return after close()");
        assertEquals(Machine.ShutdownReason.NONE, result.get());
        SmpEmulatorTest.assertNoCpuThreads(); // CPU, pump and console threads
        assertDoesNotThrow(emu::close);
        assertThrows(IllegalStateException.class, emu::run);

        assertEquals(1L, emu.log().unimplementedCounts().get("hcall-0x1234"));
        assertTrue(log.stream().anyMatch(e -> "hcall-0x1234".equals(e.key())), log.toString());
    }
}
