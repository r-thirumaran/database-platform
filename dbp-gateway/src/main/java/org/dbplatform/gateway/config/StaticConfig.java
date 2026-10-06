package org.dbplatform.gateway.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Static (control-plane-less) gateway configuration loaded from the YAML file named by
 * {@code DBP_GATEWAY_CONFIG}. See {@code gateway-example.yaml} and the module README for the schema.
 *
 * @param gatewayId    identifier reported in telemetry and {@code v$session.program} (env {@code DBP_GATEWAY_ID} wins)
 * @param datasources  logical datasources and their physical pools
 * @param applications optional application registry; when {@code null}/empty any {@code application} hint is accepted
 */
public record StaticConfig(
        @JsonProperty("gatewayId") String gatewayId,
        @JsonProperty("datasources") List<DatasourceConfig> datasources,
        @JsonProperty("applications") List<ApplicationConfig> applications) {

    public StaticConfig {
        datasources = datasources == null ? List.of() : List.copyOf(datasources);
        applications = applications == null ? List.of() : List.copyOf(applications);
    }

    /** Returns the datasource with the given name or {@code null}. */
    public DatasourceConfig datasource(String name) {
        for (DatasourceConfig d : datasources) {
            if (d.name().equals(name)) {
                return d;
            }
        }
        return null;
    }

    /** Returns {@code true} when an application registry is configured (api keys required). */
    public boolean hasApplications() {
        return !applications.isEmpty();
    }

    /** Masks a secret for logs and {@code toString()}: {@code null} stays {@code null}, anything else is {@code ****}. */
    static String mask(String secret) {
        return secret == null ? "null" : "****";
    }

    /** Masks the values of properties whose name looks like a secret (password, passwd, pwd, secret, token). */
    static Map<String, String> maskSecrets(Map<String, String> props) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : props.entrySet()) {
            String k = e.getKey().toLowerCase(Locale.ROOT);
            boolean secret = k.contains("password") || k.contains("passwd") || k.contains("pwd") || k.contains("secret")
                    || k.contains("token");
            out.put(e.getKey(), secret ? mask(e.getValue()) : e.getValue());
        }
        return out;
    }

    /**
     * One logical datasource backed by one physical database.
     */
    public record DatasourceConfig(
            @JsonProperty("name") String name,
            @JsonProperty("engine") String engine,
            @JsonProperty("jdbcUrl") String jdbcUrl,
            @JsonProperty("username") String username,
            @JsonProperty("password") String password,
            @JsonProperty("passwordEnv") String passwordEnv,
            @JsonProperty("passwordFile") String passwordFile,
            @JsonProperty("poolMode") String poolMode,
            @JsonProperty("maxConnections") Integer maxConnections,
            @JsonProperty("minIdle") Integer minIdle,
            @JsonProperty("connectionTimeoutMs") Long connectionTimeoutMs,
            @JsonProperty("idleTimeoutMs") Long idleTimeoutMs,
            @JsonProperty("maxLifetimeMs") Long maxLifetimeMs,
            @JsonProperty("statementTimeoutSeconds") Integer statementTimeoutSeconds,
            @JsonProperty("validationQuery") String validationQuery,
            @JsonProperty("jdbcProperties") Map<String, String> jdbcProperties) {

        public DatasourceConfig {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("datasource name is required");
            }
            if (jdbcUrl == null || jdbcUrl.isBlank()) {
                throw new IllegalArgumentException("datasource '" + name + "': jdbcUrl is required");
            }
            jdbcProperties = jdbcProperties == null ? Map.of() : Map.copyOf(jdbcProperties);
        }

        /** Convenience constructor for programmatic (test) configuration. */
        public static DatasourceConfig of(String name, String engine, String jdbcUrl, String username, String password,
                                          String poolMode, int maxConnections) {
            return new DatasourceConfig(name, engine, jdbcUrl, username, password, null, null, poolMode, maxConnections,
                    null, null, null, null, null, null, null);
        }

        public DatasourceConfig withConnectionTimeoutMs(long ms) {
            return new DatasourceConfig(name, engine, jdbcUrl, username, password, passwordEnv, passwordFile, poolMode,
                    maxConnections, minIdle, ms, idleTimeoutMs, maxLifetimeMs, statementTimeoutSeconds, validationQuery,
                    jdbcProperties);
        }

        public DatasourceConfig withMinIdle(int n) {
            return new DatasourceConfig(name, engine, jdbcUrl, username, password, passwordEnv, passwordFile, poolMode,
                    maxConnections, n, connectionTimeoutMs, idleTimeoutMs, maxLifetimeMs, statementTimeoutSeconds,
                    validationQuery, jdbcProperties);
        }

        public DatasourceConfig withJdbcProperties(Map<String, String> props) {
            return new DatasourceConfig(name, engine, jdbcUrl, username, password, passwordEnv, passwordFile, poolMode,
                    maxConnections, minIdle, connectionTimeoutMs, idleTimeoutMs, maxLifetimeMs, statementTimeoutSeconds,
                    validationQuery, props);
        }

        public DatasourceConfig withStatementTimeoutSeconds(int seconds) {
            return new DatasourceConfig(name, engine, jdbcUrl, username, password, passwordEnv, passwordFile, poolMode,
                    maxConnections, minIdle, connectionTimeoutMs, idleTimeoutMs, maxLifetimeMs, seconds,
                    validationQuery, jdbcProperties);
        }

        /** The record's default form would print the password; secrets are masked here. */
        @Override
        public String toString() {
            return "DatasourceConfig[name=" + name + ", engine=" + engine + ", jdbcUrl=" + jdbcUrl + ", username=" + username
                    + ", password=" + mask(password) + ", passwordEnv=" + passwordEnv + ", passwordFile=" + passwordFile
                    + ", poolMode=" + poolMode + ", maxConnections=" + maxConnections + ", minIdle=" + minIdle
                    + ", connectionTimeoutMs=" + connectionTimeoutMs + ", idleTimeoutMs=" + idleTimeoutMs
                    + ", maxLifetimeMs=" + maxLifetimeMs + ", statementTimeoutSeconds=" + statementTimeoutSeconds
                    + ", validationQuery=" + validationQuery + ", jdbcProperties=" + maskSecrets(jdbcProperties) + "]";
        }
    }

    /**
     * An application known to the gateway in static mode.
     */
    public record ApplicationConfig(
            @JsonProperty("name") String name,
            @JsonProperty("apiKey") String apiKey,
            @JsonProperty("team") String team,
            @JsonProperty("datasources") List<GrantConfig> datasources) {

        public ApplicationConfig {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("application name is required");
            }
            datasources = datasources == null ? List.of() : List.copyOf(datasources);
        }

        public GrantConfig grant(String datasource) {
            for (GrantConfig g : datasources) {
                if (g.name().equals(datasource)) {
                    return g;
                }
            }
            return null;
        }

        /** The api key is a credential: masked. */
        @Override
        public String toString() {
            return "ApplicationConfig[name=" + name + ", apiKey=" + mask(apiKey) + ", team=" + team + ", datasources="
                    + datasources + "]";
        }
    }

    /**
     * A grant entry of an application: either a plain datasource name or an object with options.
     */
    public record GrantConfig(
            @JsonProperty("name") String name,
            @JsonProperty("maxLogicalConnections") Integer maxLogicalConnections,
            @JsonProperty("readOnly") Boolean readOnly,
            @JsonProperty("poolMode") String poolMode) {

        public GrantConfig {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("grant datasource name is required");
            }
        }

        /** Plain-string form: {@code datasources: [sales, inventory]}. */
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        public static GrantConfig of(String name) {
            return new GrantConfig(name, null, null, null);
        }
    }
}
