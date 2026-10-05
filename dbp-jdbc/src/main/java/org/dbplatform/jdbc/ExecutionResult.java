package org.dbplatform.jdbc;

import org.dbplatform.protocol.Warning;
import org.dbplatform.protocol.messages.ExecuteDone;
import org.dbplatform.protocol.messages.GeneratedKeys;
import org.dbplatform.protocol.messages.Message;
import org.dbplatform.protocol.messages.OutParams;
import org.dbplatform.protocol.messages.ResultSetHeader;
import org.dbplatform.protocol.messages.Rows;
import org.dbplatform.protocol.messages.UpdateCount;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The decoded response of an EXECUTE or METADATA exchange (specification section 4.6):
 * {@code (RESULT_SET_HEADER ROWS | UPDATE_COUNT)* OUT_PARAMS? GENERATED_KEYS? EXECUTE_DONE}.
 */
final class ExecutionResult {

    /** One result item in the order produced by the physical driver. */
    sealed interface Item permits ResultSetItem, UpdateCountItem {
    }

    /** A result set: its header and the first batch of rows. */
    record ResultSetItem(ResultSetHeader header, Rows rows) implements Item {
        int cursorId() {
            return header.cursorId();
        }
    }

    /** An update count. */
    record UpdateCountItem(long count) implements Item {
    }

    final List<Item> items = new ArrayList<>();
    OutParams outParams;
    GeneratedKeys generatedKeys;
    List<Warning> warnings = List.of();

    static ExecutionResult parse(List<Message> messages) throws SQLException {
        ExecutionResult r = new ExecutionResult();
        ResultSetHeader pendingHeader = null;
        for (Message m : messages) {
            if (pendingHeader != null) {
                if (m instanceof Rows rows) {
                    if (rows.cursorId() != pendingHeader.cursorId()) {
                        throw DbpSqlExceptions.protocolViolation("ROWS for cursor " + rows.cursorId()
                                + " does not match RESULT_SET_HEADER cursor " + pendingHeader.cursorId());
                    }
                    r.items.add(new ResultSetItem(pendingHeader, rows));
                    pendingHeader = null;
                    continue;
                }
                throw DbpSqlExceptions.protocolViolation("RESULT_SET_HEADER must be followed by ROWS but got " + m.type());
            }
            switch (m) {
                case ResultSetHeader h -> pendingHeader = h;
                case UpdateCount u -> r.items.add(new UpdateCountItem(u.count()));
                case OutParams o -> r.outParams = o;
                case GeneratedKeys g -> r.generatedKeys = g;
                case ExecuteDone d -> r.warnings = d.warnings();
                case Rows rows -> throw DbpSqlExceptions.protocolViolation("ROWS without RESULT_SET_HEADER (cursor "
                        + rows.cursorId() + ")");
                default -> throw DbpSqlExceptions.protocolViolation("unexpected " + m.type() + " in EXECUTE response");
            }
        }
        if (pendingHeader != null) {
            throw DbpSqlExceptions.protocolViolation("RESULT_SET_HEADER without ROWS");
        }
        return r;
    }
}
