package org.boxtheta.javapower.papr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
