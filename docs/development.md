# Development environment

Builds, tests and QEMU runs happen on a build server rather than on the machine with the checkout.
The server needs JDK 21 and JDK 27, Gradle, QEMU 11.1, clang/lld with PowerPC targets, gdb, dtc
and, for firmware rebuilds, podman. Linux and FreeBSD hosts both work.

Which server is a local detail: the scripts take its ssh name from `JP_HOST`, read from the
environment or from `local.env` at the repository root (untracked), e.g.

```
JP_HOST=${JP_HOST:-build.example.org}
```

- `scripts/remote.sh <workspace> <command...>` rsyncs this checkout to `~/javapower/work/<workspace>`
  on the server and runs the command there. Use your own workspace name so parallel builds don't
  collide, e.g. `scripts/remote.sh agent-a gradle -q :cpu-int:test`.
- Scratch files on the server go under `~/javapower/work/<workspace>-scratch/`.

## What the scripts expect on the server

- `~/javapower/images/`: `freebsd-15.1-disc1.iso` (ppc64le), `freebsd-15.1-be-disc1.iso`
  (powerpc64), `opensuse-tw-minimal.raw` (4096-byte sectors).
- `~/javapower/ref/`: `run-qemu.sh` (reference boot command line), console logs of the guests
  booting in QEMU, and `pseries-min.dts` (the device tree QEMU hands to SLOF).
- `~/javapower/src/qemu` (v11.1.3) and `~/javapower/src/SLOF`: reference sources. QEMU is GPL: read
  it to learn behaviour, do not copy or transliterate its code.

## Guest test code

Cross-compile guest test code with `clang --target=powerpc64le-linux-gnu -mcpu=power9 -nostdlib
-static -fuse-ld=lld`; run user-mode binaries with `qemu-ppc64le -cpu power9`.

## Guest console checks

`scripts/guest/drive.py` drives our CLI or QEMU through a pty (boot to a prompt, installer shell,
kyua, test programs); `drive.py regress` boots all three guests. See its docstring and
[scripts/guest/README.md](../scripts/guest/README.md).

## Firmware

The CLI and tests use the bundled SLOF image, not `/usr/share/qemu/slof.bin` (still on the server
for running real QEMU). To rebuild it, run `firmware-slof/firmware-build/build.sh [--install]
[outdir]` on the server (rootless podman); it is reproducible bit for bit, and `--install`
replaces the committed `slof.bin.gz` and `slof.properties`. See
[Rebuilding the firmware](../README.md#rebuilding-the-firmware).
