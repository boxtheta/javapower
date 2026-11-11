# Development

## Building and testing

You need JDK 21 and JDK 27 (Gradle finds both among the installed JDKs) and Gradle. `gradle check`
builds everything and runs the tests on both JDKs; see
[Building and testing](../README.md#building-and-testing) for the individual tasks. The build
needs nothing else: the firmware image and the QEMU-derived test vectors are committed.

## Regenerating test vectors and comparing with QEMU

This needs QEMU 11.1 (system and user-mode ppc64 emulators), clang and lld with PowerPC targets,
and `dtc`.

- The differential test generators live in `cpu-int/difftest` and `cpu-fpvec/difftest`; each has a
  `run.sh` that regenerates the golden vectors under QEMU.
- Cross-compile guest test code with `clang --target=powerpc64le-linux-gnu -mcpu=power9 -nostdlib
  -static -fuse-ld=lld`; run user-mode binaries with `qemu-ppc64le -cpu power9`.
- QEMU is GPL: read its sources to learn behaviour, do not copy or transliterate its code.

## Guest console checks

`scripts/guest/drive.py` drives our CLI or QEMU through a pty (boot to a prompt, installer shell,
kyua, test programs); `drive.py regress` boots all three guests. See its docstring and
[scripts/guest/README.md](../scripts/guest/README.md). It looks for the guest images in
`~/javapower/images/`:

- `freebsd-15.1-disc1.iso`: FreeBSD 15.1 ppc64le install disc
- `freebsd-15.1-be-disc1.iso`: FreeBSD 15.1 powerpc64 install disc
- `opensuse-tw-minimal.raw`: openSUSE Tumbleweed Minimal VM image with 4096-byte sectors

## Firmware

The CLI and tests use the bundled SLOF image. To rebuild it, run
`firmware-slof/firmware-build/build.sh [--install] [outdir]` on Linux with podman (rootless is
fine); it is reproducible bit for bit, and `--install` replaces the committed `slof.bin.gz` and
`slof.properties`. See [Rebuilding the firmware](../README.md#rebuilding-the-firmware).

## Building on another machine

`scripts/remote.sh <workspace> <command...>` rsyncs the checkout to `~/javapower/work/<workspace>`
on the machine named by `JP_HOST` and runs the command there. `JP_HOST` comes from the environment
or from an untracked `local.env` at the repository root. It is optional; everything above works
locally.
