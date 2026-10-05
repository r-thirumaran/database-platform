# Operations guide

How to run, configure, monitor and troubleshoot the platform components. Written for platform
engineers and DBAs. Contract references: [control-plane-api.md](control-plane-api.md),
[wire-protocol.md](wire-protocol.md), [telemetry-events.md](telemetry-events.md).

Configuration follows one rule for every component (`CONTRIBUTING.md`): **YAML file + environment
variables prefixed `DBP_`; the environment wins.** The authoritative list of options for a component is
its module README (`dbp-gateway/README.md`, `dbp-proxy/README.md`, `dbp-control-plane/README.md`); this
page consolidates the ones an operator needs. Variable names and defaults below are the ones the modules
bind (`GatewayConfig`, `ProxySettings`, `application.yml` of the control plane).

## Components at a glance

| Component     | Artifact                                             | Main class / entry                                 | Ports                                   | State                                    |
|---------------|------------------------------------------------------|----------------------------------------------------|-----------------------------------------|------------------------------------------|
| control plane | `dbp-control-plane/target/dbp-control-plane.jar` (pom `finalName`, no version suffix) | `org.dbplatform.controlplane.ControlPlaneApplication` (Spring Boot) | 8080 (API + UI)             | metadata store (PostgreSQL; H2 for dev)  |
| gateway       | `dbp-gateway/target/dbp-gateway-<ver>-all.jar` (shaded, all JDBC drivers included) | `org.dbplatform.gateway.GatewayMain`               | 7420 wire protocol, 7421 admin          | stateless; caches config; owns pools      |
| proxy         | `dbp-proxy/target/dbp-proxy-<ver>-all.jar` (shaded)  | `org.dbplatform.proxy.ProxyMain`                   | 1521 / 5432 / 1433 listeners, 7431 admin | stateless; caches config                 |
| UI            | built into the control plane jar (`dbp-ui` build)    | served on 8080                                      | –                                       | –                                        |
| driver        | `dbp-jdbc/target/dbp-jdbc-<ver>-all.jar`             | `org.dbplatform.jdbc.DbpDriver`                    | –                                       | lives in the application                 |

Admin endpoints:

| Component | Port | Open (no auth)        | Requires `X-DBP-Service-Token`                                        |
|-----------|------|-----------------------|-----------------------------------------------------------------------|
| gateway   | 7421 | `/health`, `/metrics`, `/sessions`, `/pools` | – (restrict the port at the network layer)                  |
| proxy     | 7431 | `/health`, `/metrics` | `/connections`, `/config` — **whenever `DBP_SERVICE_TOKEN` is set** (it is in compose and the manifests); without a token (static mode on a workstation) they are open too |
| control plane | 8080 | `/actuator/health` (+ `/liveness`, `/readiness`), `/actuator/prometheus`, `/v3/api-docs`, `/swagger-ui.html`, public `/api/v1/**` (`DBP_SECURITY_MODE`) | `/api/v1/internal/**` |

```bash
curl http://proxy:7431/health
curl -H "X-DBP-Service-Token: $DBP_SERVICE_TOKEN" http://proxy:7431/connections
curl -H "X-DBP-Service-Token: $DBP_SERVICE_TOKEN" http://proxy:7431/config      # never contains the token itself
curl http://gateway:7421/sessions
```

The proxy admin API binds to `DBP_PROXY_LISTEN_ADDRESS` (default `0.0.0.0`) so Prometheus can scrape it
from another container; set `DBP_PROXY_ADMIN_ADDRESS=127.0.0.1` to keep it off the network and scrape
through a sidecar or `kubectl port-forward`.

## Running the components

### Jar

```bash
# control plane (PostgreSQL metadata store)
export DBP_DB_URL=jdbc:postgresql://meta-db:5432/dbp DBP_DB_USER=dbp DBP_DB_PASSWORD=…
export DBP_SERVICE_TOKEN=$(openssl rand -hex 32) DBP_MASTER_KEY=$(openssl rand -base64 32)
java -jar dbp-control-plane/target/dbp-control-plane.jar --spring.profiles.active=postgres

# gateway
export DBP_CONTROL_PLANE_URL=http://control-plane:8080 DBP_SERVICE_TOKEN=… DBP_GATEWAY_ID=gw-1
java -jar dbp-gateway/target/dbp-gateway-0.1.0-SNAPSHOT-all.jar

# proxy
export DBP_CONTROL_PLANE_URL=http://control-plane:8080 DBP_SERVICE_TOKEN=… DBP_PROXY_ID=proxy-1
java -jar dbp-proxy/target/dbp-proxy-0.1.0-SNAPSHOT-all.jar
```

Gateway and proxy are plain Java 21 programs using virtual threads (ADR 0004). They need no
`--enable-preview`. Give them a heap proportional to the number of concurrent sessions × fetch size,
not to the number of physical connections.

### Docker Compose (`deploy/docker-compose.yml`)

The compose file starts Oracle Free 23 (host port 1522 → container 1521, so the proxy can own 1521
on the host), PostgreSQL 17 (5433 → 5432), the control plane, a gateway, a proxy, the example
applications and Prometheus/Grafana. Copy `deploy/.env.example` to `deploy/.env` and set the secrets.

Oracle Free takes several minutes to initialise on first start (data files are created on first run).
The control plane's `test-connection` and the demo seed fail until the container reports healthy; wait
for the compose healthcheck rather than retrying by hand. The demo schema in `demo/` is loaded by the
database containers' init hooks.

### Kubernetes / Helm (`deploy/k8s`, `deploy/helm`)

| Component     | Kind         | Notes                                                                                          |
|---------------|--------------|------------------------------------------------------------------------------------------------|
| control plane | Deployment (1 replica in the POC) | External PostgreSQL recommended; `DBP_MASTER_KEY`/`DBP_SERVICE_TOKEN` from a Secret   |
| gateway       | Deployment ≥ 2 replicas | ClusterIP Service on 7420; readiness = `GET :7421/health`; one `DBP_GATEWAY_ID` per pod (use the pod name) |
| proxy         | Deployment ≥ 2 replicas (or DaemonSet) | Service/LoadBalancer on 1521/5432/1433; `DBP_PROXY_ID` = pod name; see NetworkPolicy in [security.md](security.md) |

Horizontal scaling: gateways are independent; each instance owns its own HikariCP pools, so the
physical connection budget of a database is **`poolPolicy.maxConnections × number of gateway
instances`** per datasource (see [Capacity planning](#capacity-planning)). Proxies are independent and
enforce quotas per instance from the shared config; the control plane aggregates counts.

### Where each component can run

The control plane and the UI are plain HTTP; the gateway and the proxy are **raw TCP servers** on
non-HTTP ports, which rules out HTTP-only serverless platforms for them.

| Component | Protocol / ports | Runs on | Not deployable on |
|-----------|------------------|---------|-------------------|
| control plane (+ bundled UI) | HTTP 8080 | any container platform, including **Cloud Run**, Azure Container Apps, App Runner, Kubernetes, VMs. Needs a reachable PostgreSQL (Cloud SQL etc.) and a route to the databases for the collectors (VPC connector / private networking). Single replica in the POC; scale-to-zero defeats the collectors and heartbeat tracking, so keep min instances ≥ 1. | – |
| gateway   | TCP 7420 (wire protocol), HTTP 7421 (admin) | Kubernetes (ClusterIP/LoadBalancer Service), VMs, ECS/Fargate behind a **Network Load Balancer**, Azure Container Apps with **TCP ingress**; long-running, min instances > 0 | **Cloud Run** (HTTP/WebSocket/gRPC ingress only) and other HTTP-only serverless runtimes |
| proxy     | TCP 1521 / 5432 / 1433, HTTP 7431 (admin) | same as the gateway (needs the conventional database ports exposed over TCP) | **Cloud Run** and HTTP-only runtimes |
| driver    | lives in the application | anywhere the application runs — an application **on Cloud Run** reaches the gateway over the **Serverless VPC Access connector** (or Direct VPC egress) to the gateway's internal address; its own Hikari pool then counts logical sessions only | – |

Resource sizing (from the module READMEs and `deploy/README.md`): gateway and proxy run comfortably
with 300–500 MB RAM each; 4 CPU cores drive a few hundred logical sessions on the gateway, where the
physical pool, not the gateway, is the bottleneck; the proxy spends two virtual threads and two
`DBP_PROXY_BUFFER_BYTES` buffers per proxied connection. The control plane needs 600–900 MB plus its
PostgreSQL. The Kubernetes manifests request 512 Mi / 0.5 CPU (gateway), 384 Mi / 0.25 CPU (proxy) and
768 Mi / 0.25 CPU (control plane) with limits at roughly twice that.

## Configuration reference

### Control plane

Every option is bound through `application.yml` (`dbp.*`); the environment wins. Full list in
`dbp-control-plane/README.md`.

| Variable                              | Default              | Meaning                                                                                  |
|---------------------------------------|----------------------|------------------------------------------------------------------------------------------|
| `DBP_PORT`                            | `8080`               | HTTP port (API + UI)                                                                     |
| `SPRING_PROFILES_ACTIVE`              | `dev`                | `dev` = H2 file store under `./data` + demo seed enabled; `postgres` = PostgreSQL metadata store |
| `DBP_DB_URL`                          | dev: `jdbc:h2:file:./data/dbp;MODE=PostgreSQL;…`; postgres: `jdbc:postgresql://localhost:5432/dbp` | JDBC URL of the metadata store |
| `DBP_DB_USER`, `DBP_DB_PASSWORD`      | dev: `sa` / empty; postgres: `dbp` / `dbp` | Metadata store credentials                                                     |
| `DBP_DB_POOL_SIZE`                    | `10`                 | Hikari pool size of the metadata store (postgres profile)                                |
| `DBP_SERVICE_TOKEN`                   | `dev-service-token`  | Shared secret for `/api/v1/internal/**` (`X-DBP-Service-Token`). **Change it.**            |
| `DBP_MASTER_KEY`                      | unset → built-in dev key, `WARN` at startup | AES-256-GCM key (sha-256 of the value) for INLINE credential secrets. Loss = secrets unrecoverable |
| `DBP_SECURITY_MODE`                   | `none`               | `none` or `basic` (HTTP Basic on the public `/api/v1/**`); internal endpoints always need the service token |
| `DBP_ADMIN_USER`, `DBP_ADMIN_PASSWORD` | `admin` / `admin`   | Operator credentials for `basic` mode                                                    |
| `DBP_TELEMETRY_RETENTION_HOURS`       | `72`                 | Retention of raw `QueryEvent`/`ConnectionEvent` rows and pool snapshots; aggregates and relationships are kept |
| `DBP_QUERY_STATS_RETENTION_DAYS`      | `30`                 | Retention of hourly query statistics                                                     |
| `DBP_TELEMETRY_CLEANUP_INTERVAL_SECONDS` | `600`             | Retention job interval                                                                   |
| `DBP_GOVERNANCE_ENABLED`, `DBP_GOVERNANCE_INTERVAL_SECONDS` | `true`, `300` | Scheduled violation recomputation (also `POST /governance/evaluate`)          |
| `DBP_RELATIONSHIP_STALE_DAYS`         | `30`                 | Relationships without activity for this long are shown as stale                          |
| `DBP_DEMO_SEED_ENABLED`               | `true` (dev), `false` (postgres) | Enables `POST /seed/demo`                                                    |
| `DBP_SEED_ON_STARTUP`                 | `false`              | Load the demo dataset at startup                                                         |
| `DBP_COLLECTOR_ENABLED`               | `true`               | Global collector switch (each database also has `collector.enabled`, default `false`)    |
| `DBP_COLLECTOR_TICK_SECONDS`          | `5`                  | Scheduler tick; each database runs on its own intervals                                  |
| `DBP_COLLECTOR_CONNECT_TIMEOUT_SECONDS` | `10`               | JDBC connect timeout of collector connections                                            |
| `DBP_COLLECTOR_MAX_SQL_PER_SAMPLE`    | `200`                | New statements fetched from `V$SQL` / `pg_stat_statements` per runtime sample            |
| `DBP_COMPONENT_HEALTHY_SECONDS`       | `30`                 | A gateway/proxy is `healthy` in `GET /components` while its last heartbeat is younger than this |
| `DBP_CORS_ALLOWED_ORIGINS`            | `http://localhost:5173` | CORS origins for `/api/**` (Vite dev server)                                          |

The metadata store schema is managed with Flyway inside the control plane; upgrades migrate on start.

### Gateway

Read by `GatewayConfig.fromEnv()`; system properties with the same names are accepted as a fallback.

| Variable                                   | Default                | Meaning                                                                            |
|--------------------------------------------|------------------------|------------------------------------------------------------------------------------|
| `DBP_CONTROL_PLANE_URL`                    | –                      | Base URL of the control plane (`http://…:8080`). Absent → static mode               |
| `DBP_SERVICE_TOKEN`                        | `dev-service-token`    | Sent as `X-DBP-Service-Token`                                                       |
| `DBP_GATEWAY_ID`                           | `gw-<short hostname>`  | Component id in heartbeats/telemetry (`gatewayId`), `v$session.program`, session ids |
| `DBP_GATEWAY_PORT`                         | `7420`                 | Wire-protocol listener                                                              |
| `DBP_GATEWAY_ADMIN_PORT`                   | `7421`                 | `/health`, `/metrics`, `/sessions`, `/pools` (`-1` disables)                        |
| `DBP_GATEWAY_BIND`                         | `0.0.0.0`              | Bind address for both listeners                                                     |
| `DBP_GATEWAY_ADVERTISED_HOST`              | local hostname         | Host in the logical `url` server property (`DatabaseMetaData.getURL()`)             |
| `DBP_GATEWAY_IDLE_TIMEOUT_SECONDS`         | `1800`                 | Socket read timeout: idle logical sessions are closed (fatal `08006`)               |
| `DBP_GATEWAY_MAX_FRAME_BYTES`              | `67108864` (64 MiB)    | Max wire frame accepted and produced; must match the driver's `maxFrameBytes`       |
| `DBP_GATEWAY_ROWS_FRAME_SOFT_BYTES`        | `4194304` (4 MiB)      | A ROWS frame stops early (fewer rows than `fetchSize`) beyond this size             |
| `DBP_GATEWAY_MAX_SESSIONS`                 | `0` (unlimited)        | Global cap of logical sessions (`08004` "too many logical connections")             |
| `DBP_GATEWAY_MAX_OPEN_CURSORS`             | `256`                  | Open cursors per logical session before `HY000`                                     |
| `DBP_GATEWAY_SHUTDOWN_GRACE_SECONDS`       | `20`                   | Wait for in-flight statements on shutdown                                           |
| `DBP_GATEWAY_CONFIG`                       | –                      | Static YAML (datasources, credentials, optional applications/api keys) for static mode |
| `DBP_AUTH_CACHE_SECONDS`                   | `60`                   | Positive api-key cache TTL (negative results: 5 s)                                  |
| `DBP_CONFIG_POLL_SECONDS`                  | `5`                    | Polling of `GET /internal/config-version`                                           |
| `DBP_POOL_STATS_SECONDS`                   | `15`                   | `PoolStats` reporting interval                                                      |
| `DBP_HEARTBEAT_SECONDS`                    | `10`                   | Heartbeat interval                                                                  |
| `DBP_TELEMETRY_FLUSH_MS`, `DBP_TELEMETRY_QUEUE_SIZE` | `2000`, `50000` | Telemetry batching: flush period (also flushed at 500 events) and bounded queue (oldest dropped when full) |
| `DBP_GATEWAY_TLS_KEYSTORE`, `DBP_GATEWAY_TLS_KEYSTORE_PASSWORD` | –  | PKCS12/JKS keystore enabling TLS on 7420 (driver `ssl=true`)                       |
| `DBP_LOG_LEVEL`, `DBP_LOG_LEVEL_HIKARI`    | `INFO`, `WARN`         | Logback levels                                                                      |

Pool settings themselves (`maxConnections`, `minIdle`, `connectionTimeoutMs`, `idleTimeoutMs`,
`maxLifetimeMs`, `statementTimeoutSeconds`, `validationQuery`, `mode`) come from the datasource's
`poolPolicy` in the control plane (or the static YAML), not from gateway environment variables.

### Proxy

Read by `ProxySettings.fromEnv()`.

| Variable                           | Default                | Meaning                                                                                  |
|------------------------------------|------------------------|------------------------------------------------------------------------------------------|
| `DBP_CONTROL_PLANE_URL`            | –                      | Base URL of the control plane; absent → static mode with `DBP_PROXY_CONFIG` only           |
| `DBP_SERVICE_TOKEN`                | `dev-service-token`    | Sent as `X-DBP-Service-Token`. **When set (any value)** the same header is required on the admin `/connections` and `/config` |
| `DBP_PROXY_ID`                     | `proxy-1`              | Component id (`proxyId` in events, `componentId` in heartbeats) — use the pod name on Kubernetes |
| `DBP_PROXY_CONFIG`                 | –                      | YAML with listeners/routes/quotas when running without a control plane (re-read on mtime change) |
| `DBP_CONFIG_POLL_SECONDS`          | `5`                    | Config-version poll / file watch interval (also re-binds listeners that failed to bind)  |
| `DBP_HEARTBEAT_SECONDS`            | `10`                   | Heartbeat interval                                                                       |
| `DBP_PROXY_LISTEN_ADDRESS`         | `0.0.0.0`              | Bind address of the listeners (and of the admin API unless set below)                    |
| `DBP_PROXY_ADMIN_ADDRESS`          | = listen address       | Bind address of the admin API; `127.0.0.1` keeps it off the network                      |
| `DBP_PROXY_ADMIN_PORT`             | `7431`                 | `/health`, `/metrics`, `/connections`, `/config`                                         |
| `DBP_PROXY_STRICT_ALIASES`         | `false`                | `true`: refuse `<match>.<alias>` when no application declares `<alias>` (`ORA-12514` / `3D000`) |
| `DBP_PROXY_UNKNOWN_APP_MAX_CONNECTIONS` | `0` (unlimited)   | Per-datasource cap on live connections without a resolved `applicationId` (`ORA-12516` / `53300`) |
| `DBP_PROXY_IDLE_TIMEOUT_SECONDS`   | `0` (off)              | Close a connection idle in both directions for this long; keep 0 for pools that rely on their own keepalive, or set above the pool's `idleTimeout` |
| `DBP_PROXY_CONNECT_TIMEOUT_MS`     | `5000`                 | Backend TCP connect timeout; failure → `BACKEND_FAILED` event, client gets `ORA-12541` / `08001` |
| `DBP_PROXY_HANDSHAKE_TIMEOUT_MS`   | `15000`                | Deadline for the whole connect handshake measured from accept (stalled clients are dropped) |
| `DBP_PROXY_BUFFER_BYTES`           | `32768`                | Pump buffer per direction                                                                |
| `DBP_TELEMETRY_FLUSH_MS`, `DBP_TELEMETRY_QUEUE_SIZE` | `2000`, `50000` | As for the gateway                                                             |
| `DBP_LOG_LEVEL`                    | `INFO`                 | Level for `org.dbplatform.proxy` (`DEBUG` logs every handshake)                           |

Listener ports (1521/5432/1433) and routes come from `GET /internal/proxy/config` (or the static file);
the effective configuration is visible on `GET :7431/config` (token).

### Driver (JDBC URL properties)

`jdbc:dbp://host[:7420][,host2[:port]]/<datasource>?apiKey=…&application=…&user=…&ssl=false&connectTimeoutMs=10000&socketTimeoutMs=0&fetchSize=100&maxFrameBytes=67108864&autoCommit=true&readOnly=…&schema=…&txIsolation=…&clientInfo.ApplicationName=…`

| Property | Default | Meaning |
|----------|---------|---------|
| `apiKey` | – | Application credential (`dbp_<id>_<secret>`); falls back to `password`; optional in static gateway mode |
| `application` | – | Application name hint (used by a gateway without a control plane) |
| `user` / `password` | – | Informational user name; `password` is used as `apiKey` when `apiKey` is absent |
| `ssl` | `false` | TLS on the same port (`SSLSocketFactory.getDefault()`, JVM trust store) |
| `connectTimeoutMs` / `socketTimeoutMs` | `10000` / `0` | Connect timeout per host; read timeout (`0` = none; a timeout is fatal, `08006`) |
| `fetchSize` | `100` | Rows per ROWS frame / FETCH |
| `maxFrameBytes` | 64 MiB | Must match `DBP_GATEWAY_MAX_FRAME_BYTES` |
| `autoCommit`, `readOnly`, `schema`, `txIsolation` | `true`, –, –, server default | Initial session settings |
| `clientInfo.<name>` | – | Initial client info entries |

Several hosts are tried in order at connect time (client-side failover). All properties can also be
passed as `java.util.Properties` (Hikari/Spring `data-source-properties`); URL values win; unknown
properties are ignored. Full table in `dbp-jdbc/README.md`.

## Metrics catalogue

Gateway and proxy export Micrometer meters in Prometheus text format with the prefix `dbp_`
(`GatewayMetrics`, `ProxyMetrics`); JVM/process meters are not registered by the POC components
(scrape the control plane for a JVM view). The names below are the exact exposition names.

### Gateway (`:7421/metrics`)

| Metric                                   | Type      | Labels                                   | Meaning                                                              |
|------------------------------------------|-----------|------------------------------------------|----------------------------------------------------------------------|
| `dbp_gateway_logical_sessions`           | gauge     | `datasource`                             | Open logical sessions (one per driver connection)                    |
| `dbp_gateway_pinned_sessions`            | gauge     | `datasource`                             | Sessions currently holding a physical connection                      |
| `dbp_gateway_pool_active`, `_idle`, `_waiting`, `_total`, `_max` | gauge | `datasource`         | HikariCP state summed over the pools serving the datasource (all credential versions) |
| `dbp_gateway_statements_total`           | counter   | `datasource`, `operation`, `success`     | Statements executed (`operation` = `SqlOperation`: SELECT, INSERT, …, CALL, TXN, OTHER) |
| `dbp_gateway_statement_duration_seconds` | histogram (`_bucket`, `_count`, `_sum`, `_max`; SLO buckets 1 ms … 30 s) | `datasource` | End-to-end statement latency measured at the gateway, including the wait for a physical connection |
| `dbp_gateway_errors_total`               | counter   | `sqlstate` (lower-case label)            | Every ERROR frame sent, including rejected HELLOs                    |
| `dbp_gateway_telemetry_dropped_total`    | counter   | –                                        | Events dropped because the queue was full or the control plane rejected them |

There is no per-application label on gateway meters: per-application numbers come from telemetry
(`GET /stats/queries/top?applicationId=`, `GET /stats/connections?groupBy=application`). Config
version, control-plane reachability, heartbeat and telemetry drops are also in `GET :7421/health`.

### Proxy (`:7431/metrics`)

| Metric                                   | Type    | Labels                                              | Meaning                                                      |
|------------------------------------------|---------|-----------------------------------------------------|--------------------------------------------------------------|
| `dbp_proxy_connections_active`           | gauge   | `listener`, `application`, `datasource`, `backend`  | Live proxied connections (`application="unknown"`, `datasource="none"` when unresolved) |
| `dbp_proxy_connections_live`             | gauge   | –                                                   | Live connections, all listeners                              |
| `dbp_proxy_connections_accepted_total`   | counter | `listener`                                          | TCP connections accepted                                     |
| `dbp_proxy_connections_refused_total`    | counter | `listener`, `reason` (`quota`, `unknown_service`, `undeclared_alias`, `listener_cap`, `no_route`, `protocol`) | Refused by the proxy (`quota` includes the unknown-application cap) |
| `dbp_proxy_connections_failed_total`     | counter | `listener`                                          | Backend unreachable / refused / malformed handshake          |
| `dbp_proxy_bytes_in_bytes_total`, `dbp_proxy_bytes_out_bytes_total` | counter | `listener`               | Bytes from / to clients                                      |
| `dbp_proxy_backend_connect_seconds`      | timer (`_count`, `_sum`, `_max`, and `{quantile="0.5|0.95|0.99"}`) | `listener`, `backend` | Backend TCP connect latency                        |
| `dbp_proxy_telemetry_events_dropped`     | gauge (monotonic) | –                                         | Events dropped because the control plane was unreachable     |

Connection lifetimes and per-connection byte counts are in `ConnectionEvent.CLOSE` (telemetry) and
`GET :7431/connections`, not in Prometheus.

### Control plane (`:8080/actuator/prometheus`)

The POC control plane registers **no custom `dbp_*` meters**; it exposes the Spring Boot defaults:

| Family                                          | Meaning                                                                 |
|-------------------------------------------------|-------------------------------------------------------------------------|
| `http_server_requests_seconds_count/_sum/_max` (`uri`, `method`, `status`, `outcome`) | API traffic; telemetry ingestion is `uri=~"/api/v1/internal/telemetry/.*"`, heartbeats `uri="/api/v1/internal/heartbeat"` |
| `hikaricp_connections_*`                        | Metadata-store pool (`pool="HikariPool-1"`)                              |
| `jvm_*`, `process_*`, `system_*`, `logback_events_total` | JVM and process                                                |

Platform-level counts (components online, open violations, collector runs and errors, live
connections by source) are served by the REST API: `GET /components` (`healthy` per heartbeat),
`GET /governance/violations?status=OPEN`, `GET /databases/{id}/collector-status` (`lastError`),
`GET /stats/overview`, `GET /stats/pools`. Export them with a JSON exporter if you want them in
Prometheus.

## Alerting suggestions

| Alert                              | Expression sketch                                                                    | Why                                                      |
|------------------------------------|--------------------------------------------------------------------------------------|----------------------------------------------------------|
| Pool saturation                    | `dbp_gateway_pool_waiting > 0` for 5 min, or `dbp_gateway_pool_active / dbp_gateway_pool_max > 0.9` | Applications will start seeing `08001`                 |
| Statement latency                  | `histogram_quantile(0.95, sum by (le, datasource) (rate(dbp_gateway_statement_duration_seconds_bucket[5m])))` above budget | Slow database, or sessions waiting for a physical connection (the wait is included) |
| Gateway errors                     | `rate(dbp_gateway_errors_total{sqlstate=~"08.*|HY000"}[5m])` above baseline          | Connectivity or protocol problems                          |
| Telemetry loss                     | `increase(dbp_gateway_telemetry_dropped_total[10m]) > 0` or `increase(dbp_proxy_telemetry_events_dropped[10m]) > 0` | Control plane unreachable or undersized; relationships under-count |
| Config drift                       | `GET /components`: `configVersion` differs between instances for 5 min (also in each `GET :7421/health` / `:7431/health`) | One instance is not picking up config |
| Component offline                  | `GET /components` reports `healthy: false` (no heartbeat for `DBP_COMPONENT_HEALTHY_SECONDS`), or Prometheus `up{job=~"gateway|proxy"} == 0` | Heartbeats missing |
| Proxy refusals                     | `rate(dbp_proxy_connections_refused_total{reason="quota"}[5m]) > 0`                   | An application exceeded `maxProxyConnections` (or the unknown-application cap) |
| Proxy backend failures             | `rate(dbp_proxy_connections_failed_total[5m]) > 0`                                    | Database or listener unreachable from the proxy            |
| Proxy degraded                     | `GET :7431/health` `status = DEGRADED`                                                | A listener failed to bind or stopped accepting (re-bound on the next poll) |
| Collector failing                  | `GET /databases/{id}/collector-status` with `lastError` set or stale `lastDictionaryRun`/`lastRuntimeRun` | Dictionary/relationships going stale             |
| Credential version mismatch        | `GET /stats/pools`: `credentialVersion` differs across gateways 15 min after a rotation | A gateway did not drain/reconnect                        |
| Database session budget            | Oracle `V$RESOURCE_LIMIT` (`processes`, `sessions`) current vs limit (DBA-side)         | The platform's pools plus everything else must fit          |

## Capacity planning

### Where sessions come from

```
Oracle sessions ≈ Σ_gateways Σ_datasources pool.total
               + Σ_proxied legacy connections (1 per client connection)
               + collector sessions (1–2 per database)
               + direct connections not yet on the platform
               + DBA/tooling headroom
```

This sum must stay below the database's `PROCESSES`/`SESSIONS` limits (Oracle) or `max_connections`
(PostgreSQL) with headroom. The control plane's `Database.maxPhysicalConnections` is the budget you
declare for the platform's share; keep `Σ datasources(poolPolicy.maxConnections) × gatewayInstances ≤
maxPhysicalConnections`. The control plane does not reject a configuration that exceeds it; it reports
the overshoot as a warning in `GET /datasources/{id}/summary` (pool policy × number of gateway instances
seen in heartbeats).

### Pool sizing per datasource

In `TRANSACTION` mode a physical connection is held only while a logical session is pinned (from the
first statement of a transaction to COMMIT/ROLLBACK, or for the duration of a single autocommit
statement, plus while any cursor is open). Little's law gives the starting point:

```
required physical connections ≈ transaction arrival rate (tx/s) × mean pinned duration (s)
maxConnections                ≈ required × (1.5 … 2) for burst headroom, bounded by the DB budget
```

Both inputs are observable after Phase 1: `dbp_gateway_statements_total` by `operation=TXN` and the
pinned duration from gateway telemetry (`pinned = true` events) or `pinnedSessions / logicalSessions`
in `PoolStats`. Until then use the Phase 0 baseline: active (not idle) sessions per application at peak.

In `SESSION` mode each open logical session holds one physical connection for its whole life, so
`maxConnections` must be ≥ the sum of the consumers' local pool sizes, and the platform does **not**
reduce sessions for that datasource. Use SESSION mode only where [compatibility.md](compatibility.md#pool-mode-semantics)
says it is needed.

Rules of thumb:

| Setting                      | Guidance                                                                                             |
|------------------------------|------------------------------------------------------------------------------------------------------|
| `minIdle`                    | Small (2–5); Oracle logons are expensive, so avoid `0` for latency-sensitive datasources               |
| `connectionTimeoutMs`        | Lower than the application's own Hikari `connectionTimeout`, so the app sees `08001` instead of a hang |
| `maxLifetimeMs`              | Shorter than any firewall/listener idle kill and than the credential rotation window you want         |
| `statementTimeoutSeconds`    | A safety net (`HY008`); applications should still set `setQueryTimeout`                               |
| `AccessGrant.maxLogicalConnections` | ≥ app instances × local pool size, otherwise the app sees refusals at connect                   |
| `AccessGrant.maxProxyConnections`   | Current observed peak + margin during Phase 0; tighten later                                     |

### Gateway sizing

Memory: dominated by in-flight row batches (`fetchSize × row width × concurrent fetches`) and by LOB
values (materialised up to `maxFrameBytes`). CPU: SQL analysis (JSqlParser) per *new* normalised
statement; the gateway caches analysis per `sqlHash`. Network: every result row crosses two hops.
Numbers are to be measured in your environment; the examples module contains a load generator.

## Request-level tracing

Every statement the gateway executes is reported as a `QueryEvent` that carries the session's
**client info** (`QueryEvent.clientInfo`, see [telemetry-events.md](telemetry-events.md)). The driver
sends `Connection.setClientInfo(name, value)` to the gateway immediately (`SET_CLIENT_INFO` frame, one
round trip, no physical connection needed); the gateway remembers the entries per logical session,
includes the current map in every subsequent `QueryEvent`, forwards them to the physical connection
while the session is pinned (and replays them on every re-pin), and resets them to the pool baseline on
release. For Oracle the standard names are additionally mapped to end-to-end metrics —
`ApplicationName` → `OCSID.MODULE`, `ClientUser` → `OCSID.CLIENTID`, `action` → `OCSID.ACTION` — so
`V$SESSION.MODULE/CLIENT_IDENTIFIER/ACTION` and the unified audit trail show them too. `clientInfo.<name>`
URL properties set the initial entries (e.g. `clientInfo.ApplicationName=orders-service`).

An application can therefore tag each request with its trace context and correlate database statements
with its distributed traces: set `traceparent` (or your trace id) and `action` on the connection before
the first statement of the request and clear them afterwards. Spring with a `DataSource` wrapper:

```java
/** Stamps the current request's trace context on every connection handed to the application. */
public class TracingDataSource extends org.springframework.jdbc.datasource.DelegatingDataSource {
    public TracingDataSource(DataSource delegate) { super(delegate); }

    @Override
    public Connection getConnection() throws SQLException {
        Connection c = super.getConnection();
        String traceparent = TraceContext.currentTraceparent();   // W3C traceparent of the active span
        String action = TraceContext.currentOperation();          // e.g. "POST /orders"
        try {
            c.setClientInfo("traceparent", traceparent == null ? null : traceparent);
            c.setClientInfo("action", action == null ? null : action);   // Oracle: OCSID.ACTION
        } catch (SQLClientInfoException e) {
            // never fail the request because of tagging
        }
        return c;
    }
}
```

Register it around the pooled `DataSource` (`new TracingDataSource(hikariDataSource)`) so the tag is
set every time Hikari hands out a connection, i.e. once per request/transaction; `setClientInfo(name,
null)` removes an entry. With `JdbcTemplate` the same can be done per call:

```java
jdbcTemplate.execute((ConnectionCallback<Void>) c -> { c.setClientInfo("traceparent", tp); return null; });
```

Then `GET /stats/queries/top` and the raw events show which statements ran under which trace, and a
DBA sees the same ids in `V$SESSION.ACTION` / `pg_stat_activity` (PostgreSQL only exposes
`application_name`; other entries stay in telemetry). Through the proxy the vendor driver's own client
info (`DBMS_APPLICATION_INFO`, `OCSID.*`, `application_name`) reaches the database unchanged, and the
collector samples `MODULE/ACTION/CLIENT_IDENTIFIER`. Exporting gateway spans to OpenTelemetry (instead of
correlating by id) is roadmap.

## Credential rotation runbook

Goal: rotate a database password without redeploying applications and without a connection storm.

```mermaid
sequenceDiagram
  participant Op as Operator / secret store
  participant CP as Control plane
  participant GW as Gateway(s)
  participant DB as Database
  Op->>DB: ALTER USER SALES_APP IDENTIFIED BY new password (or secret-store rotation + DB change)
  Op->>CP: POST /credentials/{id}/rotate  ({"secret": new} for INLINE, {} to re-read provider)
  CP->>CP: version++ , rotatedAt, configVersion++
  GW->>CP: GET /internal/config-version (poll)
  GW->>CP: GET /internal/resolve/datasource/{name}  → credentialVersion n+1
  GW->>CP: GET /internal/credentials/{id}/material (service token; audited)
  GW->>DB: new physical connections use the new password
  GW->>GW: soft-evict idle old-version connections; active ones retire at un-pin
  GW->>CP: PoolStats.credentialVersion = n+1
```

Steps:

1. **Prepare**: confirm `GET /stats/pools` shows all gateways on the current `credentialVersion`.
2. **Change the password in the database** (DBA) *and* in the secret store/provider in the same change
   window. For Oracle consider a grace period if your version supports gradual password rollover
   (`PASSWORD_ROLLOVER_TIME` in the profile, 19c+/21c; verify for your release) so both passwords work
   during the drain.
3. **Notify the platform**: `POST /credentials/{id}/rotate` with the new secret (INLINE) or `{}`
   (ENV/FILE/Vault re-read). `rotatedAt` and `version` change; `configVersion` increments.
4. **Gateways drain**: within the poll interval every gateway re-resolves, fetches the new material,
   opens new connections with it and retires old-version connections as they become idle/un-pinned.
   Long SESSION-mode sessions keep their old connection until they close; if the old password must
   stop working immediately, those sessions will fail on their next statement with the vendor's
   authentication/`08006` error and the application pool reconnects.
5. **Verify**: `GET /stats/pools` → `credentialVersion` uniform; `dbp_gateway_errors_total{sqlstate="08001"}`
   flat; `V$SESSION` (Oracle) shows no sessions older than the rotation for that user, or
   `pg_stat_activity.backend_start` on PostgreSQL.
6. **Rollback**: `POST /credentials/{id}/rotate` with the previous secret (and restore the DB password).

Api-key rotation for applications is a separate procedure in [security.md](security.md#application-identity-api-keys).

## Upgrade and zero-downtime notes

| Component     | Procedure                                                                                                       |
|---------------|-----------------------------------------------------------------------------------------------------------------|
| Gateway       | Rolling restart one instance at a time. The driver fails over at *connect* time only; existing logical sessions on the restarting instance are closed (`08006`, fatal) and the application pool re-creates them against the next host. On SIGTERM the gateway stops accepting, waits up to `DBP_GATEWAY_SHUTDOWN_GRACE_SECONDS` (20) for in-flight statements, then closes sessions, pools and telemetry; `/health` does not turn unhealthy first, so drain by removing the instance from the load balancer / letting Kubernetes stop routing to the terminating pod, wait for `dbp_gateway_logical_sessions` to fall, then stop. Open transactions on that instance are rolled back by the database. |
| Proxy         | Same pattern. Proxied connections are long-lived; a restart closes them all (a `CLOSE` event with reason `proxy shutdown` is emitted for each), which legacy pools handle as a reconnect. Prefer draining by removing the instance from DNS/LB and waiting for `dbp_proxy_connections_live` to approach zero (can take as long as the clients' pool `maxLifetime`). |
| Control plane | Single instance in the POC. Gateways and proxies keep serving from cached config while it is down; telemetry is buffered then dropped. Upgrade = stop, migrate (Flyway on start), start. Keep the outage shorter than the telemetry queue can absorb (50 000 events by default). |
| Driver        | Protocol version is negotiated in `HELLO`; a gateway rejects unknown versions with `08004 unsupported protocol version`. Upgrade gateways before drivers when a protocol bump happens. |
| Wire protocol | v1 is frozen; additions require a version bump (see [wire-protocol.md](wire-protocol.md#6-versioning)). |

Compatibility matrix across versions is to be published with the first tagged release.

## Troubleshooting

| Symptom                                                   | Where             | Likely cause and action                                                                                                  |
|-----------------------------------------------------------|-------------------|--------------------------------------------------------------------------------------------------------------------------|
| `ORA-12514 TNS:listener does not currently know of service requested` right after connecting through the proxy | Oracle client via proxy | The requested `SERVICE_NAME` matches no route and the listener has no default route, the alias `<datasource>.<app>` is misspelt, or `DBP_PROXY_STRICT_ALIASES=true` and no application declares the alias. Check `GET :7431/config` (token) and the `REFUSED` `ConnectionEvent.reason` (`dbp_proxy_connections_refused_total{reason}`). |
| `ORA-12516 TNS:listener could not find available handler` from the proxy | Oracle client via proxy | Quota exceeded (`maxProxyConnections` for the application/datasource, the datasource quota, `DBP_PROXY_UNKNOWN_APP_MAX_CONNECTIONS`, or the listener's `maxConnections`). See `reason: "quota exceeded: app/ds n/n"`. Raise the grant or fix the application's pool size. If the error comes from the real listener instead, the database `PROCESSES` limit is hit. |
| `SQLState 08004` on `getConnection` with the driver        | Application       | Invalid/revoked api key, no enabled `AccessGrant` for (application, datasource), or protocol version mismatch. Check `POST /internal/auth/application` behaviour via the control plane logs and `GET /access-grants?applicationId=`. |
| `SQLState 08001` on first statement                      | Application       | Gateway could not get a physical connection within `connectionTimeoutMs`: pool exhausted (`pool_waiting > 0`), database down, wrong credential. Look at `dbp_gateway_pool_*` and the gateway log for the physical exception. |
| `SQLState 08006` (fatal) mid-session                      | Application       | Gateway restarted, network cut, or the physical connection died while pinned. The application pool should evict and reconnect; investigate the gateway log around the timestamp. |
| `SQLState 0A000`                                          | Application       | Unsupported JDBC feature (see [compatibility.md](compatibility.md)). Remediate the application or route it through the proxy. |
| `SQLState HY000 too many cursors`                         | Application       | Result sets not closed; > `DBP_GATEWAY_MAX_OPEN_CURSORS` (256). Fix the leak (try-with-resources).                       |
| `SQLState HY008`                                          | Application       | `setQueryTimeout` or `statementTimeoutSeconds` hit. Tune or fix the query.                                               |
| Pool exhaustion with few active statements                | Gateway           | Sessions pinned by open cursors or by `autoCommit=false` without commit; `/sessions` on 7421 lists pinned sessions and age. In SESSION mode this is by design. |
| Physical connections keep growing after rotation          | Gateway           | Old connections are not evicted while pinned; SESSION-mode sessions never un-pin. Check `PoolStats.credentialVersion`.  |
| Telemetry missing for an application                      | Control plane     | `dbp_gateway_telemetry_dropped_total` / `dbp_proxy_telemetry_events_dropped` rising (control plane down/slow), service token mismatch (`401` in the component log), or the SQL could not be parsed (tables empty, `operation = OTHER`). |
| Relationships attribute to the wrong application          | Control plane     | Overlapping identity rules (CIDR too wide); precedence is service alias → program/application_name → machine → CIDR. Tighten rules; prefer the gateway path. |
| Oracle in compose not ready / `ORA-12514` for `FREEPDB1`  | Compose           | First-start initialisation of Oracle Free takes minutes; wait for the healthcheck. Host port is 1522, container 1521.    |
| Control plane fails to start: Flyway validation error     | Control plane     | Schema from a newer version or a manual edit. Restore from backup or run the matching version.                            |
| UI empty                                                  | UI                | Not a UI problem: check `GET /stats/overview`; the UI only talks to the control plane API.                                |
| `401` from `GET :7431/connections` or `/config`           | Proxy admin       | `DBP_SERVICE_TOKEN` is set on the proxy, so these two endpoints require `X-DBP-Service-Token: <token>`; `/health` and `/metrics` never do. |
| `GET :7431/health` reports `DEGRADED`                     | Proxy             | A listener could not bind (port in use at start-up) or its accept loop died; it is re-bound on the next `DBP_CONFIG_POLL_SECONDS` poll. Check the `listeners` array in the health body. |

Useful DBA-side checks:

```sql
-- Oracle: sessions per program/machine and whether they came through the proxy (PORT joins ConnectionEvent.proxyLocalPort)
SELECT username, program, machine, port, status, COUNT(*) FROM v$session
WHERE type = 'USER' GROUP BY username, program, machine, port, status ORDER BY 6 DESC;

-- PostgreSQL: sessions per application_name / client address
SELECT usename, application_name, client_addr, client_port, state, count(*) FROM pg_stat_activity
GROUP BY 1,2,3,4,5 ORDER BY 6 DESC;
```

## Backup of the metadata store

The metadata store is the only stateful component. It holds configuration (teams, applications,
databases, encrypted credentials, datasources, routing rules, grants), the curated catalogue
(ownership, producers, classifications, declared relationships), derived relationships/aggregates and
the bounded raw telemetry window.

| What                            | How                                                                                                  | Frequency                 |
|---------------------------------|------------------------------------------------------------------------------------------------------|---------------------------|
| PostgreSQL metadata store       | `pg_dump` (or your managed-service snapshot); include the Flyway history table                         | Daily + before upgrades   |
| `DBP_MASTER_KEY`                | Stored in your secret manager, never alongside the dump. Without it INLINE secrets in a restored dump are unreadable | On every change |
| Configuration export            | `GET /api/v1/export` (JSON, no secrets) committed to a configuration repository; restore with `POST /import` (upsert by name) | On every config change (automate) |
| H2 (dev only)                   | Copy the `data/` directory while stopped                                                              | –                         |

Restore test: start a control plane against the restored database with the same `DBP_MASTER_KEY`, run
`POST /databases/{id}/test-connection` for each database (proves credentials decrypt), and compare
`GET /stats/overview` counts. Telemetry raw events older than `DBP_TELEMETRY_RETENTION_HOURS` are
expected to be missing; relationships and aggregates must be present.
