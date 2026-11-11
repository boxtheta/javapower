package org.boxtheta.javapower.core;

/**
 * Architectural state of one POWER9 hardware thread. Plain mutable fields, no behaviour beyond
 * packing/unpacking of split registers. Owned by exactly one executing thread; only the fields
 * marked volatile may be touched from other threads.
 *
 * <p>Bit numbering in comments follows the ISA (bit 0 = most significant of the 64-bit register).
 */
public final class CpuState {
    public final long[] gpr = new long[32];

    /** Address of the instruction being executed (CIA). */
    public long pc;
    /** Address of the next instruction (NIA). The run loop sets it to pc+4 before exec; branches overwrite it. */
    public long npc;

    public long lr;
    public long ctr;
    public long msr;

    /** Condition register; CR0 is bits 31..28 of this int, CR7 is bits 3..0. */
    public int cr;

    // XER, kept split. Each flag is 0 or 1.
    public int so, ov, ca, ov32, ca32;
    /** XER bits 57:63 (string byte count), 0..127. */
    public int xerBc;

    /**
     * VSX registers. VSR[n] = vsrHi[n] (doubleword 0, ISA bits 0:63) : vsrLo[n] (doubleword 1).
     * FPR[n] is vsrHi[n]. VR[n] (Altivec) is VSR[32+n].
     */
    public final long[] vsrHi = new long[64];
    public final long[] vsrLo = new long[64];
    /** FPSCR, 64-bit; ISA bit 32 (FX) is Java bit 31. */
    public long fpscr;
    /** VSCR low word: NJ is bit 16, SAT is bit 0 (Java numbering). */
    public int vscr;

    /**
     * Backing store for SPRs that need no special handling, indexed by SPR number (see {@link Spr}).
     * LR, CTR and XER are NOT stored here.
     */
    public final long[] spr = new long[1024];

    // Load/store reservation (larx/stcx).
    public boolean resValid;
    /** Real address of the reservation (start of the reserved granule). */
    public long resAddr;
    public int resLen;
    /** Value observed by the larx, for compare-and-swap style stcx emulation. */
    public long resVal;

    /** Effective-address memory access under the current MSR and translation. Never null once wired. */
    public GuestMemory mem;
    /** Handles {@code sc 1} from supervisor state (the PAPR hypervisor call interface). */
    public Hypervisor hv;

    /**
     * Process table descriptor registered by the guest via H_REGISTER_PROC_TBL (the PATE dw1 value:
     * table base | size). Zero until registered. Read by the radix MMU.
     */
    public long procTable;

    /**
     * External interrupt line from the interrupt presentation controller. Any thread may change it,
     * but only through {@link #setExternalIrq} so an idle CPU is woken.
     */
    public volatile boolean extIrq;
    /** The thread currently inside the run loop, or null. Written by the run loop only. */
    public volatile Thread runner;
    /** Set by H_CEDE / H_CONFER; the run loop idles until an interrupt is pending, then clears it. */
    public boolean ceded;
    /**
     * Index of this hardware thread within the machine (0 for the boot CPU). Also its PAPR
     * interrupt server number and device tree {@code reg}. Set once, before the CPU runs.
     */
    public int index;
    /**
     * True while this CPU is in the RTAS "stopped" state: a secondary that has not been started
     * with {@code start-cpu} yet, or one that called {@code stop-self}. The run loop executes no
     * instructions and takes no interrupts while it is set; it parks until another thread clears
     * it and calls {@link #wake}. Whoever clears it (the platform's {@code start-cpu}) first
     * writes the entry state (pc, r3, MSR and friends) into this object; the volatile write is
     * what publishes those plain fields to the CPU's own thread.
     */
    public volatile boolean halted;
    /** Asks the run loop to return to its caller at the next instruction boundary. */
    public volatile boolean stopRequested;
    /**
     * Set by another CPU's H_PROD (any thread). Ends this CPU's H_CEDE / H_CONFER / wait idle: the
     * run loop's idle wait must also return when it is set. The hypervisor consumes it (the next
     * H_CEDE returns at once, like QEMU's per-vCPU {@code prod} flag) and clears it when the CPU
     * resumes executing ({@link Hypervisor#cpuExecEnter}).
     */
    public volatile boolean prodded;
    /**
     * True while work posted with {@link #runOnCpu} may be waiting. The run loop polls it at
     * instruction-chunk boundaries and while idle or halted, and then calls {@link #runPostedWork}.
     */
    public volatile boolean workPending;
    private final java.util.concurrent.ConcurrentLinkedQueue<java.util.function.Consumer<CpuState>> work =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Instructions retired since reset; the basis for virtual time. */
    public long icount;

    /** Drive the external interrupt line; wakes the CPU if it is idle in H_CEDE. */
    public void setExternalIrq(boolean level) {
        extIrq = level;
        if (level) {
            wake();
        }
    }

    /**
     * Wake the run loop if it is parked (idle or waiting for a timer). The run loop parks with
     * {@link java.util.concurrent.locks.LockSupport#parkNanos} and re-checks its conditions on return.
     */
    public void wake() {
        Thread t = runner;
        if (t != null) {
            java.util.concurrent.locks.LockSupport.unpark(t);
        }
    }

    /**
     * Ask this CPU's own thread to run {@code w} at its next instruction boundary (QEMU's
     * {@code async_run_on_cpu}): how another thread changes this CPU's plain fields (LPCR, PCR,
     * {@code procTable}, a system reset interrupt) without racing the run loop. Safe from any
     * thread; returns at once and wakes the CPU if it is parked.
     */
    public void runOnCpu(java.util.function.Consumer<CpuState> w) {
        work.add(w);
        workPending = true;
        wake();
    }

    /** Run the work posted with {@link #runOnCpu}, in order. Call only on this CPU's own thread. */
    public void runPostedWork() {
        workPending = false;
        java.util.function.Consumer<CpuState> w;
        while ((w = work.poll()) != null) {
            w.accept(this);
        }
    }

    /**
     * Write the architectural state to a saved machine state: registers, SPRs, the reservation,
     * {@code procTable}, the idle and RTAS-stopped flags ({@code ceded}, {@code halted},
     * {@code prodded}) and {@code icount}. Not written: {@code extIrq} (the interrupt controller's
     * output, restored with it), {@code mem}, {@code hv}, {@code runner} and
     * {@code stopRequested}, which belong to the host side. Work posted with {@link #runOnCpu}
     * cannot be saved: run it first.
     *
     * @throws IllegalStateException if posted work is pending
     */
    public void saveState(java.io.DataOutput out) throws java.io.IOException {
        if (workPending || !work.isEmpty()) {
            throw new IllegalStateException("CPU " + index + " has posted work pending; run it before saving");
        }
        out.writeInt(index);
        for (long v : gpr) {
            out.writeLong(v);
        }
        out.writeLong(pc);
        out.writeLong(npc);
        out.writeLong(lr);
        out.writeLong(ctr);
        out.writeLong(msr);
        out.writeInt(cr);
        out.writeLong(getXer());
        for (int i = 0; i < 64; i++) {
            out.writeLong(vsrHi[i]);
            out.writeLong(vsrLo[i]);
        }
        out.writeLong(fpscr);
        out.writeInt(vscr);
        int n = 0;
        for (long v : spr) {
            if (v != 0) {
                n++;
            }
        }
        out.writeShort(n);
        for (int i = 0; i < spr.length; i++) {
            if (spr[i] != 0) {
                out.writeShort(i);
                out.writeLong(spr[i]);
            }
        }
        out.writeBoolean(resValid);
        out.writeLong(resAddr);
        out.writeInt(resLen);
        out.writeLong(resVal);
        out.writeLong(procTable);
        out.writeBoolean(ceded);
        out.writeBoolean(halted);
        out.writeBoolean(prodded);
        out.writeLong(icount);
    }

    /**
     * Read the state written by {@link #saveState} into this object (see there for what it
     * covers). Call only while no thread runs this CPU.
     *
     * @throws StateFormatException if the input is corrupt or was saved for another CPU index
     */
    public void loadState(StateInput.Record in) throws java.io.IOException {
        int idx = in.readInt();
        if (idx != index) {
            throw in.corrupt("state of CPU " + idx + " where CPU " + index + " was expected");
        }
        for (int i = 0; i < 32; i++) {
            gpr[i] = in.readLong();
        }
        pc = in.readLong();
        npc = in.readLong();
        lr = in.readLong();
        ctr = in.readLong();
        msr = in.readLong();
        cr = in.readInt();
        setXer(in.readLong());
        for (int i = 0; i < 64; i++) {
            vsrHi[i] = in.readLong();
            vsrLo[i] = in.readLong();
        }
        fpscr = in.readLong();
        vscr = in.readInt();
        int n = in.readUnsignedShort();
        if (n > spr.length) {
            throw in.corrupt(n + " SPRs");
        }
        java.util.Arrays.fill(spr, 0);
        for (int k = 0; k < n; k++) {
            int i = in.readUnsignedShort();
            if (i >= spr.length) {
                throw in.corrupt("SPR " + i);
            }
            spr[i] = in.readLong();
        }
        resValid = in.readBoolean();
        resAddr = in.readLong();
        resLen = in.readInt();
        resVal = in.readLong();
        procTable = in.readLong();
        ceded = in.readBoolean();
        halted = in.readBoolean();
        prodded = in.readBoolean();
        icount = in.readLong();
    }

    public long getXer() {
        return ((long) so << 31) | ((long) ov << 30) | ((long) ca << 29)
                | ((long) ov32 << 19) | ((long) ca32 << 18) | (xerBc & 0x7f);
    }

    public void setXer(long v) {
        so = (int) (v >>> 31) & 1;
        ov = (int) (v >>> 30) & 1;
        ca = (int) (v >>> 29) & 1;
        ov32 = (int) (v >>> 19) & 1;
        ca32 = (int) (v >>> 18) & 1;
        xerBc = (int) v & 0x7f;
    }

    /** Replace CR field {@code bf} (0..7) with the 4-bit value {@code v} (LT=8, GT=4, EQ=2, SO=1). */
    public void setCrField(int bf, int v) {
        int sh = (7 - bf) * 4;
        cr = (cr & ~(0xf << sh)) | ((v & 0xf) << sh);
    }

    public int getCrField(int bf) {
        return (cr >>> ((7 - bf) * 4)) & 0xf;
    }

    /** Set CR0 from a result interpreted as signed in the current computation mode, plus XER[SO]. */
    public void setCr0(long result) {
        if ((msr & Msr.SF) == 0) {
            result = (int) result;
        }
        setCrField(0, (result < 0 ? 8 : result > 0 ? 4 : 2) | so);
    }

    public boolean is64() {
        return (msr & Msr.SF) != 0;
    }

    public boolean littleEndian() {
        return (msr & Msr.LE) != 0;
    }
}
