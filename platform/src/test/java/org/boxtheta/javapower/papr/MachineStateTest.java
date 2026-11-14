package org.boxtheta.javapower.papr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.boxtheta.javapower.core.StateFormatException;
import org.boxtheta.javapower.core.StateInput;
import org.boxtheta.javapower.core.StateMismatchException;
import org.boxtheta.javapower.core.StateOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Saving and loading the platform part of a machine: hypervisor, XICS, RTAS and devices. */
class MachineStateTest {
    static final int VTY = XicsVtyTest.VTY;
    static final int VTY_IRQ = XicsVtyTest.VTY_IRQ;
    static final int VSCSI = VioDevice.REG_BASE + 2;

    static MachineConfig cfg() {
        MachineConfig c = new MachineConfig().ram(256L << 20)
                .clock(Clock.fixed(Instant.parse("2026-11-10T12:34:56Z"), ZoneOffset.UTC));
        c.addDisk(ScsiDisk.Type.DISK, new MemoryBackend(1 << 20));
        c.addDisk(ScsiDisk.Type.CDROM, new MemoryBackend(new byte[4096], true)).scsiId(3);
        return c;
    }

    static byte[] save(Machine m) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        StateOutput out = new StateOutput(bo);
        m.saveState(out);
        out.finish();
        return bo.toByteArray();
    }

    static void load(Machine m, byte[] b) throws IOException {
        StateInput in = new StateInput(new ByteArrayInputStream(b));
        m.loadState(in);
        in.finish();
    }

    /** Put something into every part of the platform state a guest can change. */
    static void dirty(Rig r) {
        // XICS: the vty interrupt unmasked and pending, an IPI queued behind it at a lower priority.
        assertArrayEquals(new int[] {0}, r.rtas("ibm,set-xive", 1, VTY_IRQ, 0, 5));
        assertEquals(H.SUCCESS, r.hcall(H.CPPR, 0xff));
        assertEquals(H.SUCCESS, r.hcall(H.IPI, 0, 7));
        // vty: 16 bytes in the device buffer, more in the backlog.
        r.console.type("0123456789abcdefghijklmnopqrstuvwxyz");
        // v-scsi: TCEs, a CRQ, interrupts on.
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, VSCSI, 0x3000, 0x123000 | 3));
        assertEquals(H.SUCCESS, r.hcall(H.REG_CRQ, VSCSI, 0x0, 0x1000));
        assertEquals(H.SUCCESS, r.hcall(H.VIO_SIGNAL, VSCSI, 1));
        // NVRAM, time of day, VPA, process table.
        r.writeBytes(0x30000, "nvram!".getBytes());
        assertEquals(0, r.rtas("nvram-store", 2, 100, 0x30000, 6)[0]);
        assertArrayEquals(new int[] {0}, r.rtas("set-time-of-day", 1, 2001, 2, 3, 4, 5, 6, 0));
        long vpa = 0x40000;
        r.mem.write16be(vpa + 4, 640);
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_VPA, 0x0000200000000000L, 0, vpa));
        assertEquals(H.SUCCESS, r.hcall(H.REGISTER_PROC_TBL, 0x1c, 0x50000, 0, 0));
    }

    @Test
    void everyPartRoundTrips() throws IOException {
        Rig a = new Rig(cfg());
        dirty(a);
        byte[] state = save(a.m);

        Rig b = new Rig(cfg());
        load(b.m, state);
        assertArrayEquals(state, save(b.m), "saved again, the state is unchanged");

        // The restored machine behaves like the original.
        for (Rig r : new Rig[] {a, b}) {
            assertTrue(r.cpu.extIrq, "the pending interrupt drives the CPU's input");
            assertEquals(H.SUCCESS, r.hcall(H.XIRR));
            assertEquals(0xff000000L | VTY_IRQ, r.r(4));
            assertEquals(H.SUCCESS, r.hcall(H.GET_TERM_CHAR, VTY));
            assertEquals("0123456789abcdef", XicsVtyTest.chars(r));
            assertEquals(H.SUCCESS, r.hcall(H.GET_TERM_CHAR, VTY));
            assertEquals("ghijklmnopqrstuv", XicsVtyTest.chars(r));
            assertEquals(H.SUCCESS, r.hcall(H.GET_TCE, VSCSI, 0x3000));
            assertEquals(0x123003L, r.r(4));
            assertEquals(0x50000L | Machine.PATE1_GR, r.m.patbEntry());
            assertEquals(0x40000, r.m.vpaAddress());
            assertArrayEquals(new int[] {0, 2001, 2, 3, 4, 5, 6, 0}, r.rtas("get-time-of-day", 8));
            // The refilled buffer raised the (rejected) vty interrupt again: resent at the EOI,
            // and only after its own EOI does the less favoured IPI come through.
            assertEquals(H.SUCCESS, r.hcall(H.EOI, 0xff000000L | VTY_IRQ));
            assertEquals(H.SUCCESS, r.hcall(H.XIRR));
            assertEquals(0xff000000L | VTY_IRQ, r.r(4));
            assertEquals(H.SUCCESS, r.hcall(H.EOI, 0xff000000L | VTY_IRQ));
            assertTrue(r.cpu.extIrq, "the IPI is presented after the EOI");
            assertEquals(H.SUCCESS, r.hcall(H.XIRR));
            assertEquals(0xff000000L | Xics.IPI, r.r(4));
        }
        assertEquals("nvram!", new String(b.m.nvram().contents(), 100, 6));
        assertArrayEquals(a.m.fdtBlob(), b.m.fdtBlob());
        assertArrayEquals(save(a.m), save(b.m), "both behaved the same");
    }

    @Test
    void inputTypedBeforeTheLoadFollowsTheSavedInput() throws IOException {
        Rig a = new Rig(cfg());
        a.console.type("saved");
        byte[] state = save(a.m);
        Rig b = new Rig(cfg());
        b.console.type("+new");
        load(b.m, state);
        assertEquals(H.SUCCESS, b.hcall(H.GET_TERM_CHAR, VTY));
        assertEquals("saved+new", XicsVtyTest.chars(b));
    }

    @Test
    void armedWatchdogsKeepTheirTimeLeft() throws IOException {
        Rig a = new Rig(cfg());
        assertEquals(H.SUCCESS, a.hcall(H.WATCHDOG, (1 << 8) | 1, 2, 60_000)); // start #2: power off in 60 s
        byte[] state = save(a.m);
        Rig b = new Rig(cfg());
        load(b.m, state);
        b.m.pump();
        assertEquals(Machine.ShutdownReason.NONE, b.m.shutdownReason());
        assertEquals(H.SUCCESS, b.hcall(H.WATCHDOG, 2 << 8, 2), "watchdog 2 is armed");
        assertEquals(H.NOOP, b.hcall(H.WATCHDOG, 2 << 8, 1), "watchdog 1 is not");
    }

    @Test
    void nvramFileIsRewrittenFromTheState(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("nvram.bin");
        Files.write(f, new byte[16384]);
        Rig a = new Rig(new MachineConfig().ram(256L << 20).nvram(f));
        a.writeBytes(0x30000, "saved".getBytes());
        a.rtas("nvram-store", 2, 0, 0x30000, 5);
        byte[] state = save(a.m);
        a.m.close();
        Path g = dir.resolve("other.bin");
        Files.write(g, new byte[16384]);
        Rig b = new Rig(new MachineConfig().ram(256L << 20).nvram(g));
        load(b.m, state);
        b.m.close();
        assertEquals("saved", new String(Files.readAllBytes(g), 0, 5));
    }

    @Test
    void otherHardwareIsRefusedBeforeAnythingChanges() throws IOException {
        Rig a = new Rig(cfg());
        dirty(a);
        byte[] state = save(a.m);

        MachineConfig other = new MachineConfig().ram(512L << 20).cpus(2).rng(true);
        other.addDisk(ScsiDisk.Type.DISK, new MemoryBackend(2 << 20)).blockSize(4096);
        Rig b = new Rig(other);
        byte[] before = save(b.m);
        StateMismatchException e = assertThrows(StateMismatchException.class, () -> load(b.m, state));
        String m = e.getMessage();
        for (String want : new String[] {"CPUs: saved 1, this machine 2", "bytes of RAM: saved 268435456",
                "spapr-rng: saved false, this machine true", "SCSI devices: saved 2, this machine 1",
                "SCSI device 0: block size: saved 512, this machine 4096", "SCSI device 0: bytes: saved 1048576"}) {
            assertTrue(m.contains(want), want + " in: " + m);
        }
        assertArrayEquals(before, save(b.m));
        assertFalse(b.cpu.extIrq);
    }

    // ---- removable media -----------------------------------------------------------------------

    /** A machine with a fixed disk, a removable disk drive and a CD-ROM drive, the last two as given. */
    record Drives(Rig rig, ScsiDisk floppy, ScsiDisk cd) {
        static Drives of(BlockBackend floppyMedium, BlockBackend cdMedium) {
            MachineConfig c = new MachineConfig().ram(256L << 20);
            c.addDisk(ScsiDisk.Type.DISK, new MemoryBackend(1 << 20));
            MachineConfig.Disk f = floppyMedium == null ? c.addRemovableDisk() : c.addRemovableDisk(floppyMedium);
            MachineConfig.Disk d = c.addDisk(ScsiDisk.Type.CDROM, cdMedium);
            Rig r = new Rig(c);
            Drives v = new Drives(r, r.m.disk(f), r.m.disk(d));
            // The guest has seen the power-on unit attentions.
            assertEquals(0x29, ScsiDiskTest.run(v.floppy, ScsiDiskTest.TUR).asc());
            assertEquals(0x29, ScsiDiskTest.run(v.cd, ScsiDiskTest.TUR).asc());
            return v;
        }
    }

    static MemoryBackend floppy(int blocks) {
        return ScsiDiskTest.medium(blocks, blocks, false);
    }

    static MemoryBackend iso(int blocks) {
        return new MemoryBackend(new byte[2048 * blocks], true);
    }

    static final int[] GESN = {0x4a, 1, 0, 0, 0x10, 0, 0, 0, 8, 0};

    @Test
    void theSameMediaLoadWithoutAWordToTheGuest() throws IOException {
        Drives a = Drives.of(floppy(8), iso(100));
        byte[] state = save(a.rig.m);
        Drives b = Drives.of(floppy(8), iso(100));
        load(b.rig.m, state);
        assertArrayEquals(state, save(b.rig.m));
        assertEquals(0, ScsiDiskTest.run(b.floppy, ScsiDiskTest.TUR).status());
        assertEquals(0, ScsiDiskTest.run(b.cd, ScsiDiskTest.TUR).status());
        assertEquals(0, ScsiDiskTest.run(b.cd, GESN).data()[4]);
    }

    @Test
    void whatTheGuestHasNotSeenOfAMediumChangeIsSaved() throws IOException {
        Drives a = Drives.of(null, iso(100));
        assertEquals(0, ScsiDiskTest.run(a.floppy, 0x1e, 0, 0, 0, 1, 0).status()); // PREVENT
        a.floppy.changeMedium(floppy(8));
        a.cd.changeMedium(iso(200));
        byte[] state = save(a.rig.m);

        Drives b = Drives.of(floppy(8), iso(200));
        load(b.rig.m, state);
        assertArrayEquals(state, save(b.rig.m), "saved again, the state is unchanged");
        assertTrue(b.floppy.removalPrevented());
        ScsiDiskTest.assertMediumChanged(ScsiDiskTest.run(b.floppy, ScsiDiskTest.TUR));
        assertEquals(0, ScsiDiskTest.run(b.floppy, ScsiDiskTest.TUR).status());
        // The CD-ROM's media event and unit attention, each once.
        assertEquals(2, ScsiDiskTest.run(b.cd, GESN).data()[4]);
        assertEquals(0, ScsiDiskTest.run(b.cd, GESN).data()[4]);
        ScsiDiskTest.assertMediumChanged(ScsiDiskTest.run(b.cd, ScsiDiskTest.TUR));
        assertEquals(0, ScsiDiskTest.run(b.cd, ScsiDiskTest.TUR).status());

        // A guest eject is state as well.
        ScsiDiskTest.assertMediumChanged(ScsiDiskTest.run(a.floppy, ScsiDiskTest.TUR));
        assertEquals(0, ScsiDiskTest.run(a.floppy, 0x1e, 0, 0, 0, 0, 0).status()); // ALLOW
        assertEquals(0, ScsiDiskTest.run(a.floppy, ScsiDiskTest.EJECT).status());
        Drives c = Drives.of(floppy(8), iso(200));
        load(c.rig.m, save(a.rig.m));
        assertTrue(c.floppy.ejected());
        ScsiDiskTest.assertNoMedium(ScsiDiskTest.run(c.floppy, ScsiDiskTest.TUR));
    }

    @Test
    void aStateLoadsWithOtherMediaOrNone() throws IOException {
        Drives a = Drives.of(floppy(8), iso(100));
        byte[] state = save(a.rig.m);

        // Other media: a change the guest is told of, with the new sizes from then on.
        Drives b = Drives.of(floppy(16), iso(300));
        load(b.rig.m, state);
        ScsiDiskTest.assertMediumChanged(ScsiDiskTest.run(b.floppy, ScsiDiskTest.READ_CAPACITY));
        assertArrayEquals(new byte[] {0, 0, 0, 15, 0, 0, 2, 0},
                ScsiDiskTest.run(b.floppy, ScsiDiskTest.READ_CAPACITY).data());
        assertEquals(2, ScsiDiskTest.run(b.cd, GESN).data()[4]);
        ScsiDiskTest.assertMediumChanged(ScsiDiskTest.run(b.cd, ScsiDiskTest.TUR));

        // The same size with other write protection is another medium too.
        Drives w = Drives.of(ScsiDiskTest.medium(8, 8, true), iso(100));
        load(w.rig.m, state);
        ScsiDiskTest.assertMediumChanged(ScsiDiskTest.run(w.floppy, ScsiDiskTest.TUR));
        assertEquals(0, ScsiDiskTest.run(w.cd, ScsiDiskTest.TUR).status());

        // Empty drives: the media are gone, which needs no unit attention.
        Drives e = Drives.of(null, null);
        load(e.rig.m, state);
        ScsiDiskTest.assertNoMedium(ScsiDiskTest.run(e.floppy, ScsiDiskTest.TUR));
        ScsiDiskTest.assertNoMedium(ScsiDiskTest.run(e.cd, ScsiDiskTest.TUR));
        assertEquals(3, ScsiDiskTest.run(e.cd, GESN).data()[4]);

        // Saved empty, loaded with media in the drives.
        byte[] empty = save(Drives.of(null, null).rig.m);
        Drives f = Drives.of(floppy(8), iso(100));
        load(f.rig.m, empty);
        ScsiDiskTest.assertMediumChanged(ScsiDiskTest.run(f.floppy, ScsiDiskTest.TUR));
        ScsiDiskTest.assertMediumChanged(ScsiDiskTest.run(f.cd, ScsiDiskTest.TUR));
        // And empty again: nothing changed.
        Drives g = Drives.of(null, null);
        load(g.rig.m, empty);
        assertArrayEquals(empty, save(g.rig.m));
        assertEquals(0, ScsiDiskTest.run(g.cd, GESN).data()[4]);

        // A medium put in while the guest had the drive ejected ends the eject.
        assertEquals(0, ScsiDiskTest.run(a.floppy, ScsiDiskTest.EJECT).status());
        Drives h = Drives.of(floppy(32), iso(100));
        load(h.rig.m, save(a.rig.m));
        assertFalse(h.floppy.ejected());
        ScsiDiskTest.assertMediumChanged(ScsiDiskTest.run(h.floppy, ScsiDiskTest.TUR));
    }

    @Test
    void theDriveIsHardwareAndTheFixedDisksMediumToo() throws IOException {
        Drives a = Drives.of(floppy(8), iso(100));
        byte[] state = save(a.rig.m);

        // A fixed disk where the removable drive was, of the medium's very size.
        MachineConfig other = new MachineConfig().ram(256L << 20);
        other.addDisk(ScsiDisk.Type.DISK, new MemoryBackend(2 << 20));
        other.addDisk(ScsiDisk.Type.DISK, floppy(8));
        other.addDisk(ScsiDisk.Type.CDROM, iso(100));
        Rig b = new Rig(other);
        byte[] before = save(b.m);
        StateMismatchException e = assertThrows(StateMismatchException.class, () -> load(b.m, state));
        String m = e.getMessage();
        for (String want : new String[] {"SCSI device 0: bytes: saved 1048576, this machine 2097152",
                "SCSI device 1: type: saved REMOVABLE, this machine DISK"}) {
            assertTrue(m.contains(want), want + " in: " + m);
        }
        assertFalse(m.contains("SCSI device 2"), m);
        assertArrayEquals(before, save(b.m));

        // The drive's block size is the drive's.
        MachineConfig big = new MachineConfig().ram(256L << 20);
        big.addDisk(ScsiDisk.Type.DISK, new MemoryBackend(1 << 20));
        big.addRemovableDisk().blockSize(4096);
        big.addDisk(ScsiDisk.Type.CDROM, iso(100));
        e = assertThrows(StateMismatchException.class, () -> load(new Rig(big).m, state));
        assertTrue(e.getMessage().contains("SCSI device 1: block size: saved 512, this machine 4096"), e.getMessage());
    }

    @Test
    void corruptMediaEventsAreRefused() throws IOException {
        Drives a = Drives.of(floppy(8), iso(100));
        a.cd.changeMedium(iso(200));
        byte[] state = save(a.rig.m);
        // The event byte of the CD-ROM is the last byte of the PAPR record's payload: an event
        // code that does not exist, with the record's checksum made right again.
        StateInput in = new StateInput(new ByteArrayInputStream(state));
        in.record("PHW ");
        int phw = 12 + 12 + recordLength(state, 12);
        int len = recordLength(state, phw);
        assertEquals(2, state[phw + 8 + len - 1]);
        state[phw + 8 + len - 1] = 9;
        java.util.zip.CRC32C crc = new java.util.zip.CRC32C();
        crc.update(state, phw, 8 + len);
        java.nio.ByteBuffer.wrap(state).putInt(phw + 8 + len, (int) crc.getValue());
        Drives b = Drives.of(floppy(8), iso(200));
        StateFormatException e = assertThrows(StateFormatException.class, () -> load(b.rig.m, state));
        assertTrue(e.getMessage().contains("media events"), e.getMessage());
    }

    /** The payload length of the record that starts at {@code at}. */
    static int recordLength(byte[] state, int at) {
        return java.nio.ByteBuffer.wrap(state).getInt(at + 4);
    }

    @Test
    void corruptPlatformRecordsAreRefused() throws IOException {
        Rig a = new Rig(cfg());
        dirty(a);
        byte[] state = save(a.m);
        for (int cut : new int[] {20, state.length / 2, state.length - 5}) {
            byte[] c = java.util.Arrays.copyOf(state, cut);
            assertThrows(StateFormatException.class, () -> load(new Rig(cfg()).m, c));
        }
    }
}
