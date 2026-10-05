package org.dbplatform.proxy.oracle;

import java.nio.charset.StandardCharsets;

/**
 * TNS REDIRECT (type 5): body is {@code u16 dataLength} followed by an address string such as
 * {@code (ADDRESS=(PROTOCOL=tcp)(HOST=h)(PORT=p))}, optionally followed by a NUL byte and a
 * replacement connect string the client must send to the new address. When {@code dataLength}
 * exceeds the bytes present in the packet the data follows in a DATA packet (same deferral rule as
 * CONNECT) — {@link #needsData()} tells the caller to read one more packet.
 */
public final class TnsRedirectPacket {
    private final int dataLength;
    private final String address;
    private final String connectData;
    private final boolean needsData;

    private TnsRedirectPacket(int dataLength, String address, String connectData, boolean needsData) {
        this.dataLength = dataLength;
        this.address = address;
        this.connectData = connectData;
        this.needsData = needsData;
    }

    public static TnsRedirectPacket parse(TnsPacket p) {
        if (p.type() != TnsPacket.TYPE_REDIRECT) {
            throw new TnsParseException("Not a REDIRECT packet: type " + p.type());
        }
        byte[] body = p.body();
        if (body.length < 2) {
            throw new TnsParseException("REDIRECT body too short");
        }
        int len = TnsPacket.u16(body, 0);
        int avail = body.length - 2;
        if (len > avail) {
            return new TnsRedirectPacket(len, null, null, true);
        }
        return fromData(len, new String(body, 2, len, StandardCharsets.ISO_8859_1));
    }

    /** Complete a redirect whose data arrived in the following DATA packet. */
    public TnsRedirectPacket withData(TnsPacket dataPacket) {
        if (dataPacket.type() != TnsPacket.TYPE_DATA) {
            throw new TnsParseException("Expected DATA packet with redirect data, got " + TnsPacket.typeName(dataPacket.type()));
        }
        byte[] body = dataPacket.body();
        int start = Math.min(2, body.length);
        return fromData(dataLength, new String(body, start, body.length - start, StandardCharsets.ISO_8859_1));
    }

    static TnsRedirectPacket fromData(int len, String data) {
        int nul = data.indexOf('\0');
        String addr;
        String cd = null;
        if (nul >= 0) {
            addr = data.substring(0, nul);
            String rest = data.substring(nul + 1);
            int end = rest.indexOf('\0');
            cd = end >= 0 ? rest.substring(0, end) : rest;
            if (cd.isEmpty()) {
                cd = null;
            }
        } else {
            addr = data;
        }
        return new TnsRedirectPacket(len, addr.strip(), cd, false);
    }

    public boolean needsData() {
        return needsData;
    }

    public int dataLength() {
        return dataLength;
    }

    /** The raw address descriptor, e.g. {@code (ADDRESS=(PROTOCOL=tcp)(HOST=h)(PORT=p))}. */
    public String address() {
        return address;
    }

    /** Replacement connect string, or {@code null} when the client should resend its own. */
    public String connectData() {
        return connectData;
    }

    public String host() {
        TnsDescriptor d = TnsDescriptor.parse(address);
        TnsNode h = d.findAny("HOST");
        if (h == null || h.value() == null || h.value().isEmpty()) {
            throw new TnsParseException("REDIRECT address without HOST: " + address);
        }
        return unquote(h.value());
    }

    public int port() {
        TnsDescriptor d = TnsDescriptor.parse(address);
        TnsNode p = d.findAny("PORT");
        if (p == null || p.value() == null) {
            throw new TnsParseException("REDIRECT address without PORT: " + address);
        }
        try {
            return Integer.parseInt(unquote(p.value()).strip());
        } catch (NumberFormatException e) {
            throw new TnsParseException("Invalid PORT in REDIRECT address: " + address);
        }
    }

    static String unquote(String v) {
        if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** Build a REDIRECT packet (used by tests / fake backends). */
    public static byte[] build(String address, String connectData) {
        String data = connectData == null ? address : address + "\0" + connectData;
        byte[] d = data.getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = new byte[2 + d.length];
        TnsPacket.putU16(body, 0, d.length);
        System.arraycopy(d, 0, body, 2, d.length);
        return TnsPacket.create(TnsPacket.TYPE_REDIRECT, 0, body).bytes();
    }

    @Override
    public String toString() {
        return "REDIRECT{address=" + address + ", connectData=" + connectData + "}";
    }
}
