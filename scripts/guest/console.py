"""Drive a guest's serial console through a pty: wait for patterns, send input, run shell commands.

Stdlib only. Used by drive.py; usable on its own:

    with Console(["app/build/install/app/bin/app", "--cdrom", iso], log="run.log") as c:
        c.expect(r"Console type \\[vt100\\]:", timeout=900)
        c.send("\\r")

The child gets a raw-mode pty as stdin/stdout (our CLI does not change terminal modes, and QEMU's
stdio chardev expects one); its stderr goes to `stderr` (a path) or is discarded. Everything the
guest prints is appended to `log` verbatim. Matching is done on the output with ANSI escape
sequences and carriage returns removed, so dialog(1) screens can be matched as plain text.
"""
import os
import pty
import re
import select
import signal
import subprocess
import time
import tty

ANSI = re.compile(rb"\x1b(\[[0-9;?]*[ -/]*[@-~]|[()][0-9A-Za-z]|[=>78DEHMc])|\x0f|\x0e")


class Timeout(Exception):
    pass


class Exited(Exception):
    pass


def clean(b):
    """Console bytes to matchable text: no escape sequences, CRs or NUL padding; latin-1."""
    return ANSI.sub(b"", b).replace(b"\r", b"").replace(b"\0", b"").decode("latin-1")


class Console:
    def __init__(self, argv, log=None, stderr=None, cwd=None, env=None):
        master, slave = pty.openpty()
        tty.setraw(slave)
        self.fd = master
        self.err = open(stderr, "wb") if stderr else subprocess.DEVNULL
        self.proc = subprocess.Popen(argv, stdin=slave, stdout=slave, stderr=self.err, cwd=cwd, env=env,
                                     start_new_session=True, close_fds=True)
        os.close(slave)
        self.log = open(log, "ab") if log else None
        self.raw = bytearray()   # everything received
        self.text = ""           # clean(raw), maintained incrementally
        self.pos = 0             # matching starts here (end of the last match)
        self.t0 = time.time()
        self._pending = b""      # tail that may hold an incomplete escape sequence

    # ---- output

    def _read(self, timeout):
        r, _, _ = select.select([self.fd], [], [], max(0.0, timeout))
        if not r:
            return False
        try:
            b = os.read(self.fd, 65536)
        except OSError:
            b = b""
        if not b:
            raise Exited("guest process exited (status %s)" % self.proc.poll())
        self.raw += b
        if self.log:
            self.log.write(b)
            self.log.flush()
        data = self._pending + b
        cut = data.rfind(b"\x1b")
        if cut >= 0 and len(data) - cut < 16:
            self._pending = data[cut:]
            data = data[:cut]
        else:
            self._pending = b""
        self.text += clean(data)
        return True

    def expect(self, patterns, timeout=60):
        """Wait until one of `patterns` (regex strings) matches the output after the last match.
        Returns (index, match). Raises Timeout or Exited."""
        if isinstance(patterns, str):
            patterns = [patterns]
        rx = [re.compile(p, re.M) for p in patterns]
        end = time.time() + timeout
        while True:
            best = None
            for i, r in enumerate(rx):
                m = r.search(self.text, self.pos)
                if m and (best is None or m.start() < best[1].start()):
                    best = (i, m)
            if best:
                self.pos = best[1].end()
                return best
            now = time.time()
            if now >= end:
                raise Timeout("no match for %r within %ds; last output:\n%s"
                              % (patterns, timeout, self.text[-1500:]))
            try:
                self._read(min(1.0, end - now))
            except Exited:
                # One last look at what arrived before the exit.
                for i, r in enumerate(rx):
                    m = r.search(self.text, self.pos)
                    if m:
                        self.pos = m.end()
                        return i, m
                raise

    def drain(self, quiet=0.5, limit=5):
        """Read until the guest has been silent for `quiet` seconds (at most `limit`)."""
        end = time.time() + limit
        while time.time() < end and self._read(quiet):
            pass
        self.pos = len(self.text)

    # ---- input

    def send(self, s, delay=0.0):
        """Send text (str or bytes). With `delay`, one byte at a time (for slow consoles)."""
        b = s.encode("latin-1") if isinstance(s, str) else s
        if delay:
            for k in range(len(b)):
                os.write(self.fd, b[k:k + 1])
                time.sleep(delay)
        else:
            while b:
                n = os.write(self.fd, b[:256])
                b = b[n:]
                time.sleep(0.01)

    # ---- shell helpers

    MARK = "@@JP"

    def sh(self, cmd, timeout=600):
        """Run `cmd` (one line, must not read stdin) in the guest's shell; return (status, output).
        The end marker is a separate input line, so it still runs after a syntax error in `cmd`,
        and it is assembled by the shell (quotes split it), so its echo never matches."""
        self.drain(0.2, 2)
        m = self.MARK
        start = len(self.text)
        self.send("%s\r" % cmd)
        self.send("echo %s'E'ND:$?:\r" % m)
        _, mt = self.expect(re.escape(m) + r"END:(\d+):", timeout)
        out = self.text[start:mt.start()]
        # Drop the echoed command line (up to the first newline) and the marker line's echo,
        # which the tty may print in the middle of the output (typed ahead).
        nl = out.find("\n")
        out = out[nl + 1:] if nl >= 0 else ""
        echo = "echo %s'E'ND:$?:" % m
        out = "".join(l for l in out.splitlines(True) if l.strip() not in (echo, "# " + echo))
        return int(mt.group(1)), out

    def elapsed(self):
        return time.time() - self.t0

    # ---- lifetime

    def close(self, grace=10):
        if self.proc.poll() is None:
            try:
                os.killpg(self.proc.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                self.proc.wait(grace)
            except subprocess.TimeoutExpired:
                os.killpg(self.proc.pid, signal.SIGKILL)
                self.proc.wait()
        try:
            os.close(self.fd)
        except OSError:
            pass
        if self.log:
            self.log.close()
        if self.err not in (None, subprocess.DEVNULL):
            self.err.close()

    def __enter__(self):
        return self

    def __exit__(self, *a):
        self.close()
