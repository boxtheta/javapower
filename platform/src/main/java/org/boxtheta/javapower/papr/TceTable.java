package org.boxtheta.javapower.papr;

import java.util.Arrays;

/**
 * A PAPR TCE (translation control entry) table: the IOMMU of one logical I/O bus number (LIOBN).
 * Each 4 KiB page of the DMA window maps to a real page with read/write permission bits
 * ({@code tce & 1}: the device may read memory, {@code tce & 2}: the device may write memory).
 * In bypass mode (ibm,set-tce-bypass, used by SLOF) DMA addresses are real addresses.
 *
 * <p>Thread-safe: entries are updated by hypercalls on any CPU while devices translate DMA
 * addresses on other threads; the table's own monitor guards them (a leaf lock).
 */
public final class TceTable {
    public static final int PAGE_SHIFT = 12;
    public static final long PAGE_SIZE = 1L << PAGE_SHIFT;
    public static final long PAGE_MASK = ~(PAGE_SIZE - 1);
    public static final long PERM_READ = 1;
    public static final long PERM_WRITE = 2;

    private final int liobn;
    private final long busOffset;
    private final long[] table;
    private volatile boolean bypass;

    private final PaprLog log;

    /** A table with a log of its own (tests); a machine passes its log. */
    public TceTable(int liobn, long busOffset, long windowSize) {
        this(liobn, busOffset, windowSize, new PaprLog());
    }

    TceTable(int liobn, long busOffset, long windowSize, PaprLog log) {
        this.log = log;
        this.liobn = liobn;
        this.busOffset = busOffset;
        this.table = new long[(int) (windowSize >>> PAGE_SHIFT)];
    }

    public int liobn() {
        return liobn;
    }

    public long busOffset() {
        return busOffset;
    }

    public long windowSize() {
        return (long) table.length << PAGE_SHIFT;
    }

    public int entries() {
        return table.length;
    }

    public boolean bypass() {
        return bypass;
    }

    public void setBypass(boolean b) {
        bypass = b;
    }

    /** Table reset (RTAS quiesce, QEMU's spapr_tce_reset): clear every entry; bypass is kept. */
    public synchronized void clear() {
        Arrays.fill(table, 0);
    }

    /** Device reset: clear every entry and leave bypass. */
    public synchronized void reset() {
        Arrays.fill(table, 0);
        bypass = false;
    }

    private long index(long ioba) {
        return (ioba - busOffset) >>> PAGE_SHIFT;
    }

    /** Store a TCE for the page containing {@code ioba}; H_PARAMETER if outside the window. */
    public synchronized long put(long ioba, long tce) {
        long idx = index(ioba & PAGE_MASK);
        if (ioba < busOffset || idx >= table.length) {
            log.once("tce-put-out-of-bounds", String.format("liobn 0x%x ioba 0x%x", liobn, ioba));
            return H.PARAMETER;
        }
        table[(int) idx] = tce;
        return H.SUCCESS;
    }

    /** The TCE for {@code ioba}, or null if outside the window. */
    public synchronized Long get(long ioba) {
        long idx = index(ioba & PAGE_MASK);
        if (ioba < busOffset || idx >= table.length) {
            return null;
        }
        return table[(int) idx];
    }

    /**
     * Translate a DMA address for an access of the given direction.
     *
     * @param deviceWrites true when the device writes guest memory
     * @return the real address, or -1 if unmapped or not permitted
     */
    public synchronized long translate(long ioba, boolean deviceWrites) {
        if (bypass) {
            return ioba;
        }
        long idx = index(ioba);
        if (ioba < busOffset || idx >= table.length) {
            return -1;
        }
        long tce = table[(int) idx];
        long need = deviceWrites ? PERM_WRITE : PERM_READ;
        if ((tce & need) == 0) {
            return -1;
        }
        return (tce & PAGE_MASK) | (ioba & ~PAGE_MASK);
    }
}
