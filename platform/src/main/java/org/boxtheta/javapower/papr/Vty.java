package org.boxtheta.javapower.papr;

import java.util.ArrayDeque;

/**
 * The hypervisor virtual terminal ({@code vty@...}, compatible "hvterm1"), driven by
 * H_PUT_TERM_CHAR / H_GET_TERM_CHAR.
 *
 * <p>Input follows QEMU's spapr_vty: the device holds a 16-byte buffer; host input waits in a
 * backlog until the buffer has room (QEMU applies the same backpressure to its chardev), and the
 * interrupt is pulsed whenever bytes arrive in an empty buffer. Host input is accepted from any
 * thread. Input state is guarded by an input lock and guest output by a separate output lock, so
 * H_PUT_TERM_CHAR never waits for console input or for H_GET_TERM_CHAR, and neither takes the
 * machine lock.
 */
public final class Vty extends VioDevice {
    static final int BUFSIZE = 16;
    /** Host input beyond this many waiting bytes is dropped. */
    private static final int MAX_BACKLOG = 1 << 20;

    private final CharBackend backend;
    private final byte[] buf = new byte[BUFSIZE];
    private int in;
    private int out;
    private final ArrayDeque<Byte> backlog = new ArrayDeque<>();
    private final Object inLock = new Object();
    private final Object outLock = new Object();

    public Vty(CharBackend backend) {
        this.backend = backend;
    }

    public CharBackend backend() {
        return backend;
    }

    @Override
    public String dtName() {
        return "vty";
    }

    @Override
    public String dtType() {
        return "serial";
    }

    @Override
    public String dtCompatible() {
        return "hvterm1";
    }

    void start() {
        backend.start(new CharBackend.InputSink() {
            @Override
            public void receive(byte[] b, int off, int len) {
                Vty.this.receive(b, off, len);
            }

            @Override
            public void error(String key, String detail) {
                log().once(key, detail);
            }
        });
    }

    /** Host-to-guest input (any thread). */
    public void receive(byte[] b, int off, int len) {
        synchronized (inLock) {
            for (int i = 0; i < len; i++) {
                if (backlog.size() >= MAX_BACKLOG) {
                    log().once("vty-input-overflow", "console input dropped");
                    break;
                }
                backlog.add(b[off + i]);
            }
            fill();
        }
    }

    /** Move backlog bytes into the device buffer; pulse if they land in an empty buffer. */
    private void fill() {
        if (backlog.isEmpty() || in - out >= BUFSIZE) {
            return;
        }
        boolean wasEmpty = in == out;
        while (!backlog.isEmpty() && in - out < BUFSIZE) {
            buf[in++ % BUFSIZE] = backlog.pollFirst();
        }
        if (wasEmpty) {
            pulseIrq();
        }
    }

    /** Guest output (any thread; each call reaches the backend whole, in call order). */
    public void putChars(byte[] b, int off, int len) {
        synchronized (outLock) {
            backend.write(b, off, len);
        }
    }

    /**
     * Take up to {@code dst.length} buffered input bytes (any thread).
     * Like QEMU (and PowerVM), stops before a NUL that directly follows a CR so the guest's
     * "\r\0" workaround does not eat real input.
     */
    public int getChars(byte[] dst) {
        synchronized (inLock) {
            int n = 0;
            while (n < dst.length && out != in) {
                if (n > 0 && dst[n - 1] == '\r' && buf[out % BUFSIZE] == 0) {
                    break;
                }
                dst[n++] = buf[out++ % BUFSIZE];
            }
            fill();
            return n;
        }
    }

    /** Bytes waiting for the guest (device buffer plus backlog). */
    public int pendingInput() {
        synchronized (inLock) {
            return (in - out) + backlog.size();
        }
    }

    @Override
    protected void resetDevice() {
        // QEMU keeps buffered input across reset.
    }
}
