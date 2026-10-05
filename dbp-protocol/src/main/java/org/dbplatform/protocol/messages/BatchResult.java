package org.dbplatform.protocol.messages;

import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.ProtocolInput;
import org.dbplatform.protocol.ProtocolOutput;
import org.dbplatform.protocol.Warning;

import java.util.Arrays;
import java.util.List;

/**
 * BATCH_RESULT (0x4B): {@code i32 count, count × i64 updateCount, i32 warningCount, warningCount × Warning};
 * answer to EXECUTE_BATCH. Update counts follow {@code Statement.executeLargeBatch} semantics
 * ({@code SUCCESS_NO_INFO = -2}, {@code EXECUTE_FAILED = -3}).
 *
 * @param updateCounts one entry per batch element, never {@code null}
 * @param warnings     warnings, never {@code null}
 */
public record BatchResult(long[] updateCounts, List<Warning> warnings) implements Message {

    /**
     * Copies the array and normalises the list.
     *
     * @param updateCounts {@code null} = empty
     * @param warnings     {@code null} = empty
     */
    public BatchResult {
        updateCounts = updateCounts == null ? new long[0] : updateCounts.clone();
        warnings = Codec.copyList(warnings);
        for (Warning w : warnings) {
            if (w == null) {
                throw new IllegalArgumentException("warning must not be null");
            }
        }
    }

    /**
     * Returns a defensive copy of the update counts.
     *
     * @return the counts
     */
    @Override
    public long[] updateCounts() {
        return updateCounts.clone();
    }

    /**
     * Returns the update counts narrowed to {@code int} for {@code Statement.executeBatch}; values outside the
     * {@code int} range become {@code Statement.SUCCESS_NO_INFO}.
     *
     * @return int counts
     */
    public int[] intUpdateCounts() {
        int[] out = new int[updateCounts.length];
        for (int i = 0; i < out.length; i++) {
            long v = updateCounts[i];
            out[i] = (v > Integer.MAX_VALUE || v < Integer.MIN_VALUE) ? java.sql.Statement.SUCCESS_NO_INFO : (int) v;
        }
        return out;
    }

    @Override
    public MessageType type() {
        return MessageType.BATCH_RESULT;
    }

    @Override
    public void encode(ProtocolOutput out) {
        out.writeI32(updateCounts.length);
        for (long c : updateCounts) {
            out.writeI64(c);
        }
        Codec.writeWarnings(out, warnings);
    }

    /**
     * Decodes the payload.
     *
     * @param in source
     * @return the message
     * @throws ProtocolException if malformed
     */
    public static BatchResult decode(ProtocolInput in) throws ProtocolException {
        int n = in.readCount("updateCount", 8);
        long[] counts = new long[n];
        for (int i = 0; i < n; i++) {
            counts[i] = in.readI64();
        }
        List<Warning> warnings = Codec.readWarnings(in);
        return new BatchResult(counts, warnings);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof BatchResult b && Arrays.equals(b.updateCounts, updateCounts) && b.warnings.equals(warnings);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(updateCounts) + warnings.hashCode();
    }

    @Override
    public String toString() {
        return "BatchResult[updateCounts=" + Arrays.toString(updateCounts) + ", warnings=" + warnings + "]";
    }
}
