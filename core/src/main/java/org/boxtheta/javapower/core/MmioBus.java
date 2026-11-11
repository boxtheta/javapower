package org.boxtheta.javapower.core;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The memory-mapped I/O regions of one machine: the real addresses outside guest RAM that
 * reach a device. The CPUs consult it only for accesses that are not to RAM, where they would
 * otherwise raise a machine check, so RAM accesses never pay for it.
 *
 * <p>Regions may be mapped and unmapped at any time, from any thread, also while the CPUs run
 * (PCI BARs move when the guest programs them). The set of regions is an immutable sorted array
 * replaced on every change and published through a volatile field: a lookup sees either the old
 * or the new set, never a mix, and takes no lock. CPUs never cache a translation to a region, so
 * a change applies to every access that looks the region up after it; an access already past its
 * lookup may still complete on the old region after {@link #unmap} returns.
 *
 * <p>The bus only keeps regions apart from each other. Whoever maps a region keeps it out of
 * guest RAM (RAM takes precedence) and of address space the machine uses otherwise.
 */
public final class MmioBus {
    private static final MmioRegion[] NONE = {};

    /** Sorted by base, pairwise disjoint. */
    private volatile MmioRegion[] regions = NONE;

    /**
     * Map {@code r}.
     *
     * @throws IllegalArgumentException if it overlaps a region already mapped (or is mapped)
     */
    public synchronized void map(MmioRegion r) {
        Objects.requireNonNull(r, "region");
        MmioRegion[] cur = regions;
        int i = 0;
        while (i < cur.length && cur[i].base() < r.base()) {
            i++;
        }
        for (MmioRegion o : cur) {
            if (o.overlaps(r.base(), r.size())) {
                throw new IllegalArgumentException("MMIO region " + r + " overlaps " + o);
            }
        }
        MmioRegion[] next = new MmioRegion[cur.length + 1];
        System.arraycopy(cur, 0, next, 0, i);
        next[i] = r;
        System.arraycopy(cur, i, next, i + 1, cur.length - i);
        regions = next;
    }

    /** Unmap {@code r}; returns false if it was not mapped. */
    public synchronized boolean unmap(MmioRegion r) {
        MmioRegion[] cur = regions;
        for (int i = 0; i < cur.length; i++) {
            if (cur[i] == r) {
                MmioRegion[] next = new MmioRegion[cur.length - 1];
                System.arraycopy(cur, 0, next, 0, i);
                System.arraycopy(cur, i + 1, next, i, cur.length - i - 1);
                regions = cur.length == 1 ? NONE : next;
                return true;
            }
        }
        return false;
    }

    /** The mapped regions, by base address. */
    public List<MmioRegion> regions() {
        return List.of(regions);
    }

    /** The region holding real address {@code addr}, or null. */
    public MmioRegion find(long addr) {
        MmioRegion[] rs = regions;
        int lo = 0;
        int hi = rs.length - 1;
        // The last region whose base is at or below addr.
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (rs[mid].base() <= addr) {
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (hi < 0) {
            return null;
        }
        MmioRegion r = rs[hi];
        return addr - r.base() < r.size() ? r : null;
    }

    /**
     * The region a CPU or hypercall access of {@code size} (1, 2, 4 or 8) bytes at real address
     * {@code addr} goes to: one holding all of {@code [addr, addr + size)}, provided the access is
     * naturally aligned. Null otherwise; such an access is treated as one to unassigned memory.
     */
    public MmioRegion regionFor(long addr, int size) {
        if ((addr & (size - 1)) != 0 || addr < 0) {
            return null;
        }
        MmioRegion r = find(addr);
        return r != null && addr + size <= r.end() ? r : null;
    }

    /** Whether no region is mapped. */
    public boolean isEmpty() {
        return regions.length == 0;
    }

    @Override
    public String toString() {
        return "MmioBus" + Arrays.toString(regions);
    }
}
