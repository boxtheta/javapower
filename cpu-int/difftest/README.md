# cpu-int differential tests

Golden vectors for `org.boxtheta.javapower.cpu` come from QEMU, not from the spec. Unlike cpu-fpvec (user
mode), this harness runs **bare metal under `qemu-system-ppc64 -M pseries -cpu power9`** with our
own firmware in place of SLOF, so it can cover 32-bit mode, both endiannesses, problem and
supervisor state, privileged instructions, SPR access rules, interrupt delivery (SRR0/SRR1,
DAR/DSISR, AIL), and the radix MMU, with the exact state QEMU leaves.

## Pieces

| File | Role |
|---|---|
| `tramp.S` | Big-endian entry at 0x100: H_SET_MODE(LE interrupts), rfid to 64-bit LE code |
| `boot.S` | Interrupt stubs at every vector (also AIL=3/AIL=2 and scv locations), `run_case`, `common` handler |
| `harness.c` | Driver: per case loads SPRs, code, memory window and page-table patches, runs, records |
| `link.ld` | Firmware layout |
| `gen.py` | Builds the firmware, assembles cases with llvm-mc, runs QEMU, writes golden lines |
| `groups_*.py` | Case generators: `alu`, `alu32`, `branch`, `ldst`, `sys`, `spr`, `mmu` (+ `selftest` in `groups.py`) |
| `run.sh` / `fetch.sh` | Regenerate on the dev server / copy into `src/test/resources/golden-int/` |
| `scan.py` | Coverage scan: decodes every instruction word of real guest binaries |
| `testsummary.py` | Prints failures from the last `:cpu-int:test` run |

A case is a few instructions placed at real 0x200000 and entered by `rfid` with the case's MSR.
Every case ends in `sc`, so it finishes at the first interrupt; the handler records the vector,
SRR0/SRR1, DAR/DSISR, the handler MSR, all GPRs, CR/XER/LR/CTR, 16 SPRs, the 512-byte data
window at 0x2FFF00 (straddling a page boundary) and up to 12 page-table entries. On the Java
side `Golden.java` rebuilds the same machine (LPCR, process table at 0x400000, SPRs, CFAR) and
`GoldenTest` runs each case through the full `Interpreter` until its first interrupt.

## Usage

    scripts/remote.sh agent-a sh cpu-int/difftest/run.sh            # all groups (~1 min)
    sh cpu-int/difftest/fetch.sh                                   # locally
    scripts/remote.sh agent-a 'gradle -q :cpu-int:test; python3 cpu-int/difftest/testsummary.py'

Coverage scan of guest binaries (paths on the dev server):

    scripts/remote.sh agent-a 'gradle -q :cpu-int:testClasses && python3 cpu-int/difftest/scan.py <elf or dir>...'

## Known, deliberate differences from QEMU

* XER bits other than SO/OV/CA/OV32/CA32/byte count are not stored (QEMU keeps bits 32:63 of
  any mtxer value); `CpuState` has no field for them.
* Trace interrupts (MSR[SE]/MSR[BE]) are not implemented.
* The branch history buffer is not recorded (mfbhrbe returns 0); PMU registers are storage only.
* `slbmte` and friends are no-ops (radix only); QEMU validates SLB operands.
