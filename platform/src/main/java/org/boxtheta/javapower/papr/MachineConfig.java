package org.boxtheta.javapower.papr;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Configuration of a {@link Machine}: the equivalent of QEMU's
 * {@code -M pseries,ic-mode=xics -cpu power9 -smp 1 -m <ram> -nodefaults} plus one spapr-vty, one
 * spapr-vscsi and its SCSI devices. Plain mutable fields with fluent setters; read once when the
 * machine is constructed.
 */
public final class MachineConfig {
    /** Default firmware location (the stock SLOF image QEMU ships). */
    public static final Path DEFAULT_FIRMWARE = Path.of("/usr/share/qemu/slof.bin");

    /** One SCSI device on the v-scsi bus. */
    public static final class Disk {
        final ScsiDisk.Type type;
        final Path file;
        final BlockBackend backend;
        int blockSize;
        int physicalBlockSize;
        boolean readOnly;
        boolean persistent;
        String deviceId;
        String serial;
        int scsiId = -1;
        int lun;

        private Disk(ScsiDisk.Type type, Path file, BlockBackend backend) {
            this.type = type;
            this.file = file;
            this.backend = backend;
            this.blockSize = type == ScsiDisk.Type.CDROM ? 2048 : 512;
            this.readOnly = type == ScsiDisk.Type.CDROM;
        }

        /** Logical (and, unless set separately, physical) block size: 512, 2048 or 4096. */
        public Disk blockSize(int bs) {
            this.blockSize = bs;
            return this;
        }

        public Disk physicalBlockSize(int bs) {
            this.physicalBlockSize = bs;
            return this;
        }

        /** Like {@code readonly=on}: the image is opened read-only and the target refuses writes. */
        public Disk readOnly(boolean ro) {
            this.readOnly = ro;
            return this;
        }

        /**
         * Write guest changes to the image file itself. The default is a copy-on-write snapshot
         * discarded at close (QEMU's {@code snapshot=on}), so images are never modified.
         */
        public Disk persistent(boolean p) {
            this.persistent = p;
            return this;
        }

        /** Identifier for VPD page 0x83 (QEMU uses the drive id, e.g. "d0"). */
        public Disk deviceId(String id) {
            this.deviceId = id;
            return this;
        }

        public Disk serial(String s) {
            this.serial = s;
            return this;
        }

        /** SCSI target id; by default the first free id from 0, like QEMU's scsi-id auto-assignment. */
        public Disk scsiId(int id) {
            this.scsiId = id;
            return this;
        }

        public Disk lun(int l) {
            this.lun = l;
            return this;
        }
    }

    long ramSize = 2L << 30;
    Path firmware = DEFAULT_FIRMWARE;
    byte[] firmwareImage;
    CharBackend console;
    final List<Disk> disks = new ArrayList<>();
    Path nvramFile;
    String bootDevice;
    String kernelCmdline;
    boolean bootMenu;
    String uuid;
    String vmName;
    boolean phb = true;
    boolean rng;
    Random random;
    Clock clock = Clock.systemUTC();

    /** Guest RAM in bytes (a multiple of 256 MiB; the reference uses 2 GiB). */
    public MachineConfig ram(long bytes) {
        this.ramSize = bytes;
        return this;
    }

    /** Firmware image file, loaded at real address 0 (QEMU's {@code -bios}). */
    public MachineConfig firmware(Path p) {
        this.firmware = p;
        this.firmwareImage = null;
        return this;
    }

    /** Firmware given as bytes (tests); takes precedence over the path. */
    public MachineConfig firmware(byte[] image) {
        this.firmwareImage = image;
        return this;
    }

    /** Backend of the spapr-vty console (vty@71000001). Defaults to a {@link BufferCharBackend}. */
    public MachineConfig console(CharBackend c) {
        this.console = c;
        return this;
    }

    /** Add a disk ({@code scsi-hd}) backed by an image file. */
    public Disk addDisk(Path image) {
        Disk d = new Disk(ScsiDisk.Type.DISK, image, null);
        disks.add(d);
        return d;
    }

    /** Add a CD-ROM ({@code scsi-cd}) backed by an ISO image; read-only, 2048-byte blocks. */
    public Disk addCdrom(Path iso) {
        Disk d = new Disk(ScsiDisk.Type.CDROM, iso, null);
        disks.add(d);
        return d;
    }

    /** Add a device on an already open backend (tests). The backend is used as is. */
    public Disk addDisk(ScsiDisk.Type type, BlockBackend backend) {
        Disk d = new Disk(type, null, backend);
        d.persistent = true;
        disks.add(d);
        return d;
    }

    /** Back the NVRAM with a file (8 KiB .. 1 MiB) that receives every store. */
    public MachineConfig nvram(Path file) {
        this.nvramFile = file;
        return this;
    }

    /** {@code /chosen/qemu,boot-device} (QEMU's {@code -boot order=...}); null for none. */
    public MachineConfig bootDevice(String s) {
        this.bootDevice = s;
        return this;
    }

    /** {@code /chosen/bootargs} (QEMU's {@code -append}); null for none. */
    public MachineConfig kernelCmdline(String s) {
        this.kernelCmdline = s;
        return this;
    }

    /** {@code /chosen/qemu,boot-menu} (QEMU's {@code -boot menu=on}). */
    public MachineConfig bootMenu(boolean b) {
        this.bootMenu = b;
        return this;
    }

    /** VM UUID; null leaves {@code vm,uuid} all zeros and omits {@code system-id}, as QEMU does. */
    public MachineConfig uuid(String u) {
        this.uuid = u;
        return this;
    }

    /** {@code ibm,partition-name} (QEMU's {@code -name}); null for none. */
    public MachineConfig name(String n) {
        this.vmName = n;
        return this;
    }

    /** Whether to model the default (empty) PCI host bridge QEMU always creates. Default true. */
    public MachineConfig phb(boolean b) {
        this.phb = b;
        return this;
    }

    /** Add a spapr-rng device (H_RANDOM and /ibm,platform-facilities). Default off, like the reference. */
    public MachineConfig rng(boolean b) {
        this.rng = b;
        return this;
    }

    /** Randomness for /chosen/rng-seed and H_RANDOM; defaults to a SecureRandom. */
    public MachineConfig random(Random r) {
        this.random = r;
        return this;
    }

    /** Clock behind get-time-of-day; defaults to the host UTC clock. */
    public MachineConfig clock(Clock c) {
        this.clock = c;
        return this;
    }
}
