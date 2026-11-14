package org.boxtheta.javapower.papr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The SCSI disk and CD-ROM command set, with responses as QEMU's hw/scsi/scsi-disk.c builds them. */
class ScsiDiskTest {
    /** Outcome of one command. */
    record Res(int status, byte[] sense, int xfer, byte[] data) {
        int key() {
            return sense == null ? -1 : sense[2] & 0xf;
        }

        int asc() {
            return sense == null ? -1 : sense[12] & 0xff;
        }
    }

    static Res run(ScsiTarget t, int lun, byte[] dataOut, int... cdbBytes) {
        byte[] cdb = new byte[Math.max(16, cdbBytes.length)];
        for (int i = 0; i < cdbBytes.length; i++) {
            cdb[i] = (byte) cdbBytes[i];
        }
        ByteArrayOutputStream in = new ByteArrayOutputStream();
        int[] outPos = {0};
        Res[] res = new Res[1];
        ScsiRequest.DataPath path = new ScsiRequest.DataPath() {
            @Override
            public int toInitiator(byte[] b, int off, int len) {
                in.write(b, off, len);
                return len;
            }

            @Override
            public int fromInitiator(byte[] b, int off, int len) {
                int n = Math.min(len, dataOut.length - outPos[0]);
                System.arraycopy(dataOut, outPos[0], b, off, n);
                outPos[0] += n;
                return n;
            }
        };
        t.submit(new ScsiRequest(lun, cdb, path, (st, sense, x) -> res[0] = new Res(st, sense, x, in.toByteArray())));
        return res[0];
    }

    static Res run(ScsiTarget t, int... cdb) {
        return run(t, 0, new byte[0], cdb);
    }

    static ScsiDisk disk(int bs, int blocks) {
        MemoryBackend b = new MemoryBackend(bs * blocks);
        for (int i = 0; i < b.data().length; i++) {
            b.data()[i] = (byte) (i ^ (i >>> 9));
        }
        ScsiDisk d = ScsiDisk.disk(b, bs, "d0");
        assertEquals(6, run(d, 0x00).key()); // power-on unit attention
        return d;
    }

    static ScsiDisk cd(int blocks) {
        ScsiDisk d = ScsiDisk.cdrom(new MemoryBackend(new byte[2048 * blocks], true), "d0");
        run(d, 0x00);
        return d;
    }

    @Test
    void unitAttentionAndRequestSense() {
        ScsiDisk d = ScsiDisk.disk(new MemoryBackend(512 * 8), 512, "d0");
        Res r = run(d, 0x12, 0, 0, 0, 36, 0); // INQUIRY does not consume it
        assertEquals(0, r.status);
        r = run(d, 0x25);
        assertEquals(2, r.status);
        assertEquals(6, r.key());
        assertEquals(0x29, r.asc());
        assertEquals(0, run(d, 0x00).status);
        // An error's sense is kept for REQUEST SENSE, in fixed or descriptor format.
        r = run(d, 0xff);
        assertEquals(5, r.key());
        assertEquals(0x20, r.asc());
        r = run(d, 0x03, 0, 0, 0, 18, 0);
        assertEquals(0, r.status);
        assertEquals(18, r.data.length);
        assertEquals(0x70, r.data[0]);
        assertEquals(5, r.data[2]);
        assertEquals(0x20, r.data[12]);
        // Without saved sense: NO SENSE.
        r = run(d, 0x03, 1, 0, 0, 8, 0);
        assertArrayEquals(new byte[] {0x72, 0, 0, 0, 0, 0, 0, 0}, r.data);
    }

    @Test
    void inquiryAndVpd() {
        ScsiDisk d = disk(4096, 16);
        Res r = run(d, 0x12, 0, 0, 0, 96, 0);
        assertEquals(96, r.data.length); // the allocation length, zero padded
        assertEquals(0, r.data[0]);
        assertEquals(0, r.data[1]);
        assertEquals(5, r.data[2]);
        assertEquals(0x12, r.data[3]);
        assertEquals(91, r.data[4]);
        assertEquals(0x12, r.data[7]);
        assertEquals("QEMU    QEMU HARDDISK   2.5+", new String(r.data, 8, 28, StandardCharsets.US_ASCII));
        r = run(d, 0x12, 1, 0x00, 0, 255, 0);
        assertArrayEquals(new byte[] {0, 0, 0, 5, 0, (byte) 0x83, (byte) 0xb0, (byte) 0xb1, (byte) 0xb2},
                Arrays.copyOf(r.data, 9));
        r = run(d, 0x12, 1, 0x83, 0, 255, 0);
        assertArrayEquals(new byte[] {0, (byte) 0x83, 0, 6, 2, 0, 0, 2, 'd', '0'}, Arrays.copyOf(r.data, 10));
        r = run(d, 0x12, 1, 0xb0, 0, 255, 0);
        ByteBuffer bl = ByteBuffer.wrap(r.data);
        assertEquals(0x3c, bl.get(3));
        assertEquals(1, bl.get(4)); // WSNZ
        assertEquals(Integer.MAX_VALUE / 4096, bl.getInt(8));
        assertEquals((1 << 30) / 4096, bl.getInt(20));
        assertEquals(255, bl.getInt(24));
        assertEquals(1, bl.getInt(28));
        assertEquals(Integer.MAX_VALUE / 4096, bl.getInt(40));
        r = run(d, 0x12, 1, 0xb2, 0, 255, 0);
        assertArrayEquals(new byte[] {0, (byte) 0xb2, 0, 4, 0, (byte) 0xe0, 2, 0}, Arrays.copyOf(r.data, 8));
        r = run(d, 0x12, 1, 0xb1, 0, 255, 0);
        assertEquals(0x3c, r.data[3]);
        assertEquals(5, run(d, 0x12, 1, 0x80, 0, 255, 0).key()); // no serial configured
        assertEquals(0x24, run(d, 0x12, 0, 1, 0, 255, 0).asc());
    }

    @Test
    void readCapacity() {
        ScsiDisk d = disk(4096, 16);
        Res r = run(d, 0x25);
        assertArrayEquals(new byte[] {0, 0, 0, 15, 0, 0, 0x10, 0}, r.data);
        r = run(d, 0x9e, 0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 32, 0, 0);
        assertEquals(32, r.data.length);
        ByteBuffer b = ByteBuffer.wrap(r.data);
        assertEquals(15, b.getLong(0));
        assertEquals(4096, b.getInt(8));
        assertEquals(0, r.data[13]);
        assertEquals((byte) 0x80, r.data[14]); // TPE
        assertEquals(0x24, run(d, 0x25, 0, 0, 0, 0, 1, 0, 0, 0, 0).asc()); // LBA without PMI
        assertEquals(0x24, run(d, 0x9e, 0x11, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 32, 0, 0).asc());
        ScsiDisk big = ScsiDisk.disk(new MemoryBackend(512 * 64), 512, "x");
        run(big, 0);
        assertArrayEquals(new byte[] {0, 0, 0, 63, 0, 0, 2, 0}, run(big, 0x25).data);
    }

    @Test
    void readWriteAllSizes() {
        ScsiDisk d = disk(512, 512);
        MemoryBackend b = (MemoryBackend) d.backend();
        // READ(6) with transfer length 0 means 256 blocks.
        Res r = run(d, 0x08, 0, 0, 2, 0, 0);
        assertEquals(0, r.status);
        assertEquals(256 * 512, r.xfer);
        assertArrayEquals(Arrays.copyOfRange(b.data(), 1024, 1024 + 256 * 512), r.data);
        r = run(d, 0x28, 0, 0, 0, 1, 0, 0, 0, 3, 0);
        assertArrayEquals(Arrays.copyOfRange(b.data(), 256 * 512, 259 * 512), r.data);
        r = run(d, 0xa8, 0, 0, 0, 0, 7, 0, 0, 0, 1, 0, 0);
        assertArrayEquals(Arrays.copyOfRange(b.data(), 7 * 512, 8 * 512), r.data);
        r = run(d, 0x88, 0, 0, 0, 0, 0, 0, 0, 0, 9, 0, 0, 0, 1, 0, 0);
        assertArrayEquals(Arrays.copyOfRange(b.data(), 9 * 512, 10 * 512), r.data);

        byte[] w = new byte[1024];
        Arrays.fill(w, (byte) 0x5c);
        r = run(d, 0, w, 0x2a, 0, 0, 0, 0, 10, 0, 0, 2, 0);
        assertEquals(0, r.status);
        assertEquals(-1024, r.xfer);
        assertArrayEquals(w, Arrays.copyOfRange(b.data(), 10 * 512, 12 * 512));
        r = run(d, 0, w, 0x0a, 0, 0, 20, 1, 0);
        assertArrayEquals(Arrays.copyOf(w, 512), Arrays.copyOfRange(b.data(), 20 * 512, 21 * 512));
        r = run(d, 0, w, 0xaa, 0, 0, 0, 0, 30, 0, 0, 0, 2, 0, 0);
        assertEquals(0, r.status);
        r = run(d, 0, w, 0x8a, 0, 0, 0, 0, 0, 0, 0, 0, 40, 0, 0, 0, 2, 0, 0);
        assertEquals(0, r.status);
        assertArrayEquals(w, Arrays.copyOfRange(b.data(), 40 * 512, 42 * 512));

        assertEquals(0x21, run(d, 0x28, 0, 0, 0, 1, 0xff, 0, 0, 2, 0).asc());
        assertEquals(0x24, run(d, 0x28, 0x20, 0, 0, 0, 0, 0, 0, 1, 0).asc()); // RDPROTECT
        assertEquals(0x21, run(d, 0x2b, 0, 0, 0, 2, 0, 0, 0, 0, 0).asc()); // SEEK past the end
        assertEquals(0, run(d, 0x35).status); // SYNCHRONIZE CACHE
        assertEquals(1, b.flushes());
        assertEquals(0, run(d, 0x2f).status); // VERIFY
        assertEquals(0, run(d, 0x1b, 0, 0, 0, 1, 0).status); // START STOP: start
    }

    @Test
    void readOnlyAndFourKiB() {
        ScsiDisk ro = ScsiDisk.disk(new MemoryBackend(new byte[4096 * 4], true), 4096, "d0");
        run(ro, 0);
        Res r = run(ro, 0, new byte[4096], 0x2a, 0, 0, 0, 0, 0, 0, 0, 1, 0);
        assertEquals(7, r.key());
        assertEquals(0x27, r.asc());
        r = run(ro, 0x1a, 0, 0x3f, 0, 255, 0);
        assertEquals((byte) 0x80, r.data[2]); // WP in MODE SENSE(6)
        ScsiDisk d = disk(4096, 4);
        r = run(d, 0x28, 0, 0, 0, 0, 3, 0, 0, 1, 0);
        assertEquals(4096, r.data.length);
    }

    @Test
    void modeSense() {
        ScsiDisk d = disk(512, 4096);
        Res r = run(d, 0x1a, 0, 0x3f, 0, 255, 0);
        int len = (r.data[0] & 0xff) + 1;
        assertEquals(8, r.data[3]); // block descriptor length
        assertEquals(512 >>> 8, r.data[4 + 6]);
        // Pages 01, 04, 05, 08 (in page order) after the descriptor.
        int p = 12;
        int[] pages = new int[4];
        for (int i = 0; i < 4; i++) {
            pages[i] = r.data[p];
            p += 2 + (r.data[p + 1] & 0xff);
        }
        assertArrayEquals(new int[] {1, 4, 5, 8}, pages);
        assertEquals(len, p);
        assertEquals(4, r.data[p - 0x12]); // caching page: WCE
        r = run(d, 0x5a, 8, 0x08, 0, 0, 0, 0, 0, 255, 0); // MODE SENSE(10), DBD, caching page
        assertEquals(0, r.data[7]);
        assertEquals(8, r.data[8]);
        assertEquals(0x12, r.data[9]);
        assertEquals(0x39, run(d, 0x1a, 0, 0xc8, 0, 255, 0).asc()); // saved values
        assertEquals(0x24, run(d, 0x1a, 0, 0x2a, 0, 255, 0).asc()); // CD page on a disk
        assertEquals(0, run(d, 0, new byte[12], 0x15, 0x10, 0, 0, 12, 0).status); // MODE SELECT accepted
    }

    @Test
    void reportLunsAndOtherLuns() {
        ScsiDisk d = disk(512, 8);
        Res r = run(d, 0xa0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0);
        assertEquals(256, r.data.length);
        assertEquals(8, ByteBuffer.wrap(r.data).getInt(0));
        // A command for LUN 2 on this target: INQUIRY says "no LUN", others are not supported.
        r = run(d, 2, new byte[0], 0x12, 0, 0, 0, 36, 0);
        assertEquals(0x7f, r.data[0]);
        assertEquals(0x25, run(d, 2, new byte[0], 0x00).asc());
    }

    @Test
    void unmapAndWriteSame() {
        ScsiDisk d = disk(512, 64);
        MemoryBackend b = (MemoryBackend) d.backend();
        byte[] list = new byte[24];
        ByteBuffer.wrap(list).putShort(0, (short) 22).putShort(2, (short) 16).putLong(8, 4).putInt(16, 8);
        byte[] before = b.data().clone();
        assertEquals(0, run(d, 0, list, 0x42, 0, 0, 0, 0, 0, 0, 0, 24, 0).status);
        assertArrayEquals(before, b.data()); // data may stay (LBPRZ = 0)
        ByteBuffer.wrap(list).putLong(8, 60);
        assertEquals(0x21, run(d, 0, list, 0x42, 0, 0, 0, 0, 0, 0, 0, 24, 0).asc());
        assertEquals(0x1a, run(d, 0, new byte[4], 0x42, 0, 0, 0, 0, 0, 0, 0, 4, 0).asc());
        assertEquals(0x24, run(d, 0, list, 0x42, 1, 0, 0, 0, 0, 0, 0, 24, 0).asc());

        byte[] blk = new byte[512];
        Arrays.fill(blk, (byte) 0x77);
        assertEquals(0, run(d, 0, blk, 0x41, 0, 0, 0, 0, 10, 0, 0, 5, 0).status);
        for (int i = 10 * 512; i < 15 * 512; i++) {
            assertEquals(0x77, b.data()[i]);
        }
        assertEquals(0x24, run(d, 0, blk, 0x41, 0, 0, 0, 0, 10, 0, 0, 0, 0).asc()); // WSNZ
        assertEquals(0, run(d, 0, blk, 0x93, 8, 0, 0, 0, 0, 0, 0, 0, 20, 0, 0, 0, 1, 0, 0).status);
        assertEquals(0x77, b.data()[20 * 512]);
    }

    @Test
    void cdrom() {
        ScsiDisk c = cd(1000);
        Res r = run(c, 0x12, 0, 0, 0, 36, 0);
        assertEquals(5, r.data[0]);
        assertEquals((byte) 0x80, r.data[1]); // removable
        assertEquals("QEMU CD-ROM     ", new String(r.data, 16, 16, StandardCharsets.US_ASCII));
        assertArrayEquals(new byte[] {0, 0, 0x03, (byte) 0xe7, 0, 0, 0x08, 0}, run(c, 0x25).data);
        assertEquals(7, run(c, 0, new byte[2048], 0x2a, 0, 0, 0, 0, 0, 0, 0, 1, 0).key());
        // READ TOC, format 0, LBA form: one data track and the lead-out at 1000.
        r = run(c, 0x43, 0, 0, 0, 0, 0, 0, 0, 0xff, 0);
        assertEquals(0xff, r.data.length);
        ByteBuffer t = ByteBuffer.wrap(r.data);
        assertEquals(18, t.getShort(0));
        assertEquals(1, r.data[2]);
        assertEquals(1, r.data[3]);
        assertEquals(0x14, r.data[5]);
        assertEquals(0, t.getInt(8));
        assertEquals((byte) 0xaa, r.data[14]);
        assertEquals(1000, t.getInt(16));
        // GET CONFIGURATION: current profile CD-ROM (0x0008) for a small image.
        r = run(c, 0x46, 0, 0, 0, 0, 0, 0, 0, 64, 0);
        assertEquals(8, ByteBuffer.wrap(r.data).getShort(6));
        // GET EVENT STATUS NOTIFICATION (polled, media class).
        r = run(c, 0x4a, 1, 0, 0, 0x10, 0, 0, 0, 8, 0);
        assertEquals(4, r.data[2]);
        assertEquals(2, r.data[5]); // media present
        // MODE SENSE: CD capabilities page, no block descriptors.
        r = run(c, 0x5a, 0, 0x2a, 0, 0, 0, 0, 0, 255, 0);
        assertEquals(0, r.data[7]);
        assertEquals(0x2a, r.data[8]);
        // Eject: medium gone, then load again.
        assertEquals(0, run(c, 0x1b, 0, 0, 0, 2, 0).status);
        assertEquals(2, run(c, 0x28, 0, 0, 0, 0, 0, 0, 0, 1, 0).key());
        assertEquals(0x3a, run(c, 0x00).asc());
        assertEquals(0, run(c, 0x1b, 0, 0, 0, 3, 0).status);
        assertEquals(0, run(c, 0x00).status);
        // Locked tray cannot be ejected.
        assertEquals(0, run(c, 0x1e, 0, 0, 0, 1, 0).status);
        assertEquals(0x53, run(c, 0x1b, 0, 0, 0, 2, 0).asc());
        // Disk-only pages and VPD.
        assertEquals(5, run(c, 0x12, 1, 0xb0, 0, 255, 0).key());
        r = run(c, 0x12, 1, 0, 0, 255, 0);
        assertEquals(2, r.data[3]);
        assertFalse(r.data.length < 6);
    }

    @Test
    void dvdSizedImageReportsDvdProfile() {
        long blocks = (80L * 60 * 75 * 2048 / 512) / 4 + 10; // just above an 80-minute CD
        ScsiDisk c = ScsiDisk.cdrom(new SizedBackend(blocks * 2048), "d0");
        run(c, 0);
        Res r = run(c, 0x46, 0, 0, 0, 0, 0, 0, 0, 64, 0);
        assertEquals(0x10, ByteBuffer.wrap(r.data).getShort(6));
        r = run(c, 0xad, 0, 0, 0, 0, 0, 0, 0, 0, 0x08, 0x04, 0);
        assertEquals(0, r.status);
        assertTrue(r.data.length > 4);
    }

    // ---- removable media -----------------------------------------------------------------------

    static final int[] TUR = {0x00};
    static final int[] READ_CAPACITY = {0x25};
    static final int[] READ_BLOCK_0 = {0x28, 0, 0, 0, 0, 0, 0, 0, 1, 0};
    static final int[] WRITE_BLOCK_0 = {0x2a, 0, 0, 0, 0, 0, 0, 0, 1, 0};
    static final int[] EJECT = {0x1b, 0, 0, 0, 2, 0};
    static final int[] LOAD = {0x1b, 0, 0, 0, 3, 0};

    /** A medium of {@code blocks} 512-byte blocks, every byte {@code fill}. */
    static MemoryBackend medium(int blocks, int fill, boolean readOnly) {
        byte[] b = new byte[512 * blocks];
        Arrays.fill(b, (byte) fill);
        return new MemoryBackend(b, readOnly);
    }

    /** An empty drive whose power-on unit attention has been reported. */
    static ScsiDisk emptyDrive() {
        ScsiDisk d = ScsiDisk.removable(null, 512, "d0");
        assertEquals(0x29, run(d, TUR).asc());
        return d;
    }

    static void assertNoMedium(Res r) {
        assertEquals(2, r.status);
        assertEquals(ScsiRequest.KEY_NOT_READY, r.key());
        assertEquals(0x3a, r.asc());
        assertEquals(0, r.sense[13]);
    }

    static void assertMediumChanged(Res r) {
        assertEquals(2, r.status);
        assertEquals(ScsiRequest.KEY_UNIT_ATTENTION, r.key());
        assertEquals(0x28, r.asc());
        assertEquals(0, r.sense[13]);
    }

    @Test
    void removableDiskIsADiskWithTheRmbBit() {
        ScsiDisk d = ScsiDisk.removable(medium(16, 0, false), 512, "d0");
        run(d, TUR);
        Res r = run(d, 0x12, 0, 0, 0, 36, 0);
        assertEquals(0, r.data[0]); // direct access
        assertEquals((byte) 0x80, r.data[1]); // RMB
        assertEquals("QEMU    QEMU HARDDISK   2.5+", new String(r.data, 8, 28, StandardCharsets.US_ASCII));
        // The disk's VPD pages and mode pages, not the CD's.
        r = run(d, 0x12, 1, 0x00, 0, 255, 0);
        assertArrayEquals(new byte[] {0, 0, 0, 5, 0, (byte) 0x83, (byte) 0xb0, (byte) 0xb1, (byte) 0xb2},
                Arrays.copyOf(r.data, 9));
        assertEquals(0, run(d, 0x1a, 0, 0x04, 0, 255, 0).status);
        assertEquals(0x24, run(d, 0x1a, 0, 0x2a, 0, 255, 0).asc());
        assertEquals(0x24, run(d, 0x46, 0, 0, 0, 0, 0, 0, 0, 64, 0).asc()); // GET CONFIGURATION is MMC
        assertEquals(0x24, run(d, 0x4a, 1, 0, 0, 0x10, 0, 0, 0, 8, 0).asc()); // and so is GESN
        assertTrue(d.type().removable());
        assertTrue(ScsiDisk.Type.CDROM.removable());
        assertFalse(ScsiDisk.Type.DISK.removable());
    }

    @Test
    void emptyDriveIsNotReady() {
        ScsiDisk d = emptyDrive();
        assertFalse(d.hasMedium());
        assertEquals(0, d.blocks());
        assertNoMedium(run(d, TUR));
        assertNoMedium(run(d, READ_CAPACITY));
        assertNoMedium(run(d, 0x9e, 0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 32, 0, 0));
        assertNoMedium(run(d, READ_BLOCK_0));
        assertNoMedium(run(d, 0, new byte[512], WRITE_BLOCK_0));
        assertNoMedium(run(d, 0, new byte[512], 0x41, 0, 0, 0, 0, 0, 0, 0, 1, 0)); // WRITE SAME
        assertNoMedium(run(d, 0x35)); // SYNCHRONIZE CACHE
        // What a guest needs to find the drive works without a medium.
        Res r = run(d, 0x12, 0, 0, 0, 36, 0);
        assertEquals(0, r.status);
        assertEquals((byte) 0x80, r.data[1]);
        r = run(d, 0xa0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0); // REPORT LUNS
        assertEquals(0, r.status);
        assertEquals(8, ByteBuffer.wrap(r.data).getInt(0));
        r = run(d, 0x1a, 0, 0x3f, 0, 255, 0); // MODE SENSE: not write-protected, no block descriptor
        assertEquals(0, r.status);
        assertEquals(0, r.data[2]);
        assertEquals(0, r.data[3]);
        assertEquals(0, run(d, 0x1e, 0, 0, 0, 1, 0).status); // PREVENT
        assertEquals(0, run(d, 0x1e, 0, 0, 0, 0, 0).status); // ALLOW
        assertEquals(0, run(d, 0x1b, 0, 0, 0, 1, 0).status); // START
        // The sense of the last failure is there for REQUEST SENSE.
        assertNoMedium(run(d, TUR));
        r = run(d, 0x03, 0, 0, 0, 18, 0);
        assertEquals(2, r.data[2]);
        assertEquals(0x3a, r.data[12]);
    }

    @Test
    void loadedMediumIsAnnouncedOnceAndThenUsed() {
        ScsiDisk d = emptyDrive();
        MemoryBackend a = medium(8, 0xa1, false);
        assertEquals(null, d.changeMedium(a));
        assertTrue(d.hasMedium());
        // INQUIRY does not report the unit attention; the next command that can does, once.
        assertEquals(0, run(d, 0x12, 0, 0, 0, 36, 0).status);
        assertTrue(d.unitAttentionPending());
        assertMediumChanged(run(d, TUR));
        assertFalse(d.unitAttentionPending());
        assertEquals(0, run(d, TUR).status);
        assertArrayEquals(new byte[] {0, 0, 0, 7, 0, 0, 2, 0}, run(d, READ_CAPACITY).data);
        assertEquals(8, d.blocks());
        Res r = run(d, READ_BLOCK_0);
        assertEquals(512, r.data.length);
        assertEquals((byte) 0xa1, r.data[511]);
        byte[] w = new byte[512];
        Arrays.fill(w, (byte) 0x5c);
        assertEquals(0, run(d, 0, w, WRITE_BLOCK_0).status);
        assertEquals(0x5c, a.data()[0]);
        assertEquals((byte) 0xa1, a.data()[512]);
        assertEquals(0, run(d, 0x1a, 0, 0x3f, 0, 255, 0).data[2]); // not write-protected
    }

    @Test
    void changedMediumBringsItsOwnSizeAndProtection() {
        MemoryBackend a = medium(8, 0xa1, false);
        ScsiDisk d = ScsiDisk.removable(a, 512, "d0");
        run(d, TUR);
        assertArrayEquals(new byte[] {0, 0, 0, 7, 0, 0, 2, 0}, run(d, READ_CAPACITY).data);

        // A larger, write-protected medium in its place.
        MemoryBackend b = medium(2880, 0xb2, true);
        assertEquals(a, d.changeMedium(b));
        assertMediumChanged(run(d, READ_CAPACITY));
        assertArrayEquals(new byte[] {0, 0, 0x0b, 0x3f, 0, 0, 2, 0}, run(d, READ_CAPACITY).data);
        assertEquals((byte) 0xb2, run(d, READ_BLOCK_0).data[0]);
        Res r = run(d, 0, new byte[512], WRITE_BLOCK_0);
        assertEquals(ScsiRequest.KEY_DATA_PROTECT, r.key());
        assertEquals(0x27, r.asc());
        assertEquals((byte) 0x80, run(d, 0x1a, 0, 0x3f, 0, 255, 0).data[2]); // WP
        // Block 8 exists now; on the first medium it did not.
        assertEquals(0, run(d, 0x28, 0, 0, 0, 0, 8, 0, 0, 1, 0).status);

        // Emptied: the medium comes back to the caller, untouched since.
        assertEquals(b, d.changeMedium(null));
        assertNoMedium(run(d, TUR));
        assertEquals(null, d.changeMedium(null));
        assertFalse(d.unitAttentionPending());

        // The first medium again: block 8 is out of range again.
        assertEquals(null, d.changeMedium(a));
        assertMediumChanged(run(d, TUR));
        assertEquals(0x21, run(d, 0x28, 0, 0, 0, 0, 8, 0, 0, 1, 0).asc());
        // The same backend put in again counts as a change, and nothing comes out.
        assertEquals(null, d.changeMedium(a));
        assertMediumChanged(run(d, TUR));
        assertEquals(a, d.backend());

        // A medium that is not a whole number of blocks: the rest is out of reach.
        d.changeMedium(new MemoryBackend(new byte[512 * 3 + 100], false));
        assertMediumChanged(run(d, TUR));
        assertEquals(3, d.blocks());
        assertEquals(0x21, run(d, 0x28, 0, 0, 0, 0, 3, 0, 0, 1, 0).asc());
    }

    @Test
    void aMediumLoadedAndTakenOutUnseenLeavesNoUnitAttention() {
        ScsiDisk d = emptyDrive();
        MemoryBackend a = medium(8, 0, false);
        d.changeMedium(a);
        assertEquals(a, d.changeMedium(null));
        assertNoMedium(run(d, TUR));
    }

    @Test
    void aPendingResetUnitAttentionStandsForTheMediumChangeToo() {
        ScsiDisk d = ScsiDisk.removable(null, 512, "d0");
        d.changeMedium(medium(8, 0, false));
        assertEquals(0x29, run(d, TUR).asc());
        assertEquals(0, run(d, TUR).status);
        // And a reset after the change replaces its unit attention.
        d.changeMedium(medium(16, 0, false));
        d.reset();
        assertEquals(0x29, run(d, TUR).asc());
        assertEquals(0, run(d, TUR).status);
        assertEquals(16, d.blocks());
    }

    @Test
    void fixedDisksKeepTheirMedium() {
        ScsiDisk d = disk(512, 8);
        assertThrows(IllegalStateException.class, () -> d.changeMedium(medium(8, 0, false)));
        assertThrows(IllegalStateException.class, () -> d.changeMedium(null));
        assertTrue(d.hasMedium());
        assertThrows(IllegalArgumentException.class, () -> ScsiDisk.disk(null, 512, "d0"));
        // A guest's eject of a fixed disk does nothing, as before.
        assertEquals(0, run(d, EJECT).status);
        assertFalse(d.ejected());
        assertEquals(0, run(d, TUR).status);
    }

    @Test
    void guestEjectAndRemovalLock() {
        MemoryBackend a = medium(8, 0xa1, false);
        ScsiDisk d = ScsiDisk.removable(a, 512, "d0");
        run(d, TUR);
        int[] ejects = {0};
        d.onGuestEject(() -> ejects[0]++);

        // While the guest prevents removal its own eject fails; the embedder can see the lock.
        assertEquals(0, run(d, 0x1e, 0, 0, 0, 1, 0).status);
        assertTrue(d.removalPrevented());
        Res r = run(d, EJECT);
        assertEquals(ScsiRequest.KEY_ILLEGAL_REQUEST, r.key());
        assertEquals(0x53, r.asc());
        assertEquals(2, r.sense[13]);
        assertEquals(0, ejects[0]);
        assertFalse(d.ejected());
        assertEquals(0, run(d, 0x1e, 0, 0, 0, 0, 0).status);
        assertFalse(d.removalPrevented());

        // Ejected: the medium stays in the drive, out of the guest's reach, and the embedder is told.
        assertEquals(0, run(d, EJECT).status);
        assertEquals(1, ejects[0]);
        assertTrue(d.ejected());
        assertEquals(a, d.backend());
        assertNoMedium(run(d, TUR));
        assertNoMedium(run(d, READ_BLOCK_0));
        assertEquals(0, run(d, EJECT).status); // already out: no second notification
        assertEquals(1, ejects[0]);
        // Loaded again by the guest: there it is, with no unit attention.
        assertEquals(0, run(d, LOAD).status);
        assertFalse(d.ejected());
        assertEquals(0, run(d, TUR).status);
        assertEquals((byte) 0xa1, run(d, READ_BLOCK_0).data[0]);

        // A medium put in by the embedder ends the guest's eject.
        assertEquals(0, run(d, EJECT).status);
        assertEquals(2, ejects[0]);
        MemoryBackend b = medium(16, 0xb2, false);
        assertEquals(a, d.changeMedium(b));
        assertFalse(d.ejected());
        assertMediumChanged(run(d, TUR));
        assertEquals((byte) 0xb2, run(d, READ_BLOCK_0).data[0]);

        // The embedder's change does not ask the guest's lock.
        assertEquals(0, run(d, 0x1e, 0, 0, 0, 1, 0).status);
        assertEquals(b, d.changeMedium(null));
        assertNoMedium(run(d, TUR));
        assertTrue(d.removalPrevented());

        // A listener may take the medium out itself, and one that fails harms nothing.
        d.reset();
        run(d, TUR);
        d.changeMedium(a);
        run(d, TUR);
        d.onGuestEject(() -> assertEquals(a, d.changeMedium(null)));
        assertEquals(0, run(d, EJECT).status);
        assertFalse(d.hasMedium());
        assertEquals(0, run(d, LOAD).status);
        assertNoMedium(run(d, TUR));
        d.onGuestEject(() -> {
            throw new IllegalStateException("listener");
        });
        assertEquals(0, run(d, EJECT).status);
        assertTrue(d.ejected());
    }

    @Test
    void cdromMediumChangeAndMediaEvents() {
        ScsiDisk c = cd(1000);
        int[] gesn = {0x4a, 1, 0, 0, 0x10, 0, 0, 0, 8, 0};
        Res r = run(c, gesn);
        assertEquals(0, r.data[4]); // no event
        assertEquals(2, r.data[5]); // media present

        BlockBackend first = c.changeMedium(null);
        assertTrue(first != null);
        assertNoMedium(run(c, TUR));
        r = run(c, gesn);
        assertEquals(3, r.data[4]); // MediaRemoval, once
        assertEquals(0, r.data[5]); // no media, tray closed
        assertEquals(0, run(c, gesn).data[4]);

        c.changeMedium(new MemoryBackend(new byte[2048 * 500], true));
        // GESN neither reports nor clears the unit attention, and gives the event once.
        r = run(c, gesn);
        assertEquals(2, r.data[4]); // NewMedia
        assertEquals(2, r.data[5]);
        assertEquals(0, run(c, gesn).data[4]);
        assertMediumChanged(run(c, TUR));
        assertArrayEquals(new byte[] {0, 0, 0x01, (byte) 0xf3, 0, 0, 0x08, 0}, run(c, READ_CAPACITY).data);
        // READ TOC: the lead-out follows the new medium.
        r = run(c, 0x43, 0, 0, 0, 0, 0, 0, 0, 0xff, 0);
        assertEquals(500, ByteBuffer.wrap(r.data).getInt(16));
        // A writable backend does not make a CD-ROM writable.
        c.changeMedium(new MemoryBackend(2048 * 4));
        assertMediumChanged(run(c, TUR));
        assertEquals(7, run(c, 0, new byte[2048], 0x2a, 0, 0, 0, 0, 0, 0, 0, 1, 0).key());

        // While the guest has the tray open an event waits.
        run(c, gesn);
        assertEquals(0, run(c, EJECT).status);
        c.changeMedium(null);
        r = run(c, gesn);
        assertEquals(0, r.data[4]);
        assertEquals(1, r.data[5]); // tray open
        assertEquals(0, run(c, LOAD).status);
        r = run(c, gesn);
        assertEquals(3, r.data[4]);
        assertEquals(0, r.data[5]);

        // An empty CD-ROM drive from the start.
        ScsiDisk e = ScsiDisk.cdrom(null, "d1");
        run(e, TUR);
        assertNoMedium(run(e, TUR));
        assertEquals(0, ByteBuffer.wrap(run(e, 0x46, 0, 0, 0, 0, 0, 0, 0, 64, 0).data).getShort(6)); // no profile
        assertEquals(0, run(e, gesn).data[5]);
    }

    /** A medium whose reads wait until the test lets them go, and that counts its calls. */
    static final class SlowMedium implements BlockBackend {
        final java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        volatile boolean closed;
        volatile boolean usedAfterClose;

        private void call() {
            calls.incrementAndGet();
            if (closed) {
                usedAfterClose = true;
            }
        }

        @Override
        public long size() {
            call();
            return 512 * 8;
        }

        @Override
        public boolean readOnly() {
            call();
            return false;
        }

        @Override
        public void read(long offset, byte[] buf, int off, int len) throws java.io.IOException {
            call();
            reading.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                throw new java.io.IOException(e);
            }
            call(); // the command is still inside the backend when the wait ends
            Arrays.fill(buf, off, off + len, (byte) 0x11);
        }

        @Override
        public void write(long offset, byte[] buf, int off, int len) {
            call();
        }

        @Override
        public void flush() {
            call();
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    /**
     * A medium change from another thread waits for the command that is using the old medium,
     * and nothing touches the old medium once the change has returned.
     */
    @Test
    void mediumChangeWaitsForTheCommandInProgress() throws Exception {
        SlowMedium slow = new SlowMedium();
        ScsiDisk d = ScsiDisk.removable(slow, 512, "d0");
        run(d, TUR);
        Res[] read = new Res[1];
        Thread cpu = new Thread(() -> read[0] = run(d, READ_BLOCK_0), "guest-cpu");
        cpu.start();
        assertTrue(slow.reading.await(10, java.util.concurrent.TimeUnit.SECONDS), "the read never reached the backend");

        MemoryBackend next = medium(4, 0x22, false);
        BlockBackend[] out = new BlockBackend[1];
        Thread server = new Thread(() -> {
            out[0] = d.changeMedium(next);
            slow.close(); // what an embedder does with a medium it got back
        }, "server");
        server.start();
        // The change must not return while the read is inside the old medium.
        server.join(300);
        assertTrue(server.isAlive(), "the medium changed under a command in progress");
        assertFalse(slow.closed);

        slow.release.countDown();
        cpu.join(10_000);
        server.join(10_000);
        assertFalse(cpu.isAlive());
        assertFalse(server.isAlive());
        assertEquals(slow, out[0]);
        // The read finished on the old medium, with its data.
        assertEquals(0, read[0].status);
        assertEquals(0x11, read[0].data[0]);

        // From here on only the new medium is used.
        int calls = slow.calls.get();
        assertMediumChanged(run(d, TUR));
        assertEquals(0x22, run(d, READ_BLOCK_0).data[0]);
        assertArrayEquals(new byte[] {0, 0, 0, 3, 0, 0, 2, 0}, run(d, READ_CAPACITY).data);
        assertEquals(calls, slow.calls.get());
        assertFalse(slow.usedAfterClose);
    }

    /** Commands on one thread and medium changes on another: every command sees one whole medium. */
    @Test
    void commandsAndMediumChangesFromTwoThreads() throws Exception {
        ScsiDisk d = emptyDrive();
        MemoryBackend[] media = {medium(8, 1, false), medium(16, 2, false), medium(32, 3, false)};
        java.util.concurrent.atomic.AtomicBoolean stop = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread server = new Thread(() -> {
            try {
                java.util.Random rnd = new java.util.Random(7);
                while (!stop.get()) {
                    int k = rnd.nextInt(media.length + 1);
                    d.changeMedium(k == media.length ? null : media[k]);
                }
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "server");
        server.start();
        try {
            int reads = 0;
            for (int i = 0; i < 20_000 || reads < 100; i++) {
                assertTrue(i < 2_000_000, "the drive never had a medium for a command");
                // The last block of the smallest medium: within every medium.
                Res r = run(d, 0x28, 0, 0, 0, 0, 7, 0, 0, 1, 0);
                if (r.status == 0) {
                    reads++;
                    assertEquals(512, r.data.length);
                    byte fill = r.data[0];
                    assertTrue(fill >= 1 && fill <= 3, "fill " + fill);
                    for (byte b : r.data) {
                        assertEquals(fill, b);
                    }
                } else {
                    assertTrue(r.key() == ScsiRequest.KEY_NOT_READY && r.asc() == 0x3a
                            || r.key() == ScsiRequest.KEY_UNIT_ATTENTION && r.asc() == 0x28,
                            "sense key " + r.key() + " asc " + Integer.toHexString(r.asc()));
                }
            }
        } finally {
            stop.set(true);
            server.join(10_000);
        }
        assertEquals(null, failure.get());
    }

    /** A read-only backend of a given size that reads zeros. */
    static final class SizedBackend implements BlockBackend {
        final long size;

        SizedBackend(long size) {
            this.size = size;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public boolean readOnly() {
            return true;
        }

        @Override
        public void read(long offset, byte[] buf, int off, int len) {
            Arrays.fill(buf, off, off + len, (byte) 0);
        }

        @Override
        public void write(long offset, byte[] buf, int off, int len) throws java.io.IOException {
            throw new java.io.IOException("read-only");
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
