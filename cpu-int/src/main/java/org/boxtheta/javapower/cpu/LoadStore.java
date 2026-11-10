package org.boxtheta.javapower.cpu;

import static org.boxtheta.javapower.cpu.Fields.ds;
import static org.boxtheta.javapower.cpu.Fields.ra;
import static org.boxtheta.javapower.cpu.Fields.raOr0;
import static org.boxtheta.javapower.cpu.Fields.rb;
import static org.boxtheta.javapower.cpu.Fields.rt;
import static org.boxtheta.javapower.cpu.Fields.si;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.GuestMemory;
import org.boxtheta.javapower.core.Msr;
import org.boxtheta.javapower.core.PpcException;
import org.boxtheta.javapower.core.Spr;

/** Integer loads and stores, load-reserve / store-conditional, string and multiple forms. */
final class LoadStore {
    private LoadStore() {}

    static void register(DecodeTable t) {
        // D-form.
        t.addPrimary(34, "lbz", LoadStore::lbz);
        t.addPrimary(35, "lbzu", LoadStore::lbzu);
        t.addPrimary(40, "lhz", LoadStore::lhz);
        t.addPrimary(41, "lhzu", LoadStore::lhzu);
        t.addPrimary(42, "lha", LoadStore::lha);
        t.addPrimary(43, "lhau", LoadStore::lhau);
        t.addPrimary(32, "lwz", LoadStore::lwz);
        t.addPrimary(33, "lwzu", LoadStore::lwzu);
        t.addPrimary(38, "stb", LoadStore::stb);
        t.addPrimary(39, "stbu", LoadStore::stbu);
        t.addPrimary(44, "sth", LoadStore::sth);
        t.addPrimary(45, "sthu", LoadStore::sthu);
        t.addPrimary(36, "stw", LoadStore::stw);
        t.addPrimary(37, "stwu", LoadStore::stwu);
        t.addPrimary(46, "lmw", LoadStore::lmw);
        t.addPrimary(47, "stmw", LoadStore::stmw);
        // DS-form.
        t.add(0xfc000003, (58 << 26), "ld", LoadStore::ld);
        t.add(0xfc000003, (58 << 26) | 1, "ldu", LoadStore::ldu);
        t.add(0xfc000003, (58 << 26) | 2, "lwa", LoadStore::lwa);
        t.add(0xfc000003, (62 << 26), "std", LoadStore::std);
        t.add(0xfc000003, (62 << 26) | 1, "stdu", LoadStore::stdu);
        t.add(0xfc000003, (62 << 26) | 2, "stq", LoadStore::stq);
        // DQ-form lq (ISA bits 28:31 reserved).
        t.add(0xfc000000, (56 << 26), "lq", LoadStore::lq);

        // X-form indexed.
        t.addX(31, 87, "lbzx", LoadStore::lbzx);
        t.addX(31, 119, "lbzux", LoadStore::lbzux);
        t.addX(31, 279, "lhzx", LoadStore::lhzx);
        t.addX(31, 311, "lhzux", LoadStore::lhzux);
        t.addX(31, 343, "lhax", LoadStore::lhax);
        t.addX(31, 375, "lhaux", LoadStore::lhaux);
        t.addX(31, 23, "lwzx", LoadStore::lwzx);
        t.addX(31, 55, "lwzux", LoadStore::lwzux);
        t.addX(31, 341, "lwax", LoadStore::lwax);
        t.addX(31, 373, "lwaux", LoadStore::lwaux);
        t.addX(31, 21, "ldx", LoadStore::ldx);
        t.addX(31, 53, "ldux", LoadStore::ldux);
        t.addX(31, 215, "stbx", LoadStore::stbx);
        t.addX(31, 247, "stbux", LoadStore::stbux);
        t.addX(31, 407, "sthx", LoadStore::sthx);
        t.addX(31, 439, "sthux", LoadStore::sthux);
        t.addX(31, 151, "stwx", LoadStore::stwx);
        t.addX(31, 183, "stwux", LoadStore::stwux);
        t.addX(31, 149, "stdx", LoadStore::stdx);
        t.addX(31, 181, "stdux", LoadStore::stdux);
        t.addX(31, 790, "lhbrx", LoadStore::lhbrx);
        t.addX(31, 534, "lwbrx", LoadStore::lwbrx);
        t.addX(31, 532, "ldbrx", LoadStore::ldbrx);
        t.addX(31, 918, "sthbrx", LoadStore::sthbrx);
        t.addX(31, 662, "stwbrx", LoadStore::stwbrx);
        t.addX(31, 660, "stdbrx", LoadStore::stdbrx);

        // Load-reserve / store-conditional.
        t.addX(31, 52, "lbarx", LoadStore::lbarx);
        t.addX(31, 116, "lharx", LoadStore::lharx);
        t.addX(31, 20, "lwarx", LoadStore::lwarx);
        t.addX(31, 84, "ldarx", LoadStore::ldarx);
        t.addX(31, 276, "lqarx", LoadStore::lqarx);
        t.addX(31, 694, "stbcx.", LoadStore::stbcx);
        t.addX(31, 726, "sthcx.", LoadStore::sthcx);
        t.addX(31, 150, "stwcx.", LoadStore::stwcx);
        t.addX(31, 214, "stdcx.", LoadStore::stdcx);
        t.addX(31, 182, "stqcx.", LoadStore::stqcx);

        // Strings.
        t.addX(31, 597, "lswi", LoadStore::lswi);
        t.addX(31, 533, "lswx", LoadStore::lswx);
        t.addX(31, 725, "stswi", LoadStore::stswi);
        t.addX(31, 661, "stswx", LoadStore::stswx);

        // Atomic memory operations (ISA 3.0).
        t.addX(31, 582, "lwat", LoadStore::lwat);
        t.addX(31, 614, "ldat", LoadStore::ldat);
        t.addX(31, 710, "stwat", LoadStore::stwat);
        t.addX(31, 742, "stdat", LoadStore::stdat);

        // Cache-inhibited forms are hypervisor-only.
        for (int xo : new int[] {853, 821, 789, 885, 981, 949, 917, 1013}) {
            t.addX(31, xo, "ldst-ci", LoadStore::hvOnly);
        }
    }

    static void hvOnly(CpuState s, int i) {
        throw PpcException.privileged();
    }

    // ---------------------------------------------------------------- EA helpers

    private static long eaD(CpuState s, int i) {
        return Fields.ea(s, raOr0(s, i) + si(i));
    }

    private static long eaDu(CpuState s, int i) {
        return Fields.ea(s, s.gpr[ra(i)] + si(i));
    }

    private static long eaDs(CpuState s, int i) {
        return Fields.ea(s, raOr0(s, i) + ds(i));
    }

    private static long eaDsU(CpuState s, int i) {
        return Fields.ea(s, s.gpr[ra(i)] + ds(i));
    }

    private static long eaX(CpuState s, int i) {
        return Fields.ea(s, raOr0(s, i) + s.gpr[rb(i)]);
    }

    private static long eaXu(CpuState s, int i) {
        return Fields.ea(s, s.gpr[ra(i)] + s.gpr[rb(i)]);
    }

    /** Update forms with RA=0 (or RA=RT for loads) are invalid forms. */
    private static void checkUpdateLoad(int i) {
        int ra = ra(i);
        if (ra == 0 || ra == rt(i)) {
            throw PpcException.illegal();
        }
    }

    private static void checkUpdateStore(int i) {
        if (ra(i) == 0) {
            throw PpcException.illegal();
        }
    }

    // ---------------------------------------------------------------- D-form loads

    static void lbz(CpuState s, int i) {
        s.gpr[rt(i)] = s.mem.read8(eaD(s, i));
    }

    static void lbzu(CpuState s, int i) {
        checkUpdateLoad(i);
        long ea = eaDu(s, i);
        s.gpr[rt(i)] = s.mem.read8(ea);
        s.gpr[ra(i)] = ea;
    }

    static void lhz(CpuState s, int i) {
        s.gpr[rt(i)] = s.mem.read16(eaD(s, i));
    }

    static void lhzu(CpuState s, int i) {
        checkUpdateLoad(i);
        long ea = eaDu(s, i);
        s.gpr[rt(i)] = s.mem.read16(ea);
        s.gpr[ra(i)] = ea;
    }

    static void lha(CpuState s, int i) {
        s.gpr[rt(i)] = (short) s.mem.read16(eaD(s, i));
    }

    static void lhau(CpuState s, int i) {
        checkUpdateLoad(i);
        long ea = eaDu(s, i);
        s.gpr[rt(i)] = (short) s.mem.read16(ea);
        s.gpr[ra(i)] = ea;
    }

    static void lwz(CpuState s, int i) {
        s.gpr[rt(i)] = s.mem.read32(eaD(s, i)) & 0xffffffffL;
    }

    static void lwzu(CpuState s, int i) {
        checkUpdateLoad(i);
        long ea = eaDu(s, i);
        s.gpr[rt(i)] = s.mem.read32(ea) & 0xffffffffL;
        s.gpr[ra(i)] = ea;
    }

    static void ld(CpuState s, int i) {
        s.gpr[rt(i)] = s.mem.read64(eaDs(s, i));
    }

    static void ldu(CpuState s, int i) {
        checkUpdateLoad(i);
        long ea = eaDsU(s, i);
        s.gpr[rt(i)] = s.mem.read64(ea);
        s.gpr[ra(i)] = ea;
    }

    static void lwa(CpuState s, int i) {
        s.gpr[rt(i)] = s.mem.read32(eaDs(s, i));
    }

    // ---------------------------------------------------------------- D-form stores

    static void stb(CpuState s, int i) {
        s.mem.write8(eaD(s, i), (int) s.gpr[rt(i)]);
    }

    static void stbu(CpuState s, int i) {
        checkUpdateStore(i);
        long ea = eaDu(s, i);
        s.mem.write8(ea, (int) s.gpr[rt(i)]);
        s.gpr[ra(i)] = ea;
    }

    static void sth(CpuState s, int i) {
        s.mem.write16(eaD(s, i), (int) s.gpr[rt(i)]);
    }

    static void sthu(CpuState s, int i) {
        checkUpdateStore(i);
        long ea = eaDu(s, i);
        s.mem.write16(ea, (int) s.gpr[rt(i)]);
        s.gpr[ra(i)] = ea;
    }

    static void stw(CpuState s, int i) {
        s.mem.write32(eaD(s, i), (int) s.gpr[rt(i)]);
    }

    static void stwu(CpuState s, int i) {
        checkUpdateStore(i);
        long ea = eaDu(s, i);
        s.mem.write32(ea, (int) s.gpr[rt(i)]);
        s.gpr[ra(i)] = ea;
    }

    static void std(CpuState s, int i) {
        s.mem.write64(eaDs(s, i), s.gpr[rt(i)]);
    }

    static void stdu(CpuState s, int i) {
        checkUpdateStore(i);
        long ea = eaDsU(s, i);
        s.mem.write64(ea, s.gpr[rt(i)]);
        s.gpr[ra(i)] = ea;
    }

    // ---------------------------------------------------------------- quadword

    static void lq(CpuState s, int i) {
        int rtp = rt(i);
        if ((rtp & 1) != 0 || rtp == ra(i)) {
            throw PpcException.illegal();
        }
        long ea = Fields.ea(s, raOr0(s, i) + (short) (i & 0xfff0));
        loadQuad(s, ea, rtp);
    }

    static void stq(CpuState s, int i) {
        int rsp = rt(i);
        if ((rsp & 1) != 0) {
            throw PpcException.illegal();
        }
        long ea = eaDs(s, i);
        storeQuad(s, ea, rsp);
    }

    /** Quadword load; QEMU (one CPU) does not require 16-byte alignment for lq. */
    private static void loadQuad(CpuState s, long ea, int rtp) {
        long ea2 = Fields.ea(s, ea + 8);
        long hi;
        long lo;
        if (s.littleEndian()) {
            lo = s.mem.read64(ea);
            hi = s.mem.read64(ea2);
        } else {
            hi = s.mem.read64(ea);
            lo = s.mem.read64(ea2);
        }
        s.gpr[rtp] = hi;
        s.gpr[rtp + 1] = lo;
    }

    private static void storeQuad(CpuState s, long ea, int rsp) {
        long ea2 = Fields.ea(s, ea + 8);
        // Translate both halves first so a fault leaves storage unchanged.
        probe(s, ea, 16, true);
        if (s.littleEndian()) {
            s.mem.write64(ea, s.gpr[rsp + 1]);
            s.mem.write64(ea2, s.gpr[rsp]);
        } else {
            s.mem.write64(ea, s.gpr[rsp]);
            s.mem.write64(ea2, s.gpr[rsp + 1]);
        }
    }

    // ---------------------------------------------------------------- X-form

    static void lbzx(CpuState s, int i) {
        s.gpr[rt(i)] = s.mem.read8(eaX(s, i));
    }

    static void lbzux(CpuState s, int i) {
        checkUpdateLoad(i);
        long ea = eaXu(s, i);
        s.gpr[rt(i)] = s.mem.read8(ea);
        s.gpr[ra(i)] = ea;
    }

    static void lhzx(CpuState s, int i) {
        s.gpr[rt(i)] = s.mem.read16(eaX(s, i));
    }

    static void lhzux(CpuState s, int i) {
        checkUpdateLoad(i);
        long ea = eaXu(s, i);
        s.gpr[rt(i)] = s.mem.read16(ea);
        s.gpr[ra(i)] = ea;
    }

    static void lhax(CpuState s, int i) {
        s.gpr[rt(i)] = (short) s.mem.read16(eaX(s, i));
    }

    static void lhaux(CpuState s, int i) {
        checkUpdateLoad(i);
        long ea = eaXu(s, i);
        s.gpr[rt(i)] = (short) s.mem.read16(ea);
        s.gpr[ra(i)] = ea;
    }

    static void lwzx(CpuState s, int i) {
        s.gpr[rt(i)] = s.mem.read32(eaX(s, i)) & 0xffffffffL;
    }

    static void lwzux(CpuState s, int i) {
        checkUpdateLoad(i);
        long ea = eaXu(s, i);
        s.gpr[rt(i)] = s.mem.read32(ea) & 0xffffffffL;
        s.gpr[ra(i)] = ea;
    }

    static void lwax(CpuState s, int i) {
        s.gpr[rt(i)] = s.mem.read32(eaX(s, i));
    }

    static void lwaux(CpuState s, int i) {
        checkUpdateLoad(i);
        long ea = eaXu(s, i);
        s.gpr[rt(i)] = s.mem.read32(ea);
        s.gpr[ra(i)] = ea;
    }

    static void ldx(CpuState s, int i) {
        s.gpr[rt(i)] = s.mem.read64(eaX(s, i));
    }

    static void ldux(CpuState s, int i) {
        checkUpdateLoad(i);
        long ea = eaXu(s, i);
        s.gpr[rt(i)] = s.mem.read64(ea);
        s.gpr[ra(i)] = ea;
    }

    static void stbx(CpuState s, int i) {
        s.mem.write8(eaX(s, i), (int) s.gpr[rt(i)]);
    }

    static void stbux(CpuState s, int i) {
        checkUpdateStore(i);
        long ea = eaXu(s, i);
        s.mem.write8(ea, (int) s.gpr[rt(i)]);
        s.gpr[ra(i)] = ea;
    }

    static void sthx(CpuState s, int i) {
        s.mem.write16(eaX(s, i), (int) s.gpr[rt(i)]);
    }

    static void sthux(CpuState s, int i) {
        checkUpdateStore(i);
        long ea = eaXu(s, i);
        s.mem.write16(ea, (int) s.gpr[rt(i)]);
        s.gpr[ra(i)] = ea;
    }

    static void stwx(CpuState s, int i) {
        s.mem.write32(eaX(s, i), (int) s.gpr[rt(i)]);
    }

    static void stwux(CpuState s, int i) {
        checkUpdateStore(i);
        long ea = eaXu(s, i);
        s.mem.write32(ea, (int) s.gpr[rt(i)]);
        s.gpr[ra(i)] = ea;
    }

    static void stdx(CpuState s, int i) {
        s.mem.write64(eaX(s, i), s.gpr[rt(i)]);
    }

    static void stdux(CpuState s, int i) {
        checkUpdateStore(i);
        long ea = eaXu(s, i);
        s.mem.write64(ea, s.gpr[rt(i)]);
        s.gpr[ra(i)] = ea;
    }

    static void lhbrx(CpuState s, int i) {
        s.gpr[rt(i)] = Integer.reverseBytes(s.mem.read16(eaX(s, i))) >>> 16;
    }

    static void lwbrx(CpuState s, int i) {
        s.gpr[rt(i)] = Integer.reverseBytes(s.mem.read32(eaX(s, i))) & 0xffffffffL;
    }

    static void ldbrx(CpuState s, int i) {
        s.gpr[rt(i)] = Long.reverseBytes(s.mem.read64(eaX(s, i)));
    }

    static void sthbrx(CpuState s, int i) {
        s.mem.write16(eaX(s, i), Integer.reverseBytes((int) s.gpr[rt(i)]) >>> 16);
    }

    static void stwbrx(CpuState s, int i) {
        s.mem.write32(eaX(s, i), Integer.reverseBytes((int) s.gpr[rt(i)]));
    }

    static void stdbrx(CpuState s, int i) {
        s.mem.write64(eaX(s, i), Long.reverseBytes(s.gpr[rt(i)]));
    }

    // ---------------------------------------------------------------- larx / stcx

    private static long reserve(CpuState s, long ea, int len) {
        if ((ea & (len - 1)) != 0) {
            throw PpcException.alignment(ea);
        }
        long ra = s.mem.translateData(ea, false);
        long v = switch (len) {
            case 1 -> s.mem.read8(ea);
            case 2 -> s.mem.read16(ea);
            case 4 -> s.mem.read32(ea) & 0xffffffffL;
            default -> s.mem.read64(ea);
        };
        s.resValid = true;
        s.resAddr = ra;
        s.resLen = len;
        s.resVal = v;
        return v;
    }

    static void lbarx(CpuState s, int i) {
        s.gpr[rt(i)] = reserve(s, eaX(s, i), 1);
    }

    static void lharx(CpuState s, int i) {
        s.gpr[rt(i)] = reserve(s, eaX(s, i), 2);
    }

    static void lwarx(CpuState s, int i) {
        s.gpr[rt(i)] = reserve(s, eaX(s, i), 4);
    }

    static void ldarx(CpuState s, int i) {
        s.gpr[rt(i)] = reserve(s, eaX(s, i), 8);
    }

    static void lqarx(CpuState s, int i) {
        int rtp = rt(i);
        if ((rtp & 1) != 0 || rtp == ra(i) || rtp == rb(i)) {
            throw PpcException.illegal();
        }
        long ea = eaX(s, i);
        if ((ea & 15) != 0) {
            throw PpcException.alignment(ea);
        }
        long ra = s.mem.translateData(ea, false);
        loadQuad(s, ea, rtp);
        s.resValid = true;
        s.resAddr = ra;
        s.resLen = 16;
        // For a quadword the observed value is the first doubleword in storage.
        s.resVal = s.mem.read64(ea);
    }

    /**
     * Store-conditional, as QEMU does it: fails without touching storage unless a reservation of
     * the same length is held for this address; then succeeds if the reserved storage still holds
     * the value the larx observed (compare-and-swap model). Sets CR0 = 0b00 || success || SO.
     * There is no alignment check: a misaligned address cannot match the (aligned) reservation.
     */
    private static boolean storeCond(CpuState s, long ea, int len) {
        boolean ok = false;
        if (s.resValid && len == s.resLen) {
            long ra = s.mem.translateData(ea, true);
            if (ra == s.resAddr) {
                long cur = switch (len) {
                    case 1 -> s.mem.read8(ea);
                    case 2 -> s.mem.read16(ea);
                    case 4 -> s.mem.read32(ea) & 0xffffffffL;
                    default -> s.mem.read64(ea);
                };
                ok = cur == s.resVal;
            }
        }
        s.resValid = false;
        s.setCrField(0, (ok ? 2 : 0) | s.so);
        return ok;
    }

    static void stbcx(CpuState s, int i) {
        long ea = eaX(s, i);
        if (storeCond(s, ea, 1)) {
            s.mem.write8(ea, (int) s.gpr[rt(i)]);
        }
    }

    static void sthcx(CpuState s, int i) {
        long ea = eaX(s, i);
        if (storeCond(s, ea, 2)) {
            s.mem.write16(ea, (int) s.gpr[rt(i)]);
        }
    }

    static void stwcx(CpuState s, int i) {
        long ea = eaX(s, i);
        if (storeCond(s, ea, 4)) {
            s.mem.write32(ea, (int) s.gpr[rt(i)]);
        }
    }

    static void stdcx(CpuState s, int i) {
        long ea = eaX(s, i);
        if (storeCond(s, ea, 8)) {
            s.mem.write64(ea, s.gpr[rt(i)]);
        }
    }

    static void stqcx(CpuState s, int i) {
        int rsp = rt(i);
        if ((rsp & 1) != 0) {
            throw PpcException.illegal();
        }
        long ea = eaX(s, i);
        boolean ok = false;
        if (s.resValid && s.resLen == 16) {
            long ra = s.mem.translateData(ea, true);
            ok = ra == s.resAddr && s.mem.read64(ea) == s.resVal;
        }
        s.resValid = false;
        s.setCrField(0, (ok ? 2 : 0) | s.so);
        if (ok) {
            storeQuad(s, ea, rsp);
        }
    }

    // ---------------------------------------------------------------- multiple / string

    /**
     * Check that {@code len} bytes at {@code ea} are accessible before modifying anything. A
     * fault in a following page is reported with DAR = the first byte of that page (QEMU).
     */
    static void probe(CpuState s, long ea, int len, boolean store) {
        s.mem.translateData(ea, store);
        long last = Fields.ea(s, ea + len - 1);
        if (((ea ^ last) & ~0xfffL) != 0) {
            s.mem.translateData(last & ~0xfffL, store);
        }
    }

    /** Multiple and string forms are not supported in little-endian mode (alignment, DAR unchanged). */
    private static void noLittleEndian(CpuState s) {
        if ((s.msr & Msr.LE) != 0) {
            throw PpcException.alignment(s.spr[Spr.DAR]);
        }
    }

    /** Whether register {@code rx} is among the {@code n} registers starting at {@code start} (wrapping). */
    static boolean regInRange(int start, int n, int rx) {
        int end = start + n;
        return end <= 32 ? (rx >= start && rx < end) : (rx >= start || rx < end - 32);
    }

    static void lmw(CpuState s, int i) {
        noLittleEndian(s);
        long ea = eaD(s, i);
        int r = rt(i);
        // Check the whole range is accessible before modifying registers.
        probe(s, ea, 4 * (32 - r), false);
        for (; r < 32; r++) {
            s.gpr[r] = s.mem.read32(ea) & 0xffffffffL;
            ea = Fields.ea(s, ea + 4);
        }
    }

    static void stmw(CpuState s, int i) {
        noLittleEndian(s);
        long ea = eaD(s, i);
        int r = rt(i);
        probe(s, ea, 4 * (32 - r), true);
        for (; r < 32; r++) {
            s.mem.write32(ea, (int) s.gpr[r]);
            ea = Fields.ea(s, ea + 4);
        }
    }

    private static void loadString(CpuState s, long ea, int n, int r) {
        if (n == 0) {
            return;
        }
        GuestMemory m = s.mem;
        probe(s, ea, n, false);
        int k = 0;
        long acc = 0;
        for (int b = 0; b < n; b++) {
            acc = (acc << 8) | m.read8(Fields.ea(s, ea + b));
            if (++k == 4) {
                s.gpr[r] = acc;
                r = (r + 1) & 31;
                k = 0;
                acc = 0;
            }
        }
        if (k != 0) {
            s.gpr[r] = acc << (8 * (4 - k));
        }
    }

    private static void storeString(CpuState s, long ea, int n, int r) {
        if (n == 0) {
            return;
        }
        GuestMemory m = s.mem;
        probe(s, ea, n, true);
        int k = 0;
        for (int b = 0; b < n; b++) {
            int v = (int) (s.gpr[r] >>> (24 - 8 * k)) & 0xff;
            m.write8(Fields.ea(s, ea + b), v);
            if (++k == 4) {
                r = (r + 1) & 31;
                k = 0;
            }
        }
    }

    static void lswi(CpuState s, int i) {
        noLittleEndian(s);
        int nb = rb(i);
        if (nb == 0) {
            nb = 32;
        }
        if (regInRange(rt(i), (nb + 3) / 4, ra(i))) {
            throw PpcException.illegal();
        }
        loadString(s, Fields.ea(s, raOr0(s, i)), nb, rt(i));
    }

    static void lswx(CpuState s, int i) {
        noLittleEndian(s);
        int n = s.xerBc;
        if (n != 0) {
            int nregs = (n + 3) / 4;
            int ra = ra(i);
            if ((ra != 0 && regInRange(rt(i), nregs, ra)) || regInRange(rt(i), nregs, rb(i))) {
                throw PpcException.illegal();
            }
        }
        loadString(s, eaX(s, i), n, rt(i));
    }

    static void stswi(CpuState s, int i) {
        noLittleEndian(s);
        int nb = rb(i);
        storeString(s, Fields.ea(s, raOr0(s, i)), nb == 0 ? 32 : nb, rt(i));
    }

    static void stswx(CpuState s, int i) {
        noLittleEndian(s);
        storeString(s, eaX(s, i), s.xerBc, rt(i));
    }

    // ---------------------------------------------------------------- atomic memory operations

    /*
     * lwat/ldat/stwat/stdat as QEMU implements them: operands are extended according to the
     * access size, which for words is zero-extension, so the "signed" max/min function codes
     * compare words as unsigned. Invalid function codes raise a DSI that leaves DAR and DSISR
     * unchanged. Misaligned addresses raise an alignment interrupt.
     */

    private static PpcException invalidFc(CpuState s) {
        return PpcException.dsi(s.spr[Spr.DAR], (int) s.spr[Spr.DSISR]);
    }

    private static long amoCompute(int fc, long mem, long src) {
        return switch (fc) {
            case 0 -> mem + src;
            case 1 -> mem ^ src;
            case 2 -> mem | src;
            case 3 -> mem & src;
            case 4 -> Long.compareUnsigned(mem, src) >= 0 ? mem : src;
            case 5 -> mem >= src ? mem : src;
            case 6 -> Long.compareUnsigned(mem, src) <= 0 ? mem : src;
            case 7 -> mem <= src ? mem : src;
            default -> src; // 8: swap
        };
    }

    private static long ld(CpuState s, long ea, boolean word) {
        return word ? s.mem.read32(ea) & 0xffffffffL : s.mem.read64(ea);
    }

    private static void st(CpuState s, long ea, boolean word, long v) {
        if (word) {
            s.mem.write32(ea, (int) v);
        } else {
            s.mem.write64(ea, v);
        }
    }

    private static void loadAtomic(CpuState s, int i, boolean word) {
        int fc = rb(i);
        boolean valid = fc <= 8 || fc == 16 || fc == 24 || fc == 25 || fc == 28;
        if (!valid) {
            throw invalidFc(s);
        }
        long ea = Fields.ea(s, raOr0(s, i));
        int size = word ? 4 : 8;
        if ((ea & (size - 1)) != 0) {
            throw PpcException.alignment(ea);
        }
        long mask = word ? 0xffffffffL : -1L;
        int rt = rt(i);
        long src = s.gpr[(rt + 1) & 31] & mask;
        long old = ld(s, ea, word); // QEMU's atomics probe for read first, then write
        s.mem.translateData(ea, true);
        long result;
        switch (fc) {
            case 16 -> { // compare and swap not equal
                st(s, ea, word, old != src ? s.gpr[(rt + 2) & 31] : old);
                result = old;
            }
            case 24, 25, 28 -> { // fetch and increment bounded / increment equal / decrement bounded
                long ea2 = Fields.ea(s, ea + size);
                s.mem.translateData(ea2, false);
                long next = ld(s, ea2, word);
                boolean cond = fc == 25 ? old == next : old != next;
                long u = old + (fc == 28 ? -1 : 1);
                st(s, ea, word, cond ? u : old);
                result = cond ? old : 0xffffffff80000000L;
            }
            default -> {
                st(s, ea, word, amoCompute(fc, old, src));
                result = old;
            }
        }
        s.gpr[rt] = result;
    }

    private static void storeAtomic(CpuState s, int i, boolean word) {
        int fc = rb(i);
        if (fc > 7 && fc != 24) {
            throw invalidFc(s);
        }
        long ea = Fields.ea(s, raOr0(s, i));
        int size = word ? 4 : 8;
        if ((ea & (size - 1)) != 0) {
            throw PpcException.alignment(ea);
        }
        long mask = word ? 0xffffffffL : -1L;
        long src = s.gpr[rt(i)] & mask;
        long old = ld(s, ea, word); // QEMU's atomics probe for read first, then write
        s.mem.translateData(ea, true);
        if (fc == 24) { // store twin
            long ea2 = Fields.ea(s, ea + size);
            s.mem.translateData(ea2, true);
            long next = ld(s, ea2, word);
            if (old == next) {
                st(s, ea, word, src);
                st(s, ea2, word, src);
            }
            return;
        }
        st(s, ea, word, amoCompute(fc, old, src));
    }

    static void lwat(CpuState s, int i) {
        loadAtomic(s, i, true);
    }

    static void ldat(CpuState s, int i) {
        loadAtomic(s, i, false);
    }

    static void stwat(CpuState s, int i) {
        storeAtomic(s, i, true);
    }

    static void stdat(CpuState s, int i) {
        storeAtomic(s, i, false);
    }
}
