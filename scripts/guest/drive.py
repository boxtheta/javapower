#!/usr/bin/env python3
"""Guest console scenarios for javapower and the QEMU reference (needs QEMU and the guest images).

    python3 scripts/guest/drive.py <scenario> [--vm ours|qemu] [options]

Scenarios:
  prompt-le      FreeBSD 15.1 ppc64le ISO to "Console type [vt100]:"
  prompt-be      FreeBSD 15.1 powerpc64 (big-endian) ISO to the same prompt
  suse           openSUSE Tumbleweed (raw 4K disk) to its first-boot screen
  shell          --guest le, be or suse (default be): boot to a shell (FreeBSD: the installer's;
                 openSUSE: as for resume), then run each --cmd and print the output
                 (with --pkgs: writable layers and those packages first, as for kyua)
  kyua           BE ISO: shell, writable /usr, /etc, /libexec (tmpfs + unionfs), install the tests,
                 kyua and atf packages from the disc's offline repo, run kyua on --tests and
                 write per-test results (one "test_program:case result" line each) to --out
  progs          BE ISO with --cdrom2 attached: run every program listed in the second disc's
                 RUN file, write their output to --out
  resume         --guest le, be or suse: boot to a shell, write a file to a disk, save the machine
                 on exit (SIGTERM), resume it in a new process, check the file, write more, check
                 the clock, uptime and that every CPU still runs; each --cmd runs before the save and
                 after the load
  net            --guest le, be or suse with a network device on a small segment (netpeer.py: a
                 host that answers ARP and ping, plus QEMU's user-mode network for TCP to this
                 machine): ping both ways (also fragmented, and a burst), download and upload
                 --net-mib MiB over TCP and compare checksums; --nics 2 adds a second device on
                 a second segment; --peer-guest le|be|suse also boots that guest under QEMU with
                 spapr-vlan on the same segment and pings between the two guests
  consoles       --guest le, be or suse: two more consoles on TCP sockets (ours: named jp.one and
                 jp.two); find their devices by name (QEMU: by node), write to and read from each,
                 run a shell on one and a getty on the other; with --save (ours), save with unread
                 input on one, resume in a new process, read it and use both consoles again
  media          --guest le, be or suse (ours): an empty removable disk drive and an empty CD-ROM
                 drive on the v-scsi bus; through the CLI's --media port, load a medium, write to
                 it, swap it for a larger one, load one write-protected, empty the drive, and let
                 the guest eject; the guest must see each change (size, contents, write
                 protection, no medium) and its writes must be in the image files. Then the same
                 swaps with discs in the CD-ROM drive
  regress        prompt-le, prompt-be + a short kyua subset, suse; non-zero exit on failure
  compare        compare kyua results: --baseline (QEMU) against --out (ours), per directory

Logs (raw console, emulator stderr) go to --logdir. Exit status 0 on success.
"""
import argparse
import functools
import hashlib
import http.server
import os
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
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
        if a.guest == "suse":
            ctx = start(a, "shell", a.cdrom2, disk4k=SUSE)
        else:
            ctx = start(a, "shell", [ISO_BE if a.guest == "be" else ISO_LE] + a.cdrom2)
        with ctx as c:
            guest_shell(a, c)
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
    elif a.scenario == "net":
        net(a)
    elif a.scenario == "consoles":
        consoles(a)
    elif a.scenario == "media":
        media(a)
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
                        "date -u +%s; uptime", "getconf NPROCESSORS_ONLN"] + a.cmd)
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
        if a.cmd:
            run_cmds(a, c, a.cmd)
        if a.guest != "suse":
            out = run_cmds(a, c, ["umount /tmp/m && fsck -t ufs -n /dev/da0"])
    say(a, "resumed run: %s" % timings(os.path.join(a.logdir, b.name + "-%s.stderr.log" % a.vm)))
    say(a, "state %s: %d bytes" % (state, os.path.getsize(state)))


# ---- network checks

def free_ports(n):
    """n free ports on 127.0.0.1 (asked of the kernel, then released)."""
    socks = []
    for _ in range(n):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.bind(("127.0.0.1", 0))
        socks.append(s)
    ports = [s.getsockname()[1] for s in socks]
    for s in socks:
        s.close()
    return ports


def dgram(local, remote):
    return ("dgram,local.type=inet,local.host=127.0.0.1,local.port=%d,remote.type=inet,remote.host=127.0.0.1,"
            "remote.port=%d" % (local, remote))


def net_args(vm, k, local, remote, mac):
    """Arguments for network device k with address `mac` on the UDP link local -> remote."""
    if vm == "ours":
        return ["--net", "dgram,local=127.0.0.1:%d,remote=127.0.0.1:%d,mac=%s" % (local, remote, mac)]
    return ["-netdev", "%s,id=n%d" % (dgram(local, remote), k), "-device", "spapr-vlan,netdev=n%d,mac=%s" % (k, mac)]


# The guest's devices, and the QEMU guest on its segment (each needs an address of its own there).
NET_MACS = ["02:00:00:00:00:15", "02:00:00:00:01:15"]
PEER_GUEST_MAC = "02:00:00:00:00:16"


class NetPeer:
    """netpeer.py as a child process: a hub with a host that answers ARP and ping."""

    def __init__(self, a, name, links, ip):
        self.log = open(os.path.join(a.logdir, name + ".netpeer.log"), "w")
        argv = [sys.executable, os.path.join(HERE, "netpeer.py"), "--ip", ip]
        for l in links:
            argv += ["--link", "%d:%d" % l]
        self.proc = subprocess.Popen(argv, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log, text=True)
        if "ready" not in self.proc.stdout.readline():
            raise Exited("netpeer did not start")

    def cmd(self, line):
        self.proc.stdin.write(line + "\n")
        self.proc.stdin.flush()
        return self.proc.stdout.readline().strip()

    def close(self):
        try:
            self.proc.stdin.close()
            self.proc.wait(10)
        except (OSError, subprocess.TimeoutExpired):
            self.proc.kill()
        self.log.close()


class HostServices:
    """This machine's side of the TCP checks: an HTTP server with a random blob and a sink that
    reads uploads to the end; QEMU's user-mode network makes both reachable at 10.0.2.2."""

    def __init__(self, a, mib):
        self.dir = tempfile.mkdtemp(dir=a.logdir)
        blob = os.urandom(mib << 20)
        with open(os.path.join(self.dir, "blob"), "wb") as f:
            f.write(blob)
        self.sha = hashlib.sha256(blob).hexdigest()

        class Quiet(http.server.SimpleHTTPRequestHandler):
            def log_message(self, *args):
                pass

        self.http = http.server.ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(Quiet, directory=self.dir))
        threading.Thread(target=self.http.serve_forever, daemon=True).start()
        self.sink = socket.socket()
        self.sink.bind(("127.0.0.1", 0))
        self.sink.listen(4)
        self.uploads = []
        threading.Thread(target=self._sink, daemon=True).start()

    def _sink(self):
        while True:
            try:
                conn, _ = self.sink.accept()
            except OSError:
                return
            h = hashlib.sha256()
            n = 0
            with conn:
                while True:
                    b = conn.recv(1 << 16)
                    if not b:
                        break
                    h.update(b)
                    n += len(b)
            self.uploads.append((n, h.hexdigest()))

    def ports(self):
        return self.http.server_address[1], self.sink.getsockname()[1]

    def close(self):
        self.http.shutdown()
        self.sink.close()
        shutil.rmtree(self.dir, ignore_errors=True)


NET_GUEST = {
    # guest: boot media, ping summary pattern (group 1: received), download, checksum, upload
    "be": dict(cdroms=[ISO_BE], received=r"(\d+) packets received",
               get="fetch -q -o /tmp/blob http://10.0.2.2:%d/blob", sha="sha256 -q /tmp/blob",
               put="nc -N 10.0.2.2 %d < /tmp/blob"),
    "le": dict(cdroms=[ISO_LE], received=r"(\d+) packets received",
               get="fetch -q -o /tmp/blob http://10.0.2.2:%d/blob", sha="sha256 -q /tmp/blob",
               put="nc -N 10.0.2.2 %d < /tmp/blob"),
    "suse": dict(disk4k=SUSE, received=r"(\d+) received",
                 get="curl -s -o /tmp/blob http://10.0.2.2:%d/blob", sha="sha256sum /tmp/blob | cut -c1-64",
                 put="cat /tmp/blob > /dev/tcp/10.0.2.2/%d"),
}


def net_ifaces(c, guest, macs):
    """The guest's interface names for the devices with these MAC addresses. (FreeBSD numbers
    its llan interfaces in reverse device tree order, under QEMU too, so go by address.)"""
    if guest == "suse":
        cmd = "for i in /sys/class/net/*; do echo ${i##*/} $(cat $i/address); done"
    else:
        cmd = "ifconfig -a | awk '/^[a-z]/ {i = $1} /ether/ {print i, $2}'"
    st, o = c.sh(cmd, timeout=120)
    by_mac = {}
    for line in o.splitlines():
        p = line.split()
        if len(p) == 2:
            by_mac[p[1].lower()] = p[0].rstrip(":")
    if any(m not in by_mac for m in macs):
        raise Exited("expected interfaces with %s, found %s" % (macs, by_mac))
    return [by_mac[m] for m in macs]


def net_up(c, guest, ifname, cidr):
    if guest == "suse":
        cmd = "ip link set %s up && ip addr replace %s dev %s && ip -br addr show %s" % (ifname, cidr, ifname, ifname)
    else:
        cmd = "ifconfig %s inet %s up && ifconfig %s" % (ifname, cidr, ifname)
    st, o = c.sh(cmd, timeout=120)
    print("$ %s   [status %d]\n%s" % (cmd, st, o), flush=True)
    if st:
        raise Exited("cannot configure %s" % ifname)


def guest_ping(a, c, g, args, want):
    """Ping from the guest; returns packets received, fails if fewer than `want`."""
    cmd = "ping %s" % args
    t0 = time.time()
    st, o = c.sh(cmd, timeout=900)
    m = re.findall(g["received"], o)
    got = int(m[-1]) if m else 0
    say(a, "guest: %s -> %d received (%.0fs)" % (cmd, got, time.time() - t0))
    if got < want:
        raise Exited("%s: %d received, wanted %d:\n%s" % (cmd, got, want, o[-1500:]))
    return got


def peer_ping(a, peer, ip, count, size, want):
    t0 = time.time()
    r = peer.cmd("ping %s %d %d" % (ip, count, size))
    say(a, "peer: %s (%.0fs)" % (r, time.time() - t0))
    m = re.search(r"received (\d+)", r)
    if not m or int(m.group(1)) < want:
        raise Exited("peer ping %s: %s" % (ip, r))


def net(a):
    """Bring the guest's network devices up and exercise them; see the module docstring."""
    os.makedirs(a.logdir, exist_ok=True)
    g = NET_GUEST[a.guest]
    tag = "net-%s" % a.guest
    # Segment 1: the guest, netpeer's host and hub, QEMU's user-mode network, maybe a QEMU guest.
    p = free_ports(12)
    guest_l, hub_g, hub_s, slirp_l, hub_q, peer_l, hub2_g, guest2_l = p[:8]
    links = [(hub_g, guest_l), (hub_s, slirp_l)]
    if a.peer_guest:
        links.append((hub_q, peer_l))
    services = HostServices(a, a.net_mib)
    http_port, sink_port = services.ports()
    peer = NetPeer(a, tag, links, "10.0.2.99")
    peer2 = NetPeer(a, tag + "-2", [(hub2_g, guest2_l)], "10.0.3.99") if a.nics > 1 else None
    slirp_log = open(os.path.join(a.logdir, tag + ".slirp.log"), "w")
    slirp = subprocess.Popen(["qemu-system-ppc64", "-M", "none", "-nodefaults", "-display", "none", "-monitor", "none",
                              "-netdev", "%s,id=d" % dgram(slirp_l, hub_s), "-netdev", "user,id=u,net=10.0.2.0/24",
                              "-netdev", "hubport,id=h0,hubid=0,netdev=d", "-netdev", "hubport,id=h1,hubid=0,netdev=u"],
                             stdout=slirp_log, stderr=slirp_log)
    b = argparse.Namespace(**vars(a))
    b.extra = list(a.extra) + net_args(a.vm, 0, guest_l, hub_g, NET_MACS[0])
    if a.nics > 1:
        b.extra += net_args(a.vm, 1, guest2_l, hub2_g, NET_MACS[1])
    if a.guest == "suse" and a.vm == "ours" and not a.clock_scale:
        b.clock_scale = 4
    try:
        with start(b, tag, g.get("cdroms", []), g.get("disk4k")) as c:
            guest_shell(a, c)
            try:
                net_checks(a, c, g, b, peer, peer2, services, http_port, sink_port, tag, peer_l, hub_q)
            except (Timeout, Exited):
                say(a, "segment: %s" % peer.cmd("stats"))
                try:
                    run_cmds(a, c, ["netstat -i; sysctl dev.llan 2>/dev/null" if a.guest != "suse" else "ip -s link"],
                             timeout=60)
                except (Timeout, Exited):
                    pass
                raise
    finally:
        for x in (peer, peer2):
            if x:
                x.close()
        slirp.terminate()
        slirp.wait()
        slirp_log.close()
        services.close()
    say(a, "net %s: passed" % a.guest)


def net_checks(a, c, g, b, peer, peer2, services, http_port, sink_port, tag, peer_l, hub_q):
    ifs = net_ifaces(c, a.guest, NET_MACS[:a.nics])
    if a.guest == "suse":
        c.sh("systemctl stop NetworkManager wicked 2>/dev/null; true", timeout=120)
    run_cmds(a, c, ["dmesg | grep -i -e llan -e ibmveth | head -20"])
    net_up(c, a.guest, ifs[0], "10.0.2.15/24")
    guest_ping(a, c, g, "-c 5 10.0.2.99", 5)
    peer_ping(a, peer, "10.0.2.15", 20, 56, 20)
    guest_ping(a, c, g, "-c 3 -s 4000 10.0.2.99", 3)
    peer_ping(a, peer, "10.0.2.15", 5, 3000, 5)
    got = guest_ping(a, c, g, "-q -c %d -i 0.01 -s 1400 10.0.2.99" % a.net_burst, a.net_burst * 95 // 100)
    if got < a.net_burst:
        say(a, "note: the burst lost %d of %d" % (a.net_burst - got, a.net_burst))
    t0 = time.time()
    st, o = c.sh("%s && %s" % (g["get"] % http_port, g["sha"]), timeout=3600)
    dt = time.time() - t0
    say(a, "guest: downloaded %d MiB in %.1fs (%.0f KiB/s): %s" % (a.net_mib, dt, (a.net_mib << 10) / dt,
                                                                  o.strip()[-64:]))
    if services.sha not in o:
        raise Exited("download checksum mismatch: %s, wanted %s" % (o.strip(), services.sha))
    t0 = time.time()
    st, o = c.sh(g["put"] % sink_port, timeout=3600)
    dt = time.time() - t0
    for _ in range(50):
        if services.uploads:
            break
        time.sleep(0.2)
    say(a, "guest: uploaded in %.1fs (%.0f KiB/s): %s" % (dt, (a.net_mib << 10) / dt, services.uploads))
    if not services.uploads or services.uploads[-1] != (a.net_mib << 20, services.sha):
        raise Exited("upload mismatch: %s, wanted %d bytes %s" % (services.uploads, a.net_mib << 20,
                                                                   services.sha))
    if peer2:
        net_up(c, a.guest, ifs[1], "10.0.3.15/24")
        guest_ping(a, c, g, "-c 5 10.0.3.99", 5)
        peer_ping(a, peer2, "10.0.3.15", 20, 56, 20)
        guest_ping(a, c, g, "-c 5 10.0.2.99", 5)  # the first device still works
    if a.peer_guest:
        net_with_qemu_guest(a, c, g, tag, peer_l, hub_q)
    run_cmds(a, c, ["netstat -i" if a.guest != "suse" else "ip -s link"])
    say(a, "segment: %s" % peer.cmd("stats"))


def net_with_qemu_guest(a, c, g, tag, local, remote):
    """Boot --peer-guest under QEMU with spapr-vlan on the same segment and ping both ways."""
    q = argparse.Namespace(**vars(a))
    q.vm, q.guest, q.clock_scale, q.smp, q.name = "qemu", a.peer_guest, None, 1, None
    qg = NET_GUEST[a.peer_guest]
    q.extra = net_args("qemu", 0, local, remote, PEER_GUEST_MAC)
    with start(q, tag + "-peer", qg.get("cdroms", []), qg.get("disk4k")) as qc:
        guest_shell(q, qc)
        ifs = net_ifaces(qc, a.peer_guest, [PEER_GUEST_MAC])
        if a.peer_guest == "suse":
            qc.sh("systemctl stop NetworkManager wicked 2>/dev/null; true", timeout=120)
        net_up(qc, a.peer_guest, ifs[0], "10.0.2.16/24")
        guest_ping(q, qc, qg, "-c 5 10.0.2.15", 5)
        guest_ping(a, c, g, "-c 5 10.0.2.16", 5)
        guest_ping(a, c, g, "-c 3 -s 4000 10.0.2.16", 3)


class Port:
    """The client end of a console on a TCP port: collects what the guest writes, sends input."""

    def __init__(self, port, name):
        self.name = name
        end = time.time() + 120
        while True:
            try:
                self.s = socket.create_connection(("127.0.0.1", port))
                break
            except OSError:
                if time.time() > end:
                    raise
                time.sleep(0.5)
        self.data = bytearray()
        self.pos = 0
        self.lock = threading.Lock()
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        while True:
            try:
                b = self.s.recv(4096)
            except OSError:
                return
            if not b:
                return
            with self.lock:
                self.data += b

    def send(self, b):
        self.s.sendall(b)

    def expect(self, rx, timeout=120):
        r = re.compile(rx)
        end = time.time() + timeout
        while True:
            with self.lock:
                m = r.search(bytes(self.data), self.pos)
                if m:
                    self.pos = m.end()
                    return m
            if time.time() > end:
                raise Timeout("console %s: no %r within %ds; got %r" % (self.name, rx, timeout, bytes(self.data[-300:])))
            time.sleep(0.2)

    def close(self):
        self.s.close()


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


# Shell functions printing the device of the console whose vty node has the label $1 (or is
# named $1, e.g. vty@71000003), as documented in the README.
#
# Linux: hvc0 is the vty of /chosen/linux,stdout-path; hvc_vio gives the others hvc1, hvc2, ... in
# the order it bound them, which is unit address order, the order of the bound devices' names.
HVC_BY_NAME = (
    r'''hvc_by_name() { b=$(tr -d '\0' </proc/device-tree/chosen/linux,stdout-path); b=${b##*/}; n=1; '''
    r'''for d in /sys/bus/vio/drivers/hvc_console/[0-9a-f]*; do o=$(readlink -f $d/of_node); '''
    r'''if [ ${o##*/} = $b ]; then k=0; else k=$n; n=$((n+1)); fi; '''
    r'''if [ ${o##*/} = "$1" ] || { [ -e $o/label ] && [ "$(tr -d '\0' <$o/label)" = "$1" ]; }; '''
    r'''then echo /dev/hvc$k; return 0; fi; done; return 1; }''')
# FreeBSD: uart_phyp attaches uart0, uart1, ... to the vty children of /vdevice in the order the
# Open Firmware tree lists them, which is the order ofwdump prints them in.
UART_AWK = "; ".join([
    r'''function check() { if (v && (lab == want || ("vty@" reg) == want)) { print "/dev/cuau" (k - 1); found = 1; exit } }''',
    r'''/^ *Node 0x[0-9a-f]*: / { check(); v = ($3 == "vty"); if (v) k++; lab = ""; reg = ""; prop = ""; next }''',
    r'''/^ *[^ ]+:$/ { prop = $1; next }''',
    r'''prop == "label:" && index($0, q) { lab = $0; gsub(q, "", lab); sub(/^ +/, "", lab) }''',
    r'''prop == "reg:" && reg == "" { reg = $1 $2 $3 $4 }''',
    r'''END { if (!found) check(); exit !found }''',
])
UART_BY_NAME = r'''uart_by_name() { ofwdump -p -r /vdevice | awk -v want="$1" -v q="'" '%s'; }''' % UART_AWK


def readme_function(name):
    """The shell function `name` as the README documents it, renamed doc_<name>."""
    text = open(os.path.join(ROOT, "README.md")).read()
    for block in re.findall(r"```sh\n(.*?)```", text, re.S):
        if name + "() {" in block:
            lines = [l[2:] if l.startswith("  ") else l for l in block.splitlines()]
            body = "\n".join(l for l in lines if l.strip() and not l.startswith(name + " "))
            return body.replace(name + "() {", "doc_" + name + "() {")
    raise Exited("no %s in README.md" % name)


def find_consoles(a, c, names, regs, linux):
    """The guest's device for each extra console: by name with ours, by node name with QEMU.
    With ours, the README's version of the lookup must give the same answer."""
    fn = "hvc_by_name" if linux else "uart_by_name"
    c.sh(HVC_BY_NAME if linux else UART_BY_NAME)
    if a.vm == "ours":
        for line in readme_function(fn).splitlines():
            c.send(line + "\r")
        st, o = c.sh("type doc_%s >/dev/null && echo defined" % fn)
        if "defined" not in o:
            raise Exited("the README's %s did not define: %r" % (fn, o))
    run_cmds(a, c, ["ls /dev/hvc*; ls /sys/bus/vio/drivers/hvc_console" if linux else
                    "ls /dev/ttyu* /dev/cuau*; sysctl kern.console; dmesg | grep uart"])
    dev = {}
    for n, reg in zip(names, regs):
        key = n if a.vm == "ours" else "vty@%x" % reg
        st, o = c.sh("%s %s" % ("hvc_by_name" if linux else "uart_by_name", key))
        if st or not o.strip().startswith("/dev/"):
            raise Exited("no device for console %s: %r" % (key, o))
        dev[n] = o.strip()
        if a.vm == "ours":
            st, o = c.sh("doc_%s %s" % (fn, key))
            if st or o.strip() != dev[n]:
                raise Exited("the README's %s says %r for %s, not %s" % (fn, o, key, dev[n]))
        say(a, "console %s (vty@%x) is %s" % (n, reg, dev[n]))
    return dev


def exchange(a, c, port, dev, n):
    """Write a line to console n and read one from it; check both ends."""
    c.sh("exec 3<>%s; printf 'to-host-%s\\n' >&3" % (dev, n))
    port.expect(re.escape(b"to-host-" + n.encode()) + rb"\r?\n")
    port.send(b"to-guest-" + n.encode() + b"\n")
    st, o = c.sh("read -r line <&3; echo \"got:$line\"; exec 3<&-", timeout=120)
    if "got:to-guest-" + n not in o:
        raise Exited("%s did not read its input: %r" % (dev, o))
    say(a, "console %s (%s): the host got to-host-%s, the guest read to-guest-%s" % (n, dev, n, n))


def interactive(a, c, port, dev, names, linux):
    """A shell on the first extra console and a getty on the second, used from the host side."""
    one, two = names
    if linux:
        c.sh("setsid -f sh -c 'exec bash -i <>%s >&0 2>&1'" % dev[one])
        c.sh("systemctl start serial-getty@%s.service" % dev[two][len("/dev/"):])
    else:
        c.sh("daemon sh -c 'exec sh -i <>%s >&0 2>&1'" % dev[one])
        c.sh("daemon /usr/libexec/getty 3wire %s" % dev[two][len("/dev/"):])
    p1, p2 = port
    time.sleep(2)
    p1.send(b"echo shell-on-$(uname -s)-$((6*7))\r")
    m = p1.expect(rb"shell-on-(\w+)-42", timeout=120)
    say(a, "shell on %s: %s" % (dev[one], m.group(0).decode()))
    p2.expect(rb"login: ", timeout=120)
    p2.send(b"root\r")
    # FreeBSD refuses root on a tty /etc/ttys does not call secure: login answers that, which
    # still shows the whole dialogue crossing the console.
    m = p2.expect(rb"(Password:|Login incorrect|# |\$ )", timeout=120)
    say(a, "getty on %s: login prompt, then %r after the user name" % (dev[two], m.group(1).decode()))


def consoles(a):
    """Two more consoles on TCP sockets: find them, use them, save and resume with input waiting."""
    linux = a.guest == "suse"
    names = ["jp.one", "jp.two"]
    regs = [0x71000003, 0x71000004]
    ports = [free_port() for _ in names]
    save = a.save and a.vm == "ours"
    extra = []
    for k, (n, p) in enumerate(zip(names, ports)):
        if a.vm == "ours":
            extra += ["--console", "tcp:127.0.0.1:%d,name=%s" % (p, n)]
        else:
            extra += ["-chardev", "socket,id=x%d,host=127.0.0.1,port=%d,server=on,wait=off" % (k, p),
                      "-device", "spapr-vty,chardev=x%d" % k]
    os.makedirs(a.logdir, exist_ok=True)
    state = os.path.join(a.logdir, "consoles-%s.state" % a.guest)
    if linux:
        disk = os.path.join(a.logdir, "consoles-suse.raw")
        subprocess.check_call(["cp", "--sparse=always", SUSE, disk])
        boot, cdroms = dict(disk4k=disk), []
        if a.vm == "ours":
            extra += ["--persistent"]
    else:
        boot, cdroms = {}, [ISO_BE if a.guest == "be" else ISO_LE]
    if os.path.exists(state):
        os.remove(state)
    b = argparse.Namespace(**vars(a))
    b.extra = list(a.extra) + extra + (["--save-state", state] if save else [])
    b.name = "consoles-%s-1" % a.guest
    with start(b, "consoles", cdroms, **boot) as c:
        port = [Port(p, n) for p, n in zip(ports, names)]
        guest_shell(a, c)
        for p in port:
            if p.data:
                raise Exited("console %s got output before the guest used it: %r" % (p.name, bytes(p.data)))
        st, o = c.sh("tr -d '\\0' </proc/device-tree/chosen/linux,stdout-path; echo" if linux
                     else "sysctl -n kern.console")
        say(a, "firmware and boot output came on the first console (%s); the others stayed silent" % o.strip())
        dev = find_consoles(a, c, names, regs, linux)
        for n, p in zip(names, port):
            exchange(a, c, p, dev[n], n)
        if not save:
            interactive(a, c, port, dev, names, linux)
            return
        # Input nobody reads yet: Linux reads an hvc only while it is open, so it waits in the
        # vty; FreeBSD's uart_phyp takes input at once, so it waits in the open tty's buffer.
        if not linux:
            c.sh("exec 4<>%s" % dev[names[1]])
        msg = b"in-flight:" + bytes(range(0x41, 0x5b)) + b"0123456789"
        port[1].send(msg + b"\n")
        time.sleep(3)
        for p in port:
            p.close()
        c.close(grace=1800)
    if not os.path.exists(state):
        raise Exited("no state saved")
    say(a, "saved with %d bytes of unread input on %s: %s" % (len(msg) + 1, names[1], timings(
        os.path.join(a.logdir, b.name + "-%s.stderr.log" % a.vm))))
    b.extra = list(a.extra) + extra + ["--load-state", state]
    b.name = "consoles-%s-2" % a.guest
    with start(b, "consoles", cdroms, **boot) as c:
        port = [Port(p, n) for p, n in zip(ports, names)]
        time.sleep(2)
        c.send("\r")
        c.expect(r"# $", timeout=600)
        say(a, "resumed: shell again after %.0fs" % c.elapsed())
        st, o = c.sh("read -r line %s; echo \"got:$line\"" % ("<" + dev[names[1]] if linux else "<&4"), timeout=120)
        if "got:" + msg.decode() not in o:
            raise Exited("the input saved on %s did not arrive: %r" % (names[1], o))
        say(a, "after the resume %s read the input saved with it: %s" % (dev[names[1]], o.strip()))
        for n, p in zip(names, port):
            exchange(a, c, p, dev[n], n)
        interactive(a, c, port, dev, names, linux)


def media_ctl(port, cmd):
    """Send one command to the CLI's --media port; return its answer lines (the last starts with ok)."""
    lines = []
    with socket.create_connection(("127.0.0.1", port), timeout=30) as s:
        s.sendall((cmd + "\n").encode())
        for line in s.makefile("r"):
            lines.append(line.rstrip("\n"))
            if line.startswith(("ok", "error")):
                break
    if not lines or not lines[-1].startswith("ok"):
        raise Exited("media control: %s -> %s" % (cmd, lines))
    return lines


def file_sha(path, nbytes=None):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        left = os.path.getsize(path) if nbytes is None else nbytes
        while left > 0:
            b = f.read(min(1 << 20, left))
            if not b:
                break
            h.update(b)
            left -= len(b)
    return h.hexdigest()


class Drive:
    """A drive with removable media as a guest sees it: its device, and commands for its size in
    512-byte sectors without opening it (`seen`: what the driver's polling found), with opening
    it (`size`), the checksum of its first n bytes, and ejecting the medium. `seen_gone` says
    whether `seen` also shows that a medium is gone."""

    def __init__(self, guest, scsi_id, cd):
        self.id = scsi_id
        self.cd = cd
        if guest == "suse":
            # The boot disk is sda; the removable disk is sdb and the CD-ROM sr0.
            name = "sr0" if cd else "sdb"
            self.dev = "/dev/" + name
            self.sys = "/sys/block/" + name
            self.seen = "cat %s/size" % self.sys
            self.seen_gone = True
            self.size = "blockdev --getsz %s 2>/dev/null || echo 0" % self.dev
            self.sha = "head -c %%d %s | sha256sum | cut -c1-64" % self.dev
            self.eject = "eject %s" % self.dev
        else:
            # The install disc is cd0; the removable disk is da0 and the second CD-ROM cd1.
            name = "cd1" if cd else "da0"
            self.dev = "/dev/" + name
            self.sys = None
            # GEOM's view, which the driver updates from its periodic TEST UNIT READY when a
            # medium arrives; it keeps the size of a medium that is gone.
            self.seen = ("geom disk list %s 2>/dev/null | awk '/Mediasize:/ { print $2 / 512; f = 1; exit } "
                         "END { if (!f) print 0 }'" % name)
            self.seen_gone = False
            # cd(4) can be opened without a medium and then has its last one's size: read a block.
            self.size = ("dd if=%s of=/dev/null bs=2048 count=1 2>/dev/null && diskinfo %s 2>/dev/null "
                         "| awk '{ print $3 / 512; f = 1 } END { if (!f) print 0 }' || echo 0" % (self.dev, name))
            self.sha = "dd if=%s bs=64k 2>/dev/null | head -c %%d | sha256 -q" % self.dev
            self.eject = "camcontrol eject %s" % name


def media_wait(a, c, d, sectors, what, polled=45, timeout=120):
    """Wait until the guest sees a medium of `sectors` 512-byte sectors in `d` (0: none): first
    for its driver's polling to find it, then, if it has not, by opening the device."""
    if sectors == 0 and not d.seen_gone:
        polled = 0
    t0 = time.time()
    while True:
        waited = time.time() - t0
        how = "the driver's polling" if waited < polled else "opening the device"
        st, o = c.sh(d.seen if waited < polled else d.size, timeout=120)
        try:
            if int(float(o.strip().splitlines()[-1])) == sectors:
                break
        except (ValueError, IndexError):
            pass
        if waited > timeout:
            raise Exited("%s: the guest does not see %d sectors in %s; last answer %r" % (what, sectors, d.dev, o))
        time.sleep(1)
    # The device node agrees.
    st, o = c.sh(d.size, timeout=120)
    if int(float(o.strip().splitlines()[-1])) != sectors:
        raise Exited("%s: %s has %s sectors when opened, not %d" % (what, d.dev, o.strip(), sectors))
    say(a, "%s: the guest sees %s in %s after %.0fs (%s)" % (
        what, "%d sectors" % sectors if sectors else "no medium", d.dev, time.time() - t0, how))


def media_sha(a, c, d, nbytes, want, what):
    st, o = c.sh(d.sha % nbytes, timeout=1200)
    got = o.strip().splitlines()[-1] if o.strip() else ""
    if got != want:
        raise Exited("%s: the guest reads %s from %s, the image has %s" % (what, got, d.dev, want))
    say(a, "%s: the guest reads the image's %d bytes (sha256 %s...)" % (what, nbytes, want[:16]))


def media_status(port, d):
    for line in media_ctl(port, "status"):
        if line.split()[:1] == [str(d.id)]:
            return line
    raise Exited("media control: no status for drive %d" % d.id)


def media(a):
    """Change the media of a removable disk drive and a CD-ROM drive under a running guest."""
    if a.vm != "ours":
        raise SystemExit("media: --vm ours only")
    linux = a.guest == "suse"
    os.makedirs(a.logdir, exist_ok=True)
    port = free_port()
    img = {}
    for name, kib in (("a", 1440), ("b", 2880)):
        img[name] = os.path.join(a.logdir, "media-%s-%s.img" % (a.guest, name))
        with open(img[name], "wb") as f:
            f.write(os.urandom(kib << 10))
    size = {k: os.path.getsize(v) for k, v in img.items()}
    if linux:
        disk = os.path.join(a.logdir, "media-suse.raw")
        subprocess.check_call(["cp", "--sparse=always", SUSE, disk])
        boot, cdroms = dict(disk4k=disk), []
    else:
        boot, cdroms = {}, [ISO_BE if a.guest == "be" else ISO_LE]
    b = argparse.Namespace(**vars(a))
    # SCSI ids follow the order of the options: the boot device is 0.
    b.extra = list(a.extra) + ["--removable", "empty", "--cdrom", "empty", "--media", "tcp:127.0.0.1:%d" % port]
    b.name = "media-%s" % a.guest
    fd = Drive(a.guest, 1, False)
    cd = Drive(a.guest, 2, True)
    errlog = os.path.join(a.logdir, "%s-%s.stderr.log" % (b.name, a.vm))
    with start(b, "media", cdroms, **boot) as c:
        guest_shell(a, c)
        if linux:
            st, o = c.sh("cat %s/removable %s/events_poll_msecs /sys/module/block/parameters/events_dfl_poll_msecs"
                         % (fd.sys, fd.sys))
            removable, poll, dfl = o.split()
            say(a, "%s: removable=%s events_poll_msecs=%s (module default %s)" % (fd.dev, removable, poll, dfl))
        else:
            st, o = c.sh("camcontrol devlist; camcontrol inquiry da0 | head -3; sysctl kern.cam.da.poll_period")
            removable = "1" if re.search(r"Removable Direct Access", o) else "0"
            say(a, "devices: %s" % " | ".join(l.strip() for l in o.strip().splitlines()))
        if removable != "1":
            raise Exited("%s is not a removable disk to the guest" % fd.dev)

        # An empty drive.
        media_wait(a, c, fd, 0, "empty drive", polled=0)
        st, o = c.sh("dd if=%s of=/dev/null bs=512 count=1" % fd.dev)
        if st == 0:
            raise Exited("reading the empty drive succeeded: %s" % o)
        say(a, "empty drive: a read fails: %s" % o.strip().splitlines()[-1])

        # A medium goes in; the guest writes to it.
        media_ctl(port, "load %d %s" % (fd.id, img["a"]))
        media_wait(a, c, fd, size["a"] // 512, "medium A loaded")
        media_sha(a, c, fd, size["a"], file_sha(img["a"]), "medium A")
        st, o = c.sh("dd if=/dev/urandom of=%s bs=512 seek=100 count=128 %s 2>&1 && sync"
                     % (fd.dev, "conv=fsync" if linux else ""))
        if st:
            raise Exited("writing to medium A failed: %s" % o)
        st, o = c.sh(fd.sha % size["a"], timeout=1200)
        written = o.strip().splitlines()[-1]
        if not re.fullmatch(r"[0-9a-f]{64}", written):
            raise Exited("no checksum from the guest: %r" % o)

        # Swapped for a larger one without emptying the drive first.
        media_ctl(port, "load %d %s" % (fd.id, img["b"]))
        if file_sha(img["a"]) != written:
            raise Exited("the guest's writes are not in image A after it left the drive")
        say(a, "medium A taken out: its image has the guest's writes")
        media_wait(a, c, fd, size["b"] // 512, "medium B swapped in")
        media_sha(a, c, fd, size["b"], file_sha(img["b"]), "medium B")

        # A write-protected medium.
        media_ctl(port, "load %d %s ro" % (fd.id, img["a"]))
        media_wait(a, c, fd, size["a"] // 512, "medium A loaded write-protected")
        media_sha(a, c, fd, size["a"], written, "medium A again")
        st, o = c.sh("dd if=/dev/zero of=%s bs=512 count=1 %s 2>&1" % (fd.dev, "conv=fsync" if linux else ""))
        if st == 0:
            raise Exited("a write to the write-protected medium succeeded: %s" % o)
        say(a, "write-protected: a write fails: %s" % o.strip().splitlines()[-1])
        if file_sha(img["a"]) != written:
            raise Exited("the write-protected image changed")

        # While the guest has the device open it prevents removal; the embedder can see that.
        c.sh("sleep 8 <%s &" % fd.dev)
        time.sleep(3)
        locked = media_status(port, fd)
        time.sleep(8)
        c.sh("wait")
        unlocked = media_status(port, fd)
        say(a, "status with the device open: %r, closed: %r" % (locked, unlocked))
        if "locked" not in locked or "locked" in unlocked:
            raise Exited("the guest's removal lock did not follow its open and close")

        # Emptied.
        media_ctl(port, "eject %d" % fd.id)
        media_wait(a, c, fd, 0, "drive emptied")

        # The guest ejects: the CLI takes the medium out.
        media_ctl(port, "load %d %s" % (fd.id, img["b"]))
        media_wait(a, c, fd, size["b"] // 512, "medium B loaded")
        st, o = c.sh(fd.eject + " 2>&1", timeout=120)
        if st:
            raise Exited("%s failed: %s" % (fd.eject, o))
        time.sleep(1)
        status = media_status(port, fd)
        ejected = "guest ejected the medium of SCSI device %d" % fd.id in open(errlog, errors="replace").read()
        say(a, "after the guest's %r: status %r, eject reported: %s" % (fd.eject, status, ejected))
        if "empty" not in status or not ejected:
            raise Exited("the guest's eject did not reach the drive")
        media_wait(a, c, fd, 0, "ejected by the guest")

        # The CD-ROM drive: two discs of different sizes, one after the other.
        discs = [ISO_LE, ISO_BE]
        media_wait(a, c, cd, 0, "empty CD-ROM drive", polled=0)
        for k, iso in enumerate(discs):
            nbytes = os.path.getsize(iso)
            media_ctl(port, "load %d %s ro" % (cd.id, iso))
            media_wait(a, c, cd, nbytes // 512, "disc %d loaded" % (k + 1))
            media_sha(a, c, cd, 8 << 20, file_sha(iso, 8 << 20), "disc %d" % (k + 1))
            mnt = "mount -o ro %s /mnt" if linux else "mount -t cd9660 %s /mnt"
            st, o = c.sh((mnt + " && ls /mnt | head -5 && umount /mnt") % cd.dev, timeout=300)
            if st:
                raise Exited("mounting disc %d failed: %s" % (k + 1, o))
            say(a, "disc %d mounts: %s" % (k + 1, " ".join(o.split())))
        media_ctl(port, "eject %d" % cd.id)
        media_wait(a, c, cd, 0, "CD-ROM drive emptied")
        say(a, "media: all changes seen by the guest")


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
    p.add_argument("--cmd", action="append", default=[], help="shell: command to run; resume: also run before the"
                   " save and after the load")
    p.add_argument("--pkgs", help="shell scenario: comma-separated packages to install first "
                   "(e.g. tests,kyua,atf,atf-lib)")
    p.add_argument("--tests", action="append", default=[], help="kyua: test path under /usr/tests")
    p.add_argument("--parallel", type=int, default=1, help="kyua parallelism")
    p.add_argument("--out", help="results file")
    p.add_argument("--name", help="log file prefix (default: the scenario)")
    p.add_argument("--baseline", help="regress: kyua results from QEMU to compare against")
    p.add_argument("--guest", choices=["le", "be", "suse"], default="be", help="shell, resume, net, consoles, media: which guest")
    p.add_argument("--nics", type=int, default=1, choices=[1, 2], help="net: network devices")
    p.add_argument("--net-mib", type=int, default=4, help="net: MiB to download and upload")
    p.add_argument("--net-burst", type=int, default=500, help="net: pings in the burst")
    p.add_argument("--peer-guest", choices=["le", "be", "suse"], help="net: a QEMU guest on the same segment")
    p.add_argument("--gzip", action="store_true", help="resume: gzip the state file")
    p.add_argument("--save", action="store_true", help="consoles (ours): also save and resume with input waiting")
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
