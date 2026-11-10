"""Integer loads and stores in real mode: every form, both endiannesses, 64- and 32-bit mode.

Effective addresses point into the 512-byte window WIN..WIN+0x200, which straddles the 4 KiB
boundary at 0x300000, so page-crossing accesses are covered.
"""
import random

from gen import Case, MSR64LE, MSR64BE, MSR32LE, MSR32BE, WIN, xform

M64 = (1 << 64) - 1
MID = WIN + 0x100  # 0x300000, the page boundary


def rand64(rng):
    return rng.choice([0, M64, 0x8000000000000000, 0x7F, 0x80, 0xFF80, 0x8000, 0x80000000,
                       rng.getrandbits(64), rng.getrandbits(64)])


LOADS_D = [("lbz", 1), ("lhz", 2), ("lha", 2), ("lwz", 4)]
LOADS_DU = [("lbzu", 1), ("lhzu", 2), ("lhau", 2), ("lwzu", 4)]
STORES_D = [("stb", 1), ("sth", 2), ("stw", 4)]
STORES_DU = [("stbu", 1), ("sthu", 2), ("stwu", 4)]
LOADS_DS = [("ld", 8), ("lwa", 4)]
LOADS_X = [("lbzx", 1), ("lhzx", 2), ("lhax", 2), ("lwzx", 4), ("lwax", 4), ("ldx", 8),
           ("lhbrx", 2), ("lwbrx", 4), ("ldbrx", 8)]
LOADS_XU = [("lbzux", 1), ("lhzux", 2), ("lhaux", 2), ("lwzux", 4), ("lwaux", 4), ("ldux", 8)]
STORES_X = [("stbx", 1), ("sthx", 2), ("stwx", 4), ("stdx", 8), ("sthbrx", 2), ("stwbrx", 4),
            ("stdbrx", 8)]
STORES_XU = [("stbux", 1), ("sthux", 2), ("stwux", 4), ("stdux", 8)]


class G:
    def __init__(self, prefix, msr, rng, scale):
        self.prefix, self.msr, self.rng, self.scale = prefix, msr, rng, scale
        self.n = 0
        self.out = []
        self.hi = 0xFFFFFFFF00000000 if (msr >> 63) == 0 else 0

    def case(self, mn, code, regs, xer=0):
        self.n += 1
        c = Case("%s%s-%d" % (self.prefix, mn, self.n), code, msr=self.msr)
        for k, v in regs.items():
            c.r(k, v)
        c.xer = xer
        c.cr = self.rng.getrandbits(32)
        self.out.append(c)
        return c

    def k(self, n):
        return max(3, int(n * self.scale))

    def addr(self, size):
        """A base address in the window such that base+size fits, biased to the page boundary."""
        rng = self.rng
        if rng.random() < 0.4:
            a = MID - rng.randint(0, size)
        else:
            a = WIN + rng.randint(0, 0x200 - size)
        return a

    def junk(self, a):
        """In 32-bit mode, high register bits are ignored by EA computation."""
        return a | (self.hi if self.rng.random() < 0.5 else 0)


def gen_basic(g):
    rng = g.rng
    for mn, sz in LOADS_D + STORES_D:
        for _ in range(g.k(24)):
            a = g.addr(sz)
            d = rng.randint(-0x80, 0x80)
            g.case(mn, ["%s 3, %d(4)" % (mn, d)], {3: rand64(rng), 4: g.junk(a - d)})
        # RA=0: EA = D (low memory, zero in both worlds).
        g.case(mn, ["%s 3, 0x2000(0)" % mn], {})
    for mn, sz in LOADS_DU + STORES_DU:
        for _ in range(g.k(12)):
            a = g.addr(sz)
            d = rng.randint(-0x80, 0x80)
            g.case(mn, ["%s 3, %d(4)" % (mn, d)], {3: rand64(rng), 4: g.junk(a - d)})
        g.case(mn, ["%s 3, 0(0)" % mn], {})       # RA=0: invalid
        g.case(mn, ["%s 4, 0(4)" % mn], {4: MID})  # RA=RT: invalid for loads
    for mn, sz in LOADS_DS + [("std", 8)]:
        for _ in range(g.k(24)):
            a = g.addr(sz)
            d = rng.randint(-0x20, 0x20) * 4
            g.case(mn, ["%s 3, %d(4)" % (mn, d)], {3: rand64(rng), 4: g.junk(a - d)})
    for mn, sz in [("ldu", 8), ("stdu", 8)]:
        for _ in range(g.k(12)):
            a = g.addr(sz)
            d = rng.randint(-0x20, 0x20) * 4
            g.case(mn, ["%s 3, %d(4)" % (mn, d)], {3: rand64(rng), 4: g.junk(a - d)})
        g.case(mn, ["%s 3, 0(0)" % mn], {})
        g.case(mn, ["%s 4, 0(4)" % mn], {4: MID})
    for mn, sz in LOADS_X + STORES_X:
        for _ in range(g.k(20)):
            a = g.addr(sz)
            ra = rng.choice([0, 4, 4, 4])
            if ra == 0:
                regs = {3: rand64(rng), 5: g.junk(a)}
            else:
                b = rng.randint(-0x100, 0x100)
                regs = {3: rand64(rng), 4: g.junk(a - b), 5: b & M64}
            g.case(mn, ["%s 3, %d, 5" % (mn, ra)], regs)
    for mn, sz in LOADS_XU + STORES_XU:
        for _ in range(g.k(10)):
            a = g.addr(sz)
            b = rng.randint(-0x100, 0x100)
            g.case(mn, ["%s 3, 4, 5" % mn], {3: rand64(rng), 4: g.junk(a - b), 5: b & M64})
        g.case(mn, ["%s 3, 0, 5" % mn], {5: MID})
        g.case(mn, ["%s 4, 4, 5" % mn], {4: MID, 5: 0})
    # Store then load back through the same address.
    for _ in range(g.k(10)):
        a = g.addr(8)
        g.case("stld", ["std 3, 0(4)", "lwz 6, 0(4)", "lhz 7, 2(4)", "lbz 8, 7(4)"], {3: rand64(rng), 4: a})


def gen_multiple(g):
    rng = g.rng
    for _ in range(g.k(10)):
        rt = rng.randint(1, 31)
        a = g.addr(4 * (32 - rt))
        a -= a % 4 if rng.random() < 0.7 else 0
        g.case("lmw", ["lmw %d, 0(4)" % rt], {4: g.junk(a)})
        g.case("stmw", ["stmw %d, 0(4)" % rt], {4: g.junk(a)})
    g.case("lmw", ["lmw 3, 0(4)"], {4: MID - 4})  # RA in range: invalid form
    g.case("lmw", ["lmw 31, 3(4)"], {4: MID - 4})
    for _ in range(g.k(16)):
        nb = rng.randint(0, 31)
        rt = rng.randint(0, 31)
        a = g.addr(32)
        ra = rng.choice([0, 4]) if a < 0x10000 else 4
        nregs = ((nb or 32) + 3) // 4
        regs_used = [(rt + i) % 32 for i in range(nregs)]
        if 4 in regs_used:
            continue
        g.case("lswi", [xform(31, 597, rt, 4, nb)], {4: g.junk(a)})
        g.case("stswi", [xform(31, 725, rt, 4, nb)], {4: g.junk(a)})
    for _ in range(g.k(16)):
        n = rng.randint(0, 127)
        rt = rng.randint(6, 31)
        a = WIN + rng.randint(0, 0x200 - max(n, 1))
        regs_used = [(rt + i) % 32 for i in range((n + 3) // 4)]
        if 4 in regs_used or 5 in regs_used:
            continue
        b = rng.randint(0, 16)
        g.case("lswx", [xform(31, 533, rt, 4, 5)], {4: g.junk(a - b), 5: b}, xer=n | (rng.choice([0, 1 << 29])))
        g.case("stswx", [xform(31, 661, rt, 4, 5)], {4: g.junk(a - b), 5: b}, xer=n)
    # Invalid forms: target range covering RA (and RB for lswx).
    g.case("lswi", [xform(31, 597, 3, 4, 8)], {4: MID})
    g.case("lswi", [xform(31, 597, 30, 4, 0)], {4: MID})
    g.case("lswi", [xform(31, 597, 3, 0, 4)], {})
    g.case("lswx", [xform(31, 533, 3, 4, 5)], {4: MID, 5: 0}, xer=8)
    g.case("lswx", [xform(31, 533, 3, 6, 5)], {6: MID, 5: 0}, xer=12)
    g.case("lswx", [xform(31, 533, 3, 6, 5)], {6: MID, 5: 0}, xer=0)


def gen_quad(g):
    rng = g.rng
    for _ in range(g.k(12)):
        a = WIN + 16 * rng.randint(0, 0x1F)
        if rng.random() < 0.2:
            a += rng.choice([4, 8])
        d = 16 * rng.randint(-4, 4)
        g.case("lq", ["lq 6, %d(4)" % d], {4: g.junk(a - d)})
        g.case("stq", ["stq 6, %d(4)" % (d & ~3)], {4: g.junk(a - (d & ~3)), 6: rand64(rng), 7: rand64(rng)})
    g.case("lq", [0xE0000000 | (5 << 21) | (4 << 16)], {4: MID})  # odd RTp: invalid
    g.case("lq", ["lq 4, 0(4)"], {4: MID})                       # RTp == RA: invalid
    g.case("stq", [0xF8000002 | (5 << 21) | (4 << 16)], {4: MID})  # odd RSp


def gen_atomic(g):
    rng = g.rng
    for mn, sz, st in [("lbarx", 1, "stbcx."), ("lharx", 2, "sthcx."), ("lwarx", 4, "stwcx."),
                       ("ldarx", 8, "stdcx.")]:
        for _ in range(g.k(10)):
            a = g.addr(sz)
            a -= a % sz
            # Success: same address.
            g.case(mn, ["%s 3, 0, 4" % mn, "%s 6, 0, 4" % st], {4: g.junk(a), 6: rand64(rng)})
            # Different address, or an intervening store of a different value.
            g.case(mn, ["%s 3, 0, 4" % mn, "%s 6, 0, 5" % st], {4: a, 5: a ^ (sz * 4), 6: rand64(rng)})
            g.case(mn, ["%s 3, 0, 4" % mn, "addi 7, 3, 1", "stb 7, 0(4)", "%s 6, 0, 4" % st],
                   {4: a, 6: rand64(rng)})
            # Intervening store of the same value (QEMU compares values: succeeds).
            g.case(mn, ["%s 3, 0, 4" % mn, "stb 3, 0(4)", "%s 6, 0, 4" % st] if sz == 1 else
                   ["%s 3, 0, 4" % mn, "%s 3, 0, 5" % mn, "%s 6, 0, 4" % st], {4: a, 5: a, 6: rand64(rng)})
            # stcx. with no reservation; unaligned larx/stcx.
            g.case(mn, ["%s 6, 0, 4" % st], {4: a, 6: rand64(rng)}, xer=rng.choice([0, 1 << 31]))
            if sz > 1:
                g.case(mn, ["%s 3, 0, 4" % mn], {4: a + 1})
                g.case(mn, ["%s 3, 0, 4" % mn, "%s 6, 0, 5" % st], {4: a, 5: a + 1})
        # Reservation of another size.
        a = MID - 16
        other = {"stbcx.": "sthcx.", "sthcx.": "stwcx.", "stwcx.": "stdcx.", "stdcx.": "stwcx."}[st]
        g.case(mn, ["%s 3, 0, 4" % mn, "%s 6, 0, 4" % other], {4: a})
        # Interrupt between larx and stcx. clears the reservation: sc lands in a pad and the
        # harness ends the case, so instead use a trap that is not taken.
        g.case(mn, ["%s 3, 0, 4" % mn, "tw 0, 0, 0", "%s 6, 0, 4" % st], {4: a})
    for _ in range(g.k(6)):
        a = WIN + 16 * rng.randint(0, 0x1F)
        g.case("lqarx", ["lqarx 6, 0, 4", "stqcx. 8, 0, 4"], {4: g.junk(a), 8: rand64(rng), 9: rand64(rng)})
        g.case("lqarx", ["lqarx 6, 0, 4", "stqcx. 8, 0, 5"], {4: a, 5: a ^ 16})
    g.case("lqarx", ["lqarx 6, 0, 4"], {4: MID + 8})
    g.case("lqarx", [xform(31, 276, 7, 0, 4)], {4: MID})  # odd RTp
    g.case("lqarx", [xform(31, 276, 4, 0, 4)], {4: MID})  # RTp == RB
    for mn, sz in [("lwat", 4), ("ldat", 8)]:
        for fc in list(range(0, 9)) + [16, 24, 25, 28, 9, 31]:
            for _ in range(2):
                a = MID - sz * rng.randint(0, 4)
                g.case(mn, ["%s 6, 4, %d" % (mn, fc)], {4: g.junk(a), 7: rand64(rng), 8: rand64(rng)})
        g.case(mn, ["%s 6, 4, 0" % mn], {4: MID + 2})
    for mn, sz in [("stwat", 4), ("stdat", 8)]:
        for fc in list(range(0, 8)) + [24, 9, 31]:
            for _ in range(2):
                a = MID - sz * rng.randint(0, 4)
                g.case(mn, ["%s 6, 4, %d" % (mn, fc)], {4: g.junk(a), 6: rand64(rng)})
        g.case(mn, ["%s 6, 4, 0" % mn], {4: MID + 2})


def gen_cache(g):
    rng = g.rng
    for _ in range(g.k(8)):
        a = WIN + rng.randint(0, 0x1FF)
        g.case("dcbz", ["dcbz 4, 5"], {4: g.junk(a - 8), 5: 8})
        g.case("dcbz", ["dcbz 0, 5"], {5: g.junk(a)})
    for mn in ["dcbt 0, 4", "dcbtst 0, 4", "dcbst 0, 4", "dcbf 0, 4", "dcbf 0, 4, 1", "dcbf 0, 4, 3",
               "icbi 0, 4", "icbt 0, 0, 4", "sync", "lwsync", "ptesync", "eieio", "isync",
               "dcbt 0, 4, 16", "dcbt 0, 4, 8", "dcbtst 0, 4, 16"]:
        g.case(mn.split()[0], [mn], {4: MID})
    for xo in [853, 821, 789, 885, 981, 949, 917, 1013]:  # cache-inhibited forms: hypervisor only
        g.case("ldstci", [xform(31, xo, 3, 4, 5)], {4: MID, 5: 0})
    g.case("dcba", [xform(31, 758, 0, 0, 4)], {4: MID})
    g.case("dcbi", [xform(31, 470, 0, 0, 4)], {4: MID})


def ldst():
    out = []
    for prefix, msr, scale, seed in [("", MSR64LE, 1.0, 31), ("be", MSR64BE, 0.6, 32),
                                     ("w", MSR32LE, 0.4, 33), ("wbe", MSR32BE, 0.4, 34)]:
        g = G(prefix, msr, random.Random(seed), scale)
        gen_basic(g)
        gen_multiple(g)
        gen_quad(g)
        gen_atomic(g)
        gen_cache(g)
        out += g.out
    return out


GROUPS = {"ldst": ldst}
