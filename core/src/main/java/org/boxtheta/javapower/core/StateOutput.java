package org.boxtheta.javapower.core;

import java.io.ByteArrayOutputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;

/**
 * Writes a saved machine state: a header, then a sequence of records, then an end record.
 * Each module writes the records for its own part of the machine (see {@link StateInput} for the
 * reading side and the format).
 *
 * <h2>Format</h2>
 * All numbers are big-endian. The header is the 8 bytes {@code "JPWRSTAT"} followed by the format
 * version ({@link #VERSION}) as a 32-bit integer. Each record is a 4-character ASCII tag, the
 * payload length as a 32-bit integer (at most {@link #MAX_RECORD} bytes), the payload, and a
 * CRC-32C of the tag, length and payload. The state ends with a record tagged {@code "END."}
 * with an empty payload. Records are written whole, so a state can be streamed through a
 * compressor; guest RAM is split into records of at most 1 MiB (see
 * {@link PhysicalMemory#saveState}).
 *
 * <p>Not thread-safe. The underlying stream is neither buffered nor closed here; pass a buffered
 * stream for speed.
 */
public final class StateOutput {
    /** The first 8 bytes of every state: {@code "JPWRSTAT"}. */
    public static final long MAGIC = 0x4a50575253544154L;
    /** The format version this release writes and reads. */
    public static final int VERSION = 1;
    /** Largest record payload, in bytes. */
    public static final int MAX_RECORD = 64 << 20;
    static final String END = "END.";

    /** Writes the payload of one record. */
    @FunctionalInterface
    public interface Body {
        void write(DataOutput out) throws IOException;
    }

    private final DataOutputStream out;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream(1 << 16);
    private final DataOutputStream body = new DataOutputStream(buf);
    private boolean inRecord;
    private boolean finished;

    /** Start a state on {@code out}: writes the header. */
    public StateOutput(OutputStream out) throws IOException {
        this.out = new DataOutputStream(out);
        this.out.writeLong(MAGIC);
        this.out.writeInt(VERSION);
    }

    /**
     * Write one record: {@code body} writes the payload, which is then framed and written out.
     *
     * @param tag 4 ASCII characters naming the record
     * @throws IllegalArgumentException if the tag is not 4 ASCII characters or the payload is
     *     larger than {@link #MAX_RECORD}
     */
    public void record(String tag, Body body) throws IOException {
        byte[] t = tagBytes(tag);
        if (inRecord || finished) {
            throw new IllegalStateException(finished ? "the state is finished" : "records do not nest");
        }
        inRecord = true;
        try {
            buf.reset();
            body.write(this.body);
            this.body.flush();
            if (buf.size() > MAX_RECORD) {
                throw new IllegalArgumentException("record " + tag + " is larger than " + MAX_RECORD + " bytes");
            }
            writeFramed(t, buf.toByteArray());
        } finally {
            inRecord = false;
        }
    }

    /** Write the end record and flush the stream (which stays open). */
    public void finish() throws IOException {
        if (finished) {
            return;
        }
        writeFramed(tagBytes(END), new byte[0]);
        finished = true;
        out.flush();
    }

    private void writeFramed(byte[] tag, byte[] payload) throws IOException {
        CRC32C crc = new CRC32C();
        crc.update(tag);
        crc.update(intBytes(payload.length));
        crc.update(payload);
        out.write(tag);
        out.writeInt(payload.length);
        out.write(payload);
        out.writeInt((int) crc.getValue());
    }

    static byte[] intBytes(int v) {
        return new byte[] {(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    static byte[] tagBytes(String tag) {
        byte[] t = tag.getBytes(StandardCharsets.US_ASCII);
        if (t.length != 4 || tag.length() != 4 || !tag.chars().allMatch(c -> c >= 0x20 && c < 0x7f)) {
            throw new IllegalArgumentException("record tag must be 4 ASCII characters: \"" + tag + "\"");
        }
        return t;
    }

    // ---- helpers for record bodies ------------------------------------------------------------

    /** Write a byte array with its length (null as length -1). */
    public static void writeBytes(DataOutput out, byte[] b) throws IOException {
        if (b == null) {
            out.writeInt(-1);
            return;
        }
        out.writeInt(b.length);
        out.write(b);
    }

    /** Write a string with a presence flag (null allowed), in modified UTF-8. */
    public static void writeString(DataOutput out, String s) throws IOException {
        out.writeBoolean(s != null);
        if (s != null) {
            out.writeUTF(s);
        }
    }
}
