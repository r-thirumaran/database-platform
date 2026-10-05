package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

import java.util.Map;

/**
 * HELLO_OK (0x42): {@code string sessionId, string serverVersion, string engine, map serverProperties}.
 *
 * <p>{@code engine} is one of the {@code ENGINE_*} constants. {@code serverProperties} delivers the scalar
 * {@code DatabaseMetaData} values listed in section 4.5 of the specification (booleans as
 * {@code "true"/"false"}), plus {@code url} (the logical JDBC URL) and {@code poolMode}
 * ({@code TRANSACTION} or {@code SESSION}).</p>
 *
 * @param sessionId        gateway session id
 * @param serverVersion    gateway version
 * @param engine           physical database engine family
 * @param serverProperties scalar metadata, never {@code null}
 */
public record HelloOk(String sessionId, String serverVersion, String engine, Map<String, String> serverProperties)
        implements Message {

    /** Oracle Database. */
    public static final String ENGINE_ORACLE = "ORACLE";
    /** PostgreSQL. */
    public static final String ENGINE_POSTGRES = "POSTGRES";
    /** Microsoft SQL Server. */
    public static final String ENGINE_MSSQL = "MSSQL";
    /** H2 (tests and demos). */
    public static final String ENGINE_H2 = "H2";
    /** Anything else. */
    public static final String ENGINE_OTHER = "OTHER";

    /** Server property: logical JDBC URL resolved for the session. */
    public static final String PROP_URL = "url";
    /** Server property: pooling mode applied to the session. */
    public static final String PROP_POOL_MODE = "poolMode";

    /**
     * Normalises the property map.
     *
     * @param sessionId        see record
     * @param serverVersion    see record
     * @param engine           see record
     * @param serverProperties {@code null} = empty
     */
    public HelloOk {
        serverProperties = Codec.copyMap(serverProperties);
    }

    /**
     * Returns a server property.
     *
     * @param key property key
     * @return the value or {@code null}
     */
    public String property(String key) {
        return serverProperties.get(key);
    }

    /**
     * Returns a boolean server property.
     *
     * @param key  property key
     * @param dflt value when absent
     * @return parsed value
     */
    public boolean booleanProperty(String key, boolean dflt) {
        String v = serverProperties.get(key);
        return v == null ? dflt : Boolean.parseBoolean(v);
    }

    /**
     * Returns an integer server property.
     *
     * @param key  property key
     * @param dflt value when absent or not a number
     * @return parsed value
     */
    public int intProperty(String key, int dflt) {
        String v = serverProperties.get(key);
        if (v == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    @Override
    public MessageType type() {
        return MessageType.HELLO_OK;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeString(sessionId).writeString(serverVersion).writeString(engine).writeMap(serverProperties);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static HelloOk decode(ProtocolInput in) throws ProtocolException {
        String sessionId = in.readString();
        String serverVersion = in.readString();
        String engine = in.readString();
        Map<String, String> props = in.readMap();
        return new HelloOk(sessionId, serverVersion, engine, props);
    }
}
