package com.nailem.streamport.airplay;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal binary property list ("bplist00") reader and writer.
 * <p>
 * Every plist we parse comes from the network, so the reader is strict and bounded: the object count, nesting
 * depth, container sizes and every offset are checked before use, and a malformed list throws
 * {@link IllegalArgumentException} instead of reading out of bounds or allocating without limit.
 * <p>
 * Values map to Java as: dict → {@code Map<String,Object>}, array → {@code List<Object>}, integer → {@code Long},
 * real → {@code Double}, bool → {@code Boolean}, data → {@code byte[]}, string → {@code String}. Dates, UIDs and
 * sets are rejected; AirPlay mirroring never sends them.
 */
public final class BPlist {

    static final int MAX_BYTES = 256 * 1024;
    static final int MAX_OBJECTS = 4096;
    static final int MAX_DEPTH = 16;
    static final int MAX_CONTAINER = 1024;

    private final byte[] buf;
    private final int refSize;
    private final int numObjects;
    private final long[] offsets;
    private int visited;

    private BPlist(byte[] buf, int refSize, int numObjects, long[] offsets) {
        this.buf = buf;
        this.refSize = refSize;
        this.numObjects = numObjects;
        this.offsets = offsets;
    }

    /** Parses a whole binary plist. Throws {@link IllegalArgumentException} on anything malformed. */
    public static Object parse(byte[] data) {
        if (data == null || data.length < 8 + 32 || data.length > MAX_BYTES) {
            throw new IllegalArgumentException("bplist: bad size");
        }
        if (!new String(data, 0, 8, StandardCharsets.US_ASCII).equals("bplist00")) {
            throw new IllegalArgumentException("bplist: bad magic");
        }
        int t = data.length - 32;
        int offsetSize = data[t + 6] & 0xFF;
        int refSize = data[t + 7] & 0xFF;
        long numObjects = readBE(data, t + 8, 8);
        long top = readBE(data, t + 16, 8);
        long tableOffset = readBE(data, t + 24, 8);
        if (offsetSize < 1 || offsetSize > 8 || refSize < 1 || refSize > 8) {
            throw new IllegalArgumentException("bplist: bad int sizes");
        }
        if (numObjects < 1 || numObjects > MAX_OBJECTS || top < 0 || top >= numObjects) {
            throw new IllegalArgumentException("bplist: bad object count");
        }
        // Written so nothing can overflow: tableOffset comes straight from the input.
        if (tableOffset < 8 || tableOffset > t || numObjects * offsetSize > t - tableOffset) {
            throw new IllegalArgumentException("bplist: bad offset table");
        }
        long[] offsets = new long[(int) numObjects];
        for (int i = 0; i < numObjects; i++) {
            long off = readBE(data, (int) tableOffset + i * offsetSize, offsetSize);
            if (off < 8 || off >= tableOffset) {
                throw new IllegalArgumentException("bplist: object offset out of range");
            }
            offsets[i] = off;
        }
        BPlist p = new BPlist(data, refSize, (int) numObjects, offsets);
        return p.object((int) top, 0, (int) tableOffset);
    }

    /** {@link #parse} that requires a dictionary at the top. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseDict(byte[] data) {
        Object o = parse(data);
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("bplist: top object is not a dict");
        }
        return (Map<String, Object>) o;
    }

    private Object object(int index, int depth, int end) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("bplist: nested too deep");
        }
        // Shared references are legal but a list that points back at itself would recurse until MAX_DEPTH on
        // every path; the visit budget keeps a wide fan-out of shared objects from going exponential.
        if (++visited > MAX_OBJECTS * 4) {
            throw new IllegalArgumentException("bplist: too many references");
        }
        int pos = (int) offsets[index];
        int marker = buf[pos] & 0xFF;
        int type = marker >> 4;
        int info = marker & 0x0F;
        switch (type) {
            case 0x0:
                if (marker == 0x08) return Boolean.FALSE;
                if (marker == 0x09) return Boolean.TRUE;
                if (marker == 0x00) return null;
                throw new IllegalArgumentException("bplist: bad simple object");
            case 0x1: {
                if (info > 4) throw new IllegalArgumentException("bplist: int too wide");
                int n = 1 << info;
                check(pos + 1, n, end);
                // 16-byte ints carry unsigned 64-bit values; the low 8 bytes hold them.
                // Ints shorter than 8 bytes are unsigned; an 8-byte int is two's complement, which readBE already gives.
                return n == 16 ? readBE(buf, pos + 1 + 8, 8) : readBE(buf, pos + 1, n);
            }
            case 0x2: {
                if (info == 2) {
                    check(pos + 1, 4, end);
                    return (double) Float.intBitsToFloat((int) readBE(buf, pos + 1, 4));
                }
                if (info == 3) {
                    check(pos + 1, 8, end);
                    return Double.longBitsToDouble(readBE(buf, pos + 1, 8));
                }
                throw new IllegalArgumentException("bplist: bad real");
            }
            case 0x4: {
                int[] lo = length(pos, info, end);
                check(lo[1], lo[0], end);
                byte[] out = new byte[lo[0]];
                System.arraycopy(buf, lo[1], out, 0, lo[0]);
                return out;
            }
            case 0x5: {
                int[] lo = length(pos, info, end);
                check(lo[1], lo[0], end);
                return new String(buf, lo[1], lo[0], StandardCharsets.US_ASCII);
            }
            case 0x6: {
                int[] lo = length(pos, info, end);
                if (lo[0] > MAX_BYTES / 2) throw new IllegalArgumentException("bplist: string too long");
                check(lo[1], lo[0] * 2, end);
                return new String(buf, lo[1], lo[0] * 2, StandardCharsets.UTF_16BE);
            }
            case 0xA: {
                int[] lo = length(pos, info, end);
                if (lo[0] > MAX_CONTAINER) throw new IllegalArgumentException("bplist: array too big");
                check(lo[1], lo[0] * refSize, end);
                List<Object> list = new ArrayList<>(lo[0]);
                for (int i = 0; i < lo[0]; i++) {
                    list.add(object(ref(lo[1] + i * refSize), depth + 1, end));
                }
                return list;
            }
            case 0xD: {
                int[] lo = length(pos, info, end);
                if (lo[0] > MAX_CONTAINER) throw new IllegalArgumentException("bplist: dict too big");
                check(lo[1], lo[0] * refSize * 2, end);
                Map<String, Object> map = new LinkedHashMap<>();
                for (int i = 0; i < lo[0]; i++) {
                    Object key = object(ref(lo[1] + i * refSize), depth + 1, end);
                    if (!(key instanceof String)) throw new IllegalArgumentException("bplist: non-string key");
                    Object value = object(ref(lo[1] + (lo[0] + i) * refSize), depth + 1, end);
                    map.put((String) key, value);
                }
                return map;
            }
            default:
                throw new IllegalArgumentException("bplist: unsupported object type " + type);
        }
    }

    /** Returns {length, dataStart} for a sized object, reading the extended int length when info == 0xF. */
    private int[] length(int pos, int info, int end) {
        if (info != 0x0F) {
            return new int[]{info, pos + 1};
        }
        check(pos + 1, 1, end);
        int marker = buf[pos + 1] & 0xFF;
        if ((marker >> 4) != 0x1 || (marker & 0x0F) > 3) {
            throw new IllegalArgumentException("bplist: bad extended length");
        }
        int n = 1 << (marker & 0x0F);
        check(pos + 2, n, end);
        long len = readBE(buf, pos + 2, n);
        if (len < 0 || len > MAX_BYTES) {
            throw new IllegalArgumentException("bplist: length out of range");
        }
        return new int[]{(int) len, pos + 2 + n};
    }

    private int ref(int pos) {
        long r = readBE(buf, pos, refSize);
        if (r < 0 || r >= numObjects) {
            throw new IllegalArgumentException("bplist: bad object reference");
        }
        return (int) r;
    }

    private static void check(int start, long len, int end) {
        if (start < 0 || len < 0 || start + len > end) {
            throw new IllegalArgumentException("bplist: object runs past the end");
        }
    }

    private static long readBE(byte[] b, int pos, int n) {
        long v = 0;
        for (int i = 0; i < n; i++) {
            v = (v << 8) | (b[pos + i] & 0xFF);
        }
        return v;
    }

    // ---- writer ----

    /** Serialises maps, lists, numbers, booleans, strings and byte arrays to a binary plist. */
    public static byte[] write(Object root) {
        List<Node> nodes = new ArrayList<>();
        node(root, nodes, 0);
        int refSize = nodes.size() < 256 ? 1 : 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] magic = "bplist00".getBytes(StandardCharsets.US_ASCII);
        out.write(magic, 0, magic.length);
        long[] offsets = new long[nodes.size()];
        for (Node n : nodes) {
            offsets[n.id] = out.size();
            writeNode(out, n, refSize);
        }
        long tableOffset = out.size();
        int offsetSize = tableOffset < 256 ? 1 : tableOffset < 65536 ? 2 : 4;
        for (long off : offsets) {
            writeBE(out, off, offsetSize);
        }
        for (int i = 0; i < 6; i++) out.write(0);
        out.write(offsetSize);
        out.write(refSize);
        writeBE(out, nodes.size(), 8);
        writeBE(out, 0, 8);
        writeBE(out, tableOffset, 8);
        return out.toByteArray();
    }

    private static final class Node {
        final int id;
        final Object value;
        final List<Node> keys = new ArrayList<>();
        final List<Node> values = new ArrayList<>();

        Node(int id, Object value) {
            this.id = id;
            this.value = value;
        }
    }

    private static Node node(Object o, List<Node> nodes, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("bplist: nested too deep");
        Node n = new Node(nodes.size(), o);
        nodes.add(n);
        if (o instanceof Map) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                Node k = new Node(nodes.size(), String.valueOf(e.getKey()));
                nodes.add(k);
                n.keys.add(k);
            }
            for (Object v : ((Map<?, ?>) o).values()) {
                n.values.add(node(v, nodes, depth + 1));
            }
        } else if (o instanceof List) {
            for (Object v : (List<?>) o) {
                n.values.add(node(v, nodes, depth + 1));
            }
        }
        return n;
    }

    private static void writeNode(ByteArrayOutputStream out, Node n, int refSize) {
        Object v = n.value;
        if (v instanceof Map) {
            writeMarker(out, 0xD, n.keys.size());
            for (Node k : n.keys) writeBE(out, k.id, refSize);
            for (Node c : n.values) writeBE(out, c.id, refSize);
        } else if (v instanceof List) {
            writeMarker(out, 0xA, n.values.size());
            for (Node c : n.values) writeBE(out, c.id, refSize);
        } else if (v == null) {
            out.write(0x00);
        } else if (v instanceof Boolean) {
            out.write((Boolean) v ? 0x09 : 0x08);
        } else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            long x = ((Number) v).longValue();
            if (x >= 0 && x < 256) {
                out.write(0x10);
                writeBE(out, x, 1);
            } else if (x >= 0 && x < 65536) {
                out.write(0x11);
                writeBE(out, x, 2);
            } else if (x >= 0 && x < 0x100000000L) {
                out.write(0x12);
                writeBE(out, x, 4);
            } else {
                out.write(0x13);
                writeBE(out, x, 8);
            }
        } else if (v instanceof Double || v instanceof Float) {
            out.write(0x23);
            writeBE(out, Double.doubleToLongBits(((Number) v).doubleValue()), 8);
        } else if (v instanceof byte[]) {
            byte[] b = (byte[]) v;
            writeMarker(out, 0x4, b.length);
            out.write(b, 0, b.length);
        } else if (v instanceof String) {
            String s = (String) v;
            boolean ascii = true;
            for (int i = 0; i < s.length() && ascii; i++) ascii = s.charAt(i) < 0x80;
            byte[] b = s.getBytes(ascii ? StandardCharsets.US_ASCII : StandardCharsets.UTF_16BE);
            writeMarker(out, ascii ? 0x5 : 0x6, s.length());
            out.write(b, 0, b.length);
        } else {
            throw new IllegalArgumentException("bplist: cannot write " + v.getClass());
        }
    }

    private static void writeMarker(ByteArrayOutputStream out, int type, int len) {
        if (len < 15) {
            out.write((type << 4) | len);
            return;
        }
        out.write((type << 4) | 0xF);
        if (len < 256) {
            out.write(0x10);
            writeBE(out, len, 1);
        } else if (len < 65536) {
            out.write(0x11);
            writeBE(out, len, 2);
        } else {
            out.write(0x12);
            writeBE(out, len, 4);
        }
    }

    private static void writeBE(ByteArrayOutputStream out, long v, int n) {
        for (int i = n - 1; i >= 0; i--) {
            out.write((int) (v >>> (8 * i)) & 0xFF);
        }
    }
}
