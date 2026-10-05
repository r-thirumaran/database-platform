package org.dbplatform.protocol;

/**
 * Tags of the {@code Value} encoding (section 2 of the specification).
 */
public enum ValueTag {

    /** SQL NULL, no data. */
    NULL(0),
    /** {@code bool}. */
    BOOLEAN(1),
    /** {@code i8}. */
    BYTE(2),
    /** {@code i16}. */
    SHORT(3),
    /** {@code i32}. */
    INT(4),
    /** {@code i64}. */
    LONG(5),
    /** {@code f32}. */
    FLOAT(6),
    /** {@code f64}. */
    DOUBLE(7),
    /** {@code string} holding {@code BigDecimal.toPlainString()}. */
    DECIMAL(8),
    /** {@code string}; also used for CLOB, NCLOB, UUID, XML, JSON, INTERVAL and unknown types. */
    STRING(9),
    /** {@code bytes}; also used for BLOB. */
    BYTES(10),
    /** {@code i64 epochDay}. */
    DATE(11),
    /** {@code i64 nanoOfDay}. */
    TIME(12),
    /** {@code i64 epochSecond, i32 nano} of the local date-time treated as UTC. */
    TIMESTAMP(13),
    /** {@code i64 epochSecond, i32 nano, i32 offsetSeconds}. */
    TIMESTAMP_TZ(14),
    /** {@code i64 nanoOfDay, i32 offsetSeconds}. */
    TIME_TZ(15),
    /** {@code i32 jdbcType}; parameters only ({@code setNull(i, type)}). */
    TYPED_NULL(16);

    private static final ValueTag[] BY_CODE = values();

    private final int code;

    ValueTag(int code) {
        this.code = code;
    }

    /**
     * Returns the one-byte wire code of this tag.
     *
     * @return the code, {@code 0..16}
     */
    public int code() {
        return code;
    }

    /**
     * Resolves a wire code to a tag.
     *
     * @param code the code read from the payload
     * @return the tag
     * @throws ProtocolException if the code is unknown
     */
    public static ValueTag fromCode(int code) throws ProtocolException {
        if (code < 0 || code >= BY_CODE.length || BY_CODE[code].code != code) {
            throw new ProtocolException("unknown value tag " + code);
        }
        return BY_CODE[code];
    }
}
