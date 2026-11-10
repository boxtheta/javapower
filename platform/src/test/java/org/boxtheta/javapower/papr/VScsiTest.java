package org.boxtheta.javapower.papr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The v-scsi adapter end to end: TCE mapping, CRQ registration and initialisation, SRP login, MAD
 * management datagrams, and SCSI commands with direct and indirect descriptors against a
 * {@link MemoryBackend}, as QEMU's hw/scsi/spapr_vscsi.c handles them.
 */
class VScsiTest {
    static final int REG = 0x71000002;
    static final int LIOBN = REG;
    static final long LUN0 = 0x8000000000000000L; // SLOF/Linux LUN encoding of target 0 LUN 0

    // Guest real layout; ioba == real address - RA_BASE through a linear TCE mapping.
    static final long RA_BASE = 0x1000000;
    static final long CRQ_IOBA = 0x0;
    static final long IU_IOBA = 0x1000;
    static final long DATA_IOBA = 0x10000;

    final Rig r;
    final MemoryBackend disk;
    int crqNext;

    VScsiTest() {
        disk = new MemoryBackend(4096 * 64);
        for (int i = 0; i < disk.data().length; i++) {
            disk.data()[i] = (byte) (i * 7 + (i >>> 12));
        }
        MachineConfig c = new MachineConfig().ram(256L << 20);
        c.addDisk(ScsiDisk.Type.DISK, disk).blockSize(4096);
        r = new Rig(c);
    }

    void mapLinear(int pages) {
        for (int i = 0; i < pages; i++) {
            assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, LIOBN, i * 4096L, (RA_BASE + i * 4096L) | 3));
        }
    }

    long ra(long ioba) {
        return RA_BASE + ioba;
    }

    /** Register and initialise the CRQ, as the drivers do. */
    void bringUp() {
        mapLinear(64);
        assertEquals(H.SUCCESS, r.hcall(H.REG_CRQ, REG, CRQ_IOBA, 4096));
        assertEquals(H.RESOURCE, r.hcall(H.REG_CRQ, REG, CRQ_IOBA, 4096));
        assertEquals(H.SUCCESS, r.hcall(H.ENABLE_CRQ, REG));
        assertEquals(H.SUCCESS, r.hcall(H.SEND_CRQ, REG, 0xc001000000000000L, 0));
        byte[] e = nextCrq();
        assertEquals((byte) 0xc0, e[0]);
        assertEquals(0x02, e[1]);
    }

    /** Take the next entry of the response queue and mark it free. */
    byte[] nextCrq() {
        long a = ra(CRQ_IOBA) + crqNext;
        byte[] e = r.readBytes(a, 16);
        assertTrue((e[0] & 0x80) != 0, "no CRQ entry at " + crqNext);
        r.mem.write8(a, 0);
        crqNext = (crqNext + 16) % 4096;
        return e;
    }

    /** Send an IU (written at IU_IOBA) and return the response CRQ entry. */
    byte[] sendIu(byte[] iu, int format) {
        r.writeBytes(ra(IU_IOBA), iu);
        long hi = (0x80L << 56) | ((long) format << 48) | (iu.length & 0xffffL);
        assertEquals(H.SUCCESS, r.hcall(H.SEND_CRQ, REG, hi, IU_IOBA));
        byte[] e = nextCrq();
        assertEquals((byte) 0x80, e[0]);
        assertEquals(format, e[1]);
        assertEquals(0, e[3], "CRQ status");
        return e;
    }

    byte[] responseIu(byte[] crq) {
        int len = ((crq[6] & 0xff) << 8) | (crq[7] & 0xff);
        return r.readBytes(ra(IU_IOBA), len);
    }

    static byte[] login(long tag) {
        ByteBuffer b = ByteBuffer.allocate(64);
        b.put(0, (byte) 0);
        b.putLong(8, tag);
        b.putInt(16, 256);
        b.putShort(24, (short) 6);
        return b.array();
    }

    /** SRP_CMD with a direct descriptor (data in when !out). */
    static byte[] cmd(long tag, long lun, byte[] cdb, boolean out, long va, int len) {
        ByteBuffer b = ByteBuffer.allocate(48 + (len > 0 ? 16 : 0));
        b.put(0, (byte) SRP_CMD);
        b.put(1, (byte) 0);
        if (len > 0) {
            b.put(5, (byte) (out ? 0x10 : 0x01));
            b.put(out ? 6 : 7, (byte) 1);
        }
        b.putLong(8, tag);
        b.putLong(20, lun);
        b.put(32, cdb);
        if (len > 0) {
            b.putLong(48, va);
            b.putInt(60, len);
        }
        return b.array();
    }

    static final int SRP_CMD = 2;

    /** SRP_CMD with an indirect descriptor table held in the IU. */
    static byte[] cmdIndirect(long tag, long lun, byte[] cdb, boolean out, long[] vas, int[] lens) {
        int n = vas.length;
        ByteBuffer b = ByteBuffer.allocate(48 + 20 + 16 * n);
        b.put(0, (byte) SRP_CMD);
        b.put(5, (byte) (out ? 0x20 : 0x02));
        b.put(out ? 6 : 7, (byte) n);
        b.putLong(8, tag);
        b.putLong(20, lun);
        b.put(32, cdb);
        int total = 0;
        for (int l : lens) {
            total += l;
        }
        // table_desc: the list itself (va unused when all descriptors are local), then total len.
        b.putLong(48, 0);
        b.putInt(60, 16 * n);
        b.putInt(64, total);
        for (int i = 0; i < n; i++) {
            b.putLong(68 + 16 * i, vas[i]);
            b.putInt(68 + 16 * i + 12, lens[i]);
        }
        return b.array();
    }

    record Rsp(int status, int flags, int resIn, int resOut, byte[] sense, long tag) {}

    Rsp srp(byte[] iu) {
        byte[] crq = sendIu(iu, VScsi.FORMAT_SRP);
        byte[] rsp = responseIu(crq);
        ByteBuffer b = ByteBuffer.wrap(rsp);
        assertEquals((byte) 0xc1, rsp[0]);
        assertEquals(1, b.getInt(4)); // req_lim_delta
        assertArrayEquals(Arrays.copyOfRange(iu, 8, 16), Arrays.copyOfRange(crq, 8, 16)); // tag in CRQ
        int senseLen = b.getInt(28);
        byte[] sense = (rsp[18] & VScsi.RSP_FLAG_SNSVALID) != 0 ? Arrays.copyOfRange(rsp, 36, 36 + senseLen) : null;
        return new Rsp(rsp[19] & 0xff, rsp[18] & 0xff, b.getInt(24), b.getInt(20), sense, b.getLong(8));
    }

    static byte[] cdb(int... b) {
        byte[] c = new byte[16];
        for (int i = 0; i < b.length; i++) {
            c[i] = (byte) b[i];
        }
        return c;
    }

    @Test
    void fullExchange() {
        bringUp();
        // SRP login.
        byte[] crq = sendIu(login(0x1122334455667788L), VScsi.FORMAT_SRP);
        assertEquals(52, ((crq[6] & 0xff) << 8) | (crq[7] & 0xff));
        ByteBuffer lr = ByteBuffer.wrap(responseIu(crq));
        assertEquals((byte) 0xc0, lr.get(0));
        assertEquals(22, lr.getInt(4));
        assertEquals(0x1122334455667788L, lr.getLong(8));
        assertEquals(256, lr.getInt(16));
        assertEquals(256, lr.getInt(20));
        assertEquals(6, lr.getShort(24));

        // INQUIRY works despite the pending power-on unit attention.
        Rsp rsp = srp(cmd(1, LUN0, cdb(0x12, 0, 0, 0, 96, 0), false, DATA_IOBA, 96));
        assertEquals(0, rsp.status);
        assertEquals(0, rsp.flags);
        byte[] inq = r.readBytes(ra(DATA_IOBA), 36);
        assertEquals(0, inq[0]);
        assertEquals("QEMU    QEMU HARDDISK   2.5+", new String(inq, 8, 28, StandardCharsets.US_ASCII));

        // The first other command reports the unit attention with autosense.
        rsp = srp(cmd(2, LUN0, cdb(0x00), false, 0, 0));
        assertEquals(2, rsp.status);
        assertNotNull(rsp.sense);
        assertEquals(18, rsp.sense.length);
        assertEquals(0x70, rsp.sense[0]);
        assertEquals(6, rsp.sense[2]);
        assertEquals(0x29, rsp.sense[12]);
        rsp = srp(cmd(3, LUN0, cdb(0x00), false, 0, 0));
        assertEquals(0, rsp.status);

        // READ CAPACITY(10).
        rsp = srp(cmd(4, LUN0, cdb(0x25), false, DATA_IOBA, 8));
        assertEquals(0, rsp.status);
        ByteBuffer cap = ByteBuffer.wrap(r.readBytes(ra(DATA_IOBA), 8));
        assertEquals(63, cap.getInt(0));
        assertEquals(4096, cap.getInt(4));

        // WRITE(10) of LBAs 5-6 from two indirect descriptors on different pages.
        byte[] payload = new byte[8192];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (0xa5 ^ i);
        }
        r.writeBytes(ra(0x20000), Arrays.copyOfRange(payload, 0, 3000));
        r.writeBytes(ra(0x28000), Arrays.copyOfRange(payload, 3000, 8192));
        rsp = srp(cmdIndirect(5, LUN0, cdb(0x2a, 0, 0, 0, 0, 5, 0, 0, 2, 0), true,
                new long[] {0x20000, 0x28000}, new int[] {3000, 5192}));
        assertEquals(0, rsp.status);
        assertEquals(0, rsp.resOut);
        assertArrayEquals(payload, Arrays.copyOfRange(disk.data(), 5 * 4096, 7 * 4096));

        // READ(10) them back through a direct descriptor.
        rsp = srp(cmd(6, LUN0, cdb(0x28, 0, 0, 0, 0, 5, 0, 0, 2, 0), false, 0x30000, 8192));
        assertEquals(0, rsp.status);
        assertArrayEquals(payload, r.readBytes(ra(0x30000), 8192));

        // READ(16) into a buffer that is too small: data-in overflow residual.
        rsp = srp(cmd(7, LUN0, cdb(0x88, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0), false, 0x38000, 4096));
        assertEquals(0, rsp.status);
        assertEquals(VScsi.RSP_FLAG_DIOVER, rsp.flags);
        assertEquals(4096, rsp.resIn);
        assertArrayEquals(Arrays.copyOfRange(disk.data(), 4096, 8192), r.readBytes(ra(0x38000), 4096));

        // Reading past the end: CHECK CONDITION, LBA out of range.
        rsp = srp(cmd(8, LUN0, cdb(0x28, 0, 0, 0, 0, 63, 0, 0, 2, 0), false, 0x30000, 8192));
        assertEquals(2, rsp.status);
        assertEquals(5, rsp.sense[2]);
        assertEquals(0x21, rsp.sense[12]);
    }

    @Test
    void reportLunsAndMissingTargets() {
        bringUp();
        Rsp rsp = srp(cmd(1, 0, cdb(0xa0, 0, 0, 0, 0, 0, 0, 0, 1, 0), false, DATA_IOBA, 256));
        assertEquals(0, rsp.status);
        ByteBuffer d = ByteBuffer.wrap(r.readBytes(ra(DATA_IOBA), 16));
        assertEquals(8, d.getInt(0));
        assertEquals(0L, d.getLong(8));

        // Target 5 does not exist: INQUIRY answers "no device here", other commands fail.
        long t5 = 0x8500000000000000L;
        rsp = srp(cmd(2, t5, cdb(0x12, 0, 0, 0, 36, 0), false, DATA_IOBA, 36));
        assertEquals(0, rsp.status);
        byte[] inq = r.readBytes(ra(DATA_IOBA), 36);
        assertEquals(0x7f, inq[0]);
        assertEquals("QEMU EMPTY      ", new String(inq, 16, 16, StandardCharsets.US_ASCII));
        rsp = srp(cmd(3, t5, cdb(0x00), false, 0, 0));
        assertEquals(2, rsp.status);
        assertEquals((byte) 0xf0, rsp.sense[0]);
        assertEquals(5, rsp.sense[2]);
        assertEquals(0x24, rsp.sense[12]);

        // Consume the power-on unit attention, which would otherwise be reported first.
        assertEquals(2, srp(cmd(9, LUN0, cdb(0x00), false, 0, 0)).status);
        // LUN 3 on target 0 reaches the target, which answers for a missing LUN.
        long lun3 = 0x8003000000000000L;
        rsp = srp(cmd(4, lun3, cdb(0x12, 0, 0, 0, 36, 0), false, DATA_IOBA, 36));
        assertEquals(0, rsp.status);
        assertEquals(0x7f, r.mem.read8(ra(DATA_IOBA)));
        rsp = srp(cmd(5, lun3, cdb(0x00), false, 0, 0));
        assertEquals(2, rsp.status);
        assertEquals(0x25, rsp.sense[12]);
    }

    @Test
    void lunDecoding() {
        assertEquals(new VScsi.Address(0, 0, 0), VScsi.decodeLun(0x8000000000000000L));
        assertEquals(new VScsi.Address(1, 3, 2), VScsi.decodeLun((0x2L << 62) | (3L << 56) | (1L << 53) | (2L << 48)));
        assertEquals(new VScsi.Address(0, 0, 7), VScsi.decodeLun(0x0007000000000000L));
        assertEquals(new VScsi.Address(0, 0, 0x123), VScsi.decodeLun(0x4123000000000000L));
        assertEquals(new VScsi.Address(2, 5, 1), VScsi.decodeLun(0x0205000100000000L));
        assertEquals(-1, VScsi.decodeLun(0xc101000000000000L).lun());
    }

    @Test
    void managementDatagrams() {
        bringUp();
        // Adapter info.
        ByteBuffer mad = ByteBuffer.allocate(24);
        mad.putInt(0, VScsi.MAD_ADAPTER_INFO).putShort(6, (short) 148).putLong(8, 0x77).putLong(16, DATA_IOBA);
        byte[] crq = sendIu(mad.array(), VScsi.FORMAT_MAD);
        assertEquals(24, crq[7]);
        ByteBuffer rsp = ByteBuffer.wrap(responseIu(crq));
        assertEquals(0, rsp.getShort(4));
        ByteBuffer info = ByteBuffer.wrap(r.readBytes(ra(DATA_IOBA), 148));
        assertEquals("16.a", new String(info.array(), 0, 4, StandardCharsets.US_ASCII));
        assertEquals("qemu", new String(info.array(), 8, 4, StandardCharsets.US_ASCII));
        assertEquals(1, info.getInt(108));
        assertEquals(2, info.getInt(112));
        assertEquals(4096 << 9, info.getInt(116));

        // Capabilities: flags and support fields are cleared, the rest is echoed back.
        byte[] cap = new byte[100];
        Arrays.fill(cap, (byte) 0x33);
        r.writeBytes(ra(DATA_IOBA), cap);
        mad = ByteBuffer.allocate(24);
        mad.putInt(0, VScsi.MAD_CAPABILITIES).putShort(6, (short) 100).putLong(16, DATA_IOBA);
        crq = sendIu(mad.array(), VScsi.FORMAT_MAD);
        ByteBuffer c = ByteBuffer.wrap(r.readBytes(ra(DATA_IOBA), 100));
        assertEquals(0, c.getInt(0));
        assertEquals(0x33333333, c.getInt(4));
        assertEquals(0, c.getShort(74));
        assertEquals(0, c.getInt(76));
        assertEquals(0, c.getInt(88));
        assertEquals(0, c.getLong(92)); // zeroed beyond the known structure

        // Unsupported MAD types answer NOT SUPPORTED.
        mad = ByteBuffer.allocate(24);
        mad.putInt(0, VScsi.MAD_HOST_CONFIG);
        crq = sendIu(mad.array(), VScsi.FORMAT_MAD);
        assertEquals(24, crq[7]);
        assertEquals((short) 0xf1, ByteBuffer.wrap(responseIu(crq)).getShort(4));
    }

    @Test
    void taskManagement() {
        bringUp();
        ByteBuffer t = ByteBuffer.allocate(48);
        t.put(0, (byte) 1).put(1, (byte) 0x06).putLong(8, 9).putLong(20, LUN0).put(30, (byte) 0x08);
        byte[] crq = sendIu(t.array(), VScsi.FORMAT_SRP);
        assertEquals(40, crq[7]);
        byte[] rsp = responseIu(crq);
        assertEquals((byte) 0xc1, rsp[0]);
        assertEquals(VScsi.RSP_FLAG_RSPVALID, rsp[18]);
        assertEquals(4, ByteBuffer.wrap(rsp).getInt(32));
        assertEquals(0, rsp[39]);
        assertEquals(1, rsp[1]); // solicited notification for success requested
        // The LUN reset raises a unit attention.
        assertTrue(((ScsiDisk) r.m.vscsi().targets().get(0)).unitAttentionPending());
        t.put(30, (byte) 0x40);
        assertEquals(4, responseIu(sendIu(t.array(), VScsi.FORMAT_SRP))[39]);
        t.putLong(20, 0x8700000000000000L).put(30, (byte) 0x01);
        assertEquals(2, responseIu(sendIu(t.array(), VScsi.FORMAT_SRP))[39]);
    }

    @Test
    void interruptsAndDmaFaults() {
        mapLinear(64);
        r.hcall(H.REG_CRQ, REG, CRQ_IOBA, 4096);
        r.rtas("ibm,set-xive", 1, 0x1102, 0, 5);
        r.hcall(H.CPPR, 0xff);
        assertEquals(H.PARAMETER, r.hcall(H.VIO_SIGNAL, REG, 2));
        assertEquals(H.PARAMETER, r.hcall(H.VIO_SIGNAL, 0x71000001, 1)); // the vty takes no modes
        r.hcall(H.SEND_CRQ, REG, 0xc001000000000000L, 0);
        assertFalse(r.cpu.extIrq); // interrupts not enabled yet
        nextCrq();
        assertEquals(H.SUCCESS, r.hcall(H.VIO_SIGNAL, REG, 1));
        r.hcall(H.SEND_CRQ, REG, 0xc001000000000000L, 0);
        assertTrue(r.cpu.extIrq);
        nextCrq();

        // An IU at an unmapped address is dropped without a response.
        r.hcall(H.SEND_CRQ, REG, (0x80L << 56) | (1L << 48) | 64, 0x200000);
        assertEquals(0, r.mem.read8(ra(CRQ_IOBA) + crqNext));
        // A data buffer outside the mapping: CHECK CONDITION, hardware error.
        r.hcall(H.XIRR);
        Rsp rsp = srp(cmd(1, LUN0, cdb(0x12, 0, 0, 0, 36, 0), false, 0x300000, 36));
        assertEquals(2, rsp.status);
        assertEquals(4, rsp.sense[2]);

        // Freeing and quiescing.
        assertEquals(H.SUCCESS, r.hcall(H.FREE_CRQ, REG));
        assertEquals(H.SUCCESS, r.hcall(H.REG_CRQ, REG, CRQ_IOBA, 4096));
        assertEquals(H.PARAMETER, r.hcall(H.REG_CRQ, REG, 0x100, 4096));
        assertEquals(H.PARAMETER, r.hcall(H.REG_CRQ, 0x71000009, 0, 4096));
        assertEquals(H.NOT_FOUND, r.hcall(H.REG_CRQ, 0x71000001, 0, 4096));
        assertEquals(0, r.rtas("quiesce", 1)[0]);
        assertFalse(r.m.vscsi().crqRegistered());
        assertEquals(H.SUCCESS, r.hcall(H.GET_TCE, LIOBN, 0));
        assertEquals(0, r.r(4));
    }

    @Test
    void tceHypercalls() {
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, LIOBN, 0x5123, 0x7000003));
        assertEquals(H.SUCCESS, r.hcall(H.GET_TCE, LIOBN, 0x5fff));
        assertEquals(0x7000003L, r.r(4));
        assertEquals(H.PARAMETER, r.hcall(H.PUT_TCE, LIOBN, 0x10000000, 3));
        assertEquals(H.PARAMETER, r.hcall(H.GET_TCE, LIOBN, 0x10000000));
        assertEquals(H.PARAMETER, r.hcall(H.PUT_TCE, 0x71000001, 0, 3));
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, 0x80000000L, 0x3fffffffL, 0x1003)); // the PHB's window
        assertEquals(H.PARAMETER, r.hcall(H.PUT_TCE, 0x80000000L, 0x40000000L, 0x1003));

        assertEquals(H.SUCCESS, r.hcall(H.STUFF_TCE, LIOBN, 0x10000, 0x9000001, 4));
        r.hcall(H.GET_TCE, LIOBN, 0x13000);
        assertEquals(0x9000001L, r.r(4));
        assertEquals(H.PARAMETER, r.hcall(H.STUFF_TCE, LIOBN, 0, 0, 0x10001));
        assertEquals(H.PARAMETER, r.hcall(H.STUFF_TCE, LIOBN, 0xffff000, 0, 2)); // runs off the end

        for (int i = 0; i < 3; i++) {
            r.mem.write64be(0x40000 + 8 * i, 0xa000003L + 0x1000L * i);
        }
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE_INDIRECT, LIOBN, 0x20000, 0x40000, 3));
        r.hcall(H.GET_TCE, LIOBN, 0x22000);
        assertEquals(0xa002003L, r.r(4));
        assertEquals(H.PARAMETER, r.hcall(H.PUT_TCE_INDIRECT, LIOBN, 0x20000, 0x40008, 1));
        assertEquals(H.PARAMETER, r.hcall(H.PUT_TCE_INDIRECT, LIOBN, 0x20000, 0x40000, 513));

        // Permissions and bypass.
        TceTable t = r.m.vscsi().tce();
        assertEquals(H.SUCCESS, r.hcall(H.PUT_TCE, LIOBN, 0x1000, 0x5000001)); // device may only read
        assertEquals(0x5000123L, t.translate(0x1123, false));
        assertEquals(-1L, t.translate(0x1123, true));
        assertEquals(-1L, t.translate(0x8000, false));
        assertEquals(0, r.rtas("ibm,set-tce-bypass", 1, REG, 1)[0]);
        assertEquals(0x8000L, t.translate(0x8000, true));
        assertEquals(-3, r.rtas("ibm,set-tce-bypass", 1, 0x71000001, 1)[0]);
        // Quiesce clears the table but keeps bypass (QEMU resets the table only).
        r.rtas("quiesce", 1);
        assertTrue(t.bypass());
        r.m.reset(r.cpu);
        assertFalse(t.bypass());
    }

    @Test
    void persistentAndSnapshotBackends() throws Exception {
        java.nio.file.Path f = java.nio.file.Files.createTempFile("vscsi", ".img");
        try {
            byte[] orig = new byte[512 * 32];
            Arrays.fill(orig, (byte) 0x42);
            java.nio.file.Files.write(f, orig);
            // Default: snapshot. Guest writes are visible to the guest but never reach the file.
            MachineConfig c = new MachineConfig().ram(256L << 20);
            c.addDisk(f);
            Rig s = new Rig(c);
            ScsiDisk d = (ScsiDisk) s.m.vscsi().targets().get(0);
            assertTrue(d.backend() instanceof SnapshotBackend);
            byte[] w = new byte[512];
            Arrays.fill(w, (byte) 0x99);
            d.backend().write(512, w, 0, 512);
            byte[] back = new byte[512];
            d.backend().read(512, back, 0, 512);
            assertArrayEquals(w, back);
            s.m.close();
            assertArrayEquals(orig, java.nio.file.Files.readAllBytes(f));
            // Persistent: writes go to the file.
            MachineConfig p = new MachineConfig().ram(256L << 20);
            p.addDisk(f).persistent(true);
            Rig ps = new Rig(p);
            ScsiDisk pd = (ScsiDisk) ps.m.vscsi().targets().get(0);
            pd.backend().write(0, w, 0, 512);
            ps.m.close();
            assertEquals((byte) 0x99, java.nio.file.Files.readAllBytes(f)[0]);
        } finally {
            java.nio.file.Files.deleteIfExists(f);
        }
    }
}
