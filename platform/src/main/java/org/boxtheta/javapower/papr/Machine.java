package org.boxtheta.javapower.papr;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.Hypervisor;
import org.boxtheta.javapower.core.MmioBus;
import org.boxtheta.javapower.core.MmioHandler;
import org.boxtheta.javapower.core.MmioRegion;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.core.Spr;

/**
 * The PAPR {@code pseries} machine: guest RAM, the SLOF firmware image, the device tree, XICS,
 * the VIO devices (nvram, vty, v-scsi and those added with {@link MachineConfig#addVioDevice}) and
 * the hypervisor itself. Mirrors QEMU's
 * {@code -M pseries,ic-mode=xics,usb=off -cpu power9 -smp N -nodefaults} closely enough for the
 * stock SLOF binary.
 *
 * <p>Most embedders should use {@code org.boxtheta.javapower.Emulator} (module {@code emulator}),
 * which creates the CPUs and runs them; this class is the machine model it drives, for embedders
 * that bring their own CPU loop:
 * <pre>{@code
 * MachineConfig cfg = new MachineConfig().ram(2L << 30).cpus(4).console(new StdioCharBackend())
 *         .firmware(SlofFirmware.image());   // javapower-firmware-slof
 * cfg.addDisk(Path.of("disk.raw")).blockSize(4096);
 * Machine m = new Machine(cfg);
 * List<CpuState> cpus = ...;      // m.cpuCount() CpuStates, each with an interpreter on m.memory()
 * m.attachCpus(cpus);             // sets cpus.get(i).index = i
 * m.reset();                      // CPU 0 at the SLOF entry point, the others RTAS-stopped (halted)
 * // run each CPU on its own thread; a CPU whose halted flag is set must park until it is
 * // cleared (start-cpu). When a CPU returns with stopRequested, look at m.shutdownReason():
 * // REBOOT -> stop and join every CPU thread, m.reset(), run again; anything else -> m.close().
 * }</pre>
 * A single-CPU machine can still use {@code attachCpu(cpu)} and {@code reset(cpu)}.
 *
 * <p>Each machine has its own {@link #log() log} (sink and once-per-key counts) and owns the host
 * resources its configuration names (disk images, NVRAM file, console backend, added VIO
 * devices), which {@link #close} releases. Machines are independent: several may live in one JVM.
 *
 * <h2>Threading and locking</h2>
 * Every CPU runs on its own host thread and calls {@link #hcall} on that thread; console input
 * arrives on backend threads and {@link #pump} runs on whatever thread the application chooses.
 * The locks, always taken in this order (a thread holding one never waits for an earlier one):
 * <ol>
 * <li>The <b>machine lock</b> ({@code lock}): partition-wide state. RTAS calls, CAS,
 *     KVMPPC_H_UPDATE_DT, H_REGISTER_VPA, H_SET_MODE (LE/AIL), H_REGISTER_PROC_TBL,
 *     H_SIGNAL_SYS_RESET, H_HOME_NODE_ASSOCIATIVITY, H_WATCHDOG, HPT resize, {@link #reset},
 *     {@link #attachCpu}, {@link #pump}, {@link #close}. CPU start/stop (start-cpu, stop-self)
 *     happens under it, so a CPU's {@link CpuState#halted} flag only changes with it held.</li>
 * <li>A <b>device lock</b> per VIO device: CRQ registration and traffic (H_REG_CRQ,
 *     H_FREE_CRQ, H_SEND_CRQ including the whole SCSI command), H_VIO_SIGNAL, device reset. The
 *     vty has separate input and output locks instead, so console output never waits for input.</li>
 * <li>Leaf locks: the {@link Xics} monitor (all ICS and ICP state; H_CPPR, H_IPI, H_XIRR,
 *     H_XIRR_X, H_EOI, H_IPOLL and every interrupt source change) and each {@link TceTable}.
 *     Holders of a leaf lock only call {@link CpuState#setExternalIrq}, which takes no lock.</li>
 * </ol>
 * The frequent calls therefore never take the machine lock: H_CEDE, H_CONFER, H_PROD and H_JOIN
 * touch only {@code CpuState} volatiles and this CPU's own fields; the XICS calls hold the short
 * XICS lock; H_PUT_TERM_CHAR/H_GET_TERM_CHAR hold only the vty locks; TCE calls lock one table;
 * H_LOGICAL_* and H_PAGE_INIT touch only guest memory and MMIO regions, whose handlers are
 * called with no lock held.
 *
 * <p>Other CPUs' state: a CPU's plain fields are written only by its own thread, with one
 * exception: start-cpu (holding the machine lock) writes the entry state into the stopped target,
 * posts the same state with {@link CpuState#runOnCpu} (see {@code startCpu} for why), then
 * clears {@code halted}, whose volatile write publishes it. Partition-wide updates (LPCR from
 * H_SET_MODE and H_REGISTER_PROC_TBL, {@code procTable}, PCR from CAS) apply at once to the
 * calling CPU and reach every other CPU, stopped or running, through {@link CpuState#runOnCpu},
 * which its run loop executes on its own thread (QEMU's {@code run_on_cpu}); system resets aimed
 * at other CPUs (H_SIGNAL_SYS_RESET, watchdog) travel the same way. The volatiles other threads
 * write directly are {@code extIrq} (XICS), {@code prodded} (H_PROD), {@code stopRequested}
 * (shutdown) and {@code halted} (start-cpu). {@link #reset} and {@link #attachCpus} must only be
 * called while no CPU thread is running.
 */
public final class Machine implements Hypervisor, AutoCloseable {
    /** Where SLOF starts (QEMU's SPAPR_ENTRY_POINT). */
    public static final long ENTRY_POINT = 0x100;
    /** Largest firmware image (QEMU's FW_MAX_SIZE). */
    public static final int FW_MAX_SIZE = 0x400000;
    /** Space reserved for the device tree (QEMU's FDT_MAX_SIZE). */
    public static final int FDT_MAX_SIZE = 0x200000;
    private static final long FDT_MAX_ADDR = 0x80000000L;

    static final int PVR_LOGICAL_2_05 = 0x0f000002;
    static final int PVR_LOGICAL_2_06 = 0x0f000003;
    static final int PVR_LOGICAL_2_06_PLUS = 0x0f100003;
    static final int PVR_LOGICAL_2_07 = 0x0f000004;
    static final int PVR_LOGICAL_3_00 = 0x0f000005;

    static final long PATE1_GR = 1L << 63;

    // SPR numbers not in core's Spr.
    static final int SPR_AMOR = 349;
    static final int SPR_PSSCR = 855;
    static final int SPR_PMCR = 884;
    static final int SPR_RPR = 186;
    static final int SPR_DAWR0 = Spr.DAWR0;
    static final int SPR_DAWRX0 = Spr.DAWRX0;
    static final int SPR_CIABR = Spr.CIABR;
    static final int SPR_PCR = 338;

    /** LPCR at the SLOF entry point: LD | PECE (PDEE HDEE EEE DEE OEE) | LPES0. */
    static final long LPCR_BOOT = 0x3f008L;
    /** POWER9 power-saving exit enables (QEMU's lpcr_pm): PDEE HDEE EEE DEE OEE, ISA bits 47..51. */
    static final long LPCR_PM = 0x1f000L;
    /** PSSCR[EC], ISA bit 43. */
    static final long PSSCR_EC = 1L << 20;
    /** PCR compatibility-level bits (ISA bits 60..62) a POWER9 implements. */
    static final long PCR_COMPAT_2_07 = 1L << 3;
    static final long PCR_COMPAT_2_06 = 1L << 2;
    static final long PCR_COMPAT_2_05 = 1L << 1;
    /** Offset of the dispatch (yield) counter in a VPA. */
    static final int VPA_DISPATCH_COUNTER = 0x100;

    /** Why the machine asked the CPU to stop. */
    public enum ShutdownReason {
        /** Running (or never started). */
        NONE,
        /** RTAS power-off, or a watchdog with the power-off action. */
        POWER_OFF,
        /** RTAS system-reboot or a watchdog restart: call {@link #reset} and run again. */
        REBOOT,
        /** ibm,os-term: the OS reported a fatal condition ({@link #shutdownMessage()} has its text). */
        PANIC,
        /** The guest made a request QEMU would exit on (e.g. asked for XIVE at CAS). */
        FATAL,
    }

    private final Object lock = new Object();
    private final MachineConfig config;
    private final PhysicalMemory mem;
    private final PaprLog log;
    private final Xics xics;
    private final Random random;
    private final byte[] firmware;

    private final Nvram nvram;
    private final Vty vty;
    private final VScsi vscsi;
    private final List<VioDevice> vio = new ArrayList<>();
    /** The devices from {@code MachineConfig.addedVio} this machine attached, in that order. */
    private final List<VioDevice> addedVio = new ArrayList<>();
    private final List<TceTable> tceTables = new ArrayList<>();
    private TceTable phbTce;
    private final List<Drc> drcs = new ArrayList<>();
    private final Rtas rtas;
    /** The MMIO regions every CPU and H_LOGICAL_CI_* reach outside RAM. */
    private final MmioBus mmio = new MmioBus();
    /** The embedder's regions ({@link MachineConfig#addMmio}), in the order they were added. */
    private final List<MmioDevice> mmioDevices = new ArrayList<>();

    /** An embedder MMIO region as this machine built it: its interrupt source, or 0 for none. */
    record MmioDevice(MachineConfig.Mmio config, MmioRegion region, int irq) {}

    /** Per-vCPU hypervisor state, indexed by CPU number. */
    private final VCpu[] vcpus;
    private volatile ShutdownReason shutdown = ShutdownReason.NONE;
    private volatile String shutdownMessage;
    private volatile Consumer<CpuState> systemResetHandler;

    // Machine state reset at every machine reset. Guarded by the machine lock.
    private OptionVector ov5 = new OptionVector();
    private OptionVector ov5Cas = new OptionVector();
    private int compatPvr;
    private boolean casPreIsa3;
    private long patbEntry;
    private int htabShift;
    private byte[] rngSeed = new byte[32];
    private byte[] fdtBlob;
    private int fdtInitialSize;
    private volatile long fdtAddr;
    private final Watchdog[] watchdogs = {new Watchdog(), new Watchdog(), new Watchdog(), new Watchdog()};
    private boolean closed;

    /**
     * What the hypervisor keeps per vCPU (QEMU's SpaprCpuState). The registrations are written
     * under the machine lock by any CPU (H_REGISTER_VPA names its target) and read without it by
     * the target's own thread (dispatch counter updates) and by H_CONFER, hence volatile.
     */
    private static final class VCpu {
        final int id;
        volatile CpuState cpu;
        volatile long vpaAddr;
        volatile long slbShadowAddr;
        volatile int slbShadowSize;
        volatile long dtlAddr;
        volatile int dtlSize;

        VCpu(int id) {
            this.id = id;
        }

        void clearRegistrations() {
            vpaAddr = slbShadowAddr = dtlAddr = 0;
            slbShadowSize = dtlSize = 0;
        }
    }

    /** One of the four PAPR watchdogs (H_WATCHDOG), timed on the host monotonic clock. */
    private static final class Watchdog {
        long deadline;
        boolean armed;
        int action;
        boolean leaveOthers;
    }

    /**
     * Build the machine described by {@code config}: allocate guest RAM, load the firmware, open
     * the disk images and the NVRAM file, and start the console backend. If this fails, everything
     * opened so far is closed again before the exception propagates, including the added VIO
     * devices created so far. Added devices are started ({@link VioDevice#startDevice}) last, in
     * the order they were added.
     *
     * @throws IOException if the firmware, a disk image or the NVRAM file cannot be read
     * @throws IllegalArgumentException if the configuration is invalid, e.g. no firmware is set
     */
    public Machine(MachineConfig config) throws IOException {
        this.config = config;
        this.log = new PaprLog(config.logSink);
        byte[] fw;
        if (config.firmwareImage != null) {
            fw = config.firmwareImage.clone();
        } else if (config.firmware != null) {
            fw = Files.readAllBytes(config.firmware);
        } else {
            throw new IllegalArgumentException("no firmware configured: set MachineConfig.firmware(bytes) or"
                    + " firmware(path); the SLOF image is SlofFirmware.image() in " + MachineConfig.SLOF_ARTIFACT);
        }
        if (fw.length > FW_MAX_SIZE) {
            throw new IOException("firmware image larger than " + FW_MAX_SIZE + " bytes");
        }
        this.firmware = fw;
        this.xics = new Xics(log);
        this.mem = new PhysicalMemory(config.ramSize);
        this.random = config.random != null ? config.random : new SecureRandom();

        // One presentation controller per CPU; its output drives whichever CpuState is attached.
        vcpus = new VCpu[config.cpus];
        for (int i = 0; i < vcpus.length; i++) {
            VCpu v = new VCpu(i);
            vcpus[i] = v;
            xics.addServer(i, level -> {
                CpuState c = v.cpu;
                if (c != null) {
                    c.setExternalIrq(level);
                }
            });
        }

        // Static machine option vector 5 (spapr_machine_init).
        ov5.set(OptionVector.OV5_DRCONF_MEMORY).set(OptionVector.OV5_FORM1_AFFINITY)
                .set(OptionVector.OV5_FORM2_AFFINITY).set(OptionVector.OV5_HP_EVT)
                .set(OptionVector.OV5_HPT_RESIZE).set(OptionVector.OV5_DRMEM_V2)
                .set(OptionVector.OV5_MMU_RADIX_300).set(OptionVector.OV5_MMU_RADIX_GTSE);

        // Interrupt sources claimed at machine init: EPOW and hot-plug event sources, PHB LSIs.
        xics.claim(Xics.IRQ_EPOW, false);
        xics.claim(Xics.IRQ_HOTPLUG, false);
        if (config.phb) {
            for (int i = 0; i < 4; i++) {
                xics.claim(DeviceTree.PHB0_LSI_BASE + i, true);
            }
        }

        buildMmio();

        // VIO devices in creation order: nvram (always first), vty, v-scsi.
        nvram = config.nvramFile != null ? new Nvram(config.nvramFile) : new Nvram();
        CharBackend console = config.console != null ? config.console : new BufferCharBackend();
        vty = new Vty(console);
        vscsi = new VScsi();
        try {
            buildDevices();
        } catch (IOException | RuntimeException | Error e) {
            closeBackends(e, false);
            throw e;
        }
        rtas = new Rtas(this);
        vty.start();
        for (VioDevice d : addedVio) {
            try {
                d.startDevice();
            } catch (RuntimeException | Error e) {
                closeBackends(e, true);
                throw e;
            }
        }
    }

    /**
     * Map the embedder's MMIO regions and claim their interrupt sources. Checks what
     * {@link MachineConfig#addMmio} could not: that no region overlaps RAM.
     */
    private void buildMmio() {
        int nextIrq = Xics.IRQ_MMIO;
        for (MachineConfig.Mmio c : config.mmios) {
            if (c.base < config.ramSize) {
                throw new IllegalArgumentException(String.format("MMIO region %s (0x%x bytes at 0x%x) overlaps guest RAM"
                        + " (0x%x bytes at 0)", c.name, c.size, c.base, config.ramSize));
            }
            int irq = 0;
            if (c.interrupt != 0) {
                if (nextIrq >= Xics.IRQ_BASE + Xics.NR_IRQS) {
                    throw new IllegalArgumentException("more than " + Xics.MMIO_IRQS + " MMIO regions with an interrupt");
                }
                irq = nextIrq++;
                xics.claim(irq, c.interrupt == 1);
            }
            MmioRegion r = new MmioRegion(c.name, c.base, c.size, c.order, c.handler);
            mmio.map(r);
            mmioDevices.add(new MmioDevice(c, r, irq));
        }
    }

    /** The rest of the constructor: everything that may fail after the NVRAM file is open. */
    private void buildDevices() throws IOException {
        int reg = VioDevice.REG_BASE;
        for (VioDevice d : List.of(nvram, vty, vscsi)) {
            d.attach(this, reg++);
            xics.claim(d.irq(), false);
            vio.add(d);
            if (d.tce() != null) {
                tceTables.add(d.tce());
            }
        }
        // Devices added through the configuration, in the order they were added.
        for (java.util.function.Supplier<? extends VioDevice> f : config.addedVio) {
            VioDevice d = f.get();
            if (d == null) {
                throw new IllegalArgumentException("a VIO device factory returned null");
            }
            d.attach(this, reg++);
            addedVio.add(d);
            xics.claim(d.irq(), false);
            vio.add(d);
            if (d.tce() != null) {
                tceTables.add(d.tce());
            }
        }
        if (config.phb) {
            TceTable t = new TceTable(DeviceTree.PHB0_LIOBN, 0, DeviceTree.PHB0_DMA_WINDOW, log);
            tceTables.add(t);
            phbTce = t;
        }

        // SCSI devices on the v-scsi bus.
        boolean[] used = new boolean[64];
        for (MachineConfig.Disk d : config.disks) {
            if (d.scsiId >= 0) {
                used[d.scsiId] = true;
            }
        }
        int n = 0;
        for (MachineConfig.Disk d : config.disks) {
            int id = d.scsiId;
            if (id < 0) {
                id = 0;
                while (used[id]) {
                    id++;
                }
                used[id] = true;
            }
            BlockBackend b = d.backend;
            if (b == null) {
                b = BlockBackend.open(d.file, d.readOnly, !d.persistent);
            }
            String devId = d.deviceId != null ? d.deviceId : "d" + n;
            int pbs = d.physicalBlockSize > 0 ? d.physicalBlockSize : d.blockSize;
            ScsiDisk disk;
            try {
                disk = new ScsiDisk(d.type, b, d.blockSize, pbs, devId, d.serial, 0, id, d.lun);
            } catch (RuntimeException e) {
                closeQuietly(b, e);
                throw e;
            }
            disk.setLog(log);
            vscsi.addTarget(disk);
            n++;
        }

        // DR connectors: one per CPU core, 31 PHB slots, and the 256 PCI slots of PHB 0.
        for (int i = 0; i < config.cpus; i++) {
            int id = i;
            drcs.add(new Drc(Drc.Kind.CPU, id, () -> DeviceTree.cpuNode(this, id)));
        }
        for (int i = 0; i < DeviceTree.MAX_PHBS; i++) {
            drcs.add(new Drc(Drc.Kind.PHB, i, i == 0 && config.phb ? () -> DeviceTree.phbNode(this) : null));
        }
        if (config.phb) {
            for (int i = 0; i < 256; i++) {
                drcs.add(new Drc(Drc.Kind.PCI, i, null));
            }
        }
    }

    private static void closeQuietly(AutoCloseable c, Throwable primary) {
        try {
            c.close();
        } catch (Exception e) {
            primary.addSuppressed(e);
        }
    }

    // ---- accessors ---------------------------------------------------------------------------

    public MachineConfig config() {
        return config;
    }

    /** This machine's diagnostics: messages go to the configured sink, once-per-key counts stay here. */
    public PaprLog log() {
        return log;
    }

    public PhysicalMemory memory() {
        return mem;
    }

    public Hypervisor hypervisor() {
        return this;
    }

    public Xics xics() {
        return xics;
    }

    public Vty vty() {
        return vty;
    }

    public VScsi vscsi() {
        return vscsi;
    }

    public Nvram nvram() {
        return nvram;
    }

    /**
     * The MMIO regions the CPUs reach outside RAM: the embedder's ({@link MachineConfig#addMmio})
     * and any the platform maps. Whoever runs the CPUs gives it to their MMUs (the
     * {@code emulator} module does). Platform code may map and unmap regions on it while the
     * CPUs run; such regions are not in the device tree or the saved state unless their owner
     * puts them there.
     */
    public MmioBus mmio() {
        return mmio;
    }

    /**
     * The XICS source number of the interrupt of the MMIO region {@code name}
     * ({@link MachineConfig.Mmio#interrupt}). Drive it with
     * {@link Xics#setIrq xics().setIrq(number, level)} from any thread, or
     * {@link Xics#pulse xics().pulse(number)} for an edge-triggered one.
     *
     * @throws IllegalArgumentException if there is no such region or it has no interrupt
     */
    public int mmioInterrupt(String name) {
        for (MmioDevice d : mmioDevices) {
            if (d.region().name().equals(name)) {
                if (d.irq() == 0) {
                    throw new IllegalArgumentException("MMIO region " + name + " has no interrupt");
                }
                return d.irq();
            }
        }
        throw new IllegalArgumentException("no MMIO region named " + name);
    }

    List<MmioDevice> mmioDevices() {
        return Collections.unmodifiableList(mmioDevices);
    }

    /**
     * Every VIO device in unit address order: nvram, vty, v-scsi, then the devices added through
     * {@link MachineConfig#addVioDevice} in the order they were added. Fixed for the machine's life.
     */
    public List<VioDevice> vioDevices() {
        return Collections.unmodifiableList(vio);
    }

    /**
     * The VIO device at {@code unitAddress} if it is a {@code type}, otherwise null. For example
     * {@code m.vioDevice(reg, MyDevice.class)} with the address {@link MachineConfig#addVioDevice}
     * returned. Safe from any thread.
     */
    public <T extends VioDevice> T vioDevice(int unitAddress, Class<T> type) {
        VioDevice d = vioByReg(unitAddress & 0xffffffffL);
        return type.isInstance(d) ? type.cast(d) : null;
    }

    List<Drc> drcs(Drc.Kind kind) {
        List<Drc> r = new ArrayList<>();
        for (Drc d : drcs) {
            if (d.kind == kind) {
                r.add(d);
            }
        }
        return r;
    }

    Drc drcByIndex(int index) {
        for (Drc d : drcs) {
            if (d.index() == index) {
                return d;
            }
        }
        return null;
    }

    OptionVector ov5Cas() {
        return ov5Cas;
    }

    int compatPvr() {
        return compatPvr;
    }

    boolean casPreIsa3() {
        return casPreIsa3;
    }

    int htabShift() {
        return htabShift;
    }

    byte[] rngSeed() {
        return rngSeed;
    }

    /**
     * The real mode area: the largest power of two not above the RAM size, at most 1 TiB. QEMU
     * gets the same through its implicit NUMA node 0 ({@code spapr_node0_size} rounds the node
     * down to a power of two).
     */
    long rmaSize() {
        return rmaSize(config.ramSize);
    }

    static long rmaSize(long ramSize) {
        return Math.min(Long.highestOneBit(ramSize), 1L << 40);
    }

    Random random() {
        return random;
    }

    /** Number of guest CPUs ({@link MachineConfig#cpus}). */
    public int cpuCount() {
        return vcpus.length;
    }

    /** The CpuState attached as CPU {@code id}, or null. */
    public CpuState cpu(int id) {
        return id >= 0 && id < vcpus.length ? vcpus[id].cpu : null;
    }

    /** The attached CPUs in CPU-number order. */
    public List<CpuState> cpus() {
        List<CpuState> r = new ArrayList<>();
        for (VCpu v : vcpus) {
            if (v.cpu != null) {
                r.add(v.cpu);
            }
        }
        return Collections.unmodifiableList(r);
    }

    /** The attached CPU numbered {@code id} (any long the guest passes), or null (QEMU's spapr_find_cpu). */
    CpuState findCpu(long id) {
        return id >= 0 && id < vcpus.length ? vcpus[(int) id].cpu : null;
    }

    private VCpu findVcpu(long id) {
        return id >= 0 && id < vcpus.length && vcpus[(int) id].cpu != null ? vcpus[(int) id] : null;
    }

    /** The hypervisor state of the calling CPU. */
    private VCpu self(CpuState s) {
        int i = s.index;
        if (i < 0 || i >= vcpus.length || vcpus[i].cpu != s) {
            throw new IllegalStateException("hypercall from a CPU not attached to this machine (index " + i + ")");
        }
        return vcpus[i];
    }

    /** Real address of the VPA registered for CPU {@code id} (0 if none); tests and debugging. */
    public long vpaAddress(int id) {
        return vcpus[id].vpaAddr;
    }

    /** The device tree blob the hypervisor currently holds (reset, CAS or KVMPPC_H_UPDATE_DT). */
    public byte[] fdtBlob() {
        synchronized (lock) {
            return fdtBlob.clone();
        }
    }

    /** Real address the reset device tree was written to (passed to SLOF in r3). */
    public long fdtAddress() {
        return fdtAddr;
    }

    /** The device tree as built at reset, as a node tree (tests, dumping). */
    public Fdt.Node buildDeviceTree(boolean reset) {
        synchronized (lock) {
            return DeviceTree.build(this, reset);
        }
    }

    public ShutdownReason shutdownReason() {
        return shutdown;
    }

    public String shutdownMessage() {
        return shutdownMessage;
    }

    /**
     * Install the action that delivers a system reset interrupt to a CPU (H_SIGNAL_SYS_RESET, the
     * watchdog's dump-restart action). It is always called on the target CPU's own thread: directly
     * when a CPU resets itself, otherwise through {@link CpuState#runOnCpu}. Without one
     * H_SIGNAL_SYS_RESET is reported as unimplemented and fails with H_HARDWARE.
     */
    public void setSystemResetHandler(Consumer<CpuState> h) {
        this.systemResetHandler = h;
    }

    // ---- CPU wiring and reset ----------------------------------------------------------------

    /**
     * Connect {@code s} as CPU number {@code s.index} (0 for a single-CPU machine): its interrupt
     * input, and this machine as its hypervisor. Attaching the same object again is a no-op.
     */
    public void attachCpu(CpuState s) {
        synchronized (lock) {
            int i = s.index;
            if (i < 0 || i >= vcpus.length) {
                throw new IllegalArgumentException("CPU index " + i + " outside 0.." + (vcpus.length - 1));
            }
            VCpu v = vcpus[i];
            if (v.cpu == s) {
                return;
            }
            if (v.cpu != null) {
                throw new IllegalStateException("CPU " + i + " is already attached");
            }
            for (VCpu o : vcpus) {
                if (o.cpu == s) {
                    throw new IllegalStateException("this CpuState is already attached as CPU " + o.id);
                }
            }
            s.hv = this;
            v.cpu = s;
            s.extIrq = xics.outputLevel(i);
        }
    }

    /**
     * Attach every CPU at once: {@code cpus.get(i)} becomes CPU i (its {@code index} is set).
     * The list must hold exactly {@link #cpuCount()} distinct, not yet attached CpuStates (or the
     * ones already attached at those positions).
     */
    public void attachCpus(List<CpuState> cpus) {
        synchronized (lock) {
            if (cpus.size() != vcpus.length) {
                throw new IllegalArgumentException("machine has " + vcpus.length + " CPUs, got " + cpus.size());
            }
            for (int i = 0; i < cpus.size(); i++) {
                CpuState c = cpus.get(i);
                if (vcpus[i].cpu != c) {
                    c.index = i;
                }
                attachCpu(c);
            }
        }
    }

    /** Machine reset with the attached CPU 0 as boot CPU; see {@link #reset(CpuState)}. */
    public void reset() {
        synchronized (lock) {
            CpuState boot = vcpus[0].cpu;
            if (boot == null) {
                throw new IllegalStateException("CPU 0 is not attached");
            }
            reset(boot);
        }
    }

    /**
     * Machine reset (power-on or reboot). {@code boot} must be CPU 0 (it is attached if nothing
     * is yet). It is set up the way QEMU hands control to SLOF: firmware at 0, device tree at
     * {@link #fdtAddress()}, PC = 0x100, r3 = tree address, MSR = ME|FP, POWER9 SPR reset values.
     * Every other attached CPU gets QEMU's reset state for a secondary and is left RTAS-stopped
     * ({@code halted} set) until the guest starts it with start-cpu. Call after the interpreters'
     * own reset so these values win, and only while no CPU thread runs; the decrementer and
     * timebase are the CPU module's.
     */
    public void reset(CpuState boot) {
        synchronized (lock) {
            if (vcpus[0].cpu == null) {
                if (boot.index != 0) {
                    throw new IllegalArgumentException("the boot CPU must have index 0");
                }
                attachCpu(boot);
            } else if (vcpus[0].cpu != boot) {
                throw new IllegalArgumentException("reset takes the boot CPU (CPU 0)");
            }
            shutdown = ShutdownReason.NONE;
            shutdownMessage = null;
            random.nextBytes(rngSeed);

            // TCG starts every guest in hash mode with an HPT sized for RAM (spapr_setup_hpt).
            htabShift = hptShiftForRam(config.ramSize);
            patbEntry = 0;
            ov5Cas = new OptionVector();
            compatPvr = 0;
            casPreIsa3 = false;
            for (VCpu v : vcpus) {
                v.clearRegistrations();
            }
            for (Watchdog w : watchdogs) {
                w.armed = false;
            }
            for (VioDevice d : vio) {
                d.reset();
            }
            if (phbTce != null) {
                phbTce.reset();
            }
            for (Drc d : drcs) {
                d.reset();
            }
            xics.reset();
            rtas.reset();
            for (MmioDevice d : mmioDevices) {
                d.region().handler().reset();
            }

            if (firmware != null) {
                mem.write(0, firmware, 0, firmware.length);
            }
            long addr = Math.min(rmaSize(), FDT_MAX_ADDR) - FDT_MAX_SIZE;
            fdtAddr = addr;
            byte[] blob = DeviceTree.blob(DeviceTree.build(this, true));
            if (blob.length > FDT_MAX_SIZE) {
                throw new IllegalStateException("device tree too large");
            }
            mem.write(addr, blob, 0, blob.length);
            fdtBlob = blob;
            fdtInitialSize = blob.length;

            for (VCpu v : vcpus) {
                CpuState c = v.cpu;
                if (c == null) {
                    continue;
                }
                // Requests posted before the reset are overtaken by it; run them now (no CPU
                // thread is running) so they cannot fire into the new boot.
                c.runPostedWork();
                resetCpuState(c);
                c.prodded = false;
                c.extIrq = false;
                if (c == boot) {
                    c.pc = ENTRY_POINT;
                    c.npc = ENTRY_POINT;
                    c.gpr[3] = addr;
                    c.halted = false;
                } else {
                    // spapr_reset_vcpu for a CPU that is not the boot CPU: no power-saving exit
                    // causes until start-cpu, PSSCR[EC] set, RTAS-stopped.
                    c.spr[Spr.LPCR] = LPCR_BOOT & ~LPCR_PM;
                    c.pc = ENTRY_POINT;
                    c.npc = ENTRY_POINT;
                    c.halted = true;
                }
            }
        }
    }

    /** Register state of a POWER9 pseries vCPU after QEMU's reset (values read back from QEMU). */
    static void resetCpuState(CpuState s) {
        java.util.Arrays.fill(s.gpr, 0);
        java.util.Arrays.fill(s.vsrHi, 0);
        java.util.Arrays.fill(s.vsrLo, 0);
        s.lr = 0;
        s.ctr = 0;
        s.cr = 0;
        s.setXer(0);
        s.fpscr = 0;
        s.vscr = 0x10000; // NJ, as QEMU's ppc reset sets it
        s.msr = Msr.ME | Msr.FP;
        for (int r : new int[] {Spr.SRR0, Spr.SRR1, Spr.SPRG0, Spr.SPRG1, Spr.SPRG2, Spr.SPRG3, Spr.DAR,
                Spr.DSISR, Spr.PIDR, Spr.AMR, Spr.IAMR, Spr.UAMOR, Spr.FSCR, Spr.CTRL_R, Spr.CTRL_W, Spr.DSCR,
                Spr.TAR, Spr.CFAR, Spr.PPR, Spr.DAWR0, Spr.DAWRX0, Spr.CIABR, Spr.VRSAVE, Spr.HSRR0, Spr.HSRR1,
                Spr.PSPB, Spr.MMCR1, Spr.MMCR2, Spr.MMCRA, Spr.BESCR, Spr.EBBHR, Spr.EBBRR, Spr.PTCR, SPR_PCR}) {
            s.spr[r] = 0;
        }
        s.spr[Spr.PVR] = Spr.PVR_POWER9;
        s.spr[Spr.LPCR] = LPCR_BOOT;
        s.spr[SPR_AMOR] = -1L;
        s.spr[SPR_PSSCR] = PSSCR_EC;
        s.spr[Spr.MMCR0] = 0x80000000L; // FC
        s.spr[SPR_PMCR] = 1;
        s.spr[SPR_RPR] = 0x103070f1f3fL;
        s.resValid = false;
        s.procTable = 0;
        s.ceded = false;
    }

    /**
     * RTAS start-cpu (QEMU's rtas_start_cpu): give the stopped CPU {@code t} the entry state, LPCR
     * modes copied from the caller, and set it running. Returns false (RTAS hardware error) if
     * {@code t} is not stopped. Caller holds the machine lock.
     *
     * <p>The entry state is written into {@code t} directly and also posted with
     * {@link CpuState#runOnCpu}, then {@code halted} is cleared. The posted copy is what makes a
     * start right after the target's stop-self safe: the target's thread may still be finishing
     * that stop-self hypercall (writing r3, advancing the PC) when this runs, and those writes
     * would clobber the direct copy. The run loop must therefore run posted work after leaving
     * the halted park and before its first instruction; it then sees exactly the entry state.
     */
    boolean startCpu(CpuState caller, CpuState t, long start, long r3) {
        if (!t.halted) {
            return false;
        }
        long callerLpcr = caller.spr[Spr.LPCR];
        long lpcr = t.spr[Spr.LPCR];
        lpcr = (lpcr & ~Spr.LPCR_ILE) | (callerLpcr & Spr.LPCR_ILE);
        lpcr = (lpcr & ~Spr.LPCR_AIL_MASK) | (callerLpcr & Spr.LPCR_AIL_MASK);
        // New CPUs start in the radix/hash mode of the existing ones.
        long radixBits = Spr.LPCR_UPRT | Spr.LPCR_GTSE | Spr.LPCR_HR;
        lpcr = (callerLpcr & Spr.LPCR_HR) != 0 ? lpcr | radixBits : lpcr & ~radixBits;
        // spapr_cpu_set_entry_state turns the power-saving exit causes back on.
        long entryLpcr = lpcr | LPCR_PM;
        long psscr = t.spr[SPR_PSSCR] & ~PSSCR_EC;
        long procTable = (patbEntry & PATE1_GR) != 0 ? patbEntry & ~PATE1_GR : 0;
        Consumer<CpuState> entry = c -> {
            c.spr[Spr.LPCR] = entryLpcr;
            c.spr[SPR_PSSCR] = psscr;
            c.msr = Msr.SF | Msr.ME;
            c.pc = start;
            c.npc = start;
            c.gpr[1] = 0;
            c.gpr[3] = r3;
            c.gpr[4] = 0;
            c.procTable = procTable;
            c.ceded = false;
            c.resValid = false;
        };
        entry.accept(t);
        t.runOnCpu(entry);
        // The volatile write publishes everything above to t's thread.
        t.halted = false;
        t.wake();
        return true;
    }

    /**
     * RTAS stop-self (QEMU's rtas_stop_self) on the calling CPU's own thread: PSSCR[EC] set, no
     * power-saving exit causes, RTAS-stopped. The run loop parks until start-cpu. Caller holds
     * the machine lock.
     */
    void stopSelf(CpuState s) {
        s.spr[SPR_PSSCR] |= PSSCR_EC;
        s.spr[Spr.LPCR] &= ~LPCR_PM;
        // Also idle the CPU for run loops that do not yet park on halted.
        s.ceded = true;
        s.halted = true;
    }

    /**
     * Apply a partition-wide register update to every attached CPU, the way QEMU's
     * spapr_set_all_lpcrs uses run_on_cpu: at once on the caller, posted to every other CPU's
     * own thread, stopped or running. Posting even to stopped CPUs keeps all updates in one FIFO
     * per CPU: a CPU that stops with updates still queued gets them, then any later ones, then
     * start-cpu's entry state, in that order. Caller holds the machine lock.
     */
    private void forAllCpus(CpuState caller, Consumer<CpuState> f) {
        for (VCpu v : vcpus) {
            CpuState c = v.cpu;
            if (c == null) {
                continue;
            }
            if (c == caller) {
                f.accept(c);
            } else {
                c.runOnCpu(f);
            }
        }
    }

    static int hptShiftForRam(long ram) {
        long p2 = Long.highestOneBit(ram) == ram ? ram : Long.highestOneBit(ram) << 1;
        int shift = Long.numberOfTrailingZeros(p2) - 7;
        return Math.max(18, Math.min(46, shift));
    }

    // ---- asynchronous work, shutdown ---------------------------------------------------------

    /**
     * Run timer-driven device work (the H_WATCHDOG timers). Safe to call from any thread at any
     * rate; the application calls it periodically (e.g. from its run loop or a timer thread).
     * Console input needs no pumping: it is delivered as soon as the backend produces it.
     */
    public void pump() {
        synchronized (lock) {
            long now = System.nanoTime();
            for (int i = 0; i < watchdogs.length; i++) {
                Watchdog w = watchdogs[i];
                if (w.armed && now - w.deadline >= 0) {
                    w.armed = false;
                    log.warn("watchdog " + (i + 1) + " expired, action " + w.action);
                    switch (w.action) {
                        case 1 -> requestStop(ShutdownReason.POWER_OFF, "watchdog");
                        case 2 -> requestStop(ShutdownReason.REBOOT, "watchdog");
                        default -> {
                            // Dump-restart: a system reset on every CPU, each on its own thread.
                            Consumer<CpuState> h = systemResetHandler;
                            if (h != null && vcpus[0].cpu != null) {
                                for (VCpu v : vcpus) {
                                    if (v.cpu != null) {
                                        v.cpu.runOnCpu(h);
                                    }
                                }
                            } else {
                                requestStop(ShutdownReason.REBOOT, "watchdog dump-restart");
                            }
                        }
                    }
                    if (!w.leaveOthers) {
                        for (Watchdog o : watchdogs) {
                            o.armed = false;
                        }
                    }
                }
            }
        }
    }

    /**
     * Ask every CPU to stop with the given reason (first reason wins until the next reset). Each
     * CPU's run loop returns at its next instruction boundary, or at once if it is idle or parked.
     */
    void requestStop(ShutdownReason r, String msg) {
        synchronized (lock) {
            if (shutdown == ShutdownReason.NONE) {
                shutdownMessage = msg;
                shutdown = r;
            }
        }
        for (VCpu v : vcpus) {
            CpuState c = v.cpu;
            if (c != null) {
                c.stopRequested = true;
                c.wake();
            }
        }
    }

    /**
     * Release the host resources: close the disk backends (deleting snapshot overlays), the NVRAM
     * file, the console backend and the added VIO devices ({@link VioDevice#closeDevice}). Idempotent. The CPUs must not be running. Guest RAM is
     * off-heap memory the JVM reclaims once the machine is unreachable.
     *
     * @throws IOException the first failure to close a backend (the others are still closed and
     *     their failures added as suppressed exceptions)
     */
    @Override
    public void close() throws IOException {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            IOException e = closeBackends(null, true);
            if (e != null) {
                throw e;
            }
        }
    }

    /**
     * Close every backend, collecting failures: into {@code primary} if given, otherwise into the
     * returned exception. The console is left alone when construction fails, since it was never
     * started.
     */
    private IOException closeBackends(Throwable primary, boolean console) {
        IOException first = null;
        List<AutoCloseable> all = new ArrayList<>();
        for (ScsiTarget t : vscsi.targets()) {
            if (t instanceof ScsiDisk d) {
                all.add(d.backend());
            }
        }
        all.add(nvram::close);
        if (console) {
            all.add(vty.backend()::close);
        }
        for (VioDevice d : addedVio) {
            all.add(d::closeDevice);
        }
        for (AutoCloseable c : all) {
            try {
                c.close();
            } catch (Exception e) {
                if (primary != null) {
                    primary.addSuppressed(e);
                } else if (first == null) {
                    first = e instanceof IOException io ? io : new IOException(e);
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        return first;
    }

    // ---- saved state ------------------------------------------------------------------------

    /**
     * Write the platform part of the machine state: a {@code "PHW "} record describing the
     * hardware (CPU count, RAM size, devices and disks, which must match when the state is
     * loaded), then a {@code "PAPR"} record with the hypervisor state (shutdown state, CAS
     * results, the device tree the guest holds, process table, VPA/SLB shadow/DTL registrations,
     * watchdogs), XICS, RTAS, the DR connectors and every VIO device (CRQ, TCE tables, NVRAM
     * contents, console input not yet read by the guest, SCSI sense state), and, if the machine
     * has MMIO regions, an {@code "MMIO"} record with each region handler's own state. Guest RAM
     * and the CPUs
     * are not written here: they belong to {@code PhysicalMemory} and the CPU module, and
     * {@code org.boxtheta.javapower.Emulator#saveState} writes all of it. Disk contents, the
     * console and the other host resources stay outside the state.
     *
     * <p>No CPU may be running.
     */
    public void saveState(org.boxtheta.javapower.core.StateOutput out) throws IOException {
        synchronized (lock) {
            out.record("PHW ", this::saveHardware);
            out.record("PAPR", d -> {
                d.writeByte(shutdown.ordinal());
                org.boxtheta.javapower.core.StateOutput.writeString(d, shutdownMessage);
                org.boxtheta.javapower.core.StateOutput.writeBytes(d, ov5Cas.toDtBytes());
                d.writeInt(compatPvr);
                d.writeBoolean(casPreIsa3);
                d.writeLong(patbEntry);
                d.writeInt(htabShift);
                org.boxtheta.javapower.core.StateOutput.writeBytes(d, rngSeed);
                org.boxtheta.javapower.core.StateOutput.writeBytes(d, fdtBlob);
                d.writeInt(fdtInitialSize);
                d.writeLong(fdtAddr);
                long now = System.nanoTime();
                for (Watchdog w : watchdogs) {
                    d.writeBoolean(w.armed);
                    d.writeInt(w.action);
                    d.writeBoolean(w.leaveOthers);
                    d.writeLong(w.armed ? Math.max(0, w.deadline - now) : 0);
                }
                for (VCpu v : vcpus) {
                    d.writeLong(v.vpaAddr);
                    d.writeLong(v.slbShadowAddr);
                    d.writeInt(v.slbShadowSize);
                    d.writeLong(v.dtlAddr);
                    d.writeInt(v.dtlSize);
                }
                xics.saveState(d);
                rtas.saveState(d);
                d.writeInt(drcs.size());
                for (Drc c : drcs) {
                    c.saveState(d);
                }
                d.writeInt(vio.size());
                for (VioDevice v : vio) {
                    v.saveState(d);
                }
                d.writeBoolean(phbTce != null);
                if (phbTce != null) {
                    phbTce.saveState(d);
                }
            });
            if (!mmioDevices.isEmpty()) {
                out.record("MMIO", d -> {
                    for (MmioDevice m : mmioDevices) {
                        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
                        java.io.DataOutputStream o = new java.io.DataOutputStream(b);
                        m.region().handler().saveState(o);
                        o.flush();
                        org.boxtheta.javapower.core.StateOutput.writeBytes(d, b.toByteArray());
                    }
                });
            }
        }
    }

    /** The hardware that must match between a saved state and the machine it is loaded into. */
    private void saveHardware(java.io.DataOutput d) throws IOException {
        d.writeInt(vcpus.length);
        d.writeLong(mem.size());
        d.writeBoolean(config.phb);
        d.writeBoolean(config.rng);
        d.writeInt(nvram.size());
        List<ScsiTarget> ts = vscsi.targets();
        d.writeInt(ts.size());
        for (ScsiTarget t : ts) {
            ScsiDisk disk = (ScsiDisk) t;
            d.writeUTF(disk.type().name());
            d.writeInt(disk.id());
            d.writeInt(disk.lun());
            d.writeInt(disk.blockSize());
            d.writeLong(disk.backend().size());
            d.writeBoolean(disk.backend().readOnly());
        }
        // Optional sections: absent without the devices they describe, so that a machine
        // without any writes the description it always did.
        if (!addedVio.isEmpty()) {
            hardwareSection(d, "VIO ", o -> {
                o.writeInt(addedVio.size());
                for (VioDevice v : addedVio) {
                    o.writeUTF(v.nodeName());
                    o.writeUTF(v.dtType());
                    o.writeUTF(v.dtCompatible());
                    o.writeLong(v.dmaWindowSize());
                    org.boxtheta.javapower.core.StateOutput.writeString(o, v.hardwareIdentity());
                }
            });
        }
        if (!mmioDevices.isEmpty()) {
            hardwareSection(d, "MMIO", o -> {
                o.writeInt(mmioDevices.size());
                for (MmioDevice m : mmioDevices) {
                    MmioRegion r = m.region();
                    o.writeUTF(r.name());
                    o.writeLong(r.base());
                    o.writeLong(r.size());
                    o.writeBoolean(r.order() == java.nio.ByteOrder.BIG_ENDIAN);
                    o.writeInt(m.irq());
                    o.writeByte(m.config().interrupt);
                }
            });
        }
    }

    /** Writes the body of one section of the hardware description. */
    private interface HardwareWriter {
        void write(java.io.DataOutput o) throws IOException;
    }

    /**
     * Append an optional section to the hardware description: a tag of four ASCII characters and
     * the body with its length. Sections follow the SCSI devices in a fixed order, each written
     * only by a machine that has such devices; {@link #checkHardware} compares them by tag, a
     * missing section standing for none of its devices.
     */
    private static void hardwareSection(java.io.DataOutput d, String tag, HardwareWriter w) throws IOException {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        java.io.DataOutputStream o = new java.io.DataOutputStream(b);
        w.write(o);
        o.flush();
        if (tag.length() != 4) {
            throw new IllegalArgumentException(tag);
        }
        d.writeBytes(tag);
        d.writeInt(b.size());
        d.write(b.toByteArray());
    }

    /**
     * The optional sections in the rest of a hardware description, by tag, in the order written.
     *
     * @throws java.io.EOFException if a section is cut short or malformed
     */
    private static java.util.Map<String, java.io.DataInputStream> hardwareSections(byte[] rest) throws IOException {
        java.util.Map<String, java.io.DataInputStream> m = new java.util.LinkedHashMap<>();
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(rest));
        while (in.available() > 0) {
            byte[] t = new byte[4];
            in.readFully(t);
            int len = in.readInt();
            if (len < 0 || len > in.available()) {
                throw new java.io.EOFException();
            }
            byte[] body = new byte[len];
            in.readFully(body);
            String tag = new String(t, java.nio.charset.StandardCharsets.US_ASCII);
            if (m.put(tag, new java.io.DataInputStream(new java.io.ByteArrayInputStream(body))) != null) {
                throw new java.io.EOFException();
            }
        }
        return m;
    }

    /**
     * Read the records written by {@link #saveState}. The hardware description is checked first:
     * if it differs from this machine's, nothing is changed and a
     * {@link org.boxtheta.javapower.core.StateMismatchException} lists every difference. The
     * attached CPUs' interrupt inputs are driven from the restored XICS state; the CPUs' own
     * state is the CPU module's to load. The device tree, boot settings and option vectors the
     * guest negotiated come from the state, not from this machine's configuration. Watchdogs
     * resume with the time they had left.
     *
     * <p>No CPU may be running. If loading fails after the check, the machine is left partly
     * loaded and must be reset or loaded again before it runs.
     *
     * @throws org.boxtheta.javapower.core.StateMismatchException if the hardware differs
     * @throws org.boxtheta.javapower.core.StateFormatException if the records are corrupt
     */
    public void loadState(org.boxtheta.javapower.core.StateInput in) throws IOException {
        synchronized (lock) {
            checkHardware(in.record("PHW "));
            org.boxtheta.javapower.core.StateInput.Record r = in.record("PAPR");
            int sd = r.readUnsignedByte();
            if (sd >= ShutdownReason.values().length) {
                throw r.corrupt("shutdown reason " + sd);
            }
            String msg = r.readString();
            byte[] ov = r.readBytes(OptionVector.MAX_BYTES + 1);
            if (ov == null || ov.length < 1) {
                throw r.corrupt("option vector 5");
            }
            int pvr = r.readInt();
            boolean pre3 = r.readBoolean();
            long patb = r.readLong();
            int htab = r.readInt();
            byte[] seed = r.readBytes(rngSeed.length);
            byte[] blob = r.readBytes(FDT_MAX_SIZE * 2);
            int initial = r.readInt();
            long addr = r.readLong();
            if (seed == null || seed.length != rngSeed.length || blob == null) {
                throw r.corrupt("device tree");
            }
            try {
                Fdt.parse(blob);
            } catch (Fdt.FdtException e) {
                throw r.corrupt("device tree: " + e.getMessage());
            }
            shutdown = ShutdownReason.values()[sd];
            shutdownMessage = msg;
            ov5Cas = OptionVector.fromBytes(java.util.Arrays.copyOfRange(ov, 1, ov.length));
            compatPvr = pvr;
            casPreIsa3 = pre3;
            patbEntry = patb;
            htabShift = htab;
            rngSeed = seed;
            fdtBlob = blob;
            fdtInitialSize = initial;
            fdtAddr = addr;
            long now = System.nanoTime();
            for (Watchdog w : watchdogs) {
                w.armed = r.readBoolean();
                w.action = r.readInt();
                w.leaveOthers = r.readBoolean();
                w.deadline = now + r.readLong();
            }
            for (VCpu v : vcpus) {
                v.vpaAddr = r.readLong();
                v.slbShadowAddr = r.readLong();
                v.slbShadowSize = r.readInt();
                v.dtlAddr = r.readLong();
                v.dtlSize = r.readInt();
            }
            xics.loadState(r);
            rtas.loadState(r);
            if (r.readInt() != drcs.size()) {
                throw r.corrupt("DR connector count");
            }
            for (Drc c : drcs) {
                c.loadState(r);
            }
            if (r.readInt() != vio.size()) {
                throw r.corrupt("VIO device count");
            }
            for (VioDevice v : vio) {
                v.loadState(r);
            }
            if (r.readBoolean() != (phbTce != null)) {
                throw r.corrupt("PHB TCE table");
            }
            if (phbTce != null) {
                phbTce.loadState(r);
            }
            if (!mmioDevices.isEmpty()) {
                loadMmio(in.record("MMIO"));
            }
            vty.afterLoad();
        }
    }

    /** Give each MMIO region's handler the state it saved. */
    private void loadMmio(org.boxtheta.javapower.core.StateInput.Record r) throws IOException {
        for (MmioDevice m : mmioDevices) {
            byte[] b = r.readBytes(org.boxtheta.javapower.core.StateOutput.MAX_RECORD);
            if (b == null) {
                throw r.corrupt("MMIO region " + m.region().name());
            }
            java.io.ByteArrayInputStream bin = new java.io.ByteArrayInputStream(b);
            try {
                m.region().handler().loadState(new java.io.DataInputStream(bin));
            } catch (java.io.EOFException e) {
                throw r.corrupt("MMIO region " + m.region().name() + ": its state ends early");
            }
            if (bin.available() != 0) {
                throw r.corrupt("MMIO region " + m.region().name() + ": " + bin.available() + " bytes of its state"
                        + " left unread");
            }
        }
    }

    private void checkHardware(org.boxtheta.javapower.core.StateInput.Record r) throws IOException {
        java.io.ByteArrayOutputStream mine = new java.io.ByteArrayOutputStream();
        saveHardware(new java.io.DataOutputStream(mine));
        List<String> diff = new ArrayList<>();
        java.io.DataInputStream cur = new java.io.DataInputStream(new java.io.ByteArrayInputStream(mine.toByteArray()));
        compare(diff, "CPUs", r.readInt(), cur.readInt());
        compare(diff, "bytes of RAM", r.readLong(), cur.readLong());
        compare(diff, "PCI host bridge", r.readBoolean(), cur.readBoolean());
        compare(diff, "spapr-rng", r.readBoolean(), cur.readBoolean());
        compare(diff, "bytes of NVRAM", r.readInt(), cur.readInt());
        int n = r.readInt("disk count", 0, 64);
        int m = cur.readInt();
        compare(diff, "SCSI devices", n, m);
        for (int i = 0; i < Math.min(n, m); i++) {
            String what = "SCSI device " + i + ": ";
            compare(diff, what + "type", r.readUTF(), cur.readUTF());
            compare(diff, what + "target id", r.readInt(), cur.readInt());
            compare(diff, what + "LUN", r.readInt(), cur.readInt());
            compare(diff, what + "block size", r.readInt(), cur.readInt());
            compare(diff, what + "bytes", r.readLong(), cur.readLong());
            compare(diff, what + "read-only", r.readBoolean(), cur.readBoolean());
        }
        // The optional sections follow the disks; with another disk count they cannot be found.
        if (n == m) {
            byte[] rest = new byte[r.remaining()];
            r.readFully(rest);
            try {
                java.util.Map<String, java.io.DataInputStream> saved = hardwareSections(rest);
                java.util.Map<String, java.io.DataInputStream> now = hardwareSections(cur.readAllBytes());
                compareVio(diff, saved.remove("VIO "), now.remove("VIO "));
                compareMmio(diff, saved.remove("MMIO"), now.remove("MMIO"));
                for (String tag : saved.keySet()) {
                    diff.add("hardware this version of javapower does not know: section " + tag.trim());
                }
            } catch (java.io.EOFException e) {
                throw r.corrupt("a hardware section ends early");
            }
        }
        if (!diff.isEmpty()) {
            throw new org.boxtheta.javapower.core.StateMismatchException(
                    "the machine state was saved from different hardware: " + String.join("; ", diff));
        }
    }

    /** Compare the added VIO device sections of two hardware descriptions; null stands for none. */
    private static void compareVio(List<String> diff, java.io.DataInputStream r, java.io.DataInputStream cur)
            throws IOException {
        int sn = r == null ? 0 : r.readInt();
        int cn = cur == null ? 0 : cur.readInt();
        compare(diff, "added VIO devices", sn, cn);
        for (int i = 0; i < Math.min(sn, cn); i++) {
            String sname = r.readUTF();
            String cname = cur.readUTF();
            String what = "VIO device " + cname + ": ";
            compare(diff, "VIO device " + (i + 1 + 3) + " node", sname, cname);
            compare(diff, what + "device_type", r.readUTF(), cur.readUTF());
            compare(diff, what + "compatible", r.readUTF(), cur.readUTF());
            compare(diff, what + "DMA window bytes", r.readLong(), cur.readLong());
            String sid = r.readBoolean() ? r.readUTF() : null;
            String cid = cur.readBoolean() ? cur.readUTF() : null;
            compare(diff, what + "identity", quote(sid), quote(cid));
        }
    }

    /** Compare the MMIO sections of two hardware descriptions; null stands for no regions. */
    private static void compareMmio(List<String> diff, java.io.DataInputStream r, java.io.DataInputStream cur)
            throws IOException {
        int sm = r == null ? 0 : r.readInt();
        int mm = cur == null ? 0 : cur.readInt();
        compare(diff, "MMIO regions", sm, mm);
        for (int i = 0; i < Math.min(sm, mm); i++) {
            String name = cur.readUTF();
            String what = "MMIO region " + i + " (" + name + "): ";
            compare(diff, what + "name", r.readUTF(), name);
            compare(diff, what + "base", hex(r.readLong()), hex(cur.readLong()));
            compare(diff, what + "size", hex(r.readLong()), hex(cur.readLong()));
            compare(diff, what + "byte order", order(r.readBoolean()), order(cur.readBoolean()));
            compare(diff, what + "interrupt", hex(r.readInt()), hex(cur.readInt()));
            compare(diff, what + "interrupt type", r.readUnsignedByte(), cur.readUnsignedByte());
        }
    }

    private static String hex(long v) {
        return "0x" + Long.toHexString(v);
    }

    private static String order(boolean big) {
        return big ? "big-endian" : "little-endian";
    }

    private static String quote(String s) {
        return s == null ? "none" : "\"" + s + "\"";
    }

    private static void compare(List<String> diff, String what, Object saved, Object now) {
        if (!saved.equals(now)) {
            diff.add(what + ": saved " + saved + ", this machine " + now);
        }
    }

    // ---- hypercalls --------------------------------------------------------------------------

    static long toReal(long addr) {
        return addr & 0x0fffffffffffffffL;
    }

    /** Runs on the calling CPU's thread; each handler takes the locks it needs (see the class comment). */
    @Override
    public void hcall(CpuState s) {
        long op = s.gpr[3];
        s.gpr[3] = dispatch(s, op);
    }

    /** Marks the vCPU preempted in its VPA dispatch counter (odd), as QEMU's cpu_exec_exit hook. */
    @Override
    public void cpuExecExit(CpuState s) {
        bumpDispatchCounter(s, 1);
    }

    /** Clears a pending prod and marks the vCPU dispatched (even), as QEMU's cpu_exec_enter hook. */
    @Override
    public void cpuExecEnter(CpuState s) {
        s.prodded = false;
        bumpDispatchCounter(s, 0);
    }

    private void bumpDispatchCounter(CpuState s, int parity) {
        int i = s.index;
        if (i < 0 || i >= vcpus.length || vcpus[i].cpu != s) {
            return;
        }
        long vpa = vcpus[i].vpaAddr;
        if (vpa == 0 || !mem.contains(vpa + VPA_DISPATCH_COUNTER, 4)) {
            return;
        }
        int d = mem.read32be(vpa + VPA_DISPATCH_COUNTER) + 1;
        if ((d & 1) != parity) {
            log.once("vpa-dispatch-parity", "VPA: incorrect dispatch counter value, correcting");
            d++;
        }
        mem.write32be(vpa + VPA_DISPATCH_COUNTER, d);
    }

    private long arg(CpuState s, int i) {
        return s.gpr[4 + i];
    }

    private static void ret(CpuState s, int i, long v) {
        s.gpr[4 + i] = v;
    }

    private long dispatch(CpuState s, long op) {
        if (op == H.KVMPPC_H_RTAS) {
            synchronized (lock) {
                return rtas.call(s, toReal(arg(s, 0)));
            }
        }
        if (op == H.KVMPPC_H_CAS) {
            synchronized (lock) {
                return hCas(s);
            }
        }
        if (op == H.KVMPPC_H_UPDATE_DT) {
            synchronized (lock) {
                return hUpdateDt(s);
            }
        }
        if (op == H.KVMPPC_H_LOGICAL_MEMOP) {
            return hLogicalMemop(s);
        }
        if (op < 0 || op > 0x488 || (op & 3) != 0) {
            return unknown(op);
        }
        return switch ((int) op) {
            case H.ENTER, H.REMOVE, H.READ, H.PROTECT, H.BULK_REMOVE -> hashMmu(s, (int) op);
            case H.CLEAR_MOD, H.CLEAR_REF -> unknown(op);
            case H.RESIZE_HPT_PREPARE, H.RESIZE_HPT_COMMIT -> {
                synchronized (lock) {
                    yield htabShift == 0 ? H.NOT_AVAILABLE : hptResize(op);
                }
            }
            case H.SET_SPRG0 -> {
                s.spr[Spr.SPRG0] = arg(s, 0);
                yield H.SUCCESS;
            }
            case H.SET_DABR, H.SET_XDABR -> H.HARDWARE; // POWER9 has no DABR
            case H.PAGE_INIT -> hPageInit(s);
            case H.REGISTER_VPA -> {
                synchronized (lock) {
                    yield hRegisterVpa(s);
                }
            }
            case H.CEDE -> hCede(s);
            case H.CONFER -> hConfer(s);
            case H.PROD -> hProd(s);
            case H.JOIN -> hJoin(s);
            case H.LOGICAL_CI_LOAD, H.LOGICAL_CACHE_LOAD -> hLogicalLoad(s);
            case H.LOGICAL_CI_STORE, H.LOGICAL_CACHE_STORE -> hLogicalStore(s);
            case H.LOGICAL_ICBI, H.LOGICAL_DCBF -> H.SUCCESS;
            case H.SET_MODE -> {
                synchronized (lock) {
                    yield hSetMode(s);
                }
            }
            case H.CLEAN_SLB, H.INVALIDATE_PID -> {
                log.unimplemented("hcall-0x" + Long.toHexString(op), "");
                yield H.FUNCTION;
            }
            case H.REGISTER_PROC_TBL -> {
                synchronized (lock) {
                    yield hRegisterProcTbl(s);
                }
            }
            case H.SIGNAL_SYS_RESET -> {
                synchronized (lock) {
                    yield hSignalSysReset(s);
                }
            }
            case H.GET_CPU_CHARACTERISTICS -> hGetCpuCharacteristics(s);
            case H.GET_TERM_CHAR -> hGetTermChar(s);
            case H.PUT_TERM_CHAR -> hPutTermChar(s);
            // XICS: the calling CPU's own presentation controller, except that H_IPI and H_IPOLL
            // name a server (any CPU's).
            case H.EOI -> {
                xics.eoi(self(s).id, (int) arg(s, 0));
                yield H.SUCCESS;
            }
            case H.CPPR -> {
                xics.setCppr(self(s).id, (int) arg(s, 0) & 0xff);
                yield H.SUCCESS;
            }
            case H.IPI -> {
                VCpu t = findVcpu(arg(s, 0));
                if (t == null) {
                    yield H.PARAMETER;
                }
                xics.setMfrr(t.id, (int) arg(s, 1) & 0xff);
                yield H.SUCCESS;
            }
            case H.IPOLL -> {
                VCpu t = findVcpu(arg(s, 0));
                if (t == null) {
                    yield H.PARAMETER;
                }
                int[] r = xics.ipoll(t.id);
                ret(s, 0, r[0] & 0xffffffffL);
                ret(s, 1, r[1]);
                yield H.SUCCESS;
            }
            case H.XIRR -> {
                ret(s, 0, xics.accept(self(s).id) & 0xffffffffL);
                yield H.SUCCESS;
            }
            case H.XIRR_X -> {
                ret(s, 0, xics.accept(self(s).id) & 0xffffffffL);
                ret(s, 1, System.nanoTime());
                yield H.SUCCESS;
            }
            case H.REG_CRQ -> {
                VioDevice d = vioByReg(arg(s, 0));
                if (d == null) {
                    yield H.PARAMETER;
                }
                synchronized (d.lock()) {
                    yield d.registerCrq(arg(s, 1), arg(s, 2));
                }
            }
            case H.FREE_CRQ -> {
                VioDevice d = vioByReg(arg(s, 0));
                if (d == null) {
                    yield H.PARAMETER;
                }
                synchronized (d.lock()) {
                    yield d.freeCrq();
                }
            }
            case H.SEND_CRQ -> {
                VioDevice d = vioByReg(arg(s, 0));
                if (d == null) {
                    yield H.PARAMETER;
                }
                if (!d.supportsCrq()) {
                    yield H.HARDWARE;
                }
                byte[] crq = ByteBuffer.allocate(16).putLong(arg(s, 1)).putLong(arg(s, 2)).array();
                synchronized (d.lock()) {
                    yield d.handleCrq(crq);
                }
            }
            case H.ENABLE_CRQ -> vioByReg(arg(s, 0)) == null ? H.PARAMETER : H.SUCCESS;
            case H.VIO_SIGNAL -> {
                VioDevice d = vioByReg(arg(s, 0));
                if (d == null || (arg(s, 1) & ~d.signalMask()) != 0) {
                    yield H.PARAMETER;
                }
                synchronized (d.lock()) {
                    d.setSignalState(arg(s, 1));
                }
                yield H.SUCCESS;
            }
            case H.PUT_TCE -> {
                TceTable t = tceByLiobn(arg(s, 0));
                yield t == null ? H.PARAMETER : t.put(arg(s, 1) & TceTable.PAGE_MASK, arg(s, 2));
            }
            case H.GET_TCE -> {
                TceTable t = tceByLiobn(arg(s, 0));
                if (t == null) {
                    yield H.PARAMETER;
                }
                Long v = t.get(arg(s, 1) & TceTable.PAGE_MASK);
                if (v == null) {
                    yield H.PARAMETER;
                }
                ret(s, 0, v);
                yield H.SUCCESS;
            }
            case H.STUFF_TCE -> hStuffTce(s);
            case H.PUT_TCE_INDIRECT -> hPutTceIndirect(s);
            case H.RANDOM -> {
                if (!config.rng) {
                    yield unknown(op);
                }
                ret(s, 0, random.nextLong());
                yield H.SUCCESS;
            }
            case H.HOME_NODE_ASSOCIATIVITY -> {
                synchronized (lock) {
                    yield hHomeNodeAssociativity(s);
                }
            }
            case H.WATCHDOG -> {
                synchronized (lock) {
                    yield hWatchdog(s);
                }
            }
            default -> unknown(op);
        };
    }

    private long unknown(long op) {
        log.unimplemented("hcall-0x" + Long.toHexString(op), "unimplemented hypercall, returning H_FUNCTION");
        return H.FUNCTION;
    }

    private VioDevice vioByReg(long reg) {
        for (VioDevice d : vio) {
            if ((d.reg() & 0xffffffffL) == reg) {
                return d;
            }
        }
        return null;
    }

    /** vty_lookup: the vty at {@code reg}, or the default vty for reg 0 (early debug consoles). */
    private Vty vtyByReg(long reg) {
        VioDevice d = vioByReg(reg);
        if (d == null && reg == 0) {
            return vty;
        }
        return d instanceof Vty v ? v : null;
    }

    TceTable tceByLiobn(long liobn) {
        for (TceTable t : tceTables) {
            if ((t.liobn() & 0xffffffffL) == liobn) {
                return t;
            }
        }
        return null;
    }

    VioDevice vioDeviceByReg(long reg) {
        return vioByReg(reg);
    }

    private long hashMmu(CpuState s, int op) {
        // Only radix is modelled. QEMU validates the PTE index against the HPT first; there is
        // none here, so every index is out of range.
        log.unimplemented("hash-mmu-hcall", String.format("hcall 0x%x (hash MMU not supported)", op));
        return H.PARAMETER;
    }

    private long hptResize(long op) {
        log.unimplemented("hpt-resize", String.format("hcall 0x%x", op));
        return H.HARDWARE;
    }

    private long hPageInit(CpuState s) {
        long flags = arg(s, 0);
        long dst = arg(s, 1);
        long src = arg(s, 2);
        final long icacheInvalidate = 1L << (63 - 40);
        final long icacheSync = 1L << (63 - 41);
        final long zeroPage = 1L << (63 - 48);
        final long copyPage = 1L << (63 - 49);
        if ((flags & ~(icacheInvalidate | icacheSync | zeroPage | copyPage)) != 0) {
            return H.PARAMETER;
        }
        if ((dst & 0xfff) != 0 || !mem.contains(dst, 4096)) {
            return H.PARAMETER;
        }
        if ((flags & copyPage) != 0) {
            if ((src & 0xfff) != 0 || !mem.contains(src, 4096)) {
                return H.PARAMETER;
            }
            byte[] b = new byte[4096];
            mem.read(src, b, 0, 4096);
            mem.write(dst, b, 0, 4096);
        } else if ((flags & zeroPage) != 0) {
            mem.fill(dst, 4096, (byte) 0);
        }
        return H.SUCCESS;
    }

    private long hRegisterVpa(CpuState s) {
        final long registerVpa = 0x0000200000000000L;
        final long registerDtl = 0x0000400000000000L;
        final long registerSlb = 0x0000600000000000L;
        final long deregisterVpa = 0x0000a00000000000L;
        final long deregisterDtl = 0x0000c00000000000L;
        final long deregisterSlb = 0x0000e00000000000L;
        long flags = arg(s, 0);
        long procno = arg(s, 1);
        long addr = arg(s, 2);
        VCpu t = findVcpu(procno);
        if (t == null) {
            return H.PARAMETER;
        }
        if (flags == registerVpa) {
            if (addr == 0) {
                return H.HARDWARE;
            }
            if (addr % 128 != 0) {
                return H.PARAMETER;
            }
            if (!mem.contains(addr + 4, 2)) {
                return H.PARAMETER;
            }
            int size = mem.read16be(addr + 4);
            if (size < 640) {
                return H.PARAMETER;
            }
            if (addr / 4096 != (addr + size - 1) / 4096 || !mem.contains(addr, size)) {
                return H.PARAMETER;
            }
            t.vpaAddr = addr;
            mem.write8(addr + 9, mem.read8(addr + 9) | 0x2); // shared processor
            return H.SUCCESS;
        } else if (flags == deregisterVpa) {
            if (t.slbShadowAddr != 0 || t.dtlAddr != 0) {
                return H.RESOURCE;
            }
            t.vpaAddr = 0;
            return H.SUCCESS;
        } else if (flags == registerSlb) {
            if (addr == 0) {
                return H.HARDWARE;
            }
            if (!mem.contains(addr + 4, 4)) {
                return H.PARAMETER;
            }
            int size = mem.read32be(addr + 4);
            if (Integer.compareUnsigned(size, 8) < 0 || addr / 4096 != (addr + (size & 0xffffffffL) - 1) / 4096) {
                return H.PARAMETER;
            }
            if (t.vpaAddr == 0) {
                return H.RESOURCE;
            }
            t.slbShadowSize = size;
            t.slbShadowAddr = addr;
            return H.SUCCESS;
        } else if (flags == deregisterSlb) {
            t.slbShadowAddr = 0;
            t.slbShadowSize = 0;
            return H.SUCCESS;
        } else if (flags == registerDtl) {
            if (addr == 0) {
                return H.HARDWARE;
            }
            if (!mem.contains(addr + 4, 4)) {
                return H.PARAMETER;
            }
            int size = mem.read32be(addr + 4);
            if (Integer.compareUnsigned(size, 48) < 0) {
                return H.PARAMETER;
            }
            if (t.vpaAddr == 0) {
                return H.RESOURCE;
            }
            t.dtlSize = size;
            t.dtlAddr = addr;
            return H.SUCCESS;
        } else if (flags == deregisterDtl) {
            t.dtlAddr = 0;
            t.dtlSize = 0;
            return H.SUCCESS;
        }
        return H.PARAMETER;
    }

    /** Registered VPA address of CPU 0 (0 if none); tests and debugging. */
    public long vpaAddress() {
        return vcpus[0].vpaAddr;
    }

    /** H_CEDE: own CPU only, no lock. Idle with MSR[EE]=1 until an interrupt or H_PROD. */
    private long hCede(CpuState s) {
        s.msr |= Msr.EE;
        if (s.prodded) {
            s.prodded = false;
            return H.SUCCESS;
        }
        if (!s.extIrq) {
            s.ceded = true;
        }
        return H.SUCCESS;
    }

    /** H_CONFER to self (and H_JOIN): wait until prodded, without enabling interrupts. */
    private long conferSelf(CpuState s) {
        if (s.prodded) {
            s.prodded = false;
            return H.SUCCESS;
        }
        s.ceded = true;
        return H.SUCCESS;
    }

    /**
     * H_CONFER (QEMU's h_confer): -1 yields to everyone; a target of self waits for H_PROD;
     * another target is yielded to only if its VPA dispatch counter still equals the caller's
     * sample and is odd (that vCPU is preempted). Yielding is a host thread yield.
     */
    private long hConfer(CpuState s) {
        long target = arg(s, 0);
        int dispatch = (int) arg(s, 1);
        if (target != -1) {
            VCpu t = findVcpu(target);
            if (t == null) {
                return H.PARAMETER;
            }
            if (t.cpu == s) {
                return conferSelf(s);
            }
            long vpa = t.vpaAddr;
            if (vpa == 0 || (dispatch & 1) == 0 || !mem.contains(vpa + VPA_DISPATCH_COUNTER, 4)) {
                return H.SUCCESS;
            }
            if (mem.read32be(vpa + VPA_DISPATCH_COUNTER) != dispatch) {
                return H.SUCCESS;
            }
        }
        Thread.yield();
        return H.SUCCESS;
    }

    /** H_PROD: mark the target prodded and wake it if it is idle (any thread may do this). */
    private long hProd(CpuState s) {
        CpuState t = findCpu(arg(s, 0));
        if (t == null) {
            return H.PARAMETER;
        }
        t.prodded = true;
        t.wake();
        return H.SUCCESS;
    }

    /**
     * H_JOIN (QEMU's h_join): with MSR[EE]=0, confer to self unless every other CPU is already
     * joined or stopped, in which case return H_CONTINUE. "Joined" is read from the other CPUs'
     * {@code ceded}/{@code msr} without synchronisation, as QEMU reads their halted flags.
     */
    private long hJoin(CpuState s) {
        if ((s.msr & Msr.EE) != 0) {
            return H.BAD_MODE;
        }
        for (VCpu v : vcpus) {
            CpuState c = v.cpu;
            if (c == null || c == s) {
                continue;
            }
            if (!(c.halted || c.ceded) || (c.msr & Msr.EE) != 0) {
                return conferSelf(s);
            }
        }
        return H.CONTINUE;
    }

    private long hLogicalLoad(CpuState s) {
        long size = arg(s, 0);
        long addr = arg(s, 1);
        if (size != 1 && size != 2 && size != 4 && size != 8) {
            return H.PARAMETER;
        }
        long v = 0;
        MmioRegion r;
        if (mem.contains(addr, size)) {
            v = switch ((int) size) {
                case 1 -> mem.read8(addr);
                case 2 -> mem.read16be(addr);
                case 4 -> mem.read32be(addr) & 0xffffffffL;
                default -> mem.read64be(addr);
            };
        } else if ((r = mmio.regionFor(addr, (int) size)) != null) {
            try {
                v = r.read(addr - r.base(), (int) size, java.nio.ByteOrder.BIG_ENDIAN);
            } catch (MmioHandler.BusError e) {
                log.once("logical-load-bus-error", String.format("addr 0x%x: %s", addr, e.getMessage()));
            }
        } else {
            log.once("logical-load-unassigned", String.format("addr 0x%x", addr));
        }
        ret(s, 0, v);
        return H.SUCCESS;
    }

    private long hLogicalStore(CpuState s) {
        long size = arg(s, 0);
        long addr = arg(s, 1);
        long val = arg(s, 2);
        if (size != 1 && size != 2 && size != 4 && size != 8) {
            return H.PARAMETER;
        }
        MmioRegion r;
        if (mem.contains(addr, size)) {
            switch ((int) size) {
                case 1 -> mem.write8(addr, (int) val);
                case 2 -> mem.write16be(addr, (int) val);
                case 4 -> mem.write32be(addr, (int) val);
                default -> mem.write64be(addr, val);
            }
        } else if ((r = mmio.regionFor(addr, (int) size)) != null) {
            try {
                r.write(addr - r.base(), (int) size, val, java.nio.ByteOrder.BIG_ENDIAN);
            } catch (MmioHandler.BusError e) {
                log.once("logical-store-bus-error", String.format("addr 0x%x: %s", addr, e.getMessage()));
            }
        } else {
            log.once("logical-store-unassigned", String.format("addr 0x%x", addr));
        }
        return H.SUCCESS;
    }

    private long hLogicalMemop(CpuState s) {
        long dst = arg(s, 0);
        long src = arg(s, 1);
        long esize = arg(s, 2);
        long count = arg(s, 3);
        long op = arg(s, 4);
        if (Long.compareUnsigned(count, 0x80000000L) > 0) {
            return H.PARAMETER;
        }
        if (esize > 3 || esize < 0) {
            return H.PARAMETER;
        }
        long mask = (1L << esize) - 1;
        long step = 1L << esize;
        if ((dst & mask) != 0 || (src & mask) != 0 || Long.compareUnsigned(op, 1) > 0) {
            return H.PARAMETER;
        }
        if (Long.compareUnsigned(dst, src) >= 0 && Long.compareUnsigned(dst, src + (count << esize)) < 0) {
            dst += (count - 1) << esize;
            src += (count - 1) << esize;
            step = -step;
        }
        int n = (int) step < 0 ? (int) -step : (int) step;
        while (count-- > 0) {
            long v = mem.contains(src, n) ? switch (n) {
                case 1 -> mem.read8(src);
                case 2 -> mem.read16be(src);
                case 4 -> mem.read32be(src) & 0xffffffffL;
                default -> mem.read64be(src);
            } : 0;
            if (op == 1) {
                v = ~v;
            }
            if (mem.contains(dst, n)) {
                switch (n) {
                    case 1 -> mem.write8(dst, (int) v);
                    case 2 -> mem.write16be(dst, (int) v);
                    case 4 -> mem.write32be(dst, (int) v);
                    default -> mem.write64be(dst, v);
                }
            }
            dst += step;
            src += step;
        }
        return H.SUCCESS;
    }

    private long hSetMode(CpuState s) {
        long mflags = arg(s, 0);
        long resource = arg(s, 1);
        long v1 = arg(s, 2);
        long v2 = arg(s, 3);
        switch ((int) resource) {
            case 1 -> { // CIABR
                if (mflags != 0) {
                    return H.UNSUPPORTED_FLAG;
                }
                if (v2 != 0) {
                    return H.P4;
                }
                if ((v1 & 3) == 3) {
                    return H.P3;
                }
                s.spr[SPR_CIABR] = v1;
                return H.SUCCESS;
            }
            case 2 -> { // DAWR0
                if (mflags != 0) {
                    return H.UNSUPPORTED_FLAG;
                }
                if ((v2 & (1L << (63 - 61))) != 0) {
                    return H.P4;
                }
                s.spr[SPR_DAWR0] = v1;
                s.spr[SPR_DAWRX0] = v2;
                return H.SUCCESS;
            }
            case 3 -> { // address translation mode (AIL)
                if (v1 != 0) {
                    return H.P3;
                }
                if (v2 != 0) {
                    return H.P4;
                }
                if (mflags != 0 && mflags != 3) {
                    return H.UNSUPPORTED_FLAG;
                }
                long ail = mflags << Spr.LPCR_AIL_SHIFT;
                forAllCpus(s, c -> c.spr[Spr.LPCR] = (c.spr[Spr.LPCR] & ~Spr.LPCR_AIL_MASK) | ail);
                return H.SUCCESS;
            }
            case 4 -> { // interrupt endianness
                if (v1 != 0) {
                    return H.P3;
                }
                if (v2 != 0) {
                    return H.P4;
                }
                if (mflags == 0) {
                    forAllCpus(s, c -> c.spr[Spr.LPCR] &= ~Spr.LPCR_ILE);
                    return H.SUCCESS;
                }
                if (mflags == 1) {
                    forAllCpus(s, c -> c.spr[Spr.LPCR] |= Spr.LPCR_ILE);
                    return H.SUCCESS;
                }
                return H.UNSUPPORTED_FLAG;
            }
            default -> {
                // DAWR1 exists from POWER10 on.
                return H.P2;
            }
        }
    }

    private long hRegisterProcTbl(CpuState s) {
        final long flagsMask = 0x1f;
        final long modify = 0x10;
        final long register = 0x08;
        final long radix = 0x04;
        final long hashProcTbl = 0x02;
        final long gtse = 0x01;
        long flags = arg(s, 0);
        long procTbl = arg(s, 1);
        long pageSize = arg(s, 2);
        long tableSize = arg(s, 3);
        long cproc;
        if ((flags & ~flagsMask) != 0) {
            return H.PARAMETER;
        }
        if ((flags & modify) != 0) {
            if ((flags & register) != 0) {
                long bytes = 1L << (Math.min(tableSize, 50) + 12);
                if ((procTbl & (bytes - 1)) != 0) {
                    log.once("proc-tbl-unaligned", String.format("proc_tbl 0x%x size 0x%x", procTbl, bytes));
                }
                if ((flags & radix) != 0) {
                    if ((procTbl & 0xfff) != 0 || (procTbl >>> 60) != 0) {
                        return H.P2;
                    } else if (pageSize != 0) {
                        return H.P3;
                    } else if (Long.compareUnsigned(tableSize, 24) > 0) {
                        return H.P4;
                    }
                    cproc = PATE1_GR | procTbl | tableSize;
                } else {
                    if ((flags & hashProcTbl) != 0) {
                        return H.PARAMETER;
                    }
                    if ((procTbl >>> 38) != 0) {
                        return H.P2;
                    } else if ((pageSize & ~7L) != 0) {
                        return H.P3;
                    } else if (Long.compareUnsigned(tableSize, 24) > 0) {
                        return H.P4;
                    }
                    cproc = (procTbl << 25) | pageSize << 5 | tableSize;
                    log.warn("guest registered a hash MMU process table; only radix is supported");
                }
            } else {
                cproc = patbEntry & PATE1_GR;
            }
        } else {
            if (((flags & radix) == 0) != ((patbEntry & PATE1_GR) == 0)) {
                return H.PARAMETER;
            }
            cproc = patbEntry;
        }
        if ((cproc & PATE1_GR) != 0) {
            htabShift = 0; // HASH -> RADIX frees the HPT
        }
        patbEntry = cproc;
        long update = 0;
        if ((flags & radix) != 0) {
            update |= Spr.LPCR_UPRT | Spr.LPCR_HR;
        } else if ((flags & hashProcTbl) != 0) {
            update |= Spr.LPCR_UPRT;
        }
        if ((flags & gtse) != 0) {
            update |= Spr.LPCR_GTSE;
        }
        long mask = Spr.LPCR_UPRT | Spr.LPCR_HR | Spr.LPCR_GTSE;
        long lpcrBits = update;
        long procTable = (cproc & PATE1_GR) != 0 ? cproc & ~PATE1_GR : 0;
        forAllCpus(s, c -> {
            c.spr[Spr.LPCR] = (c.spr[Spr.LPCR] & ~mask) | lpcrBits;
            c.procTable = procTable;
        });
        return H.SUCCESS;
    }

    /** The process table entry as QEMU stores it (PATE1_GR | base | size for radix). */
    public long patbEntry() {
        synchronized (lock) {
            return patbEntry;
        }
    }

    /**
     * H_SIGNAL_SYS_RESET (QEMU's h_signal_sys_reset): -1 all CPUs, -2 all but the caller (both
     * skip RTAS-stopped CPUs), otherwise one CPU by number. The reset runs on each target's own
     * thread: at once for the caller, through {@link CpuState#runOnCpu} for the others.
     */
    private long hSignalSysReset(CpuState s) {
        long target = arg(s, 0);
        List<CpuState> targets = new ArrayList<>();
        if (target < 0) {
            if (target < -2) {
                return H.PARAMETER;
            }
            for (VCpu v : vcpus) {
                CpuState c = v.cpu;
                if (c == null || (target == -2 && c == s) || c.halted) {
                    continue;
                }
                targets.add(c);
            }
            if (targets.isEmpty()) {
                return H.SUCCESS;
            }
        } else {
            CpuState c = findCpu(target);
            if (c == null) {
                return H.PARAMETER;
            }
            targets.add(c);
        }
        Consumer<CpuState> h = systemResetHandler;
        if (h == null) {
            log.unimplemented("signal-sys-reset", "no system reset handler installed");
            return H.HARDWARE;
        }
        for (CpuState c : targets) {
            if (c == s) {
                h.accept(c);
            } else {
                c.runOnCpu(h);
            }
        }
        return H.SUCCESS;
    }

    private long hGetCpuCharacteristics(CpuState s) {
        // Default caps of the current pseries machine: cfpc, sbbc and ibs "workaround",
        // ccf-assist on (TCG warns but keeps these values).
        long ch = bit(5) // HON_BRANCH_HINTS
                | bit(2) | bit(3) | bit(4) // L1D_FLUSH_ORI30, L1D_FLUSH_TRIG2, L1D_THREAD_PRIV
                | bit(0) // SPEC_BAR_ORI31
                | bit(9); // BCCTR_FLUSH_ASSIST
        long beh = bit(0) // FAVOUR_SECURITY
                | bit(1) // L1D_FLUSH_PR
                | bit(2) // BNDS_CHK_SPEC_BAR
                | bit(5); // FLUSH_COUNT_CACHE
        ret(s, 0, ch);
        ret(s, 1, beh);
        return H.SUCCESS;
    }

    /** ISA bit {@code n} (0 = MSB) of a doubleword. */
    private static long bit(int n) {
        return 1L << (63 - n);
    }

    private long hGetTermChar(CpuState s) {
        Vty v = vtyByReg(arg(s, 0));
        if (v == null) {
            return H.PARAMETER;
        }
        byte[] buf = new byte[16];
        int n = v.getChars(buf);
        ByteBuffer bb = ByteBuffer.wrap(buf);
        ret(s, 0, n);
        ret(s, 1, bb.getLong(0));
        ret(s, 2, bb.getLong(8));
        return H.SUCCESS;
    }

    private long hPutTermChar(CpuState s) {
        Vty v = vtyByReg(arg(s, 0));
        if (v == null) {
            return H.PARAMETER;
        }
        long len = arg(s, 1);
        if (Long.compareUnsigned(len, 16) > 0) {
            return H.PARAMETER;
        }
        byte[] buf = ByteBuffer.allocate(16).putLong(arg(s, 2)).putLong(arg(s, 3)).array();
        v.putChars(buf, 0, (int) len);
        return H.SUCCESS;
    }

    private long hStuffTce(CpuState s) {
        TceTable t = tceByLiobn(arg(s, 0));
        if (t == null) {
            return H.PARAMETER;
        }
        long ioba = arg(s, 1) & TceTable.PAGE_MASK;
        long value = arg(s, 2);
        long npages = arg(s, 3);
        if (Long.compareUnsigned(npages, t.entries()) > 0) {
            return H.PARAMETER;
        }
        long r = H.PARAMETER;
        for (long i = 0; i < npages; i++, ioba += TceTable.PAGE_SIZE) {
            r = t.put(ioba, value);
            if (r != H.SUCCESS) {
                break;
            }
        }
        return r;
    }

    private long hPutTceIndirect(CpuState s) {
        TceTable t = tceByLiobn(arg(s, 0));
        if (t == null) {
            return H.PARAMETER;
        }
        long ioba = arg(s, 1) & TceTable.PAGE_MASK;
        long list = arg(s, 2);
        long npages = arg(s, 3);
        if (Long.compareUnsigned(npages, 512) > 0 || (list & 0xfff) != 0) {
            return H.PARAMETER;
        }
        long r = H.PARAMETER;
        for (long i = 0; i < npages; i++, ioba += TceTable.PAGE_SIZE) {
            long a = list + i * 8;
            long tce = mem.contains(a, 8) ? mem.read64be(a) : 0;
            r = t.put(ioba, tce);
            if (r != H.SUCCESS) {
                break;
            }
        }
        return r;
    }

    private long hHomeNodeAssociativity(CpuState s) {
        if (arg(s, 0) != 1) {
            return H.FUNCTION;
        }
        VCpu t = findVcpu(arg(s, 1));
        if (t == null) {
            return H.P2;
        }
        // vCPU associativity without its length cell: FORM1 {0,0,0,node,vcpu}, FORM2 {node,vcpu}.
        int[] assoc = ov5Cas.test(OptionVector.OV5_FORM2_AFFINITY) ? new int[] {0, t.id}
                : new int[] {0, 0, 0, 0, t.id};
        int k = 0;
        for (int i = 0; i < 6; i++) {
            int a = k < assoc.length ? assoc[k++] : -1;
            int b = k < assoc.length ? assoc[k++] : -1;
            ret(s, i, ((long) a << 32) | (b & 0xffffffffL));
        }
        return H.SUCCESS;
    }

    private long hWatchdog(CpuState s) {
        long flags = arg(s, 0);
        long num = arg(s, 1);
        long timeout = arg(s, 2);
        int op = (int) (flags >>> 8) & 0xff;
        int action = (int) flags & 0xff;
        if ((flags >>> 17) != 0) {
            return H.PARAMETER;
        }
        boolean valid = num >= 1 && num <= watchdogs.length;
        switch (op) {
            case 1 -> {
                if (!valid) {
                    return H.P2;
                }
                if (Long.compareUnsigned(timeout, 1) <= 0) {
                    return H.P3;
                }
                if (action < 1 || action > 3) {
                    return H.PARAMETER;
                }
                Watchdog w = watchdogs[(int) num - 1];
                w.action = action;
                w.leaveOthers = ((flags >>> 16) & 1) != 0;
                w.deadline = System.nanoTime() + timeout * 1_000_000L;
                w.armed = true;
                return H.SUCCESS;
            }
            case 2 -> {
                if (num == -1) {
                    long r = H.NOOP;
                    for (Watchdog w : watchdogs) {
                        if (w.armed) {
                            w.armed = false;
                            r = H.SUCCESS;
                        }
                    }
                    return r;
                }
                if (!valid) {
                    return H.P2;
                }
                Watchdog w = watchdogs[(int) num - 1];
                if (w.armed) {
                    w.armed = false;
                    return H.SUCCESS;
                }
                return H.NOOP;
            }
            case 3 -> {
                ret(s, 0, (1L << 48) | ((long) watchdogs.length << 32));
                return H.SUCCESS;
            }
            case 4 -> {
                if (!valid) {
                    return H.P2;
                }
                ret(s, 0, 2);
                return H.SUCCESS;
            }
            default -> {
                return H.PARAMETER;
            }
        }
    }

    // ---- client architecture support, device tree updates ----------------------------------

    private static boolean supportedCompat(int pvr) {
        return pvr == PVR_LOGICAL_2_05 || pvr == PVR_LOGICAL_2_06 || pvr == PVR_LOGICAL_2_06_PLUS
                || pvr == PVR_LOGICAL_2_07 || pvr == PVR_LOGICAL_3_00;
    }

    private static int compatRank(int pvr) {
        return pvr == PVR_LOGICAL_2_06_PLUS ? PVR_LOGICAL_2_06 * 2 + 1 : pvr * 2;
    }

    /** PCR value for a logical PVR, masked to what a POWER9 implements (QEMU's ppc_set_compat). */
    static long pcrForCompat(int pvr) {
        return switch (pvr) {
            case PVR_LOGICAL_2_05 -> PCR_COMPAT_2_07 | PCR_COMPAT_2_06 | PCR_COMPAT_2_05;
            case PVR_LOGICAL_2_06, PVR_LOGICAL_2_06_PLUS -> PCR_COMPAT_2_07 | PCR_COMPAT_2_06;
            case PVR_LOGICAL_2_07 -> PCR_COMPAT_2_07;
            default -> 0; // raw mode or ISA 3.00: the 3.00/3.10 bits are outside POWER9's mask
        };
    }

    private long hCas(CpuState s) {
        long vec = toReal(arg(s, 0));
        long buf = arg(s, 1);
        long bufSize = arg(s, 2);
        if (Long.compareUnsigned(bufSize, 4) < 0) {
            log.warn("SLOF provided insufficient CAS buffer " + bufSize);
            requestStop(ShutdownReason.FATAL, "insufficient CAS buffer");
            return H.PARAMETER;
        }
        bufSize -= 4;

        // CAS is only allowed while the boot CPU is the only one running (or idle).
        for (VCpu v : vcpus) {
            CpuState c = v.cpu;
            if (c != null && c != s && !(c.halted || c.ceded)) {
                log.warn("guest has multiple active vCPUs at CAS, which is not allowed");
                return H.MULTI_THREADS_ACTIVE;
            }
        }

        // PVR list: (mask, pvr) pairs terminated by an entry with pvr bits outside its mask.
        boolean explicit = false;
        int best = 0;
        for (int i = 0; i < 512; i++) {
            if (!mem.contains(vec, 8)) {
                break;
            }
            int mask = mem.read32be(vec);
            int pvr = mem.read32be(vec + 4);
            vec += 8;
            if ((~mask & pvr) != 0) {
                break;
            }
            if ((Spr.PVR_POWER9 & mask) == (pvr & mask)) {
                explicit = true;
            } else if (supportedCompat(pvr) && (best == 0 || compatRank(pvr) > compatRank(best))) {
                best = pvr;
            }
        }
        log.info(String.format("spapr_cas_pvr current=0x%x, explicit_match=%d, new=0x%x", compatPvr,
                explicit ? 1 : 0, best));
        if (best == 0 && !explicit) {
            log.warn("Couldn't negotiate a suitable PVR during CAS");
            return H.HARDWARE;
        }
        if (compatPvr != best) {
            long pcr = pcrForCompat(best);
            forAllCpus(s, c -> c.spr[SPR_PCR] = pcr);
        }
        compatPvr = best;

        OptionVector ov1 = OptionVector.parse(mem, vec, 1);
        if (ov1 == null) {
            log.warn("guest didn't provide option vector 1");
            return H.PARAMETER;
        }
        OptionVector guest5 = OptionVector.parse(mem, vec, 5);
        if (guest5 == null) {
            log.warn("guest didn't provide option vector 5");
            return H.PARAMETER;
        }
        if (guest5.test(OptionVector.OV5_MMU_BOTH)) {
            requestStop(ShutdownReason.FATAL, "guest requested hash and radix MMU, which is invalid.");
            return H.PARAMETER;
        }
        if (guest5.test(OptionVector.OV5_XIVE_BOTH)) {
            requestStop(ShutdownReason.FATAL, "guest requested an invalid interrupt mode");
            return H.PARAMETER;
        }
        if (guest5.test(OptionVector.OV5_XIVE_EXPLOIT)) {
            requestStop(ShutdownReason.FATAL, "Guest requested unavailable interrupt mode (XIVE)");
            return H.PARAMETER;
        }
        if (!guest5.test(OptionVector.OV5_MMU_RADIX_300)) {
            log.warn("guest chose the hash MMU at CAS; only radix is implemented");
        }
        ov5Cas = ov5.intersect(guest5);
        casPreIsa3 = !ov1.test(OptionVector.OV1_PPC_3_00);

        byte[] blob = DeviceTree.blob(DeviceTree.build(this, false));
        if (blob.length > bufSize) {
            log.warn("CAS device tree does not fit in the guest buffer");
            requestStop(ShutdownReason.FATAL, "CAS buffer too small");
            return H.NO_MEM;
        }
        if (!mem.contains(buf, 4 + blob.length)) {
            return H.PARAMETER;
        }
        fdtBlob = blob;
        fdtInitialSize = blob.length;
        mem.write32be(buf, 1); // SpaprDeviceTreeUpdateHeader.version_id
        mem.write(buf + 4, blob, 0, blob.length);
        log.info("spapr_cas_continue Copy changes to the guest: " + (blob.length + 4) + " bytes");
        return H.SUCCESS;
    }

    private long hUpdateDt(CpuState s) {
        long dt = toReal(arg(s, 0));
        if (!mem.contains(dt, Fdt.HEADER_SIZE)) {
            return H.PARAMETER;
        }
        byte[] hdr = new byte[Fdt.HEADER_SIZE];
        mem.read(dt, hdr, 0, hdr.length);
        int cb = ByteBuffer.wrap(hdr).getInt(4);
        if (cb < 0 || cb > fdtInitialSize * 2L || !mem.contains(dt, cb)) {
            log.once("update-dt-size", "rejected device tree of " + cb + " bytes");
            return H.PARAMETER;
        }
        byte[] blob = new byte[cb];
        mem.read(dt, blob, 0, cb);
        try {
            Fdt.parse(blob);
        } catch (Fdt.FdtException e) {
            log.once("update-dt-check", e.getMessage());
            return H.PARAMETER;
        }
        fdtBlob = blob;
        return H.SUCCESS;
    }

    /** linux,rtas-base from the current device tree blob (as spapr_get_rtas_addr), or 0. */
    long rtasAddr() {
        try {
            Fdt.Node r = Fdt.parse(fdtBlob).find("rtas");
            if (r == null || !r.has("linux,rtas-base")) {
                return 0;
            }
            return r.getCell("linux,rtas-base", 0) & 0xffffffffL;
        } catch (Fdt.FdtException e) {
            return 0;
        }
    }
}
