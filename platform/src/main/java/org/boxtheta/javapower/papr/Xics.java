package org.boxtheta.javapower.papr;

import java.util.BitSet;
import java.util.Map;
import java.util.TreeMap;

/**
 * Emulated XICS interrupt controller as used by PAPR guests in "legacy" (non-XIVE) mode: one
 * interrupt source controller (ICS) covering global interrupt numbers 0x1000..0x1fff, and one
 * interrupt presentation controller (ICP) per server (= vCPU id), accessed through the
 * H_CPPR/H_IPI/H_XIRR/H_XIRR_X/H_EOI/H_IPOLL hcalls and the ibm,set-xive/get-xive/int-on/int-off
 * RTAS calls.
 *
 * <p>Semantics follow QEMU's hw/intc/xics.c: the ICP holds XIRR (CPPR in bits 31..24 Java
 * numbering, XISR in 23..0), the priority of the pending interrupt and the MFRR; a source is
 * presented only if its priority is more favoured (numerically lower) than both the CPPR and any
 * already pending interrupt, otherwise it is rejected and resent later. MSI (edge) sources are
 * remembered as "rejected"/"masked pending"; LSI (level) sources are resent while asserted.
 *
 * <p>All methods are synchronized on this object, a leaf lock: hcalls from every CPU thread
 * (each CPU's own ICP, plus H_IPI/H_IPOLL and ibm,set-xive aimed at any server) and device input
 * threads raising interrupts all serialise here, and every operation is short. While holding it
 * the controller only calls its servers' output lines, which drive
 * {@link org.boxtheta.javapower.core.CpuState#setExternalIrq} (level-triggered, lock-free).
 */
public final class Xics {
    public static final int IRQ_BASE = 0x1000;
    public static final int NR_IRQS = 0x1000;
    /** XISR value of an inter-processor interrupt. */
    public static final int IPI = 2;

    /** First source number of VIO devices (QEMU's SPAPR_IRQ_VIO). */
    public static final int IRQ_VIO = IRQ_BASE + 0x100;
    public static final int IRQ_EPOW = IRQ_BASE;
    public static final int IRQ_HOTPLUG = IRQ_BASE + 1;
    public static final int IRQ_PCI_LSI = IRQ_BASE + 0x200;

    private static final int XISR_MASK = 0x00ffffff;

    private static final int ST_ASSERTED = 1;
    private static final int ST_SENT = 2;
    private static final int ST_REJECTED = 4;
    private static final int ST_MASKED_PENDING = 8;

    private static final int FL_LSI = 1;
    private static final int FL_MSI = 2;

    /** The interrupt output of a presenter. */
    @FunctionalInterface
    public interface IrqLine {
        void set(boolean level);
    }

    /** Per-server presentation controller state. */
    public static final class Icp {
        final int server;
        final IrqLine output;
        int xirr;
        int pendingPriority = 0xff;
        int mfrr = 0xff;
        /** XISR currently holds a source from the ICS (as opposed to an IPI or nothing). */
        boolean xisrFromIcs;
        boolean outputLevel;

        Icp(int server, IrqLine output) {
            this.server = server;
            this.output = output;
        }

        int cppr() {
            return xirr >>> 24;
        }

        int xisr() {
            return xirr & XISR_MASK;
        }

        void raise() {
            outputLevel = true;
            output.set(true);
        }

        void lower() {
            outputLevel = false;
            output.set(false);
        }
    }

    private final int[] server = new int[NR_IRQS];
    private final int[] priority = new int[NR_IRQS];
    private final int[] savedPriority = new int[NR_IRQS];
    private final int[] status = new int[NR_IRQS];
    private final int[] flags = new int[NR_IRQS];
    private final Map<Integer, Icp> icps = new TreeMap<>();
    /**
     * Sources a resend could present: rejected MSIs and asserted, unsent LSIs. Kept in step with
     * {@link #status} so a resend visits only these instead of all {@link #NR_IRQS} sources.
     */
    private final BitSet resendable = new BitSet(NR_IRQS);

    private final PaprLog log;

    /** A controller with a log of its own (tests); a machine passes its log. */
    public Xics() {
        this(new PaprLog());
    }

    Xics(PaprLog log) {
        this.log = log;
        reset();
    }

    /** Register a presenter for {@code serverNo} (the vCPU id). */
    public synchronized Icp addServer(int serverNo, IrqLine output) {
        Icp icp = new Icp(serverNo, output);
        icps.put(serverNo, icp);
        return icp;
    }

    public synchronized boolean hasServer(int serverNo) {
        return icps.containsKey(serverNo);
    }

    /** Machine reset: all sources masked (priority 0xff), all presenters idle. Claims are kept. */
    public synchronized void reset() {
        for (int i = 0; i < NR_IRQS; i++) {
            server[i] = 0;
            priority[i] = 0xff;
            savedPriority[i] = 0xff;
            status[i] = 0;
        }
        resendable.clear();
        for (Icp icp : icps.values()) {
            icp.xirr = 0;
            icp.pendingPriority = 0xff;
            icp.mfrr = 0xff;
            icp.xisrFromIcs = false;
            icp.lower();
        }
    }

    public static boolean validIrq(long nr) {
        return nr >= IRQ_BASE && nr < IRQ_BASE + NR_IRQS;
    }

    /** Claim a source as level (LSI) or edge (MSI) triggered, like spapr_irq_claim. */
    public synchronized void claim(int irq, boolean lsi) {
        int src = irq - IRQ_BASE;
        if (flags[src] != 0) {
            throw new IllegalStateException(String.format("IRQ 0x%x is not free", irq));
        }
        flags[src] = lsi ? FL_LSI : FL_MSI;
    }

    public synchronized boolean isLsi(int irq) {
        return (flags[irq - IRQ_BASE] & FL_LSI) != 0;
    }

    // ---- source side -------------------------------------------------------------------------

    /** Drive a source input. For MSIs only a rising (non-zero) value has an effect. */
    public synchronized void setIrq(int irq, boolean level) {
        int src = irq - IRQ_BASE;
        if ((flags[src] & FL_LSI) != 0) {
            if (level) {
                setStatus(src, status[src] | ST_ASSERTED);
            } else {
                setStatus(src, status[src] & ~ST_ASSERTED);
            }
            resendLsi(src);
        } else if (level) {
            if (priority[src] == 0xff) {
                setStatus(src, status[src] | ST_MASKED_PENDING);
            } else {
                icpIrq(server[src], irq, priority[src]);
            }
        }
    }

    /** Edge-pulse a source (qemu_irq_pulse). */
    public synchronized void pulse(int irq) {
        setIrq(irq, true);
        setIrq(irq, false);
    }

    private void resendLsi(int src) {
        if (priority[src] != 0xff && (status[src] & ST_ASSERTED) != 0 && (status[src] & ST_SENT) == 0) {
            setStatus(src, status[src] | ST_SENT);
            icpIrq(server[src], src + IRQ_BASE, priority[src]);
        }
    }

    private void resendMsi(int src) {
        if ((status[src] & ST_REJECTED) != 0) {
            setStatus(src, status[src] & ~ST_REJECTED);
            if (priority[src] != 0xff) {
                icpIrq(server[src], src + IRQ_BASE, priority[src]);
            }
        }
    }

    private void setStatus(int src, int v) {
        status[src] = v;
        boolean lsi = (flags[src] & FL_LSI) != 0;
        boolean candidate = lsi ? (v & (ST_ASSERTED | ST_SENT)) == ST_ASSERTED : (v & ST_REJECTED) != 0;
        resendable.set(src, candidate);
    }

    /** Resend every source that may need it, in source order (QEMU's ics_resend). */
    private void icsResend() {
        for (int i = resendable.nextSetBit(0); i >= 0; i = resendable.nextSetBit(i + 1)) {
            if ((flags[i] & FL_LSI) != 0) {
                resendLsi(i);
            } else {
                resendMsi(i);
            }
        }
    }

    private void icsReject(int irq) {
        if (!validIrq(irq)) {
            return;
        }
        int src = irq - IRQ_BASE;
        if ((flags[src] & FL_MSI) != 0) {
            setStatus(src, status[src] | ST_REJECTED);
        } else if ((flags[src] & FL_LSI) != 0) {
            setStatus(src, status[src] & ~ST_SENT);
        }
    }

    private void icsEoi(int irq) {
        int src = irq - IRQ_BASE;
        if ((flags[src] & FL_LSI) != 0) {
            setStatus(src, status[src] & ~ST_SENT);
        }
    }

    /** ibm,set-xive and friends. */
    private void writeXive(int src, int srv, int prio, int saved) {
        server[src] = srv;
        priority[src] = prio;
        savedPriority[src] = saved;
        if ((flags[src] & FL_LSI) != 0) {
            resendLsi(src);
        } else if ((status[src] & ST_MASKED_PENDING) != 0 && prio != 0xff) {
            setStatus(src, status[src] & ~ST_MASKED_PENDING);
            icpIrq(srv, src + IRQ_BASE, prio);
        }
    }

    public synchronized void setXive(int irq, int srv, int prio) {
        writeXive(irq - IRQ_BASE, srv, prio, prio);
    }

    /** Returns {server, priority}. */
    public synchronized int[] getXive(int irq) {
        int src = irq - IRQ_BASE;
        return new int[] {server[src], priority[src]};
    }

    public synchronized void intOff(int irq) {
        int src = irq - IRQ_BASE;
        writeXive(src, server[src], 0xff, priority[src]);
    }

    public synchronized void intOn(int irq) {
        int src = irq - IRQ_BASE;
        writeXive(src, server[src], savedPriority[src], savedPriority[src]);
    }

    // ---- presentation side -------------------------------------------------------------------

    private void icpIrq(int srv, int nr, int prio) {
        Icp icp = icps.get(srv);
        if (icp == null) {
            log.once("xics-invalid-server", String.format("invalid server %d for IRQ 0x%x", srv, nr));
            icsReject(nr);
            return;
        }
        if (prio >= icp.cppr() || (icp.xisr() != 0 && icp.pendingPriority <= prio)) {
            icsReject(nr);
        } else {
            if (icp.xisr() != 0 && icp.xisrFromIcs) {
                icsReject(icp.xisr());
                icp.xisrFromIcs = false;
            }
            icp.xirr = (icp.xirr & ~XISR_MASK) | (nr & XISR_MASK);
            icp.xisrFromIcs = true;
            icp.pendingPriority = prio;
            icp.raise();
        }
    }

    private void checkIpi(Icp icp) {
        if (icp.xisr() != 0 && icp.pendingPriority <= icp.mfrr) {
            return;
        }
        if (icp.xisr() != 0 && icp.xisrFromIcs) {
            icsReject(icp.xisr());
        }
        icp.xirr = (icp.xirr & ~XISR_MASK) | IPI;
        icp.pendingPriority = icp.mfrr;
        icp.xisrFromIcs = false;
        icp.raise();
    }

    private void resend(Icp icp) {
        if (icp.mfrr < icp.cppr()) {
            checkIpi(icp);
        }
        icsResend();
    }

    private Icp icp(int srv) {
        Icp icp = icps.get(srv);
        if (icp == null) {
            throw new IllegalArgumentException("no ICP for server " + srv);
        }
        return icp;
    }

    public synchronized void setCppr(int srv, int cppr) {
        Icp icp = icp(srv);
        int old = icp.cppr();
        icp.xirr = (icp.xirr & XISR_MASK) | ((cppr & 0xff) << 24);
        cppr &= 0xff;
        if (cppr < old) {
            if (icp.xisr() != 0 && cppr <= icp.pendingPriority) {
                int oldXisr = icp.xisr();
                icp.xirr &= ~XISR_MASK;
                icp.pendingPriority = 0xff;
                icp.lower();
                if (icp.xisrFromIcs) {
                    icsReject(oldXisr);
                    icp.xisrFromIcs = false;
                }
            }
        } else if (icp.xisr() == 0) {
            resend(icp);
        }
    }

    public synchronized void setMfrr(int srv, int mfrr) {
        Icp icp = icp(srv);
        icp.mfrr = mfrr & 0xff;
        if (icp.mfrr < icp.cppr()) {
            checkIpi(icp);
        }
    }

    /** H_XIRR: return XIRR and make the pending interrupt the current one. */
    public synchronized int accept(int srv) {
        Icp icp = icp(srv);
        int xirr = icp.xirr;
        icp.lower();
        icp.xirr = icp.pendingPriority << 24;
        icp.pendingPriority = 0xff;
        icp.xisrFromIcs = false;
        return xirr;
    }

    /** H_IPOLL: {xirr, mfrr} without side effects. */
    public synchronized int[] ipoll(int srv) {
        Icp icp = icp(srv);
        return new int[] {icp.xirr, icp.mfrr};
    }

    /** H_EOI: restore CPPR from the given XIRR and signal end-of-interrupt to the source. */
    public synchronized void eoi(int srv, int xirr) {
        Icp icp = icp(srv);
        icp.xirr = (icp.xirr & XISR_MASK) | (xirr & 0xff000000);
        int irq = xirr & XISR_MASK;
        if (validIrq(irq)) {
            icsEoi(irq);
        }
        if (icp.xisr() == 0) {
            resend(icp);
        }
    }

    // ---- saved state ----------------------------------------------------------------------------

    /** Write every source's routing and status and every presenter's registers. */
    synchronized void saveState(java.io.DataOutput out) throws java.io.IOException {
        int n = 0;
        for (int i = 0; i < NR_IRQS; i++) {
            if (!sourceIsReset(i)) {
                n++;
            }
        }
        out.writeInt(n);
        for (int i = 0; i < NR_IRQS; i++) {
            if (!sourceIsReset(i)) {
                out.writeShort(i);
                out.writeInt(flags[i]);
                out.writeInt(server[i]);
                out.writeByte(priority[i]);
                out.writeByte(savedPriority[i]);
                out.writeByte(status[i]);
            }
        }
        out.writeInt(icps.size());
        for (Icp icp : icps.values()) {
            out.writeInt(icp.server);
            out.writeInt(icp.xirr);
            out.writeByte(icp.pendingPriority);
            out.writeByte(icp.mfrr);
            out.writeBoolean(icp.xisrFromIcs);
            out.writeBoolean(icp.outputLevel);
        }
    }

    private boolean sourceIsReset(int i) {
        return flags[i] == 0 && server[i] == 0 && priority[i] == 0xff && savedPriority[i] == 0xff && status[i] == 0;
    }

    /**
     * Read the state written by {@link #saveState}. Sources must have been claimed the same way
     * (they are, for the same machine configuration). Every presenter's output line is driven to
     * its saved level.
     */
    synchronized void loadState(org.boxtheta.javapower.core.StateInput.Record in) throws java.io.IOException {
        int[] fl = new int[NR_IRQS];
        int[] srv = new int[NR_IRQS];
        int[] pr = new int[NR_IRQS];
        int[] sp = new int[NR_IRQS];
        int[] st = new int[NR_IRQS];
        java.util.Arrays.fill(pr, 0xff);
        java.util.Arrays.fill(sp, 0xff);
        int n = in.readInt("source count", 0, NR_IRQS);
        for (int k = 0; k < n; k++) {
            int i = in.readUnsignedShort();
            if (i >= NR_IRQS) {
                throw in.corrupt("interrupt source " + i);
            }
            fl[i] = in.readInt();
            srv[i] = in.readInt();
            pr[i] = in.readUnsignedByte();
            sp[i] = in.readUnsignedByte();
            st[i] = in.readUnsignedByte();
        }
        for (int i = 0; i < NR_IRQS; i++) {
            if (fl[i] != flags[i]) {
                throw in.corrupt(String.format("interrupt source 0x%x claimed differently", i + IRQ_BASE));
            }
        }
        int m = in.readInt();
        if (m != icps.size()) {
            throw in.corrupt(m + " presentation controllers, expected " + icps.size());
        }
        int[][] icp = new int[m][];
        boolean[][] icpFlags = new boolean[m][];
        for (int k = 0; k < m; k++) {
            icp[k] = new int[] {in.readInt(), in.readInt(), in.readUnsignedByte(), in.readUnsignedByte()};
            icpFlags[k] = new boolean[] {in.readBoolean(), in.readBoolean()};
            if (!icps.containsKey(icp[k][0])) {
                throw in.corrupt("presentation controller for server " + icp[k][0]);
            }
        }
        // Everything read and checked: apply.
        System.arraycopy(srv, 0, server, 0, NR_IRQS);
        System.arraycopy(pr, 0, priority, 0, NR_IRQS);
        System.arraycopy(sp, 0, savedPriority, 0, NR_IRQS);
        for (int i = 0; i < NR_IRQS; i++) {
            setStatus(i, st[i]);
        }
        for (int k = 0; k < m; k++) {
            Icp c = icps.get(icp[k][0]);
            c.xirr = icp[k][1];
            c.pendingPriority = icp[k][2];
            c.mfrr = icp[k][3];
            c.xisrFromIcs = icpFlags[k][0];
            if (icpFlags[k][1]) {
                c.raise();
            } else {
                c.lower();
            }
        }
    }

    /** Current XIRR of a server (tests, debugging). */
    public synchronized int xirr(int srv) {
        return icp(srv).xirr;
    }

    public synchronized boolean outputLevel(int srv) {
        return icp(srv).outputLevel;
    }
}
