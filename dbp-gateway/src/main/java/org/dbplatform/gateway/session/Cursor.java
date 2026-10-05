package org.dbplatform.gateway.session;

import org.dbplatform.protocol.ValueTag;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * An open server-side cursor: a physical {@link ResultSet}, the statement it came from and the value tags of its
 * columns.
 *
 * @param id            cursor id (unique per session)
 * @param resultSet     physical result set
 * @param statement     statement that produced it
 * @param tags          per-column value tags
 * @param ownsStatement whether closing the cursor closes the statement (direct executions)
 * @param outParam      whether it belongs to a cursor-typed OUT parameter
 * @param statementId   registered statement id the cursor belongs to (-1 for direct executions)
 */
public record Cursor(int id, ResultSet resultSet, Statement statement, ValueTag[] tags, boolean ownsStatement,
                     boolean outParam, int statementId) {

    public int columnCount() {
        return tags.length;
    }

    /** Closes the result set (and the statement when owned); never throws. */
    public void close() {
        try {
            resultSet.close();
        } catch (SQLException | RuntimeException ignored) {
            // best effort
        }
        if (ownsStatement) {
            try {
                statement.close();
            } catch (SQLException | RuntimeException ignored) {
                // best effort
            }
        }
    }
}
