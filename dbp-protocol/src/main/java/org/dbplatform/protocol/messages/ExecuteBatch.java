package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolConstants;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;

import java.util.ArrayList;
import java.util.List;

/**
 * EXECUTE_BATCH (0x15): {@code i32 statementId (-1 = plain statement batch), string sql (null unless prepared
 * and not registered), u8 kind, i32 paramSetCount, paramSetCount × Value[], string[] sqls}, answered by
 * BATCH_RESULT.
 *
 * <p>Two shapes exist: a <em>prepared</em> batch ({@code kind = PREPARED/CALLABLE}) carries one {@code Value[]}
 * per {@code addBatch()} in {@link #paramSets()} and an empty {@link #sqls()}; a <em>plain statement</em> batch
 * ({@code kind = STATEMENT}, {@code statementId = -1}) carries the SQL strings in {@link #sqls()} and no
 * parameter sets.</p>
 *
 * @param statementId registered statement id, or {@link ProtocolConstants#DIRECT_STATEMENT_ID}
 * @param sql         SQL of a prepared batch whose statement is not registered, otherwise {@code null}
 * @param kind        statement flavour
 * @param paramSets   one parameter list per batch entry (prepared batches)
 * @param sqls        SQL strings (plain statement batches)
 */
public record ExecuteBatch(int statementId, String sql, StatementKind kind, List<List<Object>> paramSets,
                           List<String> sqls) implements Message {

    /**
     * Validates and normalises.
     *
     * @param statementId see record
     * @param sql         see record
     * @param kind        non-null
     * @param paramSets   {@code null} = empty
     * @param sqls        {@code null} = empty
     */
    public ExecuteBatch {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        paramSets = Codec.copyRows(paramSets);
        sqls = Codec.copyList(sqls);
    }

    /**
     * Creates a plain {@code Statement} batch.
     *
     * @param sqls the SQL strings added with {@code addBatch(String)}
     * @return the message
     */
    public static ExecuteBatch ofStatements(List<String> sqls) {
        return new ExecuteBatch(ProtocolConstants.DIRECT_STATEMENT_ID, null, StatementKind.STATEMENT, List.of(), sqls);
    }

    /**
     * Creates a batch for a registered prepared statement.
     *
     * @param statementId id returned in PREPARED
     * @param kind        PREPARED or CALLABLE
     * @param paramSets   one parameter list per batch entry
     * @return the message
     */
    public static ExecuteBatch ofPrepared(int statementId, StatementKind kind, List<List<Object>> paramSets) {
        return new ExecuteBatch(statementId, null, kind, paramSets, List.of());
    }

    @Override
    public MessageType type() {
        return MessageType.EXECUTE_BATCH;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeI32(statementId).writeString(sql).writeU8(kind.code());
        out.writeI32(paramSets.size());
        for (List<Object> set : paramSets) {
            out.writeValues(set);
        }
        out.writeStringArray(sqls);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static ExecuteBatch decode(ProtocolInput in) throws ProtocolException {
        int statementId = in.readI32();
        String sql = in.readString();
        StatementKind kind = StatementKind.fromCode(in.readU8());
        int n = in.readCount("paramSet", 4);
        List<List<Object>> sets = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            sets.add(in.readValues());
        }
        List<String> sqls = in.readStringArray();
        return new ExecuteBatch(statementId, sql, kind, sets, sqls);
    }
}
