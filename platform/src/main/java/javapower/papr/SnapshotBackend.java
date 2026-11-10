package javapower.papr;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Copy-on-write overlay over a read-only base image, the equivalent of QEMU's {@code snapshot=on}:
 * reads come from the overlay where a cluster has been written and from the base otherwise;
 * writes never reach the base. The overlay lives in memory or in a temporary file that is deleted
 * on close.
 */
public final class SnapshotBackend implements BlockBackend {
    /** Overlay granularity. Partial-cluster writes copy the rest of the cluster up from the base. */
    public static final int CLUSTER = 64 * 1024;

    private final BlockBackend base;
    private final boolean readOnly;
    private final Map<Long, byte[]> memClusters;
    private final Map<Long, Long> fileIndex;
    private final FileChannel overlayFile;
    private final Path overlayPath;

    /**
     * @param useTempFile keep the overlay in a temporary file instead of the heap
     * @param readOnly    refuse guest writes entirely (snapshot of a read-only drive)
     */
    public SnapshotBackend(BlockBackend base, boolean useTempFile, boolean readOnly) throws IOException {
        this.base = base;
        this.readOnly = readOnly;
        if (useTempFile) {
            overlayPath = Files.createTempFile("javapower-snapshot", ".cow");
            overlayFile = FileChannel.open(overlayPath, StandardOpenOption.READ, StandardOpenOption.WRITE,
                    StandardOpenOption.DELETE_ON_CLOSE);
            fileIndex = new HashMap<>();
            memClusters = null;
        } else {
            overlayPath = null;
            overlayFile = null;
            fileIndex = null;
            memClusters = new HashMap<>();
        }
    }

    @Override
    public long size() {
        return base.size();
    }

    @Override
    public boolean readOnly() {
        return readOnly;
    }

    /** Number of clusters that have been copied up so far. */
    public int dirtyClusters() {
        return memClusters != null ? memClusters.size() : fileIndex.size();
    }

    private byte[] loadOverlay(long cluster) throws IOException {
        if (memClusters != null) {
            return memClusters.get(cluster);
        }
        Long off = fileIndex.get(cluster);
        if (off == null) {
            return null;
        }
        byte[] data = new byte[CLUSTER];
        ByteBuffer bb = ByteBuffer.wrap(data);
        long pos = off;
        while (bb.hasRemaining()) {
            int n = overlayFile.read(bb, pos);
            if (n < 0) {
                throw new EOFException("overlay truncated");
            }
            pos += n;
        }
        return data;
    }

    private void storeOverlay(long cluster, byte[] data) throws IOException {
        if (memClusters != null) {
            memClusters.put(cluster, data);
            return;
        }
        Long off = fileIndex.get(cluster);
        if (off == null) {
            off = (long) fileIndex.size() * CLUSTER;
            fileIndex.put(cluster, off);
        }
        ByteBuffer bb = ByteBuffer.wrap(data);
        long pos = off;
        while (bb.hasRemaining()) {
            pos += overlayFile.write(bb, pos);
        }
    }

    private int clusterLen(long cluster) {
        return (int) Math.min(CLUSTER, size() - cluster * CLUSTER);
    }

    @Override
    public void read(long offset, byte[] buf, int off, int len) throws IOException {
        if (offset < 0 || offset + len > size()) {
            throw new EOFException("read beyond end of image");
        }
        while (len > 0) {
            long cluster = offset / CLUSTER;
            int inCluster = (int) (offset % CLUSTER);
            int n = Math.min(len, CLUSTER - inCluster);
            byte[] ov = loadOverlay(cluster);
            if (ov != null) {
                System.arraycopy(ov, inCluster, buf, off, n);
            } else {
                base.read(offset, buf, off, n);
            }
            offset += n;
            off += n;
            len -= n;
        }
    }

    @Override
    public void write(long offset, byte[] buf, int off, int len) throws IOException {
        if (readOnly) {
            throw new IOException("read-only image");
        }
        if (offset < 0 || offset + len > size()) {
            throw new EOFException("write beyond end of image");
        }
        while (len > 0) {
            long cluster = offset / CLUSTER;
            int inCluster = (int) (offset % CLUSTER);
            int n = Math.min(len, CLUSTER - inCluster);
            byte[] ov = loadOverlay(cluster);
            if (ov == null) {
                ov = new byte[CLUSTER];
                base.read(cluster * CLUSTER, ov, 0, clusterLen(cluster));
            }
            System.arraycopy(buf, off, ov, inCluster, n);
            storeOverlay(cluster, ov);
            offset += n;
            off += n;
            len -= n;
        }
    }

    @Override
    public void flush() {
        // Nothing reaches stable storage in snapshot mode.
    }

    @Override
    public void close() throws IOException {
        try {
            base.close();
        } finally {
            if (overlayFile != null) {
                overlayFile.close();
                Files.deleteIfExists(overlayPath);
            }
        }
    }
}
