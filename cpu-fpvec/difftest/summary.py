#!/usr/bin/env python3
"""Summarize JUnit XML results: python3 summary.py [maxlines-per-failure] [filter]"""
import glob, sys, html, xml.etree.ElementTree as ET
n = int(sys.argv[1]) if len(sys.argv) > 1 else 6
flt = sys.argv[2] if len(sys.argv) > 2 else ""
tot = bad = 0
for f in glob.glob("cpu-fpvec/build/test-results/test/*.xml"):
    for tc in ET.parse(f).getroot().iter("testcase"):
        tot += 1
        fl = tc.find("failure")
        if fl is None:
            fl = tc.find("error")
        if fl is not None:
            bad += 1
            if flt in tc.get("name"):
                msg = (fl.get("message") or fl.text or "").splitlines()
                print("FAIL", tc.get("name"))
                for l in msg[:n]:
                    print("   ", l[:400])
print("total", tot, "failed", bad)
