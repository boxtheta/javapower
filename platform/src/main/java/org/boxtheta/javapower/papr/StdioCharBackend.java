package org.boxtheta.javapower.papr;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Console on the process's standard input and output. Input is read by a daemon thread. */
public final class StdioCharBackend implements CharBackend {
    private final InputStream in;
    private final OutputStream out;
    private Thread reader;

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
            PaprLog.once("console-write-failed", e.toString());
        }
    }

    @Override
    public synchronized void start(InputSink sink) {
        if (reader != null) {
            return;
        }
        reader = new Thread(() -> {
            byte[] buf = new byte[256];
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    sink.receive(buf, 0, n);
                }
            } catch (IOException e) {
                PaprLog.once("console-read-failed", e.toString());
            }
        }, "papr-console-input");
        reader.setDaemon(true);
        reader.start();
    }
}
