package javapower.cpu;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.Arrays;

import javapower.core.CpuState;
import javapower.core.GuestMemory;
import javapower.core.Msr;
import javapower.core.PhysicalMemory;
import javapower.core.PpcException;
import javapower.core.Spr;

/**
 * Effective-address memory access for one CPU: real mode (MSR[IR]/MSR[DR]=0) and ISA 3.0 radix
 * translation for a guest running under a virtual hypervisor (flat partition scope: guest real
 * address == host physical address). Behaviour follows QEMU's mmu-radix64.c for a pseries guest.
 *
 * <p>The process table is located by {@link CpuState#procTable} (the PATE doubleword 1 registered
 * through H_REGISTER_PROC_TBL). Effective addresses in quadrant 0 use the PID in PIDR, quadrant 3
 * uses PID 0; quadrants 1 and 2, and addresses with EA bits 2:11 set, raise segment interrupts.
 * Page tables are big-endian in memory. As in QEMU, reference and change bits are never written
 * by the MMU: an access whose PTE lacks R (or C for a store) faults with DSISR[ATOMIC_RC].
 *
 * <p>Translations are cached in a software TLB of 4 KiB granules per (MSR[PR], quadrant 0/3) and
 * access type. Like QEMU's TLB, one successful walk fills the entries of every access type the
 * PTE permits (R/C are only checked for the access that caused the walk), entries are not
 * dropped when AMR/IAMR change, and tlbie/tlbiel/slbia invalidations take effect at the next
 * context-synchronising event ({@link #requestFlush}, {@link #syncFlush}). Flushes are O(1):
 * a per-context epoch is folded into every tag.
 *
 * <p>Accesses outside guest RAM raise a machine check with QEMU's POWER9 SRR1/DSISR codes.
 */
public final class RadixMmu implements GuestMemory {
    // Radix PTE / PDE fields (Java bit numbering of the 64-bit big-endian entry).
    static final long PTE_VALID = 0x8000000000000000L;
    static final long PTE_LEAF = 0x4000000000000000L;
    static final long PTE_RPN = 0x01FFFFFFFFFFF000L;
    static final long PTE_R = 0x100;
    static final long PTE_C = 0x80;
    static final long PTE_ATT = 0x30;
    static final long PTE_ATT_NI_IO = 0x20;
    static final long PTE_PRIV = 0x8;
    static final long PTE_EAA_R = 0x4;
    static final long PTE_EAA_RW = 0x2;
    static final long PTE_EAA_X = 0x1;
    static final long PDE_NLB = 0x0FFFFFFFFFFFFF00L;
    static final long PDE_NLS = 0x1F;
    static final long PATB_PRTB = 0x0FFFFFFFFFFFF000L;
    static final long PATB_PRTS = 0x1F;

    static final long SRR1_NOEXEC_GUARD = 0x10000000L;
    static final int DSISR_ATOMIC_RC = 0x00040000;

    /**
     * Core's {@link PpcException} has no factories for segment interrupts; the MMU raises a DSI
     * (or ISI) carrying this marker and the interpreter delivers it as DSEG (ISEG) instead.
     */
    static final int DSISR_SEGMENT_MARKER = 0xFFFFFFFF;
    static final long SRR1_SEGMENT_MARKER = -1L;

    static final int LOAD = 0;
    static final int STORE = 1;
    static final int EXEC = 2;

    // Machine check (access to unassigned real memory) codes, as QEMU's POWER9 model sets them.
    static final long MC_SRR1_LOAD = 1L << (63 - 42);
    static final long MC_SRR1_STORE = (1L << (63 - 36)) | (1L << (63 - 43)) | (1L << (63 - 45)) | (1L << (63 - 42));
    static final long MC_SRR1_FETCH = (1L << (63 - 36)) | (1L << (63 - 44)) | (1L << (63 - 45));
    static final long MC_DSISR_LOAD = 1L << (63 - 57);

    private static final int TLB_BITS = 10;
    private static final int TLB_SIZE = 1 << TLB_BITS;
    private static final int EPOCH_MASK = 0xfff;
    private static final long PAGE_MASK = ~0xfffL;

    private static final ValueLayout.OfLong L_BE =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.BIG_ENDIAN);

    private final CpuState s;
    private final PhysicalMemory pm;
    private final MemorySegment seg;
    private final long ramSize;

    /** Tags and addends, indexed by ((ctx * 3 + type) << TLB_BITS) | (vpn & mask). */
    private final long[] tag = new long[4 * 3 * TLB_SIZE];
    private final long[] addend = new long[4 * 3 * TLB_SIZE];
    /** Per-context epoch (1..4095) folded into the low 12 bits of every tag. */
    private final int[] epoch = {1, 1, 1, 1};

    /** A tlbie/tlbiel/slbia is waiting for a context-synchronising event. */
    private boolean flushPending;

    /** Access type of the last machine check raised (for SRR1/DSISR at delivery). */
    int mcType;

    /** Interpreter owning this MMU, if any; used by instruction handlers to reach timers etc. */
    Interpreter owner;

    /** Statistics, for tests and tuning. */
    long walks;

    public RadixMmu(CpuState s, PhysicalMemory pm) {
        this.s = s;
        this.pm = pm;
        this.seg = pm.segment();
        this.ramSize = pm.size();
    }

    public PhysicalMemory physical() {
        return pm;
    }

    // ------------------------------------------------------------------ invalidation

    /** Invalidate every cached translation now (process table change, PIDR change, reset). */
    public void flushAll() {
        flushPending = false;
        for (int c = 0; c < 4; c++) {
            bump(c);
        }
    }

    /** tlbie / tlbiel / slbia: invalidate at the next context-synchronising event. */
    public void requestFlush() {
        flushPending = true;
    }

    /** A context-synchronising event (isync, ptesync, rfid, interrupt): apply a pending flush. */
    public void syncFlush() {
        if (flushPending) {
            flushAll();
        }
    }

    private void bump(int ctx) {
        int e = epoch[ctx] + 1;
        if (e > EPOCH_MASK) {
            for (int t = 0; t < 3; t++) {
                Arrays.fill(tag, (ctx * 3 + t) << TLB_BITS, (ctx * 3 + t + 1) << TLB_BITS, 0L);
            }
            e = 1;
        }
        epoch[ctx] = e;
    }

    // ------------------------------------------------------------------ translation

    /**
     * Translate {@code ea} (already truncated to the computation mode) for an access of
     * {@code size} bytes that does not cross a 4 KiB boundary. Returns the real address.
     */
    private long xlate(long ea, int type, int size) {
        long m = s.msr;
        boolean reloc = type == EXEC ? (m & Msr.IR) != 0 : (m & Msr.DR) != 0;
        if (!reloc) {
            long pa = ea & 0x0FFFFFFFFFFFFFFFL;
            if (pa > ramSize - size) {
                throw machineCheck(ea, type);
            }
            return pa;
        }
        int ctx = (int) ((m >>> 13) & 2) | (int) (ea >>> 63);
        int slot = ((ctx * 3 + type) << TLB_BITS) | ((int) (ea >>> 12) & (TLB_SIZE - 1));
        if (tag[slot] == ((ea & PAGE_MASK) | epoch[ctx])) {
            return addend[slot] + ea;
        }
        return miss(ea, type, ctx);
    }

    private PpcException machineCheck(long ea, int type) {
        mcType = type;
        return PpcException.machineCheck(ea);
    }

    private long miss(long ea, int type, int ctx) {
        walks++;
        long q = ea >>> 62;
        if ((ea & 0x3FF0000000000000L) != 0 || q == 1 || q == 2) {
            throw type == EXEC
                    ? PpcException.isi(SRR1_SEGMENT_MARKER)
                    : PpcException.dsi(ea, DSISR_SEGMENT_MARKER);
        }
        boolean pr = (s.msr & Msr.PR) != 0;
        long pid = q == 3 ? 0 : (s.spr[Spr.PIDR] & 0xffffffffL);
        long pt = s.procTable;
        if (pt == 0) {
            throw fault(ea, type, PpcException.DSISR_NOPTE);
        }
        long prtb = pt & PATB_PRTB;
        long size = 1L << ((pt & PATB_PRTS) + 12);
        if ((prtb & (size - 1)) != 0) {
            throw fault(ea, type, PpcException.DSISR_BADCONFIG);
        }
        long off = pid * 16;
        if (off >= size) {
            throw fault(ea, type, PpcException.DSISR_NOPTE);
        }
        long prtbe0 = readTableEntry(prtb + off);
        if (prtbe0 == 0) {
            throw fault(ea, type, PpcException.DSISR_BADCONFIG);
        }
        int psize = (int) (((prtbe0 >>> 58) & 0x18) | ((prtbe0 >>> 5) & 7)) + 31;
        long base = prtbe0 & PDE_NLB;
        int nls = (int) (prtbe0 & PDE_NLS);
        long eaddr = ea & 0x3FFFFFFFFFFFFFFFL;

        // Walk the radix tree (misaligned table bases are silently aligned, as in QEMU).
        long pteAddr = (base & ~((1L << (nls + 3)) - 1)) + ((eaddr >>> (psize - nls)) & ((1L << nls) - 1)) * 8;
        long pte;
        int level = 0;
        while (true) {
            if (!validLevel(level++, psize, nls)) {
                throw fault(ea, type, PpcException.DSISR_BADCONFIG);
            }
            pte = readTableEntry(pteAddr);
            if ((pte & PTE_VALID) == 0) {
                throw fault(ea, type, PpcException.DSISR_NOPTE);
            }
            psize -= nls;
            if ((pte & PTE_LEAF) != 0) {
                break;
            }
            nls = (int) (pte & PDE_NLS);
            base = pte & PDE_NLB & ~((1L << (nls + 3)) - 1);
            pteAddr = base + ((eaddr >>> (psize - nls)) & ((1L << nls) - 1)) * 8;
        }
        long pageMask = (1L << psize) - 1;
        long raddr = ((pte & PTE_RPN) & ~pageMask) | (eaddr & pageMask);

        // Protection.
        if ((pte & PTE_ATT) == PTE_ATT_NI_IO && type == EXEC) {
            throw PpcException.isi(SRR1_NOEXEC_GUARD);
        }
        int prot;
        if ((pte & PTE_PRIV) != 0 && pr) {
            prot = 0;
        } else {
            prot = eaa(pte);
            if (!pr && (pte & PTE_PRIV) == 0) {
                prot &= amrProt();
            }
        }
        if ((prot & (1 << type)) == 0) {
            if (type == EXEC) {
                throw PpcException.isi(SRR1_NOEXEC_GUARD);
            }
            throw fault(ea, type, PpcException.DSISR_PROTFAULT);
        }

        // Reference / change bits: never updated here; the access that walked must find them set.
        long need = type == STORE ? (PTE_R | PTE_C) : PTE_R;
        if ((pte & need) != need) {
            throw fault(ea, type, DSISR_ATOMIC_RC);
        }

        long pagePa = raddr & PAGE_MASK;
        if (pagePa > ramSize - 4096) {
            throw machineCheck(ea, type);
        }
        // Fill every access type the PTE grants (one QEMU TLB entry covers them all).
        long tagv = (ea & PAGE_MASK) | epoch[ctx];
        long add = pagePa - (ea & PAGE_MASK);
        int idx = (int) (ea >>> 12) & (TLB_SIZE - 1);
        for (int t = 0; t < 3; t++) {
            if ((prot & (1 << t)) != 0) {
                int sl = ((ctx * 3 + t) << TLB_BITS) | idx;
                tag[sl] = tagv;
                addend[sl] = add;
            }
        }
        return raddr;
    }

    private long readTableEntry(long pa) {
        if (pa < 0 || pa > ramSize - 8) {
            return 0; // Unbacked table memory reads as invalid entries.
        }
        return seg.get(L_BE, pa);
    }

    /** Tree shapes POWER9 supports (QEMU ppc_radix64_is_valid_level). */
    private static boolean validLevel(int level, int psize, int nls) {
        return switch (level) {
            case 0 -> psize == 52 && nls == 13;
            case 1, 2 -> nls == 9;
            case 3 -> nls == 9 || nls == 5;
            default -> false;
        };
    }

    /** Permission bits indexed by access type: 1 = load, 2 = store, 4 = exec. */
    private static int eaa(long pte) {
        int p = 0;
        if ((pte & PTE_EAA_R) != 0) {
            p |= 1;
        }
        if ((pte & PTE_EAA_RW) != 0) {
            p |= 3;
        }
        if ((pte & PTE_EAA_X) != 0) {
            p |= 4;
        }
        return p;
    }

    /** Key-0 AMR/IAMR permissions; applied to supervisor accesses of non-privileged pages. */
    private int amrProt() {
        int amr = (int) (s.spr[Spr.AMR] >>> 62);
        int iamr = (int) (s.spr[Spr.IAMR] >>> 62);
        int p = 0;
        if ((amr & 2) == 0) {
            p |= 2;
        }
        if ((amr & 1) == 0) {
            p |= 1;
        }
        if ((iamr & 1) == 0) {
            p |= 4;
        }
        return p;
    }

    private static PpcException fault(long ea, int type, int cause) {
        return switch (type) {
            case EXEC -> PpcException.isi(cause & 0xffffffffL);
            case STORE -> PpcException.dsi(ea, cause | PpcException.DSISR_ISSTORE);
            default -> PpcException.dsi(ea, cause);
        };
    }

    // ------------------------------------------------------------------ instruction fetch

    /**
     * Translate an instruction address (already truncated to the computation mode) and return the
     * real address. The caller may cache the page mapping until the next MSR change or flush.
     */
    public long translateFetch(long ea) {
        return xlate(ea, EXEC, 4);
    }

    /** Fetch the instruction word at {@code ea}, in architectural order (honours MSR[LE]). */
    public int fetch(long ea) {
        long pa = xlate(ea, EXEC, 4);
        return (s.msr & Msr.LE) != 0 ? pm.read32le(pa) : pm.read32be(pa);
    }

    // ------------------------------------------------------------------ GuestMemory

    private long eaMode(long ea) {
        return s.msr < 0 ? ea : ea & 0xffffffffL;
    }

    @Override
    public long translateData(long ea, boolean store) {
        return xlate(eaMode(ea), store ? STORE : LOAD, 1);
    }

    @Override
    public int read8(long ea) {
        return pm.read8(xlate(eaMode(ea), LOAD, 1));
    }

    @Override
    public int read16(long ea) {
        ea = eaMode(ea);
        if ((ea & 0xfff) > 0xffe) {
            return (int) readCross(ea, 2);
        }
        long pa = xlate(ea, LOAD, 2);
        return (s.msr & Msr.LE) != 0 ? pm.read16le(pa) : pm.read16be(pa);
    }

    @Override
    public int read32(long ea) {
        ea = eaMode(ea);
        if ((ea & 0xfff) > 0xffc) {
            return (int) readCross(ea, 4);
        }
        long pa = xlate(ea, LOAD, 4);
        return (s.msr & Msr.LE) != 0 ? pm.read32le(pa) : pm.read32be(pa);
    }

    @Override
    public long read64(long ea) {
        ea = eaMode(ea);
        if ((ea & 0xfff) > 0xff8) {
            return readCross(ea, 8);
        }
        long pa = xlate(ea, LOAD, 8);
        return (s.msr & Msr.LE) != 0 ? pm.read64le(pa) : pm.read64be(pa);
    }

    @Override
    public void write8(long ea, int v) {
        pm.write8(xlate(eaMode(ea), STORE, 1), v);
    }

    @Override
    public void write16(long ea, int v) {
        ea = eaMode(ea);
        if ((ea & 0xfff) > 0xffe) {
            writeCross(ea, 2, v);
            return;
        }
        long pa = xlate(ea, STORE, 2);
        if ((s.msr & Msr.LE) != 0) {
            pm.write16le(pa, v);
        } else {
            pm.write16be(pa, v);
        }
    }

    @Override
    public void write32(long ea, int v) {
        ea = eaMode(ea);
        if ((ea & 0xfff) > 0xffc) {
            writeCross(ea, 4, v);
            return;
        }
        long pa = xlate(ea, STORE, 4);
        if ((s.msr & Msr.LE) != 0) {
            pm.write32le(pa, v);
        } else {
            pm.write32be(pa, v);
        }
    }

    @Override
    public void write64(long ea, long v) {
        ea = eaMode(ea);
        if ((ea & 0xfff) > 0xff8) {
            writeCross(ea, 8, v);
            return;
        }
        long pa = xlate(ea, STORE, 8);
        if ((s.msr & Msr.LE) != 0) {
            pm.write64le(pa, v);
        } else {
            pm.write64be(pa, v);
        }
    }

    /** Access straddling a 4 KiB boundary: translate both pages, then move bytes. */
    private long readCross(long ea, int size) {
        long mask = s.msr < 0 ? -1L : 0xffffffffL;
        long ea2 = ((ea | 0xfff) + 1) & mask;
        int n1 = (int) (0x1000 - (ea & 0xfff));
        long pa1 = xlate(ea, LOAD, n1);
        long pa2 = xlate(ea2, LOAD, size - n1);
        boolean le = (s.msr & Msr.LE) != 0;
        long v = 0;
        for (int k = 0; k < size; k++) {
            long b = k < n1 ? pm.read8(pa1 + k) : pm.read8(pa2 + k - n1);
            if (le) {
                v |= b << (8 * k);
            } else {
                v = (v << 8) | b;
            }
        }
        return v;
    }

    private void writeCross(long ea, int size, long v) {
        long mask = s.msr < 0 ? -1L : 0xffffffffL;
        long ea2 = ((ea | 0xfff) + 1) & mask;
        int n1 = (int) (0x1000 - (ea & 0xfff));
        long pa1 = xlate(ea, STORE, n1);
        long pa2 = xlate(ea2, STORE, size - n1);
        boolean le = (s.msr & Msr.LE) != 0;
        for (int k = 0; k < size; k++) {
            int b = (int) (le ? v >>> (8 * k) : v >>> (8 * (size - 1 - k))) & 0xff;
            if (k < n1) {
                pm.write8(pa1 + k, b);
            } else {
                pm.write8(pa2 + k - n1, b);
            }
        }
    }
}
