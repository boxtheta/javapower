"""Scalar floating-point groups."""
import struct

from vals import (Case, MEMBASE, M64, DP_CORE, DP_SPECIAL, rand_dp, rand_sp_as_dp, rand_i64, rand_fpscr,
                  rand_cr, rand_vscr, sp_to_dp_bits, rand_sp32, enc_x, raw)

GPRS = [0, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]
BASE_GPRS = [3, 4, 5, 6, 7, 8, 9, 10, 11, 12]


def regs(rng, n, lo=0, hi=31, alias=0.15):
    out = []
    for _ in range(n):
        if out and rng.random() < alias:
            out.append(rng.choice(out))
        else:
            while True:
                r = rng.randint(lo, hi)
                if r not in out:
                    break
            out.append(r)
    return out


def base_case(rng, asm):
    c = Case(asm)
    c.fpscr = rand_fpscr(rng)
    c.cr = rand_cr(rng)
    c.vscr = rand_vscr(rng)
    return c


def set_fpr(rng, c, r, val):
    c.vsr[r] = (val, rng.getrandbits(64))


def fp_op(rng, mn, nsrc, gen_val, count, rc=True, pairs=None):
    """FRT plus nsrc FPR sources, assembled in order `mn t, s0, s1, ...`."""
    out = []
    names = [mn] + ([mn + "."] if rc else [])
    combos = []
    if pairs is not None:
        combos = pairs
    for i in range(count + len(combos)):
        m = names[0] if (not rc or rng.random() < 0.8) else names[1]
        r = regs(rng, nsrc + 1)
        c = base_case(rng, "%s %s" % (m, ", ".join(str(x) for x in r)))
        if i < len(combos):
            vals = list(combos[i])
        else:
            vals = [gen_val(rng) for _ in range(nsrc)]
        for reg, v in zip(r[1:], vals):
            set_fpr(rng, c, reg, v)
        if r[0] not in r[1:]:
            set_fpr(rng, c, r[0], rand_dp(rng))
        out.append(c)
    return out


def special_pairs(rng, pool, n):
    pairs = [(a, b) for a in pool for b in pool]
    rng.shuffle(pairs)
    return pairs[:n]


def g_fparith(rng):
    cs = []
    for mn in ("fadd", "fsub", "fmul", "fdiv"):
        cs += fp_op(rng, mn, 2, rand_dp, 250, pairs=special_pairs(rng, DP_CORE, 300))
        cs += fp_op(rng, mn + "s", 2, rand_sp_as_dp, 250, pairs=special_pairs(rng, DP_CORE, 150))
    # near-overflow / underflow arithmetic
    for mn in ("fadd", "fmul", "fdiv", "fadds", "fmuls", "fdivs"):
        def edge(r):
            e = r.choice([r.randrange(1, 40), r.randrange(2000, 2047), r.randrange(800, 1250),
                          r.randrange(1023 - 150, 1023 - 100), r.randrange(1023 + 100, 1023 + 150)])
            return (r.getrandbits(1) << 63) | (e << 52) | r.getrandbits(52)
        cs += fp_op(rng, mn, 2, edge, 150)
    for mn in ("fsqrt", "fsqrts", "fre", "fres", "frsqrte", "frsqrtes"):
        g = rand_dp if not mn.endswith("s") or mn == "frsqrtes" else rand_sp_as_dp
        cs += fp_op(rng, mn, 1, g, 250, pairs=[(v,) for v in DP_SPECIAL])
    return cs


def g_fpfma(rng):
    cs = []
    for mn in ("fmadd", "fmsub", "fnmadd", "fnmsub"):
        cs += fp_op(rng, mn, 3, rand_dp, 300)
        cs += fp_op(rng, mn + "s", 3, rand_sp_as_dp, 200)
        trip = [(rng.choice(DP_CORE), rng.choice(DP_CORE), rng.choice(DP_CORE)) for _ in range(150)]
        cs += fp_op(rng, mn, 3, rand_dp, 0, pairs=trip)

        # cancellation: a*c close to -b
        def canc(r, mn=mn):
            return None
        for _ in range(60):
            a = rand_dp(rng) & ~(0x7FF << 52) | ((1023 + rng.randrange(-20, 20)) << 52)
            c_ = rand_dp(rng) & ~(0x7FF << 52) | ((1023 + rng.randrange(-20, 20)) << 52)
            p = struct.unpack("<d", struct.pack("<Q", a))[0] * struct.unpack("<d", struct.pack("<Q", c_))[0]
            b = struct.unpack("<Q", struct.pack("<d", -p if mn in ("fmadd", "fnmadd") else p))[0]
            b ^= rng.choice([0, 1, 2, 3, 1 << 20])
            cs += fp_op(rng, mn, 3, rand_dp, 0, pairs=[(a, c_, b)])
    cs += fp_op(rng, "fsel", 3, rand_dp, 200)
    return cs


def g_fpconv(rng):
    cs = []
    for mn in ("fctid", "fctidz", "fctidu", "fctiduz", "fctiw", "fctiwz", "fctiwu", "fctiwuz",
               "frsp", "frin", "friz", "frip", "frim"):
        cs += fp_op(rng, mn, 1, rand_dp, 250, pairs=[(v,) for v in DP_SPECIAL])
    for mn in ("fcfid", "fcfids", "fcfidu", "fcfidus"):
        cs += fp_op(rng, mn, 1, rand_i64, 250)
    for mn in ("fmr", "fneg", "fabs", "fnabs"):
        cs += fp_op(rng, mn, 1, rand_dp, 40)
    cs += fp_op(rng, "fcpsgn", 2, rand_dp, 60)
    for mn, xo in (("fmrgew", 966), ("fmrgow", 838)):
        for c in fp_op(rng, mn, 2, rand_dp, 60, rc=False):
            t, a, b = (int(x) for x in c.asm.split(" ", 1)[1].split(","))
            c.asm = raw(enc_x(63, t, a, b, xo), c.asm)
            cs.append(c)
    # frsp on values near single boundaries in all rounding modes
    cs += fp_op(rng, "frsp", 1, lambda r: sp_to_dp_bits(rand_sp32(r)) ^ r.getrandbits(29), 300)
    return cs


def g_fpcmp(rng):
    cs = []
    for mn in ("fcmpu", "fcmpo"):
        for _ in range(400):
            bf = rng.randrange(8)
            a, b = regs(rng, 2)
            c = base_case(rng, "%s %d, %d, %d" % (mn, bf, a, b))
            set_fpr(rng, c, a, rng.choice(DP_CORE) if rng.random() < 0.6 else rand_dp(rng))
            set_fpr(rng, c, b, rng.choice(DP_CORE) if rng.random() < 0.6 else rand_dp(rng))
            cs.append(c)
    for _ in range(300):
        bf = rng.randrange(8)
        a, b = regs(rng, 2)
        c = base_case(rng, "ftdiv %d, %d, %d" % (bf, a, b))
        set_fpr(rng, c, a, rand_dp(rng))
        set_fpr(rng, c, b, rand_dp(rng))
        cs.append(c)
    for _ in range(200):
        bf = rng.randrange(8)
        b = rng.randrange(32)
        c = base_case(rng, "ftsqrt %d, %d" % (bf, b))
        set_fpr(rng, c, b, rand_dp(rng))
        cs.append(c)
    return cs


def g_fpscr(rng):
    cs = []

    def fps(rng):
        # random FPSCR, including some with enabled exceptions pending at the end
        return rand_fpscr(rng, 0.5, 0.6)

    for _ in range(60):
        for asm in ("mffs %d", "mffs. %d", "mffsce %d", "mffsl %d"):
            t = rng.randrange(32)
            c = base_case(rng, asm % t)
            c.fpscr = fps(rng)
            cs.append(c)
        t, b = regs(rng, 2)
        for asm in ("mffscdrn %d, %d", "mffscrn %d, %d"):
            c = base_case(rng, asm % (t, b))
            c.fpscr = fps(rng)
            set_fpr(rng, c, b, rng.getrandbits(64) if rng.random() < 0.5 else rng.getrandbits(3) << 32 | rng.getrandbits(2))
            cs.append(c)
        c = base_case(rng, "mffscdrni %d, %d" % (t, rng.randrange(8)))
        c.fpscr = fps(rng)
        cs.append(c)
        c = base_case(rng, "mffscrni %d, %d" % (t, rng.randrange(4)))
        c.fpscr = fps(rng)
        cs.append(c)
    for _ in range(400):
        flm = rng.choice([0xFF, 0x01, 0x80, rng.randrange(256)])
        b = rng.randrange(32)
        L = rng.choice([0, 0, 1])
        W = rng.choice([0, 0, 1])
        dot = rng.choice(["", "."])
        c = base_case(rng, "mtfsf%s %d, %d, %d, %d" % (dot, flm, b, L, W))
        c.fpscr = fps(rng)
        v = rng.getrandbits(64)
        if rng.random() < 0.5:
            v &= ~((0xF8 << 0) | (0xFFFFFFF0 << 32) | (1 << 31) | (0x3FF << 17))  # mostly harmless values
        set_fpr(rng, c, b, v)
        cs.append(c)
    for _ in range(300):
        bf = rng.randrange(8)
        u = rng.randrange(16)
        W = rng.choice([0, 0, 1])
        dot = rng.choice(["", "."])
        c = base_case(rng, "mtfsfi%s %d, %d, %d" % (dot, bf, u, W))
        c.fpscr = fps(rng)
        cs.append(c)
    for _ in range(300):
        bt = rng.randrange(32)
        mn = rng.choice(["mtfsb0", "mtfsb1", "mtfsb0.", "mtfsb1."])
        w = enc_x(63, bt, 0, 0, 70 if mn.startswith("mtfsb0") else 38, 1 if mn.endswith(".") else 0)
        c = base_case(rng, raw(w, "%s %d" % (mn, bt)))
        c.fpscr = fps(rng)
        cs.append(c)
    for _ in range(300):
        c = base_case(rng, "mcrfs %d, %d" % (rng.randrange(8), rng.randrange(8)))
        c.fpscr = fps(rng)
        cs.append(c)
    return cs


def mem_val(rng, size):
    if size == 8:
        return struct.pack("<Q", rand_dp(rng))
    if size == 4:
        return struct.pack("<I", rand_sp32(rng) if rng.random() < 0.7 else rng.getrandbits(32))
    return bytes(rng.getrandbits(8) for _ in range(size))


def ea_regs(rng, size, align=1, upd=False, xform=False, avoid=()):
    """Choose base/index registers and values so that EA lands inside the test buffer.
    Returns (asm operand text, gpr dict, ea offset)."""
    off = rng.randrange(0, 512 - size + 1)
    if align >= 16 or rng.random() < 0.7:
        off -= off % max(align, 1)
    gpr = {}
    if xform:
        ra, rb = rng.sample([r for r in BASE_GPRS if r not in avoid], 2)
        if not upd and rng.random() < 0.15:
            ra = 0
        if ra == 0:
            gpr[rb] = MEMBASE + off
        else:
            split = rng.randrange(-1000, 1000)
            gpr[ra] = MEMBASE + off - split
            gpr[rb] = split & M64
        return "%d, %d" % (ra, rb), gpr, off, ra
    ra = rng.choice([r for r in BASE_GPRS if r not in avoid])
    d = rng.randrange(-200, 200)
    if align > 1:
        d -= d % align
    gpr[ra] = MEMBASE + off - d
    return "%d(%d)" % (d, ra), gpr, off, ra


def fp_ldst(rng, mn, size, store, upd, xform, count, align=1, pair=False, tgt_even=False):
    cs = []
    for _ in range(count):
        t = rng.randrange(0, 32, 2 if tgt_even else 1)
        opnd, gpr, off, ra = ea_regs(rng, 16 if pair else size, align=align, upd=upd, xform=xform)
        c = base_case(rng, "%s %d, %s" % (mn, t, opnd))
        if pair:
            if xform:
                ra_, rb_ = (int(x) for x in opnd.split(","))
                c.asm = raw(enc_x(31, t, ra_, rb_, 791 if store == 0 else 919), c.asm)
            else:
                d_, ra_ = opnd[:-1].split("(")
                c.asm = raw(((57 if not store else 61) << 26) | (t << 21) | (int(ra_) << 16) | (int(d_) & 0xFFFC), c.asm)
        c.gpr.update(gpr)
        if store:
            v = rand_dp(rng) if size == 8 else (rand_dp(rng) if rng.random() < 0.6 else rand_i64(rng))
            set_fpr(rng, c, t, v)
            if pair:
                set_fpr(rng, c, t + 1, rand_dp(rng))
        else:
            c.mem[off] = mem_val(rng, size)
            if pair:
                c.mem[off + 8] = mem_val(rng, 8)
        cs.append(c)
    return cs


def g_fpldst(rng):
    cs = []
    for mn, size, st in (("lfs", 4, 0), ("lfd", 8, 0), ("stfs", 4, 1), ("stfd", 8, 1)):
        cs += fp_ldst(rng, mn, size, st, False, False, 120)
        cs += fp_ldst(rng, mn + "u", size, st, True, False, 40)
        cs += fp_ldst(rng, mn + "x", size, st, False, True, 80)
        cs += fp_ldst(rng, mn + "ux", size, st, True, True, 40)
    cs += fp_ldst(rng, "lfiwax", 4, 0, False, True, 80)
    cs += fp_ldst(rng, "lfiwzx", 4, 0, False, True, 80)
    cs += fp_ldst(rng, "stfiwx", 4, 1, False, True, 80)
    cs += fp_ldst(rng, "lfdp", 8, 0, False, False, 60, align=16, pair=True, tgt_even=True)
    cs += fp_ldst(rng, "stfdp", 8, 1, False, False, 60, align=16, pair=True, tgt_even=True)
    cs += fp_ldst(rng, "lfdpx", 8, 0, False, True, 60, align=16, pair=True, tgt_even=True)
    cs += fp_ldst(rng, "stfdpx", 8, 1, False, True, 60, align=16, pair=True, tgt_even=True)
    return cs


def g_fpldst_be(rng):
    return g_fpldst(rng)


GROUPS = {
    "fpldst_be": g_fpldst_be,
    "fparith": g_fparith,
    "fpfma": g_fpfma,
    "fpconv": g_fpconv,
    "fpcmp": g_fpcmp,
    "fpscr": g_fpscr,
    "fpldst": g_fpldst,
}
