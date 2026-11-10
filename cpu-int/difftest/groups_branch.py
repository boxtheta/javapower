"""Branches, CR logical operations and CR moves, in 64- and 32-bit mode.

The instruction under test sits at SLOT+16 between `sc` landing pads (SLOT..SLOT+12 and
SLOT+20..), so SRR0 of the final system call tells where a branch went.
"""
import random

from gen import Case, MSR64LE, MSR32LE, MSR64BE, SLOT, xform

M64 = (1 << 64) - 1
PC = SLOT + 16


def bcase(name, insn, msr, rng, **kw):
    c = Case(name, ["sc"] * 4 + [insn], msr=msr, pc=PC)
    c.cr = kw.get("cr", rng.getrandbits(32))
    if "ctr" in kw:
        c.ctr = kw["ctr"]
    if "lr" in kw:
        c.lr = kw["lr"]
    return c


CTRS = [0, 1, 2, 3, 0x100000000, 0x100000001, 0xFFFFFFFF, 0xFFFFFFFFFFFFFFFF, 0x8000000000000001]


def gen(prefix, msr, rng, scale=1.0):
    out = []
    n = [0]

    def nm(mn):
        n[0] += 1
        return "%s%s-%d" % (prefix, mn, n[0])

    hi = 0xFFFFFFFF00000000 if (msr >> 63) == 0 else 0
    for off in [-16, -8, -4, 4, 8, 12]:
        for lk in ["", "l"]:
            out.append(bcase(nm("b"), "b%s .%+d" % (lk, off), msr, rng))
    for tgt in [SLOT, SLOT + 8, SLOT + 24]:
        for lk in ["", "l"]:
            out.append(bcase(nm("b"), "b%sa 0x%x" % (lk, tgt), msr, rng))
    for bo in range(32):
        for k in range(max(2, int(6 * scale))):
            bi = rng.randint(0, 31)
            ctr = rng.choice(CTRS)
            off = rng.choice([-16, 8, 12])
            lk = rng.choice(["", "l"])
            out.append(bcase(nm("bc"), "bc%s %d, %d, .%+d" % (lk, bo, bi, off), msr, rng, ctr=ctr))
            out.append(bcase(nm("bca"), "bc%sa %d, %d, 0x2000" % (lk, bo, bi), msr, rng, ctr=ctr))
            lr = rng.choice([SLOT + 8, SLOT + 11, SLOT + 24 | hi, SLOT])
            for bh in [0]:
                out.append(bcase(nm("bclr"), "bclr%s %d, %d, %d" % (lk, bo, bi, bh), msr, rng, ctr=ctr, lr=lr))
            tgt = rng.choice([SLOT + 8, SLOT + 10, SLOT | hi])
            if bo & 4:
                out.append(bcase(nm("bcctr"), "bcctr%s %d, %d, 0" % (lk, bo, bi), msr, rng, ctr=tgt))
            else:
                # BO_2=0 is an invalid form for bcctr.
                out.append(bcase(nm("bcctr"), "bcctr%s %d, %d, 0" % (lk, bo, bi), msr, rng, ctr=tgt))
            c = bcase(nm("bctar"), xform(19, 560, bo, bi, 0, 1 if lk else 0), msr, rng, ctr=ctr)
            c.s("TAR", rng.choice([SLOT + 8, SLOT + 9, SLOT | hi]))
            out.append(c)
    # CR logical.
    for mn in ["crand", "crandc", "creqv", "crnand", "crnor", "cror", "crorc", "crxor"]:
        for k in range(int(30 * scale) + 4):
            out.append(bcase(nm(mn), "%s %d, %d, %d" % (mn, rng.randint(0, 31), rng.randint(0, 31),
                                                       rng.randint(0, 31)), msr, rng))
    for k in range(int(30 * scale) + 4):
        out.append(bcase(nm("mcrf"), "mcrf %d, %d" % (rng.randint(0, 7), rng.randint(0, 7)), msr, rng))
    # CR moves. mfocrf/mtocrf with a non-single FXM are undefined; QEMU gives fixed results.
    fxms = [0, 1, 2, 4, 8, 16, 32, 64, 128, 3, 0xFF, 0x81]
    for fxm in fxms:
        for k in range(3):
            c = bcase(nm("mfcr"), "mfcr 3", msr, rng)
            out.append(c)
            c = bcase(nm("mfocrf"), xform(31, 19, 3) | 0x00100000 | (fxm << 12), msr, rng)
            c.r(3, rng.getrandbits(64))
            out.append(c)
            c = bcase(nm("mtcrf"), "mtcrf %d, 4" % fxm, msr, rng)
            c.r(4, rng.getrandbits(64))
            out.append(c)
            c = bcase(nm("mtocrf"), xform(31, 144, 4) | 0x00100000 | (fxm << 12), msr, rng)
            c.r(4, rng.getrandbits(64))
            out.append(c)
    for d in [0, 1, -1, 0x7FFF, -0x8000, 0x1234]:
        out.append(bcase(nm("addpcis"), "addpcis 3, %d" % d, msr, rng))
    return out


def branch():
    rng = random.Random(21)
    return (gen("", MSR64LE, rng) + gen("w", MSR32LE, rng, 0.5) + gen("be", MSR64BE, rng, 0.2))


GROUPS = {"branch": branch}
