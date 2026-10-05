package org.dbplatform.protocol;

import java.sql.SQLWarning;

/**
 * A JDBC warning as carried in EXECUTE_DONE and BATCH_RESULT.
 *
 * <pre>Warning := string sqlState (nullable), i32 vendorCode, string message</pre>
 *
 * @param sqlState   SQLState or {@code null}
 * @param vendorCode vendor error code
 * @param message    message (nullable on the wire, normally present)
 */
public record Warning(String sqlState, int vendorCode, String message) {

    /**
     * Captures a physical {@link SQLWarning} (one link of the chain).
     *
     * @param w the warning
     * @return the record
     */
    public static Warning from(SQLWarning w) {
        return new Warning(w.getSQLState(), w.getErrorCode(), w.getMessage());
    }

    /**
     * Converts back to a {@link SQLWarning} (driver side).
     *
     * @return a new warning
     */
    public SQLWarning toSqlWarning() {
        return new SQLWarning(message, sqlState, vendorCode);
    }

    /**
     * Writes this structure.
     *
     * @param out destination
     */
    public void encode(ProtocolOutput out) {
        out.writeString(sqlState).writeI32(vendorCode).writeString(message);
    }

    /**
     * Reads this structure.
     *
     * @param in source
     * @return the warning
     * @throws ProtocolException if malformed
     */
    public static Warning decode(ProtocolInput in) throws ProtocolException {
        String sqlState = in.readString();
        int vendorCode = in.readI32();
        String message = in.readString();
        return new Warning(sqlState, vendorCode, message);
    }
}
