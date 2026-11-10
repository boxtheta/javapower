# Differential-test runner, guest side (ppc64le, ELFv2). See README in gen.py.
#
# run_case(in=r3, out=r4, save=r5, slot=r6):
#   loads FPSCR, VSCR, CR, all 64 VSRs and GPRs r0,r3..r12 from *in, calls the one-instruction
#   slot (insn; blr), then stores the same state to *out. r28..r31 belong to the harness and must
#   not be targets of the instruction under test.

        .set OFF_GPR,    8
        .set OFF_FPSCR,  264
        .set OFF_CR,     272
        .set OFF_SIG,    276
        .set OFF_VSCR,   280
        .set OFF_VSR,    296
        .set OFF_FPSCRIN, 1832
        .set OFF_SIGNIP, 1840
        .set SLOT_RET,   0x31000004

        .abiversion 2
        .text

        .globl _start
        .type _start,@function
_start:
        bl 0f
0:      mflr 12
        addis 2, 12, (.TOC. - 0b)@ha
        addi 2, 2, (.TOC. - 0b)@l
        li 0, 0
        stdu 0, -64(1)
        bl cmain
        nop
        li 0, 234           # exit_group
        sc
        b .

        .globl sys6
        .type sys6,@function
# long sys6(long nr, a1..a6): returns -errno on failure
sys6:
        mr 0, 3
        mr 3, 4
        mr 4, 5
        mr 5, 6
        mr 6, 7
        mr 7, 8
        mr 8, 9
        sc
        bnslr
        neg 3, 3
        blr

        .globl sighandler
        .type sighandler,@function
# r3 = signal, r4 = siginfo, r5 = ucontext. mcontext gregs at uc+232 (verified by selftest).
sighandler:
        ld 6, 232+29*8(5)       # interrupted r29 = out pointer
        stw 3, OFF_SIG(6)
        ld 7, 232+32*8(5)       # NIP
        std 7, OFF_SIGNIP(6)
        lis 7, SLOT_RET@h
        ori 7, 7, SLOT_RET@l
        std 7, 232+32*8(5)
        blr

        .globl run_case
        .type run_case,@function
run_case:
        std 1, 0(5)
        std 2, 8(5)
        mflr 0
        std 0, 16(5)
        .irp r, 13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31
        std \r, 24+8*(\r-13)(5)
        .endr
        li 7, 192
        .irp v, 14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,52,53,54,55,56,57,58,59,60,61,62,63
        stxvd2x \v, 5, 7
        addi 7, 7, 16
        .endr
        mr 28, 5                # save area
        mr 29, 4                # out
        mr 31, 3                # in
        mtctr 6
        # Clear QEMU's accumulated softfloat flags (some VSX helpers do not reset them and would
        # otherwise see stale flags from the previous case): fcmpu resets them and raises none.
        xxlxor 0, 0, 0
        fcmpu 0, 0, 0
        # FPSCR (all 64 bits via L=1), then read back what actually got set.
        lfd 0, OFF_FPSCR(31)
        mtfsf 0xff, 0, 1, 0
        mffs 0
        stfd 0, OFF_FPSCRIN(29)
        # VSCR
        li 30, OFF_VSCR
        lxvd2x 32, 31, 30
        mtvscr 0
        # CR
        lwz 0, OFF_CR(31)
        mtcrf 0xff, 0
        # VSRs
        .irp v, 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63
        li 30, OFF_VSR+16*\v
        lxvd2x \v, 31, 30
        .endr
        # GPRs
        ld 0, OFF_GPR(31)
        .irp r, 3,4,5,6,7,8,9,10,11,12
        ld \r, OFF_GPR+8*\r(31)
        .endr
        bctrl
        # ---- results ----
        std 0, OFF_GPR(29)
        .irp r, 3,4,5,6,7,8,9,10,11,12
        std \r, OFF_GPR+8*\r(29)
        .endr
        mfcr 30
        stw 30, OFF_CR(29)
        .irp v, 0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32,33,34,35,36,37,38,39,40,41,42,43,44,45,46,47,48,49,50,51,52,53,54,55,56,57,58,59,60,61,62,63
        li 30, OFF_VSR+16*\v
        stxvd2x \v, 29, 30
        .endr
        mffs 0
        stfd 0, OFF_FPSCR(29)
        mfvscr 0
        li 30, OFF_VSCR
        stxvd2x 32, 29, 30
        # restore a clean FPSCR (no enables) and VSCR, then callee-saved state
        li 30, 0
        std 30, -8(1)
        lfd 0, -8(1)
        mtfsf 0xff, 0, 1, 0
        vxor 0, 0, 0
        mtvscr 0
        mr 5, 28
        li 7, 192
        .irp v, 14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,52,53,54,55,56,57,58,59,60,61,62,63
        lxvd2x \v, 5, 7
        addi 7, 7, 16
        .endr
        ld 0, 16(5)
        mtlr 0
        ld 1, 0(5)
        ld 2, 8(5)
        .irp r, 13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31
        ld \r, 24+8*(\r-13)(5)
        .endr
        blr
