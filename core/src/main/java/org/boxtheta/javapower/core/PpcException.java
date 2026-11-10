package org.boxtheta.javapower.core;

/**
 * A synchronous guest interrupt raised while executing an instruction. Thrown by instruction
 * handlers and memory accesses; caught by the run loop, which delivers it (sets SRR0/SRR1, DAR,
 * DSISR and vectors). Handlers must throw BEFORE modifying architectural state so the instruction
 * can be restarted.
 *
 * <p>Stackless: cheap enough to use for guest page faults.
 */
public final class PpcException extends RuntimeException {
    public static final int MACHINE_CHECK = 0x200;
    public static final int DSI = 0x300;
    public static final int DSEG = 0x380;
    public static final int ISI = 0x400;
    public static final int ISEG = 0x480;
    public static final int EXTERNAL = 0x500;
    public static final int ALIGNMENT = 0x600;
    public static final int PROGRAM = 0x700;
    public static final int FP_UNAVAILABLE = 0x800;
    public static final int DECREMENTER = 0x900;
    public static final int SYSCALL = 0xc00;
    public static final int TRACE = 0xd00;
    public static final int VEC_UNAVAILABLE = 0xf20;
    public static final int VSX_UNAVAILABLE = 0xf40;
    public static final int FAC_UNAVAILABLE = 0xf60;
    public static final int SYSCALL_VECTORED = 0x3000;

    // SRR1 bits for program interrupts.
    public static final long SRR1_FP = 1L << 20;
    public static final long SRR1_ILLEGAL = 1L << 19;
    public static final long SRR1_PRIVILEGED = 1L << 18;
    public static final long SRR1_TRAP = 1L << 17;

    // DSISR / SRR1 bits for storage interrupts.
    public static final int DSISR_NOPTE = 0x40000000;
    public static final int DSISR_PROTFAULT = 0x08000000;
    public static final int DSISR_ISSTORE = 0x02000000;
    public static final int DSISR_BADCONFIG = 0x00080000;
    public static final int DSISR_NOEXEC_OR_G = 0x10000000;

    public final int vector;
    /** Bits to OR into SRR1 (program reason, ISI fault bits). */
    public final long srr1;
    public final long dar;
    public final int dsisr;
    /** Whether DAR/DSISR are meaningful for this interrupt. */
    public final boolean setsDar;
    /** For SYSCALL: the LEV field. For FAC_UNAVAILABLE: the FSCR interruption cause. */
    public final int aux;

    private PpcException(int vector, long srr1, long dar, int dsisr, boolean setsDar, int aux) {
        super(null, null, false, false);
        this.vector = vector;
        this.srr1 = srr1;
        this.dar = dar;
        this.dsisr = dsisr;
        this.setsDar = setsDar;
        this.aux = aux;
    }

    private static final PpcException ILLEGAL_I = new PpcException(PROGRAM, SRR1_ILLEGAL, 0, 0, false, 0);
    private static final PpcException PRIV_I = new PpcException(PROGRAM, SRR1_PRIVILEGED, 0, 0, false, 0);
    private static final PpcException TRAP_I = new PpcException(PROGRAM, SRR1_TRAP, 0, 0, false, 0);
    private static final PpcException FPEXC_I = new PpcException(PROGRAM, SRR1_FP, 0, 0, false, 0);
    private static final PpcException FPU_I = new PpcException(FP_UNAVAILABLE, 0, 0, 0, false, 0);
    private static final PpcException VEC_I = new PpcException(VEC_UNAVAILABLE, 0, 0, 0, false, 0);
    private static final PpcException VSX_I = new PpcException(VSX_UNAVAILABLE, 0, 0, 0, false, 0);

    public static PpcException illegal() { return ILLEGAL_I; }
    public static PpcException privileged() { return PRIV_I; }
    public static PpcException trap() { return TRAP_I; }
    /** Enabled floating-point exception (FPSCR[FEX] with MSR[FE0|FE1] != 0). */
    public static PpcException fpEnabled() { return FPEXC_I; }
    public static PpcException fpUnavailable() { return FPU_I; }
    public static PpcException vecUnavailable() { return VEC_I; }
    public static PpcException vsxUnavailable() { return VSX_I; }

    public static PpcException dsi(long dar, int dsisr) {
        return new PpcException(DSI, 0, dar, dsisr, true, 0);
    }

    public static PpcException isi(long srr1Bits) {
        return new PpcException(ISI, srr1Bits, 0, 0, false, 0);
    }

    public static PpcException alignment(long dar) {
        return new PpcException(ALIGNMENT, 0, dar, 0, true, 0);
    }

    public static PpcException syscall(int lev) {
        return new PpcException(SYSCALL, 0, 0, 0, false, lev);
    }

    public static PpcException syscallVectored(int lev) {
        return new PpcException(SYSCALL_VECTORED, 0, 0, 0, false, lev);
    }

    public static PpcException facilityUnavailable(int cause) {
        return new PpcException(FAC_UNAVAILABLE, 0, 0, 0, false, cause);
    }

    public static PpcException machineCheck(long dar) {
        return new PpcException(MACHINE_CHECK, 0, dar, 0, true, 0);
    }

    @Override
    public String getMessage() {
        return String.format("guest interrupt 0x%x srr1=%x dar=%x dsisr=%x", vector, srr1, dar, dsisr);
    }
}
