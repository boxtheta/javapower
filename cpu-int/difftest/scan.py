#!/usr/bin/env python3
"""Instruction coverage scan of real guest binaries (needs the guest images).

  python3 scan.py <file-or-dir>...

Disassembles every ELF (and raw big-endian blobs named *.bin) with llvm-objdump, collects the
distinct instruction words with their mnemonics and execution-independent counts, and asks
org.boxtheta.javapower.cpu.DecodeScan which ones the integer decode table does not handle. Floating-point
and vector instructions (the cpu-fpvec module) are listed separately.
"""
import collections
import os
import re
import subprocess
import sys

FP_VEC = re.compile(r"^(f|v|x[svx]|mtfpr|mffpr|lf|stf|lv|stv|lxv|stxv|lxs|stxs|lx|stx|mtfs|mffs|mcrfs|mtvsr|mfvsr|"
                    r"mtvscr|mfvscr|dss|dst|lvsl|lvsr|dadd|dsub|dmul|ddiv|dcmp|dquai|dqua|drrnd|drint|dctdp|"
                    r"dctqpq|drsp|drdpq|dcffix|dctfix|ddedpd|denbcd|dxex|diex|dscli|dscri|dtstdc|dtstdg|"
                    r"dtstex|dtstsf|bcd|lxsi|stxsi|darn$)")


def words_of(path):
    out = subprocess.run(["llvm-objdump", "-d", "--no-print-imm-hex", path], stdout=subprocess.PIPE,
                         stderr=subprocess.DEVNULL, text=True).stdout
    be = None
    for line in out.splitlines():
        if "file format" in line:
            be = "powerpc" in line and "le" not in line.split("file format")[1].replace("elf64-powerpc", "").replace(
                "elf32-powerpc", "") and "powerpcle" not in line
            be = "elf64-powerpcle" not in line and "elf32-powerpcle" not in line
        m = re.match(r"\s*[0-9a-f]+:\s+((?:[0-9a-f]{2} ){4})\s*(\S+)", line)
        if not m:
            continue
        b = bytes.fromhex(m.group(1).replace(" ", ""))
        w = int.from_bytes(b, "big" if be else "little")
        yield w, m.group(2)


def main():
    files = []
    for a in sys.argv[1:]:
        if os.path.isdir(a):
            for root, _, names in os.walk(a):
                for n in names:
                    p = os.path.join(root, n)
                    if os.path.islink(p) or not os.path.isfile(p):
                        continue
                    with open(p, "rb") as f:
                        if f.read(4) == b"\x7fELF":
                            files.append(p)
        else:
            files.append(a)
    count = collections.Counter()
    mn = {}
    where = {}
    for p in files:
        for w, m in words_of(p):
            count[w] += 1
            mn[w] = m
            where.setdefault(w, os.path.basename(p))
    tmp = os.path.join(os.environ.get("TMPDIR", "/tmp"), "scan-words.txt")
    with open(tmp, "w") as f:
        for w, c in count.items():
            if re.match(r"^[a-z][a-z0-9_.+-]*$", mn[w]) and not re.match(r"^[0-9a-f]{2}$", mn[w]):
                f.write("%08x %s %d %s\n" % (w, mn[w], c, where[w]))
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    cp = ":".join(os.path.join(root, d) for d in ["core/build/classes/java/main", "cpu-int/build/classes/java/main",
                                                  "cpu-int/build/classes/java/test"])
    res = subprocess.run(["java", "-cp", cp, "org.boxtheta.javapower.cpu.DecodeScan", tmp], stdout=subprocess.PIPE, text=True,
                         check=True).stdout
    by = collections.defaultdict(lambda: [0, 0, set(), ""])
    for line in res.splitlines():
        w, m, c, src, name, key = line.split(None, 5)
        e = by[m]
        e[0] += int(c)
        e[1] += 1
        e[2].add(src)
        e[3] = key + " " + name + " e.g. " + w
    print("%d files, %d distinct words" % (len(files), len(count)))
    for fpv in [False, True]:
        print("\n== %s" % ("floating-point / vector (cpu-fpvec)" if fpv else "NOT DECODED (integer/system)"))
        for m, (c, d, srcs, ex) in sorted(by.items(), key=lambda kv: -kv[1][0]):
            if bool(FP_VEC.match(m)) == fpv:
                print("%-12s %7d uses %5d words  %-28s %s" % (m, c, d, ex, ",".join(sorted(srcs))[:80]))


if __name__ == "__main__":
    main()
