package javapower.papr;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** In-memory console: collects output and lets the caller type input (tests, scripting). */
public final class BufferCharBackend implements CharBackend {
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private volatile InputSink sink;

    @Override
    public synchronized void write(byte[] b, int off, int len) {
        out.write(b, off, len);
    }

    @Override
    public void start(InputSink s) {
        sink = s;
    }

    /** Everything the guest has written so far. */
    public synchronized byte[] outputBytes() {
        return out.toByteArray();
    }

    public synchronized String output() {
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    public synchronized void clearOutput() {
        out.reset();
    }

    /** Deliver input to the guest. */
    public void type(byte[] b) {
        InputSink s = sink;
        if (s == null) {
            throw new IllegalStateException("backend not attached");
        }
        s.receive(b, 0, b.length);
    }

    public void type(String s) {
        type(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
