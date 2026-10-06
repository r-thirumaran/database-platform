package org.dbplatform.controlplane;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** The shipped application.yml: the H2 dev store waits 10 s for locks (H2's default is 2 s), the PostgreSQL profile carries no H2 setting. */
class DevStoreConfigTest {

    private static PropertySource<?> profileDocument(String profile) throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        return documents.stream().filter(d -> profile.equals(d.getProperty("spring.config.activate.on-profile"))).findFirst()
                .orElseThrow(() -> new AssertionError("no '" + profile + "' document in application.yml"));
    }

    @Test
    void devProfileDefaultH2UrlSetsLockTimeout() throws IOException {
        String url = String.valueOf(profileDocument("dev").getProperty("spring.datasource.url"));
        assertThat(url).startsWith("${DBP_DB_URL:jdbc:h2:file:./data/dbp;").contains(";MODE=PostgreSQL;").endsWith(";LOCK_TIMEOUT=10000}");
    }

    @Test
    void postgresProfileHasNoH2Setting() throws IOException {
        String url = String.valueOf(profileDocument("postgres").getProperty("spring.datasource.url"));
        assertThat(url).contains("jdbc:postgresql:").doesNotContain("LOCK_TIMEOUT");
    }

    @Test
    void updatesAreFlushedInPrimaryKeyOrder() throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        assertThat(documents.get(0).getProperty("spring.jpa.properties.hibernate.order_updates")).isEqualTo(true);
    }
}
