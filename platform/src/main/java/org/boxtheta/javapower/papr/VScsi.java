package org.boxtheta.javapower.papr;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The PAPR virtual SCSI host adapter ({@code v-scsi@...}, compatible "IBM,v-scsi"), following
 * QEMU's hw/scsi/spapr_vscsi.c: SRP information units travel through the CRQ, data moves by
 * TCE-translated DMA described by direct or indirect SRP memory descriptors, and VSCSI management
 * datagrams (adapter info, capabilities) are answered the way QEMU answers them.
 *
 * <p>Commands complete synchronously inside H_SEND_CRQ.
 */
public final class VScsi extends VioDevice {
    static final int SRP_MAX_IU_LEN = 256;
    /** SRP_MAX_IU_LEN minus sizeof(union srp_iu) (64, the login request). */
    static final int SRP_MAX_IU_DATA_LEN = SRP_MAX_IU_LEN - 64;
    static final int REQ_LIMIT = 24;
    static final int MAX_SECTORS = 4096;
    static final long REPORT_LUNS_WLUN = 0xc101000000000000L;

    // CRQ formats and statuses.
    static final int FORMAT_SRP = 0x01;
    static final int FORMAT_MAD = 0x02;
    static final int STATUS_OK = 0x00;
    static final int STATUS_ADAPTER_FAIL = 0x10;

    // SRP opcodes.
    static final int SRP_LOGIN_REQ = 0x00;
    static final int SRP_TSK_MGMT = 0x01;
    static final int SRP_CMD = 0x02;
    static final int SRP_LOGIN_RSP = 0xc0;
    static final int SRP_RSP = 0xc1;

    static final int SRP_BUF_FORMAT_DIRECT = 1 << 1;
    static final int SRP_BUF_FORMAT_INDIRECT = 1 << 2;
    static final int DESC_NONE = 0;
    static final int DESC_DIRECT = 1;
    static final int DESC_INDIRECT = 2;

    static final int RSP_FLAG_RSPVALID = 1;
    static final int RSP_FLAG_SNSVALID = 1 << 1;
    static final int RSP_FLAG_DOOVER = 1 << 2;
    static final int RSP_FLAG_DOUNDER = 1 << 3;
    static final int RSP_FLAG_DIOVER = 1 << 4;
    static final int RSP_FLAG_DIUNDER = 1 << 5;

    // MAD types.
    static final int MAD_EMPTY_IU = 0x01;
    static final int MAD_ERROR_LOG = 0x02;
    static final int MAD_ADAPTER_INFO = 0x03;
    static final int MAD_HOST_CONFIG = 0x04;
    static final int MAD_CAPABILITIES = 0x05;
    static final int MAD_NOT_SUPPORTED = 0xf1;

    // Task management functions and responses.
    static final int TSK_ABORT_TASK = 0x01;
    static final int TSK_ABORT_TASK_SET = 0x02;
    static final int TSK_CLEAR_TASK_SET = 0x04;
    static final int TSK_LUN_RESET = 0x08;
    static final int TSK_CLEAR_ACA = 0x40;
    static final int TSK_MGMT_COMPLETE = 0x00;
    static final int TSK_MGMT_FIELDS_INVALID = 0x02;
    static final int TSK_MGMT_NOT_SUPPORTED = 0x04;

    /** sizeof(struct capabilities) in QEMU's viosrp.h. */
    static final int CAPABILITIES_SIZE = 92;

    private final List<ScsiTarget> targets = new ArrayList<>();

    /** One request in flight: the CRQ entry, its IU and the descriptor walk state. */
    private final class Req {
        final byte[] crq;
        final byte[] iu = new byte[SRP_MAX_IU_LEN];
        boolean writing;
        boolean directionKnown;
        int dmaFmt;
        int localDesc;
        int totalDesc;
        int cdbOffset;
        int curDescNum;
        long curDescOffset;
        long transferred;
        int senseLen;
        byte[] sense = new byte[252];

        Req(byte[] crq) {
            this.crq = crq.clone();
        }

        long ioba() {
            return ByteBuffer.wrap(crq).getLong(8);
        }

        ByteBuffer bb() {
            return ByteBuffer.wrap(iu);
        }
    }

    public void addTarget(ScsiTarget t) {
        targets.add(t);
    }

    public List<ScsiTarget> targets() {
        return Collections.unmodifiableList(targets);
    }

    @Override
    public String dtName() {
        return "v-scsi";
    }

    @Override
    public String dtType() {
        return "vscsi";
    }

    @Override
    public String dtCompatible() {
        return "IBM,v-scsi";
    }

    @Override
    public long dmaWindowSize() {
        return 0x10000000L;
    }

    @Override
    public long signalMask() {
        return 1;
    }

    @Override
    public boolean supportsCrq() {
        return true;
    }

    @Override
    protected void addDeviceProps(Fdt.Node node) {
        node.setCell("#size-cells", 0);
        node.setCell("#address-cells", 2);
    }

    @Override
    protected void resetDevice() {
        for (ScsiTarget t : targets) {
            t.reset();
        }
    }

    // ---- CRQ entry point ---------------------------------------------------------------------

    @Override
    protected long handleCrq(byte[] crq) {
        int valid = crq[0] & 0xff;
        int format = crq[1] & 0xff;
        switch (valid) {
            case 0xc0 -> { // initialisation
                if (format == 0x01) {
                    byte[] r = new byte[16];
                    r[0] = (byte) 0xc0;
                    r[1] = 0x02;
                    sendCrq(r);
                }
            }
            case 0xff -> {
                // Transport event: not handled (as in QEMU).
            }
            case 0x80 -> {
                if (format == FORMAT_SRP || format == FORMAT_MAD) {
                    gotPayload(crq);
                } else {
                    PaprLog.once("vscsi-payload-format-" + format, "unsupported payload format");
                }
            }
            default -> PaprLog.once("vscsi-crq-" + Integer.toHexString(valid), "unknown CRQ");
        }
        return H.SUCCESS;
    }

    private void gotPayload(byte[] crq) {
        Req req = new Req(crq);
        int len = ((crq[6] & 0xff) << 8) | (crq[7] & 0xff);
        if (len > SRP_MAX_IU_LEN) {
            PaprLog.once("vscsi-iu-too-long", "SRP IU of " + len + " bytes");
            return;
        }
        if (!dmaRead(req.ioba(), req.iu, 0, len)) {
            PaprLog.once("vscsi-iu-dma", "DMA read failure");
            return;
        }
        if ((crq[1] & 0xff) == FORMAT_MAD) {
            handleMad(req);
        } else {
            handleSrp(req);
        }
    }

    /** Write the IU back to where the request came from and post the response CRQ. */
    private void sendIu(Req req, int length, int format) {
        boolean ok = dmaWrite(req.ioba(), req.iu, 0, length);
        if (!ok) {
            PaprLog.once("vscsi-send-iu", "DMA write failure");
        }
        byte[] r = new byte[16];
        r[0] = (byte) 0x80;
        r[1] = (byte) format;
        r[3] = (byte) (ok ? STATUS_OK : STATUS_ADAPTER_FAIL);
        r[6] = (byte) (length >>> 8);
        r[7] = (byte) length;
        System.arraycopy(req.iu, 8, r, 8, 8); // the SRP/MAD tag, in its memory byte order
        if (sendCrq(r) != 0) {
            PaprLog.once("vscsi-send-crq", "error sending response");
        }
    }

    // ---- management datagrams ----------------------------------------------------------------

    private void handleMad(Req req) {
        ByteBuffer bb = req.bb();
        int type = bb.getInt(0);
        int retlen;
        switch (type) {
            case MAD_ADAPTER_INFO -> {
                adapterInfo(req);
                return;
            }
            case MAD_CAPABILITIES -> {
                capabilities(req);
                return;
            }
            case MAD_EMPTY_IU -> retlen = 32;
            case MAD_ERROR_LOG, MAD_HOST_CONFIG -> retlen = 24;
            default -> {
                PaprLog.once("vscsi-mad-" + type, "unknown MAD type");
                retlen = 0;
            }
        }
        bb.putShort(4, (short) MAD_NOT_SUPPORTED);
        sendIu(req, retlen, FORMAT_MAD);
    }

    private void adapterInfo(Req req) {
        ByteBuffer bb = req.bb();
        int length = bb.getShort(6) & 0xffff;
        long buffer = bb.getLong(16);
        byte[] info = new byte[Math.max(length, 148)];
        byte[] ver = "16.a".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ver, 0, info, 0, ver.length);
        byte[] name = "qemu".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, info, 8, name.length);
        ByteBuffer ib = ByteBuffer.wrap(info);
        ib.putInt(104, 0); // partition_number
        ib.putInt(108, 1); // mad_version
        ib.putInt(112, 2); // os_type
        ib.putInt(116, MAX_SECTORS << 9); // port_max_txu[0]
        if (!dmaWrite(buffer, info, 0, length)) {
            PaprLog.once("vscsi-adapter-info", "DMA write failure");
        }
        // QEMU stores a 32-bit status into the 16-bit field; on a little-endian host that is 0.
        bb.putShort(4, (short) 0);
        sendIu(req, 24, FORMAT_MAD);
    }

    private void capabilities(Req req) {
        ByteBuffer bb = req.bb();
        int reqLen = bb.getShort(6) & 0xffff;
        int len = Math.min(reqLen, CAPABILITIES_SIZE);
        long buffer = bb.getLong(16);
        byte[] cap = new byte[CAPABILITIES_SIZE];
        if (!dmaRead(buffer, cap, 0, len)) {
            PaprLog.once("vscsi-capabilities", "DMA read failure");
        }
        ByteBuffer cb = ByteBuffer.wrap(cap);
        cb.putInt(0, 0); // flags
        cb.putShort(74, (short) 0); // migration.common.server_support
        cb.putInt(76, 0); // migration.ecl
        cb.putShort(86, (short) 0); // reserve.common.server_support
        cb.putInt(88, 0); // reserve.type
        if (!dmaWrite(buffer, cap, 0, len)) {
            PaprLog.once("vscsi-capabilities", "DMA write failure");
        }
        if (reqLen > len) {
            dmaFill(buffer + len, 0, reqLen - len);
        }
        bb.putShort(4, (short) 0);
        sendIu(req, 24, FORMAT_MAD);
    }

    // ---- SRP ---------------------------------------------------------------------------------

    private void handleSrp(Req req) {
        int opcode = req.iu[0] & 0xff;
        switch (opcode) {
            case SRP_LOGIN_REQ -> login(req);
            case SRP_TSK_MGMT -> taskManagement(req);
            case SRP_CMD -> command(req);
            default -> PaprLog.once("vscsi-srp-" + Integer.toHexString(opcode), "unsupported SRP opcode");
        }
    }

    private void login(Req req) {
        ByteBuffer bb = req.bb();
        long tag = bb.getLong(8);
        java.util.Arrays.fill(req.iu, 0, 52, (byte) 0);
        req.iu[0] = (byte) SRP_LOGIN_RSP;
        bb.putInt(4, REQ_LIMIT - 2);
        bb.putLong(8, tag);
        bb.putInt(16, SRP_MAX_IU_LEN);
        bb.putInt(20, SRP_MAX_IU_LEN);
        bb.putShort(24, (short) (SRP_BUF_FORMAT_DIRECT | SRP_BUF_FORMAT_INDIRECT));
        sendIu(req, 52, FORMAT_SRP);
    }

    /** Decoded SRP LUN: (channel, id, lun), lun = -1 for the well-known/extended format. */
    record Address(int channel, int id, int lun) {}

    static Address decodeLun(long srpLun) {
        int channel = 0;
        int id = 0;
        while (true) {
            switch ((int) (srpLun >>> 62)) {
                case 0 -> {
                    if ((srpLun >>> 56) != 0) {
                        channel = (int) (srpLun >>> 56) & 0x3f;
                        id = (int) (srpLun >>> 48) & 0xff;
                        srpLun <<= 16;
                        continue;
                    }
                    return new Address(channel, id, (int) (srpLun >>> 48) & 0xff);
                }
                case 1 -> {
                    return new Address(channel, id, (int) (srpLun >>> 48) & 0x3fff);
                }
                case 2 -> {
                    return new Address((int) (srpLun >>> 53) & 0x7, (int) (srpLun >>> 56) & 0x3f,
                            (int) (srpLun >>> 48) & 0x1f);
                }
                default -> {
                    return new Address(0, 0, -1);
                }
            }
        }
    }

    /** scsi_device_find: the exact LUN, or else the first device on that target. */
    private ScsiTarget find(Address a) {
        if (a.lun < 0) {
            return null;
        }
        ScsiTarget any = null;
        for (ScsiTarget t : targets) {
            if (t.channel() == a.channel && t.id() == a.id) {
                if (t.lun() == a.lun) {
                    return t;
                }
                if (any == null) {
                    any = t;
                }
            }
        }
        return any;
    }

    private void makeupSense(Req req, int key, int asc, int ascq) {
        java.util.Arrays.fill(req.sense, (byte) 0);
        req.senseLen = 18;
        req.sense[0] = (byte) 0xf0;
        req.sense[2] = (byte) key;
        req.sense[7] = 0xa;
        req.sense[12] = (byte) asc;
        req.sense[13] = (byte) ascq;
    }

    private void sendRsp(Req req, int status, long resIn, long resOut) {
        ByteBuffer bb = req.bb();
        long tag = bb.getLong(8);
        int solNot = req.iu[1] & 0xff;
        java.util.Arrays.fill(req.iu, 0, 36, (byte) 0);
        req.iu[0] = (byte) SRP_RSP;
        bb.putInt(4, 1);
        bb.putLong(8, tag);
        int flags = 0;
        if (resIn < 0) {
            flags |= RSP_FLAG_DIUNDER;
            resIn = -resIn;
        } else if (resIn != 0) {
            flags |= RSP_FLAG_DIOVER;
        }
        if (resOut < 0) {
            flags |= RSP_FLAG_DOUNDER;
            resOut = -resOut;
        } else if (resOut != 0) {
            flags |= RSP_FLAG_DOOVER;
        }
        bb.putInt(20, (int) resOut);
        bb.putInt(24, (int) resIn);
        req.iu[19] = (byte) status;
        int total = 36;
        if (status != 0) {
            req.iu[1] = (byte) ((solNot & 0x04) >> 2);
            if (req.senseLen > 0) {
                int n = Math.min(req.senseLen, SRP_MAX_IU_DATA_LEN);
                flags |= RSP_FLAG_SNSVALID;
                bb.putInt(28, n);
                System.arraycopy(req.sense, 0, req.iu, 36, n);
                total += n;
            }
        } else {
            req.iu[1] = (byte) ((solNot & 0x02) >> 1);
        }
        req.iu[18] = (byte) flags;
        sendIu(req, total, FORMAT_SRP);
    }

    private void command(Req req) {
        ByteBuffer bb = req.bb();
        long lun = bb.getLong(20); // srp_cmd/srp_tsk_mgmt: tag at 8, 4 reserved bytes, LUN at 20
        int addCdbLen = req.iu[31] & 0xff;
        int cdbLen = 16 + (addCdbLen & ~3);
        byte[] cdb = new byte[cdbLen];
        System.arraycopy(req.iu, 32, cdb, 0, Math.min(cdbLen, SRP_MAX_IU_LEN - 32));
        if ((lun == 0 || lun == REPORT_LUNS_WLUN) && (cdb[0] & 0xff) == ScsiDisk.REPORT_LUNS) {
            reportLuns(req);
            return;
        }
        Address a = decodeLun(lun);
        ScsiTarget t = find(a);
        if (t == null) {
            if ((cdb[0] & 0xff) == ScsiDisk.INQUIRY) {
                inquiryNoTarget(req, cdb);
            } else {
                makeupSense(req, ScsiRequest.KEY_ILLEGAL_REQUEST, 0x24, 0);
                sendRsp(req, ScsiRequest.CHECK_CONDITION, 0, 0);
            }
            return;
        }
        ScsiRequest.DataPath path = new ScsiRequest.DataPath() {
            @Override
            public int toInitiator(byte[] b, int off, int len) {
                return transfer(req, false, b, off, len);
            }

            @Override
            public int fromInitiator(byte[] b, int off, int len) {
                return transfer(req, true, b, off, len);
            }
        };
        t.submit(new ScsiRequest(a.lun, cdb, path, (status, sense, xferLen) -> complete(req, status, sense, xferLen)));
    }

    private void complete(Req req, int status, byte[] sense, int xferLen) {
        if (status == ScsiRequest.TRANSFER_ERROR) {
            PaprLog.once("vscsi-rdma", "RDMA error");
            makeupSense(req, ScsiRequest.KEY_HARDWARE_ERROR, 0, 0);
            sendRsp(req, ScsiRequest.CHECK_CONDITION, 0, 0);
            return;
        }
        long resIn = 0;
        long resOut = 0;
        if (status == ScsiRequest.CHECK_CONDITION && sense != null) {
            java.util.Arrays.fill(req.sense, (byte) 0);
            req.senseLen = Math.min(sense.length, req.sense.length);
            System.arraycopy(sense, 0, req.sense, 0, req.senseLen);
        }
        if (status == ScsiRequest.GOOD) {
            // Only overflows (data left over when the initiator's buffers ran out) are reported.
            long left = Math.abs((long) xferLen) - req.transferred;
            if (xferLen < 0) {
                resOut = left;
            } else {
                resIn = left;
            }
        }
        sendRsp(req, status, resIn, resOut);
    }

    private void inquiryNoTarget(Req req, byte[] cdb) {
        if ((cdb[1] & 0x01) != 0 || cdb[2] != 0) {
            makeupSense(req, ScsiRequest.KEY_ILLEGAL_REQUEST, 0x24, 0);
            sendRsp(req, ScsiRequest.CHECK_CONDITION, 0, 0);
            return;
        }
        int alen = ((cdb[3] & 0xff) << 8) | (cdb[4] & 0xff);
        int len = Math.min(alen, 36);
        byte[] d = new byte[36];
        d[0] = 0x7f;
        d[2] = 0x06;
        d[3] = 0x02;
        d[4] = 36 - 5;
        d[7] = 0x10;
        System.arraycopy("QEMU    ".getBytes(StandardCharsets.US_ASCII), 0, d, 8, 8);
        System.arraycopy("QEMU EMPTY      ".getBytes(StandardCharsets.US_ASCII), 0, d, 16, 16);
        int rc = transfer(req, false, d, 0, len);
        if (rc < 0) {
            makeupSense(req, ScsiRequest.KEY_HARDWARE_ERROR, 0, 0);
            sendRsp(req, ScsiRequest.CHECK_CONDITION, 0, 0);
        } else {
            sendRsp(req, 0, 36 - rc, 0);
        }
    }

    private void reportLuns(Req req) {
        boolean lun0 = false;
        int n = 0;
        for (ScsiTarget t : targets) {
            n += 8;
            if (t.channel() == 0 && t.id() == 0 && t.lun() == 0) {
                lun0 = true;
            }
        }
        if (!lun0) {
            n += 8;
        }
        int len = n + 8;
        byte[] d = new byte[len];
        ByteBuffer.wrap(d).putInt(0, n);
        int i = lun0 ? 8 : 16;
        for (ScsiTarget t : targets) {
            d[i] = (byte) (t.id() == 0 && t.channel() == 0 ? 0 : 2 << 6);
            d[i] |= (byte) t.id();
            d[i + 1] = (byte) ((t.channel() << 5) | t.lun());
            i += 8;
        }
        int rc = transfer(req, false, d, 0, len);
        if (rc < 0) {
            makeupSense(req, ScsiRequest.KEY_HARDWARE_ERROR, 0, 0);
            sendRsp(req, ScsiRequest.CHECK_CONDITION, 0, 0);
        } else {
            sendRsp(req, 0, len - rc, 0);
        }
    }

    private void taskManagement(Req req) {
        ByteBuffer bb = req.bb();
        long tag = bb.getLong(8);
        int solNot = req.iu[1] & 0xff;
        long lun = bb.getLong(20); // srp_cmd/srp_tsk_mgmt: tag at 8, 4 reserved bytes, LUN at 20
        int func = req.iu[30] & 0xff;
        Address a = decodeLun(lun);
        ScsiTarget t = find(a);
        int resp = TSK_MGMT_COMPLETE;
        if (t == null) {
            resp = TSK_MGMT_FIELDS_INVALID;
        } else {
            switch (func) {
                case TSK_ABORT_TASK, TSK_ABORT_TASK_SET, TSK_CLEAR_TASK_SET -> {
                    // Requests complete synchronously, so there is never anything to abort.
                    if (t.lun() != a.lun) {
                        resp = TSK_MGMT_FIELDS_INVALID;
                    }
                }
                case TSK_LUN_RESET -> {
                    if (t.lun() != a.lun) {
                        resp = TSK_MGMT_FIELDS_INVALID;
                    } else {
                        t.reset();
                    }
                }
                case TSK_CLEAR_ACA -> resp = TSK_MGMT_NOT_SUPPORTED;
                default -> resp = TSK_MGMT_FIELDS_INVALID;
            }
        }
        java.util.Arrays.fill(req.iu, 0, 40, (byte) 0);
        req.iu[0] = (byte) SRP_RSP;
        bb.putInt(4, 1);
        bb.putLong(8, tag);
        req.iu[18] = RSP_FLAG_RSPVALID;
        bb.putInt(32, 4);
        req.iu[1] = (byte) (resp != 0 ? (solNot & 0x04) >> 2 : (solNot & 0x02) >> 1);
        req.iu[19] = 0;
        req.iu[39] = (byte) resp;
        sendIu(req, 40, FORMAT_SRP);
    }

    // ---- SRP memory descriptors --------------------------------------------------------------

    private static int dataOutDescSize(byte[] iu) {
        int fmt = (iu[5] & 0xff) >>> 4;
        return switch (fmt) {
            case DESC_DIRECT -> 16;
            case DESC_INDIRECT -> 20 + 16 * (iu[6] & 0xff);
            default -> 0;
        };
    }

    private boolean preprocess(Req req, boolean writing) {
        byte[] iu = req.iu;
        req.writing = writing;
        req.directionKnown = true;
        req.cdbOffset = (iu[31] & 0xff) & ~3;
        if (writing) {
            req.dmaFmt = (iu[5] & 0xff) >>> 4;
        } else {
            req.cdbOffset += dataOutDescSize(iu);
            req.dmaFmt = iu[5] & 0xf;
        }
        switch (req.dmaFmt) {
            case DESC_NONE -> {
            }
            case DESC_DIRECT -> {
                req.totalDesc = 1;
                req.localDesc = 1;
            }
            case DESC_INDIRECT -> {
                int at = 48 + req.cdbOffset;
                req.totalDesc = at + 16 <= SRP_MAX_IU_LEN ? (int) ((ByteBuffer.wrap(iu).getInt(at + 12) & 0xffffffffL) / 16)
                        : 0;
                req.localDesc = writing ? iu[6] & 0xff : iu[7] & 0xff;
            }
            default -> {
                PaprLog.once("vscsi-desc-fmt-" + req.dmaFmt, "unknown descriptor format");
                return false;
            }
        }
        return true;
    }

    /** A direct buffer descriptor: va, len. */
    private record Desc(long va, long len) {}

    private static Desc readDesc(byte[] b, int at) {
        if (at + 16 > b.length) {
            return new Desc(0, 0);
        }
        ByteBuffer bb = ByteBuffer.wrap(b);
        return new Desc(bb.getLong(at), bb.getInt(at + 12) & 0xffffffffL);
    }

    /** vscsi_fetch_desc: 1 with {@code out[0]} set, 0 when out of descriptors, -1 on error. */
    private int fetchDesc(Req req, int n, long bufOffset, Desc[] out) {
        Desc d;
        int base = 48 + req.cdbOffset;
        switch (req.dmaFmt) {
            case DESC_NONE -> {
                return 0;
            }
            case DESC_DIRECT -> d = readDesc(req.iu, base);
            case DESC_INDIRECT -> {
                if (n < req.localDesc) {
                    d = readDesc(req.iu, base + 20 + 16 * n);
                } else if (n < req.totalDesc) {
                    Desc table = readDesc(req.iu, base);
                    long off = 16L * n;
                    if (off >= table.len) {
                        return -1;
                    }
                    byte[] raw = new byte[16];
                    if (!dmaRead(table.va + off, raw, 0, 16)) {
                        return -1;
                    }
                    d = readDesc(raw, 0);
                } else {
                    return 0;
                }
            }
            default -> {
                return -1;
            }
        }
        if (bufOffset > d.len) {
            return -1;
        }
        out[0] = new Desc(d.va + bufOffset, d.len - bufOffset);
        return out[0].len != 0 ? 1 : 0;
    }

    /** Move data between {@code b} and the initiator's buffers; returns bytes moved or -1. */
    private int transfer(Req req, boolean writing, byte[] b, int off, int len) {
        if (!req.directionKnown && !preprocess(req, writing)) {
            return -1;
        }
        Desc[] md = new Desc[1];
        int total = 0;
        if (req.dmaFmt == DESC_DIRECT) {
            int rc = fetchDesc(req, req.curDescNum, req.curDescOffset, md);
            if (rc < 0) {
                return -1;
            }
            if (rc == 0) {
                return 0;
            }
            int llen = (int) Math.min(len, md[0].len);
            if (llen > 0) {
                boolean ok = writing ? dmaRead(md[0].va, b, off, llen) : dmaWrite(md[0].va, b, off, llen);
                if (!ok) {
                    return -1;
                }
            }
            req.curDescOffset += llen;
            req.transferred += llen;
            return llen;
        }
        if (req.dmaFmt == DESC_NONE) {
            return 0;
        }
        while (len > 0) {
            int rc = fetchDesc(req, req.curDescNum, req.curDescOffset, md);
            if (rc < 0) {
                return -1;
            }
            if (rc == 0) {
                break;
            }
            int llen = (int) Math.min(len, md[0].len);
            boolean ok = writing ? dmaRead(md[0].va, b, off, llen) : dmaWrite(md[0].va, b, off, llen);
            if (!ok) {
                return -1;
            }
            len -= llen;
            off += llen;
            total += llen;
            req.curDescOffset += llen;
            if (md[0].len == llen) {
                req.curDescNum++;
                req.curDescOffset = 0;
            }
        }
        req.transferred += total;
        return total;
    }
}
