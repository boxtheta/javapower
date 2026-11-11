package org.boxtheta.javapower.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import org.boxtheta.javapower.papr.CharBackend;

/**
 * A console on a TCP port, for the CLI's {@code --console tcp:...}: it listens on the address it
 * is given and serves one client at a time, as QEMU's {@code -chardev socket,server=on,wait=off}
 * does. The client's bytes are the guest's input; the guest's output goes to the client, and is
 * dropped while no client is connected. A thread {@code javapower-console-<port>} accepts and
 * reads; {@link #close} closes the listening socket and the client, which ends it.
 *
 * <p>Writes to the client block the guest CPU while the client does not read, so the client
 * should keep reading.
 */
final class SocketConsole implements CharBackend {
    private final ServerSocket server;
    private final Object lock = new Object();
    private Socket client;
    private OutputStream out;
    private volatile boolean closed;
    private Thread thread;

    /**
     * Listen on {@code spec}: {@code port} (on the loopback address) or {@code host:port}. Port 0
     * picks a free port; {@link #port()} tells which.
     */
    SocketConsole(String spec) throws IOException {
        int colon = spec.lastIndexOf(':');
        InetAddress host = colon < 0 ? InetAddress.getLoopbackAddress() : InetAddress.getByName(spec.substring(0, colon));
        int port = Integer.parseInt(spec.substring(colon + 1));
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(host, port));
    }

    int port() {
        return server.getLocalPort();
    }

    String address() {
        return server.getInetAddress().getHostAddress() + ":" + port();
    }

    @Override
    public void write(byte[] b, int off, int len) {
        synchronized (lock) {
            if (out == null) {
                return;
            }
            try {
                out.write(b, off, len);
                out.flush();
            } catch (IOException e) {
                dropClient();
            }
        }
    }

    @Override
    public synchronized void start(InputSink sink) {
        if (thread != null || closed) {
            return;
        }
        thread = Thread.ofPlatform().daemon().name("javapower-console-" + port()).start(() -> serve(sink));
    }

    private void serve(InputSink sink) {
        byte[] buf = new byte[4096];
        while (!closed) {
            Socket s;
            try {
                s = server.accept();
                s.setTcpNoDelay(true);
            } catch (IOException e) {
                if (!closed) {
                    sink.error("console-accept-failed", address() + ": " + e);
                }
                return;
            }
            InputStream in;
            synchronized (lock) {
                try {
                    in = s.getInputStream();
                    out = s.getOutputStream();
                    client = s;
                } catch (IOException e) {
                    closeQuietly(s);
                    continue;
                }
                if (closed) {
                    dropClient();
                    return;
                }
            }
            try {
                int n;
                while ((n = in.read(buf)) > 0) {
                    sink.receive(buf, 0, n);
                }
            } catch (IOException e) {
                // The client went away, or close() closed it.
            }
            synchronized (lock) {
                if (client == s) {
                    dropClient();
                }
            }
        }
    }

    /** Forget the client and close its socket. Holds {@link #lock}. */
    private void dropClient() {
        if (client != null) {
            closeQuietly(client);
        }
        client = null;
        out = null;
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException e) {
            // nothing to do
        }
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (IOException e) {
            // nothing to do
        }
        synchronized (lock) {
            dropClient();
        }
    }
}
