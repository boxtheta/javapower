# Guest console checks

`drive.py` boots a guest under our CLI (`--vm ours`, the default) or QEMU (`--vm qemu`, the
same machine as `~/javapower/ref/run-qemu.sh`) with its console on a raw-mode pty, waits for
patterns and types input (`console.py`, stdlib only). Everything runs on the machine that has the guest images
(see `docs/development.md`); build the CLI first (`gradle :app:installDist`). Raw console logs and emulator stderr go to `--logdir`
(default `~/javapower/work/guest-logs`).

| Scenario | What it does |
|---|---|
| `prompt-le`, `prompt-be` | FreeBSD 15.1 ppc64le / powerpc64 ISO to `Console type [vt100]:` |
| `suse` | openSUSE Tumbleweed to the JeOS first-boot screen (use `--clock-scale 4` with ours) |
| `shell` | `--guest le`, `be` or `suse` (default `be`): boot to a shell (FreeBSD: the installer's, vt100 then Shell; openSUSE: as for `resume`), then each `--cmd` |
| `kyua` | BE ISO: shell, tmpfs+unionfs layers over `/usr`, `/etc`, `/libexec`, the tests, kyua and atf packages from the disc's offline repo, `kyua test` for each `--tests` path; writes `test_program:case result` lines to `--out` |
| `progs` | BE ISO plus a second disc (`--cdrom2`): installs the lib32 runtime and runs the disc's `RUN` script; output to `--out` |
| `resume` | `--guest le`, `be` or `suse` (default `be`): boot to a shell (openSUSE: GRUB entry edited to skip the first-boot wizard and open a root shell on the console), write a file to a disk, stop with SIGTERM so the CLI saves the machine (`--save-state`), resume it in a new process (`--load-state`), check the file, write another, show the clock and uptime, run a loop pinned to each CPU, run each `--cmd` before the save and after the load, and for FreeBSD check the file system with `fsck` |
| `net` | `--guest le`, `be` or `suse` with a network device on a small segment: `netpeer.py` (a hub with a host at 10.0.2.99 that answers ARP and ping) and QEMU's user-mode network (TCP to this machine at 10.0.2.2). Pings both ways, fragmented pings, a burst of `--net-burst` pings, a download and an upload of `--net-mib` MiB with checksums. `--nics 2` adds a second device on a second segment; `--peer-guest le\|be\|suse` boots that guest under QEMU with spapr-vlan on the first segment and pings between the guests. FreeBSD's ppc64le `llan` driver reads the receive queue's big-endian offsets and lengths in host order; ours recognises the driver and writes them little-endian for it, while under QEMU it receives nothing |
| `consoles` | `--guest le`, `be` or `suse`: two more consoles on TCP ports (`--console`; with `--vm qemu`, `-device spapr-vty` on socket chardevs), named `jp.one` and `jp.two` with ours; checks that the extra consoles stay silent through the boot, finds each console's device with the README's lookup functions (by name; with QEMU by node), writes a line each way, then starts a shell on one and a getty on the other and uses them from the host. With `--save` (ours): sends input to a console nobody reads, saves the machine on SIGTERM, resumes it in a new process, reads the input there and uses both consoles again |
| `media` | `--guest le`, `be` or `suse` (ours): an empty removable disk drive and an empty CD-ROM drive (`--removable empty --cdrom empty`), with media changed through the CLI's `--media` port while the guest runs. Loads an image, checks its size and checksum in the guest, writes to it, swaps it for a larger one and checks that the guest's writes are in the first image, loads an image write-protected and checks that a write fails, checks that the guest locks the drive while it has the device open, empties the drive, and has the guest eject a medium (`eject`, `camcontrol eject`), which the CLI then takes out. Each change must first be found by the guest's own media polling, without the device being opened; the log says how each was found. Then two discs in turn in the CD-ROM drive, each read and mounted |
| `regress` | `prompt-le`, `prompt-be`, `kyua` (short subset), `suse`; exit status 1 if any fails, or if `--baseline` (QEMU's kyua results) passed a test that ours did not |

Examples:

    scripts/remote.sh be 'python3 scripts/guest/drive.py net --guest be --nics 2 --peer-guest suse'

    scripts/remote.sh be 'python3 scripts/guest/drive.py regress --ram 4096 --baseline ~/javapower/ref/kyua-regress-qemu.txt'
    scripts/remote.sh be 'python3 scripts/guest/drive.py kyua --vm qemu --ram 4096 --tests lib/libc --out /tmp/libc-qemu.txt'
    scripts/remote.sh be 'python3 scripts/guest/drive.py shell --cmd "uname -a" --cmd "sysctl hw"'

Arguments after `--` go to the emulator or QEMU unchanged, e.g. `-- --jit off`.

`progs/` holds the 32- and 64-bit BE test programs (integer, FP, strings, signals and setjmp,
threads, processes, hashing). `progs/build.sh` builds them statically and dynamically against
sysroots taken from the install disc's `-dev` and `-lib32` packages and packs `progs.iso` with
xorriso. Compare the `progs` output of both VMs with `diff`.

`mmio/` is for testing MMIO regions with a guest: `mmio/build.sh` builds `mmiotest.c` for both
FreeBSD guests and packs it with a `RUN` script into `mmio.iso`. It maps the CLI's testing-only
`--test-mmio` device through `/dev/mem` and makes loads and stores of every size; the CLI prints
each access the device sees on stderr (in the `--logdir` stderr log):

    scripts/remote.sh be 'python3 scripts/guest/drive.py shell --cdrom2 <outdir>/mmio.iso
        --cmd "mount -t cd9660 /dev/cd1 /mnt && sh /mnt/RUN"
        --cmd "ofwdump -p /test-mmio@100000000000" -- --test-mmio 0x100000000000'
