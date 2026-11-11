#!/usr/bin/env python3
"""Guest console scenarios for javapower and the QEMU reference (needs QEMU and the guest images).

    python3 scripts/guest/drive.py <scenario> [--vm ours|qemu] [options]

Scenarios:
  prompt-le      FreeBSD 15.1 ppc64le ISO to "Console type [vt100]:"
  prompt-be      FreeBSD 15.1 powerpc64 (big-endian) ISO to the same prompt
  suse           openSUSE Tumbleweed (raw 4K disk) to its first-boot screen
  shell          BE ISO to the installer's shell, then run each --cmd and print the output
                 (with --pkgs: writable layers and those packages first, as for kyua)
  kyua           BE ISO: shell, writable /usr, /etc, /libexec (tmpfs + unionfs), install the tests,
                 kyua and atf packages from the disc's offline repo, run kyua on --tests and
                 write per-test results (one "test_program:case result" line each) to --out
  progs          BE ISO with --cdrom2 attached: run every program listed in the second disc's
                 RUN file, write their output to --out
  resume         --guest le, be or suse: boot to a shell, write a file to a disk, save the machine
                 on exit (SIGTERM), resume it in a new process, check the file, write more, check
                 the clock, uptime and that every CPU still runs
  regress        prompt-le, prompt-be + a short kyua subset, suse; non-zero exit on failure
  compare        compare kyua results: --baseline (QEMU) against --out (ours), per directory

Logs (raw console, emulator stderr) go to --logdir. Exit status 0 on success.
"""
import argparse
import os
import re
import signal
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from console import Console, Timeout, Exited  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(HERE))
HOME = os.path.expanduser("~")
IMAGES = os.path.join(HOME, "javapower", "images")
ISO_LE = os.path.join(IMAGES, "freebsd-15.1-disc1.iso")
ISO_BE = os.path.join(IMAGES, "freebsd-15.1-be-disc1.iso")
SUSE = os.path.join(IMAGES, "opensuse-tw-minimal.raw")
APP = os.path.join(ROOT, "app", "build", "install", "app", "bin", "app")
PKG = "/usr/freebsd-packages/offline"

# A short kyua subset for the regression run (a few minutes in either VM).
REGRESS_TESTS = ["lib/libc/string", "lib/libc/stdlib", "bin/sh/builtins", "usr.bin/sed", "lib/msun"]


def vm_argv(a, cdroms=(), disk4k=None):
    """Command line for a guest with the given CD-ROM images (first is the boot disc)."""
    if a.vm == "ours":
        argv = [a.app, "--ram", str(a.ram), "--smp", str(a.smp)]
        for c in cdroms:
            argv += ["--cdrom", c]
        if disk4k:
            argv += ["--disk4k", disk4k]
        if a.clock_scale:
            argv += ["--clock-scale", str(a.clock_scale)]
        return argv + a.extra
    argv = ["qemu-system-ppc64", "-M", "pseries,ic-mode=xics,usb=off", "-cpu", "power9", "-smp", str(a.smp),
            "-m", "%dM" % a.ram, "-nodefaults", "-display", "none", "-monitor", "none",
            "-chardev", "stdio,id=c0,signal=off", "-device", "spapr-vty,chardev=c0",
            "-device", "spapr-vscsi,id=scsi0"]
    for k, c in enumerate(cdroms):
        argv += ["-drive", "file=%s,if=none,id=cd%d,format=raw,readonly=on" % (c, k),
                 "-device", "scsi-cd,drive=cd%d,bus=scsi0.0" % k]
    if disk4k:
        argv += ["-drive", "file=%s,if=none,id=d0,format=raw,snapshot=on" % disk4k,
                 "-device", "scsi-hd,drive=d0,bus=scsi0.0,logical_block_size=4096,physical_block_size=4096"]
    return argv + a.extra


def start(a, name, cdroms=(), disk4k=None):
    os.makedirs(a.logdir, exist_ok=True)
    tag = "%s-%s" % (a.name or name, a.vm)
    log = os.path.join(a.logdir, tag + ".console.log")
    err = os.path.join(a.logdir, tag + ".stderr.log")
    for f in (log, err):
        if os.path.exists(f):
            os.remove(f)
    argv = vm_argv(a, cdroms, disk4k)
    say(a, "start %s: %s" % (tag, " ".join(argv)))
    return Console(argv, log=log, stderr=err)


def say(a, msg):
    print("[%s %s] %s" % (time.strftime("%H:%M:%S"), a.vm, msg), flush=True)


def to_prompt(a, c):
    c.expect(r"Console type \[vt100\]:", timeout=a.boot_timeout)
    say(a, "console-type prompt after %.0fs" % c.elapsed())


def to_shell(a, c):
    """From the console-type prompt to the installer's shell (# prompt)."""
    to_prompt(a, c)
    c.send("\r")
    c.expect(r"S\s*hell\s*\]\s*\[\s*L\s*ive System", timeout=300)
    c.drain(1.0, 10)
    # bsddialog buttons: Install, Shell, Live System; "s" is Shell's hotkey. (An arrow key's
    # ESC would be read as Cancel when its bytes arrive apart, dropping to login:.)
    c.send("s")
    c.expect(r"type 'exit' to return to the installer", timeout=120)
    c.expect(r"# $", timeout=60)
    c.send("\r")
    c.expect(r"# $", timeout=60)
    c.sh("export PS1='# '; stty -echoctl cols 300; unset TERMCAP; kldload -n unionfs", timeout=60)
    say(a, "shell after %.0fs" % c.elapsed())


def run_cmds(a, c, cmds, timeout=600):
    out = []
    for cmd in cmds:
        st, o = c.sh(cmd, timeout=timeout)
        out.append((cmd, st, o))
        print("$ %s   [status %d]\n%s" % (cmd, st, o), flush=True)
    return out


def writable(a, c, pkgs):
    """Writable tmpfs layers (unionfs) over /usr, /etc and /libexec, then install `pkgs` from the
    disc's offline repo into them. (tmpfs is in GENERIC; unionfs was loaded by to_shell.) The
    installer's /tmp is a 20 MB tmpfs: a fresh one without a size limit is mounted over it first."""
    c.sh("cd / && mount -t tmpfs tmpfs /tmp", timeout=300)
    for d in ["usr", "etc", "libexec"]:
        st, o = c.sh("mkdir -p /tmp/u%s && mount -t tmpfs tmpfs /tmp/u%s && mount -t unionfs /tmp/u%s /%s"
                     % (d, d, d, d), timeout=300)
        if st:
            raise Exited("union mount of /%s failed: %s" % (d, o))
    t0 = time.time()
    st, o = c.sh("for p in %s; do tar -xf %s/FreeBSD-$p-15.1.pkg -C / --exclude '+*' 2>/dev/null "
                 "|| echo FAILED $p; done" % (" ".join(pkgs), PKG), timeout=3600)
    if "FAILED" in o:
        raise Exited("package install failed: " + o)
    say(a, "installed %s in %.0fs" % (" ".join(pkgs), time.time() - t0))
    c.sh("mkdir -p /tmp/home && export HOME=/tmp/home", timeout=60)


def kyua(a, c, tests, out):
    """Install the tests and run kyua on `tests`; write per-test results to `out`."""
    writable(a, c, ["tests", "kyua", "atf", "atf-lib"])
    results = {}
    for t in tests:
        t0 = time.time()
        # stdin from /dev/null: the shell helper's end-marker line is typed ahead and must not be
        # read by the tests. Per-test progress goes to the console (and the log).
        st, o = c.sh("cd /usr/tests && kyua -v parallelism=%d test %s </dev/null 2>&1" % (a.parallel, t),
                     timeout=a.test_timeout)
        summary = [l for l in o.splitlines() if re.search(r"\d+/\d+ passed", l)]
        say(a, "kyua test %s: %.0fs, %s" % (t, time.time() - t0, summary[-1] if summary else "no summary"))
        st, o = c.sh("kyua report --results-filter passed,skipped,xfail,broken,failed </dev/null | grep -- '->'",
                     timeout=600)
        n = 0
        for line in o.splitlines():
            m = re.match(r"^(\S+)\s+->\s+(\w+)", line.strip())
            if m:
                results[m.group(1)] = m.group(2)
                n += 1
        say(a, "  %d results" % n)
    with open(out, "w") as f:
        for k in sorted(results):
            f.write("%s %s\n" % (k, results[k]))
    counts = {}
    for v in results.values():
        counts[v] = counts.get(v, 0) + 1
    say(a, "kyua results: %s -> %s" % (counts, out))
    return results


def scenario(a):
    if a.scenario == "prompt-le":
        with start(a, "prompt-le", [ISO_LE]) as c:
            to_prompt(a, c)
    elif a.scenario == "prompt-be":
        with start(a, "prompt-be", [ISO_BE]) as c:
            to_prompt(a, c)
    elif a.scenario == "suse":
        with start(a, "suse", disk4k=SUSE) as c:
            # jeos-firstboot's welcome dialog on the console.
            c.expect(r"JeOS Firstboot", timeout=a.boot_timeout)
            say(a, "first-boot screen after %.0fs" % c.elapsed())
    elif a.scenario == "shell":
        with start(a, "shell", [ISO_BE] + a.cdrom2) as c:
            to_shell(a, c)
            if a.pkgs:
                writable(a, c, a.pkgs.split(","))
            run_cmds(a, c, a.cmd, a.test_timeout)
    elif a.scenario == "kyua":
        with start(a, "kyua", [ISO_BE]) as c:
            to_shell(a, c)
            kyua(a, c, a.tests or REGRESS_TESTS,
                 a.out or os.path.join(a.logdir, "%s-%s.txt" % (a.name or "kyua", a.vm)))
            c.sh("sync", timeout=60)
    elif a.scenario == "progs":
        with start(a, "progs", [ISO_BE] + a.cdrom2) as c:
            to_shell(a, c)
            progs(a, c)
    elif a.scenario == "resume":
        resume(a)
    elif a.scenario == "regress":
        return regress(a)
    elif a.scenario == "compare":
        table(a.baseline, a.out)
        return 1 if compare(a.baseline, a.out) else 0
    else:
        raise SystemExit("unknown scenario " + a.scenario)
    return 0


def progs(a, c):
    """Install the 32-bit runtime, mount the second disc and run its RUN script."""
    writable(a, c, ["clibs-lib32"])
    st, o = c.sh("mkdir -p /tmp/p && mount -t cd9660 /dev/cd1 /tmp/p && ls /tmp/p", timeout=120)
    say(a, "mounted: %s" % o.strip().replace("\n", " "))
    st, o = c.sh("cd /tmp/p && sh ./RUN </dev/null 2>&1", timeout=a.test_timeout)
    out = a.out or os.path.join(a.logdir, "%s-%s.txt" % (a.name or "progs", a.vm))
    with open(out, "w") as f:
        f.write(o)
    say(a, "progs: status %d, %d lines -> %s" % (st, o.count("\n"), out))


def suse_shell(a, c):
    """openSUSE from GRUB to a root shell on the console: the boot entry is edited to mask the
    first-boot wizard and the serial getty and to start systemd's debug shell on hvc0, so the
    whole system comes up and the shell needs no password."""
    c.expect(r"highlighted entry will be executed", timeout=a.boot_timeout)
    c.drain(1.0, 5)
    c.send("e")
    c.expect(r"Ctrl-x or F10 to boot", timeout=60)
    c.drain(1.0, 5)
    c.send("\x1b[B" * 8)       # down to the linux line
    c.send("\x05")              # end of line
    c.send(" systemd.mask=jeos-firstboot.service systemd.mask=serial-getty@hvc0.service"
           " systemd.debug_shell=hvc0", delay=0.02)
    c.send("\x18")              # Ctrl-x: boot
    c.expect(r"[#$] $", timeout=a.boot_timeout)
    c.drain(5.0, 120)
    c.send("\r")
    c.expect(r"# $", timeout=120)
    c.sh("export PS1='# '; stty -echoctl cols 300", timeout=120)
    say(a, "shell after %.0fs" % c.elapsed())


RESUME = {
    # guest: (directory on the disk, how to get it, checksum command, CPU-pinning prefix)
    "be": ("/tmp/m", "newfs -U /dev/da0 >/dev/null && mkdir -p /tmp/m && mount /dev/da0 /tmp/m", "sha256 -q",
           "cpuset -l %d"),
    "le": ("/tmp/m", "newfs -U /dev/da0 >/dev/null && mkdir -p /tmp/m && mount /dev/da0 /tmp/m", "sha256 -q",
           "cpuset -l %d"),
    "suse": ("/root/m", "mkdir -p /root/m", "sha256sum", "taskset -c %d"),
}


def guest_shell(a, c):
    if a.guest == "suse":
        suse_shell(a, c)
    else:
        to_shell(a, c)


def timings(path):
    t = open(path, errors="replace").read()
    return re.findall(r"\[javapower\] (saved|loaded) \S+ (?:\((\d+) bytes\) )?in (\d+) ms", t)


def resume(a):
    """Use a guest, save it on SIGTERM, resume it in a new process and keep using it."""
    os.makedirs(a.logdir, exist_ok=True)
    state = os.path.join(a.logdir, "resume-%s.state%s" % (a.guest, ".gz" if a.gzip else ""))
    if a.guest == "suse":
        disk = os.path.join(a.logdir, "resume-suse.raw")
        subprocess.check_call(["cp", "--sparse=always", SUSE, disk])
        boot = dict(disk4k=disk)
        cdroms = []
    else:
        disk = os.path.join(a.logdir, "resume-%s-scratch.raw" % a.guest)
        with open(disk, "wb") as f:
            f.truncate(256 << 20)
        boot = {}
        cdroms = [ISO_BE if a.guest == "be" else ISO_LE]
    d, setup, sha, pin = RESUME[a.guest]
    extra = list(a.extra) + ["--persistent"]
    if a.guest != "suse":
        extra += ["--disk", disk]
    if os.path.exists(state):
        os.remove(state)
    sums = "%s %s/blob | cut -c1-64" % (sha, d)

    b = argparse.Namespace(**vars(a))
    b.extra = extra + ["--save-state", state]
    b.name = "resume-%s-1" % a.guest
    with start(b, "resume", cdroms, **boot) as c:
        guest_shell(a, c)
        run_cmds(a, c, [setup, "dd if=/dev/random of=%s/blob bs=64k count=64 2>/dev/null && sync" % d,
                        sums + " > %s/blob.sum && sync && cat %s/blob.sum" % (d, d),
                        "date -u +%s; uptime", "getconf NPROCESSORS_ONLN"])
        t0 = time.time()
        c.close(grace=1800)
    say(a, "first run ended (save took %.0fs including exit): %s" % (time.time() - t0, timings(
        os.path.join(a.logdir, b.name + "-%s.stderr.log" % a.vm))))
    if not os.path.exists(state):
        raise Exited("no state saved")

    b.extra = extra + ["--load-state", state]
    b.name = "resume-%s-2" % a.guest
    with start(b, "resume", cdroms, **boot) as c:
        time.sleep(2)
        c.send("\r")
        c.expect(r"# $", timeout=600)
        say(a, "shell again after %.0fs" % c.elapsed())
        out = run_cmds(a, c, [
            sums + "; cat %s/blob.sum" % d,
            "dd if=/dev/random of=%s/blob2 bs=64k count=64 2>/dev/null && sync && ls -l %s" % (d, d),
            "date -u +%s; uptime; sleep 10; date -u +%s; uptime",
            "getconf NPROCESSORS_ONLN",
        ] + [(pin % k) + " sh -c 'i=0; while [ $i -lt 3000 ]; do i=$((i+1)); done; echo ran on cpu %d'" % k
             for k in range(a.smp)])
        got = out[0][2].split()
        if len(got) != 2 or got[0] != got[1]:
            raise Exited("the file written before the save reads back differently: %s" % got)
        for k in range(a.smp):
            if "ran on cpu %d" % k not in out[4 + k][2]:
                raise Exited("CPU %d did not run" % k)
        if a.guest != "suse":
            out = run_cmds(a, c, ["umount /tmp/m && fsck -t ufs -n /dev/da0"])
    say(a, "resumed run: %s" % timings(os.path.join(a.logdir, b.name + "-%s.stderr.log" % a.vm)))
    say(a, "state %s: %d bytes" % (state, os.path.getsize(state)))


def regress(a):
    failed = []
    for name in ["prompt-le", "prompt-be", "kyua", "suse"]:
        b = argparse.Namespace(**vars(a))
        b.scenario = name
        if name == "suse":
            b.clock_scale = a.clock_scale or (4 if a.vm == "ours" else None)
        if name == "kyua":
            b.tests = a.tests or REGRESS_TESTS
            b.out = os.path.join(a.logdir, "regress-kyua-%s.txt" % a.vm)
        t0 = time.time()
        try:
            scenario(b)
            say(a, "PASS %s (%.0fs)" % (name, time.time() - t0))
        except (Timeout, Exited) as e:
            say(a, "FAIL %s (%.0fs): %s" % (name, time.time() - t0, str(e)[-800:]))
            failed.append(name)
            continue
        if name == "kyua" and a.baseline:
            bad = compare(a.baseline, b.out)
            if bad:
                failed.append(name)
    say(a, "regress: %s" % ("FAILED " + " ".join(failed) if failed else "all passed"))
    return 1 if failed else 0


def load_results(path):
    """Results file ("test result" lines), or a raw console log holding `kyua report` output."""
    r = {}
    if path.endswith(".console.log"):
        from console import clean
        for m in re.finditer(r"^(\S+:\S+)\s+->\s+(\w+)", clean(open(path, "rb").read()), re.M):
            r[m.group(1)] = m.group(2)
        return r
    for line in open(path):
        p = line.split()
        if len(p) == 2:
            r[p[0]] = p[1]
    return r


def table(ref_path, got_path, depth=2):
    """Per-directory counts of each result in both files, and every test whose result differs."""
    ref, got = load_results(ref_path), load_results(got_path)
    kinds = ["passed", "failed", "broken", "skipped", "xfail"]
    rows = {}
    for which, res in (("ref", ref), ("got", got)):
        for k, v in res.items():
            d = "/".join(k.split(":")[0].split("/")[:depth])
            rows.setdefault(d, {}).setdefault(which, {}).setdefault(v, 0)
            rows[d][which][v] += 1
    print("%-24s %s" % ("directory", "  ".join("%-15s" % k for k in kinds)))
    for d in sorted(rows):
        cells = []
        for k in kinds:
            r, g = rows[d].get("ref", {}).get(k, 0), rows[d].get("got", {}).get(k, 0)
            cells.append("%-15s" % ("%d / %d" % (r, g)))
        print("%-24s %s" % (d, "  ".join(cells)))
    for k in sorted(set(ref) | set(got)):
        if ref.get(k) != got.get(k):
            print("  DIFF %s: reference %s, got %s" % (k, ref.get(k, "missing"), got.get(k, "missing")))


def compare(ref_path, got_path):
    """Tests that passed in the reference and did not pass here (printed); returns them."""
    ref, got = load_results(ref_path), load_results(got_path)
    bad = sorted(k for k, v in ref.items() if v == "passed" and got.get(k) != "passed")
    for k in bad:
        print("  REGRESSION %s: reference passed, got %s" % (k, got.get(k, "missing")))
    return bad


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("scenario")
    p.add_argument("--vm", choices=["ours", "qemu"], default="ours")
    p.add_argument("--app", default=APP)
    p.add_argument("--ram", type=int, default=2048, help="MiB")
    p.add_argument("--smp", type=int, default=1)
    p.add_argument("--clock-scale", type=float)
    p.add_argument("--cdrom2", action="append", default=[], help="extra CD-ROM image(s)")
    p.add_argument("--cmd", action="append", default=[], help="shell scenario: command to run")
    p.add_argument("--pkgs", help="shell scenario: comma-separated packages to install first "
                   "(e.g. tests,kyua,atf,atf-lib)")
    p.add_argument("--tests", action="append", default=[], help="kyua: test path under /usr/tests")
    p.add_argument("--parallel", type=int, default=1, help="kyua parallelism")
    p.add_argument("--out", help="results file")
    p.add_argument("--name", help="log file prefix (default: the scenario)")
    p.add_argument("--baseline", help="regress: kyua results from QEMU to compare against")
    p.add_argument("--guest", choices=["le", "be", "suse"], default="be", help="resume: which guest")
    p.add_argument("--gzip", action="store_true", help="resume: gzip the state file")
    p.add_argument("--logdir", default=os.path.join(HOME, "javapower", "work", "guest-logs"))
    p.add_argument("--boot-timeout", type=int, default=1800)
    p.add_argument("--test-timeout", type=int, default=6 * 3600)
    p.add_argument("extra", nargs="*", help="after --: extra emulator/QEMU arguments")
    a = p.parse_args()
    # SIGTERM/SIGHUP unwind normally, so the guest process group is closed with the console.
    for sig in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(sig, lambda n, f: sys.exit(128 + n))
    try:
        return scenario(a)
    except (Timeout, Exited) as e:
        say(a, "FAILED: %s" % e)
        return 1


if __name__ == "__main__":
    sys.exit(main())
