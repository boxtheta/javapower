package org.boxtheta.javapower.papr;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.Arrays;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** One frame per UDP datagram, both ways, and a clean close. */
class DatagramNetBackendTest {
    @Test
    void framesTravelAsDatagrams() throws Exception {
        InetAddress lo = InetAddress.getLoopbackAddress();
        try (DatagramChannel peer = DatagramChannel.open().bind(new InetSocketAddress(lo, 0))) {
            DatagramNetBackend b = new DatagramNetBackend(new InetSocketAddress(lo, 0),
                    (InetSocketAddress) peer.getLocalAddress());
            LinkedBlockingQueue<byte[]> got = new LinkedBlockingQueue<>();
            b.start((frame, off, len) -> got.add(Arrays.copyOfRange(frame, off, off + len)));

            byte[] out = new byte[1514];
            Arrays.fill(out, (byte) 0x5a);
            b.transmit(out);
            ByteBuffer buf = ByteBuffer.allocate(70000);
            peer.receive(buf);
            buf.flip();
            assertEquals(1514, buf.remaining());
            assertArrayEquals(out, Arrays.copyOf(buf.array(), buf.limit()));

            byte[] in = new byte[9000];
            Arrays.fill(in, (byte) 0x33);
            peer.send(ByteBuffer.wrap(in), b.localAddress());
            byte[] f = got.poll(10, TimeUnit.SECONDS);
            assertNotNull(f);
            assertArrayEquals(in, f);

            Thread t = b.readerThread();
            b.close();
            t.join(10_000);
            assertFalse(t.isAlive(), "close ends the reader thread");
            b.transmit(out); // ignored after close
            b.close();
        }
    }
}
