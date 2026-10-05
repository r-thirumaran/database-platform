package org.dbplatform.common.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UtilTest {

    @AfterEach
    void reset() {
        Env.setEnvReader(null);
        System.clearProperty("DBP_TEST_X");
        System.clearProperty("dbp.test.y");
    }

    @Test
    void envLookupOrderAndParsing() {
        Env.setEnvReader(Map.of("DBP_TEST_X", "from-env", "DBP_FLAG", "yes", "DBP_NUM", "42", "DBP_DUR", "2s")::get);
        System.setProperty("DBP_TEST_X", "from-prop");
        System.setProperty("dbp.test.y", "dotted");
        assertThat(Env.get("DBP_TEST_X", "d")).isEqualTo("from-env");
        assertThat(Env.get("TEST_Y", "d")).isEqualTo("dotted");
        assertThat(Env.get("dbp.test.y", "d")).isEqualTo("dotted");
        assertThat(Env.get("DBP_MISSING", "d")).isEqualTo("d");
        assertThat(Env.getBoolean("FLAG", false)).isTrue();
        assertThat(Env.getInt("NUM", 1)).isEqualTo(42);
        assertThat(Env.getLong("NUM", 1)).isEqualTo(42L);
        assertThat(Env.getDuration("DUR", Duration.ZERO)).isEqualTo(Duration.ofSeconds(2));
        assertThat(Env.getDuration("MISSING", Duration.ofMillis(5))).isEqualTo(Duration.ofMillis(5));
        assertThat(Env.getEnum("MISSING", java.time.DayOfWeek.class, java.time.DayOfWeek.MONDAY)).isEqualTo(java.time.DayOfWeek.MONDAY);
        assertThatThrownBy(() -> Env.require("DBP_MISSING")).isInstanceOf(IllegalStateException.class).hasMessageContaining("DBP_MISSING");
        Env.setEnvReader(Map.of("DBP_NUM", "abc")::get);
        assertThatThrownBy(() -> Env.getInt("NUM", 1)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("DBP_NUM");
    }

    @Test
    void durationsParseAndFormat() {
        assertThat(Durations.parse("2000")).isEqualTo(Duration.ofMillis(2000));
        assertThat(Durations.parse("500ms")).isEqualTo(Duration.ofMillis(500));
        assertThat(Durations.parse("2s")).isEqualTo(Duration.ofSeconds(2));
        assertThat(Durations.parse("5m")).isEqualTo(Duration.ofMinutes(5));
        assertThat(Durations.parse("1h")).isEqualTo(Duration.ofHours(1));
        assertThat(Durations.parse("1h30m")).isEqualTo(Duration.ofMinutes(90));
        assertThat(Durations.parse("1.5s")).isEqualTo(Duration.ofMillis(1500));
        assertThat(Durations.parse("PT2S")).isEqualTo(Duration.ofSeconds(2));
        assertThat(Durations.parse(" 2 days ")).isEqualTo(Duration.ofDays(2));
        assertThat(Durations.parse("-3s")).isEqualTo(Duration.ofSeconds(-3));
        assertThatThrownBy(() -> Durations.parse("abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Durations.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThat(Durations.parseOrDefault("zzz", Duration.ofSeconds(1))).isEqualTo(Duration.ofSeconds(1));
        assertThat(Durations.format(Duration.ofMillis(5_432_100))).isEqualTo("1h30m32s100ms");
        assertThat(Durations.format(Duration.ZERO)).isEqualTo("0ms");
    }

    @Test
    void idsAreUniqueSortableUlids() {
        Set<String> seen = new HashSet<>();
        String prev = "";
        for (int i = 0; i < 10_000; i++) {
            String id = Ids.ulid();
            assertThat(id).hasSize(26).matches("[0-9A-HJKMNP-TV-Z]{26}");
            assertThat(seen.add(id)).isTrue();
            assertThat(id.compareTo(prev)).isGreaterThan(0);
            prev = id;
        }
        long t = Ids.ulidTimestamp(Ids.ulid());
        assertThat(t).isBetween(System.currentTimeMillis() - 5_000, System.currentTimeMillis() + 5_000);
        assertThat(Ids.uuid()).hasSize(36);
        assertThat(Ids.token(16)).hasSize(16);
    }

    @Test
    void hostnamesNeverBlank() {
        assertThat(Hostnames.localHostName()).isNotBlank();
        assertThat(Hostnames.localShortName()).isNotBlank().doesNotContain(".");
        assertThat(Hostnames.localHostName()).isSameAs(Hostnames.localHostName());
    }
}
