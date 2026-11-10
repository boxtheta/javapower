package javapower.papr;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** A raw image file accessed with positional I/O. */
public final class RawFileBackend implements BlockBackend {
    private final FileChannel ch;
    private final boolean readOnly;
    private final long size;

    public RawFileBackend(Path file, boolean readOnly) throws IOException {
        this.readOnly = readOnly;
        this.ch = readOnly ? FileChannel.open(file, StandardOpenOption.READ)
                : FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.size = ch.size();
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }

    @Override
    public void read(long offset, byte[] buf, int off, int len) throws IOException {
        if (offset < 0 || offset + len > size) {
            throw new EOFException("read beyond end of image");
        }
        ByteBuffer bb = ByteBuffer.wrap(buf, off, len);
        long pos = offset;
        while (bb.hasRemaining()) {
            int n = ch.read(bb, pos);
            if (n < 0) {
                throw new EOFException("short read");
            }
            pos += n;
        }
    }

    @Override
    public void write(long offset, byte[] buf, int off, int len) throws IOException {
        if (readOnly) {
            throw new IOException("read-only image");
        }
        if (offset < 0 || offset + len > size) {
            throw new EOFException("write beyond end of image");
        }
        ByteBuffer bb = ByteBuffer.wrap(buf, off, len);
        long pos = offset;
        while (bb.hasRemaining()) {
            pos += ch.write(bb, pos);
        }
    }

    @Override
    public void flush() throws IOException {
        if (!readOnly) {
            ch.force(false);
        }
    }

    @Override
    public void close() throws IOException {
        ch.close();
    }
}
