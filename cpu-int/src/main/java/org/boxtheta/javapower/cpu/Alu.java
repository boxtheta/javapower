package org.boxtheta.javapower.cpu;

import static org.boxtheta.javapower.cpu.Fields.bf;
import static org.boxtheta.javapower.cpu.Fields.oe;
import static org.boxtheta.javapower.cpu.Fields.ra;
import static org.boxtheta.javapower.cpu.Fields.raOr0;
import static org.boxtheta.javapower.cpu.Fields.rb;
import static org.boxtheta.javapower.cpu.Fields.rc;
import static org.boxtheta.javapower.cpu.Fields.rcBit;
import static org.boxtheta.javapower.cpu.Fields.rt;
import static org.boxtheta.javapower.cpu.Fields.si;
import static org.boxtheta.javapower.cpu.Fields.ui;

import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.PpcException;

/** Fixed-point arithmetic, logical, compare, rotate/shift and trap instructions. */
final class Alu {
    private Alu() {}

    static void register(Reg t) {
        // D-form arithmetic / logical / compare / trap.
        t.addPrimary(14, "addi", Alu::addi);
        t.addPrimary(15, "addis", Alu::addis);
        t.addPrimary(12, "addic", Alu::addic);
        t.addPrimary(13, "addic.", Alu::addicRc);
        t.addPrimary(8, "subfic", Alu::subfic);
        t.addPrimary(7, "mulli", Alu::mulli);
        t.addPrimary(24, "ori", Alu::ori);
        t.addPrimary(25, "oris", Alu::oris);
        t.addPrimary(26, "xori", Alu::xori);
        t.addPrimary(27, "xoris", Alu::xoris);
        t.addPrimary(28, "andi.", Alu::andi);
        t.addPrimary(29, "andis.", Alu::andis);
        t.addPrimary(11, "cmpi", Alu::cmpi);
        t.addPrimary(10, "cmpli", Alu::cmpli);
        t.addPrimary(3, "twi", Alu::twi);
        t.addPrimary(2, "tdi", Alu::tdi);

        // XO-form arithmetic (OE matched by the op).
        t.addXO(31, 266, "add", Alu::add);
        t.addXO(31, 10, "addc", Alu::addc);
        t.addXO(31, 138, "adde", Alu::adde);
        t.addXO(31, 234, "addme", Alu::addme);
        t.addXO(31, 202, "addze", Alu::addze);
        t.addXO(31, 40, "subf", Alu::subf);
        t.addXO(31, 8, "subfc", Alu::subfc);
        t.addXO(31, 136, "subfe", Alu::subfe);
        t.addXO(31, 232, "subfme", Alu::subfme);
        t.addXO(31, 200, "subfze", Alu::subfze);
        t.addXO(31, 104, "neg", Alu::neg);
        t.addXO(31, 235, "mullw", Alu::mullw);
        t.addXO(31, 233, "mulld", Alu::mulld);
        t.addX(31, 75, "mulhw", Alu::mulhw);
        t.addX(31, 11, "mulhwu", Alu::mulhwu);
        t.addX(31, 73, "mulhd", Alu::mulhd);
        t.addX(31, 9, "mulhdu", Alu::mulhdu);
        t.addXO(31, 491, "divw", Alu::divw);
        t.addXO(31, 459, "divwu", Alu::divwu);
        t.addXO(31, 489, "divd", Alu::divd);
        t.addXO(31, 457, "divdu", Alu::divdu);
        t.addXO(31, 427, "divwe", Alu::divwe);
        t.addXO(31, 395, "divweu", Alu::divweu);
        t.addXO(31, 425, "divde", Alu::divde);
        t.addXO(31, 393, "divdeu", Alu::divdeu);
        t.addX(31, 779, "modsw", Alu::modsw);
        t.addX(31, 267, "moduw", Alu::moduw);
        t.addX(31, 777, "modsd", Alu::modsd);
        t.addX(31, 265, "modud", Alu::modud);
        t.addX(31, 170, "addex", Alu::addex);
        t.addX(31, 74, "addg6s", Alu::addg6s);
        // VA-form multiply-add (shares primary opcode 4 with VMX).
        t.add(0xfc00003f, (4 << 26) | 48, "maddhd", Alu::maddhd);
        t.add(0xfc00003f, (4 << 26) | 49, "maddhdu", Alu::maddhdu);
        t.add(0xfc00003f, (4 << 26) | 51, "maddld", Alu::maddld);

        // X-form logical.
        t.addX(31, 28, "and", Alu::and);
        t.addX(31, 60, "andc", Alu::andc);
        t.addX(31, 444, "or", Alu::or);
        t.addX(31, 412, "orc", Alu::orc);
        t.addX(31, 316, "xor", Alu::xor);
        t.addX(31, 476, "nand", Alu::nand);
        t.addX(31, 124, "nor", Alu::nor);
        t.addX(31, 284, "eqv", Alu::eqv);
        t.addX(31, 954, "extsb", Alu::extsb);
        t.addX(31, 922, "extsh", Alu::extsh);
        t.addX(31, 986, "extsw", Alu::extsw);
        t.addX(31, 26, "cntlzw", Alu::cntlzw);
        t.addX(31, 58, "cntlzd", Alu::cntlzd);
        t.addX(31, 538, "cnttzw", Alu::cnttzw);
        t.addX(31, 570, "cnttzd", Alu::cnttzd);
        t.addX(31, 122, "popcntb", Alu::popcntb);
        t.addX(31, 378, "popcntw", Alu::popcntw);
        t.addX(31, 506, "popcntd", Alu::popcntd);
        t.addX(31, 154, "prtyw", Alu::prtyw);
        t.addX(31, 186, "prtyd", Alu::prtyd);
        t.addX(31, 508, "cmpb", Alu::cmpb);
        t.addX(31, 252, "bpermd", Alu::bpermd);
        t.addX(31, 282, "cdtbcd", Alu::cdtbcd);
        t.addX(31, 314, "cbcdtd", Alu::cbcdtd);

        // Shifts.
        t.addX(31, 24, "slw", Alu::slw);
        t.addX(31, 536, "srw", Alu::srw);
        t.addX(31, 792, "sraw", Alu::sraw);
        t.addX(31, 824, "srawi", Alu::srawi);
        t.addX(31, 27, "sld", Alu::sld);
        t.addX(31, 539, "srd", Alu::srd);
        t.addX(31, 794, "srad", Alu::srad);
        t.addX(31, 826, "sradi", Alu::sradi);
        t.addX(31, 827, "sradi", Alu::sradi);
        t.addX(31, 890, "extswsli", Alu::extswsli);
        t.addX(31, 891, "extswsli", Alu::extswsli);

        // Rotates.
        t.addPrimary(21, "rlwinm", Alu::rlwinm);
        t.addPrimary(23, "rlwnm", Alu::rlwnm);
        t.addPrimary(20, "rlwimi", Alu::rlwimi);
        t.add(0xfc00001c, (30 << 26), "rldicl", Alu::rldicl);
        t.add(0xfc00001c, (30 << 26) | (1 << 2), "rldicr", Alu::rldicr);
        t.add(0xfc00001c, (30 << 26) | (2 << 2), "rldic", Alu::rldic);
        t.add(0xfc00001c, (30 << 26) | (3 << 2), "rldimi", Alu::rldimi);
        t.add(0xfc00001e, (30 << 26) | (8 << 1), "rldcl", Alu::rldcl);
        t.add(0xfc00001e, (30 << 26) | (9 << 1), "rldcr", Alu::rldcr);

        // Compare / select / misc.
        t.addX(31, 0, "cmp", Alu::cmp);
        t.addX(31, 32, "cmpl", Alu::cmpl);
        t.addX(31, 192, "cmprb", Alu::cmprb);
        t.addX(31, 224, "cmpeqb", Alu::cmpeqb);
        t.addX(31, 128, "setb", Alu::setb);
        t.addX(31, 576, "mcrxrx", Alu::mcrxrx);
        t.addX(31, 512, "mcrxr", Alu::mcrxr);
        t.addA(31, 15, "isel", Alu::isel);
        t.addX(31, 4, "tw", Alu::tw);
        t.addX(31, 68, "td", Alu::td);
        t.addX(31, 755, "darn", Alu::darn);
    }

    // ---------------------------------------------------------------- add / subtract core

    /** rt = a + b + cin, updating CA/CA32 if {@code setCa}, OV/OV32/SO if {@code oe}, CR0 if {@code rc}. */
    private static void add3(CpuState s, int insn, long a, long b, long cin, boolean setCa, boolean oe) {
        long r = a + b + cin;
        if (setCa) {
            int ca32 = (int) ((a ^ b ^ r) >>> 32) & 1;
            s.ca32 = ca32;
            s.ca = s.msr < 0 ? (int) (((a & b) | ((a | b) & ~r)) >>> 63) : ca32;
        }
        if (oe) {
            long v = (a ^ r) & (b ^ r);
            int ov32 = (int) (v >>> 31) & 1;
            Fields.setOv(s, s.msr < 0 ? (int) (v >>> 63) : ov32, ov32);
        }
        s.gpr[rt(insn)] = r;
        if (rcBit(insn)) {
            s.setCr0(r);
        }
    }

    static void addi(CpuState s, int i) {
        s.gpr[rt(i)] = raOr0(s, i) + si(i);
    }

    static void addis(CpuState s, int i) {
        s.gpr[rt(i)] = raOr0(s, i) + (si(i) << 16);
    }

    static void addic(CpuState s, int i) {
        long a = s.gpr[ra(i)];
        long b = si(i);
        long r = a + b;
        s.ca32 = (int) ((a ^ b ^ r) >>> 32) & 1;
        s.ca = s.msr < 0 ? (int) (((a & b) | ((a | b) & ~r)) >>> 63) : s.ca32;
        s.gpr[rt(i)] = r;
    }

    static void addicRc(CpuState s, int i) {
        addic(s, i);
        s.setCr0(s.gpr[rt(i)]);
    }

    static void subfic(CpuState s, int i) {
        long a = ~s.gpr[ra(i)];
        long b = si(i);
        long r = a + b + 1;
        s.ca32 = (int) ((a ^ b ^ r) >>> 32) & 1;
        s.ca = s.msr < 0 ? (int) (((a & b) | ((a | b) & ~r)) >>> 63) : s.ca32;
        s.gpr[rt(i)] = r;
    }

    static void mulli(CpuState s, int i) {
        s.gpr[rt(i)] = s.gpr[ra(i)] * si(i);
    }

    static void add(CpuState s, int i) {
        add3(s, i, s.gpr[ra(i)], s.gpr[rb(i)], 0, false, oe(i));
    }

    static void addc(CpuState s, int i) {
        add3(s, i, s.gpr[ra(i)], s.gpr[rb(i)], 0, true, oe(i));
    }

    static void adde(CpuState s, int i) {
        add3(s, i, s.gpr[ra(i)], s.gpr[rb(i)], s.ca, true, oe(i));
    }

    static void addme(CpuState s, int i) {
        add3(s, i, s.gpr[ra(i)], -1L, s.ca, true, oe(i));
    }

    static void addze(CpuState s, int i) {
        add3(s, i, s.gpr[ra(i)], 0, s.ca, true, oe(i));
    }

    static void subf(CpuState s, int i) {
        add3(s, i, ~s.gpr[ra(i)], s.gpr[rb(i)], 1, false, oe(i));
    }

    static void subfc(CpuState s, int i) {
        add3(s, i, ~s.gpr[ra(i)], s.gpr[rb(i)], 1, true, oe(i));
    }

    static void subfe(CpuState s, int i) {
        add3(s, i, ~s.gpr[ra(i)], s.gpr[rb(i)], s.ca, true, oe(i));
    }

    static void subfme(CpuState s, int i) {
        add3(s, i, ~s.gpr[ra(i)], -1L, s.ca, true, oe(i));
    }

    static void subfze(CpuState s, int i) {
        add3(s, i, ~s.gpr[ra(i)], 0, s.ca, true, oe(i));
    }

    static void neg(CpuState s, int i) {
        add3(s, i, ~s.gpr[ra(i)], 0, 1, false, oe(i));
    }

    static void addex(CpuState s, int i) {
        // Z23-form; CY (ISA bits 21:22) = 0 is the only defined value: carry in/out through OV.
        long a = s.gpr[ra(i)];
        long b = s.gpr[rb(i)];
        long r = a + b + s.ov;
        s.ov32 = (int) ((a ^ b ^ r) >>> 32) & 1;
        s.ov = s.msr < 0 ? (int) (((a & b) | ((a | b) & ~r)) >>> 63) : s.ov32;
        s.gpr[rt(i)] = r;
    }

    static void addg6s(CpuState s, int i) {
        long a = s.gpr[ra(i)];
        long b = s.gpr[rb(i)];
        long r = 0;
        for (int n = 0; n < 16; n++) {
            int sh = 4 * n + 4;
            boolean carry;
            if (sh == 64) {
                long lo = a + b;
                carry = Long.compareUnsigned(lo, a) < 0;
            } else {
                long m = (1L << sh) - 1;
                carry = (((a & m) + (b & m)) >>> sh) != 0;
            }
            if (!carry) {
                r |= 6L << (4 * n);
            }
        }
        s.gpr[rt(i)] = r;
    }

    // ---------------------------------------------------------------- multiply / divide

    private static void result(CpuState s, int i, long r) {
        s.gpr[rt(i)] = r;
        if (rcBit(i)) {
            s.setCr0(r);
        }
    }

    static void mullw(CpuState s, int i) {
        long r = (long) (int) s.gpr[ra(i)] * (long) (int) s.gpr[rb(i)];
        if (oe(i)) {
            int ov = r != (long) (int) r ? 1 : 0;
            Fields.setOv(s, ov, ov);
        }
        result(s, i, r);
    }

    static void mulld(CpuState s, int i) {
        long a = s.gpr[ra(i)];
        long b = s.gpr[rb(i)];
        long r = a * b;
        if (oe(i)) {
            int ov = Math.multiplyHigh(a, b) != (r >> 63) ? 1 : 0;
            Fields.setOv(s, ov, ov);
        }
        result(s, i, r);
    }

    static void mulhw(CpuState s, int i) {
        long p = (long) (int) s.gpr[ra(i)] * (long) (int) s.gpr[rb(i)];
        result(s, i, p >>> 32);
    }

    static void mulhwu(CpuState s, int i) {
        long p = (s.gpr[ra(i)] & 0xffffffffL) * (s.gpr[rb(i)] & 0xffffffffL);
        result(s, i, p >>> 32);
    }

    static void mulhd(CpuState s, int i) {
        result(s, i, Math.multiplyHigh(s.gpr[ra(i)], s.gpr[rb(i)]));
    }

    static void mulhdu(CpuState s, int i) {
        result(s, i, Math.unsignedMultiplyHigh(s.gpr[ra(i)], s.gpr[rb(i)]));
    }

    private static void divOv(CpuState s, int i, boolean ov) {
        if (oe(i)) {
            int v = ov ? 1 : 0;
            Fields.setOv(s, v, v);
        }
    }

    static void divw(CpuState s, int i) {
        int a = (int) s.gpr[ra(i)];
        int b = (int) s.gpr[rb(i)];
        boolean bad = b == 0 || (a == Integer.MIN_VALUE && b == -1);
        int q = a / (bad ? 1 : b);
        divOv(s, i, bad);
        result(s, i, q & 0xffffffffL);
    }

    static void divwu(CpuState s, int i) {
        int a = (int) s.gpr[ra(i)];
        int b = (int) s.gpr[rb(i)];
        boolean bad = b == 0;
        int q = Integer.divideUnsigned(a, bad ? 1 : b);
        divOv(s, i, bad);
        result(s, i, q & 0xffffffffL);
    }

    static void divd(CpuState s, int i) {
        long a = s.gpr[ra(i)];
        long b = s.gpr[rb(i)];
        boolean bad = b == 0 || (a == Long.MIN_VALUE && b == -1);
        long q = a / (bad ? 1 : b);
        divOv(s, i, bad);
        result(s, i, q);
    }

    static void divdu(CpuState s, int i) {
        long a = s.gpr[ra(i)];
        long b = s.gpr[rb(i)];
        boolean bad = b == 0;
        long q = Long.divideUnsigned(a, bad ? 1 : b);
        divOv(s, i, bad);
        result(s, i, q);
    }

    static void divwe(CpuState s, int i) {
        long dividend = s.gpr[ra(i)] << 32;
        long divisor = (int) s.gpr[rb(i)];
        long q = 0;
        boolean ov;
        if (divisor == 0 || (divisor == -1 && dividend == Long.MIN_VALUE)) {
            ov = true;
        } else {
            q = dividend / divisor;
            ov = q != (int) q;
        }
        if (ov) {
            q = 0;
        }
        divOv(s, i, ov);
        result(s, i, q);
    }

    static void divweu(CpuState s, int i) {
        long dividend = s.gpr[ra(i)] << 32;
        long divisor = s.gpr[rb(i)] & 0xffffffffL;
        long q = 0;
        boolean ov;
        if (divisor == 0) {
            ov = true;
        } else {
            q = Long.divideUnsigned(dividend, divisor);
            ov = Long.compareUnsigned(q, 0xffffffffL) > 0;
        }
        if (ov) {
            q = 0;
        }
        divOv(s, i, ov);
        result(s, i, q);
    }

    /** (hi:0) / d for unsigned hi < d; the quotient fits in 64 bits. */
    static long divu128Hi(long hi, long d) {
        long rem = hi;
        long q = 0;
        for (int n = 0; n < 64; n++) {
            boolean top = rem < 0;
            rem <<= 1;
            q <<= 1;
            if (top || Long.compareUnsigned(rem, d) >= 0) {
                rem -= d;
                q |= 1;
            }
        }
        return q;
    }

    static void divdeu(CpuState s, int i) {
        long a = s.gpr[ra(i)];
        long b = s.gpr[rb(i)];
        long q = 0;
        boolean ov = b == 0 || Long.compareUnsigned(a, b) >= 0;
        if (!ov) {
            q = divu128Hi(a, b);
        }
        divOv(s, i, ov);
        result(s, i, q);
    }

    static void divde(CpuState s, int i) {
        long a = s.gpr[ra(i)];
        long b = s.gpr[rb(i)];
        long ua = a < 0 ? -a : a;
        long ub = b < 0 ? -b : b;
        long q = 0;
        boolean ov = b == 0 || Long.compareUnsigned(ua, ub) >= 0;
        if (!ov) {
            q = divu128Hi(ua, ub);
            if ((a < 0) != (b < 0)) {
                q = -q;
            }
        }
        divOv(s, i, ov);
        result(s, i, q);
    }

    static void modsw(CpuState s, int i) {
        int a = (int) s.gpr[ra(i)];
        int b = (int) s.gpr[rb(i)];
        boolean bad = b == 0 || (a == Integer.MIN_VALUE && b == -1);
        s.gpr[rt(i)] = a % (bad ? 1 : b);
    }

    static void moduw(CpuState s, int i) {
        int a = (int) s.gpr[ra(i)];
        int b = (int) s.gpr[rb(i)];
        s.gpr[rt(i)] = Integer.remainderUnsigned(a, b == 0 ? 1 : b) & 0xffffffffL;
    }

    static void modsd(CpuState s, int i) {
        long a = s.gpr[ra(i)];
        long b = s.gpr[rb(i)];
        boolean bad = b == 0 || (a == Long.MIN_VALUE && b == -1);
        s.gpr[rt(i)] = a % (bad ? 1 : b);
    }

    static void modud(CpuState s, int i) {
        long a = s.gpr[ra(i)];
        long b = s.gpr[rb(i)];
        s.gpr[rt(i)] = Long.remainderUnsigned(a, b == 0 ? 1 : b);
    }

    static void maddhd(CpuState s, int i) {
        long a = s.gpr[ra(i)];
        long b = s.gpr[rb(i)];
        long c = s.gpr[rc(i)];
        long hi = Math.multiplyHigh(a, b);
        long lo = a * b;
        long sum = lo + c;
        hi += (Long.compareUnsigned(sum, lo) < 0 ? 1 : 0) + (c >> 63);
        s.gpr[rt(i)] = hi;
    }

    static void maddhdu(CpuState s, int i) {
        long a = s.gpr[ra(i)];
        long b = s.gpr[rb(i)];
        long c = s.gpr[rc(i)];
        long hi = Math.unsignedMultiplyHigh(a, b);
        long lo = a * b;
        long sum = lo + c;
        hi += Long.compareUnsigned(sum, lo) < 0 ? 1 : 0;
        s.gpr[rt(i)] = hi;
    }

    static void maddld(CpuState s, int i) {
        s.gpr[rt(i)] = s.gpr[ra(i)] * s.gpr[rb(i)] + s.gpr[rc(i)];
    }

    // ---------------------------------------------------------------- logical

    /** X-form logical result: RA = r, CR0 if Rc. */
    private static void logical(CpuState s, int i, long r) {
        s.gpr[ra(i)] = r;
        if (rcBit(i)) {
            s.setCr0(r);
        }
    }

    static void ori(CpuState s, int i) {
        s.gpr[ra(i)] = s.gpr[rt(i)] | ui(i);
    }

    static void oris(CpuState s, int i) {
        s.gpr[ra(i)] = s.gpr[rt(i)] | (ui(i) << 16);
    }

    static void xori(CpuState s, int i) {
        s.gpr[ra(i)] = s.gpr[rt(i)] ^ ui(i);
    }

    static void xoris(CpuState s, int i) {
        s.gpr[ra(i)] = s.gpr[rt(i)] ^ (ui(i) << 16);
    }

    static void andi(CpuState s, int i) {
        long r = s.gpr[rt(i)] & ui(i);
        s.gpr[ra(i)] = r;
        s.setCr0(r);
    }

    static void andis(CpuState s, int i) {
        long r = s.gpr[rt(i)] & (ui(i) << 16);
        s.gpr[ra(i)] = r;
        s.setCr0(r);
    }

    static void and(CpuState s, int i) {
        logical(s, i, s.gpr[rt(i)] & s.gpr[rb(i)]);
    }

    static void andc(CpuState s, int i) {
        logical(s, i, s.gpr[rt(i)] & ~s.gpr[rb(i)]);
    }

    static void or(CpuState s, int i) {
        logical(s, i, s.gpr[rt(i)] | s.gpr[rb(i)]);
    }

    static void orc(CpuState s, int i) {
        logical(s, i, s.gpr[rt(i)] | ~s.gpr[rb(i)]);
    }

    static void xor(CpuState s, int i) {
        logical(s, i, s.gpr[rt(i)] ^ s.gpr[rb(i)]);
    }

    static void nand(CpuState s, int i) {
        logical(s, i, ~(s.gpr[rt(i)] & s.gpr[rb(i)]));
    }

    static void nor(CpuState s, int i) {
        logical(s, i, ~(s.gpr[rt(i)] | s.gpr[rb(i)]));
    }

    static void eqv(CpuState s, int i) {
        logical(s, i, ~(s.gpr[rt(i)] ^ s.gpr[rb(i)]));
    }

    static void extsb(CpuState s, int i) {
        logical(s, i, (byte) s.gpr[rt(i)]);
    }

    static void extsh(CpuState s, int i) {
        logical(s, i, (short) s.gpr[rt(i)]);
    }

    static void extsw(CpuState s, int i) {
        logical(s, i, (int) s.gpr[rt(i)]);
    }

    static void cntlzw(CpuState s, int i) {
        logical(s, i, Integer.numberOfLeadingZeros((int) s.gpr[rt(i)]));
    }

    static void cntlzd(CpuState s, int i) {
        logical(s, i, Long.numberOfLeadingZeros(s.gpr[rt(i)]));
    }

    static void cnttzw(CpuState s, int i) {
        logical(s, i, Integer.numberOfTrailingZeros((int) s.gpr[rt(i)]));
    }

    static void cnttzd(CpuState s, int i) {
        logical(s, i, Long.numberOfTrailingZeros(s.gpr[rt(i)]));
    }

    static void popcntb(CpuState s, int i) {
        long x = s.gpr[rt(i)];
        x = x - ((x >>> 1) & 0x5555555555555555L);
        x = (x & 0x3333333333333333L) + ((x >>> 2) & 0x3333333333333333L);
        x = (x + (x >>> 4)) & 0x0f0f0f0f0f0f0f0fL;
        s.gpr[ra(i)] = x;
    }

    static void popcntw(CpuState s, int i) {
        long x = s.gpr[rt(i)];
        s.gpr[ra(i)] = ((long) Integer.bitCount((int) (x >>> 32)) << 32) | Integer.bitCount((int) x);
    }

    static void popcntd(CpuState s, int i) {
        s.gpr[ra(i)] = Long.bitCount(s.gpr[rt(i)]);
    }

    static void prtyw(CpuState s, int i) {
        long x = s.gpr[rt(i)] & 0x0101010101010101L;
        x ^= x >>> 16;
        x ^= x >>> 8;
        s.gpr[ra(i)] = x & 0x0000000100000001L;
    }

    static void prtyd(CpuState s, int i) {
        long x = s.gpr[rt(i)] & 0x0101010101010101L;
        s.gpr[ra(i)] = Long.bitCount(x) & 1;
    }

    static void cmpb(CpuState s, int i) {
        long a = s.gpr[rt(i)];
        long b = s.gpr[rb(i)];
        long r = 0;
        for (int n = 0; n < 64; n += 8) {
            if (((a >>> n) & 0xff) == ((b >>> n) & 0xff)) {
                r |= 0xffL << n;
            }
        }
        s.gpr[ra(i)] = r;
    }

    static void bpermd(CpuState s, int i) {
        long rs = s.gpr[rt(i)];
        long rb = s.gpr[rb(i)];
        long r = 0;
        for (int n = 0; n < 8; n++) {
            int idx = (int) (rs >>> (56 - 8 * n)) & 0xff;
            if (idx < 64 && ((rb >>> (63 - idx)) & 1) != 0) {
                r |= 1L << (7 - n);
            }
        }
        s.gpr[ra(i)] = r;
    }

    /** Decode one 10-bit densely packed decimal declet into three BCD digits. */
    static int dpdToBcd(int d) {
        int p = (d >>> 9) & 1, q = (d >>> 8) & 1, r = (d >>> 7) & 1;
        int st = (d >>> 5) & 3, u = (d >>> 4) & 1, v = (d >>> 3) & 1;
        int w = (d >>> 2) & 1, x = (d >>> 1) & 1, y = d & 1;
        int pqr = (d >>> 7) & 7, stu = (d >>> 4) & 7, wxy = d & 7;
        int s1 = st >>> 1, t1 = st & 1;
        int d2, d1, d0;
        if (v == 0) {
            d2 = pqr;
            d1 = stu;
            d0 = wxy;
        } else {
            switch ((w << 1) | x) {
                case 0 -> { d2 = pqr; d1 = stu; d0 = 8 + y; }
                case 1 -> { d2 = pqr; d1 = 8 + u; d0 = (s1 << 2) | (t1 << 1) | y; }
                case 2 -> { d2 = 8 + r; d1 = stu; d0 = (p << 2) | (q << 1) | y; }
                default -> {
                    switch (st) {
                        case 0 -> { d2 = 8 + r; d1 = 8 + u; d0 = (p << 2) | (q << 1) | y; }
                        case 1 -> { d2 = 8 + r; d1 = (p << 2) | (q << 1) | u; d0 = 8 + y; }
                        case 2 -> { d2 = pqr; d1 = 8 + u; d0 = 8 + y; }
                        default -> { d2 = 8 + r; d1 = 8 + u; d0 = 8 + y; }
                    }
                }
            }
        }
        return (d2 << 8) | (d1 << 4) | d0;
    }

    /** Encode three BCD digits (12 bits) as a declet. Digits must be 0..9. */
    static int bcdToDpd(int bcd) {
        int d2 = (bcd >>> 8) & 0xf, d1 = (bcd >>> 4) & 0xf, d0 = bcd & 0xf;
        int a = d2 >>> 3, e = d1 >>> 3, i = d0 >>> 3;
        int bcd2 = d2 & 7, fgh = d1 & 7, jkm = d0 & 7;
        int d = d2 & 1, h = d1 & 1, m = d0 & 1;
        int fg = (d1 >>> 1) & 3, jk = (d0 >>> 1) & 3;
        int pqr, stu, v, wxy;
        switch ((a << 2) | (e << 1) | i) {
            case 0 -> { pqr = bcd2; stu = fgh; v = 0; wxy = jkm; }
            case 1 -> { pqr = bcd2; stu = fgh; v = 1; wxy = m; }
            case 2 -> { pqr = bcd2; stu = (jk << 1) | h; v = 1; wxy = 2 | m; }
            case 3 -> { pqr = bcd2; stu = 4 | h; v = 1; wxy = 6 | m; }
            case 4 -> { pqr = (jk << 1) | d; stu = fgh; v = 1; wxy = 4 | m; }
            case 5 -> { pqr = (fg << 1) | d; stu = 2 | h; v = 1; wxy = 6 | m; }
            case 6 -> { pqr = (jk << 1) | d; stu = h; v = 1; wxy = 6 | m; }
            default -> { pqr = d; stu = 6 | h; v = 1; wxy = 6 | m; }
        }
        return (pqr << 7) | (stu << 4) | (v << 3) | wxy;
    }

    static void cdtbcd(CpuState s, int i) {
        long x = s.gpr[rt(i)];
        long r = 0;
        for (int w = 0; w < 64; w += 32) {
            int word = (int) (x >>> w);
            long bcd = ((long) dpdToBcd((word >>> 10) & 0x3ff) << 12) | dpdToBcd(word & 0x3ff);
            r |= bcd << w;
        }
        s.gpr[ra(i)] = r;
    }

    static void cbcdtd(CpuState s, int i) {
        long x = s.gpr[rt(i)];
        long r = 0;
        for (int w = 0; w < 64; w += 32) {
            int word = (int) (x >>> w);
            int digits = 0;
            for (int n = 0; n < 6; n++) {
                int dgt = (word >>> (4 * n)) & 0xf;
                if (dgt > 9) {
                    dgt &= 9;
                }
                digits |= dgt << (4 * n);
            }
            long dpd = ((long) bcdToDpd((digits >>> 12) & 0xfff) << 10) | bcdToDpd(digits & 0xfff);
            r |= dpd << w;
        }
        s.gpr[ra(i)] = r;
    }

    // ---------------------------------------------------------------- shifts / rotates

    static void slw(CpuState s, int i) {
        int n = (int) s.gpr[rb(i)] & 0x3f;
        long r = n > 31 ? 0 : ((s.gpr[rt(i)] << n) & 0xffffffffL);
        logical(s, i, r);
    }

    static void srw(CpuState s, int i) {
        int n = (int) s.gpr[rb(i)] & 0x3f;
        long r = n > 31 ? 0 : ((s.gpr[rt(i)] & 0xffffffffL) >>> n);
        logical(s, i, r);
    }

    private static void srawBy(CpuState s, int i, int n) {
        int v = (int) s.gpr[rt(i)];
        long r;
        int ca;
        if (n < 32) {
            r = v >> n;
            ca = (v < 0 && (v & ((1 << n) - 1)) != 0) ? 1 : 0;
        } else {
            r = v >> 31;
            ca = v < 0 ? 1 : 0;
        }
        s.ca = ca;
        s.ca32 = ca;
        logical(s, i, r);
    }

    static void sraw(CpuState s, int i) {
        srawBy(s, i, (int) s.gpr[rb(i)] & 0x3f);
    }

    static void srawi(CpuState s, int i) {
        srawBy(s, i, rb(i));
    }

    static void sld(CpuState s, int i) {
        int n = (int) s.gpr[rb(i)] & 0x7f;
        logical(s, i, n > 63 ? 0 : s.gpr[rt(i)] << n);
    }

    static void srd(CpuState s, int i) {
        int n = (int) s.gpr[rb(i)] & 0x7f;
        logical(s, i, n > 63 ? 0 : s.gpr[rt(i)] >>> n);
    }

    private static void sradBy(CpuState s, int i, int n) {
        long v = s.gpr[rt(i)];
        long r;
        int ca;
        if (n < 64) {
            r = v >> n;
            ca = (v < 0 && (v & ((1L << n) - 1)) != 0) ? 1 : 0;
        } else {
            r = v >> 63;
            ca = v < 0 ? 1 : 0;
        }
        s.ca = ca;
        s.ca32 = ca;
        logical(s, i, r);
    }

    static void srad(CpuState s, int i) {
        sradBy(s, i, (int) s.gpr[rb(i)] & 0x7f);
    }

    static void sradi(CpuState s, int i) {
        sradBy(s, i, rb(i) | ((i & 2) << 4));
    }

    static void extswsli(CpuState s, int i) {
        int n = rb(i) | ((i & 2) << 4);
        logical(s, i, ((long) (int) s.gpr[rt(i)]) << n);
    }

    /** MASK(mb, me) over 64 bits, ISA numbering. */
    static long mask(int mb, int me) {
        long m1 = -1L >>> mb;
        long m2 = -1L << (63 - me);
        return mb <= me ? (m1 & m2) : (m1 | m2);
    }

    private static long rotl32(long v, int n) {
        long w = v & 0xffffffffL;
        w |= w << 32;
        return Long.rotateLeft(w, n);
    }

    static void rlwinm(CpuState s, int i) {
        int sh = rb(i);
        int mb = rc(i);
        int me = (i >>> 1) & 31;
        logical(s, i, rotl32(s.gpr[rt(i)], sh) & mask(mb + 32, me + 32));
    }

    static void rlwnm(CpuState s, int i) {
        int sh = (int) s.gpr[rb(i)] & 31;
        int mb = rc(i);
        int me = (i >>> 1) & 31;
        logical(s, i, rotl32(s.gpr[rt(i)], sh) & mask(mb + 32, me + 32));
    }

    static void rlwimi(CpuState s, int i) {
        int sh = rb(i);
        int mb = rc(i);
        int me = (i >>> 1) & 31;
        long m = mask(mb + 32, me + 32);
        long r = (rotl32(s.gpr[rt(i)], sh) & m) | (s.gpr[ra(i)] & ~m);
        logical(s, i, r);
    }

    private static int mdSh(int i) {
        return rb(i) | ((i & 2) << 4);
    }

    /** MD/MDS-form mb/me field: ISA bits 21:26 stored as b0:4 || b5 (b5 at ISA bit 26). */
    private static int mdMb(int i) {
        return ((i >>> 6) & 0x1f) | (i & 0x20);
    }

    static void rldicl(CpuState s, int i) {
        logical(s, i, Long.rotateLeft(s.gpr[rt(i)], mdSh(i)) & mask(mdMb(i), 63));
    }

    static void rldicr(CpuState s, int i) {
        logical(s, i, Long.rotateLeft(s.gpr[rt(i)], mdSh(i)) & mask(0, mdMb(i)));
    }

    static void rldic(CpuState s, int i) {
        int sh = mdSh(i);
        logical(s, i, Long.rotateLeft(s.gpr[rt(i)], sh) & mask(mdMb(i), 63 - sh));
    }

    static void rldimi(CpuState s, int i) {
        int sh = mdSh(i);
        long m = mask(mdMb(i), 63 - sh);
        logical(s, i, (Long.rotateLeft(s.gpr[rt(i)], sh) & m) | (s.gpr[ra(i)] & ~m));
    }

    static void rldcl(CpuState s, int i) {
        int sh = (int) s.gpr[rb(i)] & 63;
        logical(s, i, Long.rotateLeft(s.gpr[rt(i)], sh) & mask(mdMb(i), 63));
    }

    static void rldcr(CpuState s, int i) {
        int sh = (int) s.gpr[rb(i)] & 63;
        logical(s, i, Long.rotateLeft(s.gpr[rt(i)], sh) & mask(0, mdMb(i)));
    }

    // ---------------------------------------------------------------- compare / select

    private static int cmpSigned(CpuState s, long a, long b) {
        return (a < b ? 8 : a > b ? 4 : 2) | s.so;
    }

    private static int cmpUnsigned(CpuState s, long a, long b) {
        int c = Long.compareUnsigned(a, b);
        return (c < 0 ? 8 : c > 0 ? 4 : 2) | s.so;
    }

    /** L bit of cmp/cmpl/cmpi/cmpli: ISA bit 10. */
    private static boolean lBit(int i) {
        return (i & 0x00200000) != 0;
    }

    static void cmpi(CpuState s, int i) {
        long a = s.gpr[ra(i)];
        if (!lBit(i)) {
            a = (int) a;
        }
        s.setCrField(bf(i), cmpSigned(s, a, si(i)));
    }

    static void cmpli(CpuState s, int i) {
        long a = s.gpr[ra(i)];
        if (!lBit(i)) {
            a &= 0xffffffffL;
        }
        s.setCrField(bf(i), cmpUnsigned(s, a, ui(i)));
    }

    static void cmp(CpuState s, int i) {
        long a = s.gpr[ra(i)];
        long b = s.gpr[rb(i)];
        if (!lBit(i)) {
            a = (int) a;
            b = (int) b;
        }
        s.setCrField(bf(i), cmpSigned(s, a, b));
    }

    static void cmpl(CpuState s, int i) {
        long a = s.gpr[ra(i)];
        long b = s.gpr[rb(i)];
        if (!lBit(i)) {
            a &= 0xffffffffL;
            b &= 0xffffffffL;
        }
        s.setCrField(bf(i), cmpUnsigned(s, a, b));
    }

    static void cmprb(CpuState s, int i) {
        int src1 = (int) s.gpr[ra(i)] & 0xff;
        long b = s.gpr[rb(i)];
        int lo1 = (int) b & 0xff, hi1 = (int) (b >>> 8) & 0xff;
        int lo2 = (int) (b >>> 16) & 0xff, hi2 = (int) (b >>> 24) & 0xff;
        boolean in = lo1 <= src1 && src1 <= hi1;
        if (lBit(i)) {
            in |= lo2 <= src1 && src1 <= hi2;
        }
        s.setCrField(bf(i), in ? 4 : 0);
    }

    static void cmpeqb(CpuState s, int i) {
        long src1 = s.gpr[ra(i)] & 0xff;
        long b = s.gpr[rb(i)];
        boolean m = false;
        for (int n = 0; n < 64; n += 8) {
            m |= ((b >>> n) & 0xff) == src1;
        }
        s.setCrField(bf(i), m ? 4 : 0);
    }

    static void setb(CpuState s, int i) {
        int f = s.getCrField(Fields.bfa(i));
        s.gpr[rt(i)] = (f & 8) != 0 ? -1L : (f & 4) != 0 ? 1 : 0;
    }

    static void mcrxrx(CpuState s, int i) {
        s.setCrField(bf(i), (s.ov << 3) | (s.ov32 << 2) | (s.ca << 1) | s.ca32);
    }

    static void mcrxr(CpuState s, int i) {
        s.setCrField(bf(i), (s.so << 3) | (s.ov << 2) | (s.ca << 1));
        s.so = 0;
        s.ov = 0;
        s.ca = 0;
    }

    static void isel(CpuState s, int i) {
        int bc = rc(i);
        boolean bit = ((s.cr >>> (31 - bc)) & 1) != 0;
        s.gpr[rt(i)] = bit ? raOr0(s, i) : s.gpr[rb(i)];
    }

    // ---------------------------------------------------------------- traps

    static boolean trapCond(int to, long a, long b) {
        return ((to & 0x10) != 0 && a < b)
                || ((to & 0x08) != 0 && a > b)
                || ((to & 0x04) != 0 && a == b)
                || ((to & 0x02) != 0 && Long.compareUnsigned(a, b) < 0)
                || ((to & 0x01) != 0 && Long.compareUnsigned(a, b) > 0);
    }

    static void twi(CpuState s, int i) {
        if (trapCondW(rt(i), (int) s.gpr[ra(i)], (int) si(i))) {
            throw PpcException.trap();
        }
    }

    /** Word traps compare 32-bit values; unsigned comparisons use the zero-extended words. */
    private static boolean trapCondW(int to, int a, int b) {
        return ((to & 0x10) != 0 && a < b)
                || ((to & 0x08) != 0 && a > b)
                || ((to & 0x04) != 0 && a == b)
                || ((to & 0x02) != 0 && Integer.compareUnsigned(a, b) < 0)
                || ((to & 0x01) != 0 && Integer.compareUnsigned(a, b) > 0);
    }

    static void tw(CpuState s, int i) {
        if (trapCondW(rt(i), (int) s.gpr[ra(i)], (int) s.gpr[rb(i)])) {
            throw PpcException.trap();
        }
    }

    static void tdi(CpuState s, int i) {
        if (trapCond(rt(i), s.gpr[ra(i)], si(i))) {
            throw PpcException.trap();
        }
    }

    static void td(CpuState s, int i) {
        if (trapCond(rt(i), s.gpr[ra(i)], s.gpr[rb(i)])) {
            throw PpcException.trap();
        }
    }

    // ---------------------------------------------------------------- darn

    private static final java.util.SplittableRandom DARN_SEED = new java.util.SplittableRandom();

    static void darn(CpuState s, int i) {
        int l = (i >>> 16) & 3;
        long r;
        if (l == 3) {
            r = -1L;
        } else {
            long v;
            synchronized (DARN_SEED) {
                v = DARN_SEED.nextLong();
            }
            r = l == 0 ? (v & 0xffffffffL) : v;
            if (r == -1L) {
                r = 0; // -1 is the "error" value; never return it for a successful read.
            }
        }
        s.gpr[rt(i)] = r;
    }
}
