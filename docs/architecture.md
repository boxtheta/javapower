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
- XICS interrupts via hcalls. VIO devices (nvram, vty, v-scsi, then the devices added through
  `MachineConfig`: an l-lan virtual Ethernet device for each network, and embedders' own), and
  on the PCI host bridge the PCI devices an embedder adds, virtio ones through a virtio PCI
  transport (see [PCI devices](#pci-devices)).
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
| `platform` | `org.boxtheta.javapower.papr` | Machine, device tree, hcalls, RTAS, XICS, VIO devices, PCI devices and the virtio PCI transport, disk backends. |
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

## PCI devices

PHB 0, the PCI host bridge QEMU's `pseries` always creates, holds the PCI devices an embedder
adds with `MachineConfig.addPciDevice` (`Phb` in `platform`). There is no hot-plug: the devices
are placed in their slots when the machine is built, those given a slot first, then the others
in the order they were added, each into the lowest free slot.

- Enumeration: like QEMU, the machine puts a node per device under the bridge's node (name from
  the class code, `reg`, ids, `interrupts`, `ibm,loc-code`, `ibm,my-drc-index`, in QEMU's
  property order) and keeps `qemu,phb-enumerated`, so SLOF does not scan the bus: it walks those
  nodes, assigns BARs through RTAS from the windows in the bridge's `ranges`, writes
  `assigned-addresses` and enables decoding. Linux and FreeBSD then read configuration space
  through RTAS (`ibm,read-pci-config`; the forms without a BUID fail as in QEMU) and route
  interrupts through the bridge's `interrupt-map`. The slot's PCI DR connector is populated.
  The device tree of a machine without PCI devices is unchanged.
- `PciDevice` holds the 256 bytes of conventional configuration space with QEMU's write masks:
  writable command bits (I/O, memory, bus master, SERR, INTx disable), cache line size and
  interrupt line, write-one-to-clear status error bits, BARs that keep their type bits and decode
  a naturally aligned range, read-only capabilities, plain storage elsewhere after the header.
  Accesses come from RTAS under the machine lock and the device's configuration lock.
- BARs: when the guest writes a BAR or the command register, the device recomputes each BAR's
  PCI address (QEMU's `pci_bar_address` rules) and remaps it on the machine's `MmioBus` at the
  real address the bridge's windows give it: the I/O window (ports 0 to 0xffff at 32 TiB), the
  32-bit memory window (PCI 2 to 4 GiB at 32 TiB + 2 GiB) or the 64-bit one (PCI 33 TiB to
  34 TiB at the same real addresses). A BAR outside the windows or overlapping another BAR is
  left unmapped. Remapping while CPUs run is safe because the bus never lets a translation to a
  region outlive a lookup. BAR regions are little-endian.
- Interrupts: INTx only. Pin p of slot s drives LSI `0x1200 + (s + p - 1) % 4`, QEMU's swizzle,
  which the existing `interrupt-map` describes; the bridge counts the devices asserting each LSI
  and drives the XICS source while any does. INTx disable in the command register masks a pin.
  The `ibm,change-msi` family still answers as for a device without MSI: no guest driver we
  support needs MSI (Linux's and FreeBSD's virtio drivers fall back to INTx, FreeBSD's `vtrnd`
  even polls), so MSI-X was left out.
- DMA: `PciDevice.dmaRead`/`dmaWrite` translate bus addresses through the bridge's TCE table
  (LIOBN 0x80000000, a 1 GiB window of 4 KiB pages) while bus mastering is enabled. The DDW
  calls (`ibm,query-pe-dma-window` and the rest) still fail with a parameter error, so Linux uses
  that window instead of the 64-bit direct window QEMU would create.
- The virtio PCI transport (`VirtioPciDevice`, virtio 1.1 section 4.1) builds what QEMU's
  `virtio-*-pci,vectors=0` builds on a conventional PCI bus: one 16 KiB 64-bit BAR with the
  common configuration, ISR, device configuration and notification areas (multiplier 4),
  described by vendor capabilities plus the PCI configuration access capability. By default,
  as in QEMU, a device type with a legacy interface is transitional: it also has the legacy
  registers in I/O BAR 0 and the legacy device id; other types are modern only. It offers
  VIRTIO_F_VERSION_1, RING_INDIRECT_DESC and RING_EVENT_IDX, not ACCESS_PLATFORM, so addresses
  in the split rings are guest real addresses, as QEMU's virtio devices use by default and the
  stock pseries drivers expect. As in QEMU, features the driver writes that were not offered
  are dropped and VERSION_1 is not insisted on; without VERSION_1 the rings are in the legacy
  byte order, that of the CPU that last reset the device (big-endian at a machine reset).
  Linux uses the modern interface. FreeBSD uses the legacy interface of a transitional device;
  its modern driver byte-swaps the registers twice on a big-endian kernel and so negotiates no
  features and programs swapped ring addresses (under QEMU as well), which the transport
  refuses as misaligned. The device-specific part is a `VirtioDevice`; `VirtioRng` is the
  built-in one.
- Threads and locks: each transport has one lock guarding its registers and every virtqueue;
  `Virtqueue` and `VirtqueueRequest` take it for each call and read or write guest RAM under
  it, so a device reset (which takes it) never races a copy into memory the guest reclaims, and
  requests popped before a reset are stale afterwards. Device hooks are called without it, so a
  device may take its own locks and call back into the transport. The lock order is machine
  lock, device configuration lock, transport lock, INTx lock, bridge LSI lock, XICS.
- Saved state: a `"PCI "` record after `"PAPR"`, only when there are PCI devices, holds each
  device's configuration space, interrupt level and own state (for virtio: the transport
  registers, each queue's addresses and indices, and the device's state). Requests popped and
  not completed are saved as to be popped again; after a load `Machine.pump` tells the device
  once the guest runs, since RAM is loaded after the platform. The hardware description gains a
  `"PCI "` section (slot, model, ids, class, pin, BARs and the device's own description).

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
