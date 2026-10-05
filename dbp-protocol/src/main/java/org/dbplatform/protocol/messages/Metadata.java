package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

import java.util.List;

/**
 * METADATA (0x30): {@code string operation, Value[] args}, answered by exactly one
 * {@code RESULT_SET_HEADER ROWS} pair followed by EXECUTE_DONE (or ERROR).
 *
 * <p>{@code operation} is the name of a {@code java.sql.DatabaseMetaData} method returning a {@code ResultSet};
 * {@code args} are its arguments in order: {@code STRING} or {@code NULL} for strings, {@code BOOLEAN} for
 * booleans, {@code INT} for {@code scope}, and a {@code String[]} argument ({@code getTables} types) as a single
 * {@code STRING} joined with {@code '\u0000'} (see {@link #joinStringArray(String[])}).</p>
 *
 * @param operation {@code DatabaseMetaData} method name, never {@code null}
 * @param args      arguments, never {@code null} (elements may be {@code null})
 */
public record Metadata(String operation, List<Object> args) implements Message {

    /** Separator used to pack a {@code String[]} argument into one STRING value. */
    public static final char ARRAY_SEPARATOR = '\u0000';

    /**
     * Validates and normalises.
     *
     * @param operation non-null
     * @param args      {@code null} = empty
     */
    public Metadata {
        if (operation == null) {
            throw new IllegalArgumentException("operation must not be null");
        }
        args = Codec.copyList(args);
    }

    /**
     * Creates a METADATA request.
     *
     * @param operation method name
     * @param args      arguments
     * @return the message
     */
    public static Metadata of(String operation, Object... args) {
        return new Metadata(operation, args == null ? List.of() : java.util.Arrays.asList(args));
    }

    /**
     * Packs a {@code String[]} argument into the single STRING representation; {@code null} stays {@code null}.
     *
     * @param values the array
     * @return joined string or {@code null}
     */
    public static String joinStringArray(String[] values) {
        if (values == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(ARRAY_SEPARATOR);
            }
            sb.append(values[i]);
        }
        return sb.toString();
    }

    /**
     * Unpacks a STRING produced by {@link #joinStringArray(String[])}; {@code null} stays {@code null} and an
     * empty string yields an empty array.
     *
     * @param joined the joined string
     * @return the array or {@code null}
     */
    public static String[] splitStringArray(String joined) {
        if (joined == null) {
            return null;
        }
        if (joined.isEmpty()) {
            return new String[0];
        }
        return joined.split(String.valueOf(ARRAY_SEPARATOR), -1);
    }

    @Override
    public MessageType type() {
        return MessageType.METADATA;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeString(operation).writeValues(args);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static Metadata decode(ProtocolInput in) throws ProtocolException {
        String operation = in.readString();
        if (operation == null) {
            throw new ProtocolException("METADATA operation must not be null");
        }
        List<Object> args = in.readValues();
        return new Metadata(operation, args);
    }
}
