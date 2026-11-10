package org.boxtheta.javapower.papr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Embedding concerns: per-machine logs, and host resources released by {@link Machine#close}. */
class LibraryTest {
    private static MachineConfig config(List<PaprLog.Entry> sink) {
        return new MachineConfig().ram(256L << 20).logSink(sink::add);
    }

    @Test
    void twoMachinesKeepTheirLogsAndCountsSeparate() {
        List<PaprLog.Entry> a = new CopyOnWriteArrayList<>();
        List<PaprLog.Entry> b = new CopyOnWriteArrayList<>();
        Rig ra = new Rig(config(a));
        Rig rb = new Rig(config(b));
        assertTrue(a.isEmpty(), a.toString());

        assertEquals(H.FUNCTION, ra.hcall(0x1234));
        assertEquals(H.FUNCTION, ra.hcall(0x1234));
        assertEquals(1, a.size(), a.toString());
        assertEquals(PaprLog.Level.UNIMP, a.get(0).level());
        assertEquals("hcall-0x1234", a.get(0).key());
        assertTrue(b.isEmpty(), "machine B saw machine A's message: " + b);

        // B reports the same key itself: A's "report once" bookkeeping does not leak into B.
        assertEquals(H.FUNCTION, rb.hcall(0x1234));
        assertEquals(1, b.size(), b.toString());
        assertEquals("hcall-0x1234", b.get(0).key());
        assertEquals(1, a.size());

        assertEquals(Map.of("hcall-0x1234", 2L), ra.m.log().unimplementedCounts());
        assertEquals(Map.of("hcall-0x1234", 1L), rb.m.log().unimplementedCounts());

        // Unknown RTAS tokens are counted as unimplemented too.
        ra.mem.write32be(Rig.RTAS_BUF, 0x2fff);
        ra.mem.write32be(Rig.RTAS_BUF + 4, 0);
        ra.mem.write32be(Rig.RTAS_BUF + 8, 1);
        assertEquals(H.PARAMETER, ra.hcall(H.KVMPPC_H_RTAS, Rig.RTAS_BUF));
        assertEquals(1L, ra.m.log().unimplementedCounts().get("rtas-token-0x2fff"));
        assertFalse(rb.m.log().counts().containsKey("rtas-token-0x2fff"));

        // Resetting one machine's counts reports the key again there only.
        ra.m.log().resetCounts();
        assertEquals(H.FUNCTION, ra.hcall(0x1234));
        assertEquals(3, a.size());
        assertEquals(1, b.size());
    }

    @Test
    void sinkCanBeReplacedPerMachine() {
        List<PaprLog.Entry> first = new CopyOnWriteArrayList<>();
        List<PaprLog.Entry> second = new CopyOnWriteArrayList<>();
        Rig r = new Rig(config(first));
        r.m.log().setSink(second::add);
        r.hcall(0x1234);
        assertTrue(first.isEmpty());
        assertEquals(1, second.size());
        // null restores the default (System.Logger) sink.
        assertNotNull(r.m.log().setSink(null));
        assertNotNull(r.m.log().sink());
    }

    @Test
    void printToFormatsOneLinePerMessageAndSkipsDebug() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PaprLog log = new PaprLog(PaprLog.printTo(new PrintStream(bytes, true, StandardCharsets.UTF_8)));
        log.debug("hidden");
        log.info("hello");
        log.unimplemented("hcall-0x1", "");
        log.once("k", "detail");
        log.once("k", "detail");
        String nl = System.lineSeparator();
        assertEquals("[papr info] hello" + nl + "[papr unimp] hcall-0x1" + nl + "[papr warn] k: detail" + nl,
                bytes.toString(StandardCharsets.UTF_8));
        assertEquals(Map.of("hcall-0x1", 1L, "k", 2L), log.counts());
        assertEquals(Map.of("hcall-0x1", 1L), log.unimplementedCounts());
    }

    @Test
    void defaultSinkIsTheSystemLogger() {
        // Must not throw and must not need configuration.
        assertDoesNotThrow(() -> new PaprLog().warn("javapower LibraryTest: default sink smoke test"));
    }

    @Test
    void closeStopsTheConsoleThreadAndIsIdempotent() throws Exception {
        PipedOutputStream typer = new PipedOutputStream();
        PipedInputStream in = new PipedInputStream(typer);
        StdioCharBackend console = new StdioCharBackend(in, new ByteArrayOutputStream());
        Machine m = new Machine(new MachineConfig().ram(256L << 20).firmware(new byte[] {0x48, 0, 0, 0})
                .console(console).logSink(e -> { }));
        Thread reader = console.readerThread();
        assertNotNull(reader);
        assertTrue(reader.isAlive());
        assertTrue(reader.isDaemon());
        typer.write("x".getBytes(StandardCharsets.US_ASCII));
        typer.flush();
        long end = System.currentTimeMillis() + 2000;
        while (m.vty().pendingInput() == 0 && System.currentTimeMillis() < end) {
            Thread.sleep(5);
        }
        assertEquals(1, m.vty().pendingInput());

        m.close();
        reader.join(2000);
        assertFalse(reader.isAlive(), "console thread still running after close()");
        assertDoesNotThrow(m::close);
    }

    /** A backend that records whether it was closed. */
    private static final class Tracking implements BlockBackend {
        final MemoryBackend inner = new MemoryBackend(1 << 20);
        volatile boolean closed;

        @Override public long size() { return inner.size(); }
        @Override public boolean readOnly() { return false; }
        @Override public void read(long o, byte[] b, int off, int len) throws IOException { inner.read(o, b, off, len); }
        @Override public void write(long o, byte[] b, int off, int len) throws IOException { inner.write(o, b, off, len); }
        @Override public void flush() { }
        @Override public void close() { closed = true; }
    }

    @Test
    void closeClosesDiskBackends() throws Exception {
        Tracking disk = new Tracking();
        MachineConfig cfg = new MachineConfig().ram(256L << 20).firmware(new byte[] {0x48, 0, 0, 0})
                .logSink(e -> { });
        cfg.addDisk(ScsiDisk.Type.DISK, disk);
        Machine m = new Machine(cfg);
        assertFalse(disk.closed);
        m.close();
        assertTrue(disk.closed);
    }

    @Test
    void failedConstructionClosesWhatItOpened() {
        Tracking disk = new Tracking();
        MachineConfig cfg = new MachineConfig().ram(256L << 20).firmware(new byte[] {0x48, 0, 0, 0})
                .logSink(e -> { });
        cfg.addDisk(ScsiDisk.Type.DISK, disk);
        cfg.addDisk(Path.of("/nonexistent/javapower-test.img"));
        assertThrows(IOException.class, () -> new Machine(cfg));
        assertTrue(disk.closed, "the first disk was left open");
    }
}
