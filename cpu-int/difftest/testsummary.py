#!/usr/bin/env python3
"""Print the JUnit results of the last :cpu-int:test run (failures with their messages, totals)."""
import glob
import html
import re
import sys

tot = [0, 0, 0]
limit = int(sys.argv[1]) if len(sys.argv) > 1 else 4000
for f in sorted(glob.glob("cpu-int/build/test-results/test/*.xml")):
    s = open(f).read()
    for m in re.finditer(r'<testcase name="([^"]*)" classname="([^"]*)"[^>]*>\s*<failure message="([^"]*)"', s):
        print("FAIL %s %s: %s" % (m.group(2).split(".")[-1], html.unescape(m.group(1)), html.unescape(m.group(3))[:limit]))
    t = re.search(r'tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', s)
    if t:
        tot[0] += int(t.group(1))
        tot[1] += int(t.group(3))
        tot[2] += int(t.group(4))
print("tests=%d failures=%d errors=%d" % tuple(tot))
