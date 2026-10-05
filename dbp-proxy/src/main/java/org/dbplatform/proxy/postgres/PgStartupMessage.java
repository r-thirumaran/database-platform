package org.dbplatform.proxy.postgres;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The first message a PostgreSQL client sends: {@code i32 length, i32 code, ...}. The code is either a
 * special request (SSLRequest, GSSENCRequest, CancelRequest) or the protocol version of a
 * StartupMessage (major 3), whose body is {@code key\0value\0 ... \0}.
 */
public final class PgStartupMessage {
    public static final int SSL_REQUEST = 80877103;
    public static final int GSSENC_REQUEST = 80877104;
    public static final int CANCEL_REQUEST = 80877102;
    public static final int PROTOCOL_3_0 = 196608;
    /** Defensive upper bound; real startup messages are a few hundred bytes. PostgreSQL itself caps at 10000. */
    public static final int MAX_LENGTH = 10_000;

    private final int code;
    private final byte[] raw;
    private final LinkedHashMap<String, String> params;

    private PgStartupMessage(int code, byte[] raw, LinkedHashMap<String, String> params) {
        this.code = code;
        this.raw = raw;
        this.params = params;
    }

    /** Read one length-prefixed message. Returns {@code null} on EOF before the first byte. */
    public static PgStartupMessage read(InputStream in) throws IOException {
        byte[] head = new byte[8];
        int n = readFully(in, head, 0, 8);
        if (n == 0) {
            return null;
        }
        if (n < 8) {
            throw new EOFException("Truncated PostgreSQL startup header");
        }
        int length = i32(head, 0);
        if (length < 8 || length > MAX_LENGTH) {
            throw new PgProtocolException("Invalid startup message length " + length);
        }
        byte[] raw = new byte[length];
        System.arraycopy(head, 0, raw, 0, 8);
        int got = readFully(in, raw, 8, length - 8);
        if (got < length - 8) {
            throw new EOFException("Truncated PostgreSQL startup message");
        }
        return parse(raw);
    }

    public static PgStartupMessage parse(byte[] raw) {
        if (raw.length < 8) {
            throw new PgProtocolException("Startup message too short");
        }
        int code = i32(raw, 4);
        LinkedHashMap<String, String> params = null;
        if (isStartup(code)) {
            params = new LinkedHashMap<>();
            int pos = 8;
            while (pos < raw.length) {
                if (raw[pos] == 0) {
                    break;
                }
                int kEnd = indexOfNul(raw, pos);
                String key = new String(raw, pos, kEnd - pos, StandardCharsets.UTF_8);
                pos = kEnd + 1;
                int vEnd = indexOfNul(raw, pos);
                String value = new String(raw, pos, vEnd - pos, StandardCharsets.UTF_8);
                pos = vEnd + 1;
                params.put(key, value);
            }
        }
        return new PgStartupMessage(code, raw, params);
    }

    private static boolean isStartup(int code) {
        return (code >>> 16) == 3;
    }

    private static int indexOfNul(byte[] b, int from) {
        for (int i = from; i < b.length; i++) {
            if (b[i] == 0) {
                return i;
            }
        }
        throw new PgProtocolException("Unterminated string in startup message");
    }

    public int code() {
        return code;
    }

    public boolean isSslRequest() {
        return code == SSL_REQUEST;
    }

    public boolean isGssEncRequest() {
        return code == GSSENC_REQUEST;
    }

    public boolean isCancelRequest() {
        return code == CANCEL_REQUEST;
    }

    public boolean isStartup() {
        return params != null;
    }

    public int protocolMajor() {
        return code >>> 16;
    }

    public int protocolMinor() {
        return code & 0xFFFF;
    }

    /** Raw bytes as received (length prefix included). */
    public byte[] raw() {
        return raw;
    }

    /** Startup parameters in wire order; {@code null} for non-startup messages. */
    public Map<String, String> params() {
        return params;
    }

    public String param(String key) {
        return params == null ? null : params.get(key);
    }

    public String user() {
        return param("user");
    }

    public String database() {
        String db = param("database");
        return db == null || db.isEmpty() ? user() : db;
    }

    public boolean hasExplicitDatabase() {
        String db = param("database");
        return db != null && !db.isEmpty();
    }

    public String applicationName() {
        return param("application_name");
    }

    /** Cancel request process id (only meaningful for {@link #isCancelRequest()}). */
    public int cancelPid() {
        return raw.length >= 16 ? i32(raw, 8) : 0;
    }

    /** Copy of this startup message with one parameter replaced/added; length is recomputed. */
    public PgStartupMessage withParam(String key, String value) {
        if (params == null) {
            throw new IllegalStateException("Not a StartupMessage");
        }
        LinkedHashMap<String, String> p = new LinkedHashMap<>(params);
        p.put(key, value);
        return build(code, p);
    }

    public static PgStartupMessage build(int protocolCode, Map<String, String> params) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[8], 0, 8);
        for (Map.Entry<String, String> e : params.entrySet()) {
            byte[] k = e.getKey().getBytes(StandardCharsets.UTF_8);
            byte[] v = e.getValue().getBytes(StandardCharsets.UTF_8);
            out.write(k, 0, k.length);
            out.write(0);
            out.write(v, 0, v.length);
            out.write(0);
        }
        out.write(0);
        byte[] raw = out.toByteArray();
        putI32(raw, 0, raw.length);
        putI32(raw, 4, protocolCode);
        return new PgStartupMessage(protocolCode, raw, new LinkedHashMap<>(params));
    }

    public List<String> paramKeys() {
        return params == null ? List.of() : new ArrayList<>(params.keySet());
    }

    static int readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int r = in.read(buf, off + total, len - total);
            if (r < 0) {
                return total;
            }
            total += r;
        }
        return total;
    }

    public static int i32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    public static void putI32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }

    @Override
    public String toString() {
        if (isSslRequest()) {
            return "SSLRequest";
        }
        if (isGssEncRequest()) {
            return "GSSENCRequest";
        }
        if (isCancelRequest()) {
            return "CancelRequest{pid=" + cancelPid() + "}";
        }
        return "StartupMessage{protocol=" + protocolMajor() + "." + protocolMinor() + ", params=" + params + "}";
    }
}
