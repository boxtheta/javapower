#!/usr/bin/env python3
"""A small Ethernet segment for guest network checks (stdlib only).

    python3 scripts/guest/netpeer.py --link 7101:7100 [--link 7201:7200 ...]
                                     [--ip 10.0.2.99] [--mac 02:00:00:00:00:99]

Each --link LOCAL:REMOTE is one port of a hub: it binds 127.0.0.1:LOCAL and sends to
127.0.0.1:REMOTE, one Ethernet frame per UDP datagram (the format of QEMU's -netdev dgram and of
javapower's --net dgram). Frames from one port are repeated to all the others. On the segment
sits a host with --ip and --mac that answers ARP requests and ICMP echo requests (fragmented ones
too) and pings other hosts on command.

Commands, one per line on standard input; each prints one result line on standard output:
  ping IP COUNT SIZE [INTERVAL]   ping IP (ARP first); prints "ping IP sent N received M"
  stats                           prints "stats" and per-port frame and byte counts
  quit
"""
import argparse
import os
import select
import socket
import struct
import sys
import time

BROADCAST = b"\xff" * 6


def csum(b):
    if len(b) % 2:
        b += b"\0"
    s = sum(struct.unpack("!%dH" % (len(b) // 2), b))
    while s >> 16:
        s = (s & 0xffff) + (s >> 16)
    return ~s & 0xffff


def mac_bytes(s):
    return bytes(int(x, 16) for x in s.split(":"))


def ip_bytes(s):
    return socket.inet_aton(s)


class Peer:
    def __init__(self, links, ip, mac):
        self.ports = []
        for local, remote in links:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 << 20)
            s.bind(("127.0.0.1", local))
            self.ports.append({"sock": s, "remote": ("127.0.0.1", remote), "rx": 0, "rxb": 0, "tx": 0, "txb": 0})
        self.ip = ip_bytes(ip)
        self.mac = mac_bytes(mac)
        self.arp = {}            # ip bytes -> mac bytes
        self.frags = {}          # (src, id) -> {offset: payload}, total length
        self.ip_id = 1
        self.replies = set()     # (id, seq) of echo replies received
        self.ident = os.getpid() & 0xffff

    # ---- wire

    def send(self, frame, exclude=None):
        for i, p in enumerate(self.ports):
            if i == exclude:
                continue
            p["sock"].sendto(frame, p["remote"])
            p["tx"] += 1
            p["txb"] += len(frame)

    def poll(self, timeout):
        socks = [p["sock"] for p in self.ports]
        r, _, _ = select.select(socks, [], [], timeout)
        for s in r:
            i = socks.index(s)
            frame = s.recv(65536)
            p = self.ports[i]
            p["rx"] += 1
            p["rxb"] += len(frame)
            self.send(frame, exclude=i)      # the hub
            self.local(frame)

    def run_for(self, seconds):
        end = time.time() + seconds
        while time.time() < end:
            self.poll(max(0.0, end - time.time()))

    # ---- the host on the segment

    def eth(self, dst, ethertype, payload):
        return dst + self.mac + struct.pack("!H", ethertype) + payload

    def local(self, f):
        if len(f) < 14 or (f[0:6] != self.mac and f[0:6] != BROADCAST):
            return
        et = struct.unpack("!H", f[12:14])[0]
        if et == 0x0806:
            self.on_arp(f[14:])
        elif et == 0x0800:
            self.on_ip(f[14:])

    def on_arp(self, a):
        if len(a) < 28:
            return
        op = struct.unpack("!H", a[6:8])[0]
        sha, spa, tpa = a[8:14], a[14:18], a[24:28]
        self.arp[spa] = sha
        if op == 1 and tpa == self.ip:
            reply = struct.pack("!HHBBH", 1, 0x0800, 6, 4, 2) + self.mac + self.ip + sha + spa
            self.send(self.eth(sha, 0x0806, reply))

    def on_ip(self, p):
        if len(p) < 20 or p[0] >> 4 != 4:
            return
        ihl = (p[0] & 0xf) * 4
        total = struct.unpack("!H", p[2:4])[0]
        ident, frag = struct.unpack("!HH", p[4:8])
        proto, src, dst = p[9], p[12:16], p[16:20]
        if dst != self.ip:
            return
        data = p[ihl:total]
        mf, off = frag & 0x2000, (frag & 0x1fff) * 8
        if mf or off:
            key = (src, ident)
            parts = self.frags.setdefault(key, {"parts": {}, "len": None})
            parts["parts"][off] = data
            if not mf:
                parts["len"] = off + len(data)
            if parts["len"] is None:
                return
            have = sum(len(d) for d in parts["parts"].values())
            if have < parts["len"]:
                return
            del self.frags[key]
            data = b"".join(parts["parts"][o] for o in sorted(parts["parts"]))
        if proto == 1:
            self.on_icmp(src, data)

    def on_icmp(self, src, m):
        if len(m) < 8:
            return
        if m[0] == 8:
            reply = b"\0\0\0\0" + m[4:]
            reply = reply[:2] + struct.pack("!H", csum(reply)) + reply[4:]
            self.send_ip(src, 1, reply)
        elif m[0] == 0:
            ident, seq = struct.unpack("!HH", m[4:8])
            if ident == self.ident:
                self.replies.add(seq)

    def send_ip(self, dst, proto, data):
        mac = self.arp.get(dst)
        if mac is None:
            return
        ident = self.ip_id
        self.ip_id = (self.ip_id + 1) & 0xffff
        step = 1480
        for off in range(0, len(data), step):
            chunk = data[off:off + step]
            more = 0x2000 if off + step < len(data) else 0
            h = struct.pack("!BBHHHBBH4s4s", 0x45, 0, 20 + len(chunk), ident, more | (off // 8), 64, proto, 0,
                            self.ip, dst)
            h = h[:10] + struct.pack("!H", csum(h)) + h[12:]
            self.send(self.eth(mac, 0x0800, h + chunk))

    def resolve(self, ip, timeout=5.0):
        end = time.time() + timeout
        while ip not in self.arp and time.time() < end:
            req = struct.pack("!HHBBH", 1, 0x0800, 6, 4, 1) + self.mac + self.ip + b"\0" * 6 + ip
            self.send(self.eth(BROADCAST, 0x0806, req))
            self.run_for(0.5)
        return ip in self.arp

    def ping(self, ip, count, size, interval):
        dst = ip_bytes(ip)
        if not self.resolve(dst):
            return 0, 0
        self.replies = set()
        for seq in range(count):
            m = struct.pack("!BBHHH", 8, 0, 0, self.ident, seq) + bytes((seq + k) & 0xff for k in range(size))
            m = m[:2] + struct.pack("!H", csum(m)) + m[4:]
            self.send_ip(dst, 1, m)
            self.run_for(interval)
        end = time.time() + 5.0
        while len(self.replies) < count and time.time() < end:
            self.poll(0.1)
        return count, len(self.replies)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--link", action="append", required=True, help="LOCAL:REMOTE UDP ports on 127.0.0.1")
    ap.add_argument("--ip", default="10.0.2.99")
    ap.add_argument("--mac", default="02:00:00:00:00:99")
    a = ap.parse_args()
    links = [tuple(int(x) for x in l.split(":")) for l in a.link]
    peer = Peer(links, a.ip, a.mac)
    print("ready", flush=True)
    buf = b""
    stdin = sys.stdin.fileno()
    while True:
        socks = [p["sock"] for p in peer.ports]
        r, _, _ = select.select(socks + [stdin], [], [], 1.0)
        if stdin in r:
            chunk = os.read(stdin, 4096)
            if not chunk:
                return
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                cmd = line.decode().split()
                if not cmd:
                    continue
                if cmd[0] == "quit":
                    return
                if cmd[0] == "ping":
                    interval = float(cmd[4]) if len(cmd) > 4 else 0.05
                    sent, got = peer.ping(cmd[1], int(cmd[2]), int(cmd[3]), interval)
                    print("ping %s sent %d received %d" % (cmd[1], sent, got), flush=True)
                elif cmd[0] == "stats":
                    print("stats " + " ".join("port%d rx=%d/%dB tx=%d/%dB" % (i, p["rx"], p["rxb"], p["tx"], p["txb"])
                                              for i, p in enumerate(peer.ports)), flush=True)
        if any(s in r for s in socks):
            peer.poll(0)


if __name__ == "__main__":
    main()
