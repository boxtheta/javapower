package org.boxtheta.javapower.papr;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Byte-addressed storage behind a SCSI target. All calls are synchronous for now and are made on
 * the CPU thread; the SCSI layer only talks to this interface and completes requests through a
 * callback, so an asynchronous implementation can later be dropped in behind the same boundary.
 *
 * <p>A backend is also the medium of a drive with removable media. The drive calls it only
 * while it is in the drive, one call at a time, and never again once
 * {@link ScsiDisk#changeMedium} has returned it; {@link #size} and {@link #readOnly} must stay
 * the same while it is in a drive.
 */
public interface BlockBackend extends AutoCloseable {
    /** Size in bytes. */
    long size();

    /** Whether writes are refused (the target reports DATA PROTECT / write protected). */
    boolean readOnly();

    void read(long offset, byte[] buf, int off, int len) throws IOException;

    void write(long offset, byte[] buf, int off, int len) throws IOException;

    void flush() throws IOException;

    @Override
    void close() throws IOException;

    /**
     * Open a raw image the way QEMU's {@code -drive format=raw} does.
     *
     * @param readOnly like {@code readonly=on}: the image is opened read-only and writes fail
     * @param snapshot like {@code snapshot=on}: the image is opened read-only and guest writes go
     *                 to a private copy-on-write overlay in a temporary file deleted on close
     */
    static BlockBackend open(Path file, boolean readOnly, boolean snapshot) throws IOException {
        if (snapshot) {
            return new SnapshotBackend(new RawFileBackend(file, true), true, readOnly);
        }
        return new RawFileBackend(file, readOnly);
    }
}
