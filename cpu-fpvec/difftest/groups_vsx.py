"""VSX groups (VSR 0..63)."""
from vals import (Case, M64, DP_CORE, DP_SPECIAL, SP_SPECIAL, rand_vec, rand_dp, rand_sp32, rand_sp_as_dp,
                  rand_i64, join_elems, rand_fpscr, sp_to_dp_bits)
from groups_fp import base_case, ea_regs

GP = [3, 4, 5, 6, 7, 8, 9, 10, 11, 12]


def vregs(rng, n, alias=0.15):
    out = []
    for _ in range(n):
        if out and rng.random() < alias:
            out.append(rng.choice(out))
        else:
            while True:
                r = rng.randrange(64)
                if r not in out:
                    break
            out.append(r)
    return out


def core_vec(rng, kind):
    """Biased toward special values."""
    if kind in ("vd", "sd") and rng.random() < 0.4:
        a = rng.choice(DP_CORE)
        b = rng.choice(DP_CORE)
        return (a, b if kind == "vd" else rng.getrandbits(64))
    if kind == "vf" and rng.random() < 0.4:
        return join_elems([rng.choice(SP_SPECIAL) for _ in range(4)], 32)
    if kind == "ss" and rng.random() < 0.3:
        return (sp_to_dp_bits(rng.choice(SP_SPECIAL)), rng.getrandbits(64))
    return rand_vec(rng, kind)


def xop(rng, fmt, mn, kind, count, imm=None, kinds=None, fps=None):
    """fmt letters: t a b c = VSRs, i = immediate, R = GPR target, A/B = GPR sources, F = CR field."""
    out = []
    for _ in range(count):
        letters = [ch for ch in fmt if ch in "tabc"]
        rs = vregs(rng, len(letters))
        rmap = dict(zip(letters, rs))
        c = base_case(rng, "")
        if fps is not None:
            c.fpscr = fps(rng)
        ops = []
        used = []
        for ch in fmt:
            if ch in "tabc":
                ops.append(str(rmap[ch]))
            elif ch == "i":
                ops.append(str(imm(rng)))
            elif ch == "F":
                ops.append(str(rng.randrange(8)))
            elif ch in "RAB":
                g = rng.choice([r for r in GP if r not in used])
                used.append(g)
                if ch != "R":
                    c.gpr[g] = rand_i64(rng)
                ops.append(str(g))
        c.asm = "%s %s" % (mn, ", ".join(ops))
        for ch in fmt:
            if ch in "abc":
                k = (kinds or {}).get(ch, kind)
                c.vsr[rmap[ch]] = core_vec(rng, k)
        if "t" in rmap and rmap["t"] not in [rmap[x] for x in "abc" if x in rmap]:
            c.vsr[rmap["t"]] = core_vec(rng, (kinds or {}).get("t", kind))
        out.append(c)
    return out


def enc_xx3(t, a, b, xo8):
    return ((60 << 26) | ((t & 31) << 21) | ((a & 31) << 16) | ((b & 31) << 11) | (xo8 << 3)
            | ((a >> 5) << 2) | ((b >> 5) << 1) | (t >> 5))


def raw_xx3(cases, xo8):
    """Re-encode `mn t, a, b` cases for instructions llvm-mc does not know."""
    from vals import raw
    for c in cases:
        t, a, b = (int(x) for x in c.asm.split(" ", 1)[1].split(","))
        c.asm = raw(enc_xx3(t, a, b, xo8), c.asm)
    return cases


def fps_any(rng):
    return rand_fpscr(rng, 0.5, 0.4)


def g_vsxarith(rng):
    cs = []
    N = 120
    for op in ("add", "sub", "mul", "div"):
        cs += xop(rng, "tab", "xs%sdp" % op, "sd", N, fps=fps_any)
        cs += xop(rng, "tab", "xs%ssp" % op, "ss", N, fps=fps_any)
        cs += xop(rng, "tab", "xv%sdp" % op, "vd", N, fps=fps_any)
        cs += xop(rng, "tab", "xv%ssp" % op, "vf", N, fps=fps_any)
    for op in ("sqrt", "re", "rsqrte"):
        cs += xop(rng, "tb", "xs%sdp" % op, "sd", N, fps=fps_any)
        cs += xop(rng, "tb", "xs%ssp" % op, "ss", N, fps=fps_any)
        cs += xop(rng, "tb", "xv%sdp" % op, "vd", N, fps=fps_any)
        cs += xop(rng, "tb", "xv%ssp" % op, "vf", N, fps=fps_any)
    return cs


def g_vsxfma(rng):
    cs = []
    N = 50
    for op in ("madd", "msub", "nmadd", "nmsub"):
        for f in "am":
            cs += xop(rng, "tab", "xs%s%sdp" % (op, f), "sd", N, fps=fps_any)
            cs += xop(rng, "tab", "xs%s%ssp" % (op, f), "ss", N, fps=fps_any)
            cs += xop(rng, "tab", "xv%s%sdp" % (op, f), "vd", N, fps=fps_any)
            cs += xop(rng, "tab", "xv%s%ssp" % (op, f), "vf", N, fps=fps_any)
    return cs


def g_vsxcmp(rng):
    cs = []
    N = 80
    for mn in ("xscmpeqdp", "xscmpgedp", "xscmpgtdp", "xsmaxdp", "xsmindp", "xsmaxcdp", "xsmincdp",
               "xsmaxjdp", "xsminjdp"):
        cs += xop(rng, "tab", mn, "sd", N, fps=fps_any)
    for mn in ("xvmaxdp", "xvmindp"):
        cs += xop(rng, "tab", mn, "vd", N, fps=fps_any)
    for mn in ("xvmaxsp", "xvminsp"):
        cs += xop(rng, "tab", mn, "vf", N, fps=fps_any)
    for mn in ("xscmpexpdp", "xscmpodp", "xscmpudp", "xstdivdp"):
        cs += xop(rng, "Fab", mn, "sd", N, fps=fps_any)
    cs += xop(rng, "Fab", "xvtdivdp", "vd", N)
    cs += xop(rng, "Fab", "xvtdivsp", "vf", N)
    cs += xop(rng, "Fb", "xstsqrtdp", "sd", N)
    cs += xop(rng, "Fb", "xvtsqrtdp", "vd", N)
    cs += xop(rng, "Fb", "xvtsqrtsp", "vf", N)
    for e, k in (("dp", "vd"), ("sp", "vf")):
        for op in ("eq", "gt", "ge"):
            cs += xop(rng, "tab", "xvcmp%s%s" % (op, e), k, N // 2, fps=fps_any)
            cs += xop(rng, "tab", "xvcmp%s%s." % (op, e), k, N // 2, fps=fps_any)
        xo7 = 123 if e == "dp" else 91
        cs += raw_xx3(xop(rng, "tab", "xvcmpne" + e, k, N // 2, fps=fps_any), xo7)
        cs += raw_xx3(xop(rng, "tab", "xvcmpne%s." % e, k, N // 2, fps=fps_any), xo7 | 0x80)
    cs += xop(rng, "tbi", "xvtstdcdp", "vd", N, imm=lambda r: r.randrange(128))
    cs += xop(rng, "tbi", "xvtstdcsp", "vf", N, imm=lambda r: r.randrange(128))
    cs += xop(rng, "Fbi", "xststdcdp", "sd", N, imm=lambda r: r.randrange(128))
    cs += xop(rng, "Fbi", "xststdcsp", "ss", N, imm=lambda r: r.randrange(128))
    return cs


def int_vec(rng):
    return rand_vec(rng, rng.choice(["w", "d", "x"]))


def g_vsxcvt(rng):
    cs = []
    N = 80
    for mn, k in (("xscvdpsp", "sd"), ("xvcvdpsp", "vd"), ("xscvspdp", "vf"), ("xvcvspdp", "vf"),
                  ("xscvdphp", "sd"), ("xscvhpdp", "x"), ("xvcvsphp", "vf"), ("xvcvhpsp", "x"),
                  ("xscvdpspn", "sd"), ("xscvspdpn", "vf"), ("xsrsp", "sd"),
                  ("xscvdpsxds", "sd"), ("xscvdpuxds", "sd"), ("xscvdpsxws", "sd"), ("xscvdpuxws", "sd"),
                  ("xvcvdpsxds", "vd"), ("xvcvdpuxds", "vd"), ("xvcvdpsxws", "vd"), ("xvcvdpuxws", "vd"),
                  ("xvcvspsxds", "vf"), ("xvcvspuxds", "vf"), ("xvcvspsxws", "vf"), ("xvcvspuxws", "vf")):
        cs += xop(rng, "tb", mn, k, N, fps=fps_any)
    for mn in ("xscvsxddp", "xscvuxddp", "xscvsxdsp", "xscvuxdsp", "xvcvsxddp", "xvcvuxddp", "xvcvsxwdp",
               "xvcvuxwdp", "xvcvsxwsp", "xvcvuxwsp", "xvcvsxdsp", "xvcvuxdsp"):
        out = xop(rng, "tb", mn, "x", N, fps=fps_any)
        for c in out:
            b = int(c.asm.split(",")[1])
            c.vsr[b] = int_vec(rng)
        cs += out
    for sfx in ("", "c", "m", "p", "z"):
        cs += xop(rng, "tb", "xsrdpi" + sfx, "sd", N, fps=fps_any)
        cs += xop(rng, "tb", "xvrdpi" + sfx, "vd", N, fps=fps_any)
        cs += xop(rng, "tb", "xvrspi" + sfx, "vf", N, fps=fps_any)
    return cs


def g_vsxperm(rng):
    cs = []
    N = 30
    for mn in ("xxland", "xxlandc", "xxlor", "xxlxor", "xxlnor", "xxlorc", "xxlnand", "xxleqv",
               "xxmrghw", "xxmrglw", "xvcpsgndp", "xvcpsgnsp", "xscpsgndp", "xviexpsp", "xviexpdp"):
        cs += xop(rng, "tab", mn, "x", N)
    for mn in ("xxperm", "xxpermr"):
        cs += xop(rng, "tab", mn, "x", N)
    cs += xop(rng, "tabc", "xxsel", "x", N)
    cs += xop(rng, "tabi", "xxpermdi", "x", N, imm=lambda r: r.randrange(4))
    cs += xop(rng, "tabi", "xxsldwi", "x", N, imm=lambda r: r.randrange(4))
    cs += xop(rng, "tbi", "xxspltw", "x", N, imm=lambda r: r.randrange(4))
    cs += xop(rng, "ti", "xxspltib", "x", N, imm=lambda r: r.randrange(256))
    cs += xop(rng, "tbi", "xxextractuw", "x", N, imm=lambda r: r.randrange(16))
    cs += xop(rng, "tbi", "xxinsertw", "x", N, imm=lambda r: r.randrange(16))
    for mn in ("xsabsdp", "xsnabsdp", "xsnegdp", "xvabsdp", "xvnabsdp", "xvnegdp", "xvabssp", "xvnabssp",
               "xvnegsp", "xvxexpdp", "xvxsigdp", "xvxexpsp", "xvxsigsp", "xxbrh", "xxbrw", "xxbrd", "xxbrq"):
        cs += xop(rng, "tb", mn, "vd" if "dp" in mn else "vf" if "sp" in mn else "x", N)
    cs += xop(rng, "Rb", "xsxexpdp", "sd", N)
    cs += xop(rng, "Rb", "xsxsigdp", "sd", N)
    cs += xop(rng, "tAB", "xsiexpdp", "x", N)
    return cs


def g_vsxmove(rng):
    cs = []
    N = 30
    for mn in ("mfvsrd", "mfvsrwz", "mfvsrld"):
        cs += xop(rng, "Rb", mn, "x", N)
    for mn in ("mtvsrd", "mtvsrwa", "mtvsrwz", "mtvsrws"):
        cs += xop(rng, "tA", mn, "x", N)
    cs += xop(rng, "tAB", "mtvsrdd", "x", N)
    out = []
    for _ in range(10):
        t = rng.randrange(64)
        c = base_case(rng, "mtvsrdd %d, 0, 5" % t)
        c.gpr[5] = rand_i64(rng)
        out.append(c)
    return cs + out


def vsx_ldst(rng, count):
    cs = []
    xforms = (("lxsdx", 8, 0), ("lxsiwax", 4, 0), ("lxsiwzx", 4, 0), ("lxsspx", 4, 0), ("lxsibzx", 1, 0),
              ("lxsihzx", 2, 0), ("stxsdx", 8, 1), ("stxsiwx", 4, 1), ("stxsspx", 4, 1), ("stxsibx", 1, 1),
              ("stxsihx", 2, 1), ("lxvd2x", 16, 0), ("lxvw4x", 16, 0), ("lxvh8x", 16, 0), ("lxvb16x", 16, 0),
              ("stxvd2x", 16, 1), ("stxvw4x", 16, 1), ("stxvh8x", 16, 1), ("stxvb16x", 16, 1),
              ("lxvdsx", 8, 0), ("lxvwsx", 4, 0), ("lxvx", 16, 0), ("stxvx", 16, 1))
    for mn, size, st in xforms:
        for _ in range(count):
            t = rng.randrange(64)
            opnd, gpr, off, ra = ea_regs(rng, size, xform=True)
            c = base_case(rng, "%s %d, %s" % (mn, t, opnd))
            c.gpr.update(gpr)
            c.vsr[t] = rand_vec(rng, "vd" if rng.random() < 0.5 else "x")
            if mn == "stxsspx" and rng.random() < 0.6:
                c.vsr[t] = (rand_sp_as_dp(rng), rng.getrandbits(64))
            cs.append(c)
    for mn, st in (("lxvl", 0), ("lxvll", 0), ("stxvl", 1), ("stxvll", 1)):
        for _ in range(count):
            t = rng.randrange(64)
            ra, rb = rng.sample(GP, 2)
            off = rng.randrange(0, 512 - 16)
            c = base_case(rng, "%s %d, %d, %d" % (mn, t, ra, rb))
            c.gpr[ra] = 0x30000000 + off
            nb = rng.choice([0, 1, 7, 8, 15, 16, 17, 255, rng.randrange(18)])
            c.gpr[rb] = (nb << 56) | rng.getrandbits(56)
            c.vsr[t] = rand_vec(rng, "x")
            cs.append(c)
    for mn, size, align, vr in (("lxsd", 8, 4, True), ("stxsd", 8, 4, True), ("lxssp", 4, 4, True),
                                ("stxssp", 4, 4, True), ("lxv", 16, 16, False), ("stxv", 16, 16, False)):
        for _ in range(count):
            t = rng.randrange(32 if vr else 64)
            opnd, gpr, off, ra = ea_regs(rng, size, align=align)
            c = base_case(rng, "%s %d, %s" % (mn, t, opnd))
            c.gpr.update(gpr)
            c.vsr[t + 32 if vr else t] = rand_vec(rng, "vd")
            cs.append(c)
    return cs


def g_vsxldst(rng):
    return vsx_ldst(rng, 25)


def g_vsxldst_be(rng):
    return vsx_ldst(rng, 25)


GROUPS = {
    "vsxarith": g_vsxarith,
    "vsxfma": g_vsxfma,
    "vsxcmp": g_vsxcmp,
    "vsxcvt": g_vsxcvt,
    "vsxperm": g_vsxperm,
    "vsxmove": g_vsxmove,
    "vsxldst": g_vsxldst,
    "vsxldst_be": g_vsxldst_be,
}
