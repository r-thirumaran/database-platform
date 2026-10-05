# dbp-control-plane

Spring Boot 3 service that is the **control plane** (configuration, identity, routing, credentials), the
**metadata plane** (catalogue, ownership, producer/consumer graph, impact analysis), the **telemetry
sink** and the home of the **database collectors** of the Database Access Platform. It also serves the
UI build (`dbp-ui/dist` copied into `classpath:/static/`).

The REST contract is `docs/control-plane-api.md`; telemetry payloads are `docs/telemetry-events.md`
(records from `dbp-common`); the model is `docs/metadata-model.md`.

## Run

```bash
# build (tests included: H2 in-memory + an embedded PostgreSQL started by zonky)
mvn -pl dbp-control-plane package

# dev profile (default): H2 file database under ./data, demo seed enabled, no security
java -jar dbp-control-plane/target/dbp-control-plane.jar
# or
mvn -pl dbp-control-plane spring-boot:run

# PostgreSQL metadata store
DBP_DB_URL=jdbc:postgresql://localhost:5432/dbp DBP_DB_USER=dbp DBP_DB_PASSWORD=dbp \
  java -jar dbp-control-plane/target/dbp-control-plane.jar --spring.profiles.active=postgres
```

Then:

* `http://localhost:8080/` — UI (placeholder page when the UI build is not bundled)
* `http://localhost:8080/swagger-ui.html`, `/v3/api-docs` — OpenAPI
* `http://localhost:8080/actuator/health`, `/actuator/prometheus`
* `POST http://localhost:8080/api/v1/seed/demo` — retail demo dataset (idempotent)
* `http://localhost:8080/h2-console` (dev profile only; JDBC URL `jdbc:h2:file:./data/dbp`)

Flyway migrations (`src/main/resources/db/migration`) are written in PostgreSQL-compatible SQL and run
unchanged on H2 in PostgreSQL mode and on real PostgreSQL (`PostgresMigrationTest` boots the context
against an embedded PostgreSQL 17).

## Configuration (environment variables)

Every option is bound through `application.yml` (`dbp.*`, `@ConfigurationProperties`). Environment wins.

| Variable | Default | Meaning |
|---|---|---|
| `DBP_PORT` | `8080` | HTTP port (API + UI) |
| `DBP_DB_URL` | dev: `jdbc:h2:file:./data/dbp;MODE=PostgreSQL;…`; postgres: `jdbc:postgresql://localhost:5432/dbp` | Metadata store JDBC URL |
| `DBP_DB_USER` / `DBP_DB_PASSWORD` | dev: `sa` / empty; postgres: `dbp` / `dbp` | Metadata store credentials |
| `DBP_DB_POOL_SIZE` | `10` | Hikari pool size (postgres profile) |
| `DBP_SERVICE_TOKEN` | `dev-service-token` | Shared secret gateways/proxies send as `X-DBP-Service-Token` on `/api/v1/internal/**` |
| `DBP_MASTER_KEY` | *(unset → built-in dev key, WARN at startup)* | AES-256-GCM key (sha-256 of the value) for INLINE credential secrets |
| `DBP_SECURITY_MODE` | `none` | `none` or `basic` (HTTP Basic on the public `/api/v1/**`; internal endpoints always need the service token) |
| `DBP_ADMIN_USER` / `DBP_ADMIN_PASSWORD` | `admin` / `admin` | Operator credentials for `basic` mode |
| `DBP_TELEMETRY_RETENTION_HOURS` | `72` | Retention of raw query/connection events and pool snapshots |
| `DBP_QUERY_STATS_RETENTION_DAYS` | `30` | Retention of hourly query statistics |
| `DBP_TELEMETRY_CLEANUP_INTERVAL_SECONDS` | `600` | Retention job interval |
| `DBP_GOVERNANCE_ENABLED` | `true` | Scheduled governance evaluation |
| `DBP_GOVERNANCE_INTERVAL_SECONDS` | `300` | Governance job interval (also `POST /api/v1/governance/evaluate`) |
| `DBP_RELATIONSHIP_STALE_DAYS` | `30` | Relationships without activity for this long are reported `stale` |
| `DBP_DEMO_SEED_ENABLED` | `true` (dev), `false` (postgres) | Enables `POST /api/v1/seed/demo` |
| `DBP_SEED_ON_STARTUP` | `false` | Load the demo dataset at startup |
| `DBP_COLLECTOR_ENABLED` | `true` | Global switch for the collectors (each database has `collector.enabled` too) |
| `DBP_COLLECTOR_TICK_SECONDS` | `5` | Scheduler tick; each database runs on its own intervals |
| `DBP_COLLECTOR_CONNECT_TIMEOUT_SECONDS` | `10` | JDBC connect timeout of collector connections |
| `DBP_COLLECTOR_MAX_SQL_PER_SAMPLE` | `200` | Max new statements fetched from V$SQL / pg_stat_statements per runtime sample |
| `DBP_COMPONENT_HEALTHY_SECONDS` | `30` | A gateway/proxy is `healthy` while its last heartbeat is younger than this |
| `DBP_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` | CORS origins for `/api/**` (Vite dev server) |

Credential providers: `INLINE` (encrypted with the master key), `ENV` (variable read on the control
plane host), `FILE` (file read on the control plane host). `VAULT`, `GCP_SECRET_MANAGER` and
`AWS_SECRETS_MANAGER` are accepted as configuration but `GET /internal/credentials/{id}/material`
answers `501` for them in this POC. Every material request is logged by the
`org.dbplatform.controlplane.audit.credentials` logger.

## Modules of the code base

| Package | What |
|---|---|
| `api`, `api.internal` | REST controllers (public and `/internal/**`), error body, DTOs |
| `domain`, `repo` | JPA entities (string UUID ids, JSON-in-TEXT converters) and Spring Data repositories |
| `service` | CRUD services, routing resolution, catalogue, summaries, stats, import/export |
| `service.telemetry` | Telemetry ingestion (idempotent on `eventId`), heartbeats, live connections, retention |
| `service.graph` | Graph (`<type>:<refId>` node ids, depth-limited BFS) and impact analysis with risk score |
| `service.governance` | The five policies, violation lifecycle (OPEN → ACKNOWLEDGED/RESOLVED), inferred producer |
| `service.seed` | Retail demo dataset |
| `collector` | Scheduler, dictionary crawlers and runtime/audit samplers per engine, mergers |
| `security` | Service-token filter, optional HTTP Basic filter |

## Collectors

Per database (`collector.enabled`): a **dictionary crawl** every `dictionaryIntervalSeconds`, a
**runtime sample** every `runtimeIntervalSeconds` and, when `auditTrail` is on, an incremental
**audit** read. Collectors connect with `collector.credential` when set, else the database credential.
`POST /databases/{id}/collect {"what":"DICTIONARY|RUNTIME|AUDIT"}` triggers a run,
`GET /databases/{id}/collector-status` reports the last runs and errors.

* **Oracle** — dictionary: `DBA_TABLES/VIEWS/MVIEWS/TAB_COLUMNS/TAB_COMMENTS/COL_COMMENTS/OBJECTS/PROCEDURES/
  DEPENDENCIES/TRIGGERS/CONSTRAINTS/SOURCE`, falling back to the `ALL_*` views on `ORA-00942`
  (an account with `SELECT_CATALOG_ROLE` sees everything through `DBA_*`; `ALL_*` only shows objects the
  account has privileges on). Package members come from `DBA_PROCEDURES`; the package body text is split
  per member to derive READS/WRITES (confidence 0.8) where the dictionary only says REFERENCES.
  Runtime: `V$SESSION` (SID, SERIAL#, USERNAME, STATUS, PROGRAM, MODULE, ACTION, CLIENT_IDENTIFIER, MACHINE,
  OSUSER, PORT, SERVICE_NAME, LOGON_TIME, SQL_ID, PREV_SQL_ID, SQL_EXEC_START), `V$SQL` and `V$SQL_PLAN`
  for new SQL_IDs. Audit: `UNIFIED_AUDIT_TRAIL` by `EVENT_TIMESTAMP`. No Diagnostics/Tuning Pack views
  (no ASH/AWR/`DBA_HIST_*`).
* **PostgreSQL** — `information_schema.tables/columns`, `pg_matviews`, `pg_stat_user_tables`,
  `pg_description`, `pg_constraint`, `pg_proc` (+ `prosrc` parsing), `pg_trigger`, `pg_views`; runtime:
  `pg_stat_activity` and `pg_stat_statements` when installed.
* **SQL Server** — `sys.tables/views/columns/foreign_keys/objects/triggers/sql_expression_dependencies/
  sql_modules`; runtime: `sys.dm_exec_sessions/connections/requests/sql_text`.

Session attribution: proxy correlation first (session port == `proxyLocalPort` of a live proxied
connection to the same backend → `PROXY_CORRELATION`, confidence 0.9), then identity rules in contract
order (`serviceAliases` → `pgApplicationNames`/`programNames` → `machinePatterns` → `cidrs`, →
`COLLECTOR_SESSION`, confidence 0.6). Table references come from the execution plan when available,
else from `org.dbplatform.common.sql.SqlAnalyzer` plus a regex scan that also understands procedural bodies.

## Tests

`mvn -pl dbp-control-plane test` — one shared Spring context on H2 (MockMvc) covering CRUD of every
resource, api keys and authentication, datasource resolution precedence, telemetry ingestion
(relationships, hourly query stats, CALL expansion through dictionary dependencies, idempotency),
heartbeats/live connections/pools, the demo graph and impact analysis, governance, import/export
(including `deploy/bootstrap/platform-config.json`), service-token enforcement; crawler mapping tests
against canned result sets (stub JDBC connection); and `PostgresMigrationTest` which boots the context
on the `postgres` profile against an embedded PostgreSQL.
