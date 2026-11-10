package org.boxtheta.javapower.core;

/** SPR numbers (the decoded 10-bit value, i.e. after swapping the two 5-bit halves of the field). */
public final class Spr {
    private Spr() {}

    public static final int XER = 1;
    public static final int DSCR_U = 3;
    public static final int LR = 8;
    public static final int CTR = 9;
    public static final int AMR_U = 13;
    public static final int DSCR = 17;
    public static final int DSISR = 18;
    public static final int DAR = 19;
    public static final int DEC = 22;
    public static final int SRR0 = 26;
    public static final int SRR1 = 27;
    public static final int CFAR = 28;
    public static final int AMR = 29;
    public static final int PIDR = 48;
    public static final int IAMR = 61;
    public static final int TFHAR = 128;
    public static final int TFIAR = 129;
    public static final int TEXASR = 130;
    public static final int CTRL_R = 136;
    public static final int CTRL_W = 152;
    public static final int FSCR = 153;
    public static final int UAMOR = 157;
    public static final int PSPB = 159;
    public static final int DPDES = 176;
    public static final int DAWR0 = 180;
    public static final int CIABR = 187;
    public static final int DAWRX0 = 188;
    public static final int VRSAVE = 256;
    public static final int SPRG3_U = 259;
    public static final int TB = 268;
    public static final int TBU = 269;
    public static final int SPRG0 = 272;
    public static final int SPRG1 = 273;
    public static final int SPRG2 = 274;
    public static final int SPRG3 = 275;
    public static final int TBL_W = 284;
    public static final int TBU_W = 285;
    public static final int PVR = 287;
    public static final int HSPRG0 = 304;
    public static final int HSPRG1 = 305;
    public static final int PURR = 309;
    public static final int SPURR = 308;
    public static final int HDEC = 310;
    public static final int HSRR0 = 314;
    public static final int HSRR1 = 315;
    public static final int LPCR = 318;
    public static final int LPIDR = 319;
    public static final int PTCR = 464;
    public static final int MMCR2 = 785;
    public static final int MMCRA = 786;
    public static final int MMCR0 = 795;
    public static final int MMCR1 = 798;
    public static final int BESCR = 806;
    public static final int EBBHR = 804;
    public static final int EBBRR = 805;
    public static final int TAR = 815;
    public static final int IC = 848;
    public static final int VTB = 849;
    public static final int PPR = 896;
    public static final int PPR32 = 898;

    /** POWER9 DD2.2, as reported by the QEMU reference machine. */
    public static final long PVR_POWER9 = 0x004e1202L;

    // LPCR bits used by the PAPR interface.
    public static final long LPCR_ILE = 1L << 25;
    public static final long LPCR_AIL_MASK = 3L << 23;
    public static final int LPCR_AIL_SHIFT = 23;
    public static final long LPCR_UPRT = 1L << 22;
    public static final long LPCR_HR = 1L << 20;
    public static final long LPCR_GTSE = 1L << 10;
    public static final long LPCR_TC = 1L << 9;
    public static final long LPCR_LD = 1L << 17;
}
