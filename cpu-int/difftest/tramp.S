# Big-endian entry trampoline, placed at real address 0x100 (SPAPR_ENTRY_POINT).
# QEMU's pseries starts the "firmware" here with MSR = ME|FP (32-bit, big-endian, real mode).
# Make interrupts little-endian (H_SET_MODE resource LE), then rfid into the little-endian
# 64-bit harness at ENTRY.
        .set ENTRY, 0x20000
        .text
        li      3, 0x31c        # H_SET_MODE
        li      4, 1            # mflags: little endian
        li      5, 4            # resource: LE
        li      6, 0
        li      7, 0
        sc      1
        lis     10, ENTRY@h
        ori     10, 10, ENTRY@l
        mtsrr0  10
        li      11, 1           # MSR = SF | ME | FP | LE
        rldicr  11, 11, 63, 0
        ori     11, 11, 0x3001
        mtsrr1  11
        rfid
