#!/usr/bin/env python3
"""Differential test vector generator for cpu-int (needs QEMU and clang; see README.md).

  python3 gen.py <group> <outdir>

A group module (groups_*.py, registered in groups.py) yields Case objects: a few instructions
of assembler plus the machine state to run them in. All cases of a group are assembled with
llvm-mc (independently of our decoder), packed into fixed 2304-byte records and executed by a
bare-metal firmware (boot.S + harness.c + tramp.S) under

  qemu-system-ppc64 -M pseries -cpu power9 -bios fw.bin

which, per case, loads the registers/SPRs/memory, rfids into the code with the requested MSR and
records the state at the first interrupt (every case ends in `sc`, so normal completion is a
system call interrupt with SRR0 just past the code). Results are written as one golden text
line per case:

  name|IN tokens|OUT tokens

IN:  msr= pc= code=w,w,..  [ile=0] then non-default r<n>= cr= xer= lr= ctr= s<i>= p<addr>= m<off>=
OUT: vec= srr0= srr1= dar= dsisr= hmsr= cr= xer= then changed r<n>= lr= ctr= s<i>= q<addr>=
     m<off>=, and x=<names> for outputs that must not be compared (time, random numbers).
Defaults are mirrored in Golden.java.
"""
import importlib
import os
import struct
import subprocess
import sys
import tempfile
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

CASES = 0x01000000
SLOT = 0x00200000
WIN = 0x002FFF00
WINSIZE = 512
REC = 2560
NSPR = 16
MAXSLOT = 32
MAXPATCH = 12
DAR_IN = 0x0000DA00DA00DA00
M64 = 0xFFFFFFFFFFFFFFFF
CASE_RFID = 0  # address of the harness's rfid (CFAR at case entry), set by build_fw
# Firmware byte order: False = little-endian harness with LPCR[ILE]=1 (H_SET_MODE LE), True =
# big-endian harness, interrupts delivered with LPCR[ILE]=0. Set per group (groups.BE_FIRMWARE).
FW_BE = False

# Case.spr[] order (must match harness.c and Golden.java).
SPR_NAMES = ["AMR", "IAMR", "UAMOR", "PIDR", "FSCR", "TAR", "DSCR", "SPRG0", "SPRG3", "EBBRR",
             "BESCR", "PPR", "VRSAVE", "CTRL", "SPRG2", "EBBHR"]
SPR_IDX = {n: i for i, n in enumerate(SPR_NAMES)}

MSR_SF = 1 << 63
MSR_EE = 1 << 15
MSR_PR = 1 << 14
MSR_FP = 1 << 13
MSR_ME = 1 << 12
MSR_IR = 1 << 5
MSR_DR = 1 << 4
MSR_RI = 1 << 1
MSR_LE = 1
MSR64LE = MSR_SF | MSR_ME | MSR_LE
MSR64BE = MSR_SF | MSR_ME
MSR32LE = MSR_ME | MSR_LE
MSR32BE = MSR_ME


def default_gpr(n):
    return (0x0101010101010101 * (n + 1)) ^ 0x8000000000000000


DEFAULT_CR = 0
DEFAULT_XER = 0
DEFAULT_LR = 0x1111111111111110
DEFAULT_CTR = 0x2222222222222222


def default_spr(i):
    return 0


def default_mem():
    return bytes(((i * 37 + 11) & 0xFF) for i in range(WINSIZE))


class Case:
    def __init__(self, name, code, msr=MSR64LE, pc=SLOT, sc=True):
        self.name = name
        self.ail = 0
        self.code = list(code) if not isinstance(code, str) else [code]
        if sc:
            self.code += ["sc"] * 4
        assert len(self.code) <= MAXSLOT, name
        self.msr = msr
        self.pc = pc
        self.gpr = {}
        self.cr = DEFAULT_CR
        self.xer = DEFAULT_XER
        self.lr = DEFAULT_LR
        self.ctr = DEFAULT_CTR
        self.spr = {}
        self.patch = []  # (real address, 64-bit value in big-endian memory order)
        self.mem = {}
        self.ignore = []

    def r(self, n, v):
        self.gpr[n] = v & M64
        return self

    def s(self, name, v):
        self.spr[SPR_IDX[name]] = v & M64
        return self


# Radix page tables (real addresses; entries are big-endian doublewords, see Case.patch).
PROC_TABLE = 0x400000
PT_ROOT = 0x410000   # 2^13 entries (RTS 52, root NLS 13)
PT_PUD = 0x420000
PT_PMD = 0x421000
PT_PTE = 0x422000
PTE_V = 1 << 63
PTE_L = 1 << 62
PTE_R = 0x100
PTE_C = 0x80
PTE_PRIV = 0x8
EAA_R, EAA_RW, EAA_X = 4, 2, 1


def pde(base, nls):
    return PTE_V | base | nls


def leaf(ra, eaa=7, priv=False, r=True, c=True, att=0):
    return (PTE_V | PTE_L | ra | (PTE_R if r else 0) | (PTE_C if c else 0) | (att << 4)
            | (PTE_PRIV if priv else 0) | eaa)


def prtb0(root, nls=13, rts=52):
    v = rts - 31
    return ((v >> 3) << 61) | ((v & 7) << 5) | root | nls


def map_std(c, pid=1, slot_leaf=None):
    """Identity-map 0..4 MiB with 2 MiB pages in quadrant 0 (PID pid) and quadrant 3 (PID 0):
    0..2 MiB privileged (firmware), 2..4 MiB (code slot, data window) user RWX unless given."""
    c.patch += [(PROC_TABLE, prtb0(PT_ROOT)), (PROC_TABLE + 16 * pid, prtb0(PT_ROOT)),
                (PT_ROOT, pde(PT_PUD, 9)), (PT_PUD, pde(PT_PMD, 9)),
                (PT_PMD, leaf(0, priv=True)),
                (PT_PMD + 8, leaf(0x200000) if slot_leaf is None else slot_leaf)]
    c.s("PIDR", pid)
    return c


def fw_order():
    return ">" if FW_BE else "<"


def pack(c, words):
    """One case record in the firmware's byte order. Code words are stored so that they read
    back in the case's own byte order; page-table patches are big-endian in memory."""
    e = fw_order()
    le = (c.msr & MSR_LE) != 0
    swap = le == FW_BE
    w = [struct.unpack("<I", struct.pack(">I", x))[0] if swap else x for x in words]
    w += [0] * (MAXSLOT - len(w))
    gpr = [default_gpr(i) for i in range(32)]
    for k, v in c.gpr.items():
        gpr[k] = v
    spr = [default_spr(i) for i in range(NSPR)]
    for k, v in c.spr.items():
        spr[k] = v
    patches = list(c.patch) + [(0, 0)] * (MAXPATCH - len(c.patch))
    mem = bytearray(default_mem())
    for off, b in c.mem.items():
        mem[off:off + len(b)] = b
    out = struct.pack(e + "4Q", 1 | (c.ail << 8), c.msr & M64, c.pc & M64, len(words))
    out += struct.pack(e + "32I", *w)
    out += struct.pack(e + "32Q", *gpr)
    out += struct.pack(e + "4Q", c.cr, c.xer, c.lr, c.ctr)
    out += struct.pack(e + "16Q", *spr)
    out += struct.pack(e + "Q", len(c.patch))
    for a, v in patches:
        out += struct.pack(e + "Q", a) + struct.pack(">Q", v)
    out += bytes(mem)
    out += b"\0" * (REC - len(out))
    assert len(out) == REC
    return out


def unpack_out(buf):
    o = {}
    e = fw_order()
    base = 776 + WINSIZE  # outputs follow the inputs (see struct Case in harness.c)
    vals = struct.unpack_from(e + "6Q", buf, base)
    for k, v in zip(["vec", "srr0", "srr1", "dar", "dsisr", "hmsr"], vals):
        o[k] = v
    o["gpr"] = list(struct.unpack_from(e + "32Q", buf, base + 48))
    o["cr"], o["xer"], o["lr"], o["ctr"] = struct.unpack_from(e + "4Q", buf, base + 304)
    o["spr"] = list(struct.unpack_from(e + "16Q", buf, base + 336))
    o["patch"] = list(struct.unpack_from(">%dQ" % MAXPATCH, buf, base + 464))
    o["mem"] = buf[base + 464 + 8 * MAXPATCH:base + 464 + 8 * MAXPATCH + WINSIZE]
    return o


def run(cmd, **kw):
    return subprocess.run(cmd, check=True, **kw)


def build_fw(work):
    with open(os.path.join(work, "vectors.inc"), "w") as f:
        for a in (list(range(0x200, 0x1000, 0x20)) + list(range(0x3000, 0x4000, 0x20))
                  + list(range(0x4200, 0x5000, 0x20)) + list(range(0x17000, 0x18000, 0x20))
                  + list(range(0x18200, 0x19000, 0x20))):
            f.write(".org 0x%x\n mtspr 273, 3\n lis 3, 0x%x\n ori 3, 3, 0x%x\n b common\n"
                    % (a, a >> 16, a & 0xFFFF))
    elf = os.path.join(work, "fw.elf")
    target = ["--target=powerpc64-linux-gnu", "-mabi=elfv2"] if FW_BE else ["--target=powerpc64le-linux-gnu"]
    run(["clang"] + target + ["-mcpu=power9", "-O2", "-nostdlib", "-static",
         "-fuse-ld=lld", "-ffreestanding", "-fno-builtin", "-fno-stack-protector", "-fno-pic",
         "-msoft-float", "-mno-altivec", "-mno-vsx", "-I", work, "-T", os.path.join(HERE, "link.ld"),
         "-Wl,--no-relax", "-o", elf, os.path.join(HERE, "boot.S"), os.path.join(HERE, "harness.c")])
    fw = os.path.join(work, "fw.bin")
    run(["llvm-objcopy", "-O", "binary", elf, fw])
    tobj = os.path.join(work, "tramp.o")
    tbin = os.path.join(work, "tramp.bin")
    run(["llvm-mc", "-triple=powerpc64-unknown-linux-gnu", "-mcpu=pwr9", "-filetype=obj",
         "-defsym=FW_BE=%d" % FW_BE, "-o", tobj, os.path.join(HERE, "tramp.S")])
    run(["llvm-objcopy", "-O", "binary", "--only-section=.text", tobj, tbin])
    data = bytearray(open(fw, "rb").read())
    t = open(tbin, "rb").read()
    assert len(t) <= 0x100 and data[0x100:0x100 + len(t)] == bytes(len(t))
    data[0x100:0x100 + len(t)] = t
    open(fw, "wb").write(data)
    global CASE_RFID
    syms = subprocess.run(["llvm-nm", elf], check=True, stdout=subprocess.PIPE, text=True).stdout
    CASE_RFID = int([l.split()[0] for l in syms.splitlines() if l.endswith(" case_rfid")][0], 16)
    return fw


def assemble(lines, work):
    src = os.path.join(work, "a.s")
    obj = os.path.join(work, "a.o")
    binf = os.path.join(work, "a.bin")
    with open(src, "w") as f:
        f.write(".text\n")
        for l in lines:
            f.write(l + "\n")
    run(["llvm-mc", "-triple=powerpc64le-unknown-linux-gnu", "-mcpu=pwr9", "-filetype=obj", "-o", obj, src])
    run(["llvm-objcopy", "-O", "binary", "--only-section=.text", obj, binf])
    data = open(binf, "rb").read()
    if len(data) != 4 * len(lines):
        raise SystemExit("assembler produced %d bytes for %d lines" % (len(data), len(lines)))
    return list(struct.unpack("<%dI" % len(lines), data))


def xform(po, xo, rt=0, ra=0, rb=0, rc=0):
    """Raw X-form word, for instructions llvm-mc does not know. Fields in ISA order."""
    return (po << 26) | (rt << 21) | (ra << 16) | (rb << 11) | (xo << 1) | rc


def assemble_cases(cases, work):
    lines = []
    for c in cases:
        for l in c.code:
            # Each case's code is assembled as if at SLOT: relative branches use explicit
            # offsets (.+n) so position does not matter; raw words are passed as .long.
            lines.append(".long 0x%08x" % l if isinstance(l, int) else l)
    words = assemble(lines, work)
    out = []
    i = 0
    for c in cases:
        out.append(words[i:i + len(c.code)])
        i += len(c.code)
    return out


def run_qemu(fw, inp, work, timeout):
    cin = os.path.join(work, "cases.bin")
    cout = os.path.join(work, "out.bin")
    con = os.path.join(work, "console.log")
    open(cin, "wb").write(inp)
    open(con, "w").close()
    p = subprocess.Popen(["qemu-system-ppc64", "-M", "pseries,ic-mode=xics,usb=off", "-cpu", "power9",
                          "-smp", "1", "-m", "1G", "-nodefaults", "-display", "none", "-bios", fw,
                          "-chardev", "file,id=c0,path=" + con, "-device", "spapr-vty,chardev=c0",
                          "-device", "loader,file=%s,addr=0x%x,force-raw=on" % (cin, CASES),
                          "-monitor", "stdio"],
                         stdin=subprocess.PIPE, stdout=open(os.path.join(work, "monitor.log"), "wb"),
                         stderr=subprocess.PIPE)
    t0 = time.time()
    try:
        while True:
            if "DIFFTEST DONE" in open(con, errors="replace").read():
                break
            if p.poll() is not None:
                raise SystemExit("qemu exited: " + p.stderr.read().decode(errors="replace"))
            if time.time() - t0 > timeout:
                raise SystemExit("timeout; console: " + open(con, errors="replace").read()[-2000:])
            time.sleep(0.05)
        p.stdin.write(("pmemsave 0x%x %d \"%s\"\n" % (CASES, len(inp), cout)).encode())
        p.stdin.write(b"quit\n")
        p.stdin.flush()
        p.wait(timeout=60)
    finally:
        if p.poll() is None:
            p.kill()
    if not os.path.exists(cout):
        raise SystemExit("no output; monitor: " + open(os.path.join(work, "monitor.log"), errors="replace").read()
                         + "\nconsole: " + open(con, errors="replace").read()[-2000:])
    return open(cout, "rb").read()


def fmt_mem_diff(prefix, a, b):
    out = []
    i = 0
    while i < len(a):
        if a[i] != b[i]:
            j = i
            while j < len(a) and a[j] != b[j]:
                j += 1
            out.append("%s%d=%s" % (prefix, i, bytes(b[i:j]).hex()))
            i = j
        else:
            i += 1
    return out


def golden_line(c, words, o):
    ins = ["msr=%x" % c.msr, "pc=%x" % c.pc, "code=" + ",".join("%08x" % w for w in words)]
    if c.ail:
        ins.append("ail=%d" % c.ail)
    if FW_BE:
        ins.append("ile=0")
    ins.append("cfar=%x" % CASE_RFID)
    gin = [default_gpr(k) for k in range(32)]
    for k in sorted(c.gpr):
        gin[k] = c.gpr[k]
        if c.gpr[k] != default_gpr(k):
            ins.append("r%d=%x" % (k, c.gpr[k]))
    for name, v, d in (("cr", c.cr, DEFAULT_CR), ("xer", c.xer, DEFAULT_XER), ("lr", c.lr, DEFAULT_LR),
                       ("ctr", c.ctr, DEFAULT_CTR)):
        if v != d:
            ins.append("%s=%x" % (name, v))
    sin = [default_spr(i) for i in range(NSPR)]
    for k in sorted(c.spr):
        sin[k] = c.spr[k]
        if c.spr[k] != default_spr(k):
            ins.append("s%d=%x" % (k, c.spr[k]))
    for a, v in c.patch:
        ins.append("p%x=%x" % (a, v))
    dmem = default_mem()
    min_ = bytearray(dmem)
    for off, b in c.mem.items():
        min_[off:off + len(b)] = b
    ins += fmt_mem_diff("m", dmem, min_)

    outs = ["vec=%x" % o["vec"], "srr0=%x" % o["srr0"], "srr1=%x" % o["srr1"], "dar=%x" % o["dar"],
            "dsisr=%x" % o["dsisr"], "hmsr=%x" % o["hmsr"], "cr=%x" % o["cr"], "xer=%x" % o["xer"]]
    for k in range(32):
        if o["gpr"][k] != gin[k]:
            outs.append("r%d=%x" % (k, o["gpr"][k]))
    if o["lr"] != c.lr:
        outs.append("lr=%x" % o["lr"])
    if o["ctr"] != c.ctr:
        outs.append("ctr=%x" % o["ctr"])
    for k in range(NSPR):
        if o["spr"][k] != sin[k]:
            outs.append("s%d=%x" % (k, o["spr"][k]))
    for (a, _), v in zip(c.patch, o["patch"]):
        outs.append("q%x=%x" % (a, v))
    outs += fmt_mem_diff("m", min_, o["mem"])
    if c.ignore:
        outs.append("x=" + ",".join(c.ignore))
    return "%s|%s|%s" % (c.name, " ".join(ins), " ".join(outs))


def main():
    group, outdir = sys.argv[1], sys.argv[2]
    import groups
    global FW_BE
    FW_BE = group in groups.BE_FIRMWARE
    cases = list(groups.GROUPS[group]())
    names = set()
    for c in cases:
        if c.name in names:
            raise SystemExit("duplicate case name " + c.name)
        names.add(c.name)
    with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as work:
        fw = build_fw(work)
        words = assemble_cases(cases, work)
        hdr = struct.pack(fw_order() + "Q", len(cases)) + b"\0" * 4088
        inp = hdr + b"".join(pack(c, w) for c, w in zip(cases, words))
        res = run_qemu(fw, inp, work, timeout=600)
    if len(res) != len(inp):
        raise SystemExit("got %d bytes, expected %d" % (len(res), len(inp)))
    lines = []
    for i, (c, w) in enumerate(zip(cases, words)):
        rec = res[4096 + i * REC:4096 + (i + 1) * REC]
        lines.append(golden_line(c, w, unpack_out(rec)))
    os.makedirs(outdir, exist_ok=True)
    with open(os.path.join(outdir, group + ".txt"), "w") as f:
        f.write("\n".join(lines) + "\n")
    print("%s: %d cases" % (group, len(cases)))


if __name__ == "__main__":
    main()
