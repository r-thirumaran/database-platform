# Operations guide

How to run, configure, monitor and troubleshoot the platform components. Written for platform
engineers and DBAs. Contract references: [control-plane-api.md](control-plane-api.md),
[wire-protocol.md](wire-protocol.md), [telemetry-events.md](telemetry-events.md).

Configuration follows one rule for every component (`CONTRIBUTING.md`): **YAML file + environment
variables prefixed `DBP_`; the environment wins.** The authoritative list of options for a component is
its module README (`dbp-gateway/README.md`, `dbp-proxy/README.md`, `dbp-control-plane/README.md`); this
page consolidates the ones an operator needs. Names marked † are the conventional names expected from
the module READMEs and should be verified against the README of the version you deploy.

## Components at a glance

| Component     | Artifact                                             | Main class / entry                                 | Ports                                   | State                                    |
|---------------|------------------------------------------------------|----------------------------------------------------|-----------------------------------------|------------------------------------------|
| control plane | `dbp-control-plane/target/dbp-control-plane.jar`     | `org.dbplatform.controlplane.ControlPlaneApplication` (Spring Boot) | 8080 (API + UI)             | metadata store (PostgreSQL; H2 for dev)  |
| gateway       | `dbp-gateway/target/dbp-gateway-<ver>.jar`           | `org.dbplatform.gateway.GatewayMain`               | 7420 wire protocol, 7421 admin          | stateless; caches config; owns pools      |
| proxy         | `dbp-proxy/target/dbp-proxy-<ver>.jar`               | `org.dbplatform.proxy.ProxyMain`                   | 1521 / 5432 / 1433 listeners, 7431 admin | stateless; caches config                 |
| UI            | built into the control plane jar (`dbp-ui` build)    | served on 8080                                      | –                                       | –                                        |
| driver        | `dbp-jdbc/target/dbp-jdbc-<ver>-all.jar`             | `org.dbplatform.jdbc.DbpDriver`                    | –                                       | lives in the application                 |

Admin endpoints (gateway 7421, proxy 7431): `/health`, `/metrics` (Prometheus text format), and
`/sessions` (gateway) or `/connections` (proxy). Control plane: `/actuator/health`, `/actuator/prometheus`,
`/v3/api-docs`, `/swagger-ui.html`.

## Running the components

### Jar

```bash
# control plane (PostgreSQL metadata store)
export DBP_DB_URL=jdbc:postgresql://meta-db:5432/dbp DBP_DB_USERNAME=dbp DBP_DB_PASSWORD=…
export DBP_SERVICE_TOKEN=$(openssl rand -hex 32) DBP_MASTER_KEY=$(openssl rand -base64 32)
java -jar dbp-control-plane.jar --spring.profiles.active=postgres

# gateway
export DBP_CONTROL_PLANE_URL=http://control-plane:8080 DBP_SERVICE_TOKEN=… DBP_GATEWAY_ID=gw-1
java -jar dbp-gateway-0.1.0-SNAPSHOT.jar

# proxy
export DBP_CONTROL_PLANE_URL=http://control-plane:8080 DBP_SERVICE_TOKEN=… DBP_PROXY_ID=proxy-1
java -jar dbp-proxy-0.1.0-SNAPSHOT.jar
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

## Configuration reference

### Control plane

| Variable                              | Default              | Meaning                                                                                  |
|---------------------------------------|----------------------|------------------------------------------------------------------------------------------|
| `DBP_DB_URL`                          | H2 file in `./data` (dev profile) | JDBC URL of the metadata store (`jdbc:postgresql://…` in production)        |
| `DBP_DB_USERNAME` †, `DBP_DB_PASSWORD` † | –                 | Metadata store credentials                                                               |
| `DBP_SERVICE_TOKEN`                   | `dev-service-token`  | Shared secret for `/api/v1/internal/**` (`X-DBP-Service-Token`). **Change it.**            |
| `DBP_MASTER_KEY`                      | – (dev key in the dev profile †) | Key used to encrypt INLINE credential secrets at rest. Loss = secrets unrecoverable |
| `DBP_SECURITY_MODE`                   | `none`               | `none` or `basic` for the public API/UI                                                   |
| `DBP_TELEMETRY_RETENTION_HOURS`       | `72`                 | Retention of raw `QueryEvent`/`ConnectionEvent` rows; aggregates and relationships are kept |
| `DBP_GOVERNANCE_INTERVAL_SECONDS`     | `300`                | Period of the violation recomputation job                                                |
| `DBP_RELATIONSHIP_STALE_DAYS`         | `30`                 | Relationships without activity for this long are shown as stale                          |
| `DBP_DEMO_SEED_ENABLED`               | `true` in `dev`      | Enables `POST /seed/demo`                                                                |
| `SPRING_PROFILES_ACTIVE`              | `dev`                | `dev` = H2 + demo seed; `postgres` = PostgreSQL metadata store                            |

The metadata store schema is managed with Flyway inside the control plane; upgrades migrate on start.

### Gateway

| Variable                                   | Default                | Meaning                                                                            |
|--------------------------------------------|------------------------|------------------------------------------------------------------------------------|
| `DBP_CONTROL_PLANE_URL`                    | –                      | Base URL of the control plane (`http://…:8080`). Absent → static mode               |
| `DBP_SERVICE_TOKEN`                        | `dev-service-token`    | Sent as `X-DBP-Service-Token`                                                       |
| `DBP_GATEWAY_ID`                           | hostname †             | Component id in heartbeats/telemetry (`gatewayId`)                                 |
| `DBP_GATEWAY_PORT`                         | `7420`                 | Wire-protocol listener                                                              |
| `DBP_GATEWAY_ADMIN_PORT` †                 | `7421`                 | `/health`, `/metrics`, `/sessions`                                                  |
| `DBP_GATEWAY_CONFIG`                       | –                      | YAML with static datasources (no control plane; `apiKey` optional, `application` hint used) |
| `DBP_GATEWAY_TLS_KEYSTORE`, `DBP_GATEWAY_TLS_KEYSTORE_PASSWORD` † | –       | PKCS12/JKS keystore enabling TLS on 7420 (driver `ssl=true`)                       |
| `DBP_TELEMETRY_FLUSH_MS`                   | `2000`                 | Flush period of the telemetry queue (also flushed at 500 events)                   |
| `DBP_TELEMETRY_QUEUE_SIZE` †               | `50000`                | Bounded in-memory queue; oldest events dropped when full                            |
| `dbp.maxFrameBytes`                        | `67108864` (64 MiB)    | Max wire frame; must be ≥ the driver's value for the largest row batch              |
| `dbp.maxOpenCursorsPerSession`             | `256`                  | Open cursors per logical session before `HY000`                                     |
| `DBP_CONFIG_POLL_SECONDS` †                | a few seconds          | Polling of `GET /internal/config-version`                                           |

Pool settings themselves (`maxConnections`, `minIdle`, `connectionTimeoutMs`, `idleTimeoutMs`,
`maxLifetimeMs`, `statementTimeoutSeconds`, `validationQuery`, `mode`) come from the datasource's
`poolPolicy` in the control plane, not from gateway environment variables.

### Proxy

| Variable                           | Default                | Meaning                                                                                  |
|------------------------------------|------------------------|------------------------------------------------------------------------------------------|
| `DBP_CONTROL_PLANE_URL`            | –                      | Base URL of the control plane; absent → static mode with `DBP_PROXY_CONFIG` only           |
| `DBP_SERVICE_TOKEN`                | `dev-service-token`    | Sent as `X-DBP-Service-Token`                                                             |
| `DBP_PROXY_ID`                     | hostname †             | Component id (`proxyId`)                                                                  |
| `DBP_PROXY_CONFIG`                 | –                      | YAML with listeners/routes/quotas when running without a control plane                    |
| `DBP_PROXY_ADMIN_PORT` †           | `7431`                 | `/health`, `/metrics`, `/connections`                                                     |
| `DBP_PROXY_CONNECT_TIMEOUT_MS` †   | `10000`                | Backend connect timeout; failure → `BACKEND_FAILED` event, client refused                 |
| `DBP_PROXY_IDLE_TIMEOUT_MS` †      | `0` (none)             | Close a connection with no bytes in either direction for this long; keep 0 for Oracle pools that rely on their own keepalive, or set above the pool's `idleTimeout` |
| `DBP_TELEMETRY_FLUSH_MS`           | `2000`                 | As for the gateway                                                                        |

Listener ports (1521/5432/1433) and routes come from `GET /internal/proxy/config` (or the static file).

### Driver (JDBC URL properties)

`jdbc:dbp://host[:7420][,host2[:port]]/<datasource>?apiKey=…&ssl=true&connectTimeoutMs=10000&socketTimeoutMs=0&fetchSize=100&maxFrameBytes=…&autoCommit=true&schema=…&readOnly=false&clientInfo.ApplicationName=…`

All properties can also be passed as `java.util.Properties`; URL values win. `password` is used as the
api key when `apiKey` is absent (so unchanged `username`/`password` configuration keeps working).

## Metrics catalogue

All platform metrics are exported in Prometheus text format with the prefix `dbp_`. Family names below
describe what exists; exact names, label sets and histogram buckets are listed in each module README
and visible on the `/metrics` endpoints. Standard JVM/process metrics (Micrometer) are exported alongside.

### Gateway (`:7421/metrics`)

| Family                                   | Type      | Labels (typical)                         | Meaning                                                              |
|------------------------------------------|-----------|------------------------------------------|----------------------------------------------------------------------|
| `dbp_gateway_logical_sessions`           | gauge     | `datasource`, `application`              | Open logical sessions (one per driver connection)                    |
| `dbp_gateway_pinned_sessions`            | gauge     | `datasource`                             | Sessions currently holding a physical connection                      |
| `dbp_gateway_pool_active`, `_idle`, `_waiting`, `_total`, `_max` | gauge | `datasource`, `database`     | HikariCP pool state per physical database pool                        |
| `dbp_gateway_statement_duration_seconds` | histogram | `datasource`, `application`, `operation` | End-to-end statement latency measured at the gateway                  |
| `dbp_gateway_statements_total`           | counter   | `datasource`, `application`, `operation`, `success` | Statements executed                                       |
| `dbp_gateway_errors_total`               | counter   | `sqlState`                               | Errors returned to clients (gateway-originated and physical)          |
| `dbp_gateway_pool_acquire_seconds`       | histogram | `datasource`                             | Time to obtain a physical connection (pinning latency)               |
| `dbp_gateway_telemetry_queue_size`       | gauge     | –                                        | Buffered events                                                       |
| `dbp_gateway_telemetry_dropped_total`    | counter   | –                                        | Events dropped because the queue was full / control plane unreachable |
| `dbp_gateway_control_plane_requests_total` | counter | `endpoint`, `outcome`                    | Resolve/auth/heartbeat calls                                          |
| `dbp_gateway_config_version`             | gauge     | –                                        | Config version in use (compare across instances)                     |

### Proxy (`:7431/metrics`)

| Family                                   | Type    | Labels (typical)                                    | Meaning                                                      |
|------------------------------------------|---------|-----------------------------------------------------|--------------------------------------------------------------|
| `dbp_proxy_connections_active`           | gauge   | `listener`, `application`, `datasource`, `backend`  | Open proxied connections                                     |
| `dbp_proxy_connections_opened_total`     | counter | same                                                | Connections accepted and forwarded                           |
| `dbp_proxy_connections_refused_total`    | counter | `listener`, `reason` (`quota`, `unknown_service`, `backend_failed`) | Connections refused                        |
| `dbp_proxy_bytes_total`                  | counter | `listener`, `direction` (`in`/`out`)                | Bytes relayed                                                |
| `dbp_proxy_connection_duration_seconds`  | histogram | `listener`                                        | Lifetime of closed connections                                |
| `dbp_proxy_telemetry_dropped_total`      | counter | –                                                   | As for the gateway                                           |
| `dbp_proxy_config_version`               | gauge   | –                                                   | Config version in use                                        |

### Control plane (`:8080/actuator/prometheus`)

| Family                                          | Type      | Meaning                                                            |
|-------------------------------------------------|-----------|--------------------------------------------------------------------|
| `dbp_controlplane_telemetry_events_total`       | counter   | Accepted events by `type` (`query`, `connection`, `pool`)          |
| `dbp_controlplane_telemetry_rejected_total`     | counter   | Events rejected (bad token, malformed)                             |
| `dbp_controlplane_telemetry_ingest_seconds`     | histogram | Batch processing time                                              |
| `dbp_controlplane_collector_runs_total`         | counter   | By `database`, `what` (`DICTIONARY`/`RUNTIME`/`AUDIT`), `outcome`  |
| `dbp_controlplane_collector_duration_seconds`   | histogram | Per run                                                            |
| `dbp_controlplane_components_online`            | gauge     | Gateways/proxies with a recent heartbeat                           |
| `dbp_controlplane_violations_open`              | gauge     | By `policyKind`                                                    |
| `hikaricp_*`, `http_server_requests_seconds`, `jvm_*` | –   | Spring Boot defaults                                               |

## Alerting suggestions

| Alert                              | Expression sketch                                                                    | Why                                                      |
|------------------------------------|--------------------------------------------------------------------------------------|----------------------------------------------------------|
| Pool saturation                    | `dbp_gateway_pool_waiting > 0` for 5 min, or `pool_active / pool_max > 0.9`           | Applications will start seeing `08001`                     |
| Pinning latency                    | p95 of `dbp_gateway_pool_acquire_seconds` > agreed budget                             | Pool too small or long transactions                        |
| Gateway errors                     | rate of `dbp_gateway_errors_total{sqlState=~"08.*|HY000"}` above baseline            | Connectivity or protocol problems                          |
| Telemetry loss                     | increase of `*_telemetry_dropped_total` > 0 over 10 min                               | Control plane unreachable or undersized; relationships under-count |
| Config drift                       | `count(count by (dbp_gateway_config_version) (dbp_gateway_config_version)) > 1` for 5 min | One instance is not picking up config                 |
| Component offline                  | `dbp_controlplane_components_online` below the expected number                        | Heartbeats missing                                         |
| Proxy refusals                     | rate of `dbp_proxy_connections_refused_total{reason="quota"}` > 0                      | An application exceeded `maxProxyConnections`              |
| Collector failing                  | `collector_runs_total{outcome="error"}` increasing, or `collector-status.lastError` set | Dictionary/relationships going stale                       |
| Credential version mismatch        | `PoolStats.credentialVersion` differs across gateways for 15 min after a rotation      | A gateway did not drain/reconnect                          |
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
maxPhysicalConnections` (whether the control plane validates this is implementation-defined; check the
module README).

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
5. **Verify**: `GET /stats/pools` → `credentialVersion` uniform; `dbp_gateway_errors_total{sqlState="08001"}`
   flat; `V$SESSION` (Oracle) shows no sessions older than the rotation for that user, or
   `pg_stat_activity.backend_start` on PostgreSQL.
6. **Rollback**: `POST /credentials/{id}/rotate` with the previous secret (and restore the DB password).

Api-key rotation for applications is a separate procedure in [security.md](security.md#application-identity-api-keys).

## Upgrade and zero-downtime notes

| Component     | Procedure                                                                                                       |
|---------------|-----------------------------------------------------------------------------------------------------------------|
| Gateway       | Rolling restart one instance at a time. The driver fails over at *connect* time only; existing logical sessions on the restarting instance are closed (`08006`, fatal) and the application pool re-creates them against the next host. Drain: mark the instance unhealthy (`/health` returns non-200 after SIGTERM †) so load balancers stop routing, wait for `dbp_gateway_logical_sessions` to fall, then stop. Open transactions on that instance are rolled back by the database. |
| Proxy         | Same pattern. Proxied connections are long-lived; a restart closes them all, which legacy pools handle as a reconnect. Prefer draining by removing the instance from DNS/LB and waiting for `dbp_proxy_connections_active` to approach zero (can take as long as the clients' pool `maxLifetime`). |
| Control plane | Single instance in the POC. Gateways and proxies keep serving from cached config while it is down; telemetry is buffered then dropped. Upgrade = stop, migrate (Flyway on start), start. Keep the outage shorter than the telemetry queue can absorb (50 000 events by default). |
| Driver        | Protocol version is negotiated in `HELLO`; a gateway rejects unknown versions with `08004 unsupported protocol version`. Upgrade gateways before drivers when a protocol bump happens. |
| Wire protocol | v1 is frozen; additions require a version bump (see [wire-protocol.md](wire-protocol.md#6-versioning)). |

Compatibility matrix across versions is to be published with the first tagged release.

## Troubleshooting

| Symptom                                                   | Where             | Likely cause and action                                                                                                  |
|-----------------------------------------------------------|-------------------|--------------------------------------------------------------------------------------------------------------------------|
| `ORA-12514 TNS:listener does not currently know of service requested` right after connecting through the proxy | Oracle client via proxy | The requested `SERVICE_NAME` matches no route and the listener has no default route, or the alias `<datasource>.<app>` is misspelt. Check `GET /internal/proxy/config` and the `REFUSED` `ConnectionEvent.reason`. |
| `ORA-12516 TNS:listener could not find available handler` from the proxy | Oracle client via proxy | Quota exceeded (`maxProxyConnections` for the application/datasource). See `reason: "quota exceeded: app/ds n/n"`. Raise the grant or fix the application's pool size. If the error comes from the real listener instead, the database `PROCESSES` limit is hit. |
| `SQLState 08004` on `getConnection` with the driver        | Application       | Invalid/revoked api key, no enabled `AccessGrant` for (application, datasource), or protocol version mismatch. Check `POST /internal/auth/application` behaviour via the control plane logs and `GET /access-grants?applicationId=`. |
| `SQLState 08001` on first statement                      | Application       | Gateway could not get a physical connection within `connectionTimeoutMs`: pool exhausted (`pool_waiting > 0`), database down, wrong credential. Look at `dbp_gateway_pool_*` and the gateway log for the physical exception. |
| `SQLState 08006` (fatal) mid-session                      | Application       | Gateway restarted, network cut, or the physical connection died while pinned. The application pool should evict and reconnect; investigate the gateway log around the timestamp. |
| `SQLState 0A000`                                          | Application       | Unsupported JDBC feature (see [compatibility.md](compatibility.md)). Remediate the application or route it through the proxy. |
| `SQLState HY000 too many cursors`                         | Application       | Result sets not closed; > `dbp.maxOpenCursorsPerSession` (256). Fix the leak (try-with-resources).                       |
| `SQLState HY008`                                          | Application       | `setQueryTimeout` or `statementTimeoutSeconds` hit. Tune or fix the query.                                               |
| Pool exhaustion with few active statements                | Gateway           | Sessions pinned by open cursors or by `autoCommit=false` without commit; `/sessions` on 7421 lists pinned sessions and age. In SESSION mode this is by design. |
| Physical connections keep growing after rotation          | Gateway           | Old connections are not evicted while pinned; SESSION-mode sessions never un-pin. Check `PoolStats.credentialVersion`.  |
| Telemetry missing for an application                      | Control plane     | `telemetry_dropped_total` rising (control plane down/slow), service token mismatch (`401` in gateway log), or the SQL could not be parsed (tables empty, `operation = OTHER`). |
| Relationships attribute to the wrong application          | Control plane     | Overlapping identity rules (CIDR too wide); precedence is service alias → program/application_name → machine → CIDR. Tighten rules; prefer the gateway path. |
| Oracle in compose not ready / `ORA-12514` for `FREEPDB1`  | Compose           | First-start initialisation of Oracle Free takes minutes; wait for the healthcheck. Host port is 1522, container 1521.    |
| Control plane fails to start: Flyway validation error     | Control plane     | Schema from a newer version or a manual edit. Restore from backup or run the matching version.                            |
| UI empty                                                  | UI                | Not a UI problem: check `GET /stats/overview`; the UI only talks to the control plane API.                                |

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
