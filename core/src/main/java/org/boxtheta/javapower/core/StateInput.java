package org.boxtheta.javapower.core;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.CRC32C;

/**
 * Reads a saved machine state written by {@link StateOutput} (which describes the format). The
 * constructor checks the header; {@link #record} reads the next record, checks its tag, length
 * and checksum, and returns its payload. Anything wrong with the input, including a short read
 * anywhere and a record whose payload is not read exactly to its end, is reported as a
 * {@link StateFormatException}; other {@link IOException}s come from the underlying stream.
 *
 * <p>Not thread-safe. The underlying stream is not closed here.
 */
public final class StateInput {
    private final DataInputStream in;
    private Record current;
    private boolean finished;

    /**
     * Start reading a state from {@code in}: reads and checks the header.
     *
     * @throws StateFormatException if the stream does not start with a javapower state header, or
     *     the state has a format version this release does not read
     */
    public StateInput(InputStream in) throws IOException {
        this.in = new DataInputStream(in);
        long magic;
        int version;
        try {
            magic = this.in.readLong();
            version = this.in.readInt();
        } catch (EOFException e) {
            throw new StateFormatException("not a javapower machine state: the input is too short", e);
        }
        if (magic != StateOutput.MAGIC) {
            throw new StateFormatException("not a javapower machine state (bad header)");
        }
        if (version != StateOutput.VERSION) {
            throw new StateFormatException("the machine state has format version " + version + "; this release reads"
                    + " version " + StateOutput.VERSION + " only");
        }
    }

    /**
     * Read the next record, which must be tagged {@code tag}, and return its payload.
     *
     * @throws StateFormatException if the input ends, the next record has another tag, its
     *     checksum does not match, or the previous record was not read to its end
     */
    public Record record(String tag) throws IOException {
        byte[] want = StateOutput.tagBytes(tag);
        if (finished) {
            throw new IllegalStateException("the state is finished");
        }
        closeCurrent();
        byte[] t = new byte[4];
        int len;
        byte[] payload;
        int crc;
        try {
            in.readFully(t);
            len = in.readInt();
            if (len < 0 || len > StateOutput.MAX_RECORD) {
                throw new StateFormatException("corrupt machine state: record " + printable(t) + " claims " + len
                        + " bytes");
            }
            payload = new byte[len];
            in.readFully(payload);
            crc = in.readInt();
        } catch (EOFException e) {
            throw new StateFormatException("truncated machine state: it ends before record " + tag, e);
        }
        CRC32C c = new CRC32C();
        c.update(t);
        c.update(StateOutput.intBytes(len));
        c.update(payload);
        if ((int) c.getValue() != crc) {
            throw new StateFormatException("corrupt machine state: checksum mismatch in record " + printable(t));
        }
        if (!java.util.Arrays.equals(t, want)) {
            throw new StateFormatException("corrupt machine state: expected record " + tag + ", found "
                    + printable(t));
        }
        current = new Record(tag, payload);
        return current;
    }

    /**
     * Read the end record: the state must end here.
     *
     * @throws StateFormatException if more records follow or the end record is missing
     */
    public void finish() throws IOException {
        if (finished) {
            return;
        }
        Record r = record(StateOutput.END);
        r.close();
        current = null;
        finished = true;
    }

    private void closeCurrent() throws StateFormatException {
        if (current != null) {
            current.close();
            current = null;
        }
    }

    private static String printable(byte[] t) {
        StringBuilder sb = new StringBuilder();
        for (byte b : t) {
            sb.append(b >= 0x20 && b < 0x7f ? (char) b : '?');
        }
        return sb.toString();
    }

    /**
     * The payload of one record. A read past its end throws {@link StateFormatException}. The
     * helpers read the encodings of {@link StateOutput#writeBytes} and
     * {@link StateOutput#writeString}, and check counts and values read from the state.
     */
    public static final class Record implements DataInput {
        private final String tag;
        private final DataInputStream d;
        private final ByteArrayInputStream bytes;

        Record(String tag, byte[] payload) {
            this.tag = tag;
            this.bytes = new ByteArrayInputStream(payload);
            this.d = new DataInputStream(bytes);
        }

        /** The record's tag. */
        public String tag() {
            return tag;
        }

        /** A {@link StateFormatException} naming this record. */
        public StateFormatException corrupt(String what) {
            return new StateFormatException("corrupt machine state: record " + tag + ": " + what);
        }

        /** Check that the payload was read to its end. */
        void close() throws StateFormatException {
            if (bytes.available() != 0) {
                throw corrupt(bytes.available() + " bytes left unread");
            }
        }

        private StateFormatException early(EOFException e) {
            return new StateFormatException("corrupt machine state: record " + tag + " ends early", e);
        }

        /** Read an int that must lie in {@code [min, max]}; {@code what} names it in the error. */
        public int readInt(String what, int min, int max) throws IOException {
            int v = readInt();
            if (v < min || v > max) {
                throw corrupt(what + " " + v + " outside " + min + ".." + max);
            }
            return v;
        }

        /** Read a byte array written by {@link StateOutput#writeBytes}, at most {@code max} bytes. */
        public byte[] readBytes(int max) throws IOException {
            int n = readInt();
            if (n == -1) {
                return null;
            }
            if (n < 0 || n > max || n > bytes.available()) {
                throw corrupt("byte array of length " + n);
            }
            byte[] b = new byte[n];
            readFully(b);
            return b;
        }

        /** Read a string written by {@link StateOutput#writeString}. */
        public String readString() throws IOException {
            return readBoolean() ? readUTF() : null;
        }

        @Override
        public void readFully(byte[] b) throws IOException {
            try {
                d.readFully(b);
            } catch (EOFException e) {
                throw early(e);
            }
        }

        @Override
        public void readFully(byte[] b, int off, int len) throws IOException {
            try {
                d.readFully(b, off, len);
            } catch (EOFException e) {
                throw early(e);
            }
        }

        @Override
        public int skipBytes(int n) throws IOException {
            return d.skipBytes(n);
        }

        @Override
        public boolean readBoolean() throws IOException {
            int v = readUnsignedByte();
            if (v > 1) {
                throw corrupt("boolean " + v);
            }
            return v != 0;
        }

        @Override
        public byte readByte() throws IOException {
            try {
                return d.readByte();
            } catch (EOFException e) {
                throw early(e);
            }
        }

        @Override
        public int readUnsignedByte() throws IOException {
            try {
                return d.readUnsignedByte();
            } catch (EOFException e) {
                throw early(e);
            }
        }

        @Override
        public short readShort() throws IOException {
            try {
                return d.readShort();
            } catch (EOFException e) {
                throw early(e);
            }
        }

        @Override
        public int readUnsignedShort() throws IOException {
            try {
                return d.readUnsignedShort();
            } catch (EOFException e) {
                throw early(e);
            }
        }

        @Override
        public char readChar() throws IOException {
            try {
                return d.readChar();
            } catch (EOFException e) {
                throw early(e);
            }
        }

        @Override
        public int readInt() throws IOException {
            try {
                return d.readInt();
            } catch (EOFException e) {
                throw early(e);
            }
        }

        @Override
        public long readLong() throws IOException {
            try {
                return d.readLong();
            } catch (EOFException e) {
                throw early(e);
            }
        }

        @Override
        public float readFloat() throws IOException {
            return Float.intBitsToFloat(readInt());
        }

        @Override
        public double readDouble() throws IOException {
            return Double.longBitsToDouble(readLong());
        }

        /** Not supported: the state holds no text lines. */
        @Override
        public String readLine() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String readUTF() throws IOException {
            try {
                return d.readUTF();
            } catch (EOFException e) {
                throw early(e);
            } catch (java.io.UTFDataFormatException e) {
                throw new StateFormatException("corrupt machine state: record " + tag + ": bad string", e);
            }
        }

        /** Bytes of the payload not read yet. */
        public int remaining() {
            return bytes.available();
        }
    }
}
