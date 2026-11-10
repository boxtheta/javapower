package javapower.core;

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
}
