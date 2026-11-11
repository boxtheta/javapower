# Guest console checks

`drive.py` boots a guest under our CLI (`--vm ours`, the default) or QEMU (`--vm qemu`, the
same machine as `~/javapower/ref/run-qemu.sh`) with its console on a raw-mode pty, waits for
patterns and types input (`console.py`, stdlib only). Everything runs on the dev server; build
the CLI first (`gradle :app:installDist`). Raw console logs and emulator stderr go to `--logdir`
(default `~/javapower/work/guest-logs`).

| Scenario | What it does |
|---|---|
| `prompt-le`, `prompt-be` | FreeBSD 15.1 ppc64le / powerpc64 ISO to `Console type [vt100]:` |
| `suse` | openSUSE Tumbleweed to the JeOS first-boot screen (use `--clock-scale 4` with ours) |
| `shell` | BE ISO to the installer's shell (vt100, then Shell), then each `--cmd` |
| `kyua` | BE ISO: shell, tmpfs+unionfs layers over `/usr`, `/etc`, `/libexec`, the tests, kyua and atf packages from the disc's offline repo, `kyua test` for each `--tests` path; writes `test_program:case result` lines to `--out` |
| `progs` | BE ISO plus a second disc (`--cdrom2`): installs the lib32 runtime and runs the disc's `RUN` script; output to `--out` |
| `regress` | `prompt-le`, `prompt-be`, `kyua` (short subset), `suse`; exit status 1 if any fails, or if `--baseline` (QEMU's kyua results) passed a test that ours did not |

Examples:

    scripts/remote.sh be 'python3 scripts/guest/drive.py regress --ram 4096 --baseline ~/javapower/ref/kyua-regress-qemu.txt'
    scripts/remote.sh be 'python3 scripts/guest/drive.py kyua --vm qemu --ram 4096 --tests lib/libc --out /tmp/libc-qemu.txt'
    scripts/remote.sh be 'python3 scripts/guest/drive.py shell --cmd "uname -a" --cmd "sysctl hw"'

Arguments after `--` go to the emulator or QEMU unchanged, e.g. `-- --jit off`.

`progs/` holds the 32- and 64-bit BE test programs (integer, FP, strings, signals and setjmp,
threads, processes, hashing). `progs/build.sh` builds them statically and dynamically against
sysroots taken from the install disc's `-dev` and `-lib32` packages and packs `progs.iso` with
xorriso. Compare the `progs` output of both VMs with `diff`.
