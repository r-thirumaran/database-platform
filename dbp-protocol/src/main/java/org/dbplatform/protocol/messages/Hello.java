package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolConstants;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

import java.util.Map;

/**
 * HELLO (0x01): {@code i16 protocolVersion, string clientName, string clientVersion, map properties}.
 *
 * @param protocolVersion protocol version, {@link ProtocolConstants#VERSION}
 * @param clientName      client implementation name
 * @param clientVersion   client implementation version
 * @param properties      session properties (see {@code PROP_*} constants); never {@code null}
 */
public record Hello(int protocolVersion, String clientName, String clientVersion, Map<String, String> properties)
        implements Message {

    /** Required: logical datasource name from the JDBC URL path. */
    public static final String PROP_DATASOURCE = "datasource";
    /** Application credential {@code dbp_<id>_<secret>}. */
    public static final String PROP_API_KEY = "apiKey";
    /** Application name hint. */
    public static final String PROP_APPLICATION = "application";
    /** Informational logical user name. */
    public static final String PROP_USER = "user";
    /** {@code "true"/"false"}, default {@code "true"}. */
    public static final String PROP_AUTOCOMMIT = "autoCommit";
    /** {@code "true"/"false"}. */
    public static final String PROP_READ_ONLY = "readOnly";
    /** Initial schema. */
    public static final String PROP_SCHEMA = "schema";
    /** Prefix of initial client info entries, e.g. {@code clientInfo.ApplicationName}. */
    public static final String PROP_CLIENT_INFO_PREFIX = "clientInfo.";
    /** {@code java.sql.Connection} isolation constant as decimal string. */
    public static final String PROP_TX_ISOLATION = "txIsolation";

    /**
     * Normalises the property map.
     *
     * @param protocolVersion see record
     * @param clientName      see record
     * @param clientVersion   see record
     * @param properties      see record ({@code null} = empty)
     */
    public Hello {
        properties = Codec.copyMap(properties);
    }

    /**
     * Creates a HELLO for the current protocol version.
     *
     * @param clientName    client name
     * @param clientVersion client version
     * @param properties    session properties
     * @return the message
     */
    public static Hello of(String clientName, String clientVersion, Map<String, String> properties) {
        return new Hello(ProtocolConstants.VERSION, clientName, clientVersion, properties);
    }

    @Override
    public MessageType type() {
        return MessageType.HELLO;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeI16(protocolVersion).writeString(clientName).writeString(clientVersion).writeMap(properties);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static Hello decode(ProtocolInput in) throws ProtocolException {
        int version = in.readI16();
        String clientName = in.readString();
        String clientVersion = in.readString();
        Map<String, String> props = in.readMap();
        return new Hello(version, clientName, clientVersion, props);
    }
}
