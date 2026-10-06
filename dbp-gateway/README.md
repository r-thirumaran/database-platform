# dbp-gateway

The gateway is the heart of the data plane. Applications connect to it with the drop-in JDBC driver
(`dbp-jdbc`, `jdbc:dbp://gateway:7420/<datasource>`); the gateway owns bounded physical connection pools
per physical database, maps **many logical sessions onto few physical connections**, applies credentials
fetched centrally, forwards everything (including PL/SQL calls with OUT parameters and `REF CURSOR`s) to
the vendor JDBC driver and records per-statement telemetry with tables/routines extracted.

Plain Java 21 (virtual threads, no Spring). Wire protocol: [`docs/wire-protocol.md`](../docs/wire-protocol.md).

```
java -jar dbp-gateway-0.1.0-SNAPSHOT-all.jar          # executable shaded jar, all JDBC drivers included
```

## How it works

### Logical vs physical connections

Every TCP connection from the driver is one **logical session** (`LogicalSession`). Physical connections
live in HikariCP pools keyed by (physical database, credential version). A logical session *borrows* a
physical connection only when it needs one and gives it back as soon as the rules below allow it. Session
settings (autocommit, isolation, read-only, schema, catalog, client info, network timeout) are remembered by
the gateway and re-applied to every newly borrowed connection, so un-pinning is invisible to the
application.

```
autocommit statement:   borrow → execute → stream first rows → (cursor open: stay pinned) → cursor closed → return
transaction:            SET_AUTOCOMMIT false → first EXECUTE pins → … → COMMIT/ROLLBACK → return (once cursors closed)
SESSION pool mode:      first EXECUTE pins for the life of the logical session
```

### Pinning rules

| Event                                           | TRANSACTION mode                                                     | SESSION mode              |
|-------------------------------------------------|----------------------------------------------------------------------|---------------------------|
| first EXECUTE / EXECUTE_BATCH / METADATA / SET_SAVEPOINT | borrow a physical connection, apply remembered settings (incl. `setAutoCommit`) | same          |
| statement with autocommit on, result set exhausted (`last=1`) | returned to the pool immediately                             | stays pinned              |
| statement with autocommit on, cursor still open | pinned until the cursor is closed/exhausted (FETCH `last=1`, CLOSE_CURSOR, CLOSE_STATEMENT) | stays pinned |
| statement with autocommit off                   | pinned until COMMIT / ROLLBACK (full) / SET_AUTOCOMMIT true, **and** no cursor open | stays pinned  |
| COMMIT / ROLLBACK with cursors open             | commit/rollback on the physical connection, stay pinned until the cursors close (section 4.8) | stays pinned |
| CLOSE / socket EOF / idle timeout               | rollback if a transaction may be open, reset to defaults, return      | same                      |
| physical connection failure                     | connection evicted; fatal ERROR `08006` if a transaction, cursor or SESSION-mode state was lost, otherwise the driver's error and the next statement borrows a fresh connection | fatal `08006` |

When a connection is returned: `rollback()` if autocommit was off, `setAutoCommit(true)`, warnings cleared,
cached physical `PreparedStatement`s closed, client info reset to the pool baseline. HikariCP resets
isolation / read-only / catalog / schema / network timeout to its defaults.

Physical `PreparedStatement`s are created lazily at EXECUTE time (PREPARE never touches the database) and
cached per (session, statementId, generated-keys configuration) only while the session stays pinned. A variant with a
different generated-keys configuration is never closed while a cursor of its last execution is still open.

**A transaction starts with the first statement *attempted* with autocommit off, even when that statement fails.** The
session stays pinned, so the following `ROLLBACK` reaches the same physical connection and later statements see the
database's own semantics (PostgreSQL answers `25P02 current transaction is aborted` until the rollback, Oracle and H2
carry on) — exactly what a direct connection does. Only a *connection* failure on that first statement is reported as a
plain (non-fatal) error: nothing was lost, the next statement borrows a fresh connection.

### Pool modes

| Pool mode     | Pinning                                                         | Use for                                                        |
|---------------|-----------------------------------------------------------------|----------------------------------------------------------------|
| `TRANSACTION` | from first statement with autocommit off (or open cursor) until commit/rollback and cursor close | stateless services, batch jobs, most Spring/JPA applications |
| `SESSION`     | for the whole logical connection                                | legacy code relying on package variables, temp tables, `ALTER SESSION`, `DBMS_OUTPUT` |

Resolution order per session: grant override (`poolModeOverride` / static `applications[].datasources[].poolMode`)
> datasource pool policy > `TRANSACTION`. The applied mode is delivered in `HELLO_OK.serverProperties.poolMode`.

### Identity and credentials

* **Control plane mode** (`DBP_CONTROL_PLANE_URL` set): HELLO `apiKey` → `POST /internal/auth/application`
  (positive results cached `DBP_AUTH_CACHE_SECONDS`, negative 5 s) → `GET /internal/resolve/datasource/{name}?applicationId=`
  (cached per (datasource, application) until `configVersion` changes; polled every `DBP_CONFIG_POLL_SECONDS`
  and also learned from heartbeat answers) → credential material from `GET /internal/credentials/{id}/material`
  when a pool is created. A control plane outage never affects the data path: stale cache entries keep serving.
* **Static mode** (`DBP_GATEWAY_CONFIG` YAML): datasources, credentials and optional applications/grants
  come from the file.

Credential rotation: the next resolution carries a new `credential.version` → a new pool is created for new
pins, the old one is marked draining (`softEvictConnections`) and closed once nothing is borrowed from it.
Existing logical sessions re-resolve their datasource at every pin (a cache lookup; one `/internal/resolve` call
after a `configVersion` change, never a re-authentication) and so switch to the new pool at their next pin
(after COMMIT/ROLLBACK in TRANSACTION mode).

### Physical pools

One HikariCP pool per (database, credential version); `maxConnections` is a hard cap for this gateway
instance. Engine specific connection properties are added so DBAs can see the gateway:

| Engine     | Properties set unless configured                                                               |
|------------|------------------------------------------------------------------------------------------------|
| Oracle     | `v$session.program=dbp-gateway/<gatewayId>`, `oracle.net.CONNECT_TIMEOUT`, `oracle.jdbc.ReadTimeout` (when a statement timeout is configured) |
| PostgreSQL | `ApplicationName=dbp-gateway/<gatewayId>`, `connectTimeout`, `stringtype=unspecified` (UUID / JSON / enum parameters travel as STRING and must be inferred by the server) |
| SQL Server | `applicationName=dbp-gateway/<gatewayId>`, `loginTimeout`                                       |

Client info set by the application (`Connection.setClientInfo`) is forwarded to the physical connection
while pinned and reset to the gateway baseline on release. For Oracle, `ApplicationName`→`OCSID.MODULE`,
`ClientUser`→`OCSID.CLIENTID`, `action`→`OCSID.ACTION` are set in addition to the original names.

## Running

```
# control plane mode
DBP_CONTROL_PLANE_URL=http://control-plane:8080 DBP_SERVICE_TOKEN=... DBP_GATEWAY_ID=gw-1 \
  java -jar dbp-gateway-0.1.0-SNAPSHOT-all.jar

# static mode
DBP_GATEWAY_CONFIG=/etc/dbp/gateway.yaml java -jar dbp-gateway-0.1.0-SNAPSHOT-all.jar
```

### Environment variables

| Variable                              | Default                  | Meaning                                                                 |
|---------------------------------------|--------------------------|-------------------------------------------------------------------------|
| `DBP_GATEWAY_ID`                      | `gw-<hostname>`          | identifier reported in telemetry, `v$session.program`, session ids      |
| `DBP_GATEWAY_PORT`                    | `7420`                   | wire-protocol port                                                      |
| `DBP_GATEWAY_ADMIN_PORT`              | `7421`                   | admin HTTP port (`-1` disables)                                         |
| `DBP_GATEWAY_BIND`                    | `0.0.0.0`                | bind address                                                            |
| `DBP_GATEWAY_ADVERTISED_HOST`         | local hostname           | host in the logical `url` server property                               |
| `DBP_GATEWAY_IDLE_TIMEOUT_SECONDS`    | `1800`                   | socket read timeout: idle logical sessions are closed (fatal `08006`)   |
| `DBP_GATEWAY_MAX_FRAME_BYTES`         | `67108864` (64 MiB)      | maximum frame size accepted and produced                                |
| `DBP_GATEWAY_ROWS_FRAME_SOFT_BYTES`   | `4194304` (4 MiB)        | a ROWS frame stops early (fewer rows than `fetchSize`) beyond this size |
| `DBP_GATEWAY_MAX_SESSIONS`            | `0` (unlimited)          | global cap of logical sessions (`08004` "too many logical connections") |
| `DBP_GATEWAY_MAX_OPEN_CURSORS`        | `256`                    | open cursors per session (`HY000` beyond)                               |
| `DBP_GATEWAY_SHUTDOWN_GRACE_SECONDS`  | `20`                     | wait for in-flight statements on shutdown (see [Shutdown](#shutdown))   |
| `DBP_CONTROL_PLANE_URL`               | –                        | control plane base URL; unset = static mode                             |
| `DBP_SERVICE_TOKEN`                   | `dev-service-token`      | `X-DBP-Service-Token` for `/internal/*`                                 |
| `DBP_GATEWAY_CONFIG`                  | –                        | static YAML file (static mode)                                          |
| `DBP_AUTH_CACHE_SECONDS`              | `60`                     | positive api-key cache TTL (negative: 5 s)                              |
| `DBP_CONFIG_POLL_SECONDS`             | `5`                      | `/internal/config-version` polling interval                             |
| `DBP_POOL_STATS_SECONDS`              | `15`                     | `PoolStats` reporting interval                                          |
| `DBP_HEARTBEAT_SECONDS`               | `10`                     | heartbeat interval                                                      |
| `DBP_TELEMETRY_FLUSH_MS` / `DBP_TELEMETRY_QUEUE_SIZE` | `2000` / `50000` | telemetry batching (see `docs/telemetry-events.md`)              |
| `DBP_GATEWAY_TLS_KEYSTORE`            | –                        | PKCS12/JKS keystore: enables TLS on the wire-protocol port              |
| `DBP_GATEWAY_TLS_KEYSTORE_PASSWORD`   | –                        | keystore password                                                       |
| `DBP_LOG_LEVEL` / `DBP_LOG_LEVEL_HIKARI` | `INFO` / `WARN`       | log levels (logback)                                                    |

System properties with the same names are accepted as a fallback.

### Static YAML schema

```yaml
gatewayId: gw-local                     # env DBP_GATEWAY_ID wins
datasources:
  - name: sales                         # logical name (jdbc:dbp://gateway:7420/sales)
    engine: ORACLE                      # ORACLE | POSTGRES | MSSQL | H2 | OTHER (optional, derived from jdbcUrl)
    jdbcUrl: jdbc:oracle:thin:@//oracle.example.org:1521/FREEPDB1
    username: SALES_APP
    passwordEnv: SALES_ORACLE_PASSWORD  # password | passwordEnv | passwordFile
    poolMode: TRANSACTION               # TRANSACTION (default) | SESSION
    maxConnections: 40                  # default 10
    minIdle: 2                          # default 0
    connectionTimeoutMs: 10000          # default 10000 → ERROR 08001 when exceeded
    idleTimeoutMs: 600000
    maxLifetimeMs: 1800000
    statementTimeoutSeconds: 0          # caps Statement.setQueryTimeout (0 = none)
    validationQuery: null               # HikariCP connectionTestQuery, only when needed
    jdbcProperties: { oracle.jdbc.ReadTimeout: "60000" }
  - name: inventory
    engine: POSTGRES
    jdbcUrl: jdbc:postgresql://postgres.example.org:5432/inventory
    username: inventory_app
    passwordFile: /run/secrets/inventory-postgres-password
  - name: scratch
    engine: H2
    jdbcUrl: jdbc:h2:mem:scratch;DB_CLOSE_DELAY=-1;MODE=Oracle
    username: sa
    password: ""
    poolMode: SESSION
applications:                           # optional; absent = any `application` hint is accepted, no api key needed
  - name: orders-service
    apiKey: ${ORDERS_SERVICE_API_KEY:-dbp_orders_dev}   # ${ENV} / ${ENV:-default} placeholders
    team: sales-platform
    datasources:
      - sales                           # plain name = default grant
      - name: inventory                 # object = grant options
        maxLogicalConnections: 50
        readOnly: true
        poolMode: TRANSACTION
```

The full example ships as `src/main/resources/gateway-example.yaml`.

## Admin endpoints (port 7421)

| Path        | Content                                                                                          |
|-------------|--------------------------------------------------------------------------------------------------|
| `/health`   | JSON: status, gatewayId, version, uptime, logical/pinned sessions, physical connections, control plane (mode, reachable, configVersion, telemetry drops, last heartbeat), pools |
| `/metrics`  | Prometheus text format (below)                                                                   |
| `/sessions` | JSON list: sessionId, application, datasource, poolMode, pinned, inTransaction, autoCommit, openCursors, statements, idleSeconds, ageSeconds, pinnedSeconds, clientAddress, clientInfo, schema |
| `/pools`    | JSON list: key, datasources, engine, jdbcUrl, username, credentialVersion, active/idle/waiting/total/max, pinnedSessions, draining |

### Metrics

| Metric                                                   | Labels                             |
|----------------------------------------------------------|------------------------------------|
| `dbp_gateway_logical_sessions`                           | `datasource`                       |
| `dbp_gateway_pinned_sessions`                            | `datasource`                       |
| `dbp_gateway_pool_active` / `_idle` / `_waiting` / `_connections` / `_max` | `datasource`     |
| `dbp_gateway_statements_total`                           | `datasource`, `operation`, `success` |
| `dbp_gateway_statement_duration_seconds` (histogram: `_bucket`, `_count`, `_sum`, `_max`) | `datasource` |
| `dbp_gateway_errors_total` (every ERROR frame sent, incl. rejected HELLOs) | `sqlstate`         |
| `dbp_gateway_telemetry_dropped_total`                    | –                                  |

`dbp_gateway_pool_connections` is the total number of physical connections of the datasource's pools (the Prometheus
exposition format strips a `_total` suffix from gauges, so the metric cannot be called `dbp_gateway_pool_total`).
The per-datasource gauges are registered with zero values at startup for every datasource of the static configuration
(and at the first HELLO for datasources learned from the control plane), so dashboards see every datasource right away.

## Telemetry

After every EXECUTE and EXECUTE_BATCH (not METADATA) a `QueryEvent` is enqueued (never blocking the data
path) with the SQL analysed by `SqlAnalyzer` (normalised SQL + sha-256, operation, tables with READ/WRITE,
routines, columns), duration, rows (update count, rows of the first batch for queries, -1 unknown),
success/SQLState/vendor code/message, `pinned` (the session was pinned *before* the statement), pool mode,
client info and the default schema (session schema or the pool user's schema). `PoolStats` are reported
every `DBP_POOL_STATS_SECONDS`, heartbeats every `DBP_HEARTBEAT_SECONDS`.

## Shutdown

`Gateway.stop()` (SIGTERM) proceeds in this order:

1. stop accepting; new HELLOs are answered with fatal `08006 gateway is shutting down`;
2. wait up to `DBP_GATEWAY_SHUTDOWN_GRACE_SECONDS` for in-flight statements, so their responses reach the clients;
3. close the client sockets: idle handlers unblock and release their sessions themselves (rollback, reset, return to
   the pool); a handler still executing fails on its next write and does the same when its driver call returns;
4. wait for the handler threads for the rest of the grace period (at least 2 s);
5. close whatever session is still registered — but only when its handler is not executing: a session's own cleanup and
   the shutdown path both release under the session lock and the release is idempotent, so a physical connection is never
   returned to the pool twice, nor while a statement still runs on it;
6. flush telemetry, close the pools (which aborts the connection of a handler still stuck in a driver call).

Worst case the shutdown takes the grace period plus 2 s.

## Sizing guidance

* `maxConnections` per datasource is per gateway instance: with N instances the database sees up to
  N × `maxConnections` sessions. Start with (expected concurrent transactions + expected open cursors) × 1.2.
* `connectionTimeoutMs` is how long a logical session waits for a free physical connection before the driver
  gets `08001` (`SQLTransientConnectionException`); keep it below the application's own timeouts.
* `SESSION` mode consumes one physical connection per logical session: size the pool like the application
  pool and switch to `TRANSACTION` once telemetry shows no session-state dependence.
* Memory: each logical session is a virtual thread plus small buffers; a ROWS frame holds up to `fetchSize`
  rows (bounded by `DBP_GATEWAY_ROWS_FRAME_SOFT_BYTES`). Large LOB columns travel inline.
* 4 CPU cores comfortably drive a few hundred logical sessions; the physical pool, not the gateway, is the
  bottleneck.

## Protocol behaviour worth knowing (driver side)

* `expect = QUERY` on a statement that yields no result set answers ERROR `07005` **after** the statement ran
  (PostgreSQL/Oracle behave the same way for `executeQuery`); `expect = UPDATE` on a query is `07005` too.
* A ROWS frame may hold fewer rows than `fetchSize` (soft byte limit); only `last` tells whether the cursor is
  exhausted. ERROR can terminate an EXECUTE sequence after RESULT_SET_HEADER/ROWS were sent.
* Pure OUT parameters: send `NULL` (or a typed null) at that index in `params` (or omit trailing entries); the
  gateway registers OUT parameters first and does not bind such placeholders. INOUT parameters carry their value.
* `OUT_PARAMS` is sent whenever `OutParam[]` was non-empty; cursor-typed entries carry the `INT` cursorId of the
  result item streamed just before (section 4.7). `GENERATED_KEYS` is sent only when requested *and* the driver
  returned keys.
* An ERROR that terminates an EXECUTE sequence after result items were streamed closes the cursors of those items on
  the gateway (the driver discards them); the session is not left pinned by them.
* A connection must send HELLO within 15 s and in a frame of at most 256 KiB; afterwards the configured frame size and
  idle timeout apply.
* Every HELLO failure is `fatal = 1` and the socket is closed. `08001` (pool exhausted / database down) on a
  statement is **not** fatal: the session survives and may retry. `08006` is fatal (idle timeout, lost physical
  connection with state, shutdown).
* COMMIT/ROLLBACK with autocommit on are no-ops (OK); CLOSE_CURSOR of an unknown cursor is OK; FETCH of an
  unknown/closed cursor, unknown statement/savepoint ids are `HY000`. Unnamed savepoints are named `DBP_SP_<n>`.
* `PREPARED.parameterCount` is always `-1`.
* Unexpected gateway failures are reported as `HY000 internal gateway error (ref <id>)`: the exception class, message and
  stack trace never travel to the client, they are in the gateway log at WARN under the same `ref`.
* With `expect = ANY`, when the first result set is not exhausted by the first ROWS frame and the physical driver
  cannot `getMoreResults(KEEP_CURRENT_RESULT)`, further results of that execution are not delivered.

## Limitations

* **Session state is not isolated in `TRANSACTION` mode.** Everything the gateway cannot see and reset travels with
  the physical connection to the next logical session that borrows it: temporary tables, `SET` session variables
  (`search_path`, `statement_timeout`, …), `ALTER SESSION`, Oracle package state, `DBMS_OUTPUT` buffers, prepared
  server-side cursors. Applications that rely on any of these need a grant in `SESSION` mode. What *is* reset on release:
  autocommit, isolation, read-only, schema/catalog (when changed through JDBC), client info, network timeout, warnings.
* A grant's `readOnly` is enforced in two layers. The gateway rejects `INSERT`, `UPDATE`, `DELETE`, `MERGE` and DDL (as
  classified by `SqlAnalyzer`) with SQLState `25006` before the physical connection is touched, for every engine. In
  addition `Connection.setReadOnly(true)` is set on the physical connection (PostgreSQL pools get `readOnlyMode=always`,
  so the server rejects writes in autocommit statements too; Oracle starts read-only transactions; H2 treats it as a
  hint). Writes hidden in routines (`CALL`), anonymous blocks or vendor syntax the analyzer does not classify are caught
  only by that second layer.
* No XA / distributed transactions.
* Cursors are forward-only, read-only; no scrollable or updatable result sets, no holdable cursors across
  COMMIT beyond what the physical driver offers (section 4.8).
* LOBs are sent inline; a row larger than the maximum frame size fails the statement (`HY000`).
* `SESSION` mode gives no multiplexing for that application.
* One gateway instance = one pool set. Scale horizontally with care: pools are not coordinated across
  instances (each one honours its own `maxConnections`).
* The gateway runs physical JDBC calls on virtual threads (JDK 21); drivers that hold monitors during network
  I/O pin carrier threads, which limits parallelism to the number of carriers while statements run on the
  database. The gateway itself never holds a monitor around pool or driver calls (j.u.c locks only).
* `PREPARED.parameterCount` is always `-1` (the gateway never touches the database at PREPARE time).
* `TIME` values are forwarded with the physical driver's `java.time` precision (`getObject(i, LocalTime.class)`);
  drivers without `java.time` support fall back to `java.sql.Time` and lose the sub-millisecond part.
* `DATE` / `TIMESTAMP` travel as the `java.sql.Date` / `java.sql.Timestamp` the physical driver returns (legacy calendar,
  gateway default zone), and the driver rebuilds them with the same legacy bridges, so `getDate` / `getTimestamp` match a
  direct connection exactly. Only for wall times before 1893 (LMT offsets with seconds) or dates before the 1582 Gregorian
  cutover can `getObject(i, LocalDate/LocalDateTime.class)` differ from a direct connection's `java.time` accessors by that
  historical delta.
* A `BatchUpdateException` of the physical driver is reported as a plain ERROR: per-element update counts of a partially
  executed batch are not delivered (the protocol has no counts-with-error frame; a `BATCH_RESULT`-with-error frame is
  on the roadmap).
* PostgreSQL large objects (`oid` columns, `Blob`/`Clob` locators, Hibernate `@Lob` on PostgreSQL) are not supported:
  LOB parameters are bound as `bytea`/`text` values and `oid` columns arrive as the plain `oid` number.
* `Connection.setNetworkTimeout` set by the application is forwarded to the physical connection while the session is
  pinned (and reset on release), so the physical driver's own read timeout fires with it; the lower of the two hops wins
  and a timeout is fatal for the logical connection (`08006`).
* `DATE` / `TIME` / `TIMESTAMP` travel zone-less and are rebuilt with the legacy `java.sql` bridges in the gateway's and the
  application's default zone. A wall time inside the DST overlap hour (e.g. 02:30 on the autumn change) is ambiguous:
  both sides resolve it like `java.sql.Timestamp.valueOf` does (the earlier offset), which can differ from the physical
  driver's choice by one hour. Run gateway and applications in the same zone, preferably UTC.
* The admin endpoints (`/health`, `/metrics`, `/sessions`, `/pools`) are unauthenticated by default: bind
  `DBP_GATEWAY_BIND` to an internal interface (or restrict the admin port with a network policy) and expose them only
  through the platform proxy / control plane.
* `Connection.isValid` costs one PING round trip; application pools validating on every checkout (Hikari does, after
  `aliveBypassWindowMs`) add that latency per checkout — see the driver README for the recommended Hikari settings.

## Build and test

```
mvn -q -pl dbp-gateway package
```

Tests run against H2 (in-memory) and an embedded PostgreSQL (zonky). PostgreSQL refuses to run as root; the
test helper then starts the server through `runuser -u postgres` with the system binaries
(`/usr/lib/postgresql/<ver>/bin`, override with `DBP_TEST_PG_BIN`) or the binaries bundled with zonky.
