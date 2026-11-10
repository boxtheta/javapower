package javapower.papr;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Flattened device tree (DTB, version 17) model, writer and reader.
 *
 * <p>Nodes keep properties and children in insertion order and are serialized in exactly that
 * order, so a tree built in "final" order decompiles with {@code dtc} to the same text as QEMU's
 * blob. (libfdt prepends on {@code fdt_add_subnode}/new properties, which is why QEMU's source
 * builds things in reverse; we simply build them forwards.)
 *
 * <p>Blob layout matches what libfdt produces after {@code fdt_pack}: 40-byte header, memory
 * reservation map at 0x28, structure block, strings block.
 */
public final class Fdt {
    private Fdt() {}

    public static final int MAGIC = 0xd00dfeed;
    private static final int BEGIN_NODE = 1;
    private static final int END_NODE = 2;
    private static final int PROP = 3;
    private static final int NOP = 4;
    private static final int END = 9;
    public static final int HEADER_SIZE = 40;

    /** A device tree node. */
    public static final class Node {
        private final String name;
        private final LinkedHashMap<String, byte[]> props = new LinkedHashMap<>();
        private final List<Node> children = new ArrayList<>();

        public Node(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        public Map<String, byte[]> props() {
            return props;
        }

        public List<Node> children() {
            return children;
        }

        /** Append a new child (no check for duplicates). */
        public Node addChild(String childName) {
            Node n = new Node(childName);
            children.add(n);
            return n;
        }

        public Node addChild(Node n) {
            children.add(n);
            return n;
        }

        public Node child(String childName) {
            for (Node c : children) {
                if (c.name.equals(childName)) {
                    return c;
                }
            }
            return null;
        }

        /** Look up a node by absolute-style path relative to this node, e.g. "cpus/PowerPC,POWER9@0". */
        public Node find(String path) {
            Node cur = this;
            for (String part : path.split("/")) {
                if (part.isEmpty()) {
                    continue;
                }
                Node next = cur.child(part);
                if (next == null) {
                    // Allow unit-address-less lookup ("memory" finds "memory@0").
                    for (Node c : cur.children) {
                        int at = c.name.indexOf('@');
                        if (at >= 0 && c.name.substring(0, at).equals(part)) {
                            next = c;
                            break;
                        }
                    }
                }
                if (next == null) {
                    return null;
                }
                cur = next;
            }
            return cur;
        }

        /** Set a property; an existing property keeps its position (like libfdt's setprop). */
        public Node set(String prop, byte[] value) {
            props.put(prop, value);
            return this;
        }

        public Node setEmpty(String prop) {
            return set(prop, new byte[0]);
        }

        public Node setString(String prop, String value) {
            return set(prop, strz(value));
        }

        /** NUL-separated string list. */
        public Node setStrings(String prop, String... values) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            for (String v : values) {
                b.writeBytes(strz(v));
            }
            return set(prop, b.toByteArray());
        }

        public Node setCell(String prop, int value) {
            return set(prop, cells(value));
        }

        public Node setCells(String prop, int... values) {
            return set(prop, cells(values));
        }

        public Node setU64s(String prop, long... values) {
            ByteBuffer bb = ByteBuffer.allocate(values.length * 8);
            for (long v : values) {
                bb.putLong(v);
            }
            return set(prop, bb.array());
        }

        public byte[] get(String prop) {
            return props.get(prop);
        }

        public boolean has(String prop) {
            return props.containsKey(prop);
        }

        public int getCell(String prop, int index) {
            byte[] v = props.get(prop);
            return ByteBuffer.wrap(v).getInt(index * 4);
        }

        public String getString(String prop) {
            byte[] v = props.get(prop);
            if (v == null) {
                return null;
            }
            int len = 0;
            while (len < v.length && v[len] != 0) {
                len++;
            }
            return new String(v, 0, len, StandardCharsets.US_ASCII);
        }

        public void remove(String prop) {
            props.remove(prop);
        }
    }

    public static byte[] strz(String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        byte[] r = new byte[b.length + 1];
        System.arraycopy(b, 0, r, 0, b.length);
        return r;
    }

    public static byte[] cells(int... values) {
        ByteBuffer bb = ByteBuffer.allocate(values.length * 4);
        for (int v : values) {
            bb.putInt(v);
        }
        return bb.array();
    }

    /** A memory reservation map entry. */
    public record Reservation(long address, long size) {}

    /** Serialize with an empty reservation map and boot CPU 0. */
    public static byte[] serialize(Node root) {
        return serialize(root, List.of(), 0);
    }

    public static byte[] serialize(Node root, List<Reservation> reservations, int bootCpu) {
        ByteArrayOutputStream structs = new ByteArrayOutputStream();
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        Map<String, Integer> stringOffsets = new LinkedHashMap<>();
        writeNode(root, true, structs, strings, stringOffsets);
        putInt(structs, END);
        byte[] st = structs.toByteArray();
        byte[] str = strings.toByteArray();

        int rsvOff = HEADER_SIZE;
        int rsvSize = (reservations.size() + 1) * 16;
        int structOff = rsvOff + rsvSize;
        int stringsOff = structOff + st.length;
        int total = stringsOff + str.length;
        ByteBuffer bb = ByteBuffer.allocate(total);
        bb.putInt(MAGIC);
        bb.putInt(total);
        bb.putInt(structOff);
        bb.putInt(stringsOff);
        bb.putInt(rsvOff);
        bb.putInt(17);
        bb.putInt(16);
        bb.putInt(bootCpu);
        bb.putInt(str.length);
        bb.putInt(st.length);
        for (Reservation r : reservations) {
            bb.putLong(r.address());
            bb.putLong(r.size());
        }
        bb.putLong(0);
        bb.putLong(0);
        bb.put(st);
        bb.put(str);
        return bb.array();
    }

    private static void writeNode(Node n, boolean isRoot, ByteArrayOutputStream out, ByteArrayOutputStream strings,
                                  Map<String, Integer> offsets) {
        putInt(out, BEGIN_NODE);
        byte[] nm = strz(isRoot ? "" : n.name);
        out.writeBytes(nm);
        pad(out);
        for (Map.Entry<String, byte[]> p : n.props.entrySet()) {
            Integer off = offsets.get(p.getKey());
            if (off == null) {
                off = strings.size();
                strings.writeBytes(strz(p.getKey()));
                offsets.put(p.getKey(), off);
            }
            putInt(out, PROP);
            putInt(out, p.getValue().length);
            putInt(out, off);
            out.writeBytes(p.getValue());
            pad(out);
        }
        for (Node c : n.children) {
            writeNode(c, false, out, strings, offsets);
        }
        putInt(out, END_NODE);
    }

    private static void putInt(ByteArrayOutputStream o, int v) {
        o.write(v >>> 24);
        o.write(v >>> 16);
        o.write(v >>> 8);
        o.write(v);
    }

    private static void pad(ByteArrayOutputStream o) {
        while ((o.size() & 3) != 0) {
            o.write(0);
        }
    }

    /** Thrown when a blob is malformed. */
    public static final class FdtException extends RuntimeException {
        public FdtException(String msg) {
            super(msg);
        }
    }

    /** Total size field of a blob header, or -1 if the magic is wrong. */
    public static int totalSize(byte[] header) {
        ByteBuffer bb = ByteBuffer.wrap(header);
        if (bb.getInt(0) != MAGIC) {
            return -1;
        }
        return bb.getInt(4);
    }

    /** Parse a blob into a node tree. Validates the structure (like libfdt's fdt_check_full). */
    public static Node parse(byte[] blob) {
        if (blob.length < HEADER_SIZE) {
            throw new FdtException("blob too small");
        }
        ByteBuffer bb = ByteBuffer.wrap(blob);
        if (bb.getInt(0) != MAGIC) {
            throw new FdtException("bad magic");
        }
        int total = bb.getInt(4);
        int structOff = bb.getInt(8);
        int stringsOff = bb.getInt(12);
        int version = bb.getInt(20);
        int stringsSize = bb.getInt(32);
        int structSize = version >= 17 ? bb.getInt(36) : stringsOff - structOff;
        if (total > blob.length || structOff < HEADER_SIZE || structOff + structSize > total
                || stringsOff + stringsSize > total || version < 16) {
            throw new FdtException("bad header");
        }
        int[] pos = {structOff};
        int end = structOff + structSize;
        int tag = nextTag(bb, pos, end);
        if (tag != BEGIN_NODE) {
            throw new FdtException("expected root node");
        }
        Node root = readNode(bb, pos, end, stringsOff, stringsSize);
        tag = nextTag(bb, pos, end);
        if (tag != END) {
            throw new FdtException("expected FDT_END");
        }
        return root;
    }

    private static int nextTag(ByteBuffer bb, int[] pos, int end) {
        while (true) {
            if (pos[0] + 4 > end) {
                throw new FdtException("truncated structure block");
            }
            int t = bb.getInt(pos[0]);
            pos[0] += 4;
            if (t != NOP) {
                return t;
            }
        }
    }

    private static String readStr(ByteBuffer bb, int off, int limit) {
        int e = off;
        while (e < limit && bb.get(e) != 0) {
            e++;
        }
        if (e >= limit) {
            throw new FdtException("unterminated string");
        }
        byte[] b = new byte[e - off];
        bb.get(off, b);
        return new String(b, StandardCharsets.US_ASCII);
    }

    private static Node readNode(ByteBuffer bb, int[] pos, int end, int stringsOff, int stringsSize) {
        String name = readStr(bb, pos[0], end);
        pos[0] = (pos[0] + name.length() + 1 + 3) & ~3;
        Node n = new Node(name);
        while (true) {
            int tag = nextTag(bb, pos, end);
            switch (tag) {
                case PROP -> {
                    int len = bb.getInt(pos[0]);
                    int nameOff = bb.getInt(pos[0] + 4);
                    pos[0] += 8;
                    if (len < 0 || pos[0] + len > end || nameOff < 0 || nameOff >= stringsSize) {
                        throw new FdtException("bad property");
                    }
                    byte[] v = new byte[len];
                    bb.get(pos[0], v);
                    pos[0] = (pos[0] + len + 3) & ~3;
                    n.props.put(readStr(bb, stringsOff + nameOff, stringsOff + stringsSize), v);
                }
                case BEGIN_NODE -> n.children.add(readNode(bb, pos, end, stringsOff, stringsSize));
                case END_NODE -> {
                    return n;
                }
                default -> throw new FdtException("unexpected tag " + tag);
            }
        }
    }

    /** Render a tree in a dtc-like text form (for debugging and diffs). */
    public static String toText(Node root) {
        StringBuilder sb = new StringBuilder();
        textNode(root, 0, sb, true);
        return sb.toString();
    }

    private static void textNode(Node n, int depth, StringBuilder sb, boolean root) {
        String ind = "\t".repeat(depth);
        sb.append(ind).append(root ? "/" : n.name).append(" {\n");
        for (Map.Entry<String, byte[]> p : n.props.entrySet()) {
            sb.append(ind).append('\t').append(p.getKey());
            byte[] v = p.getValue();
            if (v.length > 0) {
                sb.append(" = ");
                StringBuilder hex = new StringBuilder();
                for (byte b : v) {
                    hex.append(String.format("%02x", b & 0xff));
                }
                sb.append('[').append(hex).append(']');
            }
            sb.append(";\n");
        }
        for (Node c : n.children) {
            textNode(c, depth + 1, sb, false);
        }
        sb.append(ind).append("};\n");
    }
}
