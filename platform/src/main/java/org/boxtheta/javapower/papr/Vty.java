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
 *
 * <p>The first vty of a machine is its console ({@link MachineConfig#console}); further ones come
 * from {@link MachineConfig#addConsole} and may carry a name, which the guest finds in the node's
 * {@code label} property. The machine starts each vty's backend once while it is constructed and
 * closes it when the machine is closed; a backend the machine never started (because construction
 * failed first) is left to its owner.
 */
public final class Vty extends VioDevice {
    static final int BUFSIZE = 16;
    /** Host input beyond this many waiting bytes is dropped. */
    private static final int MAX_BACKLOG = 1 << 20;

    private final CharBackend backend;
    private final String name;
    private volatile boolean started;
    private final byte[] buf = new byte[BUFSIZE];
    private int in;
    private int out;
    private final ArrayDeque<Byte> backlog = new ArrayDeque<>();
    private final Object inLock = new Object();
    private final Object outLock = new Object();

    /** A vty without a name on {@code backend}. */
    public Vty(CharBackend backend) {
        this(backend, null);
    }

    Vty(CharBackend backend, String name) {
        this.backend = java.util.Objects.requireNonNull(backend, "backend");
        this.name = name;
    }

    public CharBackend backend() {
        return backend;
    }

    /**
     * The name given with {@link MachineConfig.Console#name}, which the guest reads from the
     * node's {@code label} property; null for none (always for the first console).
     */
    public String name() {
        return name;
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

    @Override
    protected void addDeviceProps(Fdt.Node node) {
        if (name != null) {
            node.setString("label", name);
        }
    }

    /** The name, so that a state saved with other names (or none) is refused. */
    @Override
    public String hardwareIdentity() {
        return name == null ? "" : "label=" + name;
    }

    /** Start the backend of a vty added with {@link MachineConfig#addConsole}. */
    @Override
    protected void startDevice() {
        start();
    }

    /** Close the backend of a vty added with {@link MachineConfig#addConsole}, if it was started. */
    @Override
    protected void closeDevice() {
        if (started) {
            backend.close();
        }
    }

    void start() {
        started = true;
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

    /** Input not yet taken by the guest: the device buffer, then the backlog. */
    @Override
    protected void saveDevice(java.io.DataOutput d) throws java.io.IOException {
        synchronized (inLock) {
            byte[] b = new byte[in - out];
            for (int i = 0; i < b.length; i++) {
                b[i] = buf[(out + i) % BUFSIZE];
            }
            org.boxtheta.javapower.core.StateOutput.writeBytes(d, b);
            byte[] l = new byte[backlog.size()];
            int i = 0;
            for (byte x : backlog) {
                l[i++] = x;
            }
            org.boxtheta.javapower.core.StateOutput.writeBytes(d, l);
        }
    }

    /**
     * Restore the saved input. Input the new backend delivered before the load (typed while the
     * machine was being restored) is kept and follows the saved input.
     */
    @Override
    protected void loadDevice(org.boxtheta.javapower.core.StateInput.Record r) throws java.io.IOException {
        byte[] b = r.readBytes(BUFSIZE);
        byte[] l = r.readBytes(MAX_BACKLOG);
        if (b == null || l == null) {
            throw r.corrupt("vty input");
        }
        synchronized (inLock) {
            ArrayDeque<Byte> early = new ArrayDeque<>();
            while (out != in) {
                early.add(buf[out++ % BUFSIZE]);
            }
            early.addAll(backlog);
            backlog.clear();
            in = 0;
            out = 0;
            for (byte x : b) {
                buf[in++] = x;
            }
            for (byte x : l) {
                backlog.add(x);
            }
            backlog.addAll(early);
        }
    }

    /**
     * After a load: move waiting input into an empty device buffer and raise the interrupt, as
     * when input arrives (the saved interrupt state already covers input that was saved there).
     */
    void afterLoad() {
        synchronized (inLock) {
            fill();
        }
    }

    @Override
    protected void resetDevice() {
        // QEMU keeps buffered input across reset.
    }
}
