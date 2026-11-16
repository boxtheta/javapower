package org.boxtheta.javapower.core;

/**
 * Data memory access by effective address, as seen by the instruction being executed: translated
 * under the current MSR[DR]/PR and process, truncated to 32 bits when MSR[SF]=0.
 *
 * <p>Multi-byte values are returned/accepted in the byte order selected by MSR[LE]; i.e.
 * {@code read32} returns exactly what {@code lwz} loads. Byte-reversed instructions reverse the
 * result themselves. Accesses may cross page boundaries and need not be aligned.
 *
 * <p>A failed translation throws {@link PpcException} (DSI etc.) before any byte is written.
 */
public interface GuestMemory {
    int read8(long ea);

    /** Zero-extended. */
    int read16(long ea);

    /** Not extended: the caller chooses sign- or zero-extension. */
    int read32(long ea);

    long read64(long ea);

    void write8(long ea, int v);

    void write16(long ea, int v);

    void write32(long ea, int v);

    void write64(long ea, long v);

    /**
     * Translate {@code ea} for a data access and return the real address, raising the same
     * interrupts a load or store would. Used by larx/stcx, dcbz and friends.
     */
    long translateData(long ea, boolean store);

    /**
     * A fast path for instructions that access up to 16 bytes at once: the real address at which
     * the {@code len} bytes (1 to 16) at {@code ea} lie in the guest RAM of {@link #ram}, if the
     * access can be made there directly, else -1. It can when the bytes lie in one 4 KiB page of
     * RAM whose translation for this kind of access is already known (MSR[DR]=0, or a cached
     * translation): then the access cannot fault, and making it through {@link #ram}'s
     * accessors (which track code writes) has exactly the effect the accesses of this interface
     * would have. Otherwise (page crossing, a translation not cached, a fault, MMIO, or no fast
     * path at all) the caller makes its accesses through this interface as before. Never
     * raises an interrupt and changes no translation state; a store's address counts as
     * written for the purposes of the implementation's store bookkeeping, so call it with
     * {@code store} only right before making the store.
     */
    default long ramAddress(long ea, int len, boolean store) {
        return -1;
    }

    /** The guest RAM {@link #ramAddress} refers to; null if it always returns -1. */
    default PhysicalMemory ram() {
        return null;
    }
}
