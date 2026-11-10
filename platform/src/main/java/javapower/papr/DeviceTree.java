package javapower.papr;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Builds the flattened device tree the machine hands to SLOF at reset and back to the guest at
 * client-architecture-support time, following QEMU's {@code spapr_build_fdt} for
 * {@code -M pseries,ic-mode=xics -cpu power9 -smp 1 -nodefaults}.
 *
 * <p>Nodes and properties are created here in their final order. libfdt inserts each new
 * property and subnode at the front of its parent, so QEMU's source creates them in the reverse
 * of the order seen in the blob; the order below is the blob order (compare
 * {@code pseries-min.dts}).
 */
final class DeviceTree {
    private DeviceTree() {}

    static final int PHANDLE_INTC = 0x1111;
    static final long PHB0_BUID = 0x800000020000000L;
    static final int PHB0_LIOBN = 0x80000000;
    static final long PHB0_DMA_WINDOW = 0x40000000L;
    static final int PHB0_LSI_BASE = Xics.IRQ_PCI_LSI;
    static final int MAX_PHBS = 31;
    static final long LMB_SIZE = 256L << 20;
    static final int TIMEBASE_FREQ = 512000000;
    static final int CLOCK_FREQ = 1000000000;

    /** RTAS token names, indexed by token - 0x2000 (QEMU's RTAS_* numbering). */
    static final String[] RTAS_TOKENS = {
        "display-character", "get-time-of-day", "set-time-of-day", "power-off", "system-reboot",
        "query-cpu-stopped-state", "start-cpu", "stop-self", "ibm,get-system-parameter",
        "ibm,set-system-parameter", "ibm,set-xive", "ibm,get-xive", "ibm,int-off", "ibm,int-on",
        "check-exception", "event-scan", "ibm,set-tce-bypass", "quiesce", "nvram-fetch", "nvram-store",
        "read-pci-config", "write-pci-config", "ibm,read-pci-config", "ibm,write-pci-config",
        "ibm,query-interrupt-source-number", "ibm,change-msi", "set-indicator", "set-power-level",
        "get-power-level", "get-sensor-state", "ibm,configure-connector", "ibm,os-term",
        "ibm,set-eeh-option", "ibm,get-config-addr-info2", "ibm,read-slot-reset-state2",
        "ibm,set-slot-reset", "ibm,configure-pe", "ibm,slot-error-detail", "ibm,query-pe-dma-window",
        "ibm,create-pe-dma-window", "ibm,remove-pe-dma-window", "ibm,reset-pe-dma-window",
        "ibm,suspend-me", "ibm,nmi-register", "ibm,nmi-interlock", "ibm,configure-kernel-dump",
    };

    // ibm,pa-features tables (QEMU spapr_dt_pa_features), including the 2-byte header.
    private static final int[] PA_206 = {6, 0, 0xf6, 0x1f, 0xc7, 0x00, 0x00, 0xc0};
    private static final int[] PA_207 = {24, 0,
        0xf6, 0x1f, 0xc7, 0xc0, 0x00, 0xf0, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x80, 0x00, 0x80, 0x00, 0x80, 0x00, 0x00, 0x00};
    private static final int[] PA_300 = {66, 0,
        0xf6, 0x1f, 0xc7, 0xc0, 0x00, 0xf0, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x80, 0x00, 0x80, 0x00, 0x80, 0x00, 0x00, 0x00,
        0x80, 0x00, 0x80, 0x00, 0x80, 0x00, 0x00, 0x00, 0x80, 0x00, 0xc0, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x80, 0x00, 0x80, 0x00, 0x80, 0x00, 0x80, 0x00,
        0x80, 0x00, 0x80, 0x00, 0x80, 0x00, 0x80, 0x00, 0x80, 0x00, 0x80, 0x00,
        0x80, 0x00, 0x80, 0x00, 0x00, 0x00};

    /**
     * @param reset true for the tree placed in memory at machine reset (includes the boot-time
     *              {@code /chosen} properties), false for the tree returned by H_CAS
     */
    static Fdt.Node build(Machine m, boolean reset) {
        MachineConfig c = m.config();
        Fdt.Node root = new Fdt.Node("");

        // Root properties.
        drcProps(root, m.drcs(Drc.Kind.PHB));
        root.setCell("#size-cells", 2);
        root.setCell("#address-cells", 2);
        if (c.vmName != null) {
            root.setString("ibm,partition-name", c.vmName);
        }
        String uuid = c.uuid != null ? c.uuid : "00000000-0000-0000-0000-000000000000";
        if (c.uuid != null) {
            root.setString("system-id", uuid);
        }
        root.setString("vm,uuid", uuid);
        root.setString("compatible", "qemu,pseries");
        root.setString("model", "IBM pSeries (emulated by qemu)");
        root.setString("device_type", "chrp");

        Fdt.Node pmem = root.addChild("ibm,persistent-memory");
        pmem.setString("device_type", "ibm,persistent-memory");
        pmem.setCell("#size-cells", 0);
        pmem.setCell("#address-cells", 1);

        chosen(m, root.addChild("chosen"), reset);
        rtas(m, root.addChild("rtas"));
        eventSources(root.addChild("event-sources"));
        cpus(m, root.addChild("cpus"));
        if (c.phb) {
            root.addChild(phbNode(m));
        }
        if (c.rng) {
            Fdt.Node pf = root.addChild("ibm,platform-facilities");
            pf.setCell("#size-cells", 0);
            pf.setCell("#address-cells", 1);
            pf.setString("device_type", "ibm,platform-facilities");
            pf.addChild("ibm,random-v1").setString("compatible", "ibm,random");
        }
        vdevice(m, root.addChild("vdevice"));

        Fdt.Node mem = root.addChild("memory@0");
        mem.set("ibm,associativity", associativity(m));
        mem.setU64s("reg", 0, m.rmaSize());
        mem.setString("device_type", "memory");
        // Memory beyond the RMA (only with more than 1 TiB of RAM) in power-of-two aligned chunks.
        long start = m.rmaSize();
        long left = c.ramSize - start;
        while (left > 0) {
            long sz = Long.highestOneBit(left);
            if (Long.numberOfTrailingZeros(start) < Long.numberOfTrailingZeros(sz)) {
                sz = 1L << Long.numberOfTrailingZeros(start);
            }
            Fdt.Node n = root.addChild(String.format("memory@%x", start));
            n.set("ibm,associativity", associativity(m));
            n.setU64s("reg", start, sz);
            n.setString("device_type", "memory");
            start += sz;
            left -= sz;
        }

        Fdt.Node ic = root.addChild("interrupt-controller");
        ic.setCell("phandle", PHANDLE_INTC);
        ic.setCell("linux,phandle", PHANDLE_INTC);
        ic.setCell("#interrupt-cells", 2);
        ic.setCells("ibm,interrupt-server-ranges", 0, 1);
        ic.setEmpty("interrupt-controller");
        ic.setString("compatible", "IBM,ppc-xicp");
        ic.setString("device_type", "PowerPC-External-Interrupt-Presentation");
        return root;
    }

    /** {@code ibm,drc-types/names/power-domains/indexes} for a set of connectors (spapr_dt_drc). */
    static void drcProps(Fdt.Node n, List<Drc> drcs) {
        ByteArrayOutputStream types = new ByteArrayOutputStream();
        ByteArrayOutputStream names = new ByteArrayOutputStream();
        int[] power = new int[drcs.size() + 1];
        int[] idx = new int[drcs.size() + 1];
        types.writeBytes(Fdt.cells(drcs.size()));
        names.writeBytes(Fdt.cells(drcs.size()));
        power[0] = drcs.size();
        idx[0] = drcs.size();
        for (int i = 0; i < drcs.size(); i++) {
            Drc d = drcs.get(i);
            types.writeBytes(Fdt.strz(d.kind.typeName));
            names.writeBytes(Fdt.strz(d.name()));
            power[i + 1] = -1;
            idx[i + 1] = d.index();
        }
        n.set("ibm,drc-types", types.toByteArray());
        n.set("ibm,drc-names", names.toByteArray());
        n.setCells("ibm,drc-power-domains", power);
        n.setCells("ibm,drc-indexes", idx);
    }

    private static boolean form2(Machine m) {
        return m.ov5Cas().test(OptionVector.OV5_FORM2_AFFINITY);
    }

    private static byte[] associativity(Machine m) {
        return form2(m) ? Fdt.cells(1, 0) : Fdt.cells(4, 0, 0, 0, 0);
    }

    private static void chosen(Machine m, Fdt.Node ch, boolean reset) {
        MachineConfig c = m.config();
        ch.set("ibm,architecture-vec-5", m.ov5Cas().toDtBytes());
        ch.set("rng-seed", m.rngSeed());
        if (!reset) {
            return;
        }
        // XICS only; TCG offers both hash and radix (QEMU's value, kept for fidelity even though
        // this CPU implements radix only); hash options: none; radix options: GTSE.
        ch.set("ibm,arch-vec-5-platform-support", new byte[] {23, 0x00, 24, (byte) 0xc0, 25, 0x00, 26, 0x40});
        ch.setCell("linux,pci-probe-only", 0);
        String stdout = "/vdevice/" + m.vty().nodeName();
        ch.setString("stdout-path", stdout);
        ch.setString("linux,stdout-path", stdout);
        if (c.bootDevice != null && !c.bootDevice.isEmpty()) {
            ch.setString("qemu,boot-device", c.bootDevice);
        }
        ch.setCell("qemu,graphic-depth", 32);
        ch.setCell("qemu,graphic-height", 600);
        ch.setCell("qemu,graphic-width", 800);
        if (c.bootMenu) {
            ch.setCell("qemu,boot-menu", 1);
        }
        if (c.kernelCmdline != null && !c.kernelCmdline.isEmpty()) {
            ch.setString("bootargs", c.kernelCmdline);
        }
    }

    private static void rtas(Machine m, Fdt.Node r) {
        for (int i = RTAS_TOKENS.length - 1; i >= 0; i--) {
            r.setCell(RTAS_TOKENS[i], Rtas.TOKEN_BASE + i);
        }
        // FADump: CPU state data region sized for max_cpus = 1 (QEMU spapr_dt_rtas_fadump).
        r.setCells("ibm,configure-kernel-dump-sizes", 1, 0, 0x510, 2, 0, 0);
        r.set("ibm,configure-kernel-dump-version", new byte[] {0x01, 0x00, 0x01, 0x00});
        long maxAddr = m.config().ramSize;
        r.setCells("ibm,lrdr-capacity", (int) (maxAddr >>> 32), (int) maxAddr, (int) (LMB_SIZE >>> 32),
                (int) LMB_SIZE, 1);
        r.setEmpty("ibm,extended-os-term");
        r.setEmpty("ibm,change-msix-capable");
        r.setCell("rtas-event-scan-rate", Rtas.EVENT_SCAN_RATE);
        r.setCell("rtas-error-log-max", Rtas.ERROR_LOG_MAX);
        r.setCell("rtas-size", Rtas.MIN_SIZE + Rtas.ERROR_LOG_MAX + 16 + 8);
        if (form2(m)) {
            r.set("ibm,numa-distance-table", new byte[] {0, 0, 0, 1, 10});
            r.setCells("ibm,numa-lookup-index-table", 1, 0);
            r.setCells("ibm,max-associativity-domains", 1, 1);
            r.setCells("ibm,associativity-reference-points", 1);
        } else {
            r.setCells("ibm,max-associativity-domains", 4, 0, 0, 0, 1);
            r.setCells("ibm,associativity-reference-points", 4, 4, 2);
        }
        r.setStrings("qemu,hypertas-functions", "hcall-memop1");
        r.setStrings("ibm,hypertas-functions", "hcall-pft", "hcall-term", "hcall-dabr", "hcall-interrupt",
                "hcall-tce", "hcall-vio", "hcall-splpar", "hcall-join", "hcall-bulk", "hcall-set-mode",
                "hcall-sprg0", "hcall-copy", "hcall-debug", "hcall-vphn", "hcall-multi-tce", "hcall-hpt-resize",
                "hcall-watchdog");
    }

    private static void eventSources(Fdt.Node es) {
        es.setCells("interrupt-ranges", Xics.IRQ_EPOW, 1, Xics.IRQ_HOTPLUG, 1);
        es.setCell("#interrupt-cells", 2);
        es.setEmpty("interrupt-controller");
        es.addChild("hot-plug-events").setCells("interrupts", Xics.IRQ_HOTPLUG, 0);
        es.addChild("epow-events").setCells("interrupts", Xics.IRQ_EPOW, 0);
    }

    private static void cpus(Machine m, Fdt.Node cpus) {
        drcProps(cpus, m.drcs(Drc.Kind.CPU));
        cpus.setCell("#size-cells", 0);
        cpus.setCell("#address-cells", 1);
        cpus.addChild(cpuNode(m));
    }

    /** The node of CPU 0 (also the ibm,configure-connector fragment of its DRC). */
    static Fdt.Node cpuNode(Machine m) {
        Fdt.Node n = new Fdt.Node("PowerPC,POWER9@0");
        n.setCell("ibm,dec-bits", 56);
        n.setCells("ibm,processor-radix-AP-encodings", 0x0c, 0xa0000010, 0x20000015, 0x4000001e);
        n.setCells("ibm,ppc-interrupt-gserver#s", 0, 0);
        n.setCells("ibm,ppc-interrupt-server#s", 0);
        n.setCells("ibm,pft-size", 0, m.htabShift());
        n.setCell("ibm,chip-id", 0);
        n.set("ibm,pi-features", new byte[] {1, 0, 0});
        byte[] pa = paFeatures(m);
        if (pa != null) {
            n.set("ibm,pa-features", pa);
        }
        n.setCells("ibm,segment-page-sizes", 0x0c, 0x00, 0x01, 0x0c, 0x00, 0x10, 0x110, 0x01, 0x10, 0x01);
        n.setCell("ibm,dfp", 1);
        n.setCell("ibm,vmx", 2);
        n.setCells("ibm,processor-segment-sizes", 28, 40, -1, -1);
        n.setCell("ibm,spurr", 1);
        n.setCell("ibm,purr", 1);
        n.setEmpty("64-bit");
        n.setString("status", "okay");
        n.setCell("ibm,slb-size", 32);
        n.setCell("slb-size", 32);
        n.setCell("clock-frequency", CLOCK_FREQ);
        n.setCell("timebase-frequency", TIMEBASE_FREQ);
        n.setCell("i-cache-size", 0x8000);
        n.setCell("d-cache-size", 0x8000);
        n.setCell("i-cache-line-size", 128);
        n.setCell("i-cache-block-size", 128);
        n.setCell("d-cache-line-size", 128);
        n.setCell("d-cache-block-size", 128);
        n.setCell("cpu-version", m.compatPvr() != 0 ? m.compatPvr() : (int) javapower.core.Spr.PVR_POWER9);
        n.setString("device_type", "cpu");
        n.setCell("reg", 0);
        n.setCell("ibm,my-drc-index", Drc.Kind.CPU.base);
        return n;
    }

    private static byte[] paFeatures(Machine m) {
        int compat = m.compatPvr();
        int[] t;
        if (compat == 0 || compat >= Machine.PVR_LOGICAL_3_00) {
            t = PA_300;
        } else if (compat >= Machine.PVR_LOGICAL_2_07) {
            t = PA_207;
        } else {
            t = PA_206;
        }
        byte[] b = new byte[t.length];
        for (int i = 0; i < t.length; i++) {
            b[i] = (byte) t[i];
        }
        b[3] |= 0x20; // CI large pages (POWER9 supports them)
        if (m.casPreIsa3() && b.length > 40) {
            b[40 + 2] &= (byte) ~0x80; // hide radix from pre-ISA 3.00 guests
        }
        return b;
    }

    /** The node of PHB 0 (also the ibm,configure-connector fragment of its DRC). */
    static Fdt.Node phbNode(Machine m) {
        Fdt.Node p = new Fdt.Node(String.format("pci@%x", PHB0_BUID));
        phb(m, p);
        return p;
    }

    private static void phb(Machine m, Fdt.Node p) {
        drcProps(p, m.drcs(Drc.Kind.PCI));
        p.setCell("#size-cells", 2);
        p.setCell("#address-cells", 3);
        p.setCell("qemu,phb-enumerated", 1);
        p.setCell("ibm,my-drc-index", Drc.Kind.PHB.base);
        p.setCells("ibm,dma-window", PHB0_LIOBN, 0, 0, 0, (int) PHB0_DMA_WINDOW);
        p.setCell("ibm,#dma-size-cells", 2);
        p.setCell("ibm,#dma-address-cells", 2);
        int[] map = new int[32 * 4 * 7];
        int k = 0;
        for (int dev = 0; dev < 32; dev++) {
            for (int pin = 1; pin <= 4; pin++) {
                map[k++] = dev << 11;
                map[k++] = 0;
                map[k++] = 0;
                map[k++] = pin;
                map[k++] = PHANDLE_INTC;
                map[k++] = PHB0_LSI_BASE + ((pin - 1 + dev) % 4);
                map[k++] = 1;
            }
        }
        p.setCells("interrupt-map", map);
        p.setCells("interrupt-map-mask", 0xf800, 0, 0, -1);
        p.setCells("ibm,ddw-extensions", 2, Rtas.TOKEN_BASE + 0x29, 1);
        p.setCells("ibm,ddw-applicable", Rtas.TOKEN_BASE + 0x26, Rtas.TOKEN_BASE + 0x27, Rtas.TOKEN_BASE + 0x28);
        p.setCell("ibm,pe-total-#msi", 0xd00);
        p.setCell("ibm,pci-config-space-type", 1);
        p.setCells("reg", (int) (PHB0_BUID >>> 32), (int) PHB0_BUID, 0, 0);
        p.setCells("ranges",
                0x01000000, 0, 0, 0x2000, 0x00000000, 0, 0x10000,
                0x02000000, 0, 0x80000000, 0x2000, 0x80000000, 0, 0x80000000,
                0x03000000, 0x2100, 0, 0x2100, 0, 0x100, 0);
        p.setCells("bus-range", 0, 0xff);
        p.setEmpty("used-by-rtas");
        p.setCell("#interrupt-cells", 1);
        p.setString("compatible", "IBM,Logical_PHB");
        p.setString("device_type", "pci");
    }

    private static void vdevice(Machine m, Fdt.Node v) {
        v.setEmpty("interrupt-controller");
        v.setCell("#interrupt-cells", 2);
        v.setCell("#size-cells", 0);
        v.setCell("#address-cells", 1);
        v.setString("compatible", "IBM,vdevice");
        v.setString("device_type", "vdevice");
        for (VioDevice d : m.vioDevices()) {
            Fdt.Node n = v.addChild(d.nodeName());
            d.addDeviceProps(n);
            TceTable t = d.tce();
            if (t != null) {
                n.setCells("ibm,my-dma-window", t.liobn(), 0, 0, 0, (int) t.windowSize());
                n.setCell("ibm,#dma-size-cells", 2);
                n.setCell("ibm,#dma-address-cells", 2);
            }
            n.setCells("interrupts", d.irq(), 0);
            n.setString("compatible", d.dtCompatible());
            n.setString("device_type", d.dtType());
            n.setCell("reg", d.reg());
        }
    }

    /** Serialize the way QEMU does after fdt_pack (no reservations, boot CPU 0). */
    static byte[] blob(Fdt.Node root) {
        return Fdt.serialize(root);
    }

    static String str(byte[] b) {
        return new String(b, StandardCharsets.US_ASCII);
    }
}
