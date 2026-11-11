package org.boxtheta.javapower.papr;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;

/**
 * A network backend that carries each Ethernet frame in one UDP datagram: frames the guest sends
 * go to a remote address, and every datagram arriving at the local address is a frame for the
 * guest. This is the wire format of QEMU's {@code -netdev dgram} with {@code inet} addresses and
 * of {@code -netdev socket,udp=...,localaddr=...}, so a javapower guest can share a link with a
 * QEMU guest, with another javapower guest (each sending to the other's local address), or with
 * any program that speaks it.
 *
 * <p>The socket is bound when the backend is constructed. Datagrams are read by a daemon thread,
 * {@code javapower-net-udp}, started by {@link #start}; frames are sent on the guest CPU's thread.
 * {@link #close} closes the socket, which ends the thread. Send and receive failures go to the
 * machine's log, once per kind.
 */
public final class DatagramNetBackend implements NetBackend {
    private final DatagramChannel channel;
    private final SocketAddress remote;
    private volatile FrameSink sink;
    private Thread reader;
    private volatile boolean closed;

    /**
     * Bind {@code local} and send to {@code remote}.
     *
     * @throws IOException if the local address cannot be bound
     */
    public DatagramNetBackend(InetSocketAddress local, InetSocketAddress remote) throws IOException {
        this.remote = remote;
        DatagramChannel c = DatagramChannel.open();
        try {
            c.bind(local);
        } catch (IOException | RuntimeException e) {
            c.close();
            throw e;
        }
        this.channel = c;
    }

    /** The bound local address (with the port chosen if {@code local} had port 0). */
    public InetSocketAddress localAddress() throws IOException {
        return (InetSocketAddress) channel.getLocalAddress();
    }

    @Override
    public void transmit(byte[] frame) {
        if (closed) {
            return;
        }
        try {
            channel.send(ByteBuffer.wrap(frame), remote);
        } catch (ClosedChannelException e) {
            // close()
        } catch (IOException e) {
            FrameSink s = sink;
            if (s != null) {
                s.error("net-udp-send-failed", remote + ": " + e);
            }
        }
    }

    @Override
    public synchronized void start(FrameSink s) {
        if (reader != null || closed) {
            return;
        }
        sink = s;
        reader = Thread.ofPlatform().daemon().name("javapower-net-udp").start(() -> {
            ByteBuffer buf = ByteBuffer.allocate(LLan.MAX_FRAME);
            while (!closed) {
                try {
                    buf.clear();
                    channel.receive(buf);
                    buf.flip();
                    s.receive(buf.array(), 0, buf.limit());
                } catch (ClosedChannelException e) {
                    return;
                } catch (IOException e) {
                    if (closed) {
                        return;
                    }
                    s.error("net-udp-receive-failed", e.toString());
                }
            }
        });
    }

    /** The reader thread, or null before {@link #start}. */
    synchronized Thread readerThread() {
        return reader;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            channel.close();
        } catch (IOException e) {
            // nothing left to release
        }
    }
}
