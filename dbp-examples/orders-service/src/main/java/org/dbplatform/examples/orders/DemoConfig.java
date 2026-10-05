package org.dbplatform.examples.orders;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Arrays;

@Configuration
public class DemoConfig {

    @Bean
    SqlDialect sqlDialect(@Value("${dbp.demo.engine:ORACLE}") String engine) {
        return SqlDialect.fromEngine(engine);
    }

    @Bean
    DemoInfo demoInfo(Environment environment, SqlDialect dialect,
                      @Value("${spring.datasource.url:}") String jdbcUrl,
                      @Value("${spring.datasource.username:}") String username) {
        String[] profiles = environment.getActiveProfiles();
        String mode = profiles.length == 0 ? "default" : String.join(",", Arrays.asList(profiles));
        return new DemoInfo(mode, dialect.name(), maskUrl(jdbcUrl), username);
    }

    /** Strips query-string credentials (e.g. apiKey=...) from a JDBC URL before exposing it. */
    static String maskUrl(String url) {
        if (url == null) {
            return "";
        }
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q) + "?...";
    }

    /** Static facts about this instance, shown by the health endpoint. */
    public record DemoInfo(String mode, String engine, String jdbcUrl, String username) {
    }
}
