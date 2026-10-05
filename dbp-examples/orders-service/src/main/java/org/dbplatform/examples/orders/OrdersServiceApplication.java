package org.dbplatform.examples.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Orders service example. The same binary runs in four modes, chosen by the active Spring profile:
 * <ul>
 *   <li>{@code direct}: Oracle thin driver straight to the database (bypasses the platform)</li>
 *   <li>{@code proxy}: Oracle thin driver to the transparent proxy ({@code sales.orders-service} service alias)</li>
 *   <li>{@code gateway}: platform driver {@code jdbc:dbp://gateway:7420/sales} with an API key</li>
 *   <li>{@code postgres-direct}: PostgreSQL driver to the migration target</li>
 * </ul>
 * No Java code changes between the modes; only {@code application.yml} / environment differ.
 */
@SpringBootApplication
public class OrdersServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrdersServiceApplication.class, args);
    }
}
