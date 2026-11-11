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
- XICS interrupts via hcalls. VIO devices only (nvram, vty, v-scsi, then the devices added through
  `MachineConfig`: an l-lan virtual Ethernet device for each network, and embedders' own); no PCI
  devices.
- Memory-mapped I/O: real addresses outside RAM reach the regions of an `MmioBus`, which
  embedders fill through `MachineConfig.addMmio` (see [Memory-mapped I/O](#memory-mapped-io)).
- Devices with host-side backends: the console (`CharBackend`), SCSI disks (`BlockBackend`) and
  network devices (`NetBackend`). Guest output to a backend is a synchronous call on the CPU
  thread that produced it; input arrives on the backend's own threads and goes straight to the
  device (the console's buffer, or a network frame DMAed into a receive buffer the guest
  posted) under the device's lock, which no backend call is made under. Network frames are
  kept out of saved states: a save or load stops deliveries until a CPU runs again, so device
  state and RAM agree.

## VIO devices

Every VIO device is a `VioDevice` subclass in one ordered list: nvram, vty and v-scsi at unit
addresses 0x71000000 to 0x71000002, then the devices the configuration adds, at the following
addresses in the order they were added (`MachineConfig.addedVio`, which every kind of added VIO
device goes through). The machine assigns each one its interrupt source and TCE table, routes
the CRQ, TCE and H_VIO_SIGNAL hypercalls to it by unit address, generates its device tree node,
resets, quiesces, saves and loads it, and describes the added ones in the hardware check.
Embedders' own devices are added the same way (`MachineConfig.addVioDevice`); the members of
`VioDevice` they may use and override, and the threads that call them, are documented on the
class.

## Modules

| Module | Package | Contents |
|---|---|---|
| `core` | `org.boxtheta.javapower.core` | Shared contracts: `CpuState`, `Op`, `DecodeTable`, `GuestMemory`, `PhysicalMemory`, `MmioBus` (with `MmioRegion` and `MmioHandler`), `Hypervisor`, `PpcException`, `Msr`, `Spr`. Every other module builds on these, so change them with care. |
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

## Memory-mapped I/O

An `MmioBus` (in `core`, since the MMU in `cpu-int` consults it) holds the MMIO regions of one
machine: real address ranges, each with an `MmioHandler` and the byte order of its values. The
`Machine` owns the bus and maps the embedder's regions on it; the `emulator` module hands it to
the `CpuDomain`, whose CPUs' MMUs consult it. Platform code can map and unmap regions while the
CPUs run, which PCI BARs need.

- RAM stays as fast as without MMIO. The MMU looks at the bus only where it used to raise the
  machine check for unassigned memory: real mode's bounds check, and a table walk that ends
  outside RAM. MMIO pages never enter the software TLB, so the TLB hit path, the JIT's inline
  RAM accesses (`JitRt`, `RamAccess`) and the code `RegionCompiler` generates are unchanged:
  compiled code reaches MMIO through its existing slow path, the MMU's full access methods. Each
  MMIO access walks the page table again, which is cheap next to a device access and means a
  region unmapped or moved is never reached through a stale translation.
- The MMU signals an MMIO access with a preallocated exception (one per MMU, no stack trace)
  from its translation method, caught by the access method, which calls the handler. The bus
  publishes an immutable sorted array of regions through a volatile field, so lookups take no
  lock and a change is seen by every lookup after it.
- Accesses: a naturally aligned access of 1, 2, 4 or 8 bytes wholly inside one region calls the
  handler, with the value converted between the region's byte order and MSR[LE]'s (the
  hypercalls are big-endian). Everything else outside RAM keeps today's machine check (QEMU's
  POWER9 codes for unassigned memory): unaligned accesses (QEMU's default for device regions),
  accesses that leave a region or cross a page into one, instruction fetches (QEMU can execute
  from MMIO; no guest of ours does), and handler bus errors (`MmioHandler.BusError`, QEMU's
  `MEMTX_ERROR`). Instructions wider than 8 bytes make their usual 8-byte or smaller accesses,
  as QEMU splits them.
- Reservation and atomic instructions (`l*arx`, `st*cx.`, `lwat`, `ldat`, `stwat`, `stdat`)
  on a region take a DSI with DSISR bit 37, and `dcbz` an alignment interrupt, as the
  architecture specifies for caching-inhibited storage, before the device sees any access.
  QEMU performs them on the device instead. `dcbf`, `dcbst` and `icbi` translate the address but
  no longer load from it (QEMU loads), so a device never sees them.
- `H_LOGICAL_CI_LOAD`/`STORE` (and the `CACHE` forms) reach the same regions, under the same
  rules; a failed access loads 0 or drops the store, as QEMU does for unassigned memory.
- Handlers run on the CPU threads, concurrently, with no machine lock held, so they may drive
  XICS (a leaf lock). The embedder's regions take interrupt sources from `Xics.IRQ_MMIO`
  (0x1f00, the top 256 sources), away from the VIO, PCI LSI and MSI numbers QEMU allocates.
- Embedder regions may not overlap RAM, each other, or the PCI host bridge windows (32 TiB to
  64 TiB, QEMU's `SPAPR_PCI_BASE` to `SPAPR_PCI_LIMIT`), and lie below 2^57, the end of what a
  radix PTE addresses. Their device tree nodes are children of the root, after the machine's own
  nodes. The saved state's hardware record lists them (name, base, size, byte order, interrupt)
  only when there are any, and an `"MMIO"` record holds each handler's own state, so states of
  machines without regions are unchanged.

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
