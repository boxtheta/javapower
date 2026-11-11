# javapower

A Power ISA emulator for the JVM. It models a PAPR `pseries` machine (POWER9, radix MMU, XICS,
VIO console and v-scsi) closely enough to run QEMU's stock SLOF firmware and boot FreeBSD 15.1
(ppc64le, and big-endian powerpc64 including its 32-bit compatibility layer) and openSUSE
Tumbleweed (ppc64le) to an interactive console. It is usable as a command-line tool and
as a library. Java 21 or later; no runtime dependencies (the JIT's bytecode library is bundled, see
[Third-party code](#third-party-code)).

## Embedding

Add the `emulator` artifact, which pulls in the CPU, FPU/vector and platform modules:

```xml
<dependency>
  <groupId>org.boxtheta.javapower</groupId>
  <artifactId>javapower-emulator</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Boot an ISO with your own console backend:

```java
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.boxtheta.javapower.Emulator;
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

- `MachineConfig` configures the machine: RAM, CPU count, firmware (default
  `/usr/share/qemu/slof.bin`), console, disks (`addCdrom`, `addDisk`, or `addDisk(type, backend)`
  with your own `BlockBackend`), NVRAM file, kernel command line, log sink.
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
    [--no-code-sharing]
```

| Flag | Meaning |
|---|---|
| `--ram MiB` | Guest RAM, a multiple of 256 (default 2048) |
| `--smp n` | Guest CPUs (default 1) |
| `--firmware file` | Firmware image (default `/usr/share/qemu/slof.bin`) |
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

`scripts/guest/drive.py` checks these on the dev server by driving the console through a pty,
against our CLI or QEMU (see `scripts/guest/README.md`).

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

The SLOF test in `emulator` needs `/usr/share/qemu/slof.bin` and is skipped without it. In this
project the builds run on the shared dev server through `scripts/remote.sh <workspace> <command>`
(see `CLAUDE.md`).

Modules: `core` (shared contracts), `cpu-int` (integer, branch, system instructions, MMU, run
loop), `cpu-fpvec` (floating point, VMX, VSX), `platform` (machine model, hypercalls, RTAS,
devices), `emulator` (the embedding entry point), `app` (the CLI).

## Third-party code

`javapower-cpu-int` bundles [ASM](https://asm.ow2.io/) 9.11.1, which its JIT uses to generate
bytecode. ASM is licensed under the BSD 3-Clause license; the text is in
[cpu-int/LICENSE-ASM](cpu-int/LICENSE-ASM) and in the jar as `META-INF/LICENSE-ASM`. Its classes
are relocated to the internal package `org.boxtheta.javapower.cpu.internal.asm` at build time, so
the published POM declares no dependency on ASM and it cannot clash with another copy of ASM on
the class path. The relocated package is not part of javapower's API.

## License

Apache License, Version 2.0. See [LICENSE](LICENSE).
