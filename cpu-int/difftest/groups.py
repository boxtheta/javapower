"""Registry of test-case groups: name -> generator of gen.Case."""
import copy
import importlib

_MODULES = ["groups_alu", "groups_branch", "groups_ldst", "groups_sys", "groups_mmu"]

GROUPS = {}


def _selftest():
    from gen import Case, MSR64BE, MSR32LE
    yield Case("selftest-nop", ["nop"])
    yield Case("selftest-add", ["add 3, 4, 5"])
    yield Case("selftest-be", ["addi 3, 3, 1"], msr=MSR64BE)
    yield Case("selftest-32", ["addi 3, 3, 1"], msr=MSR32LE)
    yield Case("selftest-ill", [0])
    yield Case("selftest-st", ["stw 3, 0(4)"]).r(4, 0x2FFF00 + 0x100)


GROUPS["selftest"] = _selftest

for _m in _MODULES:
    try:
        mod = importlib.import_module(_m)
    except ModuleNotFoundError:
        continue
    GROUPS.update(mod.GROUPS)

# Groups whose firmware is built big-endian, so every interrupt is delivered with LPCR[ILE]=0.
BE_FIRMWARE = set()


def _ile0(base):
    """Variant of group `base` run under the big-endian firmware: each case as generated
    ("le" prefix, only cases whose MSR has LE set: little-endian code taking big-endian
    interrupts) and with MSR[LE] cleared ("be" prefix: big-endian code and interrupts)."""
    from gen import MSR_LE

    def gen():
        out = []
        cases = list(GROUPS[base]())
        for c in cases:
            if c.msr & MSR_LE:
                d = copy.deepcopy(c)
                d.name = "le" + c.name
                out.append(d)
        for c in cases:
            d = copy.deepcopy(c)
            d.name = "be" + c.name
            d.msr &= ~MSR_LE
            out.append(d)
        return out
    return gen


for _b in ["selftest", "sys", "mmu", "trace"]:
    if _b in GROUPS:
        GROUPS[_b + "be"] = _ile0(_b)
        BE_FIRMWARE.add(_b + "be")
