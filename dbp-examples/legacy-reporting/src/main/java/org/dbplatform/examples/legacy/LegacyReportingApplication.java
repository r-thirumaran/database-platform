package org.dbplatform.examples.legacy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The "legacy application that only changes its URL". Oracle thin driver, Oracle-specific SQL
 * ({@code (+)} outer joins, {@code NVL}, {@code SYSDATE}, {@code TO_CHAR}), no platform library.
 * Its JDBC URL points at the proxy ({@code sales.legacy-reporting}), which is all the platform needs
 * to attribute its sessions and statements.
 */
@SpringBootApplication
public class LegacyReportingApplication {

    public static void main(String[] args) {
        SpringApplication.run(LegacyReportingApplication.class, args);
    }
}
