package javapower.papr;

import java.io.EOFException;
import java.io.IOException;

/** A block backend held entirely in a byte array (tests, small scratch disks). */
public final class MemoryBackend implements BlockBackend {
    private final byte[] data;
    private final boolean readOnly;
    private int flushes;

    public MemoryBackend(byte[] data, boolean readOnly) {
        this.data = data;
        this.readOnly = readOnly;
    }

    public MemoryBackend(int size) {
        this(new byte[size], false);
    }

    public byte[] data() {
        return data;
    }

    public int flushes() {
        return flushes;
    }

    @Override
    public long size() {
        return data.length;
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public void read(long offset, byte[] buf, int off, int len) throws IOException {
        if (offset < 0 || offset + len > data.length) {
            throw new EOFException("read beyond end");
        }
        System.arraycopy(data, (int) offset, buf, off, len);
    }

    @Override
    public void write(long offset, byte[] buf, int off, int len) throws IOException {
        if (readOnly) {
            throw new IOException("read-only");
        }
        if (offset < 0 || offset + len > data.length) {
            throw new EOFException("write beyond end");
        }
        System.arraycopy(buf, off, data, (int) offset, len);
    }

    @Override
    public void flush() {
        flushes++;
    }

    @Override
    public void close() {
    }
}
