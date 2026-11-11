package org.boxtheta.javapower.papr;

/**
 * Host side of a character device (the console). Output is written synchronously from the CPU
 * thread; input is pushed by the backend, from any thread, into the {@link InputSink} handed to
 * {@link #start}.
 *
 * <p>Implement this to connect the guest console to anything (a socket, a terminal widget, a
 * test script). The machine owns the backend it is given: it calls {@link #start} once while it
 * is constructed and {@link #close} when it is closed. If the machine's construction fails, it
 * closes the backends it already started and leaves the others to the caller. Each console of a
 * machine needs a backend instance of its own ({@link MachineConfig#console},
 * {@link MachineConfig#addConsole}).
 */
public interface CharBackend {
    /** The device side of the console, handed to {@link #start}. Thread-safe. */
    @FunctionalInterface
    interface InputSink {
        /** Deliver host-to-guest bytes. */
        void receive(byte[] b, int off, int len);

        /**
         * Report a host I/O failure of the backend. It reaches the machine's {@link PaprLog} once
         * per {@code key}, so a backend may call this on every failure.
         */
        default void error(String key, String detail) {
        }
    }

    /**
     * Guest output, called on the CPU thread that wrote it; calls never overlap and arrive in
     * guest order. Must not block for long: the guest CPU waits for it.
     */
    void write(byte[] b, int off, int len);

    /** Begin delivering input. Called once when the device is attached. */
    void start(InputSink sink);

    /** Stop delivering input and release host resources (threads, streams). Idempotent. */
    default void close() {
    }
}
