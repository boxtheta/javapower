package org.boxtheta.javapower.viotest;

import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import org.boxtheta.javapower.core.StateInput;
import org.boxtheta.javapower.papr.Fdt;
import org.boxtheta.javapower.papr.VioDevice;

/**
 * A CRQ device written outside {@code org.boxtheta.javapower.papr}, the way an embedder would:
 * the compiler checks that everything it uses is reachable.
 *
 * <p>Protocol: the guest sends {@code 80 01 <len:16> ... <ioba:64>}; the device reads {@code len}
 * bytes at {@code ioba}, flips bit 5 of each (ASCII case), writes them back and replies
 * {@code 80 81 00 00 <requests so far:32> <ioba:64>}. {@link #postFromHost} sends
 * {@code 80 82 00 00 <value:32> 0...} from any thread.
 */
public class EchoDevice extends VioDevice {
    public static final String COMPATIBLE = "javapower,echo";
    public static final long WINDOW = 1 << 20;

    private final String identity;
    private int requests;
    public final AtomicInteger starts = new AtomicInteger();
    public final AtomicInteger resets = new AtomicInteger();
    public final AtomicInteger quiesces = new AtomicInteger();
    public final AtomicInteger closes = new AtomicInteger();

    public EchoDevice(String identity) {
        this.identity = identity;
    }

    @Override
    public String dtName() {
        return "echo";
    }

    @Override
    public String dtType() {
        return "javapower-test";
    }

    @Override
    public String dtCompatible() {
        return COMPATIBLE;
    }

    @Override
    public long dmaWindowSize() {
        return WINDOW;
    }

    @Override
    public long signalMask() {
        return 1;
    }

    @Override
    public boolean supportsCrq() {
        return true;
    }

    @Override
    public String hardwareIdentity() {
        return identity;
    }

    @Override
    protected void addDeviceProps(Fdt.Node node) {
        node.setCell("javapower,ports", 4);
        node.setString("device_type", "replaced by the machine");
    }

    @Override
    protected long handleCrq(byte[] crq) {
        if ((crq[0] & 0xff) != 0x80 || crq[1] != 1) {
            log().once("echo-bad-crq", nodeName());
            return H_PARAMETER;
        }
        ByteBuffer in = ByteBuffer.wrap(crq);
        int len = in.getShort(2) & 0xffff;
        long ioba = in.getLong(8);
        byte[] b = new byte[len];
        if (!dmaRead(ioba, b, 0, len)) {
            return H_PARAMETER;
        }
        for (int i = 0; i < len; i++) {
            b[i] ^= 0x20;
        }
        if (!dmaWrite(ioba, b, 0, len)) {
            return H_PARAMETER;
        }
        requests++;
        byte[] r = ByteBuffer.allocate(16).put((byte) 0x80).put((byte) 0x81).putShort((short) 0)
                .putInt(requests).putLong(ioba).array();
        return sendCrq(r) == 0 ? H_SUCCESS : H_RESOURCE;
    }

    /** Post an entry from a thread of the embedder's own. Returns what sendCrq returned. */
    public int postFromHost(int value) {
        byte[] r = ByteBuffer.allocate(16).put((byte) 0x80).put((byte) 0x82).putShort((short) 0)
                .putInt(value).putLong(0).array();
        synchronized (lock()) {
            return sendCrq(r);
        }
    }

    public int requests() {
        synchronized (lock()) {
            return requests;
        }
    }

    @Override
    protected void startDevice() {
        starts.incrementAndGet();
    }

    @Override
    protected void resetDevice() {
        resets.incrementAndGet();
        requests = 0;
    }

    @Override
    protected void quiesceDevice() {
        quiesces.incrementAndGet();
    }

    @Override
    protected void closeDevice() throws IOException {
        closes.incrementAndGet();
    }

    @Override
    protected void saveDevice(DataOutput out) throws IOException {
        out.writeInt(requests);
    }

    @Override
    protected void loadDevice(StateInput.Record in) throws IOException {
        requests = in.readInt("echo requests", 0, Integer.MAX_VALUE);
    }
}
