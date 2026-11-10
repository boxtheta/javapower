"""ISA 3.0 quad-precision groups (VR operands)."""
from vals import Case, rand_qp, rand_i64, rand_dp, enc_x, raw, QP_SPECIAL
from groups_fp import regs, base_case
from groups_vsx import fps_any


def qval(rng):
    if rng.random() < 0.3:
        return rng.choice(QP_SPECIAL)
    return rand_qp(rng)


def qop(rng, mn, nsrc, count, ro=True, vals=qval, fmt=None):
    out = []
    for _ in range(count):
        r = regs(rng, nsrc + 1)
        m = mn + ("o" if ro and rng.random() < 0.4 else "")
        c = base_case(rng, "%s %s" % (m, ", ".join(str(x) for x in r)))
        c.fpscr = fps_any(rng)
        for reg in r[1:]:
            c.vsr[32 + reg] = vals(rng)
        if r[0] not in r[1:]:
            c.vsr[32 + r[0]] = qval(rng)
        out.append(c)
    return out


def g_qparith(rng):
    cs = []
    N = 250
    for mn in ("xsaddqp", "xssubqp", "xsmulqp", "xsdivqp"):
        cs += qop(rng, mn, 2, N)
    cs += qop(rng, "xssqrtqp", 1, N)
    for mn in ("xsmaddqp", "xsmsubqp", "xsnmaddqp", "xsnmsubqp"):
        cs += qop(rng, mn, 2, N)
    # cancellation-prone / exact cases
    for mn in ("xsaddqp", "xssubqp"):
        def near(r):
            v = qval(r)
            return v
        cs += qop(rng, mn, 2, 60, vals=near)
    return cs


def g_qpcvt(rng):
    cs = []
    N = 150
    cs += qop(rng, "xscvqpdp", 1, N)
    for mn in ("xscvqpsdz", "xscvqpswz", "xscvqpudz", "xscvqpuwz"):
        cs += qop(rng, mn, 1, N, ro=False)
    def dval(r):
        return (rand_dp(r), r.getrandbits(64))
    cs += qop(rng, "xscvdpqp", 1, N, ro=False, vals=dval)
    def ival(r):
        return (rand_i64(r), r.getrandbits(64))
    for mn in ("xscvsdqp", "xscvudqp"):
        cs += qop(rng, mn, 1, N, ro=False, vals=ival)
    for mn in ("xsrqpi", "xsrqpix", "xsrqpxp"):
        for _ in range(N):
            t, b = regs(rng, 2)
            c = base_case(rng, "%s %d, %d, %d, %d" % (mn, rng.randrange(2), t, b, rng.randrange(4)))
            c.fpscr = fps_any(rng)
            c.vsr[32 + b] = qval(rng)
            cs.append(c)
    return cs


def g_qpcmp(rng):
    cs = []
    N = 120
    for mn in ("xscmpoqp", "xscmpuqp", "xscmpexpqp"):
        for _ in range(N):
            a, b = regs(rng, 2)
            c = base_case(rng, "%s %d, %d, %d" % (mn, rng.randrange(8), a, b))
            c.fpscr = fps_any(rng)
            c.vsr[32 + a] = qval(rng)
            c.vsr[32 + b] = qval(rng) if rng.random() < 0.8 else c.vsr[32 + a]
            cs.append(c)
    for _ in range(N):
        b = rng.randrange(32)
        c = base_case(rng, "xststdcqp %d, %d, %d" % (rng.randrange(8), b, rng.randrange(128)))
        c.vsr[32 + b] = qval(rng)
        cs.append(c)
    for mn in ("xsabsqp", "xsnabsqp", "xsnegqp", "xsxexpqp", "xsxsigqp"):
        cs += qop(rng, mn, 1, 30, ro=False)
    for mn in ("xscpsgnqp", "xsiexpqp"):
        cs += qop(rng, mn, 2, 30, ro=False)
    return cs


GROUPS = {
    "qparith": g_qparith,
    "qpcvt": g_qpcvt,
    "qpcmp": g_qpcmp,
}
