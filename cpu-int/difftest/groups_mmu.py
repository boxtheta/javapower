"""Radix MMU as a PAPR guest (MSR[HV]=0): page sizes, permissions, AMR/IAMR, reference/change
faults, invalid trees, segment faults, quadrants, invalidation.

Real layout: code slot at 0x200000 (one page), data window 0x2FFF00..0x300100 (straddles the
4 KiB page boundary at 0x300000). Page tables live at 0x400000.. and are written per case.
Effective addresses map 1:1 onto these unless a test says otherwise.
"""
import random

from gen import (Case, MSR64LE, MSR32LE, MSR_PR, MSR_IR, MSR_DR, MSR_EE, SLOT, WIN, PROC_TABLE,
                 PT_ROOT, PT_PUD, PT_PMD, PT_PTE, pde, leaf, prtb0, EAA_R, EAA_RW, EAA_X)

SUP = MSR64LE | MSR_IR | MSR_DR
USER = MSR64LE | MSR_PR | MSR_IR | MSR_DR | MSR_EE
MID = WIN + 0x100        # 0x300000
PT_PTE64 = 0x423000      # 64 KiB-page table (NLS 5: 32 entries)
Q3 = 0xC000000000000000


class Tree:
    """Builds a radix tree mapping the code slot and the data window."""

    def __init__(self, pid=1):
        self.e = {}
        self.pid = pid
        self.e[PROC_TABLE + 16 * pid] = prtb0(PT_ROOT)
        if pid != 0:
            self.e[PROC_TABLE] = prtb0(PT_ROOT)
        self.e[PT_ROOT] = pde(PT_PUD, 9)
        self.e[PT_PUD] = pde(PT_PMD, 9)

    def map2m(self, flags_leaf):
        self.e[PT_PMD + 8] = flags_leaf
        return self

    def map4k(self, code, lo, hi):
        """code: leaf for 0x200000; lo: leaf for page 0x2FF000; hi: leaf for page 0x300000."""
        self.e[PT_PMD + 8] = pde(PT_PTE, 9)
        if code is not None:
            self.e[PT_PTE + 8 * 0x000] = code
        if lo is not None:
            self.e[PT_PTE + 8 * 0x0FF] = lo
        if hi is not None:
            self.e[PT_PTE + 8 * 0x100] = hi
        return self

    def map64k(self, code, lo, hi):
        self.e[PT_PMD + 8] = pde(PT_PTE64, 5)
        if code is not None:
            self.e[PT_PTE64 + 8 * 0] = code
        if lo is not None:
            self.e[PT_PTE64 + 8 * 15] = lo
        if hi is not None:
            self.e[PT_PTE64 + 8 * 16] = hi
        return self

    def map1g(self, flags_leaf):
        self.e[PT_PUD] = flags_leaf
        self.e.pop(PT_PMD + 8, None)
        return self

    def apply(self, c):
        assert len(self.e) <= 12, c.name
        c.patch = sorted(self.e.items())
        c.s("PIDR", self.pid)
        return c


def code_leaf(user=True, eaa=EAA_R | EAA_X, **kw):
    return leaf(0x200000, eaa=eaa, priv=not user, **kw)


ACCESSES = {
    "lbz": "lbz 3, 0(4)", "stb": "stb 3, 0(4)", "ld": "ld 3, 0(4)", "std": "std 3, 0(4)",
    "lwarx": "lwarx 3, 0, 4", "stwcx": "stwcx. 3, 0, 4", "dcbz": "dcbz 0, 4", "dcbf": "dcbf 0, 4",
    "dcbst": "dcbst 0, 4", "icbi": "icbi 0, 4", "dcbt": "dcbt 0, 4", "lmw": "lmw 28, 0(4)",
    "lwat": "lwat 6, 4, 0", "stwat": "stwat 6, 4, 0", "lq": "lq 6, 0(4)",
}


class G:
    def __init__(self, rng):
        self.rng = rng
        self.n = 0
        self.out = []

    def case(self, mn, code, tree, msr, regs=None, **kw):
        self.n += 1
        c = Case("%s-%d" % (mn, self.n), code, msr=msr, pc=kw.get("pc", SLOT))
        tree.apply(c)
        for k, v in (regs or {}).items():
            c.r(k, v)
        for name, v in kw.get("sprs", {}).items():
            c.s(name, v)
        self.out.append(c)
        return c


DATA_FLAGS = [
    ("rwx", dict(eaa=EAA_RW | EAA_R | EAA_X)), ("r", dict(eaa=EAA_R)), ("rw", dict(eaa=EAA_RW)),
    ("x", dict(eaa=EAA_X)), ("none", dict(eaa=0)), ("priv", dict(eaa=7, priv=True)),
    ("noR", dict(eaa=7, r=False)), ("noC", dict(eaa=7, c=False)), ("noRC", dict(eaa=7, r=False, c=False)),
    ("io", dict(eaa=7, att=2)), ("privr", dict(eaa=EAA_R, priv=True)),
]


def gen_access(g):
    """Data accesses under every permission combination, 4K/64K/2M/1G pages, both privileges."""
    rng = g.rng
    for size in ["4k", "64k", "2m", "1g"]:
        for fname, flags in DATA_FLAGS:
            for msr in [SUP, USER]:
                for acc, insn in ACCESSES.items():
                    if size in ("2m", "1g") and fname in ("priv", "privr", "x", "none", "r") and msr == USER:
                        continue  # the code page shares the mapping and would fault first
                    t = Tree()
                    user_code = msr == USER
                    if size == "4k":
                        t.map4k(code_leaf(user_code), leaf(0x2FF000, **flags), leaf(0x300000, **flags))
                    elif size == "64k":
                        t.map64k(leaf(0x200000, eaa=5, priv=not user_code), leaf(0x2F0000, **flags),
                                 leaf(0x300000, **flags))
                    elif size == "2m":
                        f = dict(flags)
                        f["eaa"] = f.get("eaa", 7) | EAA_X
                        t.map2m(leaf(0x200000, **f))
                    else:
                        f = dict(flags)
                        f["eaa"] = f.get("eaa", 7) | EAA_X
                        t.map1g(leaf(0, **f))
                    ea = rng.choice([MID - 16, MID + 32, MID - 4])
                    if acc in ("ld", "std"):
                        ea = rng.choice([MID - 8, MID - 4, MID + 8])  # sometimes crossing pages
                    g.case("acc%s%s%s" % (size, fname, acc), [insn], t, msr,
                           {3: rng.getrandbits(64), 4: ea, 6: 1, 7: 2})


def gen_rc(g):
    """Reference/change faults when one instruction's access type follows another's."""
    for r, c in [(True, False), (False, False), (False, True), (True, True)]:
        for msr in [SUP, USER]:
            for code in [["lbz 3, 0(4)", "stb 3, 1(4)"], ["stb 3, 0(4)", "lbz 6, 1(4)"],
                         ["lwarx 3, 0, 4", "stwcx. 3, 0, 4"], ["dcbz 0, 4"], ["icbi 0, 4"],
                         ["lbz 3, 0(4)", "lbz 6, 0x80(4)", "stb 3, 0x81(4)"]]:
                t = Tree().map4k(code_leaf(msr == USER), leaf(0x2FF000), leaf(0x300000, r=r, c=c))
                g.case("rc", code, t, msr, {4: MID + 0x40})


def gen_cross(g):
    """Accesses crossing into an unmapped or protected page: which address is reported?"""
    for msr in [SUP, USER]:
        for hi in [None, leaf(0x300000, eaa=EAA_R), leaf(0x300000, eaa=7, r=False)]:
            for lo in [leaf(0x2FF000, eaa=7)]:
                for insn, ea in [("ld 3, 0(4)", MID - 4), ("std 3, 0(4)", MID - 4), ("lwz 3, 0(4)", MID - 2),
                                 ("sth 3, 0(4)", MID - 1), ("lmw 26, 0(4)", MID - 8), ("stmw 26, 0(4)", MID - 8),
                                 ("lswi 8, 4, 12", MID - 4), ("stswi 8, 4, 12", MID - 4), ("lq 6, 0(4)", MID - 8),
                                 ("stq 6, 0(4)", MID - 8)]:
                    if insn.startswith(("lmw", "stmw", "lswi", "stswi")):
                        m = msr & ~1  # big-endian only
                        t = Tree().map4k(code_leaf(msr == USER), lo, hi)
                        c = g.case("cross", [insn], t, m, {3: 0x1122334455667788, 4: ea})
                        # Code must be big-endian too: the generator encodes per case MSR.
                    else:
                        t = Tree().map4k(code_leaf(msr == USER), lo, hi)
                        g.case("cross", [insn], t, msr, {3: 0x1122334455667788, 4: ea})


def gen_fetch(g):
    """Instruction fetch faults: the code page's own permissions."""
    for name, leafv in [("ok", code_leaf()), ("nox", code_leaf(eaa=EAA_R)), ("noR", code_leaf(r=False)),
                        ("priv", code_leaf(user=False)), ("io", code_leaf(att=2)),
                        ("rwx", code_leaf(eaa=7)), ("none", code_leaf(eaa=0)),
                        ("inval", leaf(0x200000) & ~(1 << 63))]:
        for msr in [SUP, USER, MSR64LE | MSR_IR, MSR32LE | MSR_IR | MSR_DR]:
            for iamr in [0, 1 << 62]:
                t = Tree().map4k(leafv, leaf(0x2FF000), leaf(0x300000))
                g.case("fetch" + name, ["nop"], t, msr, sprs={"IAMR": iamr})
    # Branch into a non-executable page.
    t = Tree().map4k(code_leaf(), leaf(0x2FF000, eaa=EAA_RW), leaf(0x300000, eaa=EAA_RW | EAA_X))
    for tgt in [0x2FF000, 0x300000]:
        for msr in [SUP, USER]:
            g.case("fetchbr", ["mtctr 4", "bctr"], t, msr, {4: tgt})


def gen_amr(g):
    """AMR/IAMR (key 0) apply to supervisor accesses of non-privileged pages only."""
    for amr in [0, 1 << 62, 2 << 62, 3 << 62, 0x3FFFFFFFFFFFFFFF]:
        for priv in [False, True]:
            for msr in [SUP, USER]:
                for insn in ["lbz 3, 0(4)", "stb 3, 0(4)", "lwarx 3, 0, 4", "dcbz 0, 4", "dcbf 0, 4"]:
                    t = Tree().map4k(code_leaf(msr == USER), leaf(0x2FF000, priv=priv), leaf(0x300000, priv=priv))
                    g.case("amr", [insn], t, msr, {4: MID + 64}, sprs={"AMR": amr})
    # Change AMR between two accesses of the same page (the TLB must not keep stale rights).
    for amr in [1 << 62, 2 << 62]:
        t = Tree().map4k(code_leaf(False), leaf(0x2FF000), leaf(0x300000))
        g.case("amrchg", ["lbz 3, 0(4)", "stb 3, 1(4)", "mtspr 29, 5", "lbz 6, 2(4)", "stb 6, 3(4)"], t, SUP,
               {4: MID, 5: amr})
    # IAMR on a supervisor fetch of a non-privileged code page.
    for iamr in [1 << 62, 2 << 62, 3 << 62]:
        for priv in [False, True]:
            t = Tree().map4k(code_leaf(user=not priv), leaf(0x2FF000), leaf(0x300000))
            g.case("iamr", ["nop"], t, SUP, sprs={"IAMR": iamr})


def gen_tree(g):
    """Malformed and incomplete trees."""
    good = leaf(0x200000)
    variants = {
        "rootinval": {PT_ROOT: pde(PT_PUD, 9) & ~(1 << 63)},
        "pudinval": {PT_PUD: pde(PT_PMD, 9) & ~(1 << 63)},
        "pmdinval": {PT_PMD + 8: good & ~(1 << 63)},
        "pudnls5": {PT_PUD: pde(PT_PMD, 5)},
        "pudnls13": {PT_PUD: pde(PT_PMD, 13)},
        "pmdnls9": {PT_PMD + 8: pde(PT_PTE, 9), PT_PTE: good, PT_PTE + 8 * 0x100: leaf(0x300000)},
        "pmdnls7": {PT_PMD + 8: pde(PT_PTE, 7), PT_PTE: good},
        "pmdnls5": {PT_PMD + 8: pde(PT_PTE64, 5), PT_PTE64: good},
        "pteleafless": {PT_PMD + 8: pde(PT_PTE, 9), PT_PTE: pde(PT_PTE64, 9)},
        "prtbzero": {PROC_TABLE + 16: 0},
        "rts51": {PROC_TABLE + 16: prtb0(PT_ROOT, rts=51)},
        "rootnls12": {PROC_TABLE + 16: prtb0(PT_ROOT, nls=12)},
        "rootmisaligned": {PROC_TABLE + 16: prtb0(PT_ROOT + 0x100)},
        "pudmisaligned": {PT_ROOT: pde(PT_PUD + 0x80, 9), PT_PUD + 0x80: pde(PT_PMD, 9)},
        "leafrpnhigh": {PT_PMD + 8: leaf(0x200000) | 0x00F0000000000000},
        "leafpmdunaligned": {PT_PMD + 8: leaf(0x210000)},
        "pud1g": {PT_PUD: leaf(0)},
        "pud1gunaligned": {PT_PUD: leaf(0x200000)},
    }
    for name, upd in variants.items():
        for msr in [SUP, USER, MSR64LE | MSR_DR]:
            t = Tree().map2m(good)
            t.e.update(upd)
            # Data access in the window, executed from real-mode code when only DR is on.
            g.case("tree" + name, ["lbz 3, 0(4)", "stb 3, 8(4)"], t, msr, {4: MID})
    # PID beyond the 4 KiB process table (256 entries) and an unregistered PID entry.
    for pid in [255, 256, 300]:
        t = Tree(pid=pid if pid < 256 else 1).map2m(good)
        c = g.case("pid", ["nop"], t, MSR64LE | MSR_DR | MSR_IR)
        c.s("PIDR", pid)
    for msr in [SUP, USER, MSR64LE | MSR_DR]:
        t = Tree().map2m(good)
        g.case("pid", ["lbz 3, 0(4)"], t, msr, {4: MID}, sprs={"PIDR": 7})


def gen_quadrant(g):
    good = leaf(0x200000, priv=False)
    for msr in [SUP, USER, MSR64LE | MSR_DR]:
        for ea in [MID, Q3 | MID, 0x4000000000000000 | MID, 0x8000000000000000 | MID,
                   0x0010000000000000 | MID, 0xC010000000000000 | MID, 0x0000000100300000,
                   0xFFFFFFFFFFFFFFF0]:
            t = Tree().map2m(good)
            g.case("quad", ["lbz 3, 0(4)"], t, msr, {4: ea})
            g.case("quad", ["stb 3, 0(4)"], t, msr, {4: ea})
    # Instruction fetch from the other quadrants (the code page is mapped at both 0 and 3).
    for pc in [Q3 | SLOT, 0x4000000000000000 | SLOT, 0x8000000000000000 | SLOT, 0x0100000000200000]:
        for msr in [SUP, USER]:
            t = Tree().map2m(leaf(0x200000, priv=False))
            g.case("quadfetch", ["nop"], t, msr, pc=pc)
    # 32-bit mode: high EA bits are dropped before translation.
    for msr in [MSR32LE | MSR_IR | MSR_DR, MSR32LE | MSR_IR | MSR_DR | MSR_PR | MSR_EE]:
        t = Tree().map2m(leaf(0x200000))
        g.case("quad32", ["lbz 3, 0(4)", "stb 3, 0(5)"], t, msr, {4: Q3 | MID, 5: 0x4000000000300004})
    # Quadrant 3 uses PID 0 even with PIDR != 0: different trees per PID.
    for msr in [SUP, USER]:
        t = Tree().map2m(leaf(0x200000))
        t.e[PROC_TABLE] = prtb0(PT_ROOT + 0x8000 * 0)  # same root for PID 0
        g.case("quad3", ["lbz 3, 0(4)"], t, msr, {4: Q3 | MID})


def gen_inval(g):
    """TLB invalidation: stale translations survive a PTE change until tlbie/tlbiel/PID change."""
    # Map the page tables themselves (PMD[2] -> 0x400000, privileged) so the code can edit them.
    pmd1 = PT_PMD + 8
    for flush in ["", "tlbiel", "tlbie", "pidr", "ptesync", "isync", "slbia", "mtmsrd"]:
        for invalidate in ["clearV", "readonly", "remap"]:
            t = Tree().map4k(code_leaf(False), leaf(0x2FF000), leaf(0x300000))
            t.e[PT_PMD + 16] = leaf(0x400000, priv=True)
            pte_hi = PT_PTE + 8 * 0x100
            new = {"clearV": leaf(0x300000) & ~(1 << 63), "readonly": leaf(0x300000, eaa=EAA_R),
                   "remap": leaf(0x2FF000)}[invalidate]
            code = ["ld 3, 0(4)", "std 3, 8(4)", "stdbrx 6, 0, 7", "ptesync", "ld 8, 16(4)", "std 8, 24(4)"]
            mid = {"": [], "tlbiel": [0x7C000000 | (5 << 11) | (274 << 1) | (1 << 17) | (1 << 16) | (2 << 18)],
                   "tlbie": [0x7C000000 | (5 << 11) | (306 << 1) | (1 << 17) | (1 << 16) | (2 << 18)],
                   "pidr": ["li 9, 2", "mtspr 48, 9", "li 9, 1", "mtspr 48, 9"],
                   "ptesync": ["ptesync"], "isync": ["isync"], "slbia": [0x7C0003E4], "mtmsrd": ["mfmsr 9",
                                                                                             "mtmsrd 9, 0"]}[flush]
            code = code + mid + ["ld 10, 32(4)", "std 10, 40(4)"]
            t.e[PROC_TABLE + 32] = prtb0(PT_ROOT)  # PID 2: same tree
            g.case("inval%s%s" % (invalidate, flush), code, t, SUP,
                   {4: MID, 5: 0xC00, 6: new, 7: pte_hi})


def mmu():
    g = G(random.Random(51))
    gen_access(g)
    gen_rc(g)
    gen_cross(g)
    gen_fetch(g)
    gen_amr(g)
    gen_tree(g)
    gen_quadrant(g)
    gen_inval(g)
    return g.out


GROUPS = {"mmu": mmu}
