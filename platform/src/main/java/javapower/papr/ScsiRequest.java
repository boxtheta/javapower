package javapower.papr;

/**
 * One SCSI command travelling from an HBA to a {@link ScsiTarget}. The HBA supplies the data path
 * (moving bytes between the target and the initiator's buffers) and the completion callback.
 */
public final class ScsiRequest {
    public static final int GOOD = 0x00;
    public static final int CHECK_CONDITION = 0x02;
    /** Pseudo status: a data transfer to or from the initiator failed (DMA fault). */
    public static final int TRANSFER_ERROR = -1;

    /** Moves data between target and initiator; implemented by the HBA. */
    public interface DataPath {
        /** Device to initiator. Returns the bytes accepted (fewer if buffers run out), or -1 on a fault. */
        int toInitiator(byte[] b, int off, int len);

        /** Initiator to device. Returns the bytes supplied (fewer if buffers run out), or -1 on a fault. */
        int fromInitiator(byte[] b, int off, int len);
    }

    /** Completion callback; called exactly once. */
    @FunctionalInterface
    public interface Completion {
        /**
         * @param status    SCSI status byte, or {@link #TRANSFER_ERROR}
         * @param sense     fixed-format sense data for CHECK CONDITION, otherwise null
         * @param xferLen   the transfer length the command implied (positive: data in, negative:
         *                  data out); the HBA derives residuals from it
         */
        void complete(int status, byte[] sense, int xferLen);
    }

    private final int lun;
    private final byte[] cdb;
    private final DataPath data;
    private final Completion completion;
    private boolean done;

    public ScsiRequest(int lun, byte[] cdb, DataPath data, Completion completion) {
        this.lun = lun;
        this.cdb = cdb;
        this.data = data;
        this.completion = completion;
    }

    public int lun() {
        return lun;
    }

    public byte[] cdb() {
        return cdb;
    }

    public DataPath data() {
        return data;
    }

    public boolean isDone() {
        return done;
    }

    public void complete(int status, byte[] sense, int xferLen) {
        if (done) {
            throw new IllegalStateException("SCSI request completed twice");
        }
        done = true;
        completion.complete(status, sense, xferLen);
    }

    // ---- sense helpers -----------------------------------------------------------------------

    public static final int KEY_NO_SENSE = 0x0;
    public static final int KEY_NOT_READY = 0x2;
    public static final int KEY_MEDIUM_ERROR = 0x3;
    public static final int KEY_HARDWARE_ERROR = 0x4;
    public static final int KEY_ILLEGAL_REQUEST = 0x5;
    public static final int KEY_UNIT_ATTENTION = 0x6;
    public static final int KEY_DATA_PROTECT = 0x7;
    public static final int KEY_ABORTED_COMMAND = 0xb;

    /** Fixed-format (0x70) sense data, 18 bytes, as QEMU's scsi_build_sense produces. */
    public static byte[] sense(int key, int asc, int ascq) {
        byte[] s = new byte[18];
        s[0] = 0x70;
        s[2] = (byte) key;
        s[7] = 10;
        s[12] = (byte) asc;
        s[13] = (byte) ascq;
        return s;
    }

    /** Descriptor-format (0x72) sense data, 8 bytes. */
    public static byte[] descriptorSense(int key, int asc, int ascq) {
        return new byte[] {0x72, (byte) key, (byte) asc, (byte) ascq, 0, 0, 0, 0};
    }
}
