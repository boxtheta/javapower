package org.boxtheta.javapower.cpu;

import static org.boxtheta.javapower.cpu.Fields.rt;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PpcException;
import org.boxtheta.javapower.core.Spr;

/**
 * mfspr / mtspr / mftb with the access rules of a POWER9 guest (MSR[HV]=0) as QEMU's pseries
 * model implements them: hypervisor-only SPRs raise a privileged-instruction program interrupt,
 * problem-state access to supervisor SPRs likewise, and unimplemented SPR numbers are no-ops in
 * supervisor state (except SPRs 0, 4, 5, 6 on read and 0 on write) and privileged in problem state.
 */
final class SprAccess {
    private SprAccess() {}

    static final int CTRL_R = Spr.CTRL_R;
    static final int CTRL_W = Spr.CTRL_W;
    static final int AMOR = 349;
    static final int TIDR = 144;
    static final int PSSCR = 855;
    static final int SIER = 784;
    static final int MMCR0 = Spr.MMCR0;

    /** Access class of an SPR for one direction. */
    private static final byte UNKNOWN = 0;
    private static final byte NOACCESS = 1;
    private static final byte OK = 2;

    // Indexed by SPR number: user read, user write, supervisor read, supervisor write.
    private static final byte[] UR = new byte[1024];
    private static final byte[] UW = new byte[1024];
    private static final byte[] SR = new byte[1024];
    private static final byte[] SW = new byte[1024];

    private static void def(int spr, byte ur, byte uw, byte sr, byte sw) {
        UR[spr] = ur;
        UW[spr] = uw;
        SR[spr] = sr;
        SW[spr] = sw;
    }

    private static void sup(int... sprs) {
        for (int n : sprs) {
            def(n, NOACCESS, NOACCESS, OK, OK);
        }
    }

    private static void supRead(int... sprs) {
        for (int n : sprs) {
            def(n, NOACCESS, NOACCESS, OK, NOACCESS);
        }
    }

    private static void hvOnly(int... sprs) {
        for (int n : sprs) {
            def(n, NOACCESS, NOACCESS, NOACCESS, NOACCESS);
        }
    }

    private static void both(int... sprs) {
        for (int n : sprs) {
            def(n, OK, OK, OK, OK);
        }
    }

    private static void bothRead(int... sprs) {
        for (int n : sprs) {
            def(n, OK, NOACCESS, OK, NOACCESS);
        }
    }

    static {
        both(Spr.XER, Spr.LR, Spr.CTR, Spr.AMR_U, Spr.VRSAVE, Spr.PPR, Spr.PPR32, Spr.TAR,
                128, 129, 130, 131, 800, 801, 802, 803, 804, 805, 806);
        bothRead(CTRL_R, Spr.SPRG3_U, Spr.TB, Spr.TBU, Spr.PURR, Spr.SPURR);
        sup(Spr.DSCR, Spr.DSISR, Spr.DAR, Spr.DEC, Spr.SRR0, Spr.SRR1, Spr.CFAR, Spr.AMR, Spr.PIDR,
                Spr.IAMR, TIDR, Spr.FSCR, Spr.UAMOR, Spr.PSPB, Spr.SPRG0, Spr.SPRG1, Spr.SPRG2,
                Spr.SPRG3, 282 /* EAR */, 784, 785, 786, 787, 788, 789, 790, 791, 792, 795, 796, 797,
                798, 885, 888, 889, 890, 892, 893, 894, 895);
        supRead(Spr.DPDES, Spr.PVR, 446 /* TIR */, Spr.IC, Spr.VTB, 1023 /* PIR */);
        def(CTRL_W, NOACCESS, NOACCESS, NOACCESS, OK);
        def(881, NOACCESS, NOACCESS, OK, OK); // TRIG1: read is a no-op
        def(882, NOACCESS, NOACCESS, OK, OK); // TRIG2
        // User-visible PMU aliases (read the supervisor register at n + 16).
        def(768, OK, NOACCESS, OK, OK); // USIER
        def(769, OK, OK, OK, OK); // UMMCR2
        def(770, OK, NOACCESS, OK, OK); // UMMCRA
        for (int n = 771; n <= 776; n++) {
            def(n, OK, OK, OK, OK); // UPMC1-6
        }
        def(779, OK, OK, OK, OK); // UMMCR0
        def(780, OK, NOACCESS, OK, OK); // USIAR
        def(781, OK, NOACCESS, OK, OK); // USDAR
        def(782, OK, NOACCESS, OK, OK); // UMMCR1
        hvOnly(Spr.DAWR0, 186, Spr.CIABR, Spr.DAWRX0, 190, 276, 277, Spr.TBL_W, Spr.TBU_W, 286,
                Spr.HSPRG0, Spr.HSPRG1, 306, 307, Spr.HDEC, 313, Spr.HSRR0, Spr.HSRR1, 316, 317,
                Spr.LPCR, Spr.LPIDR, 336, 337, 338, 339, AMOR, Spr.PTCR, 816, 850, 851, 853, 884, 921,
                922, 1008);
    }

    private static PpcException privileged() {
        return PpcException.privileged();
    }

    /** MMCR0[PMCC] (ISA bits 44:45). */
    private static int pmcc(CpuState s) {
        return (int) (s.spr[MMCR0] >>> 18) & 3;
    }

    /** "Group A" problem-state PMU registers: UMMCR0, UMMCR2, UPMC1-6. */
    private static boolean groupA(int n) {
        return n == 769 || n == 779 || (n >= 771 && n <= 776);
    }

    /** Problem-state read of a PMU register (QEMU power8-pmu-regs rules). */
    private static void checkPmuUserRead(CpuState s, int n) {
        int pmcc = pmcc(s);
        if (groupA(n) && pmcc == 0b01) {
            throw PpcException.facilityUnavailable(SystemInsns.IC_PMU);
        }
        if ((n == 775 || n == 776) && pmcc == 0b11) {
            throw PpcException.facilityUnavailable(SystemInsns.IC_PMU);
        }
    }

    /** Problem-state write of a group A PMU register. */
    private static void checkPmuUserWrite(CpuState s, int n) {
        int pmcc = pmcc(s);
        if ((pmcc & 0b10) != 0) {
            if ((n == 775 || n == 776) && pmcc == 0b11) {
                throw PpcException.facilityUnavailable(SystemInsns.IC_PMU);
            }
            return;
        }
        if (pmcc == 0b01) {
            throw PpcException.facilityUnavailable(SystemInsns.IC_PMU);
        }
        throw privileged();
    }

    /** MMCR0 bits visible to problem state: FC, PMAE, PMAO. */
    private static final long MMCR0_UREG_MASK = (1L << 31) | (1L << 26) | (1L << 7);

    private static void checkFscr(CpuState s, int bit, int cause) {
        if ((s.spr[Spr.FSCR] & (1L << bit)) == 0) {
            throw PpcException.facilityUnavailable(cause);
        }
    }

    private static void checkTm(CpuState s) {
        if ((s.msr & (1L << 32)) == 0) {
            throw PpcException.facilityUnavailable(SystemInsns.IC_TM);
        }
    }

    /** Generic rule for an unimplemented SPR (or one without a handler for this mode). */
    private static boolean unknownIsNop(int n, boolean pr, boolean read) {
        if ((n & 0x10) != 0) {
            if (pr) {
                throw privileged();
            }
            return true;
        }
        if (pr || n == 0 || (read && (n == 4 || n == 5 || n == 6))) {
            throw privileged();
        }
        return true;
    }

    static void mfspr(CpuState s, int i) {
        int n = Fields.spr(i);
        boolean pr = (s.msr & Msr.PR) != 0;
        byte acc = pr ? UR[n] : SR[n];
        if (acc != OK) {
            if (n >= 808 && n <= 811) {
                return;
            }
            if (acc == NOACCESS) {
                throw privileged();
            }
            unknownIsNop(n, pr, true);
            return;
        }
        long v;
        switch (n) {
            case Spr.XER -> v = s.getXer();
            case Spr.LR -> v = s.lr;
            case Spr.CTR -> v = s.ctr;
            // QEMU quirk: SPR 13 reads its own (never written) slot; writes go to AMR.
            case Spr.AMR_U -> v = s.spr[Spr.AMR_U];
            case CTRL_R -> v = s.spr[CTRL_W];
            case Spr.SPRG3_U -> v = s.spr[Spr.SPRG3];
            case Spr.TB, Spr.PURR, Spr.SPURR, Spr.VTB -> v = Interpreter.of(s).tb();
            case Spr.TBU -> v = Interpreter.of(s).tb() >>> 32;
            case Spr.DEC -> v = Interpreter.of(s).loadDec();
            case Spr.PPR32 -> v = s.spr[Spr.PPR] >>> 32;
            case 128, 129, 130 -> {
                checkTm(s);
                v = s.spr[n];
            }
            case 131 -> {
                checkTm(s);
                v = s.spr[Spr.TEXASR] >>> 32;
            }
            case Spr.TAR -> {
                if (pr) {
                    checkFscr(s, SystemInsns.FSCR_TAR, SystemInsns.IC_TAR);
                }
                v = s.spr[n];
            }
            case 800, 801, 802, 803, 804, 805, 806 -> {
                if (pr) {
                    checkFscr(s, SystemInsns.FSCR_EBB, SystemInsns.IC_EBB);
                }
                // As in QEMU, BESCRS (800) and BESCRR (802) are plain registers and the "U" forms
                // (801, 803) access their upper halves.
                v = (n == 801 || n == 803) ? s.spr[n - 1] >>> 32 : s.spr[n];
            }
            case 768, 769, 770, 771, 772, 773, 774, 775, 776, 779, 780, 781, 782 -> {
                v = s.spr[n + 16];
                if (pr) {
                    checkPmuUserRead(s, n);
                    if (n == 779) {
                        v &= MMCR0_UREG_MASK;
                    }
                }
            }
            case 881, 882 -> {
                return; // TRIG registers: read is a no-op
            }
            default -> v = s.spr[n];
        }
        s.gpr[rt(i)] = v;
    }

    static void mtspr(CpuState s, int i) {
        int n = Fields.spr(i);
        boolean pr = (s.msr & Msr.PR) != 0;
        byte acc = pr ? UW[n] : SW[n];
        if (acc != OK) {
            if (n >= 808 && n <= 811) {
                return;
            }
            if (acc == NOACCESS) {
                throw privileged();
            }
            unknownIsNop(n, pr, false);
            return;
        }
        long v = s.gpr[rt(i)];
        switch (n) {
            case Spr.XER -> s.setXer(v);
            case Spr.LR -> s.lr = v;
            case Spr.CTR -> s.ctr = v;
            case Spr.AMR_U, Spr.AMR -> {
                long mask = pr ? s.spr[Spr.UAMOR] : -1L; // AMOR is all ones under PAPR
                // Like QEMU, cached translations keep the rights they were filled with.
                s.spr[Spr.AMR] = (s.spr[Spr.AMR] & ~mask) | (v & mask);
            }
            case Spr.IAMR -> s.spr[Spr.IAMR] = v;
            case Spr.DEC -> Interpreter.of(s).storeDec(v);
            case Spr.PIDR -> {
                long nv = v & 0xffffffffL;
                if (nv != s.spr[Spr.PIDR]) {
                    s.spr[Spr.PIDR] = nv;
                    Interpreter in = Interpreter.of(s);
                    in.mmu.flushProcess(); // quadrant 3 does not use PIDR
                    in.recheck = true;
                }
            }
            case CTRL_W -> {
                long run = v & 1;
                s.spr[CTRL_W] = run | (run << 8);
            }
            case Spr.DSISR, Spr.PSPB, Spr.VRSAVE -> s.spr[n] = v & 0xffffffffL;
            case Spr.PPR32 -> s.spr[Spr.PPR] = (s.spr[Spr.PPR] & 0xffffffffL) | (v << 32);
            case 128, 129, 130 -> {
                checkTm(s);
                s.spr[n] = v;
            }
            case 131 -> {
                checkTm(s);
                s.spr[Spr.TEXASR] = (s.spr[Spr.TEXASR] & 0xffffffffL) | (v << 32);
            }
            case Spr.TAR -> {
                if (pr) {
                    checkFscr(s, SystemInsns.FSCR_TAR, SystemInsns.IC_TAR);
                }
                s.spr[n] = v;
            }
            case 800, 801, 802, 803, 804, 805, 806 -> {
                if (pr) {
                    checkFscr(s, SystemInsns.FSCR_EBB, SystemInsns.IC_EBB);
                }
                if (n == 801 || n == 803) {
                    s.spr[n - 1] = (s.spr[n - 1] & 0xffffffffL) | (v << 32);
                } else {
                    s.spr[n] = v;
                }
            }
            case 768, 769, 770, 771, 772, 773, 774, 775, 776, 779, 780, 781, 782 -> {
                if (pr) {
                    checkPmuUserWrite(s, n);
                    if (n == 779) {
                        v = (s.spr[MMCR0] & ~MMCR0_UREG_MASK) | (v & MMCR0_UREG_MASK);
                    }
                }
                s.spr[n + 16] = v;
            }
            default -> s.spr[n] = v;
        }
    }
}
