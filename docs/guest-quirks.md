# Guest quirks

This file lists behaviour that exists only to work around a known bug in a guest. Real hardware
and PAPR do not call for it. Each entry says how the emulator recognises the guest, what it does
differently and what is left out. It also says how to tell when the workaround can go. Keep this
list complete: when you add, change or remove a quirk, update its entry here and its comment in
the code.

Each quirk has an id, the heading of its entry. The id is a constant in `GuestQuirks` (package
`org.boxtheta.javapower.papr`), which lists every id in the order of this file. A test checks that
this file and `GuestQuirks.ALL` name the same ids. When a quirk takes effect, the code calls
`Machine.applyQuirk(id, detail)`. That lets you see which quirks a run needed:

- `Machine.appliedQuirks()` and `Emulator.appliedQuirks()` return the ids applied since the
  machine was built. An id stays listed after the guest stops needing the quirk, and loading a
  state adds the quirks in effect in that state.
- The first time a quirk applies, the machine's log gets an INFO line:
  `guest quirk <id>: <detail>`.
- When the CLI exits, it prints `[javapower] guest quirk <id>` for each one.

A quirk must stay narrow. It applies only to a guest that is positively recognised, and every
other guest keeps PAPR's behaviour (and QEMU's, where QEMU follows PAPR). A quirk does not change
the saved-state format, or it says how it stays compatible. It has a unit test and, where
possible, a guest check in `scripts/guest/drive.py`.

## `freebsd-llan-le-rxq`: FreeBSD ppc64le `llan` reads receive queue entries in host order

| | |
|---|---|
| Guest | FreeBSD 15.1 ppc64le (`sys/powerpc/pseries/phyp_llan.c`); every ppc64le release with that driver |
| Device | `l-lan` virtual Ethernet ([`LLan`](../platform/src/main/java/org/boxtheta/javapower/papr/LLan.java)) |
| Since | 0.3.1 |
| Under QEMU | not worked around: the guest receives nothing |
| Upstream | not recorded |

**The bug.** PAPR defines each receive queue entry as a control byte, a 16-bit offset, a 32-bit
length and the 64-bit handle, all big-endian. The driver reads them through a plain C struct,
in host order. On a big-endian kernel that works. On a little-endian kernel the control byte is
still right and the handle is right too, because the device copies the handle bytes the driver
itself stored. The offset and length come out byte-swapped, so for example an offset of 8 reads
as 2048. Every frame is trimmed wrongly and dropped. Pings get no reply, and `netstat -i` counts
the input packets while nothing reaches the stack intact.

**Recognition.** The same driver builds the MAC address argument of H_REGISTER_LOGICAL_LAN with
`memcpy` from the bytes `00 00 m0 … m5` into a `uint64_t`. On a little-endian kernel the address
therefore arrives byte-reversed: the low 16 bits are zero and the high 16 bits are not. Linux's
`ibmveth` passes the address in the low 48 bits, and big-endian FreeBSD produces the same value,
so for both the high 16 bits are zero. SLOF passes 0. The test is `LLan.reversedMac`.

**What the device does.** It does this for a registration whose MAC argument is byte-reversed,
until H_FREE_LOGICAL_LAN or reset:

- It writes each receive queue entry's offset and length little-endian. The control byte and the
  handle are unchanged.
- It byte-reverses the addresses passed to H_MULTICAST_CTRL, which the driver builds the same way.
  The driver never turns filtering on, so this keeps the filter table right rather than changing
  what it receives.

Nothing else changes. The buffer list page, the descriptor toggles and the dropped-frame counter
stay big-endian; the driver does not read them.

**Not covered.** A ppc64le FreeBSD guest whose MAC address ends in `:00:00` passes a value whose
high and low 16 bits are both zero. It is treated as big-endian and still receives nothing. Give
such a guest another address.

**Saved state.** The flag rides in bit 63 of the saved MAC address, which is otherwise always
zero. The record layout is unchanged. A state saved by 0.3.0 loads with the flag clear, and the
guest gets working receive back after it registers the device again (`ifconfig llan0 down up`).
0.3.0, which has no quirk, masks the bit off when it loads a newer state.

**Applied** at H_REGISTER_LOGICAL_LAN with a byte-reversed MAC argument, and when a state is
loaded whose device has the flag set.

**Tests.** In `LLanTest`, `theMacArgumentsByteOrderTellsALittleEndianFreeBsdDriver`,
`aLittleEndianFreeBsdDriverGetsLittleEndianOffsetsAndLengths` and `theLittleEndianQueueIsSaved`.
The guest check is `drive.py net --guest le` (add `--nics 2` for a second device).

**Retiring it.** The quirk can go once FreeBSD reads the entry with `be16toh` and `be32toh` and
every supported release has that fix. Re-run `drive.py net --guest le --vm qemu`: when it passes
under QEMU, the driver is fixed.
