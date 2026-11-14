package org.boxtheta.javapower.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.boxtheta.javapower.papr.BlockBackend;
import org.boxtheta.javapower.papr.ScsiDisk;

/**
 * The CLI's {@code --media tcp:...}: a TCP port that takes commands to change the media of the
 * machine's CD-ROM and removable disk drives while the guest runs. One command per line, drives
 * named by their SCSI target id; each command is answered with lines ending in one that starts
 * with {@code ok} or {@code error}:
 * <pre>
 * load ID FILE [ro|snapshot]   put the image FILE into drive ID (writes go to the file; ro: write
 *                              protected; snapshot: writes are discarded), replacing its medium
 * eject ID                     empty drive ID
 * status                       one line per drive: ID TYPE and the medium, then ok
 * </pre>
 * A medium the guest ejects itself from a removable disk drive is taken out too, and reported on
 * stderr.
 *
 * <p>A thread {@code javapower-media-<port>} accepts clients and serves them one at a time;
 * {@link #close} closes the listening socket and the client, which ends it.
 */
final class MediaControl implements AutoCloseable {
    private final ServerSocket server;
    private final Map<Integer, ScsiDisk> drives = new TreeMap<>();
    private volatile boolean closed;
    private volatile Socket client;

    /** Listen on {@code spec}: {@code port} (on the loopback address) or {@code host:port}. */
    MediaControl(String spec) throws IOException {
        int colon = spec.lastIndexOf(':');
        InetAddress host = colon < 0 ? InetAddress.getLoopbackAddress() : InetAddress.getByName(spec.substring(0, colon));
        int port = Integer.parseInt(spec.substring(colon + 1));
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(host, port));
    }

    String address() {
        return server.getInetAddress().getHostAddress() + ":" + server.getLocalPort();
    }

    /** Serve the drives among {@code disks} that have removable media. */
    void start(Iterable<ScsiDisk> disks) {
        for (ScsiDisk d : disks) {
            if (d.type().removable()) {
                drives.put(d.id(), d);
            }
        }
        Thread.ofPlatform().daemon().name("javapower-media-" + server.getLocalPort()).start(this::serve);
    }

    /**
     * When the guest ejects the medium of {@code d}, take it out and close it, as a drive that
     * pushes its medium out would.
     */
    static void ejectForGuest(ScsiDisk d) {
        d.onGuestEject(() -> {
            BlockBackend out = d.changeMedium(null);
            System.err.printf("[javapower] guest ejected the medium of SCSI device %d%s%n", d.id(),
                    out == null ? " (the drive was empty)" : "");
            closeMedium(out);
        });
    }

    private void serve() {
        while (!closed) {
            try (Socket s = server.accept()) {
                client = s;
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                PrintStream out = new PrintStream(s.getOutputStream(), true, StandardCharsets.UTF_8);
                String line;
                while ((line = in.readLine()) != null) {
                    if (!line.isBlank()) {
                        out.println(command(line.trim(), out));
                    }
                }
            } catch (IOException e) {
                // The client went away, or close() closed the sockets.
            } finally {
                client = null;
            }
        }
    }

    /** Run one command; the returned line ends its answer. */
    private String command(String line, PrintStream out) {
        String[] w = line.split("\\s+", 3);
        try {
            switch (w[0]) {
                case "status" -> {
                    for (ScsiDisk d : drives.values()) {
                        out.println(describe(d));
                    }
                    return "ok";
                }
                case "eject" -> {
                    BlockBackend old = drive(w).changeMedium(null);
                    closeMedium(old);
                    return old == null ? "ok the drive was empty" : "ok";
                }
                case "load" -> {
                    ScsiDisk d = drive(w);
                    if (w.length < 3) {
                        return "error load needs a drive and a file";
                    }
                    String file = w[2];
                    boolean ro = file.endsWith(" ro");
                    boolean snapshot = file.endsWith(" snapshot");
                    if (ro || snapshot) {
                        file = file.substring(0, file.lastIndexOf(' ')).trim();
                    }
                    BlockBackend medium = BlockBackend.open(Path.of(file), ro, snapshot);
                    try {
                        closeMedium(d.changeMedium(medium));
                    } catch (RuntimeException e) {
                        closeMedium(medium);
                        throw e;
                    }
                    return "ok " + medium.size() + " bytes";
                }
                default -> {
                    return "error unknown command " + w[0] + "; commands are load, eject and status";
                }
            }
        } catch (IOException | RuntimeException e) {
            return "error " + e;
        }
    }

    private ScsiDisk drive(String[] w) {
        if (w.length < 2) {
            throw new IllegalArgumentException("which drive? " + drives.keySet());
        }
        ScsiDisk d = drives.get(Integer.parseInt(w[1]));
        if (d == null) {
            throw new IllegalArgumentException("SCSI device " + w[1] + " is not a drive with removable media; those are "
                    + drives.keySet());
        }
        return d;
    }

    private static String describe(ScsiDisk d) {
        BlockBackend b = d.backend();
        return d.id() + " " + d.type() + (b == null ? " empty" : " " + b.size() + " bytes" + (b.readOnly() ? " ro" : ""))
                + (d.ejected() ? " ejected" : "") + (d.removalPrevented() ? " locked" : "");
    }

    private static void closeMedium(BlockBackend b) {
        if (b != null) {
            try {
                b.close();
            } catch (IOException e) {
                System.err.println("[javapower] closing a medium: " + e);
            }
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
        Socket s = client;
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                // nothing to do
            }
        }
    }
}
