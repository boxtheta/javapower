package org.boxtheta.javapower.cpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.boxtheta.javapower.core.PhysicalMemory;

/** A tiny assembler for test programs: the instructions the SMP tests need, with labels. */
final class Asm {
    private final List<Integer> words = new ArrayList<>();
    private final Map<String, Integer> labels = new HashMap<>();
    /** (word index, label, kind): 0 = I-form b, 1 = B-form bc. */
    private final List<Object[]> fixups = new ArrayList<>();

    int here() {
        return words.size() * 4;
    }

    Asm emit(int w) {
        words.add(w);
        return this;
    }

    Asm label(String l) {
        labels.put(l, here());
        return this;
    }

    // Branches.
    Asm b(String l) {
        fixups.add(new Object[] {words.size(), l, 0});
        return emit(18 << 26);
    }

    Asm bc(int bo, int bi, String l) {
        fixups.add(new Object[] {words.size(), l, 1});
        return emit((16 << 26) | (bo << 21) | (bi << 16));
    }

    Asm bne(String l) {
        return bc(4, 2, l);
    }

    Asm beq(String l) {
        return bc(12, 2, l);
    }

    Asm blt(String l) {
        return bc(12, 0, l);
    }

    Asm bge(String l) {
        return bc(4, 0, l);
    }

    // Integer.
    Asm addi(int rt, int ra, int si) {
        return emit((14 << 26) | (rt << 21) | (ra << 16) | (si & 0xffff));
    }

    Asm li(int rt, int si) {
        return addi(rt, 0, si);
    }

    Asm add(int rt, int ra, int rb) {
        return emit((31 << 26) | (rt << 21) | (ra << 16) | (rb << 11) | (266 << 1));
    }

    Asm or(int ra, int rs, int rb) {
        return emit((31 << 26) | (rs << 21) | (ra << 16) | (rb << 11) | (444 << 1));
    }

    Asm mr(int ra, int rs) {
        return or(ra, rs, rs);
    }

    Asm andiDot(int ra, int rs, int ui) {
        return emit((28 << 26) | (rs << 21) | (ra << 16) | (ui & 0xffff));
    }

    /** sldi ra,rs,n (rldicr ra,rs,n,63-n). */
    Asm sldi(int ra, int rs, int n) {
        int me = 63 - n;
        int mb6 = ((me & 31) << 1) | (me >>> 5);
        return emit((30 << 26) | (rs << 21) | (ra << 16) | ((n & 31) << 11) | (mb6 << 5) | (1 << 2) | ((n >>> 5) << 1));
    }

    Asm cmpwi(int ra, int si) {
        return emit((11 << 26) | (ra << 16) | (si & 0xffff));
    }

    Asm cmpdi(int ra, int si) {
        return emit((11 << 26) | (1 << 21) | (ra << 16) | (si & 0xffff));
    }

    Asm cmpd(int ra, int rb) {
        return emit((31 << 26) | (1 << 21) | (ra << 16) | (rb << 11));
    }

    Asm cmpld(int ra, int rb) {
        return emit((31 << 26) | (1 << 21) | (ra << 16) | (rb << 11) | (32 << 1));
    }

    // Loads and stores.
    Asm ld(int rt, int d, int ra) {
        return emit((58 << 26) | (rt << 21) | (ra << 16) | (d & 0xfffc));
    }

    Asm std(int rs, int d, int ra) {
        return emit((62 << 26) | (rs << 21) | (ra << 16) | (d & 0xfffc));
    }

    Asm lwz(int rt, int d, int ra) {
        return emit((32 << 26) | (rt << 21) | (ra << 16) | (d & 0xffff));
    }

    Asm stw(int rs, int d, int ra) {
        return emit((36 << 26) | (rs << 21) | (ra << 16) | (d & 0xffff));
    }

    private Asm x(int rt, int ra, int rb, int xo, int rc) {
        return emit((31 << 26) | (rt << 21) | (ra << 16) | (rb << 11) | (xo << 1) | rc);
    }

    Asm lbarx(int rt, int ra, int rb) {
        return x(rt, ra, rb, 52, 0);
    }

    Asm lharx(int rt, int ra, int rb) {
        return x(rt, ra, rb, 116, 0);
    }

    Asm lwarx(int rt, int ra, int rb) {
        return x(rt, ra, rb, 20, 0);
    }

    Asm ldarx(int rt, int ra, int rb) {
        return x(rt, ra, rb, 84, 0);
    }

    Asm lqarx(int rt, int ra, int rb) {
        return x(rt, ra, rb, 276, 0);
    }

    Asm stbcx(int rs, int ra, int rb) {
        return x(rs, ra, rb, 694, 1);
    }

    Asm sthcx(int rs, int ra, int rb) {
        return x(rs, ra, rb, 726, 1);
    }

    Asm stwcx(int rs, int ra, int rb) {
        return x(rs, ra, rb, 150, 1);
    }

    Asm stdcx(int rs, int ra, int rb) {
        return x(rs, ra, rb, 214, 1);
    }

    Asm stqcx(int rs, int ra, int rb) {
        return x(rs, ra, rb, 182, 1);
    }

    Asm stdbrx(int rs, int ra, int rb) {
        return x(rs, ra, rb, 660, 0);
    }

    /** ldat/lwat rt,ra,fc. */
    Asm ldat(int rt, int ra, int fc) {
        return x(rt, ra, fc, 614, 0);
    }

    Asm lwat(int rt, int ra, int fc) {
        return x(rt, ra, fc, 582, 0);
    }

    // System.
    Asm sc1() {
        return emit(0x44000022);
    }

    Asm isync() {
        return emit(0x4c00012c);
    }

    Asm sync() {
        return emit(0x7c0004ac);
    }

    Asm lwsync() {
        return emit(0x7c2004ac);
    }

    Asm ptesync() {
        return emit(0x7c4004ac);
    }

    Asm eieio() {
        return emit(0x7c0006ac);
    }

    Asm tlbsync() {
        return emit(0x7c00046c);
    }

    /** tlbie rb,rs with RIC=0, PRS=1, R=1 (radix, process scoped, single page). */
    Asm tlbie(int rb, int rs) {
        return emit((31 << 26) | (rs << 21) | (1 << 17) | (1 << 16) | (rb << 11) | (306 << 1));
    }

    Asm tlbiel(int rb, int rs) {
        return emit((31 << 26) | (rs << 21) | (1 << 17) | (1 << 16) | (rb << 11) | (274 << 1));
    }

    int[] assemble() {
        int[] w = new int[words.size()];
        for (int k = 0; k < w.length; k++) {
            w[k] = words.get(k);
        }
        for (Object[] f : fixups) {
            int at = (Integer) f[0];
            Integer target = labels.get((String) f[1]);
            if (target == null) {
                throw new IllegalArgumentException("undefined label " + f[1]);
            }
            int off = target - at * 4;
            w[at] |= (Integer) f[2] == 0 ? off & 0x03fffffc : off & 0xfffc;
        }
        return w;
    }

    /** Assemble and store at real address {@code at}, in little- or big-endian order. */
    long load(PhysicalMemory pm, long at, boolean le) {
        int[] w = assemble();
        for (int k = 0; k < w.length; k++) {
            if (le) {
                pm.write32le(at + 4L * k, w[k]);
            } else {
                pm.write32be(at + 4L * k, w[k]);
            }
        }
        return at;
    }
}
