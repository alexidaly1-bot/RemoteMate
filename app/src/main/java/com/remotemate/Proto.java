package com.remotemate;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Minimal protobuf writer/reader, enough for the Android TV remote protocol. */
final class Proto {
    private Proto() {}

    static final class W {
        private final ByteArrayOutputStream b = new ByteArrayOutputStream();

        W num(int field, long v) { tag(field, 0); varint(b, v); return this; }

        W bytes(int field, byte[] d) { tag(field, 2); varint(b, d.length); b.write(d, 0, d.length); return this; }

        W str(int field, String s) { return bytes(field, s.getBytes(StandardCharsets.UTF_8)); }

        W msg(int field, W m) { return bytes(field, m.toBytes()); }

        private void tag(int field, int wire) { varint(b, ((long) field << 3) | wire); }

        byte[] toBytes() { return b.toByteArray(); }
    }

    static final class R {
        private final Map<Integer, List<Object>> f = new HashMap<>();

        R(byte[] d) {
            int[] p = {0};
            while (p[0] < d.length) {
                long tag = readVarint(d, p);
                int field = (int) (tag >>> 3), wire = (int) (tag & 7);
                Object v = null;
                switch (wire) {
                    case 0: v = readVarint(d, p); break;
                    case 1: p[0] += 8; break;
                    case 2: {
                        int n = (int) readVarint(d, p);
                        v = Arrays.copyOfRange(d, p[0], p[0] + n);
                        p[0] += n;
                        break;
                    }
                    case 5: p[0] += 4; break;
                    default: throw new IllegalArgumentException("Bad wire type " + wire);
                }
                if (v != null) f.computeIfAbsent(field, k -> new ArrayList<>()).add(v);
            }
        }

        boolean has(int field) { return f.containsKey(field); }

        long num(int field) {
            List<Object> l = f.get(field);
            return l == null || !(l.get(0) instanceof Long) ? 0 : (Long) l.get(0);
        }

        byte[] bytes(int field) {
            List<Object> l = f.get(field);
            return l == null || !(l.get(0) instanceof byte[]) ? new byte[0] : (byte[]) l.get(0);
        }

        String str(int field) { return new String(bytes(field), StandardCharsets.UTF_8); }

        R msg(int field) { return new R(bytes(field)); }
    }

    static void varint(ByteArrayOutputStream b, long v) {
        while ((v & ~0x7FL) != 0) { b.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
        b.write((int) v);
    }

    static long readVarint(byte[] d, int[] p) {
        long r = 0;
        for (int s = 0; s < 64; s += 7) {
            int c = d[p[0]++] & 0xff;
            r |= (long) (c & 0x7f) << s;
            if ((c & 0x80) == 0) return r;
        }
        throw new IllegalArgumentException("Bad varint");
    }

    /** Writes one length-prefixed message. */
    static void writeFrame(OutputStream out, byte[] msg) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(msg.length + 5);
        varint(b, msg.length);
        b.write(msg, 0, msg.length);
        out.write(b.toByteArray());
        out.flush();
    }

    /** Reads one length-prefixed message (blocking). */
    static byte[] readFrame(InputStream in) throws IOException {
        long len = 0;
        for (int s = 0; ; s += 7) {
            int c = in.read();
            if (c < 0) throw new EOFException("Connection closed");
            len |= (long) (c & 0x7f) << s;
            if ((c & 0x80) == 0) break;
            if (s > 28) throw new IOException("Bad length");
        }
        if (len > 4 * 1024 * 1024) throw new IOException("Message too large");
        byte[] d = new byte[(int) len];
        int got = 0;
        while (got < d.length) {
            int n = in.read(d, got, d.length - got);
            if (n < 0) throw new EOFException("Connection closed");
            got += n;
        }
        return d;
    }
}
