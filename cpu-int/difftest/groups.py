"""Registry of test-case groups: name -> generator of gen.Case."""
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
