# Big-endian entry trampoline, placed at real address 0x100 (SPAPR_ENTRY_POINT).
# QEMU's pseries starts the "firmware" here with MSR = ME|FP (32-bit, big-endian, real mode).
# For the little-endian harness (FW_BE=0): make interrupts little-endian (H_SET_MODE resource
# LE), then rfid into the 64-bit LE harness at ENTRY. For the big-endian harness (FW_BE=1)
# interrupts stay big-endian (LPCR[ILE]=0 after reset) and the rfid enters it in 64-bit BE.
# FW_BE is defined on the llvm-mc command line (-defsym).
        .set ENTRY, 0x20000
        .text
.if FW_BE == 0
        li      3, 0x31c        # H_SET_MODE
        li      4, 1            # mflags: little endian
        li      5, 4            # resource: LE
        li      6, 0
        li      7, 0
        sc      1
.endif
        lis     10, ENTRY@h
        ori     10, 10, ENTRY@l
        mtsrr0  10
        li      11, 1           # MSR = SF | ME | FP (| LE)
        rldicr  11, 11, 63, 0
.if FW_BE == 0
        ori     11, 11, 0x3001
.else
        ori     11, 11, 0x3000
.endif
        mtsrr1  11
        rfid
