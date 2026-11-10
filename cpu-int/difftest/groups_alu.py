"""Fixed-point arithmetic, logical, rotate/shift, compare, trap and ISA 3.0 additions.

Groups: alu (64-bit mode, little-endian, plus a big-endian sample) and alu32 (MSR[SF]=0).
"""
import random

from gen import Case, MSR64LE, MSR64BE, MSR32LE, MSR32BE, xform

M64 = (1 << 64) - 1

EDGE = [0, 1, 2, 3, 0x7F, 0x80, 0xFF, 0x7FFF, 0x8000, 0xFFFF, 0x7FFFFFFF, 0x80000000, 0x80000001,
        0xFFFFFFFF, 0x100000000, 0x1FFFFFFFF, 0x7FFFFFFFFFFFFFFF, 0x8000000000000000,
        0x8000000000000001, 0xFFFFFFFFFFFFFFFF, 0xFFFFFFFFFFFFFFFE, 0xFFFFFFFF80000000,
        0xFFFFFFFF7FFFFFFF, 0x00000000FFFFFFFE, 0x123456789ABCDEF0, 0xFEDCBA9876543210,
        0x0000000080000000, 0xFFFFFFFF00000000, 0x5555555555555555, 0xAAAAAAAAAAAAAAAA]

# XER: SO=bit 31, OV=30, CA=29, OV32=19, CA32=18 (as returned by mfxer); byte count 0..127.
XER_BITS = [0, 1 << 31, 1 << 30, 1 << 29, 1 << 19, 1 << 18]


def rand_xer(rng):
    v = 0
    for b in XER_BITS[1:]:
        if rng.random() < 0.4:
            v |= b
    return v


def rand64(rng):
    k = rng.random()
    if k < 0.3:
        return rng.choice(EDGE)
    if k < 0.45:
        return (rng.choice(EDGE) + rng.randint(-3, 3)) & M64
    if k < 0.6:
        return rng.getrandbits(32) | (rng.choice([0, M64 ^ 0xFFFFFFFF]))
    if k < 0.7:
        return rng.getrandbits(rng.randint(1, 16))
    return rng.getrandbits(64)


def operand_pairs(rng, n):
    out = []
    for a in [0, 1, 0x7FFFFFFF, 0x80000000, 0xFFFFFFFF, 0x7FFFFFFFFFFFFFFF, 0x8000000000000000,
              0xFFFFFFFFFFFFFFFF]:
        for b in [0, 1, 0xFFFFFFFF, 0x80000000, 0xFFFFFFFFFFFFFFFF, 0x8000000000000000]:
            out.append((a, b))
    while len(out) < n:
        out.append((rand64(rng), rand64(rng)))
    return out


class Gen:
    def __init__(self, prefix, msr, scale):
        self.prefix = prefix
        self.msr = msr
        self.scale = scale
        self.n = 0
        self.cases = []

    def case(self, mn, code, regs, xer=0, cr=0, msr=None):
        self.n += 1
        c = Case("%s%s-%d" % (self.prefix, mn, self.n), code, msr=self.msr if msr is None else msr)
        for k, v in regs.items():
            c.r(k, v)
        c.xer = xer
        c.cr = cr
        self.cases.append(c)
        return c

    def count(self, n):
        return max(4, int(n * self.scale))


def suffixes(oe, rc):
    s = [""]
    if oe:
        s = s + ["o"]
    if rc:
        s = s + [x + "." for x in s]
    return s


def gen_arith(g, rng):
    xo3 = ["add", "addc", "adde", "subf", "subfc", "subfe", "mullw", "mulld", "divw", "divwu", "divd",
           "divdu", "divwe", "divweu", "divde", "divdeu"]
    for mn in xo3:
        for sfx in suffixes(True, True):
            for a, b in operand_pairs(rng, g.count(40 if sfx == "" else 14)):
                if mn in ("divwe", "divweu", "divde", "divdeu") and rng.random() < 0.5:
                    b = (b >> rng.randint(0, 62)) | 1
                g.case(mn, ["%s%s 3, 4, 5" % (mn, sfx)], {4: a, 5: b}, xer=rand_xer(rng), cr=rng.getrandbits(32))
    for mn in ["addme", "addze", "subfme", "subfze", "neg"]:
        for sfx in suffixes(True, True):
            for a, _ in operand_pairs(rng, g.count(20)):
                g.case(mn, ["%s%s 3, 4" % (mn, sfx)], {4: a}, xer=rand_xer(rng), cr=rng.getrandbits(32))
    for mn in ["mulhw", "mulhwu", "mulhd", "mulhdu"]:
        for sfx in ["", "."]:
            for a, b in operand_pairs(rng, g.count(30)):
                g.case(mn, ["%s%s 3, 4, 5" % (mn, sfx)], {4: a, 5: b}, xer=rand_xer(rng), cr=rng.getrandbits(32))
    for mn in ["modsw", "moduw", "modsd", "modud"]:
        for a, b in operand_pairs(rng, g.count(50)):
            g.case(mn, ["%s 3, 4, 5" % mn], {4: a, 5: b})
    for mn in ["maddhd", "maddhdu", "maddld"]:
        for k in range(g.count(50)):
            a, b = rand64(rng), rand64(rng)
            g.case(mn, ["%s 3, 4, 5, 6" % mn], {4: a, 5: b, 6: rand64(rng)})
    for k in range(g.count(60)):
        a, b = operand_pairs(rng, 60)[k % 60] if k < 48 else (rand64(rng), rand64(rng))
        g.case("addex", ["addex 3, 4, 5, 0"], {4: a, 5: b}, xer=rand_xer(rng))
    for k in range(g.count(40)):
        g.case("addg6s", ["addg6s 3, 4, 5"], {4: rand64(rng), 5: rand64(rng)})
    # Immediate forms.
    for k in range(g.count(60)):
        a = rand64(rng)
        si = rng.choice([0, 1, -1, 0x7FFF, -0x8000, rng.randint(-0x8000, 0x7FFF)])
        ra = rng.choice([0, 4])
        g.case("addi", ["addi 3, %d, %d" % (ra, si)], {4: a})
        g.case("addis", ["addis 3, %d, %d" % (ra, si)], {4: a})
        g.case("addic", ["addic 3, 4, %d" % si], {4: a}, xer=rand_xer(rng))
        g.case("addic.", ["addic. 3, 4, %d" % si], {4: a}, xer=rand_xer(rng))
        g.case("subfic", ["subfic 3, 4, %d" % si], {4: a}, xer=rand_xer(rng))
        g.case("mulli", ["mulli 3, 4, %d" % si], {4: a})
    # Same register as source and target.
    for mn in ["add", "subf", "mullw", "divd"]:
        g.case(mn, ["%s 4, 4, 4" % mn], {4: rand64(rng)})


def gen_logical(g, rng):
    for mn in ["and", "andc", "or", "orc", "xor", "nand", "nor", "eqv"]:
        for sfx in ["", "."]:
            for k in range(g.count(25)):
                g.case(mn, ["%s%s 3, 4, 5" % (mn, sfx)], {4: rand64(rng), 5: rand64(rng)}, xer=rand_xer(rng))
    for mn in ["ori", "oris", "xori", "xoris", "andi.", "andis."]:
        for k in range(g.count(30)):
            g.case(mn, ["%s 3, 4, %d" % (mn, rng.choice([0, 0xFFFF, 0x8000, rng.getrandbits(16)]))],
                   {4: rand64(rng)}, xer=rand_xer(rng))
    one = ["extsb", "extsh", "extsw", "cntlzw", "cntlzd", "cnttzw", "cnttzd"]
    for mn in one:
        for sfx in ["", "."]:
            for a in EDGE + [rand64(rng) for _ in range(g.count(30))]:
                g.case(mn, ["%s%s 3, 4" % (mn, sfx)], {4: a}, xer=rand_xer(rng))
    for mn in ["popcntb", "popcntw", "popcntd", "prtyw", "prtyd"]:
        for a in EDGE + [rand64(rng) for _ in range(g.count(30))]:
            if mn.startswith("prty"):
                code = [xform(31, 154 if mn == "prtyw" else 186, 4, 3)]
            else:
                code = ["%s 3, 4" % mn]
            g.case(mn, code, {4: a})
    for k in range(g.count(60)):
        a = rand64(rng)
        b = rand64(rng)
        if k % 3 == 0:  # make some bytes equal
            m = rng.getrandbits(64) & 0xFF00FF00FF00FF00
            b = (a & m) | (b & ~m & M64)
        g.case("cmpb", ["cmpb 3, 4, 5"], {4: a, 5: b})
    for k in range(g.count(60)):
        idx = 0
        for i in range(8):
            idx = (idx << 8) | rng.choice([rng.randint(0, 63), rng.randint(0, 255)])
        g.case("bpermd", ["bpermd 3, 4, 5"], {4: idx, 5: rand64(rng)})
    for k in range(g.count(60)):
        # cdtbcd on any input; cbcdtd on valid BCD digits.
        g.case("cdtbcd", ["cdtbcd 3, 4"], {4: rand64(rng)})
        v = 0
        for i in range(16):
            v = (v << 4) | rng.randint(0, 9)
        g.case("cbcdtd", ["cbcdtd 3, 4"], {4: v})


def gen_shift(g, rng):
    amounts = [0, 1, 7, 31, 32, 33, 63, 64, 65, 127, 128, 255]
    for mn in ["slw", "srw", "sraw", "sld", "srd", "srad"]:
        for sfx in ["", "."]:
            for k in range(g.count(40)):
                sh = rng.choice(amounts) if k % 2 else rng.getrandbits(64)
                g.case(mn, ["%s%s 3, 4, 5" % (mn, sfx)], {4: rand64(rng), 5: sh}, xer=rand_xer(rng))
    for sfx in ["", "."]:
        for k in range(g.count(40)):
            g.case("srawi", ["srawi%s 3, 4, %d" % (sfx, rng.randint(0, 31))], {4: rand64(rng)}, xer=rand_xer(rng))
            g.case("sradi", ["sradi%s 3, 4, %d" % (sfx, rng.randint(0, 63))], {4: rand64(rng)}, xer=rand_xer(rng))
            g.case("extswsli", ["extswsli%s 3, 4, %d" % (sfx, rng.randint(0, 63))], {4: rand64(rng)}, xer=rand_xer(rng))
    for sfx in ["", "."]:
        for k in range(g.count(60)):
            sh, mb, me = rng.randint(0, 31), rng.randint(0, 31), rng.randint(0, 31)
            a = rand64(rng)
            g.case("rlwinm", ["rlwinm%s 3, 4, %d, %d, %d" % (sfx, sh, mb, me)], {4: a})
            g.case("rlwnm", ["rlwnm%s 3, 4, 5, %d, %d" % (sfx, mb, me)], {4: a, 5: rand64(rng)})
            g.case("rlwimi", ["rlwimi%s 3, 4, %d, %d, %d" % (sfx, sh, mb, me)], {3: rand64(rng), 4: a})
            sh, mb = rng.randint(0, 63), rng.randint(0, 63)
            g.case("rldicl", ["rldicl%s 3, 4, %d, %d" % (sfx, sh, mb)], {4: a})
            g.case("rldicr", ["rldicr%s 3, 4, %d, %d" % (sfx, sh, mb)], {4: a})
            g.case("rldic", ["rldic%s 3, 4, %d, %d" % (sfx, sh, mb)], {4: a})
            g.case("rldimi", ["rldimi%s 3, 4, %d, %d" % (sfx, sh, mb)], {3: rand64(rng), 4: a})
            g.case("rldcl", ["rldcl%s 3, 4, 5, %d" % (sfx, mb)], {4: a, 5: rand64(rng)})
            g.case("rldcr", ["rldcr%s 3, 4, 5, %d" % (sfx, mb)], {4: a, 5: rand64(rng)})


def gen_compare(g, rng):
    for k in range(g.count(80)):
        a, b = operand_pairs(rng, 80)[k % 80]
        bf = rng.randint(0, 7)
        l = rng.randint(0, 1)
        xer = rng.choice([0, 1 << 31])
        cr = rng.getrandbits(32)
        g.case("cmp", ["cmp %d, %d, 4, 5" % (bf, l)], {4: a, 5: b}, xer=xer, cr=cr)
        g.case("cmpl", ["cmpl %d, %d, 4, 5" % (bf, l)], {4: a, 5: b}, xer=xer, cr=cr)
        si = rng.choice([0, 1, -1, -0x8000, 0x7FFF, rng.randint(-0x8000, 0x7FFF)])
        g.case("cmpi", ["cmpi %d, %d, 4, %d" % (bf, l, si)], {4: a}, xer=xer, cr=cr)
        g.case("cmpli", ["cmpli %d, %d, 4, %d" % (bf, l, si & 0xFFFF)], {4: a}, xer=xer, cr=cr)
    for k in range(g.count(80)):
        bf = rng.randint(0, 7)
        rb = rng.getrandbits(32)
        lo = rng.randint(0, 255)
        rb = (rb & ~0xFFFF) | lo | (rng.randint(lo, 255) << 8)
        ra = rng.choice([lo, rb & 0xFF, (rb >> 8) & 0xFF, (rb >> 16) & 0xFF, rng.getrandbits(64)])
        g.case("cmprb", ["cmprb %d, %d, 4, 5" % (bf, k & 1)], {4: ra | (rng.getrandbits(56) << 8), 5: rb},
               cr=rng.getrandbits(32))
        b = rng.getrandbits(64)
        ra = rng.choice([(b >> (8 * rng.randint(0, 7))) & 0xFF, rng.getrandbits(8)]) | (rng.getrandbits(56) << 8)
        g.case("cmpeqb", ["cmpeqb %d, 4, 5" % bf], {4: ra, 5: b}, cr=rng.getrandbits(32))
    for k in range(g.count(40)):
        g.case("setb", ["setb 3, %d" % rng.randint(0, 7)], {}, cr=rng.getrandbits(32))
        g.case("mcrxrx", ["mcrxrx %d" % rng.randint(0, 7)], {}, xer=rand_xer(rng), cr=rng.getrandbits(32))
        g.case("isel", ["isel 3, %d, 5, %d" % (rng.choice([0, 4]), rng.randint(0, 31))],
               {4: rand64(rng), 5: rand64(rng)}, cr=rng.getrandbits(32))
    for k in range(g.count(10)):
        g.case("mcrxr", [0x7C000400 | (rng.randint(0, 7) << 23)], {}, xer=rand_xer(rng), cr=rng.getrandbits(32))


def gen_trap(g, rng):
    pairs = [(0, 0), (1, 2), (2, 1), (0xFFFFFFFFFFFFFFFF, 1), (1, 0xFFFFFFFFFFFFFFFF),
             (0x80000000, 0x7FFFFFFF), (0x100000001, 2), (0xFFFFFFFF, 0x1FFFFFFFF)]
    for to in range(32):
        for a, b in pairs + [(rand64(rng), rand64(rng)) for _ in range(2)]:
            g.case("tw", ["tw %d, 4, 5" % to], {4: a, 5: b})
            g.case("td", ["td %d, 4, 5" % to], {4: a, 5: b})
        for a in [0, 1, 0xFFFFFFFFFFFFFFFF, 0x80000000, 0x100000005, rand64(rng)]:
            si = rng.choice([0, 1, -1, 5])
            g.case("twi", ["twi %d, 4, %d" % (to, si)], {4: a})
            g.case("tdi", ["tdi %d, 4, %d" % (to, si)], {4: a})


def gen_misc(g):
    # darn L=3 is reserved (returns -1); random values are compared only for being set.
    g.case("darn", ["darn 3, 3"], {})
    for l in range(3):
        g.case("darn", ["darn 3, %d" % l], {}).ignore.append("r3")
    # Encodings that QEMU rejects: copy/paste, cp_abort is a no-op, unknown XO.
    g.case("illegal", [0x7C20060C], {})  # copy 0, 0
    g.case("illegal", [0x7C20070D], {})  # paste. 0, 0
    g.case("illegal", [0x7C00068C], {})  # cp_abort
    g.case("illegal", [0x7C000004 | (3 << 21) | (4 << 16) | (5 << 11)], {})  # unassigned XO 2


def alu():
    g = Gen("", MSR64LE, 1.0)
    rng = random.Random(1)
    gen_arith(g, rng)
    gen_logical(g, rng)
    gen_shift(g, rng)
    gen_compare(g, rng)
    gen_trap(g, rng)
    gen_misc(g)
    # A big-endian sample: computation does not depend on MSR[LE].
    g2 = Gen("be", MSR64BE, 0.1)
    gen_arith(g2, random.Random(2))
    gen_shift(g2, random.Random(3))
    return g.cases + g2.cases


def alu32():
    g = Gen("", MSR32LE, 0.5)
    rng = random.Random(11)
    gen_arith(g, rng)
    gen_logical(g, rng)
    gen_shift(g, rng)
    gen_compare(g, rng)
    gen_trap(g, rng)
    g2 = Gen("be", MSR32BE, 0.1)
    gen_arith(g2, random.Random(12))
    return g.cases + g2.cases


GROUPS = {"alu": alu, "alu32": alu32}
