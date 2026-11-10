package javapower.core;

/** MSR bit masks (Java numbering: ISA bit n is {@code 1L << (63 - n)}). */
public final class Msr {
    private Msr() {}

    public static final long SF = 1L << 63;
    public static final long HV = 1L << 60;
    public static final long VEC = 1L << 25;
    public static final long VSX = 1L << 23;
    public static final long EE = 1L << 15;
    public static final long PR = 1L << 14;
    public static final long FP = 1L << 13;
    public static final long ME = 1L << 12;
    public static final long FE0 = 1L << 11;
    public static final long FE1 = 1L << 8;
    public static final long IR = 1L << 5;
    public static final long DR = 1L << 4;
    public static final long PMM = 1L << 2;
    public static final long RI = 1L << 1;
    public static final long LE = 1L;
}
