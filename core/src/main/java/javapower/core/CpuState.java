package javapower.core;

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

    /** External interrupt line from the interrupt presentation controller. Set/cleared by any thread. */
    public volatile boolean extIrq;
    /** Set by H_CEDE / H_CONFER; the run loop idles until an interrupt is pending, then clears it. */
    public boolean ceded;
    /** Asks the run loop to return to its caller at the next instruction boundary. */
    public volatile boolean stopRequested;

    /** Instructions retired since reset; the basis for virtual time. */
    public long icount;

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
