package org.boxtheta.javapower.fpvec;

import static org.boxtheta.javapower.fpvec.Fpscr.toDouble;
import static org.boxtheta.javapower.fpvec.Fpscr.toSingle;
import static org.boxtheta.javapower.fpvec.V.*;

import java.util.ArrayList;
import java.util.List;
import org.boxtheta.javapower.core.CpuState;
import org.boxtheta.javapower.core.DecodeTable;
import org.boxtheta.javapower.core.Op;

/**
 * The VMX and VSX load and store handlers as they were before they took the MMU's fast path
 * ({@code GuestMemory.ramAddress}), kept verbatim as the reference for {@link VecMemDiff}: every
 * access goes through the {@code GuestMemory} accessors one doubleword (or element) at a time.
 */
final class LdStRef {
    private LdStRef() {}

    /** The reference forms, by mnemonic, in registration order. */
    static final List<DecodeTable.Entry> ENTRIES = new ArrayList<>();
    static final DecodeTable TABLE = new DecodeTable();

    private static void add(int mask, int match, String name, Op op) {
        TABLE.add(mask, match, name, op);
        ENTRIES.add(new DecodeTable.Entry(mask, match, name, op));
    }

    private static void x31(int xo, String name, Op op) {
        add(0xfc000000 | (0x3ff << 1), (31 << 26) | (xo << 1), name, op);
    }

    static {
        x31(103, "lvx", LdStRef::lvx);
        x31(359, "lvxl", LdStRef::lvx);
        x31(231, "stvx", LdStRef::stvx);
        x31(487, "stvxl", LdStRef::stvx);
        x31(7, "lvebx", (s, i) -> lve(s, i, 1));
        x31(39, "lvehx", (s, i) -> lve(s, i, 2));
        x31(71, "lvewx", (s, i) -> lve(s, i, 4));
        x31(135, "stvebx", (s, i) -> stve(s, i, 1));
        x31(167, "stvehx", (s, i) -> stve(s, i, 2));
        x31(199, "stvewx", (s, i) -> stve(s, i, 4));
        x31(6, "lvsl", (s, i) -> lvs(s, i, false));
        x31(38, "lvsr", (s, i) -> lvs(s, i, true));
        x31(588, "lxsdx", (s, i) -> lxs(s, i, 0));
        x31(76, "lxsiwax", (s, i) -> lxs(s, i, 1));
        x31(12, "lxsiwzx", (s, i) -> lxs(s, i, 2));
        x31(524, "lxsspx", (s, i) -> lxs(s, i, 3));
        x31(781, "lxsibzx", (s, i) -> lxs(s, i, 4));
        x31(813, "lxsihzx", (s, i) -> lxs(s, i, 5));
        x31(716, "stxsdx", (s, i) -> stxs(s, i, 0));
        x31(140, "stxsiwx", (s, i) -> stxs(s, i, 1));
        x31(652, "stxsspx", (s, i) -> stxs(s, i, 2));
        x31(909, "stxsibx", (s, i) -> stxs(s, i, 3));
        x31(941, "stxsihx", (s, i) -> stxs(s, i, 4));
        x31(844, "lxvd2x", (s, i) -> lxvElems(s, i, 0));
        x31(780, "lxvw4x", (s, i) -> lxvElems(s, i, 1));
        x31(812, "lxvh8x", (s, i) -> lxvElems(s, i, 2));
        x31(876, "lxvb16x", (s, i) -> lxvElems(s, i, 3));
        x31(972, "stxvd2x", (s, i) -> stxvElems(s, i, 0));
        x31(908, "stxvw4x", (s, i) -> stxvElems(s, i, 1));
        x31(940, "stxvh8x", (s, i) -> stxvElems(s, i, 2));
        x31(1004, "stxvb16x", (s, i) -> stxvElems(s, i, 3));
        x31(332, "lxvdsx", LdStRef::lxvdsx);
        x31(364, "lxvwsx", LdStRef::lxvwsx);
        x31(268, "lxvx", LdStRef::lxvx);
        x31(300, "lxvx", LdStRef::lxvx);
        x31(396, "stxvx", LdStRef::stxvx);
        x31(269, "lxvl", (s, i) -> lxvl(s, i, false));
        x31(301, "lxvll", (s, i) -> lxvl(s, i, true));
        x31(397, "stxvl", (s, i) -> stxvl(s, i, false));
        x31(429, "stxvll", (s, i) -> stxvl(s, i, true));
        add(0xfc000003, (57 << 26) | 2, "lxsd", LdStRef::lxsd);
        add(0xfc000003, (57 << 26) | 3, "lxssp", LdStRef::lxssp);
        add(0xfc000003, (61 << 26) | 2, "stxsd", LdStRef::stxsd);
        add(0xfc000003, (61 << 26) | 3, "stxssp", LdStRef::stxssp);
        add(0xfc000007, (61 << 26) | 1, "lxv", LdStRef::lxv);
        add(0xfc000007, (61 << 26) | 5, "stxv", LdStRef::stxv);
    }

    static int xt(int i) { return ((i >>> 21) & 31) | ((i & 1) << 5); }

    static void checkVsxOrVec(CpuState s, int reg) {
        if (reg < 32) V.checkVsx(s); else V.checkVec(s);
    }

    static byte[] toBytes(long hi, long lo) {
        byte[] r = new byte[16];
        for (int i = 0; i < 8; i++) {
            r[i] = (byte) (hi >>> (56 - 8 * i));
            r[8 + i] = (byte) (lo >>> (56 - 8 * i));
        }
        return r;
    }

    static long fromBytes(byte[] b, int off) {
        long v = 0;
        for (int i = 0; i < 8; i++) v = (v << 8) | (b[off + i] & 0xff);
        return v;
    }

    // ---- VmxInsns

    static long ea(CpuState s, int insn) {
        int a = ra(insn);
        long ea = (a == 0 ? 0 : s.gpr[a]) + s.gpr[rb(insn)];
        return s.is64() ? ea : ea & 0xffffffffL;
    }

    static void lvx(CpuState s, int insn) {
        checkVec(s);
        long ea = ea(s, insn) & ~15L;
        long hi, lo;
        if (s.littleEndian()) {
            lo = s.mem.read64(ea);
            hi = s.mem.read64(ea + 8);
        } else {
            hi = s.mem.read64(ea);
            lo = s.mem.read64(ea + 8);
        }
        set(s, 32 + rt(insn), hi, lo);
    }

    static void stvx(CpuState s, int insn) {
        checkVec(s);
        long ea = ea(s, insn) & ~15L;
        int t = 32 + rt(insn);
        long hi = s.vsrHi[t], lo = s.vsrLo[t];
        s.mem.translateData(ea + 15, true);
        if (s.littleEndian()) {
            s.mem.write64(ea, lo);
            s.mem.write64(ea + 8, hi);
        } else {
            s.mem.write64(ea, hi);
            s.mem.write64(ea + 8, lo);
        }
    }

    static void lve(CpuState s, int insn, int size) {
        checkVec(s);
        long ea = ea(s, insn) & ~(long) (size - 1);
        int n = 16 / size;
        int index = (int) (ea & 15) / size;
        if (s.littleEndian()) index = n - 1 - index;
        long v = switch (size) {
            case 1 -> s.mem.read8(ea);
            case 2 -> s.mem.read16(ea);
            default -> s.mem.read32(ea) & 0xffffffffL;
        };
        int t = 32 + rt(insn);
        long sh = shift(size, index);
        long m = mask(size) << sh;
        if (inHi(size, index)) s.vsrHi[t] = (s.vsrHi[t] & ~m) | (v << sh);
        else s.vsrLo[t] = (s.vsrLo[t] & ~m) | (v << sh);
    }

    static void stve(CpuState s, int insn, int size) {
        checkVec(s);
        long ea = ea(s, insn) & ~(long) (size - 1);
        int n = 16 / size;
        int index = (int) (ea & 15) / size;
        if (s.littleEndian()) index = n - 1 - index;
        int t = 32 + rt(insn);
        long v = get(s.vsrHi[t], s.vsrLo[t], size, index);
        switch (size) {
            case 1 -> s.mem.write8(ea, (int) v);
            case 2 -> s.mem.write16(ea, (int) v);
            default -> s.mem.write32(ea, (int) v);
        }
    }

    static void lvs(CpuState s, int insn, boolean right) {
        checkVec(s);
        long sh = (ea(s, insn) & 15) * 0x0101010101010101L;
        if (right) {
            set(s, 32 + rt(insn), 0x1011121314151617L - sh, 0x18191a1b1c1d1e1fL - sh);
        } else {
            set(s, 32 + rt(insn), 0x0001020304050607L + sh, 0x08090a0b0c0d0e0fL + sh);
        }
    }

    // ---- VsxInsns

    static long eaX(CpuState s, int insn) {
        int a = V.ra(insn);
        long ea = (a == 0 ? 0 : s.gpr[a]) + s.gpr[V.rb(insn)];
        return s.is64() ? ea : ea & 0xffffffffL;
    }

    static long eaRa(CpuState s, int insn) {
        int a = V.ra(insn);
        long ea = a == 0 ? 0 : s.gpr[a];
        return s.is64() ? ea : ea & 0xffffffffL;
    }

    static long eaDisp(CpuState s, int insn, long disp) {
        int a = V.ra(insn);
        long ea = (a == 0 ? 0 : s.gpr[a]) + disp;
        return s.is64() ? ea : ea & 0xffffffffL;
    }

    static long addr(CpuState s, long ea, long n) {
        long r = ea + n;
        return s.is64() ? r : r & 0xffffffffL;
    }

    /** Fault (DSI) before any byte is written if either end of the access is not writable. */
    static void probeStore(CpuState s, long ea, int len) {
        s.mem.translateData(ea, true);
        s.mem.translateData(addr(s, ea, len - 1), true);
    }

    /** Full 16-byte load in the mode's byte order (lxv, lxvx, lvx-style). */
    static void ld128(CpuState s, int t, long ea) {
        long hi, lo;
        if (s.littleEndian()) {
            lo = s.mem.read64(ea);
            hi = s.mem.read64(addr(s, ea, 8));
        } else {
            hi = s.mem.read64(ea);
            lo = s.mem.read64(addr(s, ea, 8));
        }
        V.set(s, t, hi, lo);
    }

    static void st128(CpuState s, int t, long ea) {
        probeStore(s, ea, 16);
        long hi = s.vsrHi[t], lo = s.vsrLo[t];
        if (s.littleEndian()) {
            s.mem.write64(ea, lo);
            s.mem.write64(addr(s, ea, 8), hi);
        } else {
            s.mem.write64(ea, hi);
            s.mem.write64(addr(s, ea, 8), lo);
        }
    }

    /** Big-endian doubleword access regardless of MSR[LE]. */
    static long readBE64(CpuState s, long ea) {
        long v = s.mem.read64(ea);
        return s.littleEndian() ? Long.reverseBytes(v) : v;
    }

    static void writeBE64(CpuState s, long ea, long v) {
        s.mem.write64(ea, s.littleEndian() ? Long.reverseBytes(v) : v);
    }

    /** lxvd2x (0), lxvw4x (1), lxvh8x (2), lxvb16x (3). */
    static void lxvElems(CpuState s, int insn, int kind) {
        checkVsx(s);
        long ea = eaX(s, insn);
        long hi, lo;
        switch (kind) {
            case 0 -> {
                hi = s.mem.read64(ea);
                lo = s.mem.read64(addr(s, ea, 8));
            }
            case 1 -> {
                hi = s.mem.read64(ea);
                lo = s.mem.read64(addr(s, ea, 8));
                if (s.littleEndian()) {
                    hi = (hi >>> 32) | (hi << 32);
                    lo = (lo >>> 32) | (lo << 32);
                }
            }
            case 2 -> {
                hi = readBE64(s, ea);
                lo = readBE64(s, addr(s, ea, 8));
                if (s.littleEndian()) {
                    hi = V.bswapElems(hi, 2);
                    lo = V.bswapElems(lo, 2);
                }
            }
            default -> {
                hi = readBE64(s, ea);
                lo = readBE64(s, addr(s, ea, 8));
            }
        }
        V.set(s, xt(insn), hi, lo);
    }

    static void stxvElems(CpuState s, int insn, int kind) {
        checkVsx(s);
        long ea = eaX(s, insn);
        int t = xt(insn);
        long hi = s.vsrHi[t], lo = s.vsrLo[t];
        probeStore(s, ea, 16);
        switch (kind) {
            case 0 -> {
                s.mem.write64(ea, hi);
                s.mem.write64(addr(s, ea, 8), lo);
            }
            case 1 -> {
                if (s.littleEndian()) {
                    hi = (hi >>> 32) | (hi << 32);
                    lo = (lo >>> 32) | (lo << 32);
                }
                s.mem.write64(ea, hi);
                s.mem.write64(addr(s, ea, 8), lo);
            }
            case 2 -> {
                if (s.littleEndian()) {
                    hi = V.bswapElems(hi, 2);
                    lo = V.bswapElems(lo, 2);
                }
                writeBE64(s, ea, hi);
                writeBE64(s, addr(s, ea, 8), lo);
            }
            default -> {
                writeBE64(s, ea, hi);
                writeBE64(s, addr(s, ea, 8), lo);
            }
        }
    }

    /** Scalar loads into dw0 (dw1 untouched, as QEMU): kind 0 lxsdx, 1 lxsiwax, 2 lxsiwzx, 3 lxsspx, 4 lxsibzx, 5 lxsihzx. */
    static void lxs(CpuState s, int insn, int kind) {
        checkVsx(s);
        long ea = eaX(s, insn);
        long v = switch (kind) {
            case 0 -> s.mem.read64(ea);
            case 1 -> (long) s.mem.read32(ea);
            case 2 -> s.mem.read32(ea) & 0xffffffffL;
            case 3 -> toDouble(s.mem.read32(ea));
            case 4 -> s.mem.read8(ea);
            default -> s.mem.read16(ea);
        };
        s.vsrHi[xt(insn)] = v;
    }

    /** Scalar stores from dw0: kind 0 stxsdx, 1 stxsiwx, 2 stxsspx, 3 stxsibx, 4 stxsihx. */
    static void stxs(CpuState s, int insn, int kind) {
        checkVsx(s);
        long ea = eaX(s, insn);
        long v = s.vsrHi[xt(insn)];
        switch (kind) {
            case 0 -> s.mem.write64(ea, v);
            case 1 -> s.mem.write32(ea, (int) v);
            case 2 -> s.mem.write32(ea, toSingle(v));
            case 3 -> s.mem.write8(ea, (int) v);
            default -> s.mem.write16(ea, (int) v);
        }
    }

    static void lxvdsx(CpuState s, int insn) {
        checkVsx(s);
        long v = s.mem.read64(eaX(s, insn));
        V.set(s, xt(insn), v, v);
    }

    static void lxvwsx(CpuState s, int insn) {
        int t = xt(insn);
        checkVsxOrVec(s, t);
        long w = s.mem.read32(eaX(s, insn)) & 0xffffffffL;
        long v = (w << 32) | w;
        V.set(s, t, v, v);
    }

    static void lxvx(CpuState s, int insn) {
        int t = xt(insn);
        checkVsxOrVec(s, t);
        ld128(s, t, eaX(s, insn));
    }

    static void stxvx(CpuState s, int insn) {
        int t = xt(insn);
        checkVsxOrVec(s, t);
        st128(s, t, eaX(s, insn));
    }

    /** lxvl / lxvll: load GPR[RB] bits 0:7 bytes (at most 16). */
    static void lxvl(CpuState s, int insn, boolean leftJustified) {
        int t = xt(insn);
        checkVsxOrVec(s, t);
        long ea = eaRa(s, insn);
        int nb = (int) (s.gpr[V.rb(insn)] >>> 56) & 0xff;
        byte[] r = new byte[16];
        if (nb != 0) {
            nb = Math.min(nb, 16);
            boolean le = s.littleEndian() && !leftJustified;
            for (int i = 0; i < nb; i++) {
                int idx = le ? 15 - i : i;
                r[idx] = (byte) s.mem.read8(ea);
                ea = addr(s, ea, 1);
            }
        }
        V.set(s, t, fromBytes(r, 0), fromBytes(r, 8));
    }

    static void stxvl(CpuState s, int insn, boolean leftJustified) {
        int t = xt(insn);
        checkVsxOrVec(s, t);
        long ea = eaRa(s, insn);
        int nb = (int) (s.gpr[V.rb(insn)] >>> 56) & 0xff;
        if (nb == 0) return;
        nb = Math.min(nb, 16);
        byte[] r = toBytes(s.vsrHi[t], s.vsrLo[t]);
        boolean le = s.littleEndian() && !leftJustified;
        for (int i = 0; i < nb; i++) {
            int idx = le ? 15 - i : i;
            s.mem.write8(ea, r[idx]);
            ea = addr(s, ea, 1);
        }
    }

    // DS/DQ forms (primary 57 / 61)
    static void lxsd(CpuState s, int insn) {
        checkVsx(s);
        long ea = eaDisp(s, insn, (short) (insn & ~3));
        V.set(s, 32 + V.rt(insn), s.mem.read64(ea), 0);
    }

    static void lxssp(CpuState s, int insn) {
        V.checkVec(s);
        long ea = eaDisp(s, insn, (short) (insn & ~3));
        V.set(s, 32 + V.rt(insn), toDouble(s.mem.read32(ea)), 0);
    }

    static void stxsd(CpuState s, int insn) {
        V.checkVec(s);
        long ea = eaDisp(s, insn, (short) (insn & ~3));
        s.mem.write64(ea, s.vsrHi[32 + V.rt(insn)]);
    }

    static void stxssp(CpuState s, int insn) {
        V.checkVec(s);
        long ea = eaDisp(s, insn, (short) (insn & ~3));
        s.mem.write32(ea, toSingle(s.vsrHi[32 + V.rt(insn)]));
    }

    static int dqT(int insn) {
        return ((insn >>> 21) & 31) | (((insn >>> 3) & 1) << 5);
    }

    static void lxv(CpuState s, int insn) {
        int t = dqT(insn);
        checkVsxOrVec(s, t);
        ld128(s, t, eaDisp(s, insn, (short) (insn & ~15)));
    }

    static void stxv(CpuState s, int insn) {
        int t = dqT(insn);
        checkVsxOrVec(s, t);
        st128(s, t, eaDisp(s, insn, (short) (insn & ~15)));
    }
}
