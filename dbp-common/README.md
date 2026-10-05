# dbp-common

Server-side code shared by the gateway, the proxy and the control plane. Plain Java 21, no framework.
Dependencies: Jackson (databind + jsr310), JSqlParser, SLF4J API. Everything here is safe to call from
the data path: nothing blocks on the network except the explicit `ControlPlaneClient` calls, and the
SQL analyzer never throws.

```xml
<dependency>
    <groupId>org.dbplatform</groupId>
    <artifactId>dbp-common</artifactId>
</dependency>
```

## Packages

| Package                              | Contents                                                                                                  |
|--------------------------------------|-----------------------------------------------------------------------------------------------------------|
| `org.dbplatform.common.telemetry`    | Jackson records of `docs/telemetry-events.md` (`QueryEvent`, `ConnectionEvent`, `PoolStats`, `Heartbeat`, `TableAccess`, `RoutineRef`) and their enums (`Engine`, `SqlOperation`, `AccessType`, `ConnectionEventType`, `IdentitySource`, `ComponentType`); `TelemetryJson` (the one `ObjectMapper` configuration); `TelemetryClient` (non-blocking batched reporter). |
| `org.dbplatform.common.controlplane` | `ControlPlaneClient` for the internal endpoints of `docs/control-plane-api.md` §9–10, `ControlPlaneException`, and the response records (`ApplicationIdentity`, `DatasourceResolution` with `DatasourceInfo` / `GrantInfo` / `DatabaseInfo` / `CredentialRef`, `PoolPolicy`, `CredentialMaterial`, `ProxyConfig` with `Listener` / `Route` / `ProxyApplication` / `IdentityRules` / `Quota` / `DatasourceQuota`, `ConfigVersion`, `AuthRequest`). The control plane reuses these records for its own responses so both sides agree on field names. |
| `org.dbplatform.common.sql`          | `SqlAnalyzer` → `SqlAnalysis` (operation, tables with READ/WRITE, routines, columns, normalised SQL, hash), `SqlNormalizer`, `SqlOperationClassifier`. |
| `org.dbplatform.common.util`         | `Env` (`DBP_*` configuration), `Ids` (ULID / UUID), `Hostnames`, `Durations`.                             |

## Telemetry models

Field names are exactly those of `docs/telemetry-events.md`. Records are immutable; `QueryEvent` and
`ConnectionEvent` have builders. Lists default to empty, `errorMessage`/`reason` are truncated to 500
chars, `sqlNormalized` to 4000, `Heartbeat.Stats.liveConnections` to 2000 entries.

```java
QueryEvent event = QueryEvent.builder()
        .eventId(Ids.ulid()).gatewayId(gatewayId).sessionId(session.id())
        .application("orders-service").datasource("sales").engine(Engine.ORACLE)
        .sqlHash(analysis.sqlHash()).sqlNormalized(analysis.normalizedSql())
        .operation(analysis.operation()).tables(analysis.tables()).routines(analysis.routines())
        .columns(analysis.columns()).durationMs(31).rows(1).success(true)
        .poolMode("TRANSACTION").clientInfo(Map.of("module", "checkout"))
        .build();
```

`TelemetryJson.mapper()` is the shared mapper: `JavaTimeModule`, ISO-8601 instants, unknown properties
ignored, nulls omitted, unknown enum values mapped to a default (`Engine.OTHER`, `SqlOperation.OTHER`,
`IdentitySource.NONE`, `ConnectionEventType.BACKEND_FAILED`). Use `TelemetryJson.toJson` /
`fromJson` / `listFromJson`, or `TelemetryJson.newMapper()` when you need to add modules (Spring Boot:
expose `TelemetryJson.newMapper()` as the primary `ObjectMapper` bean).

## TelemetryClient

```java
TelemetryClient telemetry = TelemetryClient.fromEnv().build();      // DBP_CONTROL_PLANE_URL, DBP_SERVICE_TOKEN, ...
telemetry.record(queryEvent);        // QueryEvent      → POST /api/v1/internal/telemetry/queries
telemetry.record(connectionEvent);   // ConnectionEvent → POST /api/v1/internal/telemetry/connections
telemetry.record(poolStats);         // PoolStats       → POST /api/v1/internal/telemetry/pools
telemetry.droppedCount();            // expose as a metric (events lost because the queue was full)
telemetry.close();                   // on shutdown: flushes what it can, never throws
```

* Bounded in-memory queue (default 50 000, `DBP_TELEMETRY_QUEUE_SIZE`); when full the **oldest** event is
  dropped and counted.
* One virtual thread posts JSON arrays of at most 500 events, every `DBP_TELEMETRY_FLUSH_MS` (default
  2000) or as soon as 500 events accumulate; `flush()` forces an early send.
* Header `X-DBP-Service-Token`, `Content-Type: application/json`. A batch is sent as one POST per event
  kind. A failed POST (connection refused, timeout, 5xx, 408, 429) re-queues **only the events of that
  POST** at the head and retries them after the flush interval — events of another kind delivered in the
  same cycle are never sent twice, and `sentCount()` counts delivered events only; a 4xx rejection drops
  the events of that POST (retrying would not help). Warnings are rate limited to one per 10 s.
* `record(...)` never blocks and never throws. The client uses `HttpClient.Builder.NO_PROXY`; pass your
  own `HttpClient` through the builder for TLS or proxy settings.

| Variable                   | Default                 | Meaning                                  |
|----------------------------|-------------------------|------------------------------------------|
| `DBP_CONTROL_PLANE_URL`    | `http://localhost:8080` | Control plane base URL                   |
| `DBP_SERVICE_TOKEN`        | `dev-service-token`     | Value of `X-DBP-Service-Token`           |
| `DBP_TELEMETRY_FLUSH_MS`   | `2000`                  | Flush interval                           |
| `DBP_TELEMETRY_QUEUE_SIZE` | `50000`                 | Queue capacity                           |
| `DBP_TELEMETRY_BATCH_SIZE` | `500`                   | Max events per POST (hard cap 500)       |
| `DBP_CONTROL_PLANE_TIMEOUT`| `5s`                    | `ControlPlaneClient` request timeout     |

## ControlPlaneClient

Synchronous, thread-safe; every method sends the service token, applies the timeout and throws
`ControlPlaneException` (`status()` = HTTP status, `0` for transport errors; `errorCode()`, `path()`,
`isUnauthorized()`, `isForbidden()`, `isNotFound()`, `isRetryable()`).

```java
ControlPlaneClient cp = ControlPlaneClient.fromEnv();
Optional<Long> version = cp.heartbeat(new Heartbeat(ComponentType.GATEWAY, "gw-1", "0.1.0",
        Hostnames.localHostName(), startedAt, currentConfigVersion, Heartbeat.Stats.gateway(180, 61, telemetry.droppedCount())));
long configVersion = cp.configVersion();                                  // poll every few seconds
ApplicationIdentity app = cp.authenticateApplication(apiKey);              // 401 → ControlPlaneException
DatasourceResolution ds = cp.resolveDatasource("sales", app.applicationId()); // 403 / 404 → ControlPlaneException
CredentialMaterial cred = cp.credentialMaterial(ds.credential().id());
ProxyConfig proxyConfig = cp.proxyConfig("proxy-1");
```

`get(path, type)` / `post(path, body, type)` are public for any other internal endpoint.

## SQL analysis

```java
SqlAnalyzer analyzer = new SqlAnalyzer();            // LRU cache of 10 000 (engine, sql) analyses; share one instance
SqlAnalysis a = analyzer.analyze(sql, Engine.ORACLE);
a.operation();      // SELECT | INSERT | UPDATE | DELETE | MERGE | CALL | DDL | TXN | OTHER
a.tables();         // [TableAccess(schema, name, READ|WRITE)] as written in the SQL, quoting removed, deduplicated
a.routines();       // [RoutineRef(schema, name)]  Oracle: name = PKG.PROC
a.columns();        // ["CUSTOMER.EMAIL", ...] best effort
a.normalizedSql();  // literals/numbers/binds → ?, comments removed (hints kept), whitespace collapsed, ≤ 4000 chars
a.sqlHash();        // sha-256 hex of normalizedSql
a.parseOk();        // false when the regex fallback was used
```

How it works: `SqlOperationClassifier` looks at the leading keywords (handles comments, `WITH … INSERT`,
`BEGIN` block vs. `BEGIN` transaction, `DECLARE @x` batches, `{call …}` escapes). JSqlParser then parses
the statement (Oracle `:1` binds are rewritten to `?`, `RETURNING … INTO :x` is stripped first); the
visitor records tables with aliases, columns, function calls and CTE names, and the statement type
decides which tables are written (INSERT/UPDATE/DELETE/MERGE targets, DDL targets, `SELECT … INTO`).
`INSERT … SELECT` and `MERGE … USING` mark the target WRITE and the sources READ; subqueries, joins,
CTEs, `UPDATE … FROM`, `DELETE … USING`, T-SQL alias targets (`UPDATE c … FROM dbo.Customer c`) are
handled. Function calls that are not SQL built-ins are reported as routines (`SELECT pkg.fn(…) FROM
dual`, `TABLE(pkg.fn(…))`, `CROSS APPLY dbo.fn(…)`, `SELECT * FROM my_fn(1)`). Anything JSqlParser
rejects (Oracle `INSERT ALL`, anonymous PL/SQL blocks, JDBC `{call}` escapes, flashback queries,
`MERGE` without `INTO`, garbage) goes through a keyword/regex extractor (`FROM a, b c`, `JOIN`,
`INTO`, `UPDATE`, `DELETE [FROM]`, `USING`, `TABLE`, `INDEX … ON`, PL/SQL procedure/function calls,
`EXEC`) and is flagged `parseOk=false`.

Performance: ~100–150 µs per uncached statement per thread (JSqlParser), ~1 µs cached; the normaliser
alone is ~1 µs. Multi-statement batches are analysed statement by statement and merged.

Known limitations:

* Names are reported as written (no case folding, no default-schema resolution); the control plane
  resolves them against the catalogue with the session's default schema.
* Oracle 2-part routine names are reported as `PKG.PROC` with `schema = null` (a 2-part name may also
  be `SCHEMA.PROC`; only the catalogue can tell). SQL Server / PostgreSQL 2-part names are
  `schema.routine`.
* Unknown built-in functions may surface as (unresolvable) routines; synonyms, views and dblinks are
  reported as plain tables.
* Columns are only attributed when qualified by an alias/table name or when the statement touches a
  single table; `SELECT *` yields no columns.
* The regex fallback does not understand scoping: in a PL/SQL block every `UPDATE`/`DELETE`/`INSERT INTO`
  target is reported, and `SELECT … INTO variable` is ignored only when the variable is unqualified.
* `LOCK TABLE` is reported as `OTHER` with the table marked WRITE; `GRANT`/`REVOKE` report no tables.
* JSqlParser's complex (backtracking) mode is off by default for throughput; `new SqlAnalyzer(size,
  true)` enables it as a second attempt.

## Utilities

* `Env.get("DBP_X", default)`, `getInt`, `getLong`, `getBoolean`, `getDuration`, `getEnum`, `require`:
  environment variable first, then system property `DBP_X`, then `dbp.x` (environment wins).
* `Ids.ulid()` — 26-char Crockford base32 ULID, monotonic within a millisecond; `Ids.uuid()`; `Ids.token(n)`.
* `Hostnames.localHostName()` — `HOSTNAME` / `COMPUTERNAME`, then `InetAddress`, then `localhost`.
* `Durations.parse("2000" | "2s" | "500ms" | "1h30m" | "PT2S")`, `Durations.format(d)`.

## Build

```
mvn -q -pl dbp-common install
```

Tests cover JSON round trips of every record (exact field names), the telemetry client against an
in-process `HttpServer` (batching, header, JSON shape, drop/retry behaviour), the control plane client,
and 100+ statements across Oracle, PostgreSQL, SQL Server and ANSI including garbage input.
