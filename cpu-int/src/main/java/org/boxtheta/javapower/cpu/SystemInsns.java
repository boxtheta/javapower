package org.boxtheta.javapower.cpu;

import static org.boxtheta.javapower.cpu.Fields.raOr0;
import static org.boxtheta.javapower.cpu.Fields.rb;
import static org.boxtheta.javapower.cpu.Fields.rt;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PpcException;
import org.boxtheta.javapower.core.Spr;

/**
 * System instructions: system calls, interrupt returns, MSR/SPR moves, cache and TLB management,
 * power management, and the transactional-memory stubs. Behaviour follows QEMU for a pseries guest
 * under a virtual hypervisor (MSR[HV] always 0: hypervisor-only instructions are privileged).
 */
final class SystemInsns {
    private SystemInsns() {}

    /** FSCR facility-enable bits (Java numbering) and interruption causes. */
    static final int FSCR_TAR = 8;
    static final int FSCR_EBB = 7;
    static final int FSCR_SCV = 12;
    static final int IC_PMU = 3;
    static final int IC_BHRB = 4;
    static final int IC_TM = 5;
    static final int IC_EBB = 7;
    static final int IC_TAR = 8;
    static final int IC_SCV = 12;

    static void register(DecodeTable t) {
        // Reserved-field masks ("inval") are QEMU's: encodings with those bits set are illegal.
        Fields.add(t, 0xfc000002, (17 << 26) | 2, 0x03FFF01D, "sc", SystemInsns::sc);
        Fields.add(t, 0xfc000003, (17 << 26) | 1, 0x03FFF01C, "scv", SystemInsns::scv);
        Fields.addX(t, 19, 18, 0x03FF8001, "rfid", SystemInsns::rfid);
        Fields.addX(t, 19, 274, 0x03FF8001, "hrfid", SystemInsns::privileged);
        Fields.addX(t, 19, 82, 0x03FF8001, "rfscv", SystemInsns::rfscv);
        t.addX(19, 146, "rfebb", SystemInsns::rfebb);
        Fields.addX(t, 19, 150, 0x03FFF801, "isync", SystemInsns::isync);
        // Power-saving instructions are hypervisor-privileged (QEMU CHK_HV).
        Fields.addX(t, 19, 370, 0x03FFF801, "stop", SystemInsns::privileged);
        // POWER8 power-saving instructions do not exist on POWER9.
        t.addX(19, 402, "doze", Fields.INVALID);
        t.addX(19, 434, "nap", Fields.INVALID);
        t.addX(19, 466, "sleep", Fields.INVALID);
        t.addX(19, 498, "rvwinkle", Fields.INVALID);

        Fields.addX(t, 31, 83, 0x001FF801, "mfmsr", SystemInsns::mfmsr);
        Fields.addX(t, 31, 146, 0x001EF801, "mtmsr", SystemInsns::mtmsr);
        Fields.addX(t, 31, 178, 0x001EF801, "mtmsrd", SystemInsns::mtmsrd);
        Fields.addX(t, 31, 339, 0x00000001, "mfspr", SprAccess::mfspr);
        t.addX(31, 467, "mtspr", SprAccess::mtspr);
        Fields.addX(t, 31, 371, 0x00000001, "mftb", SprAccess::mfspr);

        // Storage control. dcbf/dcbst/icbi are translated like loads (QEMU), so they can fault.
        t.addX(31, 598, "sync", SystemInsns::sync);
        t.addX(31, 854, "eieio", SystemInsns::nop);
        Fields.addX(t, 31, 1014, 0x03C00001, "dcbz", SystemInsns::dcbz);
        Fields.addX(t, 31, 278, 0x00000001, "dcbt", SystemInsns::nop);
        Fields.addX(t, 31, 246, 0x00000001, "dcbtst", SystemInsns::nop);
        // Altivec data-stream hints: no-ops, as in QEMU. Linux issues dssall on every mm switch.
        Fields.addX(t, 31, 342, 0x00000001, "dst", SystemInsns::nop);
        Fields.addX(t, 31, 374, 0x00000001, "dstst", SystemInsns::nop);
        Fields.addX(t, 31, 822, 0x00000001, "dss", SystemInsns::nop);
        Fields.addX(t, 31, 86, 0x03C00001, "dcbf", SystemInsns::dcbfst);
        Fields.addX(t, 31, 54, 0x03E00001, "dcbst", SystemInsns::dcbfst);
        Fields.addX(t, 31, 982, 0x03E00001, "icbi", SystemInsns::icbi);
        Fields.addX(t, 31, 470, 0x03E00001, "dcbi", SystemInsns::supervisorNop);
        // Not provided by POWER9 under QEMU.
        t.addX(31, 22, "icbt", Fields.INVALID);
        t.addX(31, 758, "dcba", Fields.INVALID);
        Fields.addX(t, 31, 30, 0x039CF801, "wait", SystemInsns::waitInsn);

        // TLB / SLB management.
        t.addX(31, 306, "tlbie", SystemInsns::tlbie);
        t.addX(31, 274, "tlbiel", SystemInsns::tlbiel);
        Fields.addX(t, 31, 566, 0x03FFF801, "tlbsync", SystemInsns::tlbsync);
        t.addX(31, 370, "tlbia", Fields.INVALID);
        t.addX(31, 498, "slbia", SystemInsns::slbia);
        t.addX(31, 434, "slbie", SystemInsns::supervisorNop);
        t.addX(31, 466, "slbieg", SystemInsns::supervisorNop);
        t.addX(31, 850, "slbiag", SystemInsns::supervisorNop);
        t.addX(31, 402, "slbmte", SystemInsns::supervisorNop);
        t.addX(31, 338, "slbsync", SystemInsns::supervisorNop);
        t.addX(31, 851, "slbmfev", SystemInsns::supervisorZero);
        t.addX(31, 915, "slbmfee", SystemInsns::supervisorZero);
        t.addX(31, 979, "slbfee.", SystemInsns::slbfee);

        // Doorbells.
        t.addX(31, 142, "msgsndp", SystemInsns::supervisorNop);
        t.addX(31, 174, "msgclrp", SystemInsns::supervisorNop);
        t.addX(31, 206, "msgsnd", SystemInsns::privileged);
        t.addX(31, 238, "msgclr", SystemInsns::privileged);
        t.addX(31, 886, "msgsync", SystemInsns::privileged);

        // BHRB.
        t.addX(31, 430, "clrbhrb", SystemInsns::clrbhrb);
        t.addX(31, 302, "mfbhrbe", SystemInsns::mfbhrbe);

        // Transactional memory (facility not provided: unavailable unless MSR[TM]).
        Fields.addX(t, 31, 654, 0x01DFF800, "tbegin.", SystemInsns::tbegin);
        Fields.addX(t, 31, 686, 0x01FFF800, "tend.", SystemInsns::tmNop);
        Fields.addX(t, 31, 910, 0x03E0F800, "tabort.", SystemInsns::tmNop);
        t.addX(31, 782, "tabortwc.", SystemInsns::tmNop);
        t.addX(31, 846, "tabortwci.", SystemInsns::tmNop);
        t.addX(31, 814, "tabortdc.", SystemInsns::tmNop);
        t.addX(31, 878, "tabortdci.", SystemInsns::tmNop);
        Fields.addX(t, 31, 750, 0x03DFF800, "tsr.", SystemInsns::tmNop);
        Fields.addX(t, 31, 718, 0x007FF800, "tcheck", SystemInsns::tcheck);
        Fields.addX(t, 31, 942, 0x03E0F800, "treclaim.", SystemInsns::tmPrivNop);
        Fields.addX(t, 31, 1006, 0x03FFF800, "trechkpt.", SystemInsns::tmPrivNop);

        // Copy-paste facility: copy/paste are not implemented by QEMU (illegal); cp_abort is a no-op.
        Fields.addX(t, 31, 838, 0x03FFF801, "cp_abort", SystemInsns::nop);
    }

    static void nop(CpuState s, int i) {}

    /** isync is context synchronising: a pending TLB invalidation takes effect. */
    static void isync(CpuState s, int i) {
        Interpreter.of(s).mmu.syncFlush();
    }

    /** sync; ptesync (L=2) in supervisor state completes pending TLB invalidations (QEMU). */
    static void sync(CpuState s, int i) {
        if (((i >>> 21) & 3) == 2 && (s.msr & Msr.PR) == 0) {
            Interpreter.of(s).mmu.syncFlush();
        }
    }

    static void privileged(CpuState s, int i) {
        throw PpcException.privileged();
    }

    private static void checkSupervisor(CpuState s) {
        if ((s.msr & Msr.PR) != 0) {
            throw PpcException.privileged();
        }
    }

    static void supervisorNop(CpuState s, int i) {
        checkSupervisor(s);
    }

    static void supervisorZero(CpuState s, int i) {
        checkSupervisor(s);
        s.gpr[rt(i)] = 0;
    }

    static void slbfee(CpuState s, int i) {
        if ((s.msr & Msr.PR) != 0) {
            throw PpcException.illegal(); // QEMU: invalid, not privileged
        }
        s.gpr[rt(i)] = 0;
        s.setCrField(0, s.so);
    }

    /** MMCR0[BHRBA] (ISA bit 42): problem state may use the BHRB instructions. */
    private static void checkBhrb(CpuState s) {
        if ((s.msr & Msr.PR) != 0 && (s.spr[Spr.MMCR0] & (1L << 21)) == 0) {
            throw PpcException.facilityUnavailable(IC_BHRB);
        }
    }

    static void clrbhrb(CpuState s, int i) {
        checkBhrb(s);
    }

    /** The branch history buffer is not recorded: entries read as zero. */
    static void mfbhrbe(CpuState s, int i) {
        checkBhrb(s);
        s.gpr[rt(i)] = 0;
    }

    // ---------------------------------------------------------------- system call / return

    /** sc: like QEMU, only the low bit of LEV is decoded (odd LEV is a hypervisor call). */
    static void sc(CpuState s, int i) {
        if ((i & 0x20) != 0) {
            Interpreter.of(s).hcall();
            return;
        }
        throw PpcException.syscall(0);
    }

    static void scv(CpuState s, int i) {
        if ((s.spr[Spr.FSCR] & (1L << FSCR_SCV)) == 0) {
            throw PpcException.facilityUnavailable(IC_SCV);
        }
        throw PpcException.syscallVectored((i >>> 5) & 0x7f);
    }

    static void rfid(CpuState s, int i) {
        checkSupervisor(s);
        s.spr[Spr.CFAR] = s.pc;
        Interpreter.of(s).returnFromInterrupt(s.spr[Spr.SRR0], s.spr[Spr.SRR1]);
    }

    static void rfscv(CpuState s, int i) {
        checkSupervisor(s);
        s.spr[Spr.CFAR] = s.pc;
        Interpreter.of(s).returnFromInterrupt(s.lr, s.ctr);
    }

    /**
     * rfebb as QEMU implements it: no facility check; BESCR bits 32:33 set make it invalid;
     * branches to EBBRR (not word-aligned) and sets BESCR[GE] from S.
     */
    static void rfebb(CpuState s, int i) {
        if ((s.spr[Spr.BESCR] & 0xC0000000L) != 0) {
            throw PpcException.illegal();
        }
        long t = s.spr[Spr.EBBRR];
        s.npc = s.msr < 0 ? t : t & 0xffffffffL;
        long ge = (i >>> 11) & 1;
        s.spr[Spr.BESCR] = (s.spr[Spr.BESCR] & ~(1L << 63)) | (ge << 63);
        s.spr[Spr.CFAR] = s.pc;
    }

    // ---------------------------------------------------------------- MSR

    static void mfmsr(CpuState s, int i) {
        checkSupervisor(s);
        s.gpr[rt(i)] = s.msr;
    }

    /** L field of mtmsr/mtmsrd: ISA bit 15. */
    private static boolean lField(int i) {
        return (i & 0x00010000) != 0;
    }

    static void mtmsrd(CpuState s, int i) {
        checkSupervisor(s);
        long mask = lField(i) ? (Msr.RI | Msr.EE) : ~(Msr.LE | Msr.ME | (1L << 22) /* S */ | Msr.HV);
        long v = (s.gpr[rt(i)] & mask) | (s.msr & ~mask);
        Interpreter.of(s).storeMsr(v);
    }

    static void mtmsr(CpuState s, int i) {
        checkSupervisor(s);
        long mask = lField(i) ? (Msr.RI | Msr.EE) : (0xffffffffL & ~(Msr.LE | Msr.ME | (1L << 22)));
        long v = (s.gpr[rt(i)] & mask) | (s.msr & ~mask);
        Interpreter.of(s).storeMsr(v);
    }

    // ---------------------------------------------------------------- storage control

    /** dcbf / dcbst: a one-byte load translation (QEMU), no other effect. */
    static void dcbfst(CpuState s, int i) {
        s.mem.read8(Fields.ea(s, raOr0(s, i) + s.gpr[rb(i)]));
    }

    /** icbi: a word load of the start of the 128-byte block (QEMU), no other effect. */
    static void icbi(CpuState s, int i) {
        s.mem.read32(Fields.ea(s, raOr0(s, i) + s.gpr[rb(i)]) & ~127L);
    }

    static void slbia(CpuState s, int i) {
        checkSupervisor(s);
        flushTlb(s);
    }

    static void dcbz(CpuState s, int i) {
        long ea = Fields.ea(s, raOr0(s, i) + s.gpr[rb(i)]) & ~127L;
        for (int k = 0; k < 128; k += 8) {
            s.mem.write64(ea + k, 0);
        }
    }

    static void waitInsn(CpuState s, int i) {
        int wc = (i >>> 21) & 3;
        if (wc != 0) {
            throw PpcException.illegal();
        }
        Interpreter in = Interpreter.of(s);
        in.waiting = true;
        in.recheck = true;
    }

    static void tlbie(CpuState s, int i) {
        tlbInvalidate(s, i, false);
    }

    static void tlbiel(CpuState s, int i) {
        tlbInvalidate(s, i, true);
    }

    /**
     * tlbie / tlbiel with QEMU's rules for a guest (MSR[HV]=0): privileged in problem state, and
     * also when PRS=0 with LPCR[HR]=1 or (tlbie only) LPCR[GTSE]=0; invalid RIC/PRS/IS/AP
     * combinations are invalid forms. Any valid form flushes the whole software TLB.
     */
    private static void tlbInvalidate(CpuState s, int i, boolean local) {
        checkSupervisor(s);
        long lpcr = s.spr[Spr.LPCR];
        boolean prs = (i & (1 << 17)) != 0;
        boolean hr = (lpcr & Spr.LPCR_HR) != 0;
        if ((!prs && hr) || (!local && (lpcr & Spr.LPCR_GTSE) == 0)) {
            throw PpcException.privileged();
        }
        int ric = (i >>> 18) & 3;
        boolean r = (i & (1 << 16)) != 0;
        long rb = s.gpr[rb(i)];
        // QEMU checks the ISA 3.0 forms only with R=1 (and, for tlbie, in 64-bit mode).
        if (hr && r && (local || s.msr < 0)) {
            int is = (int) (rb >>> 10) & 3; // RB bits 52:53
            if (ric == 3 || ((ric == 1 || ric == 2) && is == 0) || (!prs && is == 1)) {
                throw PpcException.illegal();
            }
            if (ric == 1) { // page walk cache only: nothing cached
                if (local && ((rb >>> 12) & 0xfff) != 0) { // set number, RB bits 40:51
                    throw PpcException.illegal();
                }
                return;
            }
            if (is == 0) {
                int ap = (int) (rb >>> 5) & 7; // RB bits 56:58
                if (ap != 0 && ap != 5 && ap != 1 && ap != 2) {
                    throw PpcException.illegal();
                }
            }
        }
        flushTlb(s);
    }

    static void tlbsync(CpuState s, int i) {
        checkSupervisor(s);
        if ((s.spr[Spr.LPCR] & Spr.LPCR_GTSE) == 0) {
            throw PpcException.privileged();
        }
    }

    /** TLB invalidation is deferred to the next context-synchronising event, as in QEMU. */
    private static void flushTlb(CpuState s) {
        Interpreter.of(s).mmu.requestFlush();
    }

    // ---------------------------------------------------------------- transactional memory

    private static void checkTm(CpuState s) {
        if ((s.msr & (1L << 32)) == 0) {
            throw PpcException.facilityUnavailable(IC_TM);
        }
    }

    static void tbegin(CpuState s, int i) {
        checkTm(s);
        // Degenerate implementation (as QEMU): always fail, "persistent" and "nesting overflow".
        long pr = (s.msr & Msr.PR) != 0 ? 1 : 0;
        s.spr[Spr.TEXASR] = (1L << (63 - 7)) | (1L << (63 - 9)) | (pr << (63 - 35)) | (1L << (63 - 36))
                | (1L << (63 - 37));
        s.spr[Spr.TFIAR] = s.pc | pr;
        s.spr[Spr.TFHAR] = s.pc + 4;
        s.setCrField(0, 0xB);
    }

    static void tmNop(CpuState s, int i) {
        checkTm(s);
        s.setCrField(0, 0);
    }

    static void tcheck(CpuState s, int i) {
        checkTm(s);
        s.setCrField(Fields.bf(i), 0x8);
    }

    static void tmPrivNop(CpuState s, int i) {
        checkSupervisor(s);
        checkTm(s);
        s.setCrField(0, 0);
    }
}
