package org.boxtheta.javapower.papr;

import java.util.List;
import java.util.Objects;

/**
 * One request popped from a {@link Virtqueue}: a chain of guest buffers, first those the device
 * reads (the driver's data), then those it writes (its reply). The device reads and writes them
 * as two byte streams through {@link #read} and {@link #write}, then hands the request back with
 * {@link #complete}, which puts it on the used ring and interrupts the driver unless it asked
 * not to be.
 *
 * <p>Thread-safe, usable from any thread. A request belongs to the device until completed; once
 * the device is reset (by the driver or the machine) it is stale, and its methods do nothing
 * and say so, so a late completion never touches memory the guest has reused.
 */
public final class VirtqueueRequest {
    private final Virtqueue queue;
    private final int head;
    private final int generation;
    /** {address, length, writable} per buffer, in chain order. */
    private final List<long[]> segs;
    private final long readable;
    private final long writable;

    VirtqueueRequest(Virtqueue queue, int head, int generation, List<long[]> segs) {
        this.queue = queue;
        this.head = head;
        this.generation = generation;
        this.segs = segs;
        long r = 0;
        long w = 0;
        for (long[] s : segs) {
            if (s[2] != 0) {
                w += s[1];
            } else {
                r += s[1];
            }
        }
        this.readable = r;
        this.writable = w;
    }

    /** The queue the request came from. */
    public Virtqueue queue() {
        return queue;
    }

    /** The index of the chain's first descriptor, as the driver identifies the request. */
    public int head() {
        return head;
    }

    /** Total bytes in the device-readable buffers. */
    public long readableLength() {
        return readable;
    }

    /** Total bytes in the device-writable buffers. */
    public long writableLength() {
        return writable;
    }

    /** Whether the request can still be read, written and completed (the device was not reset). */
    public boolean isCurrent() {
        synchronized (queue.lock()) {
            return queue.current(generation, head);
        }
    }

    /**
     * Copy up to {@code len} bytes of the device-readable buffers, from byte {@code offset} of
     * their concatenation, into {@code dst}. Returns the number of bytes copied (less than
     * {@code len} at the end of the buffers), or -1 if the request is stale.
     */
    public int read(long offset, byte[] dst, int off, int len) {
        Objects.checkFromIndexSize(off, len, dst.length);
        return copy(false, offset, dst, off, len);
    }

    /**
     * Copy up to {@code len} bytes from {@code src} into the device-writable buffers at byte
     * {@code offset} of their concatenation. Returns the number of bytes copied (less than
     * {@code len} at the end of the buffers), or -1 if the request is stale.
     */
    public int write(long offset, byte[] src, int off, int len) {
        Objects.checkFromIndexSize(off, len, src.length);
        return copy(true, offset, src, off, len);
    }

    private int copy(boolean toGuest, long offset, byte[] b, int off, int len) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset " + offset);
        }
        synchronized (queue.lock()) {
            if (!queue.current(generation, head)) {
                return -1;
            }
            int done = 0;
            long pos = 0;
            for (long[] s : segs) {
                if ((s[2] != 0) != toGuest) {
                    continue;
                }
                long segEnd = pos + s[1];
                if (offset + done < segEnd && done < len) {
                    long in = offset + done - pos;
                    int n = (int) Math.min(len - done, s[1] - in);
                    if (toGuest) {
                        queue.memory().write(s[0] + in, b, off + done, n);
                    } else {
                        queue.memory().read(s[0] + in, b, off + done, n);
                    }
                    done += n;
                }
                pos = segEnd;
            }
            return done;
        }
    }

    /**
     * Hand the request back to the driver, saying it wrote {@code written} bytes into the
     * device-writable buffers, and interrupt the driver unless it suppressed interrupts. Returns
     * false, doing nothing, if the request is stale or was already completed.
     *
     * @throws IllegalArgumentException if {@code written} is negative or more than the writable
     *     buffers hold
     */
    public boolean complete(int written) {
        if (written < 0 || written > writable) {
            throw new IllegalArgumentException("written " + written + " of " + writable + " writable bytes");
        }
        synchronized (queue.lock()) {
            if (!queue.current(generation, head)) {
                return false;
            }
            if (queue.pushUsed(head, written)) {
                queue.usedInterrupt();
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return String.format("request %d on %s (%d readable, %d writable bytes)", head, queue, readable, writable);
    }
}
