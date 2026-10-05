package org.dbplatform.controlplane.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All {@code dbp.*} settings. Every property has an environment variable counterpart documented in
 * the module README (the defaults in {@code application.yml} use {@code ${DBP_...:default}}).
 */
@ConfigurationProperties(prefix = "dbp")
public class DbpProperties {

    /** Shared secret gateways/proxies send as {@code X-DBP-Service-Token}. */
    private String serviceToken = "dev-service-token";
    /** Master key (any string; sha-256 derived) used to AES-GCM encrypt INLINE credential secrets. */
    private String masterKey;
    /** Seconds after the last heartbeat a component is still considered healthy. */
    private int componentHealthySeconds = 30;

    private final Security security = new Security();
    private final Telemetry telemetry = new Telemetry();
    private final Governance governance = new Governance();
    private final Relationship relationship = new Relationship();
    private final Demo demo = new Demo();
    private final Collector collector = new Collector();
    private final Cors cors = new Cors();

    public static class Security {
        /** {@code none} or {@code basic}. */
        private String mode = "none";
        private String adminUser = "admin";
        private String adminPassword = "admin";

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getAdminUser() { return adminUser; }
        public void setAdminUser(String adminUser) { this.adminUser = adminUser; }
        public String getAdminPassword() { return adminPassword; }
        public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
        public boolean isBasic() { return "basic".equalsIgnoreCase(mode); }
    }

    public static class Telemetry {
        /** Raw query/connection events and pool snapshots are kept for this many hours. */
        private int retentionHours = 72;
        /** Hourly query statistics are kept for this many days. */
        private int statsRetentionDays = 30;
        /** How often the retention cleanup runs. */
        private int cleanupIntervalSeconds = 600;

        public int getRetentionHours() { return retentionHours; }
        public void setRetentionHours(int retentionHours) { this.retentionHours = retentionHours; }
        public int getStatsRetentionDays() { return statsRetentionDays; }
        public void setStatsRetentionDays(int statsRetentionDays) { this.statsRetentionDays = statsRetentionDays; }
        public int getCleanupIntervalSeconds() { return cleanupIntervalSeconds; }
        public void setCleanupIntervalSeconds(int cleanupIntervalSeconds) { this.cleanupIntervalSeconds = cleanupIntervalSeconds; }
    }

    public static class Governance {
        private boolean enabled = true;
        private int intervalSeconds = 300;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getIntervalSeconds() { return intervalSeconds; }
        public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }
    }

    public static class Relationship {
        /** A relationship with no activity for this many days is reported as stale. */
        private int staleDays = 30;

        public int getStaleDays() { return staleDays; }
        public void setStaleDays(int staleDays) { this.staleDays = staleDays; }
    }

    public static class Demo {
        /** Whether {@code POST /api/v1/seed/demo} is enabled. */
        private boolean seedEnabled = true;
        /** Load the demo dataset automatically when the application starts. */
        private boolean seedOnStartup = false;

        public boolean isSeedEnabled() { return seedEnabled; }
        public void setSeedEnabled(boolean seedEnabled) { this.seedEnabled = seedEnabled; }
        public boolean isSeedOnStartup() { return seedOnStartup; }
        public void setSeedOnStartup(boolean seedOnStartup) { this.seedOnStartup = seedOnStartup; }
    }

    public static class Collector {
        /** Global switch for the database collectors (each database also has its own flag). */
        private boolean enabled = true;
        /** Scheduler tick; each database runs when its own interval has elapsed. */
        private int tickSeconds = 5;
        /** JDBC connect/read timeout for collector connections. */
        private int connectTimeoutSeconds = 10;
        /** Maximum number of distinct SQL_IDs fetched from V$SQL per runtime sample. */
        private int maxSqlPerSample = 200;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTickSeconds() { return tickSeconds; }
        public void setTickSeconds(int tickSeconds) { this.tickSeconds = tickSeconds; }
        public int getConnectTimeoutSeconds() { return connectTimeoutSeconds; }
        public void setConnectTimeoutSeconds(int connectTimeoutSeconds) { this.connectTimeoutSeconds = connectTimeoutSeconds; }
        public int getMaxSqlPerSample() { return maxSqlPerSample; }
        public void setMaxSqlPerSample(int maxSqlPerSample) { this.maxSqlPerSample = maxSqlPerSample; }
    }

    public static class Cors {
        private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:5173"));

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
    }

    public String getServiceToken() { return serviceToken; }
    public void setServiceToken(String serviceToken) { this.serviceToken = serviceToken; }
    public String getMasterKey() { return masterKey; }
    public void setMasterKey(String masterKey) { this.masterKey = masterKey; }
    public int getComponentHealthySeconds() { return componentHealthySeconds; }
    public void setComponentHealthySeconds(int componentHealthySeconds) { this.componentHealthySeconds = componentHealthySeconds; }
    public Security getSecurity() { return security; }
    public Telemetry getTelemetry() { return telemetry; }
    public Governance getGovernance() { return governance; }
    public Relationship getRelationship() { return relationship; }
    public Demo getDemo() { return demo; }
    public Collector getCollector() { return collector; }
    public Cors getCors() { return cors; }
}
