package org.boxtheta.javapower.papr;

import java.util.List;

/**
 * Ids of the guest quirks: emulator behaviour that exists only to work around a known guest bug.
 * Each id has an entry of its own in {@code docs/guest-quirks.md}, which says how the guest is
 * recognised, what changes and when the quirk can go. A quirk reports itself with
 * {@link Machine#applyQuirk} when it takes effect, and {@link Machine#appliedQuirks} lists the ones
 * a machine has applied.
 */
final class GuestQuirks {
    /** FreeBSD ppc64le {@code llan} reads receive queue offsets and lengths in host order ({@link LLan}). */
    static final String FREEBSD_LLAN_LE_RXQ = "freebsd-llan-le-rxq";

    /** Every id, in the order of {@code docs/guest-quirks.md}. */
    static final List<String> ALL = List.of(FREEBSD_LLAN_LE_RXQ);

    private GuestQuirks() {
    }
}
