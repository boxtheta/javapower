# Bare-metal differential-test firmware, little-endian 64-bit part (see README.md).
#
# Low memory (real addresses):
#   0x00100       big-endian trampoline (tramp.S), patched in by build.py
#   0x00200-0x00fe0 interrupt stubs, one every 0x20 bytes (vectors.inc)
#   0x03000-0x03fe0 scv stubs for LPCR[AIL]=3 (reached at 0xc000000000003000 + lev*0x20)
#   0x04200-0x04fe0 interrupt stubs for AIL=3 (reached at 0xc000000000004000 + vector)
#   0x07000       SCR: state captured by the interrupt handler
#   0x07800       HSAVE: harness context saved by run_case
#   0x17000-0x17fe0 scv stubs (AIL=0 and AIL=2), one every 0x20 bytes
#   0x18200-0x18fe0 interrupt stubs for AIL=2 (0x18000 + vector)
#   0x20000       _start (harness code)
#
# With AIL the handler starts with translation on; the case's page tables then identity-map
# 0..4 MiB in both quadrants, and `common` switches back to real mode before returning.
#
# Each stub saves r3 in SPRG1, loads its own address into r3 and branches to `common`, which
# saves the interrupted state into SCR with RA=0 absolute addressing (no free register needed)
# and then returns from run_case() to the C driver.

        .set SCR,   0x7000
        .set HSAVE, 0x7800
        .set C_GPR, 160
        .set C_CR,  416

        .abiversion 2
        .section .vectors,"ax"
#include "vectors.inc"

        .section .text.entry,"ax"
        .globl _start
_start:
        bl      0f
0:      mflr    12
        addis   2, 12, (.TOC. - 0b)@ha
        addi    2, 2, (.TOC. - 0b)@l
        lis     1, 0x10         # stack top at 1 MiB
        li      0, 0
        stdu    0, -64(1)
        bl      cmain
        nop
1:      b       1b

        .text
        .globl run_case
        .type run_case,@function
# void run_case(struct Case *c)
run_case:
        std     1, HSAVE(0)
        std     2, HSAVE+8(0)
        .irp r, 13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31
        std     \r, HSAVE+16+8*(\r-13)(0)
        .endr
        mflr    4
        std     4, HSAVE+168(0)
        mfcr    4
        std     4, HSAVE+176(0)

        ld      4, 16(3)        # entry
        mtsrr0  4
        ld      4, 8(3)         # msr
        mtsrr1  4
        ld      4, C_CR(3)
        mtcrf   0xff, 4
        ld      4, C_CR+8(3)
        mtxer   4
        ld      4, C_CR+16(3)
        mtlr    4
        ld      4, C_CR+24(3)
        mtctr   4
        .irp r, 0,1,2,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31
        ld      \r, C_GPR+8*\r(3)
        .endr
        ld      3, C_GPR+24(3)
        .globl case_rfid
case_rfid:                      # its address is CFAR when the case starts
        rfid

        .globl common
common:
        std     4, SCR+48+8*4(0)
        std     3, SCR(0)
        mfspr   4, 273          # SPRG1: the interrupted r3
        std     4, SCR+48+8*3(0)
        .irp r, 0,1,2,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31
        std     \r, SCR+48+8*\r(0)
        .endr
        mfsrr0  4
        std     4, SCR+8(0)
        mfsrr1  4
        std     4, SCR+16(0)
        mfspr   4, 19           # DAR
        std     4, SCR+24(0)
        mfspr   4, 18           # DSISR
        std     4, SCR+32(0)
        mfmsr   4
        std     4, SCR+40(0)
        mfcr    4
        std     4, SCR+304(0)
        mfxer   4
        std     4, SCR+312(0)
        mflr    4
        std     4, SCR+320(0)
        mfctr   4
        std     4, SCR+328(0)
        mfmsr   4               # back to real mode (relocation-on interrupts, AIL)
        li      3, 0x30
        andc    4, 4, 3
        mtmsrd  4
        isync

        ld      1, HSAVE(0)
        ld      2, HSAVE+8(0)
        .irp r, 13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31
        ld      \r, HSAVE+16+8*(\r-13)(0)
        .endr
        ld      4, HSAVE+168(0)
        mtlr    4
        ld      4, HSAVE+176(0)
        mtcrf   0xff, 4
        blr

        .globl hcall
        .type hcall,@function
# long hcall(long op, a1..a7)
hcall:
        sc      1
        blr

        .globl flush_tlb
# Invalidate all process- and partition-scoped translations (RIC=2, PRS=1, R=1, IS=3).
flush_tlb:
        li      4, 0xc00
        ptesync
        .long   0x7c0b2224      # tlbiel 4, 0 (rs), RIC=2, PRS=1, R=1
        ptesync
        isync
        blr

        .globl sync_icache
# void sync_icache(void *p, long len): make stored instructions visible to fetch.
sync_icache:
        add     4, 3, 4
1:      dcbst   0, 3
        sync
        icbi    0, 3
        addi    3, 3, 32
        cmpld   3, 4
        blt     1b
        isync
        blr
