package org.dbplatform.protocol;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MessageTypeTest {

    /** The code table from section 4 of docs/wire-protocol.md, transcribed independently of the enum. */
    private static final Map<String, Integer> SPEC = Map.ofEntries(
            Map.entry("HELLO", 0x01), Map.entry("PING", 0x02), Map.entry("CLOSE", 0x03),
            Map.entry("PREPARE", 0x10), Map.entry("EXECUTE", 0x11), Map.entry("FETCH", 0x12),
            Map.entry("CLOSE_CURSOR", 0x13), Map.entry("CLOSE_STATEMENT", 0x14), Map.entry("EXECUTE_BATCH", 0x15),
            Map.entry("SET_AUTOCOMMIT", 0x20), Map.entry("COMMIT", 0x21), Map.entry("ROLLBACK", 0x22),
            Map.entry("SET_SAVEPOINT", 0x23), Map.entry("RELEASE_SAVEPOINT", 0x24),
            Map.entry("SET_TRANSACTION_ISOLATION", 0x25), Map.entry("SET_READ_ONLY", 0x26),
            Map.entry("SET_SCHEMA", 0x27), Map.entry("SET_CATALOG", 0x28), Map.entry("SET_CLIENT_INFO", 0x29),
            Map.entry("SET_NETWORK_TIMEOUT", 0x2A),
            Map.entry("METADATA", 0x30),
            Map.entry("OK", 0x40), Map.entry("ERROR", 0x41), Map.entry("HELLO_OK", 0x42), Map.entry("PONG", 0x43),
            Map.entry("PREPARED", 0x44), Map.entry("RESULT_SET_HEADER", 0x45), Map.entry("ROWS", 0x46),
            Map.entry("UPDATE_COUNT", 0x47), Map.entry("OUT_PARAMS", 0x48), Map.entry("EXECUTE_DONE", 0x49),
            Map.entry("GENERATED_KEYS", 0x4A), Map.entry("BATCH_RESULT", 0x4B), Map.entry("SAVEPOINT_SET", 0x50));

    @Test
    void codesMatchSpecification() {
        assertThat(MessageType.values()).hasSize(SPEC.size());
        for (MessageType t : MessageType.values()) {
            assertThat(SPEC).as("spec entry for %s", t).containsKey(t.name());
            assertThat(t.code()).as("code of %s", t).isEqualTo(SPEC.get(t.name()));
        }
    }

    @Test
    void codesAreUniqueAndRoundTrip() throws ProtocolException {
        Set<Integer> seen = new HashSet<>();
        for (MessageType t : MessageType.values()) {
            assertThat(seen.add(t.code())).isTrue();
            assertThat(MessageType.fromCode(t.code())).isSameAs(t);
        }
    }

    @Test
    void directionFollowsCodeRange() {
        for (MessageType t : MessageType.values()) {
            assertThat(t.isServerToClient()).isEqualTo(t.code() >= 0x40 && t.code() <= 0x7F);
        }
        assertThat(MessageType.HELLO.isServerToClient()).isFalse();
        assertThat(MessageType.OK.isServerToClient()).isTrue();
    }

    @Test
    void unknownCodesAreRejected() {
        for (int code : new int[] {0x00, 0x04, 0x0F, 0x16, 0x2B, 0x31, 0x3F, 0x4C, 0x4F, 0x51, 0x7F, 0x80, 0xFF, -1, 1000}) {
            assertThatThrownBy(() -> MessageType.fromCode(code))
                    .as("code 0x%x", code)
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("unknown message type");
        }
    }
}
