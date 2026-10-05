package org.dbplatform.proxy.postgres;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PgStartupMessageTest {
    @Test
    void parsesStartupParametersInOrderAndRewritesDatabase() throws IOException {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("user", "app");
        p.put("database", "sales.orders-service");
        p.put("application_name", "orders");
        p.put("client_encoding", "UTF8");
        PgStartupMessage m = PgStartupMessage.build(PgStartupMessage.PROTOCOL_3_0, p);
        assertThat(PgStartupMessage.i32(m.raw(), 0)).isEqualTo(m.raw().length);
        assertThat(m.raw()[m.raw().length - 1]).isZero();

        PgStartupMessage parsed = PgStartupMessage.read(new ByteArrayInputStream(m.raw()));
        assertThat(parsed.isStartup()).isTrue();
        assertThat(parsed.protocolMajor()).isEqualTo(3);
        assertThat(parsed.user()).isEqualTo("app");
        assertThat(parsed.database()).isEqualTo("sales.orders-service");
        assertThat(parsed.applicationName()).isEqualTo("orders");
        assertThat(parsed.paramKeys()).containsExactly("user", "database", "application_name", "client_encoding");

        PgStartupMessage rewritten = parsed.withParam("database", "postgres");
        assertThat(rewritten.database()).isEqualTo("postgres");
        assertThat(rewritten.paramKeys()).containsExactly("user", "database", "application_name", "client_encoding");
        assertThat(PgStartupMessage.i32(rewritten.raw(), 0)).isEqualTo(rewritten.raw().length);
        assertThat(rewritten.raw().length).isEqualTo(m.raw().length - "sales.orders-service".length() + "postgres".length());
    }

    @Test
    void databaseDefaultsToUserAndSpecialRequestsAreRecognised() throws IOException {
        PgStartupMessage m = PgStartupMessage.build(PgStartupMessage.PROTOCOL_3_0, Map.of("user", "bob"));
        assertThat(m.database()).isEqualTo("bob");
        assertThat(m.hasExplicitDatabase()).isFalse();

        byte[] ssl = new byte[8];
        PgStartupMessage.putI32(ssl, 0, 8);
        PgStartupMessage.putI32(ssl, 4, PgStartupMessage.SSL_REQUEST);
        assertThat(PgStartupMessage.parse(ssl).isSslRequest()).isTrue();

        byte[] cancel = new byte[16];
        PgStartupMessage.putI32(cancel, 0, 16);
        PgStartupMessage.putI32(cancel, 4, PgStartupMessage.CANCEL_REQUEST);
        PgStartupMessage.putI32(cancel, 8, 4242);
        PgStartupMessage c = PgStartupMessage.parse(cancel);
        assertThat(c.isCancelRequest()).isTrue();
        assertThat(c.cancelPid()).isEqualTo(4242);
        assertThat(c.isStartup()).isFalse();
    }

    @Test
    void rejectsAbsurdLengths() {
        byte[] bad = new byte[8];
        PgStartupMessage.putI32(bad, 0, 1 << 20);
        PgStartupMessage.putI32(bad, 4, PgStartupMessage.PROTOCOL_3_0);
        assertThatThrownBy(() -> PgStartupMessage.read(new ByteArrayInputStream(bad))).isInstanceOf(PgProtocolException.class);
    }

    @Test
    void errorResponseLayout() {
        byte[] e = PgErrorResponse.fatal("53300", "too many");
        assertThat(e[0]).isEqualTo((byte) 'E');
        assertThat(PgStartupMessage.i32(e, 1)).isEqualTo(e.length - 1);
        String body = new String(e, 5, e.length - 5, StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("SFATAL\0VFATAL\0C53300\0Mtoo many\0\0");
    }
}
