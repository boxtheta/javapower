#!/usr/bin/env python3
"""Differential test vector generator for cpu-fpvec.

Pipeline (run on the dev server, see run.sh):
  1. A group module (groups_*.py) yields test cases: assembler text for ONE instruction plus
     overrides of the default register/memory state.
  2. All instructions of a group are assembled with llvm-mc (independent of our decoder).
  3. Each case becomes a fixed 1856-byte record; runner (runner.c + harness.S) executes it under
     qemu-ppc64le -cpu power9 (groups named *_be: a big-endian ELFv2 runner under qemu-ppc64),
     which clears QEMU's softfloat flags, loads the full state, executes the word and dumps the
     full state. A signal (enabled FP exception, illegal instruction) is caught and reported.
  4. The output is diffed against the input and written as one golden text line per case:
        insn|asm|IN fields|OUT fields
     IN fields:  f=<fpscr as actually loaded> c=<cr> s=<vscr> r<n>=<gpr> v<n>=<hi><lo> m<off>=<bytes>
     OUT fields: f= c= s= always, then every changed r/v/m, and sig=<n> if a signal was raised.
     Registers/memory not listed hold the defaults below (mirrored in Golden.java).
     On a signal, Linux/QEMU's signal frame keeps only FPSCR[32:63], so DRN is not compared.

Groups live in groups_fp.py, groups_vmx.py, groups_vsx.py and groups_qp.py; intwords.py emits the
integer encodings DecodeTest checks are not shadowed.
"""
import os
import random
import struct
import subprocess
import sys
import tempfile

MEMBASE = 0x30000000
MEMSIZE = 512
CASE_SIZE = 1856
HERE = os.path.dirname(os.path.abspath(__file__))


def default_gpr(n):
    return (0x0101010101010101 * (n + 1)) ^ 0x8000000000000000


def default_vsr(n):
    return (0x5A5A000000001234 | (n << 32), 0xA5A5000000005678 | (n << 32))


def default_mem():
    return bytes(((i * 37 + 11) & 0xFF) for i in range(MEMSIZE))


class Case:
    __slots__ = ("asm", "gpr", "vsr", "fpscr", "cr", "vscr", "mem")

    def __init__(self, asm):
        self.asm = asm
        self.gpr = {}
        self.vsr = {}
        self.fpscr = 0
        self.cr = 0
        self.vscr = 0x10000
        self.mem = {}


def pack(case, word, be=False):
    gpr = [default_gpr(i) for i in range(32)]
    for k, v in case.gpr.items():
        gpr[k] = v & 0xFFFFFFFFFFFFFFFF
    vsr = [default_vsr(i) for i in range(64)]
    for k, v in case.vsr.items():
        vsr[k] = (v[0] & 0xFFFFFFFFFFFFFFFF, v[1] & 0xFFFFFFFFFFFFFFFF)
    mem = bytearray(default_mem())
    for off, b in case.mem.items():
        mem[off:off + len(b)] = b
    e = ">" if be else "<"
    out = struct.pack(e + "II", word, 0)
    out += struct.pack(e + "32Q", *gpr)
    out += struct.pack(e + "QII", case.fpscr & 0xFFFFFFFFFFFFFFFF, case.cr & 0xFFFFFFFF, 0)
    out += struct.pack(e + "QQ", 0, case.vscr & 0xFFFFFFFF)
    for hi, lo in vsr:
        out += struct.pack(e + "QQ", hi, lo)
    out += bytes(mem)
    out += b"\0" * 24
    assert len(out) == CASE_SIZE
    return out


def unpack(buf, be=False):
    e = ">" if be else "<"
    insn, _flags = struct.unpack_from(e + "II", buf, 0)
    gpr = list(struct.unpack_from(e + "32Q", buf, 8))
    fpscr, cr, sig = struct.unpack_from(e + "QII", buf, 264)
    _, vscr = struct.unpack_from(e + "QQ", buf, 280)
    vsr = [struct.unpack_from(e + "QQ", buf, 296 + 16 * i) for i in range(64)]
    mem = buf[1320:1320 + MEMSIZE]
    fpscr_in, signip = struct.unpack_from(e + "QQ", buf, 1832)
    return dict(insn=insn, gpr=gpr, fpscr=fpscr, cr=cr, sig=sig, vscr=vscr & 0xFFFFFFFF, vsr=vsr,
                mem=mem, fpscr_in=fpscr_in, signip=signip)


def assemble(lines, work, be=False):
    src = os.path.join(work, "a.s")
    obj = os.path.join(work, "a.o")
    binf = os.path.join(work, "a.bin")
    with open(src, "w") as f:
        f.write(".text\n")
        for l in lines:
            f.write(l + "\n")
    triple = "powerpc64-unknown-linux-gnu" if be else "powerpc64le-unknown-linux-gnu"
    subprocess.run(["llvm-mc", "-triple=" + triple, "-mcpu=pwr9",
                    "-filetype=obj", "-o", obj, src], check=True)
    subprocess.run(["llvm-objcopy", "-O", "binary", "--only-section=.text", obj, binf], check=True)
    data = open(binf, "rb").read()
    if len(data) != 4 * len(lines):
        raise SystemExit("assembler produced %d bytes for %d lines" % (len(data), len(lines)))
    return list(struct.unpack((">" if be else "<") + "%dI" % len(lines), data))


def build_runner(work, be=False):
    exe = os.path.join(work, "runner")
    target = ["--target=powerpc64-linux-gnu", "-mabi=elfv2"] if be else ["--target=powerpc64le-linux-gnu"]
    subprocess.run(["clang"] + target + ["-mcpu=power9", "-O2", "-nostdlib",
                    "-static", "-fuse-ld=lld", "-ffreestanding", "-fno-builtin", "-fno-stack-protector", "-o", exe,
                    os.path.join(HERE, "runner.c"), os.path.join(HERE, "harness.S")], check=True)
    return exe


def fmt_mem_diff(a, b):
    out = []
    i = 0
    while i < len(a):
        if a[i] != b[i]:
            j = i
            while j < len(a) and a[j] != b[j]:
                j += 1
            out.append("m%d=%s" % (i, bytes(b[i:j]).hex()))
            i = j
        else:
            i += 1
    return out


def run_cases(cases, outpath, be=False):
    with tempfile.TemporaryDirectory() as work:
        words = assemble([c.asm for c in cases], work, be)
        exe = build_runner(work, be)
        inp = b"".join(pack(c, w, be) for c, w in zip(cases, words))
        qemu = "qemu-ppc64" if be else "qemu-ppc64le"
        res = subprocess.run([qemu, "-cpu", "power9", exe], input=inp,
                             stdout=subprocess.PIPE, check=True).stdout
    if len(res) != len(inp):
        raise SystemExit("runner produced %d bytes, expected %d" % (len(res), len(inp)))
    dmem = default_mem()
    lines = []
    for i, (c, w) in enumerate(zip(cases, words)):
        o = unpack(res[i * CASE_SIZE:(i + 1) * CASE_SIZE], be)
        ins = ["f=%x" % o["fpscr_in"], "c=%x" % (c.cr & 0xFFFFFFFF), "s=%x" % (c.vscr & 0xFFFFFFFF)]
        gin = [default_gpr(k) for k in range(32)]
        for k, v in sorted(c.gpr.items()):
            gin[k] = v & 0xFFFFFFFFFFFFFFFF
            ins.append("r%d=%x" % (k, gin[k]))
        vin = [default_vsr(k) for k in range(64)]
        for k, v in sorted(c.vsr.items()):
            vin[k] = (v[0] & 0xFFFFFFFFFFFFFFFF, v[1] & 0xFFFFFFFFFFFFFFFF)
            ins.append("v%d=%016x%016x" % (k, vin[k][0], vin[k][1]))
        min_ = bytearray(dmem)
        for off, b in sorted(c.mem.items()):
            min_[off:off + len(b)] = b
            ins.append("m%d=%s" % (off, bytes(b).hex()))
        outs = ["f=%x" % o["fpscr"], "c=%x" % o["cr"], "s=%x" % o["vscr"]]
        for k in [0] + list(range(3, 13)):
            if o["gpr"][k] != gin[k]:
                outs.append("r%d=%x" % (k, o["gpr"][k]))
        for k in range(64):
            if tuple(o["vsr"][k]) != vin[k]:
                outs.append("v%d=%016x%016x" % (k, o["vsr"][k][0], o["vsr"][k][1]))
        outs += fmt_mem_diff(min_, o["mem"])
        if o["sig"]:
            outs.append("sig=%d" % o["sig"])
        lines.append("%08x|%s|%s|%s" % (w, c.asm, " ".join(ins), " ".join(outs)))
    with open(outpath, "w") as f:
        f.write("\n".join(lines) + "\n")
    print("%s: %d cases" % (outpath, len(lines)))


def main():
    sys.path.insert(0, HERE)
    import groups
    names = sys.argv[1:-1]
    outdir = sys.argv[-1]
    for name in names:
        rng = random.Random(hash(name) & 0xFFFFFFFF if False else sum(map(ord, name)) * 7919)
        cases = list(groups.GROUPS[name](rng))
        run_cases(cases, os.path.join(outdir, name + ".txt"), be=name.endswith("_be"))


if __name__ == "__main__":
    main()
