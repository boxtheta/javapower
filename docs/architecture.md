# Architecture

javapower is a JVM-based Power ISA emulator. Its first targets are stock FreeBSD 15.1 ppc64le,
FreeBSD 15.1 powerpc64 (big-endian) and openSUSE Tumbleweed ppc64le, booted to an interactive
console.

## Machine model

- PAPR `pseries` guest, mirroring QEMU's `-M pseries,ic-mode=xics -cpu power9` closely enough to
  run the stock SLOF firmware unmodified. We build SLOF ourselves from the same tag and toolchain as
  QEMU's `/usr/share/qemu/slof.bin` and ship it in the `firmware-slof` module; the only change is
  the product name in its boot banner (`Javapower Starting`).
- The emulator is the hypervisor: the guest runs with MSR[HV]=0 and `sc 1` in supervisor state is a
  direct call into `Hypervisor.hcall` (QEMU's "virtual hypervisor" arrangement).
- POWER9 DD2.2 (PVR 0x004e1202), ISA 3.0B, big- and little-endian: SLOF is BE; the FreeBSD
  powerpc64 kernel and userland are BE (ELFv2) and run 32-bit BE powerpc binaries (COMPAT_FREEBSD32,
  MSR[SF]=0) with LPCR[ILE]=0; the other kernels are LE.
- Radix MMU only: the guests negotiate radix through CAS. Hash/SLB is out of scope for now.
- XICS interrupts via hcalls. VIO devices only (vty, v-scsi); no PCI devices.

## Modules

| Module | Package | Contents |
|---|---|---|
| `core` | `org.boxtheta.javapower.core` | Shared contracts: `CpuState`, `Op`, `DecodeTable`, `GuestMemory`, `PhysicalMemory`, `Hypervisor`, `PpcException`, `Msr`, `Spr`. Every other module builds on these, so change them with care. |
| `cpu-int` | `org.boxtheta.javapower.cpu` | Integer/branch/system instructions, run loop, JIT, interrupt delivery, timebase/decrementer, radix MMU. |
| `cpu-fpvec` | `org.boxtheta.javapower.fpvec` | Software float, FP, VMX (Altivec), VSX, DFP instructions. |
| `platform` | `org.boxtheta.javapower.papr` | Machine, device tree, hcalls, RTAS, XICS, VIO devices, disk backends. |
| `emulator` | `org.boxtheta.javapower` | The embedding entry point: one artifact that pulls in the CPU and platform modules. |
| `app` | `org.boxtheta.javapower.app` | CLI and integration wiring. |
| `firmware-slof` | `org.boxtheta.javapower.firmware.slof` | The SLOF image (gzip resource, built by `firmware-build/`) and `SlofFirmware`. |

`cpu-int`, `cpu-fpvec` and `platform` depend only on `core`, never on each other; `emulator`
combines them and `app` depends on everything. `firmware-slof` has no dependencies at all (the JDK
decompresses the image), and no library module depends on it: only `app` and tests do. Embedders
pass `SlofFirmware.image()` to `MachineConfig.firmware`; there is no default firmware.

## Library API

The emulator is published as a library as well as a CLI. Maven coordinates are
`org.boxtheta.javapower:javapower-<module>`, and Java packages are
`org.boxtheta.javapower.<module package>`.

Public types are library API: embedders construct and drive the machine from their own code. So
there is no `System.exit`, no reliance on stdin/stdout outside the backends meant for it, no global
mutable state, and host resources (threads, files) must be closeable.

## Java versions

- Java 21 language level (`--release 21`, JDK 21 toolchain).
- `core` and `cpu-int` are multi-release jars: `src/main/java22` (compiled `--release 22`) holds
  the `MemorySegment` versions of `PhysicalMemory` and `cpu.RamAccess`; the Java 21 versions keep
  RAM in heap `byte[]` chunks (atomic CAS on byte-array views works on JDK 21, not on 27). Only
  classes that must differ go there, with the same public API (checked by `validateMultiRelease`).
- No runtime dependencies. The one bundled third-party library is ASM (BSD-3-Clause), relocated
  at build time into `org.boxtheta.javapower.cpu.internal.asm` inside the `cpu-int` jar; the JIT
  generates bytecode with it. ASM stays out of the published POMs.
