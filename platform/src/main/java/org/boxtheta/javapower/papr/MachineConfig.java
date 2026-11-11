package org.boxtheta.javapower.papr;

import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.function.Consumer;
import org.boxtheta.javapower.core.MmioHandler;

/**
 * Configuration of a {@link Machine}: the equivalent of QEMU's
 * {@code -M pseries,ic-mode=xics -cpu power9 -smp <cpus> -m <ram> -nodefaults} plus one spapr-vty, one
 * spapr-vscsi and its SCSI devices. Plain mutable fields with fluent setters; read once when the
 * machine is constructed.
 *
 * <p>What an embedder usually sets: {@link #ram}, {@link #cpus}, the {@link #console} backend
 * (default: an in-memory {@link BufferCharBackend}), disks ({@link #addCdrom}, {@link #addDisk})
 * and the {@link #logSink} for diagnostics (default: the {@link System.Logger}
 * {@value PaprLog#LOGGER_NAME}).
 *
 * <p>Embedders can map devices of their own into the guest's real address space with
 * {@link #addMmio}.
 *
 * <p>There is no default firmware: set one with {@link #firmware(byte[])} or
 * {@link #firmware(Path)}. The SLOF image the machine is built for ships in the separate artifact
 * {@value #SLOF_ARTIFACT}: {@code cfg.firmware(SlofFirmware.image())}.
 */
public final class MachineConfig {
    /** Maven coordinates of the artifact with the SLOF firmware image ({@code SlofFirmware}). */
    public static final String SLOF_ARTIFACT = "org.boxtheta.javapower:javapower-firmware-slof";
    /**
     * Most guest CPUs a machine may have. Each guest CPU is a host thread running in parallel, so
     * the real limit is the host's core count; 64 is an arbitrary bound well above that for the
     * hosts we run on. The device tree grows by about 660 bytes per CPU, far below its 2 MiB
     * reservation and the 2 MiB CAS buffer SLOF provides.
     */
    public static final int MAX_CPUS = 64;
    /**
     * Least guest RAM, 64 MiB. SLOF needs about 37 MiB of real mode area for itself, the ROM
     * filesystem it copies out of the firmware image and the device tree, and the real mode area
     * is the largest power of two not above the RAM size (see {@link #ram}).
     */
    public static final long MIN_RAM = 64L << 20;
    /**
     * Guest RAM is a whole number of these, 16 MiB: the smallest logical memory block size
     * PowerVM offers, and Linux's memory section size on ppc64.
     */
    public static final long RAM_GRANULE = 16L << 20;

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

    /**
     * One embedder MMIO region ({@link #addMmio}): its address range, handler, byte order, and
     * optionally a device tree node and an interrupt.
     */
    public static final class Mmio {
        final String name;
        final long base;
        final long size;
        final MmioHandler handler;
        ByteOrder order = ByteOrder.BIG_ENDIAN;
        String[] compatible;
        Consumer<Fdt.Node> deviceTree;
        /** 0: no interrupt; 1: level-triggered; 2: edge-triggered. */
        int interrupt;

        private Mmio(String name, long base, long size, MmioHandler handler) {
            this.name = name;
            this.base = base;
            this.size = size;
            this.handler = handler;
        }

        /**
         * The byte order of the handler's values: how they map onto the bytes at the region's
         * addresses (see {@link MmioHandler}). Default {@link ByteOrder#BIG_ENDIAN}, the order
         * of the device tree and of {@code H_LOGICAL_CI_LOAD}; PCI-style devices are usually
         * {@link ByteOrder#LITTLE_ENDIAN}. Guests of either byte order see the same bytes.
         */
        public Mmio byteOrder(ByteOrder o) {
            this.order = Objects.requireNonNull(o, "order");
            return this;
        }

        /**
         * Describe the region in the device tree with a node {@code /<name>@<base in hex>} whose
         * {@code compatible} property holds {@code values}; it also has {@code reg} (the range;
         * the root has two address and two size cells) and, with {@link #interrupt}, the
         * {@code interrupts} (source number, 1 for level or 0 for edge) and
         * {@code interrupt-parent} (the XICS) properties. Linux and FreeBSD match drivers to
         * such nodes by {@code compatible}.
         */
        public Mmio compatible(String... values) {
            if (values.length == 0) {
                throw new IllegalArgumentException("MMIO region " + name + ": compatible needs at least one value");
            }
            this.compatible = values.clone();
            return this;
        }

        /**
         * Add to the region's device tree node (created as for {@link #compatible} if that is not
         * set): {@code props} is called with the node, after the machine's own properties, each
         * time the machine builds its device tree (at reset and when the guest negotiates its
         * features), on the thread doing that. It must add the same properties every time.
         */
        public Mmio deviceTree(Consumer<Fdt.Node> props) {
            this.deviceTree = Objects.requireNonNull(props, "props");
            return this;
        }

        /**
         * Give the region an interrupt source of the XICS, level-triggered (an LSI: pending while
         * raised) or edge-triggered (an MSI: each raising edge is one interrupt). The machine
         * numbers the sources from {@link Xics#IRQ_MMIO} in the order the regions were added;
         * {@link Machine#mmioInterrupt} returns the number, and
         * {@link Xics#setIrq Machine.xics().setIrq(number, level)} drives it from any thread.
         */
        public Mmio interrupt(boolean levelTriggered) {
            this.interrupt = levelTriggered ? 1 : 2;
            return this;
        }

        boolean hasNode() {
            return compatible != null || deviceTree != null;
        }
    }

    /**
     * Real addresses QEMU's {@code pseries} keeps for the windows of its PCI host bridges
     * ({@code SPAPR_PCI_BASE} 32 TiB to {@code SPAPR_PCI_LIMIT} 64 TiB); no MMIO region may lie
     * there.
     */
    public static final long PCI_WINDOWS_BASE = 1L << 45;
    /** End of the PCI host bridge windows; see {@link #PCI_WINDOWS_BASE}. */
    public static final long PCI_WINDOWS_END = 1L << 46;
    /** MMIO regions lie below this real address, the end of what the radix MMU translates to. */
    public static final long MMIO_LIMIT = 1L << 57;

    long ramSize = 2L << 30;
    int cpus = 1;
    Path firmware;
    byte[] firmwareImage;
    CharBackend console;
    final List<Disk> disks = new ArrayList<>();
    final List<Mmio> mmios = new ArrayList<>();
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
    Consumer<PaprLog.Entry> logSink;

    /**
     * Guest RAM in bytes: at least {@link #MIN_RAM} (64 MiB) and a multiple of
     * {@link #RAM_GRANULE} (16 MiB); default 2 GiB, as in the reference. Like QEMU, the machine
     * makes the largest power of two not above the RAM size its real mode area ({@code memory@0}
     * in the device tree, the only memory SLOF uses) and describes the rest in further
     * {@code memory@} nodes. QEMU itself only accepts multiples of 256 MiB; for those the device
     * tree is the same as QEMU's.
     *
     * @throws IllegalArgumentException if {@code bytes} is below 64 MiB or not a multiple of 16 MiB
     */
    public MachineConfig ram(long bytes) {
        if (bytes < MIN_RAM || bytes % RAM_GRANULE != 0) {
            throw new IllegalArgumentException("RAM size must be at least " + (MIN_RAM >> 20)
                    + " MiB and a multiple of " + (RAM_GRANULE >> 20) + " MiB, got " + bytes + " bytes");
        }
        this.ramSize = bytes;
        return this;
    }

    /**
     * Number of guest CPUs, like QEMU's {@code -smp n}: n cores of one thread each, CPU (and
     * interrupt server) numbers 0..n-1, CPU 0 boots. 1 .. {@link #MAX_CPUS}; default 1.
     */
    public MachineConfig cpus(int n) {
        if (n < 1 || n > MAX_CPUS) {
            throw new IllegalArgumentException("cpus must be 1.." + MAX_CPUS + ", got " + n);
        }
        this.cpus = n;
        return this;
    }

    /**
     * Firmware image file, loaded at real address 0 (QEMU's {@code -bios}); read when the machine
     * is constructed. Replaces an image set with {@link #firmware(byte[])}.
     */
    public MachineConfig firmware(Path p) {
        this.firmware = p;
        this.firmwareImage = null;
        return this;
    }

    /**
     * Firmware image given as bytes, e.g. {@code SlofFirmware.image()} from {@value #SLOF_ARTIFACT}.
     * The machine copies it when it is constructed. Replaces a file set with {@link #firmware(Path)}.
     */
    public MachineConfig firmware(byte[] image) {
        this.firmwareImage = image;
        this.firmware = null;
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

    /**
     * Map a device of the embedder's into the guest's real address space: data loads and stores
     * the guest makes to {@code [base, base + size)}, with MMU translation or in real mode, from
     * the interpreter or compiled code, and through {@code H_LOGICAL_CI_LOAD} and
     * {@code H_LOGICAL_CI_STORE} (how SLOF reaches devices), call {@code handler} on the CPU's
     * thread. See {@link MmioHandler} for the calls and the threads, and the returned
     * {@link Mmio} for the byte order, a device tree node and an interrupt. The handler's
     * {@link MmioHandler#saveState state} is part of the saved machine state, and the regions'
     * names, ranges, byte orders and interrupts are part of the hardware a saved state must
     * match.
     *
     * <p>The range must not overlap guest RAM (checked when the machine is built, since the RAM
     * size may still change), the PCI host bridge windows ({@link #PCI_WINDOWS_BASE} to
     * {@link #PCI_WINDOWS_END}) or another region, and must lie below {@link #MMIO_LIMIT}.
     * Accesses outside every region and outside RAM raise a machine check, as before.
     *
     * @param name the region's name in messages and saved states, and the name of its device
     *     tree node: 1 to 31 letters, digits and {@code , . _ + -}, starting with a letter, and
     *     unique among the regions
     * @throws IllegalArgumentException if the name, the range or an overlap is invalid
     */
    public Mmio addMmio(String name, long base, long size, MmioHandler handler) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(handler, "handler");
        if (!name.matches("[A-Za-z][A-Za-z0-9,._+-]{0,30}")) {
            throw new IllegalArgumentException("MMIO region name \"" + name + "\" is not a device tree node name: 1 to 31"
                    + " letters, digits and , . _ + -, starting with a letter");
        }
        String what = String.format("MMIO region %s (0x%x bytes at 0x%x)", name, size, base);
        if (size <= 0 || base < 0 || base >= MMIO_LIMIT || size > MMIO_LIMIT - base) {
            throw new IllegalArgumentException(what + " must be a non-empty range below 0x"
                    + Long.toHexString(MMIO_LIMIT));
        }
        if (base < PCI_WINDOWS_END && PCI_WINDOWS_BASE < base + size) {
            throw new IllegalArgumentException(what + " overlaps the PCI host bridge windows [0x"
                    + Long.toHexString(PCI_WINDOWS_BASE) + ", 0x" + Long.toHexString(PCI_WINDOWS_END) + ")");
        }
        for (Mmio m : mmios) {
            if (m.name.equals(name)) {
                throw new IllegalArgumentException("there is already an MMIO region named " + name);
            }
            if (base < m.base + m.size && m.base < base + size) {
                throw new IllegalArgumentException(String.format("%s overlaps MMIO region %s (0x%x bytes at 0x%x)", what,
                        m.name, m.size, m.base));
            }
        }
        Mmio m = new Mmio(name, base, size, handler);
        mmios.add(m);
        return m;
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

    /**
     * Where the machine's diagnostics go (see {@link PaprLog}); null, the default, forwards them to
     * the {@link System.Logger} named {@value PaprLog#LOGGER_NAME}. Use
     * {@link PaprLog#printTo PaprLog.printTo(System.err)} for plain lines on stderr. The sink is
     * called from emulator threads and must be thread-safe.
     */
    public MachineConfig logSink(Consumer<PaprLog.Entry> sink) {
        this.logSink = sink;
        return this;
    }
}
