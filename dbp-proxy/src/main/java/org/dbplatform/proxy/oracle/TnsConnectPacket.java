package org.dbplatform.proxy.oracle;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * A TNS CONNECT packet (type 1). Body layout after the 8-byte header (offsets from packet start):
 * <pre>
 *  8  u16 version            10 u16 versionCompatible   12 u16 serviceOptions
 * 14  u16 sdu                16 u16 tdu                 18 u16 ntProtocolCharacteristics
 * 20  u16 lineTurnaround     22 u16 valueOf1            24 u16 connectDataLength
 * 26  u16 connectDataOffset  28 u32 maxReceivable       32 ... version dependent fields
 * </pre>
 * The connect string is always located through {@code connectDataOffset}/{@code connectDataLength}
 * (version 315 clients use offset 58, version 318 clients offset 74, future clients may add fields).
 * When {@code connectDataLength > 0} but the packet ends before {@code offset + length} the client
 * deferred the connect string to the following DATA packet (type 6, body {@code u16 dataFlags} + text);
 * Oracle clients do this when the string exceeds {@link #INLINE_LIMIT} bytes.
 *
 * <p>Instances are immutable; {@link #withConnectData(String)} produces a rewritten copy that keeps
 * every header / fixed field byte of the original except the two length fields.
 */
public final class TnsConnectPacket {
    /** Longest connect string Oracle clients send inline; longer ones go in a trailing DATA packet. */
    public static final int INLINE_LIMIT = 230;
    private static final int MIN_BODY = 20; // up to and including connectDataOffset

    private final byte[] packet;
    private final String connectData;

    private TnsConnectPacket(byte[] packet, String connectData) {
        this.packet = packet;
        this.connectData = connectData;
    }

    public static TnsConnectPacket parse(TnsPacket p) {
        if (p.type() != TnsPacket.TYPE_CONNECT) {
            throw new TnsParseException("Not a CONNECT packet: type " + p.type());
        }
        return parse(p.bytes());
    }

    public static TnsConnectPacket parse(byte[] packet) {
        if (packet.length < TnsPacket.HEADER_LENGTH + MIN_BODY) {
            throw new TnsParseException("CONNECT packet too short: " + packet.length);
        }
        int cdLen = TnsPacket.u16(packet, 24);
        int cdOff = TnsPacket.u16(packet, 26);
        if (cdOff < TnsPacket.HEADER_LENGTH + MIN_BODY || cdOff > packet.length) {
            throw new TnsParseException("Invalid connectDataOffset " + cdOff + " for packet of " + packet.length + " bytes");
        }
        String data = null;
        if (cdLen == 0) {
            data = "";
        } else if (packet.length >= cdOff + cdLen) {
            data = new String(packet, cdOff, cdLen, StandardCharsets.ISO_8859_1);
        }
        return new TnsConnectPacket(packet, data);
    }

    public int version() {
        return TnsPacket.u16(packet, 8);
    }

    public int versionCompatible() {
        return TnsPacket.u16(packet, 10);
    }

    public int serviceOptions() {
        return TnsPacket.u16(packet, 12);
    }

    public int sdu() {
        return TnsPacket.u16(packet, 14);
    }

    public int tdu() {
        return TnsPacket.u16(packet, 16);
    }

    public int ntProtocolCharacteristics() {
        return TnsPacket.u16(packet, 18);
    }

    public int lineTurnaround() {
        return TnsPacket.u16(packet, 20);
    }

    public int valueOf1() {
        return TnsPacket.u16(packet, 22);
    }

    public int connectDataLength() {
        return TnsPacket.u16(packet, 24);
    }

    public int connectDataOffset() {
        return TnsPacket.u16(packet, 26);
    }

    public long maxReceivable() {
        return packet.length >= 32 ? TnsPacket.u32(packet, 28) : 0;
    }

    public int flags() {
        return packet[5] & 0xFF;
    }

    /** True when the connect string travels in a following DATA packet and has not been attached yet. */
    public boolean isDeferred() {
        return connectData == null;
    }

    /** True when this packet (as encoded) carries no inline string and expects a DATA packet to follow. */
    public boolean usesDeferredForm() {
        return connectDataLength() > 0 && packet.length < connectDataOffset() + connectDataLength();
    }

    /** The connect string, or {@code null} while deferred data is still outstanding. */
    public String connectData() {
        return connectData;
    }

    /** Attach the connect string received in the trailing DATA packet (body = u16 flags + text). */
    public TnsConnectPacket withDeferredData(TnsPacket dataPacket) {
        if (dataPacket.type() != TnsPacket.TYPE_DATA) {
            throw new TnsParseException("Expected DATA packet with deferred connect string, got " + TnsPacket.typeName(dataPacket.type()));
        }
        byte[] body = dataPacket.body();
        if (body.length < 2) {
            throw new TnsParseException("Deferred connect DATA packet too short");
        }
        int expected = connectDataLength();
        int avail = body.length - 2;
        int take = Math.min(expected, avail);
        return new TnsConnectPacket(packet, new String(body, 2, take, StandardCharsets.ISO_8859_1));
    }

    /**
     * Copy of this packet carrying a different connect string. Header bytes (flags, checksums, length
     * encoding form) and all fixed fields are preserved; {@code connectDataLength} and the packet length
     * are recomputed and the inline/deferred form is chosen by {@link #INLINE_LIMIT}.
     */
    public TnsConnectPacket withConnectData(String newConnectData) {
        byte[] data = newConnectData.getBytes(StandardCharsets.ISO_8859_1);
        int cdOff = connectDataOffset();
        boolean inline = data.length <= INLINE_LIMIT;
        byte[] out = Arrays.copyOf(packet, inline ? cdOff + data.length : cdOff);
        TnsPacket.putU16(out, 24, data.length);
        if (inline) {
            System.arraycopy(data, 0, out, cdOff, data.length);
        }
        setLength(out, out.length);
        return new TnsConnectPacket(out, newConnectData);
    }

    private void setLength(byte[] out, int len) {
        if (TnsPacket.u16(packet, 0) == 0) {
            TnsPacket.putU16(out, 0, 0);
            TnsPacket.putU16(out, 2, len);
        } else {
            TnsPacket.putU16(out, 0, len);
        }
    }

    /** Bytes of the CONNECT packet itself (without any trailing DATA packet). */
    public byte[] packetBytes() {
        return packet;
    }

    /** The packet(s) to put on the wire: the CONNECT, plus a DATA packet when the string is deferred. */
    public List<byte[]> toWire() {
        if (!usesDeferredForm()) {
            return List.of(packet);
        }
        if (connectData == null) {
            throw new IllegalStateException("Deferred connect string not attached");
        }
        byte[] data = connectData.getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = new byte[2 + data.length];
        TnsPacket.putU16(body, 0, 0);
        System.arraycopy(data, 0, body, 2, data.length);
        return List.of(packet, TnsPacket.create(TnsPacket.TYPE_DATA, 0, body).bytes());
    }

    /** Concatenated wire bytes, convenient for tests and single-write sends. */
    public byte[] toWireBytes() {
        List<byte[]> parts = toWire();
        if (parts.size() == 1) {
            return parts.get(0);
        }
        byte[] all = new byte[parts.get(0).length + parts.get(1).length];
        System.arraycopy(parts.get(0), 0, all, 0, parts.get(0).length);
        System.arraycopy(parts.get(1), 0, all, parts.get(0).length, parts.get(1).length);
        return all;
    }

    @Override
    public String toString() {
        return "CONNECT{version=" + version() + ", sdu=" + sdu() + ", cdLen=" + connectDataLength()
                + ", cdOff=" + connectDataOffset() + ", deferred=" + usesDeferredForm() + ", data=" + connectData + "}";
    }
}
