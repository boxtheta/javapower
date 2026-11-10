package org.boxtheta.javapower.papr;

/**
 * Host side of a character device (the console). Output is written synchronously from the CPU
 * thread; input is pushed by the backend, from any thread, into the {@link InputSink} handed to
 * {@link #start}.
 */
public interface CharBackend {
    /** Receiver for host-to-guest bytes. Thread-safe. */
    @FunctionalInterface
    interface InputSink {
        void receive(byte[] b, int off, int len);
    }

    void write(byte[] b, int off, int len);

    /** Begin delivering input. Called once when the device is attached. */
    void start(InputSink sink);

    default void close() {
    }
}
