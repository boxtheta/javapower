package org.boxtheta.javapower.papr;

/**
 * Host side of a virtual Ethernet device ({@link LLan}): the other end of the guest's network
 * cable. The guest's frames reach {@link #transmit}; the backend hands frames to the guest
 * through the {@link FrameSink} given to {@link #start}. A frame is a whole Ethernet frame from
 * the destination address to the end of the payload, without preamble or frame check sequence,
 * at most {@link LLan#MAX_FRAME} bytes.
 *
 * <p>Implement this to connect the guest to anything: another emulated machine, a network
 * simulation, a socket ({@link DatagramNetBackend}), a test script. The machine owns the backend
 * it is given: it calls {@link #start} once while it is constructed and {@link #close} when it is
 * closed.
 *
 * <h2>Threads</h2>
 * <ul>
 * <li>{@link #transmit} is called on the guest CPU thread that sent the frame (inside its
 *     H_SEND_LOGICAL_LAN hypercall). Calls for one device never overlap and arrive in the order
 *     the guest sent the frames; calls for different devices may overlap. The CPU waits for the
 *     call to return, and so do other CPUs sending on the same device, so it must not block for
 *     long: queue the frame and return, or drop it. Receiving is not held up by a slow
 *     {@code transmit}.</li>
 * <li>{@link FrameSink#receive} may be called from any thread, any number of threads at once,
 *     at any time between {@link #start} and {@link #close}, whether or not the guest is running.
 *     It never waits for the guest or for {@code transmit}: it copies the frame, writes it into a
 *     receive buffer the guest posted (or queues it) and returns.</li>
 * </ul>
 *
 * <h2>Delivery</h2>
 * A frame handed to the sink is copied before {@code receive} returns, so the caller may reuse
 * its array. The device delivers frames in the order received. The guest must have opened the
 * device (its driver registered it with H_REGISTER_LOGICAL_LAN) and posted receive buffers; until
 * it has, and while it has no buffer free, frames wait in a short queue
 * ({@link LLan#QUEUE_FRAMES} frames). Frames that arrive while the device is closed by the guest,
 * or while the queue is full, are dropped, and {@code receive} returns false: this is the
 * backend's backpressure signal, and like a real link the device never blocks the sender. The
 * guest's driver accounts for frames too large for any buffer it posted.
 *
 * <p>The device has no link state: PAPR gives the guest no way to see the cable, so its
 * drivers always report the link up. A backend that is disconnected simply drops what the guest
 * sends and delivers nothing.
 *
 * <p>Frames in flight are not part of a saved machine state: the queue is dropped on save and
 * on load, as a real link drops frames during a pause.
 */
public interface NetBackend {
    /** The device side of the link, handed to {@link #start}. Thread-safe. */
    interface FrameSink {
        /**
         * Hand a frame to the guest. The bytes are copied before this returns.
         *
         * @return true if the frame was delivered or queued for the guest, false if it was dropped
         *     (the guest has not opened the device, the queue is full, the frame is longer than
         *     {@link LLan#MAX_FRAME} bytes, the machine is being saved or loaded, or it is closed)
         */
        boolean receive(byte[] frame, int off, int len);

        /**
         * Report a host I/O failure of the backend. It reaches the machine's {@link PaprLog} once
         * per {@code key}, so a backend may call this on every failure.
         */
        default void error(String key, String detail) {
        }
    }

    /**
     * A frame the guest sent. The array is a fresh copy that belongs to the backend from now on.
     * Called on the sending CPU's thread; see the class comment.
     */
    void transmit(byte[] frame);

    /** Begin delivering frames. Called once when the machine is constructed. */
    void start(FrameSink sink);

    /**
     * Stop delivering frames and release host resources (threads, sockets). Idempotent. After the
     * machine has closed its devices the sink drops everything it is given.
     */
    default void close() {
    }
}
