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
import org.boxtheta.javapower.core.CpuState;
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
    void removableMediaBelongToWhoeverHoldsThem() throws Exception {
        Tracking first = new Tracking();
        Tracking second = new Tracking();
        Tracking third = new Tracking();
        MachineConfig cfg = new MachineConfig().ram(256L << 20).firmware(new byte[] {0x48, 0, 0, 0})
                .logSink(e -> { });
        MachineConfig.Disk fixed = cfg.addDisk(ScsiDisk.Type.DISK, new Tracking());
        MachineConfig.Disk floppy = cfg.addRemovableDisk(first);
        MachineConfig.Disk empty = cfg.addRemovableDisk();
        MachineConfig.Disk cd = cfg.addDisk(ScsiDisk.Type.CDROM, null);
        assertThrows(IllegalArgumentException.class, () -> cfg.addDisk(ScsiDisk.Type.DISK, null));
        assertThrows(NullPointerException.class, () -> cfg.addRemovableDisk((BlockBackend) null));

        Machine m = new Machine(cfg);
        ScsiDisk drive = m.disk(floppy);
        assertEquals(ScsiDisk.Type.REMOVABLE, drive.type());
        assertEquals(512, drive.blockSize());
        assertEquals(first, drive.backend());
        assertEquals(ScsiDisk.Type.DISK, m.disk(fixed).type());
        assertFalse(m.disk(empty).hasMedium());
        assertEquals(ScsiDisk.Type.CDROM, m.disk(cd).type());
        assertFalse(m.disk(cd).hasMedium());
        // The drives are targets 0 to 3 in the order they were added.
        assertEquals(java.util.List.of(m.disk(fixed), drive, m.disk(empty), m.disk(cd)), m.vscsi().targets());
        assertEquals(1, drive.id());
        assertThrows(IllegalArgumentException.class,
                () -> m.disk(new MachineConfig().addRemovableDisk()), "another configuration's disk");

        // A medium taken out is the caller's: the machine does not close it, then or later.
        assertEquals(first, drive.changeMedium(second));
        assertFalse(first.closed);
        m.close();
        assertFalse(first.closed);
        assertTrue(second.closed, "the medium in the drive at close() was left open");
        // A closed machine's drives are empty and take no medium.
        assertFalse(drive.hasMedium());
        assertEquals(null, drive.changeMedium(null));
        assertThrows(IllegalStateException.class, () -> drive.changeMedium(third));
        assertFalse(third.closed);
    }

    @Test
    void missingFirmwareNamesTheArtifact() {
        MachineConfig cfg = new MachineConfig().ram(256L << 20).logSink(e -> { });
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new Machine(cfg));
        assertTrue(e.getMessage().contains(MachineConfig.SLOF_ARTIFACT), e.getMessage());
    }

    @Test
    void firmwareBytesAreCopiedAndReplaceAPath() throws Exception {
        byte[] fw = {0x48, 0, 0, 0};
        MachineConfig cfg = new MachineConfig().ram(256L << 20).logSink(e -> { })
                .firmware(Path.of("/nonexistent/javapower-firmware.bin")).firmware(fw);
        try (Machine m = new Machine(cfg)) {
            fw[0] = 0;
            CpuState c = new CpuState();
            m.attachCpu(c);
            m.reset(c);
            assertEquals(0x48000000, m.memory().read32be(0));
        }
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
