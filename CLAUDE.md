# javapower

A JVM-based Power ISA emulator. First target: boot stock FreeBSD 15.1 ppc64le and openSUSE
Tumbleweed ppc64le to an interactive console.

## Machine model

- PAPR `pseries` guest, mirroring QEMU's `-M pseries,ic-mode=xics -cpu power9` closely enough to
  run the stock SLOF firmware binary (`/usr/share/qemu/slof.bin`) unmodified.
- The emulator is the hypervisor: guest runs with MSR[HV]=0 and `sc 1` in supervisor state is a
  direct call into `Hypervisor.hcall` (QEMU's "virtual hypervisor" arrangement).
- POWER9 DD2.2 (PVR 0x004e1202), ISA 3.0B, big- and little-endian (SLOF is BE, the kernels are LE).
- Radix MMU only: both guests negotiate radix through CAS. Hash/SLB is out of scope for now.
- XICS interrupts via hcalls. VIO devices only (vty, v-scsi); no PCI devices.

## Modules and ownership

| Module | Package | Contents |
|---|---|---|
| `core` | `javapower.core` | Shared contracts: `CpuState`, `Op`, `DecodeTable`, `GuestMemory`, `PhysicalMemory`, `Hypervisor`, `PpcException`, `Msr`, `Spr`. Change only with the lead's agreement. |
| `cpu-int` | `javapower.cpu` | Integer/branch/system instructions, run loop, interrupt delivery, timebase/decrementer, radix MMU. |
| `cpu-fpvec` | `javapower.fpvec` | Software float, FP, VMX (Altivec), VSX, DFP instructions. |
| `platform` | `javapower.papr` | Machine, device tree, hcalls, RTAS, XICS, VIO devices, disk backends. |
| `app` | `javapower.app` | CLI and integration wiring. |

Modules other than `core` depend only on `core`, never on each other (`app` depends on all).

## Conventions

- Java 25 language level, no runtime dependencies. JUnit 5 for tests. `-Werror` is on.
- Comments use ISA bit numbering (bit 0 = MSB) and say so; code uses Java shifts.
- Instruction handlers are stateless `Op`s registered into a `DecodeTable` through an `InsnSet`.
  They must raise `PpcException` before modifying state.
- Correctness is established against QEMU, not from memory of the spec: every instruction group
  needs differential tests whose expected values came from running the real instruction under QEMU.

## Dev server

All builds, tests and QEMU runs happen on `devserver.nodes.bxta.net` (Arch, 12 cores, JDK 27,
Gradle, QEMU 11.1, clang/lld with PowerPC targets, gdb, dtc). Nothing is built locally.

- `scripts/remote.sh <workspace> <command...>` rsyncs this checkout to `~/javapower/work/<workspace>`
  on the server and runs the command there. Use your own workspace name so parallel builds don't
  collide, e.g. `scripts/remote.sh agent-a gradle -q :cpu-int:test`.
- `~/javapower/images/`: `freebsd-15.1-disc1.iso`, `opensuse-tw-minimal.raw` (4096-byte sectors).
- `~/javapower/ref/`: `run-qemu.sh` (reference boot command line), console logs of both guests
  booting in QEMU, and `pseries-min.dts` (the device tree QEMU hands to SLOF).
- `~/javapower/src/qemu` (v11.1.3) and `~/javapower/src/SLOF`: reference sources. QEMU is GPL: read
  it to learn behaviour, do not copy or transliterate its code.
- Cross-compile guest test code with `clang --target=powerpc64le-linux-gnu -mcpu=power9 -nostdlib
  -static -fuse-ld=lld`; run user-mode binaries with `qemu-ppc64le -cpu power9`.
- Scratch files on the server go under `~/javapower/work/<workspace>-scratch/`.
