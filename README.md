# javapower

A Power ISA emulator for the JVM. It models a PAPR `pseries` machine (POWER9, radix MMU, XICS,
VIO console and v-scsi) closely enough to run the SLOF firmware QEMU uses, unmodified, and boot
FreeBSD 15.1 (ppc64le, and big-endian powerpc64 including its 32-bit compatibility layer) and
openSUSE Tumbleweed (ppc64le) to an interactive console. It is usable as a command-line tool and
as a library. Java 21 or later; no runtime dependencies (the JIT's bytecode library is bundled, see
[Third-party code](#third-party-code)). The SLOF image is built from source by this project and
ships as its own artifact, so no QEMU installation is needed.

## Embedding

Add the `emulator` artifact, which pulls in the CPU, FPU/vector and platform modules, and the
`firmware-slof` artifact for the firmware image (or bring your own; see below):

```xml
<dependency>
  <groupId>org.boxtheta.javapower</groupId>
  <artifactId>javapower-emulator</artifactId>
  <version>0.2.0</version>
</dependency>
<dependency>
  <groupId>org.boxtheta.javapower</groupId>
  <artifactId>javapower-firmware-slof</artifactId>
  <version>0.2.0</version>
</dependency>
```

The artifacts are on Maven Central. The same versions are also published to GitHub Packages,
at `https://maven.pkg.github.com/boxtheta/javapower`, for builds that take their dependencies from
there. GitHub Packages asks for authentication even for public packages: a GitHub user name and a
token with the `read:packages` scope.

Boot an ISO with your own console backend:

```java
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.boxtheta.javapower.Emulator;
import org.boxtheta.javapower.firmware.slof.SlofFirmware;
import org.boxtheta.javapower.papr.CharBackend;
import org.boxtheta.javapower.papr.Machine;
import org.boxtheta.javapower.papr.MachineConfig;
import org.boxtheta.javapower.papr.PaprLog;

class Boot {
    public static void main(String[] args) throws Exception {
        CharBackend console = new CharBackend() {
            @Override public void write(byte[] b, int off, int len) {      // guest output
                System.out.print(new String(b, off, len, StandardCharsets.ISO_8859_1));
            }
            @Override public void start(InputSink sink) {                  // keep it to type input:
                // sink.receive(bytes, 0, bytes.length) from any thread
            }
        };
        MachineConfig cfg = new MachineConfig()
                .ram(2L << 30)
                .cpus(4)
                .firmware(SlofFirmware.image())          // the bundled SLOF image
                .console(console)
                .logSink(PaprLog.printTo(System.err));   // default: System.Logger "org.boxtheta.javapower.papr"
        cfg.addCdrom(Path.of("freebsd-15.1-disc1.iso"));

        try (Emulator emu = new Emulator(cfg)) {
            Machine.ShutdownReason why = emu.run();      // blocks until power-off, panic or stop()
            System.err.println(why + " " + emu.log().unimplementedCounts());
        }
    }
}
```

- `MachineConfig` configures the machine: RAM, CPU count, firmware, console, disks (`addCdrom`,
  `addDisk`, or `addDisk(type, backend)` with your own `BlockBackend`), NVRAM file, kernel command
  line, log sink, and devices of your own in the guest's address space (`addMmio`; see
  [Memory-mapped devices](#memory-mapped-devices)).
- Firmware has no default: pass the image as bytes (`firmware(SlofFirmware.image())` from
  `javapower-firmware-slof`, which decompresses a fresh copy on each call) or as a file
  (`firmware(Path)`, e.g. `/usr/share/qemu/slof.bin`). Without one, the `Machine` constructor throws
  `IllegalArgumentException` naming the artifact. `SlofFirmware.version()` is the SLOF tag.
- Disk images are opened as copy-on-write snapshots by default and never modified; call
  `persistent(true)` on a disk to write through.
- `Emulator.run()` runs CPU 0 on the calling thread and every other CPU on a daemon thread of its
  own; all of them have ended when `run` returns. `run(maxInsns)` stops after an instruction budget
  and can be called again to resume. `stop()` pauses a running guest from any thread.
- `Machine.ShutdownReason` says why the guest stopped (`POWER_OFF`, `PANIC`, `FATAL`, or `NONE` for
  a limit or `stop()`); reboots are followed inside `run`.
- Diagnostics belong to each machine: `emu.log()` holds the sink and the once-per-key counts, so
  machines in one JVM never share log state. The sink is called from emulator threads.
- `close()` stops a running guest, waits for `run` to return, and releases disk images, snapshot
  overlays, the NVRAM file and the console backend. It is idempotent.

`BufferCharBackend` (in-memory, for tests and scripting) and `StdioCharBackend` (standard input
and output) are ready-made console backends.

### Memory-mapped devices

An embedder can put devices of its own into the guest's real address space without javapower
modelling them. `MachineConfig.addMmio` maps a range of real addresses to an `MmioHandler` (in
`javapower-core`), and every guest load and store in the range calls it:

```java
MmioHandler uart = new MmioHandler() {
    @Override public long read(long offset, int size) { ... }              // on CPU threads
    @Override public void write(long offset, int size, long value) { ... }
};
cfg.addMmio("serial", 0x1000_0000_0000L, 0x1000, uart)
        .compatible("acme,uart")      // device tree node /serial@100000000000
        .interrupt(true);             // a level-triggered interrupt source
try (Emulator emu = new Emulator(cfg)) {
    int irq = emu.machine().mmioInterrupt("serial");
    // From the device's own thread: emu.machine().xics().setIrq(irq, true), later false.
    emu.run();
}
```

- The handler sees the data loads and stores the guest makes in the range: in real mode and with
  translation, from the interpreter and from compiled code, in either byte order, and through
  the `H_LOGICAL_CI_LOAD` and `H_LOGICAL_CI_STORE` hypercalls, which SLOF uses for its `rb@`,
  `rl@`, `rb!`, `rl!` and friends. Each call is one naturally aligned access of 1, 2, 4 or 8
  bytes that lies inside the region. Wider instructions arrive as their parts: a vector load or
  store as two 8-byte accesses, `lmw` and `stmw` as one access per word.
- Values are the bytes at the region's addresses read in the region's byte order
  (`byteOrder(...)`, big-endian by default; PCI-style devices are usually little-endian). The
  guest's byte order and the byte-reversed instructions only decide which bytes are stored, as
  with a real device: a little-endian guest's `stw` of 0x11223344 stores the bytes 44 33 22 11,
  so a big-endian region's handler receives 0x44332211 and a little-endian one 0x11223344.
- These accesses never reach the handler: an unaligned access, one that leaves the region, and an
  instruction fetch take the machine check the guest gets for an address nothing is mapped at
  (as every access outside RAM did before). `lwarx` and the other reservation and atomic
  instructions take a data storage interrupt, and `dcbz` an alignment interrupt, as the
  architecture specifies for caching-inhibited storage. `dcbf`, `dcbst` and `icbi` do not touch
  the device. A handler fails an access by throwing `MmioHandler.BusError`: the guest takes the
  machine check, and a hypercall load returns 0 (a store is dropped), as for unassigned memory.
- Threads: `read` and `write` run on the CPU threads, on several at once in a machine with more
  than one CPU, with no lock of the machine held, so the handler synchronises itself. The CPU
  waits for the call, so it should be quick. It may raise and lower its interrupt.
  `reset` (at every machine reset), `saveState` and `loadState` are called while no CPU runs.
- The range may not overlap guest RAM, the windows QEMU's `pseries` reserves for PCI host
  bridges (32 TiB to 64 TiB, `MachineConfig.PCI_WINDOWS_BASE` and `PCI_WINDOWS_END`) or another
  region, and lies below `MachineConfig.MMIO_LIMIT` (2^57). `addMmio`, or for RAM the
  `Emulator` constructor, throws `IllegalArgumentException` otherwise. The name is also the
  device tree node's name.
- A region alone is invisible to an operating system. `compatible(...)` adds a node
  `/<name>@<base>` under the root with `reg`, `compatible` and, with `interrupt`, `interrupts`
  and `interrupt-parent`; `deviceTree(node -> ...)` adds more properties with the public
  `Fdt.Node` API. FreeBSD matches drivers to such nodes by `compatible`; a Linux driver finds the
  node with `of_find_compatible_node`.
- `interrupt(levelTriggered)` claims an XICS source (numbered from `Xics.IRQ_MMIO`, 0x1f00, in
  the order the regions were added); `Machine.mmioInterrupt(name)` returns its number.
  `xics().setIrq(number, level)` drives it from any thread: a level-triggered source is pending
  while raised, an edge-triggered one interrupts once per rising edge (`xics().pulse(number)`).
- A handler's `saveState` output is part of the saved machine and comes back to `loadState`
  (see below). Without `addMmio` the machine, its device tree and its saved state are exactly as
  before.
- `Machine.mmio()` is the `MmioBus` the CPUs consult outside RAM. Platform code may map and
  unmap regions on it while the guest runs (PCI BARs move when the guest programs them); such
  regions are not checked, described in the device tree or saved by the machine.

### Saving and restoring a machine

A paused machine (before the first `run`, or after `run` has returned, e.g. after `stop()` or an
instruction limit) can be written to a stream and continued later, in the same JVM or another:

```java
emu.stop();                                    // from any thread; run() returns
try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
    emu.saveState(out);
}

// Later: a new emulator from the same configuration, with the same disks reattached.
try (Emulator emu = new Emulator(cfg);
     InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
    emu.loadState(in);
    emu.run();                                 // the guest continues where it was saved
}
```

- The state holds every CPU (registers, timebase and decrementer, reservations, idle and
  RTAS-stopped state), guest RAM, the hypervisor (CAS results, the guest's device tree, VPAs,
  process table, watchdogs), XICS, RTAS, and the devices: CRQs, TCE tables, NVRAM contents,
  console input the guest has not read yet, SCSI sense state, and what each MMIO region's
  handler saves. v-scsi commands complete inside
  the hypercall that submits them, so no request is ever in flight at a save. Compiled code,
  decode caches and TLBs are rebuilt as the guest runs.
- Outside the state, and the embedder's to keep: disk contents (reattach the images as the guest
  left them; use `persistent(true)` disks or your own `BlockBackend`, since the default
  copy-on-write snapshots are discarded at `close()`), the console backend, the NVRAM file (it
  is rewritten from the state on load), `MachineConfig.random` and `MachineConfig.clock`.
- The configuration used to load must describe the same hardware: CPU count, RAM size, the PCI
  host bridge and `rng` settings, NVRAM size, the same SCSI devices (type, target id, LUN,
  block size, size, read-only) and the same MMIO regions (name, base, size, byte order,
  interrupt). Otherwise `loadState` throws `StateMismatchException` listing
  the differences and changes nothing. Firmware, boot device, kernel command line, UUID and name
  need not match: the guest's view of them is in its RAM and saved device tree. The clock and
  time scale may differ.
- Guest time stops while a machine is saved: each CPU's timebase continues from its saved value,
  so the guest's uptime and timers resume where they were and its time of day is behind the host
  by the time spent saved, until the guest reads the RTC again or syncs with NTP (RTAS
  get-time-of-day follows `MachineConfig.clock`, plus any offset the guest set).
- The format has a magic and a version and checksums every record; RAM goes in records of at
  most 1 MiB with all-zero 4 KiB pages left out, so the stream compresses well and is never held
  in memory whole. A state of another version, a truncated or corrupt one is refused with
  `StateFormatException`. A corrupt record found after loading has begun leaves the emulator
  refusing to run until a good state is loaded or `reset()` is called. States are independent of
  the Java version (one saved on Java 21 loads on Java 27 and back).
- A state is always the whole machine; there is no incremental (dirty since the last save)
  form. Guest stores reach RAM from the interpreter, compiled code and device DMA without a
  common point at which to mark pages dirty cheaply.

Where guest RAM lives depends on the Java version, and the JVM limit for it must exceed the RAM
of every machine alive at once:

- Java 22 and later: off the Java heap, as direct memory, which the JVM caps with
  `-XX:MaxDirectMemorySize` (by default the maximum heap size, `-Xmx`). For one 2 GiB guest run
  with e.g. `-XX:MaxDirectMemorySize=3g`, or an `-Xmx` above 2 GiB.
- Java 21: on the Java heap, in 1 GiB `byte[]` chunks, so `-Xmx` must exceed the guest RAM by
  the emulator's own needs (about 1 GiB): e.g. `-Xmx3g` for one 2 GiB guest.

If the RAM cannot be allocated, `new Emulator(...)` throws an `OutOfMemoryError` saying which
limit to raise. The memory is returned when the machine becomes unreachable.

`javapower-core` and `javapower-cpu-int` are multi-release jars: on Java 22 and later the JVM
picks the versions of guest RAM and of the JIT's memory accesses built on `MemorySegment`
(`META-INF/versions/22`); on Java 21 it uses equivalent ones built on heap arrays (as Java 21
has no final foreign-memory API), which are slower: in our measurements the CPU time of a
FreeBSD boot is about 5% higher on Java 21 than on Java 27. Keep the jars intact: repackaging
that drops `META-INF/versions` or the `Multi-Release` manifest attribute leaves the Java 21
classes, which refuse to load on a JVM without atomic access to byte arrays (JDK 27 among them).

## Command line

```
app/build/install/app/bin/app [--ram MiB] [--smp n] [--firmware slof.bin] [--disk img]
    [--disk4k img] [--cdrom iso] [--clock-scale f] [--deterministic] [--icount-shift n]
    [--max-insns n] [--log-interrupts] [--status-secs n] [--jit off|blocks|regions]
    [--no-code-sharing] [--persistent] [--load-state file] [--save-state file] [--test-mmio addr]
```

| Flag | Meaning |
|---|---|
| `--ram MiB` | Guest RAM, at least 64 and a multiple of 16 (default 2048); see [Guest RAM](#guest-ram) |
| `--smp n` | Guest CPUs (default 1) |
| `--firmware file` | Firmware image (default: the bundled SLOF image) |
| `--disk img` / `--disk4k img` | Raw disk image, 512- or 4096-byte sectors (snapshot mode) |
| `--cdrom iso` | ISO image as a SCSI CD-ROM |
| `--clock-scale f` | Run guest time `f` times slower than the host clock |
| `--deterministic` | Guest time from the instruction count, fixed seeds and time of day (one CPU) |
| `--icount-shift n` | With `--deterministic`: 2^n virtual nanoseconds per instruction |
| `--max-insns n` | Stop after CPU 0 has executed `n` instructions |
| `--log-interrupts` | Print every interrupt to stderr |
| `--status-secs n` | Print per-CPU progress to stderr every `n` seconds |
| `--jit off\|blocks\|regions` | Compile hot guest code to JVM bytecode: not at all, basic blocks only, or blocks plus whole-page regions (default `regions`) |
| `--no-code-sharing` | Compile code separately for each CPU and address space instead of sharing it |
| `--persistent` | Write guest changes to the `--disk` images instead of a discarded snapshot |
| `--load-state file` | Continue the machine saved in `file` (gzipped if the name ends in `.gz`); give the same hardware options |
| `--save-state file` | When the guest is stopped by `--max-insns`, SIGTERM or SIGINT, save the machine to `file` |
| `--test-mmio addr` | For testing only: 4 KiB of scratch registers at real address `addr` (node `/test-mmio@addr`), reporting every access on stderr; see `scripts/guest/mmio` |

The console is on stdin/stdout; diagnostics and the exit summary (including unimplemented
instructions, hypercalls and RTAS calls) go to stderr. Guest RAM counts against the JVM's direct
memory limit on Java 22 and later, and against its heap on Java 21 (see [Embedding](#embedding));
for large `--ram` values raise the limit in `JAVA_OPTS`, e.g.
`JAVA_OPTS=-XX:MaxDirectMemorySize=9g app --ram 8192 ...` (Java 22+) or `JAVA_OPTS=-Xmx10g`
(Java 21).

## Supported guests

| Guest | Status | Typical command |
|---|---|---|
| FreeBSD 15.1 ppc64le install ISO | boots to the installer | `app --cdrom FreeBSD-15.1-RELEASE-powerpc-powerpc64le-disc1.iso` |
| FreeBSD 15.1 powerpc64 (big-endian) install ISO | boots to the installer; its kyua tests and 32-bit (COMPAT_FREEBSD32) programs give the same results as under QEMU | `app --ram 4096 --cdrom FreeBSD-15.1-RELEASE-powerpc-powerpc64-disc1.iso` |
| openSUSE Tumbleweed ppc64le Minimal VM (4K-sector raw image) | boots to the first-boot configuration screen | `app --disk4k opensuse-tw-minimal.raw --clock-scale 4` |

`scripts/guest/drive.py` checks these by driving the console through a pty,
against our CLI or QEMU (see `scripts/guest/README.md`).

### Guest RAM

`MachineConfig.ram` and `--ram` take any multiple of 16 MiB from 64 MiB up. As in QEMU, the
largest power of two not above the RAM size is the real mode area (`memory@0` in the device
tree), and the rest of the RAM is described by further `memory@` nodes. SLOF only uses the real
mode area and keeps about 37 MiB at its top for itself, so the boot loader and whatever it loads
through SLOF have to fit in the rest of it; the kernels then use all of the RAM. Multiples of
256 MiB, the only sizes QEMU accepts, give the same device tree as QEMU.

The smallest sizes that work with the guests above:

| Guest | Least RAM | Below that |
|---|---|---|
| SLOF alone | 64 MiB | 48 MiB leaves a 32 MiB real mode area, and SLOF stops with "Not enough memory for Open Firmware" |
| FreeBSD 15.1, either ISO | 144 MiB | at 128 MiB the kernel boots, but the installer's processes are killed for lack of memory; below 128 MiB SLOF cannot load the FreeBSD loader, which is linked at 44 MiB, where SLOF itself sits ("Memory for ELF file is already in use!") |
| openSUSE Tumbleweed Minimal | 704 MiB | from 512 to 640 MiB the kernel boots, but the out-of-memory killer stops the first-boot setup (at 512 MiB under QEMU too); below 512 MiB GRUB cannot fit the kernel and the initrd into the real mode area |

## Building and testing

Builds use Gradle with a JDK 21 toolchain, compiling for Java 21 with `-Werror`. Tests are JUnit 5;
instruction tests compare against values produced by QEMU. `gradle check` runs every test twice:
on JDK 21 (`test`, which exercises the Java 21 classes of the multi-release jars) and on JDK 27
(`test27`, the `src/main/java22` ones); Gradle finds both JDKs among the installed ones. It also
runs `jar --validate` on the multi-release jars, which fails if the two versions of a class differ
in public API.

```
gradle :core:test :platform:test :emulator:test   # unit and machine tests
gradle :cpu-int:test :cpu-fpvec:test              # instruction tests
gradle :app:installDist                           # CLI in app/build/install/app
gradle publishToMavenLocal                        # library artifacts
```

See [docs/development.md](docs/development.md) for what else is needed to regenerate test vectors,
run the guest checks and rebuild the firmware.

Modules: `core` (shared contracts), `cpu-int` (integer, branch, system instructions, MMU, run
loop), `cpu-fpvec` (floating point, VMX, VSX), `platform` (machine model, hypercalls, RTAS,
devices), `emulator` (the embedding entry point), `app` (the CLI), `firmware-slof` (the SLOF
image; depends on nothing else here, and only `app` and tests depend on it).

The machine model and module layout are described in [docs/architecture.md](docs/architecture.md);
conventions for changes are in [CONTRIBUTING.md](CONTRIBUTING.md).

### Rebuilding the firmware

The SLOF image is committed, compressed, as
`firmware-slof/src/main/resources/org/boxtheta/javapower/firmware/slof/slof.bin.gz`, next to
`slof.properties`, which records its provenance (SLOF tag and commit, the patch applied, toolchain
versions, base image digest, size and SHA-256 of the raw and compressed image). A normal Gradle
build needs neither a cross compiler nor podman. To rebuild it (Linux with podman, rootless is
fine):

```
firmware-slof/firmware-build/build.sh [--install] [outdir]
```

It builds SLOF board `qemu` in a container (Debian bookworm pinned by digest, packages from a
pinned snapshot.debian.org date, SLOF pinned by commit), with `SOURCE_DATE_EPOCH` set to the
commit time, and compresses it with zopfli (standard gzip with no name or timestamp, about 6%
smaller than `gzip -9`), so the output is bit-for-bit reproducible. The cross toolchain (GCC 12.2, binutils
2.40) is the one QEMU's own image was built with, so the result differs from
`/usr/share/qemu/slof.bin` only in its build date and in the product name of the boot banner:
`firmware-build/product-name.patch` makes it read `Javapower Starting` instead of `QEMU Starting`.
It prints the SHA-256 of both files; `--install` copies the result into the resources. To move to
a new SLOF release, change the tag and commit (and if needed the snapshot date) in
`firmware-build/Containerfile`.

### Releasing

`scripts/release.sh <version> [--upload]` builds and checks the modules, signs every artifact
with the release key (GPG
`6E501D76959FFF2632BDEE5B8BE9EC452CDD18D3`) and writes
`build/javapower-<version>-central.zip`, the bundle Maven Central's portal accepts. With `--upload`
it sends the bundle to the portal (user token in `CENTRAL_TOKEN_USER` and `CENTRAL_TOKEN_PASSWORD`)
as a deployment that is then released by hand in the portal. Run it from a terminal, on a clean
checkout of the commit to release.

Publishing the GitHub release for the tag (`v<version>`) starts the
`Publish to GitHub Packages` workflow, which builds that tag and publishes every module to GitHub
Packages with the workflow's own token. It does not run the tests again: the release was checked
by `scripts/release.sh`. The workflow can also be run by hand for a tag that already exists.
Those artifacts are built by the workflow, so they are not the signed files on Maven Central, and
GitHub Packages does not let a published version be replaced.

## Third-party code

`javapower-cpu-int` bundles [ASM](https://asm.ow2.io/) 9.11.1, which its JIT uses to generate
bytecode. ASM is licensed under the BSD 3-Clause license; the text is in
[cpu-int/LICENSE-ASM](cpu-int/LICENSE-ASM) and in the jar as `META-INF/LICENSE-ASM`. Its classes
are relocated to the internal package `org.boxtheta.javapower.cpu.internal.asm` at build time, so
the published POM declares no dependency on ASM and it cannot clash with another copy of ASM on
the class path. The relocated package is not part of javapower's API.

## License

Copyright 2026 Box Theta. Apache License, Version 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

## Third-party notes

**SLOF.** The `javapower-firmware-slof` artifact contains a binary image of SLOF (Slimline Open
Firmware, https://gitlab.com/slof/slof), built by this project from the upstream tag
`qemu-slof-20260627` with one change: the product name in the boot banner is `Javapower` instead
of `QEMU` ([product-name.patch](firmware-slof/firmware-build/product-name.patch)). SLOF is copyright IBM Corporation and contributors and is distributed under
the 3-clause BSD license; the jar carries it as `META-INF/LICENSE-SLOF` (source:
[firmware-slof/LICENSE-SLOF](firmware-slof/LICENSE-SLOF)) and its POM lists both licenses. The
x86 BIOS emulator, which SLOF keeps under a different license, is not built into the image. The
artifact has no dependencies; the JDK's `GZIPInputStream` decompresses the image.
