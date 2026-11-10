package org.boxtheta.javapower.papr;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;

/**
 * Console on a pair of streams, by default the process's standard input and output (the CLI's
 * console). Input is read by a daemon thread, {@code javapower-console-input}.
 *
 * <p>{@link #close} stops input delivery at once and interrupts the reader thread. A reader blocked
 * in a stream that ignores interrupts (as {@code System.in} does) exits when its read next
 * returns; it is a daemon thread, so it never keeps the JVM alive. The streams themselves are
 * not closed: they belong to the caller.
 */
public final class StdioCharBackend implements CharBackend {
    private final InputStream in;
    private final OutputStream out;
    private Thread reader;
    private volatile InputSink sink;
    private volatile boolean closed;

    /** Standard input and output. */
    public StdioCharBackend() {
        this(System.in, System.out);
    }

    public StdioCharBackend(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    @Override
    public void write(byte[] b, int off, int len) {
        try {
            out.write(b, off, len);
            out.flush();
        } catch (IOException e) {
            InputSink s = sink;
            if (s != null) {
                s.error("console-write-failed", e.toString());
            }
        }
    }

    @Override
    public synchronized void start(InputSink s) {
        if (reader != null || closed) {
            return;
        }
        sink = s;
        reader = Thread.ofPlatform().daemon().name("javapower-console-input").start(() -> {
            byte[] buf = new byte[256];
            try {
                int n;
                while (!closed && (n = in.read(buf)) > 0) {
                    if (closed) {
                        break;
                    }
                    s.receive(buf, 0, n);
                }
            } catch (InterruptedIOException e) {
                // close()
            } catch (IOException e) {
                if (!closed) {
                    s.error("console-read-failed", e.toString());
                }
            }
        });
    }

    /** The input thread, or null before {@link #start}. */
    synchronized Thread readerThread() {
        return reader;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (reader != null) {
            reader.interrupt();
        }
    }
}
