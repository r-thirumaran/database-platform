package org.dbplatform.protocol;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcUrlTest {

    @Test
    void minimalUrlUsesDefaultPortAndNoProperties() {
        JdbcUrl u = JdbcUrl.parse("jdbc:dbp://gateway/sales");
        assertThat(u.hosts()).containsExactly(new JdbcUrl.HostPort("gateway", 7420));
        assertThat(u.datasource()).isEqualTo("sales");
        assertThat(u.properties()).isEmpty();
        assertThat(u.property("apiKey")).isNull();
    }

    @Test
    void explicitPortMultipleHostsAndProperties() {
        JdbcUrl u = JdbcUrl.parse("jdbc:dbp://gw1:7420,gw2,gw3:9999/orders_db?apiKey=dbp_abc_secret&ssl=true&fetchSize=50");
        assertThat(u.hosts()).containsExactly(
                new JdbcUrl.HostPort("gw1", 7420),
                new JdbcUrl.HostPort("gw2", 7420),
                new JdbcUrl.HostPort("gw3", 9999));
        assertThat(u.datasource()).isEqualTo("orders_db");
        assertThat(u.properties()).containsExactly(
                Map.entry("apiKey", "dbp_abc_secret"),
                Map.entry("ssl", "true"),
                Map.entry("fetchSize", "50"));
    }

    @Test
    void ipv6LiteralsAndIpv4() {
        JdbcUrl u = JdbcUrl.parse("jdbc:dbp://[::1]:7421,[fe80::1%25eth0],10.0.0.5:7000/ds");
        assertThat(u.hosts()).containsExactly(
                new JdbcUrl.HostPort("::1", 7421),
                new JdbcUrl.HostPort("fe80::1%25eth0", 7420),
                new JdbcUrl.HostPort("10.0.0.5", 7000));
        assertThat(u.hosts().get(0).toString()).isEqualTo("[::1]:7421");
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:dbp://::1:7421/ds")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IPv6");
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:dbp://[::1/ds")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:dbp://[::1]x/ds")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void propertyEdgeCases() {
        JdbcUrl u = JdbcUrl.parse("jdbc:dbp://h/ds?ssl&a=&b=x%3Dy%26z&clientInfo.ApplicationName=My+App%20Name&&dup=1&dup=2&spaces=a%20b");
        assertThat(u.properties())
                .containsEntry("ssl", "true")
                .containsEntry("a", "")
                .containsEntry("b", "x=y&z")
                .containsEntry("clientInfo.ApplicationName", "My App Name")
                .containsEntry("dup", "2")
                .containsEntry("spaces", "a b")
                .hasSize(6);
        assertThat(JdbcUrl.parse("jdbc:dbp://h/ds?").properties()).isEmpty();
        assertThat(JdbcUrl.parse("jdbc:dbp://h/ds?a=1?b=2").property("a")).isEqualTo("1?b=2");
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:dbp://h/ds?=v")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty property name");
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:dbp://h/ds?a=%zz")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("percent-encoding");
    }

    @Test
    void datasourceIsPercentDecodedAndMustBeASingleSegment() {
        assertThat(JdbcUrl.parse("jdbc:dbp://h/my%20ds").datasource()).isEqualTo("my ds");
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:dbp://h/")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("datasource is empty");
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:dbp://h/?a=b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:dbp://h")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing '/<datasource>'");
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:dbp://h/a/b")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'/'");
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:dbp://h/a/")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hostAndPortValidation() {
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:dbp:///ds")).hasMessageContaining("missing gateway host");
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:dbp://h,/ds")).hasMessageContaining("empty host");
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:dbp://,h/ds")).hasMessageContaining("empty host");
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:dbp://:7420/ds")).hasMessageContaining("empty host");
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:dbp://h:/ds")).hasMessageContaining("empty port");
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:dbp://h:abc/ds")).hasMessageContaining("invalid port");
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:dbp://h:0/ds")).hasMessageContaining("port out of range");
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:dbp://h:65536/ds")).hasMessageContaining("port out of range");
        assertThat(JdbcUrl.parse("jdbc:dbp://h:65535/ds").hosts().get(0).port()).isEqualTo(65535);
        assertThat(JdbcUrl.parse("jdbc:dbp:// h , k /ds").hosts()).extracting(JdbcUrl.HostPort::host).containsExactly("h", "k");
    }

    @Test
    void acceptsUrlIsPrefixBasedAndCaseInsensitive() {
        assertThat(JdbcUrl.acceptsUrl("jdbc:dbp://h/ds")).isTrue();
        assertThat(JdbcUrl.acceptsUrl("JDBC:DBP://h/ds")).isTrue();
        assertThat(JdbcUrl.acceptsUrl("jdbc:dbp://")).isTrue();
        assertThat(JdbcUrl.acceptsUrl("jdbc:dbp:/h/ds")).isFalse();
        assertThat(JdbcUrl.acceptsUrl("jdbc:postgresql://h/ds")).isFalse();
        assertThat(JdbcUrl.acceptsUrl("jdbc:dbpx://h/ds")).isFalse();
        assertThat(JdbcUrl.acceptsUrl("")).isFalse();
        assertThat(JdbcUrl.acceptsUrl(null)).isFalse();
        assertThat(JdbcUrl.parse("JDBC:DBP://h/ds").datasource()).isEqualTo("ds");
        assertThatThrownBy(() -> JdbcUrl.parse("jdbc:oracle:thin:@h:1521/x")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a DBP JDBC URL");
        assertThatThrownBy(() -> JdbcUrl.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void mergedPropertiesLetUrlWin() {
        JdbcUrl u = JdbcUrl.parse("jdbc:dbp://h/ds?apiKey=fromUrl&fetchSize=10");
        Properties info = new Properties();
        info.setProperty("apiKey", "fromProps");
        info.setProperty("user", "app");
        info.put("notAString", 42);
        Map<String, String> merged = u.mergedProperties(info);
        assertThat(merged).containsEntry("apiKey", "fromUrl").containsEntry("fetchSize", "10").containsEntry("user", "app")
                .doesNotContainKey("notAString").hasSize(3);
        assertThat(u.mergedProperties(null)).containsExactlyInAnyOrderEntriesOf(u.properties());
    }

    @Test
    void toStringRendersCanonicalFormThatParsesBack() {
        String original = "jdbc:dbp://gw1,[::1]:7000/my%20ds?apiKey=a%26b&x=1";
        JdbcUrl u = JdbcUrl.parse(original);
        String rendered = u.toString();
        assertThat(rendered).isEqualTo("jdbc:dbp://gw1:7420,[::1]:7000/my%20ds?apiKey=a%26b&x=1");
        assertThat(JdbcUrl.parse(rendered)).isEqualTo(u);
    }

    @Test
    void recordIsImmutableAndValidated() {
        JdbcUrl u = JdbcUrl.parse("jdbc:dbp://h/ds?a=1");
        assertThatThrownBy(() -> u.properties().put("b", "2")).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> u.hosts().add(new JdbcUrl.HostPort("x", 1))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new JdbcUrl(java.util.List.of(), "ds", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcUrl(u.hosts(), "", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcUrl.HostPort("", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JdbcUrl.HostPort("h", 70000)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new JdbcUrl(u.hosts(), "ds", null).properties()).isEmpty();
    }
}
