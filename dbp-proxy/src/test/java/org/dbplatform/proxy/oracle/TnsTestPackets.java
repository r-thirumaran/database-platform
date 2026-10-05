package org.dbplatform.proxy.oracle;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Builds synthetic TNS packets the way Oracle clients/servers lay them out. */
final class TnsTestPackets {
    static final int OFFSET_V318 = 74;
    static final int OFFSET_V315 = 58;

    private TnsTestPackets() {
    }

    /**
     * CONNECT packet as a JDBC thin / OCI client would send it. Version 318 puts the connect data at
     * offset 74 (adds 32-bit SDU/TDU and reserved bytes), version 315 at offset 58. Strings longer than
     * 230 bytes are deferred: the CONNECT carries only the length, the DATA packet carries the text.
     */
    static byte[] connect(int version, String connectData) {
        return connect(version, connectData, false);
    }

    static byte[] connect(int version, String connectData, boolean length32) {
        int off = version >= 318 ? OFFSET_V318 : OFFSET_V315;
        byte[] data = connectData.getBytes(StandardCharsets.ISO_8859_1);
        boolean inline = data.length <= TnsConnectPacket.INLINE_LIMIT;
        byte[] p = new byte[inline ? off + data.length : off];
        if (length32) {
            TnsPacket.putU16(p, 0, 0);
            TnsPacket.putU16(p, 2, p.length);
        } else {
            TnsPacket.putU16(p, 0, p.length);
            TnsPacket.putU16(p, 2, 0);
        }
        p[4] = TnsPacket.TYPE_CONNECT;
        p[5] = 0x00;
        TnsPacket.putU16(p, 6, 0);
        TnsPacket.putU16(p, 8, version);
        TnsPacket.putU16(p, 10, 300);
        TnsPacket.putU16(p, 12, 0x0C41);
        TnsPacket.putU16(p, 14, 8192);
        TnsPacket.putU16(p, 16, 0xFFFF);
        TnsPacket.putU16(p, 18, 0x7F08);
        TnsPacket.putU16(p, 20, 0);
        TnsPacket.putU16(p, 22, 1);
        TnsPacket.putU16(p, 24, data.length);
        TnsPacket.putU16(p, 26, off);
        TnsPacket.putU32(p, 28, 2048);
        p[32] = 0x41;
        p[33] = 0x41;
        if (version >= 318) {
            TnsPacket.putU32(p, 58, 8192);
            TnsPacket.putU32(p, 62, 0x7FFFFFFF);
        }
        if (inline) {
            System.arraycopy(data, 0, p, off, data.length);
            return p;
        }
        byte[] body = new byte[2 + data.length];
        System.arraycopy(data, 0, body, 2, data.length);
        byte[] d = TnsPacket.create(TnsPacket.TYPE_DATA, 0, body).bytes();
        byte[] all = Arrays.copyOf(p, p.length + d.length);
        System.arraycopy(d, 0, all, p.length, d.length);
        return all;
    }

    /** A plausible ACCEPT: version, service options, sdu, tdu, value of 1, data length/offset, flags. */
    static byte[] accept(int version) {
        byte[] body = new byte[24];
        TnsPacket.putU16(body, 0, version);
        TnsPacket.putU16(body, 2, 0x0C41);
        TnsPacket.putU16(body, 4, 8192);
        TnsPacket.putU16(body, 6, 0xFFFF);
        TnsPacket.putU16(body, 8, 1);
        TnsPacket.putU16(body, 10, 0);
        TnsPacket.putU16(body, 12, 32);
        body[14] = 0x41;
        body[15] = 0x41;
        return TnsPacket.create(TnsPacket.TYPE_ACCEPT, 0, body).bytes();
    }

    static byte[] resend() {
        return TnsPacket.create(TnsPacket.TYPE_RESEND, 0, new byte[0]).bytes();
    }

    static byte[] data(String text) {
        byte[] t = text.getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = new byte[2 + t.length];
        System.arraycopy(t, 0, body, 2, t.length);
        return TnsPacket.create(TnsPacket.TYPE_DATA, 0, body).bytes();
    }

    static String dataText(TnsPacket p) {
        byte[] b = p.body();
        return new String(b, 2, b.length - 2, StandardCharsets.ISO_8859_1);
    }
}
