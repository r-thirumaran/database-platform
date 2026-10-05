package org.dbplatform.protocol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueTagTest {

    @Test
    void codesAreDenseFromZeroToSixteen() throws ProtocolException {
        ValueTag[] tags = ValueTag.values();
        assertThat(tags).hasSize(17);
        for (int i = 0; i < tags.length; i++) {
            assertThat(tags[i].code()).isEqualTo(i);
            assertThat(ValueTag.fromCode(i)).isSameAs(tags[i]);
        }
    }

    @Test
    void namesMatchSpecification() {
        assertThat(ValueTag.values()).extracting(Enum::name).containsExactly(
                "NULL", "BOOLEAN", "BYTE", "SHORT", "INT", "LONG", "FLOAT", "DOUBLE", "DECIMAL", "STRING", "BYTES",
                "DATE", "TIME", "TIMESTAMP", "TIMESTAMP_TZ", "TIME_TZ", "TYPED_NULL");
    }

    @Test
    void unknownCodesAreRejected() {
        for (int code : new int[] {-1, 17, 100, 255}) {
            assertThatThrownBy(() -> ValueTag.fromCode(code))
                    .isInstanceOf(ProtocolException.class)
                    .hasMessageContaining("unknown value tag");
        }
    }
}
