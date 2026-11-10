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
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PhysicalMemory;
import org.boxtheta.javapower.core.Spr;

/**
 * The PAPR {@code pseries} machine: guest RAM, the SLOF firmware image, the device tree, XICS,
 * the VIO devices (nvram, vty, v-scsi) and the hypervisor itself. Mirrors QEMU's
 * {@code -M pseries,ic-mode=xics,usb=off -cpu power9 -smp 1 -nodefaults} closely enough for the
 * stock SLOF binary.
 *
 * <p>Typical use from {@code app}:
 * <pre>{@code
 * MachineConfig cfg = new MachineConfig().ram(2L << 30).console(new StdioCharBackend());
 * cfg.addDisk(Path.of("disk.raw")).blockSize(4096);
 * Machine m = new Machine(cfg);
 * CpuState cpu = new CpuState();
 * // ... create the interpreter on m.memory(), then:
 * m.attachCpu(cpu);
 * m.reset(cpu);
 * // run the CPU; when it returns with cpu.stopRequested, look at m.shutdownReason():
 * // REBOOT -> m.reset(cpu) and run again; anything else -> m.close().
 * }</pre>
 *
 * <p>Threading: {@link #hcall} runs on the CPU thread. Console input arrives on backend threads
 * and is handed to the vty under the machine lock, which also serialises hcalls, {@link #pump}
 * and {@link #requestPowerdown}. Interrupt delivery to the CPU goes through
 * {@link CpuState#setExternalIrq}, which is safe from any thread.
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
    private final Xics xics = new Xics();
    private final Random random;
    private final byte[] firmware;

    private final Nvram nvram;
    private final Vty vty;
    private final VScsi vscsi;
    private final List<VioDevice> vio = new ArrayList<>();
    private final List<TceTable> tceTables = new ArrayList<>();
    private final TceTable phbTce;
    private final List<Drc> drcs = new ArrayList<>();
    private final Rtas rtas;

    private CpuState cpu;
    private volatile ShutdownReason shutdown = ShutdownReason.NONE;
    private volatile String shutdownMessage;
    private Consumer<CpuState> systemResetHandler;

    // Machine state reset at every machine reset.
    private OptionVector ov5 = new OptionVector();
    private OptionVector ov5Cas = new OptionVector();
    private int compatPvr;
    private boolean casPreIsa3;
    private long patbEntry;
    private int htabShift;
    private byte[] rngSeed = new byte[32];
    private byte[] fdtBlob;
    private int fdtInitialSize;
    private long fdtAddr;
    private boolean prod;
    private long vpaAddr;
    private long slbShadowAddr;
    private int slbShadowSize;
    private long dtlAddr;
    private int dtlSize;
    private final Watchdog[] watchdogs = {new Watchdog(), new Watchdog(), new Watchdog(), new Watchdog()};

    /** One of the four PAPR watchdogs (H_WATCHDOG), timed on the host monotonic clock. */
    private static final class Watchdog {
        long deadline;
        boolean armed;
        int action;
        boolean leaveOthers;
    }

    public Machine(MachineConfig config) throws IOException {
        this.config = config;
        if (config.ramSize < (256L << 20) || config.ramSize % (256L << 20) != 0) {
            throw new IllegalArgumentException("RAM size must be a non-zero multiple of 256 MiB");
        }
        this.mem = new PhysicalMemory(config.ramSize);
        this.random = config.random != null ? config.random : new SecureRandom();
        byte[] fw = config.firmwareImage;
        if (fw == null && config.firmware != null) {
            fw = Files.readAllBytes(config.firmware);
        }
        if (fw != null && fw.length > FW_MAX_SIZE) {
            throw new IOException("firmware image larger than " + FW_MAX_SIZE + " bytes");
        }
        this.firmware = fw;

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

        // VIO devices in creation order: nvram (always first), vty, v-scsi.
        nvram = config.nvramFile != null ? new Nvram(config.nvramFile) : new Nvram();
        CharBackend console = config.console != null ? config.console : new BufferCharBackend();
        vty = new Vty(console);
        vscsi = new VScsi();
        int reg = VioDevice.REG_BASE;
        for (VioDevice d : List.of(nvram, vty, vscsi)) {
            d.attach(this, reg++);
            xics.claim(d.irq(), false);
            vio.add(d);
            if (d.tce() != null) {
                tceTables.add(d.tce());
            }
        }
        if (config.phb) {
            phbTce = new TceTable(DeviceTree.PHB0_LIOBN, 0, DeviceTree.PHB0_DMA_WINDOW);
            tceTables.add(phbTce);
        } else {
            phbTce = null;
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
            vscsi.addTarget(new ScsiDisk(d.type, b, d.blockSize, pbs, devId, d.serial, 0, id, d.lun));
            n++;
        }

        // DR connectors: one CPU core, 31 PHB slots, and the 256 PCI slots of PHB 0.
        drcs.add(new Drc(Drc.Kind.CPU, 0, () -> DeviceTree.cpuNode(this)));
        for (int i = 0; i < DeviceTree.MAX_PHBS; i++) {
            drcs.add(new Drc(Drc.Kind.PHB, i, i == 0 && config.phb ? () -> DeviceTree.phbNode(this) : null));
        }
        if (config.phb) {
            for (int i = 0; i < 256; i++) {
                drcs.add(new Drc(Drc.Kind.PCI, i, null));
            }
        }
        rtas = new Rtas(this);
        vty.start();
    }

    // ---- accessors ---------------------------------------------------------------------------

    public MachineConfig config() {
        return config;
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

    List<VioDevice> vioDevices() {
        return Collections.unmodifiableList(vio);
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

    long rmaSize() {
        return Math.min(config.ramSize, 1L << 40);
    }

    Random random() {
        return random;
    }

    Object lock() {
        return lock;
    }

    CpuState cpu() {
        return cpu;
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
     * Install the action for H_SIGNAL_SYS_RESET aimed at the CPU (deliver a system reset
     * interrupt). Without one the call is reported as unimplemented and fails with H_HARDWARE.
     */
    public void setSystemResetHandler(Consumer<CpuState> h) {
        this.systemResetHandler = h;
    }

    // ---- CPU wiring and reset ----------------------------------------------------------------

    /** Connect the (single) CPU: its interrupt input, and this machine as its hypervisor. */
    public void attachCpu(CpuState s) {
        synchronized (lock) {
            if (cpu != null && cpu != s) {
                throw new IllegalStateException("only one CPU is supported");
            }
            if (cpu == null) {
                xics.addServer(0, s::setExternalIrq);
            }
            cpu = s;
            s.hv = this;
        }
    }

    /**
     * Machine reset (power-on or reboot), then set up {@code s} the way QEMU hands control to
     * SLOF: firmware at 0, device tree at {@link #fdtAddress()}, PC = 0x100, r3 = tree address,
     * MSR = ME|FP, POWER9 SPR reset values. Call after the interpreter's own reset so these values
     * win; the decrementer and timebase are the CPU module's.
     */
    public void reset(CpuState s) {
        synchronized (lock) {
            if (cpu == null) {
                attachCpu(s);
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
            prod = false;
            vpaAddr = slbShadowAddr = dtlAddr = 0;
            slbShadowSize = dtlSize = 0;
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

            if (firmware != null) {
                mem.write(0, firmware, 0, firmware.length);
            }
            fdtAddr = Math.min(rmaSize(), FDT_MAX_ADDR) - FDT_MAX_SIZE;
            byte[] blob = DeviceTree.blob(DeviceTree.build(this, true));
            if (blob.length > FDT_MAX_SIZE) {
                throw new IllegalStateException("device tree too large");
            }
            mem.write(fdtAddr, blob, 0, blob.length);
            fdtBlob = blob;
            fdtInitialSize = blob.length;

            resetCpuState(s);
            s.pc = ENTRY_POINT;
            s.npc = ENTRY_POINT;
            s.gpr[3] = fdtAddr;
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
                Spr.PSPB, Spr.MMCR1, Spr.MMCR2, Spr.MMCRA, Spr.BESCR, Spr.EBBHR, Spr.EBBRR, Spr.PTCR}) {
            s.spr[r] = 0;
        }
        s.spr[Spr.PVR] = Spr.PVR_POWER9;
        // LPCR as QEMU reports it at the SLOF entry point: LD | PECE (PDEE HDEE EEE DEE OEE) | LPES0.
        s.spr[Spr.LPCR] = 0x3f008L;
        s.spr[SPR_AMOR] = -1L;
        s.spr[SPR_PSSCR] = 0x100000L; // EC
        s.spr[Spr.MMCR0] = 0x80000000L; // FC
        s.spr[SPR_PMCR] = 1;
        s.spr[SPR_RPR] = 0x103070f1f3fL;
        s.resValid = false;
        s.procTable = 0;
        s.ceded = false;
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
                    PaprLog.warn("watchdog " + (i + 1) + " expired, action " + w.action);
                    switch (w.action) {
                        case 1 -> requestStop(ShutdownReason.POWER_OFF, "watchdog");
                        case 2 -> requestStop(ShutdownReason.REBOOT, "watchdog");
                        default -> {
                            if (systemResetHandler != null && cpu != null) {
                                systemResetHandler.accept(cpu);
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

    /** Ask the CPU to stop with the given reason (first reason wins until the next reset). */
    void requestStop(ShutdownReason r, String msg) {
        if (shutdown == ShutdownReason.NONE) {
            shutdown = r;
            shutdownMessage = msg;
        }
        if (cpu != null) {
            cpu.stopRequested = true;
            cpu.wake();
        }
    }

    /** Close the backends (disk images, NVRAM file, console). */
    @Override
    public void close() throws IOException {
        synchronized (lock) {
            IOException first = null;
            for (ScsiTarget t : vscsi.targets()) {
                if (t instanceof ScsiDisk d) {
                    try {
                        d.backend().close();
                    } catch (IOException e) {
                        first = first == null ? e : first;
                    }
                }
            }
            try {
                nvram.close();
            } catch (IOException e) {
                first = first == null ? e : first;
            }
            vty.backend().close();
            if (first != null) {
                throw first;
            }
        }
    }

    // ---- hypercalls --------------------------------------------------------------------------

    static long toReal(long addr) {
        return addr & 0x0fffffffffffffffL;
    }

    @Override
    public void hcall(CpuState s) {
        synchronized (lock) {
            long op = s.gpr[3];
            s.gpr[3] = dispatch(s, op);
        }
    }

    private long arg(CpuState s, int i) {
        return s.gpr[4 + i];
    }

    private static void ret(CpuState s, int i, long v) {
        s.gpr[4 + i] = v;
    }

    private long dispatch(CpuState s, long op) {
        if (op == H.KVMPPC_H_RTAS) {
            return rtas.call(s, toReal(arg(s, 0)));
        }
        if (op == H.KVMPPC_H_CAS) {
            return hCas(s);
        }
        if (op == H.KVMPPC_H_UPDATE_DT) {
            return hUpdateDt(s);
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
            case H.RESIZE_HPT_PREPARE, H.RESIZE_HPT_COMMIT -> htabShift == 0 ? H.NOT_AVAILABLE : hptResize(op);
            case H.SET_SPRG0 -> {
                s.spr[Spr.SPRG0] = arg(s, 0);
                yield H.SUCCESS;
            }
            case H.SET_DABR, H.SET_XDABR -> H.HARDWARE; // POWER9 has no DABR
            case H.PAGE_INIT -> hPageInit(s);
            case H.REGISTER_VPA -> hRegisterVpa(s);
            case H.CEDE -> hCede(s);
            case H.CONFER -> hConfer(s);
            case H.PROD -> hProd(s);
            case H.JOIN -> (s.msr & Msr.EE) != 0 ? H.BAD_MODE : H.CONTINUE;
            case H.LOGICAL_CI_LOAD, H.LOGICAL_CACHE_LOAD -> hLogicalLoad(s);
            case H.LOGICAL_CI_STORE, H.LOGICAL_CACHE_STORE -> hLogicalStore(s);
            case H.LOGICAL_ICBI, H.LOGICAL_DCBF -> H.SUCCESS;
            case H.SET_MODE -> hSetMode(s);
            case H.CLEAN_SLB, H.INVALIDATE_PID -> {
                PaprLog.unimplemented("hcall-0x" + Long.toHexString(op), "");
                yield H.FUNCTION;
            }
            case H.REGISTER_PROC_TBL -> hRegisterProcTbl(s);
            case H.SIGNAL_SYS_RESET -> hSignalSysReset(s);
            case H.GET_CPU_CHARACTERISTICS -> hGetCpuCharacteristics(s);
            case H.GET_TERM_CHAR -> hGetTermChar(s);
            case H.PUT_TERM_CHAR -> hPutTermChar(s);
            case H.EOI -> {
                xics.eoi(0, (int) arg(s, 0));
                yield H.SUCCESS;
            }
            case H.CPPR -> {
                xics.setCppr(0, (int) arg(s, 0) & 0xff);
                yield H.SUCCESS;
            }
            case H.IPI -> {
                if (arg(s, 0) != 0 || !xics.hasServer(0)) {
                    yield H.PARAMETER;
                }
                xics.setMfrr(0, (int) arg(s, 1) & 0xff);
                yield H.SUCCESS;
            }
            case H.IPOLL -> {
                if (arg(s, 0) != 0) {
                    yield H.PARAMETER;
                }
                int[] r = xics.ipoll(0);
                ret(s, 0, r[0] & 0xffffffffL);
                ret(s, 1, r[1]);
                yield H.SUCCESS;
            }
            case H.XIRR -> {
                ret(s, 0, xics.accept(0) & 0xffffffffL);
                yield H.SUCCESS;
            }
            case H.XIRR_X -> {
                ret(s, 0, xics.accept(0) & 0xffffffffL);
                ret(s, 1, System.nanoTime());
                yield H.SUCCESS;
            }
            case H.REG_CRQ -> {
                VioDevice d = vioByReg(arg(s, 0));
                yield d == null ? H.PARAMETER : d.registerCrq(arg(s, 1), arg(s, 2));
            }
            case H.FREE_CRQ -> {
                VioDevice d = vioByReg(arg(s, 0));
                yield d == null ? H.PARAMETER : d.freeCrq();
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
                yield d.handleCrq(crq);
            }
            case H.ENABLE_CRQ -> vioByReg(arg(s, 0)) == null ? H.PARAMETER : H.SUCCESS;
            case H.VIO_SIGNAL -> {
                VioDevice d = vioByReg(arg(s, 0));
                if (d == null || (arg(s, 1) & ~d.signalMask()) != 0) {
                    yield H.PARAMETER;
                }
                d.setSignalState(arg(s, 1));
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
            case H.HOME_NODE_ASSOCIATIVITY -> hHomeNodeAssociativity(s);
            case H.WATCHDOG -> hWatchdog(s);
            default -> unknown(op);
        };
    }

    private static long unknown(long op) {
        PaprLog.unimplemented("hcall-0x" + Long.toHexString(op), "unimplemented hypercall, returning H_FUNCTION");
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
        PaprLog.unimplemented("hash-mmu-hcall", String.format("hcall 0x%x (hash MMU not supported)", op));
        return H.PARAMETER;
    }

    private long hptResize(long op) {
        PaprLog.unimplemented("hpt-resize", String.format("hcall 0x%x", op));
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
        if (procno != 0) {
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
            vpaAddr = addr;
            mem.write8(addr + 9, mem.read8(addr + 9) | 0x2); // shared processor
            return H.SUCCESS;
        } else if (flags == deregisterVpa) {
            if (slbShadowAddr != 0 || dtlAddr != 0) {
                return H.RESOURCE;
            }
            vpaAddr = 0;
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
            if (vpaAddr == 0) {
                return H.RESOURCE;
            }
            slbShadowAddr = addr;
            slbShadowSize = size;
            return H.SUCCESS;
        } else if (flags == deregisterSlb) {
            slbShadowAddr = 0;
            slbShadowSize = 0;
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
            if (vpaAddr == 0) {
                return H.RESOURCE;
            }
            dtlAddr = addr;
            dtlSize = size;
            return H.SUCCESS;
        } else if (flags == deregisterDtl) {
            dtlAddr = 0;
            dtlSize = 0;
            return H.SUCCESS;
        }
        return H.PARAMETER;
    }

    /** Registered VPA address (0 if none); tests and debugging. */
    public long vpaAddress() {
        return vpaAddr;
    }

    private long hCede(CpuState s) {
        s.msr |= Msr.EE;
        if (prod) {
            prod = false;
            return H.SUCCESS;
        }
        if (!s.extIrq) {
            s.ceded = true;
        }
        return H.SUCCESS;
    }

    private long hConfer(CpuState s) {
        long target = arg(s, 0);
        if (target == -1) {
            return H.SUCCESS; // yield to everyone else: nobody else to run
        }
        if (target != 0) {
            return H.PARAMETER;
        }
        // Confer to self: wait until prodded (or interrupted).
        if (prod) {
            prod = false;
            return H.SUCCESS;
        }
        s.ceded = true;
        return H.SUCCESS;
    }

    private long hProd(CpuState s) {
        if (arg(s, 0) != 0) {
            return H.PARAMETER;
        }
        prod = true;
        s.ceded = false;
        s.wake();
        return H.SUCCESS;
    }

    private long hLogicalLoad(CpuState s) {
        long size = arg(s, 0);
        long addr = arg(s, 1);
        if (size != 1 && size != 2 && size != 4 && size != 8) {
            return H.PARAMETER;
        }
        long v = 0;
        if (mem.contains(addr, size)) {
            v = switch ((int) size) {
                case 1 -> mem.read8(addr);
                case 2 -> mem.read16be(addr);
                case 4 -> mem.read32be(addr) & 0xffffffffL;
                default -> mem.read64be(addr);
            };
        } else {
            PaprLog.once("logical-load-unassigned", String.format("addr 0x%x", addr));
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
        if (mem.contains(addr, size)) {
            switch ((int) size) {
                case 1 -> mem.write8(addr, (int) val);
                case 2 -> mem.write16be(addr, (int) val);
                case 4 -> mem.write32be(addr, (int) val);
                default -> mem.write64be(addr, val);
            }
        } else {
            PaprLog.once("logical-store-unassigned", String.format("addr 0x%x", addr));
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
                s.spr[Spr.LPCR] = (s.spr[Spr.LPCR] & ~Spr.LPCR_AIL_MASK) | (mflags << Spr.LPCR_AIL_SHIFT);
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
                    s.spr[Spr.LPCR] &= ~Spr.LPCR_ILE;
                    return H.SUCCESS;
                }
                if (mflags == 1) {
                    s.spr[Spr.LPCR] |= Spr.LPCR_ILE;
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
                    PaprLog.once("proc-tbl-unaligned", String.format("proc_tbl 0x%x size 0x%x", procTbl, bytes));
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
                    PaprLog.warn("guest registered a hash MMU process table; only radix is supported");
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
        s.spr[Spr.LPCR] = (s.spr[Spr.LPCR] & ~mask) | update;
        s.procTable = (cproc & PATE1_GR) != 0 ? cproc & ~PATE1_GR : 0;
        return H.SUCCESS;
    }

    /** The process table entry as QEMU stores it (PATE1_GR | base | size for radix). */
    public long patbEntry() {
        synchronized (lock) {
            return patbEntry;
        }
    }

    private long hSignalSysReset(CpuState s) {
        long target = arg(s, 0);
        if (target < 0) {
            if (target < -2) {
                return H.PARAMETER;
            }
            if (target == -2) {
                return H.SUCCESS; // all but self: there is no other CPU
            }
        } else if (target != 0) {
            return H.PARAMETER;
        }
        if (systemResetHandler == null) {
            PaprLog.unimplemented("signal-sys-reset", "no system reset handler installed");
            return H.HARDWARE;
        }
        systemResetHandler.accept(s);
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
        if (arg(s, 1) != 0) {
            return H.P2;
        }
        // vCPU associativity without its length cell: FORM1 {0,0,0,node,vcpu}, FORM2 {node,vcpu}.
        int[] assoc = ov5Cas.test(OptionVector.OV5_FORM2_AFFINITY) ? new int[] {0, 0} : new int[] {0, 0, 0, 0, 0};
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

    private long hCas(CpuState s) {
        long vec = toReal(arg(s, 0));
        long buf = arg(s, 1);
        long bufSize = arg(s, 2);
        if (Long.compareUnsigned(bufSize, 4) < 0) {
            PaprLog.warn("SLOF provided insufficient CAS buffer " + bufSize);
            requestStop(ShutdownReason.FATAL, "insufficient CAS buffer");
            return H.PARAMETER;
        }
        bufSize -= 4;

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
        PaprLog.info(String.format("spapr_cas_pvr current=0x%x, explicit_match=%d, new=0x%x", compatPvr,
                explicit ? 1 : 0, best));
        if (best == 0 && !explicit) {
            PaprLog.warn("Couldn't negotiate a suitable PVR during CAS");
            return H.HARDWARE;
        }
        compatPvr = best;

        OptionVector ov1 = OptionVector.parse(mem, vec, 1);
        if (ov1 == null) {
            PaprLog.warn("guest didn't provide option vector 1");
            return H.PARAMETER;
        }
        OptionVector guest5 = OptionVector.parse(mem, vec, 5);
        if (guest5 == null) {
            PaprLog.warn("guest didn't provide option vector 5");
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
            PaprLog.warn("guest chose the hash MMU at CAS; only radix is implemented");
        }
        ov5Cas = ov5.intersect(guest5);
        casPreIsa3 = !ov1.test(OptionVector.OV1_PPC_3_00);

        byte[] blob = DeviceTree.blob(DeviceTree.build(this, false));
        if (blob.length > bufSize) {
            PaprLog.warn("CAS device tree does not fit in the guest buffer");
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
        PaprLog.info("spapr_cas_continue Copy changes to the guest: " + (blob.length + 4) + " bytes");
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
            PaprLog.once("update-dt-size", "rejected device tree of " + cb + " bytes");
            return H.PARAMETER;
        }
        byte[] blob = new byte[cb];
        mem.read(dt, blob, 0, cb);
        try {
            Fdt.parse(blob);
        } catch (Fdt.FdtException e) {
            PaprLog.once("update-dt-check", e.getMessage());
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
