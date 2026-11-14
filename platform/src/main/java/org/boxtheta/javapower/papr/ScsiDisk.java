package org.boxtheta.javapower.papr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * SCSI direct-access disk ({@code scsi-hd}), MMC CD/DVD-ROM ({@code scsi-cd}) or direct-access
 * disk with a removable medium ({@code scsi-hd,removable=on}) on top of a {@link BlockBackend},
 * modelled on QEMU's hw/scsi/scsi-disk.c plus the target-level behaviour of scsi-bus.c (unit
 * attention after reset, sense persistence for REQUEST SENSE, wrong-LUN handling).
 *
 * <p>Emulated (non-READ/WRITE) commands transfer exactly the allocation length the CDB asks for,
 * zero padded, as QEMU does. Logical block provisioning is advertised as QEMU does for a raw image
 * (discard granularity 4 KiB): UNMAP is accepted and leaves the data in place, which LBPRZ = 0
 * allows; WRITE SAME writes its block repeatedly.
 *
 * <h2>Removable media</h2>
 * A {@link Type#CDROM} or {@link Type#REMOVABLE} drive reports a removable medium (the RMB bit of
 * INQUIRY), and the embedder changes that medium while the machine runs with
 * {@link #changeMedium}; the drive may be empty, also when it is created. A built machine's
 * drives are found with {@link Machine#disk}.
 * <ul>
 * <li>Empty drive: TEST UNIT READY, READ CAPACITY, reads, writes and the other commands that need
 *     a medium fail with NOT READY, MEDIUM NOT PRESENT (sense key 2h, 3Ah/00h). INQUIRY, MODE
 *     SENSE, REPORT LUNS, START STOP UNIT and PREVENT ALLOW MEDIUM REMOVAL work as ever.</li>
 * <li>After a medium is loaded, the next command that reports unit attentions fails once with
 *     UNIT ATTENTION, NOT READY TO READY CHANGE, MEDIUM MAY HAVE CHANGED (6h, 28h/00h). From then
 *     on READ CAPACITY and MODE SENSE give the new medium's size and write protection. Linux's
 *     {@code sd} and {@code sr} find this with their media change polling, FreeBSD's {@code da}
 *     and {@code cd} with their periodic TEST UNIT READY. QEMU reports an extra unit attention
 *     with 3Ah/00h first; we do not, since the medium is there. A reset unit attention that is
 *     still pending is reported instead of the medium change, never in addition: after it the
 *     initiator has to look at the medium again anyway.</li>
 * <li>A CD-ROM also reports the change to GET EVENT STATUS NOTIFICATION, once: NewMedia after a
 *     load, MediaRemoval after the drive was emptied.</li>
 * <li>The block size belongs to the drive and stays what it was created with; a medium is so
 *     many whole blocks, and bytes after the last whole block are out of the guest's reach.</li>
 * <li>The guest can eject (START STOP UNIT with LoEj and without Start): the medium, if any,
 *     stays in the drive but the guest reaches it no more (3Ah/00h) until it loads it again
 *     (LoEj with Start) or the embedder changes it. {@link #onGuestEject} tells the embedder,
 *     which may then take the medium out. While the guest prevents removal (PREVENT ALLOW MEDIUM
 *     REMOVAL) its own eject fails with ILLEGAL REQUEST, MEDIUM REMOVAL PREVENTED (5h, 53h/02h),
 *     as in QEMU. {@link #changeMedium} works all the same, like the emergency eject of a real
 *     drive: an embedder that wants to honour the guest's wish asks {@link #removalPrevented}
 *     first.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * A drive has a lock of its own. {@link #submit} holds it for the whole command, the data
 * transfer and the backend calls included; {@link #changeMedium}, {@link #reset} and the queries
 * take it too, and may be called from any thread, also while guest CPUs run. So a medium change
 * waits for a command in progress on this drive, and when it returns no command uses the old
 * backend and none ever will again: the caller may close it or unmap its memory at once. The
 * lock comes after the HBA's device lock in the machine's lock order and is held while the
 * backend is called, so a backend must not wait for a thread that is changing this drive's medium.
 */
public final class ScsiDisk implements ScsiTarget {
    /** The kind of device a guest sees. */
    public enum Type {
        /** A fixed disk (QEMU's {@code scsi-hd}): direct access, the medium never changes. */
        DISK,
        /** An MMC CD/DVD-ROM drive (QEMU's {@code scsi-cd}): read-only, removable medium. */
        CDROM,
        /**
         * A disk drive with a removable, writable medium (QEMU's {@code scsi-hd,removable=on}):
         * direct access like {@link #DISK} with the RMB bit set in INQUIRY, for floppy and flash
         * drive like devices.
         */
        REMOVABLE;

        /** Whether the medium of such a device can be changed ({@link ScsiDisk#changeMedium}). */
        public boolean removable() {
            return this != DISK;
        }
    }

    public static final int TYPE_DISK = 0x00;
    public static final int TYPE_ROM = 0x05;

    // Opcodes.
    static final int TEST_UNIT_READY = 0x00;
    static final int REQUEST_SENSE = 0x03;
    static final int FORMAT_UNIT = 0x04;
    static final int READ_6 = 0x08;
    static final int WRITE_6 = 0x0a;
    static final int INQUIRY = 0x12;
    static final int MODE_SELECT = 0x15;
    static final int RESERVE = 0x16;
    static final int RELEASE = 0x17;
    static final int MODE_SENSE = 0x1a;
    static final int START_STOP = 0x1b;
    static final int ALLOW_MEDIUM_REMOVAL = 0x1e;
    static final int READ_CAPACITY_10 = 0x25;
    static final int READ_10 = 0x28;
    static final int WRITE_10 = 0x2a;
    static final int SEEK_10 = 0x2b;
    static final int WRITE_VERIFY_10 = 0x2e;
    static final int VERIFY_10 = 0x2f;
    static final int SYNCHRONIZE_CACHE = 0x35;
    static final int WRITE_SAME_10 = 0x41;
    static final int UNMAP = 0x42;
    static final int READ_TOC = 0x43;
    static final int GET_CONFIGURATION = 0x46;
    static final int GET_EVENT_STATUS_NOTIFICATION = 0x4a;
    static final int READ_DISC_INFORMATION = 0x51;
    static final int MODE_SELECT_10 = 0x55;
    static final int RESERVE_10 = 0x56;
    static final int RELEASE_10 = 0x57;
    static final int MODE_SENSE_10 = 0x5a;
    static final int READ_16 = 0x88;
    static final int WRITE_16 = 0x8a;
    static final int WRITE_VERIFY_16 = 0x8e;
    static final int VERIFY_16 = 0x8f;
    static final int SYNCHRONIZE_CACHE_16 = 0x91;
    static final int WRITE_SAME_16 = 0x93;
    static final int SERVICE_ACTION_IN_16 = 0x9e;
    static final int REPORT_LUNS = 0xa0;
    static final int READ_12 = 0xa8;
    static final int WRITE_12 = 0xaa;
    static final int WRITE_VERIFY_12 = 0xae;
    static final int VERIFY_12 = 0xaf;
    static final int READ_DVD_STRUCTURE = 0xad;
    static final int MECHANISM_STATUS = 0xbd;

    static final int SAI_READ_CAPACITY_16 = 0x10;

    /** Largest single backend I/O; bigger transfers are split. */
    private static final int CHUNK = 256 * 1024;
    /** QEMU's default discard granularity and maximum UNMAP size for scsi-hd/scsi-cd. */
    private static final int DISCARD_GRANULARITY = 4096;
    private static final long MAX_UNMAP_SIZE = 1L << 30;
    /** QEMU refuses emulated commands with an allocation length above this. */
    private static final int MAX_EMULATED_XFER = 65536;
    /** CDs above this many 512-byte sectors are reported as DVDs (80 min CD). */
    private static final long CD_MAX_SECTORS = 80L * 60 * 75 * 2048 / 512;

    /** GET EVENT STATUS NOTIFICATION media event codes (MMC). */
    private static final int MEDIA_EVENT_NONE = 0;
    private static final int MEDIA_EVENT_NEW_MEDIA = 2;
    private static final int MEDIA_EVENT_REMOVAL = 3;

    private final Type type;
    /** The drive's lock (see the class comment); guards everything below that is not final. */
    private final Object lock = new Object();
    /** The medium in the drive; null when a drive with removable media is empty. */
    private BlockBackend backend;
    private final int blockSize;
    private final int physBlockExp;
    private final int channel;
    private final int id;
    private final int lun;
    private final String vendor;
    private final String product;
    private final String version;
    private final String serial;
    private final String deviceId;

    private byte[] unitAttention;
    private byte[] lastSense;
    private boolean senseIsUa;
    /** The guest ejected the medium (START STOP UNIT) and has not loaded it again. */
    private boolean trayOpen;
    /** The guest prevents medium removal (PREVENT ALLOW MEDIUM REMOVAL). */
    private boolean trayLocked;
    /**
     * The media event GET EVENT STATUS NOTIFICATION has still to report, or none. Only a CD-ROM
     * has that command, so only a CD-ROM ever has an event.
     */
    private int mediaEvent;
    /** The machine was closed: the drive takes no medium any more. */
    private boolean closed;
    /** A guest eject happened in the command being processed; the listener is told after it. */
    private boolean ejectNotify;
    private volatile Runnable ejectListener;
    private volatile PaprLog log = new PaprLog();

    /**
     * @param backend   the medium; null for an empty drive, which only a type with removable
     *                  media may be
     * @param blockSize logical block size (512 or 4096 for disks; CDs normally 2048)
     * @param deviceId  identifier reported in VPD page 0x83 (QEMU uses the drive id, e.g. "d0")
     * @param serial    unit serial number for VPD page 0x80, or null (QEMU default: none)
     * @throws IllegalArgumentException if the block size is not a power of two from 512, or a
     *     fixed disk has no backend
     */
    public ScsiDisk(Type type, BlockBackend backend, int blockSize, int physicalBlockSize, String deviceId,
                    String serial) {
        this(type, backend, blockSize, physicalBlockSize, deviceId, serial, 0, 0, 0);
    }

    public ScsiDisk(Type type, BlockBackend backend, int blockSize, int physicalBlockSize, String deviceId,
                    String serial, int channel, int id, int lun) {
        if (blockSize < 512 || Integer.bitCount(blockSize) != 1) {
            throw new IllegalArgumentException("bad logical block size " + blockSize);
        }
        if (physicalBlockSize < blockSize) {
            physicalBlockSize = blockSize;
        }
        if (backend == null && !type.removable()) {
            throw new IllegalArgumentException("a fixed disk needs a backend; only drives with removable media"
                    + " may be empty");
        }
        this.type = type;
        this.backend = backend;
        this.blockSize = blockSize;
        this.physBlockExp = Integer.numberOfTrailingZeros(physicalBlockSize / blockSize);
        this.deviceId = deviceId;
        this.serial = serial;
        this.channel = channel;
        this.id = id;
        this.lun = lun;
        this.vendor = "QEMU";
        this.product = type == Type.CDROM ? "QEMU CD-ROM" : "QEMU HARDDISK";
        this.version = "2.5+";
        reset();
    }

    /** Report through {@code l} from now on (the machine passes its log when it creates the disk). */
    void setLog(PaprLog l) {
        this.log = l;
    }

    public static ScsiDisk disk(BlockBackend b, int blockSize, String deviceId) {
        return new ScsiDisk(Type.DISK, b, blockSize, blockSize, deviceId, null);
    }

    public static ScsiDisk cdrom(BlockBackend b, String deviceId) {
        return new ScsiDisk(Type.CDROM, b, 2048, 2048, deviceId, null);
    }

    /** A drive with a removable, writable medium; {@code b} is null for an empty drive. */
    public static ScsiDisk removable(BlockBackend b, int blockSize, String deviceId) {
        return new ScsiDisk(Type.REMOVABLE, b, blockSize, blockSize, deviceId, null);
    }

    @Override
    public int channel() {
        return channel;
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public int lun() {
        return lun;
    }

    public Type type() {
        return type;
    }

    public int blockSize() {
        return blockSize;
    }

    /**
     * The medium in the drive, or null when the drive is empty. The drive's commands use it
     * under the drive's lock; anything else done with it while it is in the drive is the
     * caller's to keep apart from them.
     */
    public BlockBackend backend() {
        synchronized (lock) {
            return backend;
        }
    }

    /** Whether there is a medium in the drive (a fixed disk always has one). */
    public boolean hasMedium() {
        return backend() != null;
    }

    /**
     * Whether the guest has ejected the medium (START STOP UNIT) and not loaded it again. The
     * medium, if there is one, is still in the drive; see {@link #onGuestEject}.
     */
    public boolean ejected() {
        synchronized (lock) {
            return trayOpen;
        }
    }

    /**
     * Whether the guest currently prevents medium removal (PREVENT ALLOW MEDIUM REMOVAL), as an
     * operating system does while it has the medium mounted. {@link #changeMedium} does not
     * look at this; an embedder that wants to keep the medium in the drive for the guest does.
     */
    public boolean removalPrevented() {
        synchronized (lock) {
            return trayLocked;
        }
    }

    /**
     * Change the medium of a drive with removable media: put {@code medium} into the drive, or
     * empty the drive with null, and take out what was in it. May be called from any thread, also
     * while the guest runs. It waits for a command in progress on this drive; when it returns,
     * no command uses the medium that was taken out and none will, and every later command sees
     * the new one (see the class comment for what the guest is told).
     *
     * <p>The medium taken out is returned and is the caller's again: the drive neither flushes
     * nor closes it. The medium put in is the drive's until it is taken out the same way; the
     * machine closes the one that is in the drive when the machine itself is closed. Putting in
     * the backend that is already there tells the guest of a change too (use it when the
     * backend's contents or size were replaced behind the drive's back) and returns null,
     * since nothing was taken out.
     *
     * @param medium the new medium, or null to leave the drive empty
     * @return the medium that was in the drive and no longer is, or null if there was none
     * @throws IllegalStateException if this is a fixed disk, or the machine was closed and
     *     {@code medium} is not null (a closed machine's drives are empty and stay so)
     */
    public BlockBackend changeMedium(BlockBackend medium) {
        if (!type.removable()) {
            throw new IllegalStateException("SCSI device " + id + ":" + lun + " is a fixed disk; only a CDROM or"
                    + " REMOVABLE drive can have its medium changed");
        }
        synchronized (lock) {
            if (closed) {
                if (medium != null) {
                    throw new IllegalStateException("the machine of SCSI device " + id + ":" + lun + " is closed");
                }
                return null;
            }
            BlockBackend old = backend;
            if (old == null && medium == null) {
                return null;
            }
            backend = medium;
            mediumChanged();
            return old == medium ? null : old;
        }
    }

    /**
     * Set what tells the embedder that the guest ejected the medium (START STOP UNIT with LoEj
     * and without Start, e.g. {@code eject /dev/sdb} on Linux or {@code camcontrol eject da1} on
     * FreeBSD) and the drive let it, i.e. removal was not prevented. The medium, if there is one,
     * is still in the drive then, out of the guest's reach; a listener usually takes it out with
     * {@code changeMedium(null)}, at once or later from a thread of its own.
     *
     * <p>{@code listener} runs on the thread of the guest CPU that sent the command, after the
     * command has completed, inside that CPU's hypercall: the HBA's device lock is held, the
     * drive's lock is not. It must return quickly, since the guest's other commands wait for it.
     * An exception it throws is logged and otherwise ignored.
     *
     * @param listener the listener, or null for none
     */
    public void onGuestEject(Runnable listener) {
        this.ejectListener = listener;
    }

    /**
     * The medium in the drive is another one than a moment ago, or gone. Call with the lock
     * held, after {@code backend} was set.
     */
    private void mediumChanged() {
        if (backend != null) {
            // A medium put in is loaded: the guest's earlier eject is over.
            trayOpen = false;
            // A pending reset unit attention stays and stands for this change too.
            if (unitAttention == null || (unitAttention[12] & 0xff) != 0x29) {
                unitAttention = ScsiRequest.sense(ScsiRequest.KEY_UNIT_ATTENTION, 0x28, 0x00);
            }
        } else {
            // Without a medium there is no change to announce: commands fail with 3Ah.
            if (unitAttention != null && (unitAttention[12] & 0xff) == 0x28) {
                unitAttention = null;
            }
        }
        if (type == Type.CDROM) {
            mediaEvent = backend != null ? MEDIA_EVENT_NEW_MEDIA : MEDIA_EVENT_REMOVAL;
        }
    }

    /**
     * The machine is being closed: take the medium out for it to close, and take no other from
     * now on.
     */
    BlockBackend detach() {
        synchronized (lock) {
            BlockBackend b = backend;
            backend = null;
            closed = true;
            return b;
        }
    }

    private int peripheralType() {
        return type == Type.CDROM ? TYPE_ROM : TYPE_DISK;
    }

    private boolean removable() {
        return type.removable();
    }

    /** Size of the medium in bytes; 0 for an empty drive. Call with the lock held. */
    private long mediumSize() {
        return backend == null ? 0 : backend.size();
    }

    /** Whether writes are refused: a CD-ROM, or a write-protected medium. Call with the lock held. */
    private boolean writeProtected() {
        return type == Type.CDROM || (backend != null && backend.readOnly());
    }

    /** Number of logical blocks of the medium in the drive; 0 for an empty drive. */
    public long blocks() {
        synchronized (lock) {
            return mediumSize() / blockSize;
        }
    }

    private long maxLba() {
        long n = blocks();
        return n == 0 ? 0 : n - 1;
    }

    /** Whether commands reach a medium: there is one and the guest has not ejected it. */
    private boolean available() {
        return backend != null && !trayOpen;
    }

    @Override
    public void reset() {
        synchronized (lock) {
            // scsi_disk_reset: purge requests and raise POWER ON, RESET OR BUS DEVICE RESET OCCURRED.
            unitAttention = ScsiRequest.sense(ScsiRequest.KEY_UNIT_ATTENTION, 0x29, 0x00);
            trayLocked = false;
            trayOpen = false;
            mediaEvent = MEDIA_EVENT_NONE;
        }
    }

    /**
     * Sense and unit-attention state and the tray. The medium is the embedder's and not saved;
     * {@link #mediumBytes} and {@link #mediumReadOnly} describe it for {@link #mediumAfterLoad}.
     */
    void saveState(java.io.DataOutput out) throws java.io.IOException {
        synchronized (lock) {
            out.writeInt(id);
            out.writeInt(lun);
            org.boxtheta.javapower.core.StateOutput.writeBytes(out, unitAttention);
            org.boxtheta.javapower.core.StateOutput.writeBytes(out, lastSense);
            out.writeBoolean(senseIsUa);
            out.writeBoolean(trayOpen);
            out.writeBoolean(trayLocked);
        }
    }

    void loadState(org.boxtheta.javapower.core.StateInput.Record in) throws java.io.IOException {
        synchronized (lock) {
            int i = in.readInt();
            int l = in.readInt();
            if (i != id || l != lun) {
                throw in.corrupt("SCSI target " + i + ":" + l + " where " + id + ":" + lun + " was expected");
            }
            unitAttention = in.readBytes(252);
            lastSense = in.readBytes(252);
            senseIsUa = in.readBoolean();
            trayOpen = in.readBoolean();
            trayLocked = in.readBoolean();
            if ((unitAttention != null && unitAttention.length < 14) || (lastSense != null && lastSense.length < 14)) {
                throw in.corrupt("SCSI target " + id + ":" + lun + ": sense data too short");
            }
            mediaEvent = MEDIA_EVENT_NONE;
        }
    }

    /** Size in bytes of the medium in the drive, or -1 for an empty drive (saved states). */
    long mediumBytes() {
        synchronized (lock) {
            return backend == null ? -1 : backend.size();
        }
    }

    /** Whether the medium in the drive is write-protected; false for an empty drive (saved states). */
    boolean mediumReadOnly() {
        synchronized (lock) {
            return backend != null && backend.readOnly();
        }
    }

    /** The media event still to be reported, for the saved state. */
    int mediaEvent() {
        synchronized (lock) {
            return mediaEvent;
        }
    }

    /** Restore {@link #mediaEvent} from a saved state; false if it holds no such event. */
    boolean setMediaEvent(int event) {
        if (event != MEDIA_EVENT_NONE
                && (type != Type.CDROM || (event != MEDIA_EVENT_NEW_MEDIA && event != MEDIA_EVENT_REMOVAL))) {
            return false;
        }
        synchronized (lock) {
            mediaEvent = event;
        }
        return true;
    }

    /**
     * A state was loaded into this drive that was saved with a medium of {@code savedBytes}
     * bytes (-1: none) and that write protection. If the drive holds a medium of another size
     * or protection now, or the one has a medium and the other none, the medium was changed
     * while the machine was saved, and the guest is told as for {@link #changeMedium}.
     */
    void mediumAfterLoad(long savedBytes, boolean savedReadOnly) {
        synchronized (lock) {
            long now = backend == null ? -1 : backend.size();
            if (now != savedBytes || (backend != null && backend.readOnly() != savedReadOnly)) {
                mediumChanged();
            }
        }
    }

    /** Whether a unit attention condition is pending (tests). */
    public boolean unitAttentionPending() {
        synchronized (lock) {
            return unitAttention != null;
        }
    }

    // ---- dispatch ----------------------------------------------------------------------------

    @Override
    public void submit(ScsiRequest req) {
        Runnable listener = null;
        synchronized (lock) {
            dispatch(req);
            if (ejectNotify) {
                ejectNotify = false;
                listener = ejectListener;
            }
        }
        if (listener != null) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.once("scsi-eject-listener", "the guest eject listener of SCSI device " + id + ":" + lun
                        + " failed: " + e);
            }
        }
    }

    /** Run one command to its completion. Called with the lock held. */
    private void dispatch(ScsiRequest req) {
        byte[] cdb = req.cdb();
        int op = cdb[0] & 0xff;
        if (unitAttention != null && op != INQUIRY && op != REPORT_LUNS && op != GET_CONFIGURATION
                && op != GET_EVENT_STATUS_NOTIFICATION && !(op == REQUEST_SENSE && senseIsUa)) {
            byte[] ua = unitAttention;
            unitAttention = null;
            checkCondition(req, ua, true);
            return;
        }
        if (req.lun() != lun) {
            targetCommand(req);
            return;
        }
        if (op == REQUEST_SENSE && lastSense != null) {
            targetRequestSense(req);
            return;
        }
        try {
            switch (op) {
                case READ_6, READ_10, READ_12, READ_16 -> doRead(req);
                case WRITE_6, WRITE_10, WRITE_12, WRITE_16, WRITE_VERIFY_10, WRITE_VERIFY_12, WRITE_VERIFY_16 ->
                        doWrite(req);
                default -> emulate(req);
            }
        } catch (IOException e) {
            log.once("scsi-io-error", e.toString());
            checkCondition(req, ScsiRequest.sense(ScsiRequest.KEY_MEDIUM_ERROR, 0x11, 0x00), false);
        }
    }

    private void good(ScsiRequest req, int xfer) {
        lastSense = null;
        senseIsUa = false;
        req.complete(ScsiRequest.GOOD, null, xfer);
    }

    private void checkCondition(ScsiRequest req, byte[] sense, boolean isUa) {
        // With autosense (which v-scsi always performs) a UA is consumed by being reported.
        lastSense = isUa ? null : sense;
        senseIsUa = false;
        req.complete(ScsiRequest.CHECK_CONDITION, sense, 0);
    }

    private void illegal(ScsiRequest req, int asc) {
        checkCondition(req, ScsiRequest.sense(ScsiRequest.KEY_ILLEGAL_REQUEST, asc, 0), false);
    }

    private void invalidField(ScsiRequest req) {
        illegal(req, 0x24);
    }

    private void transferIn(ScsiRequest req, byte[] buf, int len) {
        int n = req.data().toInitiator(buf, 0, len);
        if (n < 0) {
            req.complete(ScsiRequest.TRANSFER_ERROR, null, len);
        } else {
            good(req, len);
        }
    }

    // ---- target-level commands (wrong LUN, saved sense) ----------------------------------------

    private void targetCommand(ScsiRequest req) {
        byte[] cdb = req.cdb();
        int op = cdb[0] & 0xff;
        if (req.lun() != 0 && op != INQUIRY && op != REQUEST_SENSE) {
            illegal(req, 0x25); // LOGICAL UNIT NOT SUPPORTED
            return;
        }
        switch (op) {
            case INQUIRY -> {
                if ((cdb[1] & 0x3) != 0 || cdb[2] != 0) {
                    // EVPD for a missing LUN is not worth modelling.
                    invalidField(req);
                    return;
                }
                int xfer = u16(cdb, 3);
                int len = Math.min(xfer, 36);
                byte[] buf = new byte[36];
                if (req.lun() != 0) {
                    buf[0] = 0x7f; // TYPE_NO_LUN
                } else {
                    buf[0] = 0x3f; // not present | inactive
                    buf[2] = 5;
                    buf[3] = 0x12;
                    buf[4] = (byte) (len - 5);
                    buf[7] = 0x12;
                    putAscii(buf, 8, "QEMU", 8);
                    putAscii(buf, 16, "QEMU TARGET", 16);
                    putAscii(buf, 32, "2.5", 3);
                }
                transferIn(req, buf, len);
            }
            case REQUEST_SENSE -> {
                byte[] s = ScsiRequest.sense(ScsiRequest.KEY_ILLEGAL_REQUEST, 0x25, 0);
                boolean fixed = (cdb[1] & 1) == 0;
                if (!fixed) {
                    s = ScsiRequest.descriptorSense(ScsiRequest.KEY_ILLEGAL_REQUEST, 0x25, 0);
                }
                int len = Math.min(cdb[4] & 0xff, s.length);
                transferIn(req, s, len);
            }
            case TEST_UNIT_READY -> good(req, 0);
            default -> illegal(req, 0x20);
        }
    }

    private void targetRequestSense(ScsiRequest req) {
        byte[] cdb = req.cdb();
        boolean fixed = (cdb[1] & 1) == 0;
        byte[] s = lastSense;
        if (!fixed) {
            s = ScsiRequest.descriptorSense(s[2] & 0xf, s[12] & 0xff, s[13] & 0xff);
        }
        int len = Math.min(cdb[4] & 0xff, s.length);
        byte[] out = new byte[s.length];
        System.arraycopy(s, 0, out, 0, s.length);
        transferIn(req, out, len);
    }

    // ---- READ / WRITE --------------------------------------------------------------------------

    private record Rw(long lba, long blocks) {}

    private static Rw decodeRw(byte[] cdb) {
        int op = cdb[0] & 0xff;
        return switch (op >>> 5) {
            case 0 -> { // 6-byte
                long lba = ((cdb[1] & 0x1fL) << 16) | ((cdb[2] & 0xffL) << 8) | (cdb[3] & 0xffL);
                int n = cdb[4] & 0xff;
                yield new Rw(lba, n == 0 ? 256 : n);
            }
            case 1, 2 -> new Rw(u32(cdb, 2) & 0xffffffffL, u16(cdb, 7));
            case 4 -> new Rw(u64(cdb, 2), u32(cdb, 10) & 0xffffffffL);
            case 5 -> new Rw(u32(cdb, 2) & 0xffffffffL, u32(cdb, 6) & 0xffffffffL);
            default -> throw new IllegalArgumentException("not a read/write CDB");
        };
    }

    private boolean lbaInRange(long lba, long n) {
        return lba >= 0 && lba + n >= lba && lba + n <= maxLba() + 1;
    }

    private void doRead(ScsiRequest req) throws IOException {
        byte[] cdb = req.cdb();
        if (!available()) {
            checkCondition(req, ScsiRequest.sense(ScsiRequest.KEY_NOT_READY, 0x3a, 0), false);
            return;
        }
        Rw rw = decodeRw(cdb);
        if ((cdb[0] & 0xff) != READ_6 && (cdb[1] & 0xe0) != 0) {
            invalidField(req); // protection information is not supported
            return;
        }
        if (!lbaInRange(rw.lba, rw.blocks)) {
            illegal(req, 0x21);
            return;
        }
        long total = rw.blocks * blockSize;
        if (total > Integer.MAX_VALUE) {
            invalidField(req);
            return;
        }
        long off = rw.lba * blockSize;
        long left = total;
        byte[] buf = new byte[(int) Math.min(CHUNK, Math.max(left, 1))];
        while (left > 0) {
            int n = (int) Math.min(buf.length, left);
            backend.read(off, buf, 0, n);
            int moved = req.data().toInitiator(buf, 0, n);
            if (moved < 0) {
                req.complete(ScsiRequest.TRANSFER_ERROR, null, (int) total);
                return;
            }
            if (moved < n) {
                break; // initiator buffers exhausted; the HBA reports the overflow residual
            }
            off += n;
            left -= n;
        }
        good(req, (int) total);
    }

    private void doWrite(ScsiRequest req) throws IOException {
        byte[] cdb = req.cdb();
        if (!available()) {
            checkCondition(req, ScsiRequest.sense(ScsiRequest.KEY_NOT_READY, 0x3a, 0), false);
            return;
        }
        if (writeProtected()) {
            checkCondition(req, ScsiRequest.sense(ScsiRequest.KEY_DATA_PROTECT, 0x27, 0), false);
            return;
        }
        Rw rw = decodeRw(cdb);
        if ((cdb[0] & 0xff) != WRITE_6 && (cdb[1] & 0xe0) != 0) {
            invalidField(req);
            return;
        }
        if (!lbaInRange(rw.lba, rw.blocks)) {
            illegal(req, 0x21);
            return;
        }
        long total = rw.blocks * blockSize;
        if (total > Integer.MAX_VALUE) {
            invalidField(req);
            return;
        }
        long off = rw.lba * blockSize;
        long left = total;
        byte[] buf = new byte[(int) Math.min(CHUNK, Math.max(left, 1))];
        while (left > 0) {
            int n = (int) Math.min(buf.length, left);
            int got = req.data().fromInitiator(buf, 0, n);
            if (got < 0) {
                req.complete(ScsiRequest.TRANSFER_ERROR, null, (int) -total);
                return;
            }
            try {
                backend.write(off, buf, 0, got);
            } catch (IOException e) {
                log.once("scsi-write-error", e.toString());
                checkCondition(req, ScsiRequest.sense(ScsiRequest.KEY_HARDWARE_ERROR, 0x44, 0), false);
                return;
            }
            if (got < n) {
                break;
            }
            off += n;
            left -= n;
        }
        good(req, (int) -total);
    }

    // ---- emulated commands -----------------------------------------------------------------------

    /** Allocation length implied by the CDB (QEMU's scsi_req_xfer for the commands we emulate). */
    private static int allocationLength(byte[] cdb) {
        int op = cdb[0] & 0xff;
        return switch (op) {
            case INQUIRY -> u16(cdb, 3);
            case REQUEST_SENSE, MODE_SENSE, MODE_SELECT -> cdb[4] & 0xff;
            case MODE_SENSE_10, MODE_SELECT_10, READ_TOC, GET_CONFIGURATION, GET_EVENT_STATUS_NOTIFICATION,
                 READ_DISC_INFORMATION -> u16(cdb, 7);
            case READ_CAPACITY_10 -> 8;
            case SERVICE_ACTION_IN_16 -> u32(cdb, 10);
            case MECHANISM_STATUS, READ_DVD_STRUCTURE -> u16(cdb, 8);
            case UNMAP -> u16(cdb, 7);
            case REPORT_LUNS -> u32(cdb, 6);
            default -> 0;
        };
    }

    private void emulate(ScsiRequest req) throws IOException {
        byte[] cdb = req.cdb();
        int op = cdb[0] & 0xff;
        switch (op) {
            case INQUIRY, MODE_SENSE, MODE_SENSE_10, RESERVE, RESERVE_10, RELEASE, RELEASE_10, START_STOP,
                 ALLOW_MEDIUM_REMOVAL, GET_CONFIGURATION, GET_EVENT_STATUS_NOTIFICATION, MECHANISM_STATUS,
                 REQUEST_SENSE, REPORT_LUNS -> {
                // These need no medium. (QEMU answers REPORT LUNS in scsi-bus.c, before the disk.)
            }
            default -> {
                if (!available()) {
                    checkCondition(req, ScsiRequest.sense(ScsiRequest.KEY_NOT_READY, 0x3a, 0), false);
                    return;
                }
            }
        }
        int xfer = allocationLength(cdb);
        if (xfer < 0 || xfer > MAX_EMULATED_XFER) {
            invalidField(req);
            return;
        }
        byte[] out = new byte[Math.max(4096, xfer)];
        switch (op) {
            case TEST_UNIT_READY -> {
            }
            case INQUIRY -> {
                if (!inquiry(cdb, out, xfer)) {
                    invalidField(req);
                    return;
                }
            }
            case MODE_SENSE, MODE_SENSE_10 -> {
                int r = modeSense(cdb, out);
                if (r == -2) {
                    illegal(req, 0x39); // SAVING PARAMETERS NOT SUPPORTED
                    return;
                }
                if (r < 0) {
                    invalidField(req);
                    return;
                }
            }
            case READ_TOC -> {
                if (readToc(cdb, out) < 0) {
                    invalidField(req);
                    return;
                }
            }
            case RESERVE, RELEASE -> {
                if ((cdb[1] & 1) != 0) {
                    invalidField(req);
                    return;
                }
            }
            case RESERVE_10, RELEASE_10 -> {
                if ((cdb[1] & 3) != 0) {
                    invalidField(req);
                    return;
                }
            }
            case START_STOP -> {
                if (!startStop(req)) {
                    return;
                }
            }
            case ALLOW_MEDIUM_REMOVAL -> trayLocked = (cdb[4] & 1) != 0;
            case READ_CAPACITY_10 -> {
                long nb = blocks();
                if (nb == 0) {
                    checkCondition(req, ScsiRequest.sense(ScsiRequest.KEY_NOT_READY, 0x04, 0x03), false);
                    return;
                }
                if ((cdb[8] & 1) == 0 && u32(cdb, 2) != 0) {
                    invalidField(req);
                    return;
                }
                long last = Math.min(nb - 1, 0xffffffffL);
                putU32(out, 0, (int) last);
                out[6] = (byte) (blockSize >>> 8);
            }
            case REQUEST_SENSE -> {
                // No saved sense: report NO SENSE in the requested format.
                byte[] s = (cdb[1] & 1) == 0 ? ScsiRequest.sense(0, 0, 0) : ScsiRequest.descriptorSense(0, 0, 0);
                System.arraycopy(s, 0, out, 0, Math.min(s.length, out.length));
            }
            case MECHANISM_STATUS -> {
                if (type != Type.CDROM) {
                    invalidField(req);
                    return;
                }
                out[5] = 1;
            }
            case GET_CONFIGURATION -> {
                if (!getConfiguration(out)) {
                    invalidField(req);
                    return;
                }
            }
            case GET_EVENT_STATUS_NOTIFICATION -> {
                if (!eventStatus(cdb, out)) {
                    invalidField(req);
                    return;
                }
            }
            case READ_DISC_INFORMATION -> {
                if (type != Type.CDROM || (cdb[1] & 7) != 0) {
                    invalidField(req);
                    return;
                }
                out[1] = 32;
                out[2] = 0xe;
                out[3] = 1;
                out[4] = 1;
                out[5] = 1;
                out[6] = 1;
                out[7] = 0x20;
            }
            case READ_DVD_STRUCTURE -> {
                int r = readDvdStructure(cdb, out);
                if (r == -2) {
                    illegal(req, 0x30); // INCOMPATIBLE MEDIUM INSTALLED
                    return;
                }
                if (r < 0) {
                    invalidField(req);
                    return;
                }
            }
            case SERVICE_ACTION_IN_16 -> {
                if ((cdb[1] & 0x1f) != SAI_READ_CAPACITY_16) {
                    invalidField(req);
                    return;
                }
                long nb = blocks();
                if (nb == 0) {
                    checkCondition(req, ScsiRequest.sense(ScsiRequest.KEY_NOT_READY, 0x04, 0x03), false);
                    return;
                }
                if ((cdb[14] & 1) == 0 && u64(cdb, 2) != 0) {
                    invalidField(req);
                    return;
                }
                putU64(out, 0, nb - 1);
                out[10] = (byte) (blockSize >>> 8);
                out[13] = (byte) physBlockExp;
                out[14] = (byte) 0x80; // TPE: discard supported
            }
            case SYNCHRONIZE_CACHE, SYNCHRONIZE_CACHE_16 -> {
                try {
                    backend.flush();
                } catch (IOException e) {
                    log.once("scsi-flush-error", e.toString());
                    checkCondition(req, ScsiRequest.sense(ScsiRequest.KEY_HARDWARE_ERROR, 0x44, 0), false);
                    return;
                }
            }
            case SEEK_10 -> {
                if ((u32(cdb, 2) & 0xffffffffL) > maxLba()) {
                    illegal(req, 0x21);
                    return;
                }
            }
            case VERIFY_10, VERIFY_12, VERIFY_16 -> {
                if ((cdb[1] & 6) != 0) {
                    invalidField(req);
                    return;
                }
            }
            case MODE_SELECT, MODE_SELECT_10 -> {
                // Accept and discard the parameter list (nothing is changeable).
                byte[] in = new byte[xfer];
                if (xfer > 0 && req.data().fromInitiator(in, 0, xfer) < 0) {
                    req.complete(ScsiRequest.TRANSFER_ERROR, null, -xfer);
                    return;
                }
                log.once("scsi-mode-select", "MODE SELECT ignored");
                good(req, -xfer);
                return;
            }
            case FORMAT_UNIT -> {
            }
            case UNMAP -> {
                unmap(req, xfer);
                return;
            }
            case WRITE_SAME_10, WRITE_SAME_16 -> {
                writeSame(req);
                return;
            }
            case REPORT_LUNS -> {
                if (!reportLuns(cdb, out, xfer)) {
                    invalidField(req);
                    return;
                }
            }
            default -> {
                log.once("scsi-unknown-opcode-" + Integer.toHexString(op),
                        String.format("%s: opcode 0x%02x", product, op));
                illegal(req, 0x20);
                return;
            }
        }
        if (xfer == 0) {
            good(req, 0);
        } else {
            transferIn(req, out, xfer);
        }
    }

    /** REPORT LUNS addressed to this logical unit: lists LUN 0 and this LUN (scsi-bus.c). */
    private boolean reportLuns(byte[] cdb, byte[] out, int xfer) {
        if (xfer < 16 || (cdb[2] & 0xff) > 2) {
            return false;
        }
        int n = 0;
        putLun(out, 8, 0);
        n++;
        if (lun != 0) {
            putLun(out, 16, lun);
            n++;
        }
        putU32(out, 0, n * 8);
        return true;
    }

    private static void putLun(byte[] b, int at, int l) {
        if (l < 256) {
            b[at + 1] = (byte) l;
        } else {
            b[at] = (byte) (0x40 | (l >>> 8));
            b[at + 1] = (byte) l;
        }
    }

    private void unmap(ScsiRequest req, int len) throws IOException {
        byte[] cdb = req.cdb();
        byte[] p = new byte[len];
        if (len > 0 && req.data().fromInitiator(p, 0, len) < 0) {
            req.complete(ScsiRequest.TRANSFER_ERROR, null, -len);
            return;
        }
        if (writeProtected()) {
            checkCondition(req, ScsiRequest.sense(ScsiRequest.KEY_DATA_PROTECT, 0x27, 0), false);
            return;
        }
        if ((cdb[1] & 1) != 0) { // ANCHOR
            invalidField(req);
            return;
        }
        if (len == 0) {
            good(req, 0);
            return;
        }
        if (len < 8 || u16(p, 0) + 2 > len || u16(p, 2) + 8 > len) {
            illegal(req, 0x1a); // PARAMETER LIST LENGTH ERROR
            return;
        }
        int descLen = u16(p, 2);
        for (int at = 8; descLen >= 16; at += 16, descLen -= 16) {
            long lba = u64(p, at);
            long nb = u32(p, at + 8) & 0xffffffffL;
            if (!lbaInRange(lba, nb)) {
                illegal(req, 0x21);
                return;
            }
            // Discard: the data may stay (LBPRZ = 0), and on a snapshot nothing reaches the image.
        }
        good(req, -len);
    }

    private void writeSame(ScsiRequest req) throws IOException {
        byte[] cdb = req.cdb();
        boolean ten = (cdb[0] & 0xff) == WRITE_SAME_10;
        long lba = ten ? u32(cdb, 2) & 0xffffffffL : u64(cdb, 2);
        long nb = ten ? u16(cdb, 7) : u32(cdb, 10) & 0xffffffffL;
        if (!available()) {
            checkCondition(req, ScsiRequest.sense(ScsiRequest.KEY_NOT_READY, 0x3a, 0), false);
            return;
        }
        if ((cdb[1] & 0x6) != 0 || nb == 0) { // PBDATA/LBDATA, or WSNZ
            invalidField(req);
            return;
        }
        if (!lbaInRange(lba, nb)) {
            illegal(req, 0x21);
            return;
        }
        byte[] block = new byte[blockSize];
        if (req.data().fromInitiator(block, 0, blockSize) < 0) {
            req.complete(ScsiRequest.TRANSFER_ERROR, null, -blockSize);
            return;
        }
        if (writeProtected()) {
            checkCondition(req, ScsiRequest.sense(ScsiRequest.KEY_DATA_PROTECT, 0x27, 0), false);
            return;
        }
        int per = Math.max(1, CHUNK / blockSize);
        byte[] run = new byte[(int) Math.min(per, nb) * blockSize];
        for (int i = 0; i < run.length; i += blockSize) {
            System.arraycopy(block, 0, run, i, blockSize);
        }
        long off = lba * blockSize;
        long left = nb * blockSize;
        try {
            while (left > 0) {
                int n = (int) Math.min(run.length, left);
                backend.write(off, run, 0, n);
                off += n;
                left -= n;
            }
        } catch (IOException e) {
            log.once("scsi-write-error", e.toString());
            checkCondition(req, ScsiRequest.sense(ScsiRequest.KEY_HARDWARE_ERROR, 0x44, 0), false);
            return;
        }
        good(req, -blockSize);
    }

    private boolean inquiry(byte[] cdb, byte[] out, int xfer) {
        if ((cdb[1] & 1) != 0) {
            return vpd(cdb[2] & 0xff, out);
        }
        if (cdb[2] != 0) {
            return false;
        }
        int buflen = Math.min(xfer, 256);
        out[0] = (byte) peripheralType();
        out[1] = (byte) (removable() ? 0x80 : 0);
        out[2] = 5; // SPC-3
        out[3] = 0x12; // HiSup, response data format 2
        out[4] = (byte) (buflen > 36 ? buflen - 5 : 31);
        out[7] = 0x12; // sync transfers, command queueing
        putAscii(out, 8, vendor, 8);
        putAscii(out, 16, product, 16);
        byte[] v = version.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(v, 0, out, 32, Math.min(4, v.length));
        return true;
    }

    private boolean vpd(int page, byte[] out) {
        int len = 4;
        out[0] = (byte) peripheralType();
        out[1] = (byte) page;
        switch (page) {
            case 0x00 -> {
                out[len++] = 0x00;
                if (serial != null) {
                    out[len++] = (byte) 0x80;
                }
                out[len++] = (byte) 0x83;
                if (type != Type.CDROM) {
                    out[len++] = (byte) 0xb0;
                    out[len++] = (byte) 0xb1;
                    out[len++] = (byte) 0xb2;
                }
            }
            case 0x80 -> {
                if (serial == null) {
                    return false;
                }
                byte[] s = serial.getBytes(StandardCharsets.US_ASCII);
                int l = Math.min(s.length, 36);
                System.arraycopy(s, 0, out, len, l);
                len += l;
            }
            case 0x83 -> {
                String devId = deviceId;
                if (devId == null && serial != null) {
                    devId = serial;
                }
                if (devId != null && !devId.isEmpty()) {
                    byte[] d = devId.getBytes(StandardCharsets.US_ASCII);
                    int l = Math.min(d.length, 255 - 8);
                    out[len++] = 0x2; // ASCII
                    out[len++] = 0;
                    out[len++] = 0;
                    out[len++] = (byte) l;
                    System.arraycopy(d, 0, out, len, l);
                    len += l;
                }
            }
            case 0xb0 -> {
                if (type == Type.CDROM) {
                    return false;
                }
                // Block limits (scsi_emulate_block_limits).
                long maxIo = Integer.MAX_VALUE / blockSize;
                out[len] = 1; // WSNZ
                putU32(out, len + 4, (int) maxIo); // maximum transfer length
                putU32(out, len + 16, (int) (MAX_UNMAP_SIZE / blockSize)); // max unmap LBA count
                putU32(out, len + 20, 255); // max unmap descriptors
                putU32(out, len + 24, Math.max(DISCARD_GRANULARITY, blockSize) / blockSize); // unmap granularity
                putU32(out, len + 36, (int) maxIo); // maximum write same length
                len += 0x3c;
            }
            case 0xb1 -> {
                if (type == Type.CDROM) {
                    return false;
                }
                len = 0x40; // rotation rate etc. all zero
            }
            case 0xb2 -> {
                if (type == Type.CDROM) {
                    return false;
                }
                len = 8; // logical block provisioning
                out[5] = (byte) 0xe0; // LBPU, LBPWS, LBPWS10
                out[6] = 2; // provisioning type: thin
            }
            default -> {
                return false;
            }
        }
        out[3] = (byte) (len - 4);
        return true;
    }

    private int modeSense(byte[] cdb, byte[] out) {
        boolean six = (cdb[0] & 0xff) == MODE_SENSE;
        boolean dbd = (cdb[1] & 0x8) != 0;
        int page = cdb[2] & 0x3f;
        int pc = (cdb[2] & 0xc0) >>> 6;
        int devSpecific = 0;
        if (type != Type.CDROM) {
            if (writeProtected()) {
                devSpecific |= 0x80;
            }
        } else {
            dbd = true; // MMC: no block descriptors
        }
        int p;
        if (six) {
            out[2] = (byte) devSpecific;
            p = 4;
        } else {
            out[3] = (byte) devSpecific;
            p = 8;
        }
        long nb = mediumSize() / blockSize;
        if (!dbd && mediumSize() >= 512) {
            if (six) {
                out[3] = 8;
            } else {
                out[7] = 8;
            }
            if (nb > 0xffffff) {
                nb = 0;
            }
            out[p + 1] = (byte) (nb >>> 16);
            out[p + 2] = (byte) (nb >>> 8);
            out[p + 3] = (byte) nb;
            out[p + 6] = (byte) (blockSize >>> 8);
            p += 8;
        }
        if (pc == 3) {
            return -2;
        }
        if (page == 0x3f) {
            for (int pg = 0; pg <= 0x3e; pg++) {
                int r = modePage(pg, out, p, pc);
                if (r > 0) {
                    p += r;
                }
            }
        } else {
            int r = modePage(page, out, p, pc);
            if (r < 0) {
                return -1;
            }
            p += r;
        }
        if (six) {
            out[0] = (byte) (p - 1);
        } else {
            out[0] = (byte) ((p - 2) >>> 8);
            out[1] = (byte) (p - 2);
        }
        return p;
    }

    /** Write one mode page at {@code at}; returns its length including the 2-byte header, or -1. */
    private int modePage(int page, byte[] out, int at, int pc) {
        int q = at + 2;
        int length;
        boolean disk = type != Type.CDROM;
        switch (page) {
            case 0x04 -> { // rigid disk geometry
                if (!disk) {
                    return -1;
                }
                length = 0x16;
                if (pc != 1) {
                    int[] chs = geometry();
                    int cyls = chs[0];
                    out[q] = (byte) (cyls >>> 16);
                    out[q + 1] = (byte) (cyls >>> 8);
                    out[q + 2] = (byte) cyls;
                    out[q + 3] = (byte) chs[1];
                    out[q + 4] = (byte) (cyls >>> 16);
                    out[q + 5] = (byte) (cyls >>> 8);
                    out[q + 6] = (byte) cyls;
                    out[q + 7] = (byte) (cyls >>> 16);
                    out[q + 8] = (byte) (cyls >>> 8);
                    out[q + 9] = (byte) cyls;
                    out[q + 11] = (byte) 200;
                    out[q + 12] = (byte) 0xff;
                    out[q + 13] = (byte) 0xff;
                    out[q + 14] = (byte) 0xff;
                    out[q + 18] = (byte) (5400 >>> 8);
                    out[q + 19] = (byte) (5400 & 0xff);
                }
            }
            case 0x05 -> { // flexible disk geometry
                if (!disk) {
                    return -1;
                }
                length = 0x1e;
                if (pc != 1) {
                    int[] chs = geometry();
                    out[q] = (byte) (5000 >>> 8);
                    out[q + 1] = (byte) (5000 & 0xff);
                    out[q + 2] = (byte) chs[1];
                    out[q + 3] = (byte) chs[2];
                    out[q + 4] = (byte) (blockSize >>> 8);
                    out[q + 6] = (byte) (chs[0] >>> 8);
                    out[q + 7] = (byte) chs[0];
                    out[q + 8] = (byte) (chs[0] >>> 8);
                    out[q + 9] = (byte) chs[0];
                    out[q + 10] = (byte) (chs[0] >>> 8);
                    out[q + 11] = (byte) chs[0];
                    out[q + 13] = 1;
                    out[q + 14] = 1;
                    out[q + 16] = 1;
                    out[q + 17] = 1;
                    out[q + 18] = 1;
                    out[q + 26] = (byte) (5400 >>> 8);
                    out[q + 27] = (byte) (5400 & 0xff);
                }
            }
            case 0x08 -> { // caching: write cache enabled (cache=writeback)
                length = 0x12;
                out[q] = 4;
            }
            case 0x01 -> { // read/write error recovery
                length = 10;
                if (pc == 1) {
                    if (!disk) {
                        out[q] = (byte) 0x80;
                    }
                } else {
                    out[q] = (byte) 0x80;
                    if (!disk) {
                        out[q + 1] = 0x20;
                    }
                }
            }
            case 0x0e -> { // CD audio control
                if (disk) {
                    return -1;
                }
                length = 14;
            }
            case 0x2a -> { // MM capabilities
                if (disk) {
                    return -1;
                }
                length = 0x14;
                if (pc != 1) {
                    out[q] = 0x3b;
                    out[q + 2] = 0x7f;
                    out[q + 3] = (byte) 0xff;
                    out[q + 4] = (byte) (0x2d | (trayLocked ? 2 : 0));
                    out[q + 6] = (byte) ((50 * 176) >>> 8);
                    out[q + 7] = (byte) (50 * 176);
                    out[q + 9] = 2;
                    out[q + 10] = (byte) (2048 >>> 8);
                    out[q + 12] = (byte) ((16 * 176) >>> 8);
                    out[q + 13] = (byte) (16 * 176);
                    out[q + 16] = (byte) ((16 * 176) >>> 8);
                    out[q + 17] = (byte) (16 * 176);
                    out[q + 18] = (byte) ((16 * 176) >>> 8);
                    out[q + 19] = (byte) (16 * 176);
                }
            }
            default -> {
                return -1;
            }
        }
        out[at] = (byte) page;
        out[at + 1] = (byte) length;
        return length + 2;
    }

    /** Default CHS guess (QEMU's guess_chs_for_size): 16 heads, 63 sectors. Returns {cyls, heads, secs}. */
    private int[] geometry() {
        long sectors = mediumSize() / 512;
        long cyls = sectors / (16 * 63);
        cyls = Math.max(2, Math.min(cyls, 16383));
        return new int[] {(int) cyls, 16, 63};
    }

    private boolean startStop(ScsiRequest req) {
        byte[] cdb = req.cdb();
        boolean start = (cdb[4] & 1) != 0;
        boolean loej = (cdb[4] & 2) != 0;
        if ((cdb[4] & 0xf0) != 0) {
            return true; // power condition: no-op
        }
        if (removable() && loej) {
            if (!start && !trayOpen && trayLocked) {
                checkCondition(req, ScsiRequest.sense(
                        available() ? ScsiRequest.KEY_ILLEGAL_REQUEST : ScsiRequest.KEY_NOT_READY, 0x53, 0x02), false);
                return false;
            }
            if (!start && !trayOpen) {
                ejectNotify = true; // the embedder is told once the command has completed
            }
            trayOpen = !start;
        }
        return true;
    }

    private int readToc(byte[] cdb, byte[] out) {
        boolean msf = (cdb[1] & 2) != 0;
        int format = cdb[2] & 0xf;
        int startTrack = cdb[6] & 0xff;
        int nb = (int) Math.min(blocks(), Integer.MAX_VALUE);
        int q = 2;
        switch (format) {
            case 0 -> {
                if (startTrack > 1 && startTrack != 0xaa) {
                    return -1;
                }
                out[q++] = 1;
                out[q++] = 1;
                if (startTrack <= 1) {
                    out[q++] = 0;
                    out[q++] = 0x14;
                    out[q++] = 1;
                    out[q++] = 0;
                    if (msf) {
                        out[q++] = 0;
                        lbaToMsf(out, q, 0);
                        q += 3;
                    } else {
                        putU32(out, q, 0);
                        q += 4;
                    }
                }
                out[q++] = 0;
                out[q++] = 0x16;
                out[q++] = (byte) 0xaa;
                out[q++] = 0;
                if (msf) {
                    out[q++] = 0;
                    lbaToMsf(out, q, nb);
                    q += 3;
                } else {
                    putU32(out, q, nb);
                    q += 4;
                }
                putU16(out, 0, q - 2);
                return q;
            }
            case 1 -> {
                out[1] = 0x0a;
                out[2] = 1;
                out[3] = 1;
                return 12;
            }
            case 2 -> {
                out[q++] = 1;
                out[q++] = 1;
                int[][] points = {{0xa0, 1}, {0xa1, 1}};
                for (int[] pt : points) {
                    out[q++] = 1;
                    out[q++] = 0x14;
                    out[q++] = 0;
                    out[q++] = (byte) pt[0];
                    q += 4;
                    out[q++] = (byte) pt[1];
                    out[q++] = 0;
                    out[q++] = 0;
                }
                out[q++] = 1;
                out[q++] = 0x14;
                out[q++] = 0;
                out[q++] = (byte) 0xa2;
                q += 3;
                if (msf) {
                    out[q++] = 0;
                    lbaToMsf(out, q, nb);
                    q += 3;
                } else {
                    putU32(out, q, nb);
                    q += 4;
                }
                out[q++] = 1;
                out[q++] = 0x14;
                out[q++] = 0;
                out[q++] = 1;
                q += 3;
                if (msf) {
                    out[q++] = 0;
                    lbaToMsf(out, q, 0);
                    q += 3;
                } else {
                    q += 4;
                }
                putU16(out, 0, q - 2);
                return q;
            }
            default -> {
                return -1;
            }
        }
    }

    private static void lbaToMsf(byte[] b, int at, int lba) {
        lba += 150;
        b[at] = (byte) ((lba / 75) / 60);
        b[at + 1] = (byte) ((lba / 75) % 60);
        b[at + 2] = (byte) (lba % 75);
    }

    private boolean mediaIsDvd() {
        return type == Type.CDROM && available() && mediumSize() / 512 > CD_MAX_SECTORS;
    }

    private boolean mediaIsCd() {
        return type == Type.CDROM && available() && mediumSize() / 512 <= CD_MAX_SECTORS;
    }

    private boolean getConfiguration(byte[] out) {
        if (type != Type.CDROM) {
            return false;
        }
        int current = mediaIsDvd() ? 0x0010 : mediaIsCd() ? 0x0008 : 0;
        putU32(out, 0, 36);
        putU16(out, 6, current);
        out[10] = 0x03;
        out[11] = 8;
        putU16(out, 12, 0x0010);
        out[14] = (byte) (current == 0x0010 ? 1 : 0);
        putU16(out, 16, 0x0008);
        out[18] = (byte) (current == 0x0008 ? 1 : 0);
        putU16(out, 20, 1);
        out[22] = 0x0b;
        out[23] = 8;
        putU32(out, 24, 1);
        out[28] = 1;
        putU16(out, 32, 3);
        out[34] = 0x0b;
        out[35] = 4;
        out[36] = 0x39;
        return true;
    }

    private boolean eventStatus(byte[] cdb, byte[] out) {
        if (type != Type.CDROM || (cdb[1] & 1) == 0) {
            return false;
        }
        int size = 4;
        out[3] = 1 << 4; // supported: media class
        if ((cdb[4] & (1 << 4)) != 0) {
            out[2] = 4; // GESN_MEDIA
            // An event is reported once, and not while the guest has the medium ejected.
            if (!trayOpen) {
                out[4] = (byte) mediaEvent;
                mediaEvent = MEDIA_EVENT_NONE;
            }
            out[5] = (byte) (trayOpen ? 1 : available() ? 2 : 0);
            size += 4;
        } else {
            out[2] = (byte) 0x80;
        }
        putU16(out, 0, size - 4);
        return true;
    }

    private int readDvdStructure(byte[] cdb, byte[] out) {
        if (type != Type.CDROM || cdb[1] != 0) {
            return -1;
        }
        int layer = cdb[6] & 0xff;
        int format = cdb[7] & 0xff;
        int[] capsSize = {2048 + 4, 4 + 4, 0, 188 + 4, 2048 + 4};
        int size;
        if (format != 0xff) {
            if (!available()) {
                return -1;
            }
            if (mediaIsCd()) {
                return -2;
            }
            if (format >= capsSize.length || capsSize[format] == 0) {
                return -1;
            }
            size = capsSize[format];
        } else {
            size = 4;
        }
        switch (format) {
            case 0x00 -> {
                if (layer != 0) {
                    return -1;
                }
                long nb512 = mediumSize() / 512;
                out[4] = 1;
                out[5] = 0xf;
                out[6] = 1;
                putU32(out, 12, (int) ((nb512 >>> 2) - 1));
                putU32(out, 16, (int) ((nb512 >>> 2) - 1));
            }
            case 0x01, 0x04 -> {
            }
            case 0xff -> {
                for (int i = 0; i < capsSize.length; i++) {
                    if (capsSize[i] == 0) {
                        continue;
                    }
                    out[size] = (byte) i;
                    out[size + 1] = 0x40;
                    putU16(out, size + 2, capsSize[i]);
                    size += 4;
                }
            }
            default -> {
                return -1;
            }
        }
        putU16(out, 0, size - 2);
        return size;
    }

    // ---- byte helpers ------------------------------------------------------------------------

    static int u16(byte[] b, int at) {
        return ((b[at] & 0xff) << 8) | (b[at + 1] & 0xff);
    }

    static int u32(byte[] b, int at) {
        return ((b[at] & 0xff) << 24) | ((b[at + 1] & 0xff) << 16) | ((b[at + 2] & 0xff) << 8) | (b[at + 3] & 0xff);
    }

    static long u64(byte[] b, int at) {
        return ((long) u32(b, at) << 32) | (u32(b, at + 4) & 0xffffffffL);
    }

    static void putU16(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 8);
        b[at + 1] = (byte) v;
    }

    static void putU32(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    static void putU64(byte[] b, int at, long v) {
        putU32(b, at, (int) (v >>> 32));
        putU32(b, at + 4, (int) v);
    }

    static void putAscii(byte[] b, int at, String s, int width) {
        byte[] sb = s.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < width; i++) {
            b[at + i] = i < sb.length ? sb[i] : (byte) ' ';
        }
    }
}
