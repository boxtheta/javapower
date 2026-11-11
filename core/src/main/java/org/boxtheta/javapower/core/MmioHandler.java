package org.boxtheta.javapower.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

/**
 * The device behind an {@link MmioRegion}: guest loads and stores in the region's real address
 * range become calls to {@link #read} and {@link #write}.
 *
 * <h2>Accesses</h2>
 * Every call is one naturally aligned access of 1, 2, 4 or 8 bytes ({@code size}) at
 * {@code offset} from the region's base, lying entirely inside the region. Values are the bytes
 * at {@code [offset, offset + size)} read as a number in the region's
 * {@linkplain MmioRegion#order() byte order}, in the low {@code size * 8} bits: the byte order
 * the guest runs in (MSR[LE]) and the byte-reversed instructions never change what the handler
 * sees, the machine swaps for them, just as a real device's registers sit at fixed bytes. A
 * write's value has only those bits set; a read's other bits are ignored. Larger guest accesses
 * (vector, quadword, multiple and string instructions) arrive as the separate accesses of at most
 * 8 bytes the instruction makes. Unaligned accesses, accesses that leave the region and
 * instruction fetches never reach the handler: the guest takes the machine check of unassigned
 * memory. Nor do the reservation and atomic instructions (a data storage interrupt) and the
 * cache-block instructions ({@code dcbz} takes an alignment interrupt, the others do nothing).
 *
 * <h2>Threads</h2>
 * {@link #read} and {@link #write} are called on the CPU threads, by several at once in a
 * machine with more than one CPU, with no lock of the machine held; the handler does its own
 * synchronisation. The CPU making the access waits for the call, so a handler should return
 * quickly and must not wait for that CPU. It may raise or lower interrupts. {@link #reset},
 * {@link #saveState} and {@link #loadState} are called while no CPU runs.
 */
public interface MmioHandler {
    /**
     * A guest load of {@code size} bytes at {@code offset}; returns the value (see the class
     * comment for its byte order). Throw {@link BusError} to fail the access.
     */
    long read(long offset, int size);

    /**
     * A guest store of {@code size} bytes at {@code offset} with {@code value} (see the class
     * comment for its byte order). Throw {@link BusError} to fail the access.
     */
    void write(long offset, int size, long value);

    /** The machine is reset (power-on or reboot) before the guest runs again. */
    default void reset() {
    }

    /**
     * Write the device's state into a saved machine. What is written is given back to
     * {@link #loadState} when the machine is restored, in this process or another.
     */
    default void saveState(DataOutput out) throws IOException {
    }

    /**
     * Read what {@link #saveState} wrote; the input ends where that output ended. Reading past
     * the end or leaving bytes unread makes the load fail with {@link StateFormatException}.
     */
    default void loadState(DataInput in) throws IOException {
    }

    /**
     * Thrown by a handler to fail the access it was called for, as a bus error: the guest takes
     * the machine check it would take for an address nothing is mapped at, and a hypercall
     * access reads 0 or drops the store.
     */
    final class BusError extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public BusError(String message) {
            super(message, null, false, false);
        }
    }
}
