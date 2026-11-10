# javapower

A Power ISA emulator for the JVM. It models a PAPR `pseries` machine (POWER9, radix MMU, XICS,
VIO console and v-scsi) closely enough to run QEMU's stock SLOF firmware and boot FreeBSD 15.1 and
openSUSE Tumbleweed (ppc64le) to an interactive console. It is usable as a command-line tool and
as a library. Java 25 or later; no runtime dependencies.

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

## Command line

```
app/build/install/app/bin/app [--ram MiB] [--smp n] [--firmware slof.bin] [--disk img]
    [--disk4k img] [--cdrom iso] [--clock-scale f] [--deterministic] [--icount-shift n]
    [--max-insns n] [--log-interrupts] [--status-secs n]
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

The console is on stdin/stdout; diagnostics and the exit summary (including unimplemented
instructions, hypercalls and RTAS calls) go to stderr.

## Building and testing

Builds use Gradle with a JDK 27 toolchain, compiling for Java 25 with `-Werror`. Tests are JUnit 5;
instruction tests compare against values produced by QEMU.

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
