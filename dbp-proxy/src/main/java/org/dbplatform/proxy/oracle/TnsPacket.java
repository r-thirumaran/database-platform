package org.dbplatform.proxy.oracle;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Oracle Net (TNS) packet with its 8-byte header:
 * <pre>
 *   u16 packetLength | u16 packetChecksum | u8 packetType | u8 flags | u16 headerChecksum
 * </pre>
 * Since TNS version 315 the length may instead occupy the first four bytes (big-endian u32, the old
 * checksum field being the low half). Both forms are accepted: when the first u16 is zero the second
 * u16 is used (packets during the connect handshake are always &lt; 64 KiB, so this is unambiguous).
 */
public final class TnsPacket {
    public static final int TYPE_CONNECT = 1;
    public static final int TYPE_ACCEPT = 2;
    public static final int TYPE_ACK = 3;
    public static final int TYPE_REFUSE = 4;
    public static final int TYPE_REDIRECT = 5;
    public static final int TYPE_DATA = 6;
    public static final int TYPE_NULL = 7;
    public static final int TYPE_ABORT = 9;
    public static final int TYPE_RESEND = 11;
    public static final int TYPE_MARKER = 12;
    public static final int TYPE_ATTENTION = 13;
    public static final int TYPE_CONTROL = 14;

    public static final int HEADER_LENGTH = 8;
    /** Upper bound accepted while reading handshake packets (defensive, real ones are a few hundred bytes). */
    public static final int MAX_HANDSHAKE_PACKET = 1 << 20;

    private final byte[] bytes;

    private TnsPacket(byte[] bytes) {
        this.bytes = bytes;
    }

    public static TnsPacket wrap(byte[] bytes) {
        if (bytes.length < HEADER_LENGTH) {
            throw new TnsParseException("TNS packet shorter than header: " + bytes.length);
        }
        return new TnsPacket(bytes);
    }

    /** Build a packet in the classic 16-bit-length header form with zero checksums. */
    public static TnsPacket create(int type, int flags, byte[] body) {
        int len = HEADER_LENGTH + body.length;
        if (len > 0xFFFF) {
            throw new IllegalArgumentException("TNS packet too long for 16-bit length: " + len);
        }
        byte[] b = new byte[len];
        putU16(b, 0, len);
        putU16(b, 2, 0);
        b[4] = (byte) type;
        b[5] = (byte) flags;
        putU16(b, 6, 0);
        System.arraycopy(body, 0, b, HEADER_LENGTH, body.length);
        return new TnsPacket(b);
    }

    /** Read exactly one packet from the stream, or return {@code null} on a clean EOF before any byte. */
    public static TnsPacket read(InputStream in) throws IOException {
        byte[] header = new byte[HEADER_LENGTH];
        int n = readFully(in, header, 0, HEADER_LENGTH, true);
        if (n == 0) {
            return null;
        }
        if (n < HEADER_LENGTH) {
            throw new EOFException("Truncated TNS header (" + n + " bytes)");
        }
        int length = lengthOf(header);
        if (length < HEADER_LENGTH || length > MAX_HANDSHAKE_PACKET) {
            throw new TnsParseException("Invalid TNS packet length " + length + " (type " + (header[4] & 0xFF) + ")");
        }
        byte[] all = Arrays.copyOf(header, length);
        int got = readFully(in, all, HEADER_LENGTH, length - HEADER_LENGTH, false);
        if (got < length - HEADER_LENGTH) {
            throw new EOFException("Truncated TNS packet body");
        }
        return new TnsPacket(all);
    }

    static int lengthOf(byte[] header) {
        int l = u16(header, 0);
        if (l == 0) {
            l = u16(header, 2);
        }
        return l;
    }

    /** True when the header uses the 32-bit length layout (first u16 zero, length in the second u16). */
    public boolean lengthIs32Bit() {
        return u16(bytes, 0) == 0;
    }

    public int length() {
        return bytes.length;
    }

    public int type() {
        return bytes[4] & 0xFF;
    }

    public int flags() {
        return bytes[5] & 0xFF;
    }

    public byte[] bytes() {
        return bytes;
    }

    public byte[] body() {
        return Arrays.copyOfRange(bytes, HEADER_LENGTH, bytes.length);
    }

    public static String typeName(int type) {
        return switch (type) {
            case TYPE_CONNECT -> "CONNECT";
            case TYPE_ACCEPT -> "ACCEPT";
            case TYPE_ACK -> "ACK";
            case TYPE_REFUSE -> "REFUSE";
            case TYPE_REDIRECT -> "REDIRECT";
            case TYPE_DATA -> "DATA";
            case TYPE_NULL -> "NULL";
            case TYPE_ABORT -> "ABORT";
            case TYPE_RESEND -> "RESEND";
            case TYPE_MARKER -> "MARKER";
            case TYPE_ATTENTION -> "ATTENTION";
            case TYPE_CONTROL -> "CONTROL";
            default -> "UNKNOWN(" + type + ")";
        };
    }

    static int readFully(InputStream in, byte[] buf, int off, int len, boolean allowEmpty) throws IOException {
        int total = 0;
        while (total < len) {
            int r = in.read(buf, off + total, len - total);
            if (r < 0) {
                if (total == 0 && allowEmpty) {
                    return 0;
                }
                return total;
            }
            total += r;
        }
        return total;
    }

    public static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    public static long u32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16) | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    public static void putU16(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 8);
        b[off + 1] = (byte) v;
    }

    public static void putU32(byte[] b, int off, long v) {
        b[off] = (byte) (v >>> 24);
        b[off + 1] = (byte) (v >>> 16);
        b[off + 2] = (byte) (v >>> 8);
        b[off + 3] = (byte) v;
    }
}
