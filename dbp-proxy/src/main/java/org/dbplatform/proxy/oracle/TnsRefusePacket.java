package org.dbplatform.proxy.oracle;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TNS REFUSE (type 4): body {@code u8 reasonUser, u8 reasonSystem, u16 dataLength, data}, where the
 * data is a descriptor carrying the ORA- error the client should raise:
 * {@code (DESCRIPTION=(TMP=)(VSNNUM=0)(ERR=12516)(ERROR_STACK=(ERROR=(CODE=12516)(EMFI=4))))}.
 */
public final class TnsRefusePacket {
    /** ORA-12514: listener does not currently know of service requested. */
    public static final int ERR_UNKNOWN_SERVICE = 12514;
    /** ORA-12516: listener could not find available handler with matching protocol stack (used for quota). */
    public static final int ERR_NO_HANDLER = 12516;
    /** ORA-12541: no listener (used when the backend cannot be reached). */
    public static final int ERR_NO_LISTENER = 12541;

    public static final int REASON_USER = 0x22;
    public static final int REASON_SYSTEM = 0x00;

    private static final Pattern ERR = Pattern.compile("\\(ERR=(\\d+)\\)");

    private TnsRefusePacket() {
    }

    public static String refuseDescriptor(int oraError) {
        return "(DESCRIPTION=(TMP=)(VSNNUM=0)(ERR=" + oraError + ")(ERROR_STACK=(ERROR=(CODE=" + oraError + ")(EMFI=4))))";
    }

    public static byte[] build(int oraError) {
        byte[] data = refuseDescriptor(oraError).getBytes(StandardCharsets.ISO_8859_1);
        byte[] body = new byte[4 + data.length];
        body[0] = (byte) REASON_USER;
        body[1] = (byte) REASON_SYSTEM;
        TnsPacket.putU16(body, 2, data.length);
        System.arraycopy(data, 0, body, 4, data.length);
        return TnsPacket.create(TnsPacket.TYPE_REFUSE, 0, body).bytes();
    }

    /** The ORA- code carried by a REFUSE packet, or -1 when absent. */
    public static int errorCode(TnsPacket p) {
        byte[] body = p.body();
        if (body.length < 4) {
            return -1;
        }
        int len = Math.min(TnsPacket.u16(body, 2), body.length - 4);
        String text = new String(body, 4, len, StandardCharsets.ISO_8859_1);
        Matcher m = ERR.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : -1;
    }

    public static String data(TnsPacket p) {
        byte[] body = p.body();
        if (body.length < 4) {
            return "";
        }
        int len = Math.min(TnsPacket.u16(body, 2), body.length - 4);
        return new String(body, 4, len, StandardCharsets.ISO_8859_1);
    }
}
