"""Registry of all test groups."""
from vals import Case, rand_fpscr
import groups_fp
import groups_vmx
import groups_vsx
import groups_qp

GROUPS = {}
GROUPS.update(groups_fp.GROUPS)
GROUPS.update(groups_vmx.GROUPS)
GROUPS.update(groups_vsx.GROUPS)
GROUPS.update(groups_qp.GROUPS)


def g_selftest(rng):
    cs = []
    c = Case(".long 0")  # illegal: exercises the signal path
    cs.append(c)
    c = Case("fadd 1, 2, 3")
    c.vsr[2] = (0x3FF0000000000000, 0)
    c.vsr[3] = (0x4000000000000000, 0)
    cs.append(c)
    c = Case("fdiv 1, 2, 3")  # enabled zero-divide: SIGFPE
    c.fpscr = 1 << 4
    c.vsr[2] = (0x3FF0000000000000, 0)
    c.vsr[3] = (0, 0)
    cs.append(c)
    return cs


GROUPS["selftest"] = g_selftest
GROUPS["selftest_be"] = g_selftest
