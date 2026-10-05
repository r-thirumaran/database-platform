package org.dbplatform.controlplane;

import org.dbplatform.controlplane.config.DbpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Control plane of the Database Access Platform.
 *
 * <p>Configuration (teams, applications, databases, datasources, credentials, grants), identity and
 * routing for the gateway/proxy, the metadata plane (catalogue, ownership, producer/consumer graph,
 * impact analysis), the telemetry sink, the database collectors and the static UI build.
 */
@SpringBootApplication
@EnableConfigurationProperties(DbpProperties.class)
@EnableScheduling
@EnableAsync
public class ControlPlaneApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }
}
