package org.dbplatform.examples.legacy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
public class ReportController {

    private final JdbcTemplate jdbc;
    private final String jdbcUrl;

    public ReportController(JdbcTemplate jdbc, @Value("${spring.datasource.url:}") String jdbcUrl) {
        this.jdbc = jdbc;
        this.jdbcUrl = jdbcUrl;
    }

    /** GET /report/customers?country=DE */
    @GetMapping("/report/customers")
    public List<Map<String, Object>> customers(@RequestParam(defaultValue = "DE") String country) {
        return jdbc.queryForList(LegacyQueries.CUSTOMERS_BY_COUNTRY, country.toUpperCase());
    }

    /** GET /report/revenue?months=6 */
    @GetMapping("/report/revenue")
    public List<Map<String, Object>> revenue(@RequestParam(defaultValue = "6") int months) {
        return jdbc.queryForList(LegacyQueries.REVENUE_BY_MONTH, Math.max(1, Math.min(months, 36)));
    }

    /** GET /report/open-orders?limit=25 */
    @GetMapping("/report/open-orders")
    public List<Map<String, Object>> openOrders(@RequestParam(defaultValue = "25") int limit) {
        return jdbc.queryForList(LegacyQueries.OPEN_ORDERS, Math.max(1, Math.min(limit, 500)));
    }

    /** GET /health */
    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("application", "legacy-reporting");
        body.put("jdbcUrl", jdbcUrl);
        try {
            body.put("databaseTime", jdbc.queryForObject(LegacyQueries.PING, Object.class));
            body.put("status", "UP");
            return ResponseEntity.ok(body);
        } catch (RuntimeException e) {
            body.put("status", "DOWN");
            body.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
        }
    }
}
