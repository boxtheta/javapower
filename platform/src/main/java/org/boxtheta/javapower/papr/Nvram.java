package org.boxtheta.javapower.papr;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The NVRAM ({@code nvram@71000000}, compatible "qemu,spapr-nvram"), accessed by the guest through
 * the nvram-fetch / nvram-store RTAS calls. 64 KiB of zeroes by default; optionally backed by a
 * file whose length gives the size (8 KiB .. 1 MiB) and which receives every store.
 */
public final class Nvram extends VioDevice {
    public static final int DEFAULT_SIZE = 64 * 1024;
    public static final int MIN_SIZE = 8 * 1024;
    public static final int MAX_SIZE = 1024 * 1024;

    private final byte[] data;
    private final FileChannel file;

    public Nvram() {
        this.data = new byte[DEFAULT_SIZE];
        this.file = null;
    }

    public Nvram(Path backing) throws IOException {
        this.file = FileChannel.open(backing, StandardOpenOption.READ, StandardOpenOption.WRITE);
        long len = file.size();
        if (len < MIN_SIZE || len > MAX_SIZE) {
            file.close();
            throw new IOException("spapr-nvram must be between 8 KiB and 1 MiB in size");
        }
        this.data = new byte[(int) len];
        ByteBuffer bb = ByteBuffer.wrap(data);
        long pos = 0;
        while (bb.hasRemaining()) {
            int n = file.read(bb, pos);
            if (n < 0) {
                break;
            }
            pos += n;
        }
    }

    public int size() {
        return data.length;
    }

    public byte[] contents() {
        return data;
    }

    @Override
    public String dtName() {
        return "nvram";
    }

    @Override
    public String dtType() {
        return "nvram";
    }

    @Override
    public String dtCompatible() {
        return "qemu,spapr-nvram";
    }

    @Override
    protected void addDeviceProps(Fdt.Node node) {
        node.setCell("#bytes", data.length);
    }

    /** Copy NVRAM bytes into guest memory. Returns false if the range is invalid. */
    boolean fetch(long offset, long buffer, long len) {
        if (offset + len < offset || offset + len > data.length || !machine.memory().contains(buffer, len)) {
            return false;
        }
        machine.memory().write(buffer, data, (int) offset, (int) len);
        return true;
    }

    /** Copy guest memory into NVRAM (and the backing file). Returns -1 on a bad range, 0 on an I/O error. */
    int store(long offset, long buffer, long len) {
        if (offset + len < offset || offset + len > data.length || !machine.memory().contains(buffer, len)) {
            return -1;
        }
        machine.memory().read(buffer, data, (int) offset, (int) len);
        if (file != null) {
            try {
                ByteBuffer bb = ByteBuffer.wrap(data, (int) offset, (int) len);
                long pos = offset;
                while (bb.hasRemaining()) {
                    pos += file.write(bb, pos);
                }
            } catch (IOException e) {
                log().once("nvram-write-failed", e.toString());
                return 0;
            }
        }
        return 1;
    }

    void close() throws IOException {
        if (file != null) {
            file.close();
        }
    }
}
