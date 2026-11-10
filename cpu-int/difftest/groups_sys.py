"""System instructions and interrupt delivery: MSR moves, rfid/rfscv, sc/scv, privilege checks,
TLB/SLB management, decrementer, wait, transactional-memory stubs, AIL relocation.

Every case maps 0..4 MiB (map_std) so instructions can switch to problem state or translation.
"""
import random
import struct

from gen import (Case, MSR64LE, MSR64BE, MSR32LE, MSR_PR, MSR_IR, MSR_DR, MSR_EE, MSR_LE, MSR_SF,
                 MSR_ME, MSR_FP, MSR_RI, SLOT, map_std, xform)

M64 = (1 << 64) - 1
USER = MSR64LE | MSR_PR | MSR_IR | MSR_DR | MSR_EE
VIRT = MSR64LE | MSR_IR | MSR_DR
SC = 0x44000002
TM = 1 << 32
SE = 1 << 10
BE = 1 << 9
VEC = 1 << 25
VSX = 1 << 23
FE0 = 1 << 11
FE1 = 1 << 8
PMM = 1 << 2
HV = 1 << 60
S = 1 << 22
MSR_RANDOM_BITS = [MSR_SF, HV, TM, VEC, VSX, MSR_EE, MSR_PR, MSR_FP, MSR_ME, FE0, FE1, MSR_IR, MSR_DR,
                   PMM, MSR_RI, MSR_LE, S, 1 << 40, 1 << 33, 1 << 26, 1 << 16, 1 << 6, 1 << 3]


def swap32(w):
    return struct.unpack("<I", struct.pack(">I", w))[0]


def rand_msr(rng, allow=None):
    v = 0
    for b in MSR_RANDOM_BITS:
        if rng.random() < 0.5:
            v |= b
    return v


class G:
    def __init__(self, rng):
        self.rng = rng
        self.n = 0
        self.out = []

    def case(self, mn, code, regs=None, msr=MSR64LE, pc=SLOT, cr=0, xer=0, sc=True):
        self.n += 1
        c = Case("%s-%d" % (mn, self.n), code, msr=msr, pc=pc, sc=sc)
        map_std(c)
        for k, v in (regs or {}).items():
            c.r(k, v)
        c.cr = cr
        c.xer = xer
        self.out.append(c)
        return c


def tlb(xo, rs, ric, prs, r, rb):
    return (31 << 26) | (rs << 21) | (ric << 18) | (prs << 17) | (r << 16) | (rb << 11) | (xo << 1)


def gen_msr(g):
    rng = g.rng
    for msr in [MSR64LE, MSR32LE, VIRT, USER, MSR64LE | TM | VEC | VSX | MSR_FP]:
        g.case("mfmsr", ["mfmsr 3"], msr=msr)
    for k in range(60):
        v = rand_msr(rng)
        # MSR[LE] cannot be changed by mtmsr[d]; SE/BE would request trace interrupts.
        v &= ~(SE | BE)
        for mn in ["mtmsrd 5, 0", "mtmsrd 5, 1", "mtmsr 5, 0", "mtmsr 5, 1"]:
            g.case(mn.split()[0] + ("l" if mn.endswith("1") else ""), [mn], {5: v})
    for mn in ["mtmsrd 5, 0", "mtmsr 5, 0", "mfmsr 3"]:
        g.case(mn.split()[0] + "user", [mn], {5: MSR64LE}, msr=USER)
    # Reserved bits set.
    g.case("mfmsr", [xform(31, 83, 3, 1)])
    g.case("mtmsrd", [xform(31, 178, 5, 0, 1)], {5: MSR64LE})


def gen_rfi(g):
    rng = g.rng
    for k in range(80):
        srr1 = rand_msr(rng) & ~(SE | BE)
        if rng.random() < 0.3:
            srr1 |= rng.getrandbits(64) & ~(SE | BE)
        new_le = (srr1 & MSR_LE) != 0
        tgt_idx = rng.choice([4, 5, 6])
        tgt = SLOT + 4 * tgt_idx + rng.choice([0, 0, 2]) | (rng.choice([0, 0xFFFFFFFF00000000]) if
                                                           (srr1 >> 63) == 0 else 0)
        # Pad words in the endianness the target will run with.
        pad = SC if new_le else swap32(SC)
        code = ["mtsrr0 4", "mtsrr1 5", "rfid", 0, pad, pad, pad]
        g.case("rfid", code, {4: tgt, 5: srr1}, sc=False)
    for k in range(20):
        ctr = rand_msr(rng) & ~(SE | BE) | MSR_LE
        code = ["mtlr 4", "mtctr 5", 0x4C0000A4, 0, SC, SC, SC]  # rfscv
        g.case("rfscv", code, {4: SLOT + 16, 5: ctr}, sc=False)
    g.case("rfid", ["rfid"], msr=USER)
    g.case("rfscv", [0x4C0000A4], msr=USER)
    g.case("hrfid", ["hrfid"])
    g.case("hrfid", ["hrfid"], msr=USER)
    g.case("rfid", [xform(19, 18) | (1 << 21)])  # reserved bit
    g.case("rfebb", [xform(19, 146) | (1 << 11)], msr=USER)


def gen_sc(g):
    rng = g.rng
    for msr in [MSR64LE, USER, MSR32LE, MSR64BE]:
        for lev in [0, 2, 5, 64, 127]:
            g.case("sc", [(17 << 26) | (lev << 5) | 2], {3: rng.getrandbits(64)}, msr=msr)
    # sc 1 from problem state: the hypervisor returns H_PRIVILEGE.
    g.case("sc1user", [(17 << 26) | (1 << 5) | 2], {3: 0x58}, msr=USER)
    g.case("sc", [(17 << 26) | 2 | (1 << 12)])  # reserved bit: illegal
    for msr in [MSR64LE, USER, VIRT, MSR32LE | MSR_EE]:
        for fscr in [0, 1 << 12, 0xFFFFFFFFFFFFFFFF]:
            for lev in [0, 1, 7, 127]:
                g.case("scv", [(17 << 26) | (lev << 5) | 1], msr=msr).s("FSCR", fscr)


def gen_priv(g):
    """Supervisor-only and hypervisor-only instructions."""
    insns = {
        "mfmsr": "mfmsr 3", "mtmsrd": "mtmsrd 5, 0", "mtmsrdl": "mtmsrd 5, 1", "mtmsr": "mtmsr 5, 0",
        "slbia": xform(31, 498), "slbie": "slbie 5", "slbieg": xform(31, 466, 6, 0, 5),
        "slbiag": xform(31, 850, 6), "slbmte": "slbmte 6, 6", "slbmfee": "slbmfee 3, 5",
        "slbmfev": "slbmfev 3, 5", "slbsync": xform(31, 338), "slbfee": xform(31, 979, 3, 0, 5, 1),
        "tlbsync": "tlbsync", "tlbia": xform(31, 370), "dcbi": xform(31, 470, 0, 0, 5),
        "msgsndp": xform(31, 142, 0, 0, 5), "msgclrp": xform(31, 174, 0, 0, 5),
        "msgsnd": xform(31, 206, 0, 0, 5), "msgclr": xform(31, 238, 0, 0, 5), "msgsync": xform(31, 886),
        "stop": xform(19, 370), "doze": xform(19, 402), "nap": xform(19, 434), "sleep": xform(19, 466),
        "rvwinkle": xform(19, 498), "clrbhrb": xform(31, 430), "mfbhrbe": xform(31, 302, 3) | (1 << 11),
        "ldcix": xform(31, 885, 3, 0, 5), "stdcix": xform(31, 1013, 3, 0, 5), "attn": 0x00000200,
        "tlbie": tlb(306, 0, 0, 1, 1, 5), "tlbiel": tlb(274, 0, 0, 1, 1, 5),
    }
    for mn, insn in insns.items():
        for msr in [MSR64LE, USER]:
            g.case("priv" + mn, [insn], {5: 0x200000, 6: 0}, msr=msr)


def gen_tlb(g):
    rng = g.rng
    for xo, mn in [(306, "tlbie"), (274, "tlbiel")]:
        for ric in range(4):
            for prs in range(2):
                for r in range(2):
                    for is_ in range(4):
                        for ap in [0, 1, 5, 2]:
                            rb = 0x200000 | (is_ << 10) | (ap << 5)
                            g.case(mn, [tlb(xo, 4, ric, prs, r, 5)], {4: rng.choice([0, 1]), 5: rb})
        # 32-bit mode: the address operand is truncated (tlbie).
        g.case(mn, [tlb(xo, 4, 0, 1, 1, 5)], {4: 1, 5: 0xFFFFFFFF00200000}, msr=MSR32LE)
    for ih in range(8):
        g.case("slbia", [xform(31, 498) | (ih << 21)])


def gen_dec(g):
    rng = g.rng
    # A negative DEC raises the interrupt as soon as MSR[EE] allows.
    for msr in [MSR64LE | MSR_EE, MSR64LE, USER]:
        for v in [0xFFFFFFFFFFFFFFFF, 0x80000000, 0x0080000000000000, 0x7FFFFFFF, 0xFF7FFFFFFFFFFFFF]:
            g.case("mtdec", ["mtdec 5", "nop", "nop"], {5: v}, msr=msr)
    g.case("mtdec", ["mtdec 5", "li 6, 0x4001", "mtmsrd 6, 1", "nop"], {5: M64}, msr=MSR64LE)
    g.case("mtdec", ["mtdec 5", "mfdec 3", "nop"], {5: M64}, msr=MSR64LE).ignore.append("r3")
    # wait: idles until the decrementer fires (EE=1: interrupt; EE=0: just resumes).
    for msr in [MSR64LE | MSR_EE, MSR64LE]:
        g.case("wait", ["li 5, 2000", "mtdec 5", xform(31, 30), "nop"], msr=msr)
    g.case("wait", [xform(31, 30) | (1 << 21)])  # WC=1: reserved
    g.case("wait", [xform(31, 30) | (2 << 21)])
    g.case("wait", [xform(31, 30) | (1 << 11)])  # reserved bit


def gen_tm(g):
    rng = g.rng
    tm_insns = {"tbegin": xform(31, 654, 0, 0, 0, 1), "tbeginr": xform(31, 654, 0, 0, 0, 1) | (1 << 21),
                "tend": xform(31, 686, 0, 0, 0, 1), "tendall": xform(31, 686, 1 << 4, 0, 0, 1),
                "tabort": xform(31, 910, 0, 5, 0, 1), "tcheck": xform(31, 718, 3 << 2),
                "tsr": xform(31, 750, 0, 0, 0, 1) | (1 << 21), "treclaim": xform(31, 942, 0, 5, 0, 1),
                "trechkpt": xform(31, 1006, 0, 0, 0, 1),
                "tabortwc": xform(31, 782, 4, 5, 6, 1), "tabortdci": xform(31, 878, 4, 5, 6, 1)}
    for mn, insn in tm_insns.items():
        for msr in [MSR64LE, MSR64LE | TM, USER, USER | TM]:
            g.case(mn, [insn], {5: 0x1234, 6: 0}, msr=msr, cr=rng.getrandbits(32))


def gen_ail(g):
    rng = g.rng
    tests = {"tw": "tw 31, 0, 0", "ill": 0, "sc": "sc", "scv": (17 << 26) | (3 << 5) | 1,
             "priv": "mfmsr 3", "lwarx": "lwarx 3, 0, 5"}
    for mn, insn in tests.items():
        for msr in [VIRT, USER, MSR64LE | MSR_IR, MSR64LE | MSR_DR, MSR64LE, MSR32LE | MSR_IR | MSR_DR]:
            c = g.case("ail" + mn, [insn], {5: 0x300001}, msr=msr)
            c.ail = 3
            c.s("FSCR", 1 << 12)


def gen_cfar(g):
    for code in [["b .+4", "mfspr 3, 28"], ["bc 4, 0, .+8", "mfspr 3, 28"], ["bc 12, 0, .+4", "mfspr 3, 28"],
                 ["bl .+4", "mfspr 3, 28"], ["mfspr 3, 28"], ["nop", "mtspr 28, 5", "mfspr 3, 28"]]:
        g.case("cfar", code, {5: 0x1234}, cr=0x80000000)
        g.case("cfar", code, {5: 0x1234}, cr=0)


def gen_misc(g):
    g.case("isync", [xform(19, 150) | (1 << 11)])  # reserved bit
    g.case("mftb", ["mftb 3", "mftbu 4"]).ignore += ["r3", "r4"]
    g.case("mftb", [xform(31, 371, 3) | (500 << 11)])  # undefined TBR
    for msr in [MSR64LE, USER]:
        g.case("darn", ["darn 3, 1"], msr=msr).ignore.append("r3")
        g.case("cpabort", [xform(31, 838)], msr=msr)


def sys():
    g = G(random.Random(41))
    gen_msr(g)
    gen_rfi(g)
    gen_sc(g)
    gen_priv(g)
    gen_tlb(g)
    gen_dec(g)
    gen_tm(g)
    gen_ail(g)
    gen_cfar(g)
    gen_misc(g)
    return g.out


def spr():
    """mfspr/mtspr for every SPR number, in supervisor and problem state."""
    rng = random.Random(42)
    out = []
    timey = {22, 268, 269, 308, 309, 848, 849, 1006, 777, 778, 779, 780, 781, 782, 787, 788, 789, 790,
             791, 792, 771, 772, 773, 774, 775, 776, 793, 794, 795, 796, 797, 798}
    pmu = set(range(768, 800))
    # All reads first: they see the SPRs as QEMU initialises them (writes would persist).
    for mode, msr in [("s", MSR64LE), ("u", USER)]:
        for n in range(1024):
            c = Case("mf%s-%d" % (mode, n), ["mfspr 3, %d" % n], msr=msr)
            map_std(c)
            c.r(3, 0x3333333333333333)
            if n in timey:
                c.ignore.append("r3")
            out.append(c)
    for mode, msr in [("s", MSR64LE), ("u", USER)]:
        for n in range(1024):
            v = 0 if n in pmu else rng.choice([0xFFFFFFFFFFFFFFFF, 0x5A5A5A5AA5A5A5A5, 0x0123456789ABCDEF])
            if n in (22,):  # DEC: keep it positive so no interrupt is pending
                v = 0x7FFFFFFF
            if n == 48:  # PIDR: stay on the mapped PID
                v = 1
            if n == 1:  # XER: QEMU keeps reserved bits 32:63, we do not (known difference)
                v = 0xE00C007F
            c = Case("mt%s-%d" % (mode, n), ["mtspr %d, 5" % n, "mfspr 6, %d" % n], msr=msr)
            map_std(c)
            c.r(5, v)
            c.r(6, 0x6666666666666666)
            if n in timey:
                c.ignore.append("r6")
            out.append(c)
    return out


GROUPS = {"sys": sys, "spr": spr}
