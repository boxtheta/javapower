"""Operand value pools and helpers shared by the group modules."""
import struct

from gen import Case, MEMBASE, MEMSIZE  # noqa: F401

M64 = (1 << 64) - 1


def d2b(x):
    return struct.unpack("<Q", struct.pack("<d", x))[0]


def f2b(x):
    return struct.unpack("<I", struct.pack("<f", x))[0]


def b2f(b):
    return struct.unpack("<f", struct.pack("<I", b))[0]


def sp_to_dp_bits(w):
    """Exact widening of a binary32 bit pattern to binary64 (keeps NaN payloads, SNaN-ness)."""
    s = (w >> 31) & 1
    e = (w >> 23) & 0xFF
    f = w & 0x7FFFFF
    if e == 0xFF:
        return (s << 63) | (0x7FF << 52) | (f << 29)
    if e == 0:
        if f == 0:
            return s << 63
        ee = 1 - 127 + 1023
        while not (f & 0x800000):
            f <<= 1
            ee -= 1
        return (s << 63) | (ee << 52) | ((f & 0x7FFFFF) << 29)
    return (s << 63) | ((e - 127 + 1023) << 52) | (f << 29)


DP_SPECIAL = [
    0, 1 << 63, d2b(1.0), d2b(-1.0), d2b(2.0), d2b(0.5), d2b(-0.5), d2b(3.0), d2b(1.5), d2b(-7.25),
    1, (1 << 63) | 1, 0x000FFFFFFFFFFFFF, 0x0010000000000000, 0x8010000000000000, 0x0008000000000000,
    0x7FEFFFFFFFFFFFFF, 0xFFEFFFFFFFFFFFFF, 0x7FE0000000000000, 0x7FF0000000000000, 0xFFF0000000000000,
    0x7FF8000000000000, 0x7FF8000000012345, 0xFFF8000000000001, 0x7FF4000000000000,
    0x7FF0000000000001, 0xFFF00000ABCDEF00, 0x3FF0000000000001, 0x3FEFFFFFFFFFFFFF, d2b(3.141592653589793),
    d2b(1e300), d2b(1e-300), d2b(2.0 ** 63), d2b(-2.0 ** 63), d2b(2.0 ** 64), d2b(2.0 ** 31), d2b(-2.0 ** 31),
    d2b(2.0 ** 31 - 0.5), d2b(-2.0 ** 31 - 0.5), d2b(2.0 ** 32), d2b(2.0 ** 52 + 0.5), d2b(2.0 ** 53 + 2),
    d2b(-0.4999999999999999), d2b(2.5), d2b(-3.5), d2b(4503599627370497.0), d2b(9.2233720368547748e18),
    0x47EFFFFFE0000000, 0x47EFFFFFF0000000, 0x47EFFFFFF0000001, 0x47F0000000000000, 0x3810000000000000,
    0x380FFFFFE0000000, 0x36A0000000000000, 0x3690000000000000, 0x3690000000000001, 0x3FF0000010000000,
    0x3FF0000010000001, 0x3FF0000030000000, 0xB6A8000000000000, 0x0000000000000003,
]

DP_CORE = [
    0, 1 << 63, d2b(1.0), d2b(-1.5), d2b(3.0), 1, 0x8000000000000001, 0x000FFFFFFFFFFFFF,
    0x0010000000000000, 0x7FEFFFFFFFFFFFFF, 0xFFEFFFFFFFFFFFFF, 0x7FF0000000000000, 0xFFF0000000000000,
    0x7FF8000000000000, 0xFFF8000000012345, 0x7FF4000000000001, 0xFFF0000000000011, 0x3FEFFFFFFFFFFFFF,
    d2b(1e-300), 0x47EFFFFFF0000000, 0x3690000000000001,
]


def rand_dp(rng):
    r = rng.random()
    if r < 0.25:
        return rng.choice(DP_SPECIAL)
    if r < 0.45:
        return rng.getrandbits(64)
    if r < 0.6:
        # near the extremes of the exponent range
        e = rng.choice([rng.randrange(0, 60), rng.randrange(1990, 2047), rng.randrange(60, 130)])
        return (rng.getrandbits(1) << 63) | (e << 52) | rng.getrandbits(52)
    if r < 0.7:
        # single-precision-ish values with few mantissa bits
        e = rng.randrange(1023 - 160, 1023 + 140)
        return (rng.getrandbits(1) << 63) | (e << 52) | (rng.getrandbits(24) << 28)
    e = rng.randrange(1023 - 70, 1023 + 70)
    return (rng.getrandbits(1) << 63) | (e << 52) | rng.getrandbits(52)


SP_SPECIAL = [0, 0x80000000, 0x3F800000, 0xBF800000, 1, 0x80000001, 0x007FFFFF, 0x00800000, 0x7F7FFFFF,
              0xFF7FFFFF, 0x7F800000, 0xFF800000, 0x7FC00000, 0xFFC00001, 0x7FA00000, 0xFF800001,
              0x4B000000, 0x4F000000, 0xCF000000, 0x5F000000, 0x3F000000, 0x3FC00000, 0x40490FDB,
              0x4EFFFFFF, 0x4F7FFFFF, 0x3EFFFFFF]


def rand_sp32(rng):
    r = rng.random()
    if r < 0.3:
        return rng.choice(SP_SPECIAL)
    if r < 0.6:
        return rng.getrandbits(32)
    e = rng.choice([rng.randrange(0, 20), rng.randrange(230, 255), rng.randrange(100, 160)])
    return (rng.getrandbits(1) << 31) | (e << 23) | rng.getrandbits(23)


def rand_sp_as_dp(rng):
    """Operands for single-precision FP instructions: mostly exactly representable singles."""
    if rng.random() < 0.7:
        return sp_to_dp_bits(rand_sp32(rng))
    return rand_dp(rng)


INT64_EDGE = [0, 1, M64, 0x7FFFFFFFFFFFFFFF, 0x8000000000000000, 0x80000000, 0x7FFFFFFF, 0xFFFFFFFF,
              0xFFFFFFFF80000000, 0x100000000, 0x8000, 0x7FFF, 0xFFFF, 0x80, 0x7F, 0xFF]


def rand_i64(rng):
    r = rng.random()
    if r < 0.25:
        return rng.choice(INT64_EDGE)
    if r < 0.4:
        return rng.getrandbits(rng.randrange(1, 64))
    if r < 0.5:
        return (-rng.getrandbits(rng.randrange(1, 64))) & M64
    return rng.getrandbits(64)


def rand_elems(rng, width, n):
    """n random elements of `width` bits with a bias toward boundary values."""
    m = (1 << width) - 1
    edge = [0, 1, m, m >> 1, (m >> 1) + 1, m - 1, 2, (m >> 1) - 1]
    out = []
    for _ in range(n):
        r = rng.random()
        if r < 0.35:
            out.append(rng.choice(edge))
        elif r < 0.5:
            out.append(rng.getrandbits(rng.randrange(1, width + 1)))
        else:
            out.append(rng.getrandbits(width))
    return out


def join_elems(elems, width):
    """Big-endian element order (element 0 is most significant) into (hi, lo)."""
    v = 0
    for e in elems:
        v = (v << width) | (e & ((1 << width) - 1))
    return (v >> 64) & M64, v & M64


def rand_vec(rng, kind):
    """A random 128-bit vector as (hi, lo) for an element kind."""
    if kind == "b":
        return join_elems(rand_elems(rng, 8, 16), 8)
    if kind == "h":
        return join_elems(rand_elems(rng, 16, 8), 16)
    if kind == "w":
        return join_elems(rand_elems(rng, 32, 4), 32)
    if kind == "d":
        return join_elems(rand_elems(rng, 64, 2), 64)
    if kind == "q":
        if rng.random() < 0.3:
            return join_elems(rand_elems(rng, 128, 1), 128)
        return rng.getrandbits(64), rng.getrandbits(64)
    if kind == "x":
        return rng.getrandbits(64), rng.getrandbits(64)
    if kind == "vd":
        return rand_dp(rng), rand_dp(rng)
    if kind == "vf":
        return join_elems([rand_sp32(rng) for _ in range(4)], 32)
    if kind == "sd":  # scalar double in dw0, junk in dw1
        return rand_dp(rng), rng.getrandbits(64)
    if kind == "ss":  # scalar single-as-double in dw0
        return rand_sp_as_dp(rng), rng.getrandbits(64)
    if kind == "qp":
        return rand_qp(rng)
    raise ValueError(kind)


QP_SPECIAL = [
    (0, 0), (1 << 63, 0), (0x3FFF000000000000, 0), (0xBFFF000000000000, 0), (0x4000000000000000, 0),
    (0, 1), (0x0000FFFFFFFFFFFF, M64), (0x0001000000000000, 0), (0x7FFEFFFFFFFFFFFF, M64),
    (0x7FFF000000000000, 0), (0xFFFF000000000000, 0), (0x7FFF800000000000, 0), (0xFFFF800000000000, 5),
    (0x7FFF400000000000, 0), (0x7FFF000000000000, 1), (0x3FFF000000000000, 1), (0x3FFEFFFFFFFFFFFF, M64),
    (0x403E000000000000, 0), (0xC03E000000000000, 0), (0x401E000000000000, 0), (0x403F000000000000, 0),
    (0x3FFE000000000000, 0), (0x4000800000000000, 0),
]


def rand_qp(rng):
    r = rng.random()
    if r < 0.3:
        return rng.choice(QP_SPECIAL)
    if r < 0.5:
        return rng.getrandbits(64), rng.getrandbits(64)
    if r < 0.65:
        e = rng.choice([rng.randrange(0, 40), rng.randrange(0x7FFF - 40, 0x7FFF), rng.randrange(1, 100)])
        return (rng.getrandbits(1) << 63) | (e << 48) | rng.getrandbits(48), rng.getrandbits(64)
    e = rng.randrange(0x3FFF - 130, 0x3FFF + 130)
    if rng.random() < 0.3:
        # few significant bits: exercises exact results
        return (rng.getrandbits(1) << 63) | (e << 48) | rng.getrandbits(48), 0
    return (rng.getrandbits(1) << 63) | (e << 48) | rng.getrandbits(48), rng.getrandbits(64)


# FPSCR bit positions (Java numbering; ISA bit n is 63-n).
FX, FEX, VX, OX, UX, ZX, XX = 31, 30, 29, 28, 27, 26, 25
VXSNAN, VXISI, VXIDI, VXZDZ, VXIMZ, VXVC, FR, FI = 24, 23, 22, 21, 20, 19, 18, 17
VXSOFT, VXSQRT, VXCVI, VE, OE, UE, ZE, XE = 10, 9, 8, 7, 6, 5, 4, 3
VX_BITS = [VXSNAN, VXISI, VXIDI, VXZDZ, VXIMZ, VXVC, VXSOFT, VXSQRT, VXCVI]
STICKY = [FX, OX, UX, ZX, XX] + VX_BITS + [FR, FI, 16, 15, 14, 13, 12]


def rand_fpscr(rng, enables=0.3, sticky=0.3):
    v = rng.randrange(4)
    if rng.random() < enables:
        for b in (VE, OE, UE, ZE, XE):
            if rng.random() < 0.5:
                v |= 1 << b
    if rng.random() < sticky:
        for b in STICKY:
            if rng.random() < 0.3:
                v |= 1 << b
    if rng.random() < 0.2:
        v |= rng.randrange(8) << 32  # DRN
    # never start with an enabled exception pending (would trap while loading state)
    if v & (1 << VE):
        for b in VX_BITS:
            v &= ~(1 << b)
    for e, x in ((OE, OX), (UE, UX), (ZE, ZX), (XE, XX)):
        if v & (1 << e):
            v &= ~(1 << x)
    return v


def rand_cr(rng):
    return rng.getrandbits(32)


def rand_vscr(rng):
    return rng.choice([0, 0x10000, 1, 0x10001])


def enc_x(op, rt, ra, rb, xo, rc=0):
    return (op << 26) | (rt << 21) | (ra << 16) | (rb << 11) | (xo << 1) | rc


def raw(word, text):
    """Assembler line for an instruction llvm-mc does not accept; the text is kept as a comment."""
    return ".long 0x%08x # %s" % (word, text)
