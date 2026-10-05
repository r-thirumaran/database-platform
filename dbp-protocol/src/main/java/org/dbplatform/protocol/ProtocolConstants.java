package org.dbplatform.protocol;

/**
 * Constants of the DBP wire protocol v1 shared by driver and gateway.
 *
 * @see <a href="../../../../../../docs/wire-protocol.md">docs/wire-protocol.md</a>
 */
public final class ProtocolConstants {

    /** Protocol version sent in {@code HELLO.protocolVersion}. */
    public static final int VERSION = 1;

    /** Default gateway port for the wire protocol. */
    public static final int DEFAULT_PORT = 7420;

    /** Number of rows returned with a result-set header when {@code fetchSize <= 0}. */
    public static final int DEFAULT_FETCH_SIZE = 100;

    /** Default maximum frame size (type byte plus payload), 64 MiB. */
    public static final int DEFAULT_MAX_FRAME_BYTES = 64 * 1024 * 1024;

    /** Size in bytes of the frame header ({@code u32 length}). */
    public static final int FRAME_LENGTH_PREFIX_BYTES = 4;

    /** JDBC URL prefix handled by the driver. */
    public static final String JDBC_URL_PREFIX = "jdbc:dbp://";

    /** Name of the JDBC URL / connection property that overrides the maximum frame size. */
    public static final String PROP_MAX_FRAME_BYTES = "maxFrameBytes";

    /** {@code statementId} value meaning "direct SQL, not a registered statement". */
    public static final int DIRECT_STATEMENT_ID = -1;

    /** {@code parameterCount} value in PREPARED meaning "unknown". */
    public static final int UNKNOWN_PARAMETER_COUNT = -1;

    /** {@code scale} value in an OUT parameter registration meaning "no scale". */
    public static final int NO_SCALE = -1;

    private ProtocolConstants() {
    }
}
