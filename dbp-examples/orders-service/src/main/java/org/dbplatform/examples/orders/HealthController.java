package org.dbplatform.examples.orders;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /health}: a database round trip plus pool numbers, so the difference between the
 * application's view (logical connections) and the gateway's view (physical connections) is easy to
 * show side by side with the control-plane UI.
 */
@RestController
public class HealthController {

    private final OrdersRepository repository;
    private final DataSource dataSource;
    private final DemoConfig.DemoInfo info;

    public HealthController(OrdersRepository repository, DataSource dataSource, DemoConfig.DemoInfo info) {
        this.repository = repository;
        this.dataSource = dataSource;
        this.info = info;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("application", "orders-service");
        body.put("mode", info.mode());
        body.put("engine", info.engine());
        body.put("jdbcUrl", info.jdbcUrl());
        body.put("username", info.username());
        body.put("pool", poolStats());
        try {
            body.put("database", repository.ping() ? "UP" : "DOWN");
            body.put("status", "UP");
            return ResponseEntity.ok(body);
        } catch (RuntimeException e) {
            body.put("database", "DOWN");
            body.put("status", "DOWN");
            body.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }

    private Map<String, Object> poolStats() {
        Map<String, Object> pool = new LinkedHashMap<>();
        if (dataSource instanceof HikariDataSource hikari) {
            pool.put("name", hikari.getPoolName());
            pool.put("max", hikari.getMaximumPoolSize());
            HikariPoolMXBean mx = hikari.getHikariPoolMXBean();
            if (mx != null) {
                pool.put("active", mx.getActiveConnections());
                pool.put("idle", mx.getIdleConnections());
                pool.put("total", mx.getTotalConnections());
                pool.put("waiting", mx.getThreadsAwaitingConnection());
            }
        }
        return pool;
    }
}
