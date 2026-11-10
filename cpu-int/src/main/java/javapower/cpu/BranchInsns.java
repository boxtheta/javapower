package javapower.cpu;

import static javapower.cpu.Fields.ra;
import static javapower.cpu.Fields.rb;
import static javapower.cpu.Fields.rt;

import javapower.core.CpuState;
import javapower.core.DecodeTable;
import javapower.core.Msr;
import javapower.core.PpcException;
import javapower.core.Spr;

/** Branches, condition-register logical instructions and CR moves. */
final class BranchInsns {
    private BranchInsns() {}

    static void register(DecodeTable t) {
        t.addPrimary(18, "b", BranchInsns::b);
        t.addPrimary(16, "bc", BranchInsns::bc);
        t.addX(19, 16, "bclr", BranchInsns::bclr);
        t.addX(19, 528, "bcctr", BranchInsns::bcctr);
        t.addX(19, 560, "bctar", BranchInsns::bctar);

        t.addX(19, 257, "crand", BranchInsns::crand);
        t.addX(19, 129, "crandc", BranchInsns::crandc);
        t.addX(19, 289, "creqv", BranchInsns::creqv);
        t.addX(19, 225, "crnand", BranchInsns::crnand);
        t.addX(19, 33, "crnor", BranchInsns::crnor);
        t.addX(19, 449, "cror", BranchInsns::cror);
        t.addX(19, 417, "crorc", BranchInsns::crorc);
        t.addX(19, 193, "crxor", BranchInsns::crxor);
        t.addX(19, 0, "mcrf", BranchInsns::mcrf);
        // addpcis (DX-form, XO in ISA bits 26:30).
        t.add(0xfc00003e, (19 << 26) | (2 << 1), "addpcis", BranchInsns::addpcis);

        t.addX(31, 19, "mfcr", BranchInsns::mfcr);
        t.addX(31, 144, "mtcrf", BranchInsns::mtcrf);
    }

    private static long target(CpuState s, long t) {
        return s.msr < 0 ? t : t & 0xffffffffL;
    }

    private static void link(CpuState s, int i) {
        if ((i & 1) != 0) {
            s.lr = s.npc;
        }
    }

    // Taken branches record their address in CFAR (QEMU updates it with the branch history).

    static void b(CpuState s, int i) {
        long li = ((i << 6) >> 6) & ~3L;
        long t = (i & 2) != 0 ? li : s.pc + li;
        link(s, i);
        s.npc = target(s, t);
        s.spr[Spr.CFAR] = s.pc;
    }

    /** BO/BI evaluation; decrements CTR when BO_2 = 0. */
    private static boolean cond(CpuState s, int i) {
        int bo = rt(i);
        boolean ctrOk = true;
        if ((bo & 4) == 0) {
            s.ctr--;
            long c = s.msr < 0 ? s.ctr : s.ctr & 0xffffffffL;
            ctrOk = (c != 0) != ((bo & 2) != 0);
        }
        boolean condOk = true;
        if ((bo & 0x10) == 0) {
            int bit = (s.cr >>> (31 - ra(i))) & 1;
            condOk = bit == ((bo >>> 3) & 1);
        }
        return ctrOk && condOk;
    }

    static void bc(CpuState s, int i) {
        boolean taken = cond(s, i);
        link(s, i);
        if (taken) {
            long bd = (short) (i & 0xfffc);
            s.npc = target(s, (i & 2) != 0 ? bd : s.pc + bd);
            s.spr[Spr.CFAR] = s.pc;
        }
    }

    static void bclr(CpuState s, int i) {
        long t = s.lr;
        boolean taken = cond(s, i);
        link(s, i);
        if (taken) {
            s.npc = target(s, t) & ~3L;
            s.spr[Spr.CFAR] = s.pc;
        }
    }

    static void bcctr(CpuState s, int i) {
        int bo = rt(i);
        long t = s.ctr;
        boolean taken;
        if ((bo & 4) == 0) {
            // "Test and decrement" form used by Linux for a Spectre v2 workaround: test CTR first,
            // decrement only if that test passes.
            long c = s.msr < 0 ? s.ctr : s.ctr & 0xffffffffL;
            taken = (c != 0) == ((bo & 2) == 0);
            if (taken) {
                s.ctr--;
            }
        } else {
            taken = true;
        }
        if (taken && (bo & 0x10) == 0) {
            int bit = (s.cr >>> (31 - ra(i))) & 1;
            taken = bit == ((bo >>> 3) & 1);
        }
        link(s, i);
        if (taken) {
            s.npc = target(s, t) & ~3L;
            s.spr[Spr.CFAR] = s.pc;
        }
    }

    static void bctar(CpuState s, int i) {
        if ((s.msr & Msr.PR) != 0 && (s.spr[Spr.FSCR] & (1L << 8)) == 0) {
            throw PpcException.facilityUnavailable(8);
        }
        long t = s.spr[Spr.TAR];
        boolean taken = cond(s, i);
        link(s, i);
        if (taken) {
            s.npc = target(s, t) & ~3L;
            s.spr[Spr.CFAR] = s.pc;
        }
    }

    // ---------------------------------------------------------------- CR logical

    private static int bit(CpuState s, int n) {
        return (s.cr >>> (31 - n)) & 1;
    }

    private static void setBit(CpuState s, int n, int v) {
        int m = 1 << (31 - n);
        s.cr = (v & 1) != 0 ? s.cr | m : s.cr & ~m;
    }

    static void crand(CpuState s, int i) {
        setBit(s, rt(i), bit(s, ra(i)) & bit(s, rb(i)));
    }

    static void crandc(CpuState s, int i) {
        setBit(s, rt(i), bit(s, ra(i)) & ~bit(s, rb(i)));
    }

    static void creqv(CpuState s, int i) {
        setBit(s, rt(i), ~(bit(s, ra(i)) ^ bit(s, rb(i))));
    }

    static void crnand(CpuState s, int i) {
        setBit(s, rt(i), ~(bit(s, ra(i)) & bit(s, rb(i))));
    }

    static void crnor(CpuState s, int i) {
        setBit(s, rt(i), ~(bit(s, ra(i)) | bit(s, rb(i))));
    }

    static void cror(CpuState s, int i) {
        setBit(s, rt(i), bit(s, ra(i)) | bit(s, rb(i)));
    }

    static void crorc(CpuState s, int i) {
        setBit(s, rt(i), bit(s, ra(i)) | ~bit(s, rb(i)));
    }

    static void crxor(CpuState s, int i) {
        setBit(s, rt(i), bit(s, ra(i)) ^ bit(s, rb(i)));
    }

    static void mcrf(CpuState s, int i) {
        s.setCrField(Fields.bf(i), s.getCrField(Fields.bfa(i)));
    }

    static void addpcis(CpuState s, int i) {
        // d = d0 (ISA 16:25) || d1 (11:15) || d2 (31)
        int d0 = (i >>> 6) & 0x3ff;
        int d1 = (i >>> 16) & 0x1f;
        int d2 = i & 1;
        long d = (short) ((d0 << 6) | (d1 << 1) | d2);
        s.gpr[rt(i)] = s.npc + (d << 16);
    }

    static void mfcr(CpuState s, int i) {
        if ((i & 0x00100000) != 0) {
            int fxm = (i >>> 12) & 0xff;
            if (fxm != 0 && (fxm & (fxm - 1)) == 0) {
                int sh = Integer.numberOfTrailingZeros(fxm) * 4;
                s.gpr[rt(i)] = (long) (s.cr & (0xf << sh)) & 0xffffffffL;
            }
        } else {
            s.gpr[rt(i)] = s.cr & 0xffffffffL;
        }
    }

    static void mtcrf(CpuState s, int i) {
        int fxm = (i >>> 12) & 0xff;
        int v = (int) s.gpr[rt(i)];
        if ((i & 0x00100000) != 0) {
            if (fxm == 0 || (fxm & (fxm - 1)) != 0) {
                return;
            }
        }
        int mask = 0;
        for (int n = 0; n < 8; n++) {
            if ((fxm & (1 << n)) != 0) {
                mask |= 0xf << (4 * n);
            }
        }
        s.cr = (s.cr & ~mask) | (v & mask);
    }
}
