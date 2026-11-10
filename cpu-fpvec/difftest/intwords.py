#!/usr/bin/env python3
"""Emit 'word|asm' lines for integer/system instructions that share primary opcodes with the
FP/vector instructions. cpu-fpvec must not decode any of them (DecodeTest)."""
import os, struct, subprocess, sys, tempfile

INSNS = """
maddhd 3, 4, 5, 6
maddhdu 3, 4, 5, 6
maddld 3, 4, 5, 6
add 3, 4, 5
add. 3, 4, 5
addo 3, 4, 5
addc 3, 4, 5
adde 3, 4, 5
addme 3, 4
addze 3, 4
subf 3, 4, 5
subfc 3, 4, 5
subfe 3, 4, 5
subfme 3, 4
subfze 3, 4
neg 3, 4
mullw 3, 4, 5
mulhw 3, 4, 5
mulhwu 3, 4, 5
mulld 3, 4, 5
mulhd 3, 4, 5
mulhdu 3, 4, 5
divw 3, 4, 5
divwu 3, 4, 5
divd 3, 4, 5
divdu 3, 4, 5
divwe 3, 4, 5
divweu 3, 4, 5
divde 3, 4, 5
divdeu 3, 4, 5
modsw 3, 4, 5
moduw 3, 4, 5
modsd 3, 4, 5
modud 3, 4, 5
and 3, 4, 5
andc 3, 4, 5
or 3, 4, 5
orc 3, 4, 5
nor 3, 4, 5
nand 3, 4, 5
xor 3, 4, 5
eqv 3, 4, 5
slw 3, 4, 5
srw 3, 4, 5
sraw 3, 4, 5
srawi 3, 4, 5
sld 3, 4, 5
srd 3, 4, 5
srad 3, 4, 5
sradi 3, 4, 33
cntlzw 3, 4
cntlzd 3, 4
cnttzw 3, 4
cnttzd 3, 4
popcntb 3, 4
popcntw 3, 4
popcntd 3, 4
.long 0x7c830134 # prtyw 3, 4
.long 0x7c830174 # prtyd 3, 4
cmpb 3, 4, 5
bpermd 3, 4, 5
extsb 3, 4
extsh 3, 4
extsw 3, 4
extswsli 3, 4, 5
cmpw 3, 4, 5
cmpd 3, 4, 5
cmplw 3, 4, 5
cmpld 3, 4, 5
cmprb 3, 1, 4, 5
cmpeqb 3, 4, 5
isel 3, 4, 5, 6
mfcr 3
mfocrf 3, 16
mtcrf 255, 3
mtocrf 16, 3
mfspr 3, 8
mtspr 8, 3
mftb 3
mfmsr 3
mtmsr 3
mtmsrd 3
lbzx 3, 4, 5
lbzux 3, 4, 5
lhzx 3, 4, 5
lhzux 3, 4, 5
lhax 3, 4, 5
lhaux 3, 4, 5
lwzx 3, 4, 5
lwzux 3, 4, 5
lwax 3, 4, 5
lwaux 3, 4, 5
ldx 3, 4, 5
ldux 3, 4, 5
stbx 3, 4, 5
stbux 3, 4, 5
sthx 3, 4, 5
sthux 3, 4, 5
stwx 3, 4, 5
stwux 3, 4, 5
stdx 3, 4, 5
stdux 3, 4, 5
lhbrx 3, 4, 5
lwbrx 3, 4, 5
ldbrx 3, 4, 5
sthbrx 3, 4, 5
stwbrx 3, 4, 5
stdbrx 3, 4, 5
lwarx 3, 4, 5
ldarx 3, 4, 5
lbarx 3, 4, 5
lharx 3, 4, 5
lqarx 6, 4, 5
stwcx. 3, 4, 5
stdcx. 3, 4, 5
stbcx. 3, 4, 5
sthcx. 3, 4, 5
stqcx. 6, 4, 5
dcbz 4, 5
dcbf 4, 5
dcbst 4, 5
dcbt 4, 5
dcbtst 4, 5
dcbi 4, 5
icbi 4, 5
icbt 0, 4, 5
sync
lwsync
ptesync
eieio
tw 4, 3, 5
td 4, 3, 5
lswi 3, 4, 8
.long 0x7c642c2a # lswx 3, 4, 5
stswi 3, 4, 8
.long 0x7c642d2a # stswx 3, 4, 5
darn 3, 1
addex 3, 4, 5, 0
setb 3, 2
mcrxrx 2
copy 4, 5
paste. 4, 5
cpabort
msgsndp 5
.long 0x7c00295c # msgclrp 5
tlbie 5, 6
tlbiel 5
tlbsync
slbia
slbie 5
slbmte 3, 5
slbmfee 3, 5
slbmfev 3, 5
wait
.long 0x7c64048c # lwat 3, 4, 0
.long 0x7c6404cc # ldat 3, 4, 0
.long 0x7c64058c # stwat 3, 4, 0
.long 0x7c6405cc # stdat 3, 4, 0
mcrf 1, 2
isync
rfid
hrfid
sc
nop
tbegin. 0
tend. 0
lq 6, 16(4)
stq 6, 16(4)
lhz 3, 8(4)
lwz 3, 8(4)
ld 3, 8(4)
std 3, 8(4)
lwa 3, 8(4)
ldu 3, 8(4)
stdu 3, 8(4)
""".strip().splitlines()


def main():
    be = len(sys.argv) > 1 and sys.argv[1] == "be"
    with tempfile.TemporaryDirectory() as w:
        src, obj, binf = (os.path.join(w, n) for n in ("a.s", "a.o", "a.bin"))
        open(src, "w").write(".text\n" + "\n".join(INSNS) + "\n")
        subprocess.run(["llvm-mc", "-triple=powerpc64le-unknown-linux-gnu", "-mcpu=pwr9", "-filetype=obj",
                        "-o", obj, src], check=True)
        subprocess.run(["llvm-objcopy", "-O", "binary", "--only-section=.text", obj, binf], check=True)
        data = open(binf, "rb").read()
    words = struct.unpack("<%dI" % (len(data) // 4), data)
    assert len(words) == len(INSNS), (len(words), len(INSNS))
    for w, a in zip(words, INSNS):
        print("%08x|%s" % (w, a))


if __name__ == "__main__":
    main()
