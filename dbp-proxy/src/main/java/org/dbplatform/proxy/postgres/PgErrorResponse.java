package org.dbplatform.proxy.postgres;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Builds a PostgreSQL ErrorResponse: {@code 'E', i32 length, (fieldType byte, cstring)*, 0}. The proxy
 * sends it in place of the backend when it refuses a connection, so the vendor driver raises a normal
 * {@code SQLException} with the given SQLSTATE.
 */
public final class PgErrorResponse {
    /** too_many_connections — used for quota and listener cap refusals. */
    public static final String TOO_MANY_CONNECTIONS = "53300";
    /** invalid_catalog_name — unknown logical database and no default route. */
    public static final String INVALID_CATALOG_NAME = "3D000";
    /** sqlclient_unable_to_establish_sqlconnection — backend connect failure. */
    public static final String CONNECTION_FAILURE = "08001";
    /** protocol_violation. */
    public static final String PROTOCOL_VIOLATION = "08P01";

    private PgErrorResponse() {
    }

    public static byte[] fatal(String sqlState, String message) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        field(body, 'S', "FATAL");
        field(body, 'V', "FATAL");
        field(body, 'C', sqlState);
        field(body, 'M', message);
        body.write(0);
        byte[] b = body.toByteArray();
        byte[] out = new byte[1 + 4 + b.length];
        out[0] = 'E';
        PgStartupMessage.putI32(out, 1, 4 + b.length);
        System.arraycopy(b, 0, out, 5, b.length);
        return out;
    }

    private static void field(ByteArrayOutputStream out, char type, String value) {
        out.write(type);
        byte[] v = value.getBytes(StandardCharsets.UTF_8);
        out.write(v, 0, v.length);
        out.write(0);
    }
}
