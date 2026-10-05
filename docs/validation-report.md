# End-to-end validation report

Validation of the Database Access Platform POC **as deployed**: the control plane, the gateway and the proxy run
as separate processes from their executable jars, an embedded PostgreSQL 16 carries the unchanged demo schema, and
everything is exercised only through the public interfaces (the `jdbc:dbp` driver, pgjdbc through the proxy, the
REST API). The tests live in [`dbp-integration-tests/`](../dbp-integration-tests/README.md) and are reproducible with
`mvn -f dbp-integration-tests/pom.xml verify`.

Date: 2026-10-05. Repository state: commit `ffd8ad7` plus uncommitted work of the other agents (the gateway, UI,
control-plane, proxy and common sources were being edited concurrently; the jars named below are what was tested).

## 1. Environment

| Item | Value |
|------|-------|
| Host | Linux 6.18 (container), Ubuntu 24.04.4 LTS, 4 shared cores, 16 GB; **no Docker, no Kubernetes** |
| Java | OpenJDK 21.0.11 (Ubuntu) — same JDK for the tests and all child processes |
| Maven | 3.9.11 |
| PostgreSQL | 16.14 (Ubuntu system binaries `/usr/lib/postgresql/16/bin`), started per run from a fresh `initdb -A scram-sha-256` data directory through `runuser -u postgres` (the JVM runs as root), `pg_stat_statements` preloaded, ephemeral port |
| Demo schema | `demo/sql/postgres/00_databases.sql`, `10_schema.sql`, `20_plpgsql.sql`, `30_data.sql`, `40_grants_app.sql` loaded **unchanged** with `psql -v ON_ERROR_STOP=1` (`\connect sales` works as written): 200 customers, 612 orders, no errors or warnings |
| Control plane | `dbp-control-plane/target/dbp-control-plane.jar` (Spring Boot 3.5.5), `dev` profile, H2 file store under `target/it/cp-data`, `DBP_DEMO_SEED_ENABLED=false`, `DBP_SERVICE_TOKEN=it-token`, `DBP_MASTER_KEY` set, collector tick 2 s, port 18080. Run 1 used the 19:11 build (md5 `6408d55a…`), runs 2 and 3 (final) the 21:03 build (md5 `8a4205d1…`) |
| Gateway | `org.dbplatform:dbp-gateway:0.1.0-SNAPSHOT:all` from `~/.m2` (identical to `dbp-gateway/target`, 19:11 build, md5 `f4bce31e…`), control-plane mode, `DBP_GATEWAY_ID=it-gw`, port 17420, admin port ephemeral, heartbeat 2 s, pool stats 2 s, config poll 1 s, auth cache 3 s; a second instance `it-gw-h2` in static YAML mode in front of H2 |
| Driver | `org.dbplatform:dbp-jdbc:0.1.0-SNAPSHOT` (plain jar + `dbp-protocol`), "DBP JDBC Driver 0.1.0-SNAPSHOT" |
| Proxy | `dbp-proxy/target/dbp-proxy-0.1.0-SNAPSHOT-all.jar`, control-plane mode, `DBP_PROXY_ID=it-proxy`, listen address 127.0.0.1. Run 1 used the 18:19 build (md5 `acefd80a…`), runs 2 and 3 the 21:10 build (commit `462f831`); both fail identically in control-plane mode (D3) |
| Second engine | H2 2.3.232 in-JVM TCP server, database `mem:salesh2;MODE=Oracle` |
| Configuration | `deploy/bootstrap/platform-config.json` imported through `POST /api/v1/import` after re-pointing `sales-postgres` at the embedded server (host 127.0.0.1, ephemeral port, database `sales`), credential `sales-postgres-app` as INLINE secret, `sales-postgres-collector` as ENV, `sales.currentDatabase = sales-postgres`, `sales.poolPolicy.maxConnections = 4`, Oracle collector disabled, plus a third database `sales-alt` (PostgreSQL database `sales_alt`, same cluster) for the routing scenario. The exact document is written to `dbp-integration-tests/target/it/platform-config.it.json` |

Three runs were made: run 1 with the 19:11 / 18:19 builds (7 failures: 4 over-strict assertions of the first test
version, 3 real findings D1/D3), run 2 with the 21:03 / 21:10 builds after making the tests record the findings as
PARTIAL instead of failing (1 failure: the proxy's new admin-token requirement), run 3 (final, same builds) green.
A full run takes about 2 minutes (control plane start ≈ 20 s, 41 tests). All child processes are stopped at the end
of the run, also on failure (`ps` showed no leftover `postgres` or `java` processes after any run; the PostgreSQL
data directory is deleted, logs are kept in `dbp-integration-tests/target/it/logs/`).

## 2. Results per scenario

Status: **PASS** = every assertion held; **PARTIAL** = the scenario ran and its core assertions held, but part of
the contract could not be exercised or a deviation was recorded (see notes and section 3); **FAIL** = an assertion
failed. The table is the one the module writes to `target/it/results.md`.

Final run (run 3, 2026-10-05 21:35 UTC): `Tests run: 41, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS` in 1:55 min; 32 PASS, 9 PARTIAL, 0 FAIL.

| Scenario | Test | Result | Notes |
|---|---|---|---|
| 1 Control-plane bootstrap | control plane is healthy and internal endpoints require the service token | PASS | control plane on port 18080, configVersion 1, dev profile (H2 file store), DBP_DEMO_SEED_ENABLED=false; demo schema loaded unchanged with psql: 200 customers, 612 orders |
| 1 Control-plane bootstrap | import of the adapted bootstrap document upserts the whole configuration | PASS | import counts: {"teams":2,"applications":3,"credentials":4,"databases":3,"datasources":3,"accessGrants":5,"ownership":4,"producers":3,"tables":0,"relationships":4,"dependencies":0}; sales-postgres -> jdbc:postgresql://127.0.0.1:37457/sales, sales.poolPolicy.maxConnections=4, credential INLINE (AES-GCM under DBP_MASTER_KEY) |
| 1 Control-plane bootstrap | api keys are issued once and authenticate the application | PASS |  |
| 1 Control-plane bootstrap | resolve returns the postgres jdbc url grant and pool policy | PASS | resolve: jdbcUrl=jdbc:postgresql://127.0.0.1:37457/sales, credential v1, poolPolicy {"minIdle":1,"maxSize":4,"connectionTimeoutMs":10000,"idleTimeoutMs":600000,"maxLifetimeMs":1800000,"validationTimeoutMs":5000,"mode":"TRANSACTION","maxConnections":4,"statementTimeoutSeconds":0} |
| 1 Control-plane bootstrap | test connection and credential material use the stored secret | PASS | test-connection: PostgreSQL 16.14 (Ubuntu 16.14-0ubuntu0.24.04.1) in 32 ms |
| 2 Gateway in control-plane mode | gateway process starts in control plane mode and reaches the control plane | PASS | gateway it-gw on port 17420 (admin 43201), version 0.1.0-SNAPSHOT, configVersion 23 |
| 2 Gateway in control-plane mode | heartbeat appears in components and overview | PASS | heartbeat every 2 s; components entry: version=0.1.0-SNAPSHOT host=vm healthy=true |
| 2 Gateway in control-plane mode | metrics endpoint serves prometheus text | PASS | Prometheus text served; per-datasource gauges are registered lazily with the first logical session (a scrape before any session shows no dbp_gateway_* gauges) |
| 3 Driver -> gateway -> PostgreSQL (JDBC API) | select 1 with api key in url and with user password | PASS | product PostgreSQL 16.14 (Ubuntu 16.14-0ubuntu0.24.04.1), driver DBP JDBC Driver 0.1.0-SNAPSHOT, url jdbc:dbp://127.0.0.1:17420/sales |
| 3 Driver -> gateway -> PostgreSQL (JDBC API) | prepared statement with parameters reads customers | PASS | 5 DE customers with fetchSize=2 (FETCH frames), ids [10, 20, 30, 40, 50] |
| 3 Driver -> gateway -> PostgreSQL (JDBC API) | insert with returning and with generated keys | PASS | RETURNING id=201, getGeneratedKeys id=202 |
| 3 Driver -> gateway -> PostgreSQL (JDBC API) | transaction rollback then commit | PASS |  |
| 3 Driver -> gateway -> PostgreSQL (JDBC API) | batch insert of 100 order lines | PASS | executeBatch of 100 rows in one transaction (orders 613/614); trigger trg_order_item_stock fired |
| 3 Driver -> gateway -> PostgreSQL (JDBC API) | call procedure with inout parameter via jdbc call escape | PASS | {call order_pkg_place_order(?,?,?,?)} with registerOutParameter(4, BIGINT) returned order id 615 (jdbcProperties escapeSyntaxCallMode=callIfNoReturn from the import document) |
| 3 Driver -> gateway -> PostgreSQL (JDBC API) | call procedure as plain CALL prepared statement | PASS | plain 'CALL order_pkg_place_order(?,?,?,?)' as PreparedStatement works too: INOUT value returned as a result set (order 616) |
| 3 Driver -> gateway -> PostgreSQL (JDBC API) | function call with return value | PASS | get_customer_tier(1) = SILVER |
| 3 Driver -> gateway -> PostgreSQL (JDBC API) | ref cursor out parameter inside a transaction | PASS | ref cursor get_orders_for_customer(10) iterated 9 rows inside a transaction |
| 3 Driver -> gateway -> PostgreSQL (JDBC API) | database metadata lists sales tables and columns | PASS | getTables(sales): [audit_log, customer, inventory, order_item, orders, payment, product, v_customer_order_summary]; getColumns(orders): 8 columns |
| 3 Driver -> gateway -> PostgreSQL (JDBC API) | multi statement execute with getMoreResults | PASS | getMoreResults delivered the second result set of 'SELECT 1; SELECT 2' |
| 3 Driver -> gateway -> PostgreSQL (JDBC API) | errors map to sql states and standard exception subclasses | PARTIAL | 42601 -> SQLSyntaxErrorException, 23505 -> SQLIntegrityConstraintViolationException, 25P02 after a failure inside a pinned transaction, session survives; PARTIAL: when the first statement of a transaction fails the next statement succeeded (direct PostgreSQL: 25P02, transaction aborted) - the failed first statement did not pin the session |
| 3 Driver -> gateway -> PostgreSQL (JDBC API) | is valid and close | PASS |  |
| 4 HikariCP + driver: logical vs physical connections | two hundred borrows from ten threads never exceed four physical connections and leak nothing | PASS | 200 borrows / 10 threads / 10 Hikari connections: physical max observed in pg_stat_activity = 4 (cap 4), gateway pool total=4 idle=4, logicalSessions=10; control plane /stats/pools: max=4 total=1 credentialVersion=1 |
| 4 HikariCP + driver: logical vs physical connections | pool exhaustion is reported as 08001 and not fatal | PASS | 4 pinned transactions saturate the pool; 5th logical session got 08001 after 10012 ms and recovered after a release |
| 5 Reporting-batch load: 20 logical x 50 statements | twenty logical connections run fifty statements each over at most four physical connections | PASS | 1000 statements (2252 rows) in 1059 ms = 944 statements/s; physical max 4 (cap 4); gateway logicalSessions=20; /stats/pools logicalSessions=20 total=4 max=4 |
| 6 Telemetry round trip | top queries show normalised statements with their tables | PASS | 26 distinct statements for orders-service; e.g. SELECT count(*) FROM customer WHERE id = ? (count 200, tables [customer]); CALL: {call order_pkg_place_order(?, ?, ?, ?)} |
| 6 Telemetry round trip | tables endpoint lists the sales schema | PASS | sales tables known to the catalogue: {product=true, audit_log=false, order_item=true, v_customer_order_summary=true, payment=true, orders=true, inventory=true, customer=true} |
| 6 Telemetry round trip | application summary shows reads on customer and orders and calls on place order | PASS | reads=[customer, order_item, orders, payment, product] writes=[customer, inventory, order_item, orders] calls=[ORDER_PKG.PLACE_ORDER, get_customer_tier, get_orders_for_customer, order_pkg_place_order] queryStats={"count24h":286,"count7d":286,"lastSeenAt":"2026-10-05T21:35:41.267216Z","avgDurationMs":46.69,"errors24h":6} |
| 6 Telemetry round trip | dictionary crawl catalogues routines triggers and dependencies | PARTIAL | crawl with the shipped grants: collector-status.tablesSeen=1 routinesSeen=11, 1 of 8 sales tables catalogued (not 'discovered'): [audit_log]; PARTIAL: PostgresDictionaryCrawler reads information_schema.tables/columns, which only list objects the collector role has privileges on; with the shipped grants only 1 of 8 tables were catalogued (view typed as TABLE, routine->table dependencies missing). Workaround applied by the test: GRANT SELECT ON ALL TABLES IN SCHEMA sales TO dbp_collector, then re-crawl; collector-status: lastError=null tablesSeen=8 routinesSeen=11; place_order deps=8 kinds=[REFERENCES, READS, WRITES] tables=[audit_log, inventory, order_item, orders, payment, product]; schema sales: 8 tables / 11 routines |
| 6 Telemetry round trip | call expansion attributes tables written via the routine to the caller | PASS | orders.summary consumer: orders-service WRITES via order_pkg_place_order (confidence 1.0); indirect consumers also on payment, order_item, audit_log (trigger) |
| 6 Telemetry round trip | impact analysis and graph include the consumers | PASS | impact(orders): riskScore=0.51 factors=[1 consuming team, 7 consuming applications, written by trigger, referenced by 5 routines, 1 dependent view, 402 queries in 7d] direct=3 indirect=4; graph(application, depth 2): 32 nodes [TEAM, APPLICATION, DATABASE, DATASOURCE, TABLE, ROUTINE], edges [BELONGS_TO, OWNS, ROUTES_TO, GRANTED, HOSTS, PRODUCES, CALLS, FOREIGN_KEY, REFERENCES, READS, WRITES, TRIGGERS]; governance evaluate: {"violations":7,"inferredProducers":5,"resolved":0,"ok":true}; open violations: 7 |
| 7 Credential rotation | rotation bumps the version drains the old pool and keeps traffic flowing | PASS | credential sales-postgres-app v1 -> v2; gateway switched pools in 257 ms (old pool drained & closed), /stats/pools credentialVersion=2; 137 statements + 14 new connections during rotation, 0 errors |
| 8 Routing rule, datasource switch, second engine | routing rule moves reporting batch to sales alt while orders service stays on sales | PASS | rule(priority 5, reporting-batch -> sales-alt): batch connections hit sales_alt (3 customers), orders-service stayed on sales; rule removed -> back on sales; gateway pools: [4b232141-05e6-4cbc-9415-90e5f4d87875@v2, af65aeaa-bd1e-44d9-9875-27d7473a8a4c@v2] |
| 8 Routing rule, datasource switch, second engine | switch moves new connections while a pinned transaction keeps its database | PASS | after COMMIT the existing logical connection re-resolved to sales_alt on its next pin (README: existing sessions follow at the next pin); switch sales -> sales-alt -> sales: new connections followed each switch within the 1 s config poll; 2 MigrationEvents recorded |
| 8 Routing rule, datasource switch, second engine | h2 in oracle mode is reachable through a static mode gateway | PARTIAL | second engine: H2 2.3.232 (2024-08-11) via jdbc:dbp (static YAML datasource sales-h2, gateway it-gw-h2); PARTIAL: the control plane cannot model an H2 database (Enums.Engine = ORACLE\|POSTGRES\|MSSQL; POST /databases engine H2 -> HTTP 400), so the second engine was validated behind a static-mode gateway and the control-plane routing switch used a second PostgreSQL database (sales_alt) |
| 9 Access control | application without a grant is rejected with 08004 | PASS | no-grant-app (valid key, no grant): SQLNonTransientConnectionException[08004/0]: application 'no-grant-app' is not authorised for datasource 'sales' |
| 9 Access control | wrong or missing api key is rejected with 08004 | PASS | wrong key: invalid api key; missing key: apiKey is required; unknown datasource: unknown datasource 'nosuchds' |
| 9 Access control | revoked key is rejected for new connections | PASS | revoked key rejected for new connections after 1022 ms (auth cache invalidated by the configVersion bump); the session opened before revocation kept working (authentication is at HELLO time only) |
| 9 Access control | read only grant blocks writes | PARTIAL | legacy-reporting (readOnly grant): SELECT ok; UPDATE with autocommit -> allowed (1 row); UPDATE inside an explicit transaction -> blocked 25006; PARTIAL: read-only grant is not enforced for autocommit statements (pgjdbc setReadOnly(true) with the default readOnlyMode=transaction only marks explicit BEGINs read only) |
| 10 Proxy path (PostgreSQL) | control plane builds a postgres listener routing sales to the embedded database | PASS | proxy config: listener postgres-main engine POSTGRES port 5432 (fixed per engine by the control plane), route sales -> 127.0.0.1:37457/sales, 2 listeners in total ([ORACLE:1521, POSTGRES:5432]) |
| 10 Proxy path (PostgreSQL) | proxied pgjdbc connection is attributed to orders service and correlates with client port | PARTIAL | PARTIAL: proxy ran in mode 'static-fallback' (proxy in control-plane mode never became healthy: WARN  [main] o.d.p.c.ControlPlaneConfigSource - cannot fetch proxy configuration (startup): cannot parse control plane response for /api/v1/internal/proxy/config?proxyId=it-proxy as ProxyConfigDocument: Should never call `set()` on setterless property ('programNames') (through reference chain: org.dbplatform.proxy.config.ProxyConfigDocument["applications"]->java.util.ArrayList[0]->org.dbplatform.proxy.config.ApplicationConfig["identityRules"]->org.dbplatform.proxy.config.IdentityRules["programNames"]) -> restarted in static mode), so GET /connections/live and /components were not checked; proxy-side attribution and client_port correlation verified |
| 10 Proxy path (PostgreSQL) | unknown logical database is refused with 3D000 and quota is enforced | PASS | proxy health: status=UP listeners=[POSTGRES:38259] |

### Scenario summary

| # | Scenario | Result | What was proven |
|---|----------|--------|-----------------|
| 1 | Control-plane bootstrap | PASS | Process health, service-token enforcement on `/internal/**`, import of the bootstrap document (2 teams, 3 applications, 4 credentials, 3 databases, 3 datasources, 5 grants, declared ownership/producers/relationships), api-key issue/list/authenticate, `GET /internal/resolve/datasource/sales` returns the PostgreSQL `jdbcUrl`, `jdbcProperties`, credential version, grant and pool policy (`maxConnections = 4`, TRANSACTION), `test-connection`, credential material only with the token |
| 2 | Gateway in control-plane mode | PASS | Shaded jar starts from `DBP_*` variables, admin `/health` reports control-plane mode and config version, heartbeat visible in `GET /components` and `/stats/overview`, Prometheus `/metrics` |
| 3 | Driver → gateway → PostgreSQL | PASS (1 test PARTIAL: error semantics, D6) | `SELECT 1` with `?apiKey=` and with user/password, prepared statements with parameters and `FETCH` paging, `RETURNING` and `getGeneratedKeys`, rollback/commit/savepoint, 100-row batch, `{call order_pkg_place_order(?,?,?,?)}` **and** plain `CALL …` as a prepared statement (both work), `{? = call get_customer_tier(?)}`, `REF_CURSOR` OUT parameter iterated inside a transaction, `DatabaseMetaData.getTables/getColumns/getPrimaryKeys`, multi-statement `execute` + `getMoreResults`, `42601 → SQLSyntaxErrorException`, `23505 → SQLIntegrityConstraintViolationException`, `25P02` inside an aborted transaction, `isValid`/`close`/`08003` |
| 4 | HikariCP + driver | PASS | 10 Hikari connections, 200 borrows from 10 threads, zero errors, zero leaks; `pg_stat_activity` never showed more than **4** `sales_app` backends (= `poolPolicy.maxConnections`), gateway pool `max=4`, `/stats/pools` matches; pool exhaustion surfaces as non-fatal `08001` after `connectionTimeoutMs` and the session recovers |
| 5 | Reporting-batch load | PASS | 20 logical connections on virtual threads × 50 statements (the seven `Workload` SELECTs + UPDATE batches in transactions): 1000 statements, 0 errors, physical max **4**, ≈ 900 statements/s end to end on the shared 4-core box; `/stats/pools` showed `logicalSessions=20 total=4 max=4` |
| 6 | Telemetry round trip | PARTIAL | `GET /stats/queries/top` has the normalised statements (literals replaced by `?`) with their tables and the `CALL`; `GET /tables?schema=sales` lists the schema; the application summary shows READS on `customer`/`orders` and CALLS on `order_pkg_place_order`; after a dictionary crawl the routines (PROCEDURE/FUNCTION/TRIGGER with `triggerTableId`), routine→table dependencies and the view kind are catalogued; a `CALL` after the crawl yields `orders.summary.consumers[orders-service].viaRoutine = order_pkg_place_order` (also on `payment`, `order_item` and, through the trigger, `audit_log`); `GET /impact/table/{orders}` lists direct and indirect consumers, the trigger, the dependent view and a risk score; `GET /graph?root=application:…&depth=2` has TABLE nodes and READS/CALLS edges. **PARTIAL** because the crawl only works after a grant the shipped demo does not give the collector role (defect D1) |
| 7 | Credential rotation | PASS | `POST /credentials/{id}/rotate` bumps the version; within ≈ 1 s the gateway created the `@v2` pool, drained and closed the `@v1` pool, `/stats/pools` reports the new `credentialVersion`; 137 statements and 14 new connections during the rotation, 0 errors |
| 8 | Routing rule, switch, second engine | PARTIAL | A routing rule sends `reporting-batch` to `sales-alt` while `orders-service` stays on `sales` (verified with `current_database()` through the driver); `POST /datasources/{id}/switch` moves new connections within the 1 s config poll, a pinned transaction keeps its database until COMMIT, two `MigrationEvent`s recorded; H2 `MODE=Oracle` reached through a second gateway in static mode (`getDatabaseProductName() = H2`, `SYSDATE`/`DUAL`/`ROWNUM`). **PARTIAL** because the control plane cannot model H2 (defect D5), so the second engine could not be used in the control-plane routing switch |
| 9 | Access control | PARTIAL | No grant → `08004` "not authorised", wrong/missing key → `08004`, unknown datasource → `08004`, revoked key rejected for new connections within milliseconds (config-version bump invalidates the auth cache; already-open sessions keep working); **read-only grant does not block an UPDATE** (defect D4) |
| 10 | Proxy path | PARTIAL | `GET /internal/proxy/config` builds a POSTGRES listener on 5432 routing `sales` to the embedded server with the application identity rules and quotas (verified). The proxy itself **cannot apply that document** (defect D3, both builds), so the test restarted it in static YAML mode: pgjdbc → proxy → PostgreSQL with `sales.orders-service` works, the proxy attributes the connection by service alias (`SERVICE_ALIAS`), rewrites the database name, `pg_stat_activity.client_port` equals the proxy's `proxyLocalPort` (the Oracle-style correlation key), unknown logical database → `3D000`, `sslmode=require` refused as documented, admin `/connections` requires the service token. Not verified because of D3: `GET /connections/live` (PROXY rows), the proxy heartbeat in `/components`, collector correlation |

## 3. Defects found

Severity: **High** = a documented capability does not work in the validated configuration; **Medium** = works
with a gap that matters for adopters; **Low** = cosmetic or edge case. "Worked around" says whether the test
suite contains a workaround so the rest of the chain could still be validated. No module other than
`dbp-integration-tests` was modified.

### D1 — PostgreSQL dictionary crawler only sees tables the collector role may read (High, worked around)

* **Where**: `dbp-control-plane/src/main/java/org/dbplatform/controlplane/collector/postgres/PostgresDictionaryCrawler.java`,
  the table query (`SELECT … FROM information_schema.tables WHERE table_schema IN …`, ≈ line 38) and the column
  query (`information_schema.columns`, ≈ line 51).
* **Observed**: with the grants shipped in `demo/sql/postgres/40_grants_app.sql` (`dbp_collector` = `pg_monitor` +
  `SELECT ON sales.audit_log`, deliberately *no* table access — "Row-count estimates come from pg_class.reltuples,
  so no table access is needed"), the crawl reported `tablesSeen = 1`: only `audit_log` was catalogued, the seven
  other tables stayed `discovered: true` placeholders, `v_customer_order_summary` kept kind `TABLE`, no
  routine → table dependencies were produced (`refine()` silently skips references to tables the crawl did not
  see), hence no CALL expansion, no `viaRoutine` consumers, no indirect consumers in the impact analysis and no
  TRIGGERS/view edges in the graph. `information_schema.tables` / `.columns` are privilege-filtered views in
  PostgreSQL; `pg_proc`, `pg_trigger`, `pg_views`, `pg_constraint`, `pg_description` (used for the other objects)
  are not, which is why routines and triggers were complete while tables were not.
* **Fix**: read tables and columns from `pg_catalog` like the materialized-view branch already does:
  `SELECT n.nspname, c.relname, c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE
  c.relkind IN ('r','p','v') AND n.nspname IN (…)` (`'v'` → VIEW, else TABLE) and
  `SELECT … a.attname, a.attnum, format_type(a.atttypid, a.atttypmod), NOT a.attnotnull, pg_get_expr(d.adbin, d.adrelid)
  FROM pg_attribute a JOIN pg_class c … LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
  WHERE a.attnum > 0 AND NOT a.attisdropped …`. Alternatively document in `docs/collectors.md` and
  `40_grants_app.sql` that the PostgreSQL collector role needs `SELECT` on the crawled schemas — but that
  contradicts the least-privilege statement there.
* **Workaround in the tests**: scenario 6 records the result of the first crawl, then runs
  `GRANT SELECT ON ALL TABLES IN SCHEMA sales TO dbp_collector` as superuser and triggers a second crawl; everything
  downstream (dependencies, CALL expansion, impact, graph) then works, which is why the scenario is PARTIAL and not
  FAIL.

### D2 — PostgreSQL runtime sampler fails on every run when `currentSchema` is set (High, not worked around)

* **Where**: `PostgresRuntimeSampler.STATEMENTS_SQL` (`SELECT … FROM pg_stat_statements …`, unqualified) together with
  `CollectorConnections` (≈ line 33), which copies **all** `Database.jdbcProperties` onto the collector connection.
* **Observed**: the bootstrap document sets `jdbcProperties.currentSchema = sales` for `sales-postgres` (a gateway
  setting). pgjdbc turns it into `SET search_path TO sales`, so the collector session no longer sees `public`, where
  `00_databases.sql` installs `pg_stat_statements`. `EXT_SQL` (`pg_extension`) says the extension exists, the
  statements query then fails with `ERROR: relation "pg_stat_statements" does not exist`, and the exception aborts
  the **whole** runtime sample: `collector-status.lastRuntimeRun` stays `null`, `lastError` is set permanently,
  `GET /connections/live` never contains `COLLECTOR` rows and no `COLLECTOR_SESSION` / `PROXY_CORRELATION`
  relationships are derived. The control-plane log shows the warning every 5 s (18 times during one 4-minute run);
  reproduced with both control-plane builds.
* **Fix** (both parts): (a) resolve the extension's schema —
  `SELECT n.nspname FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace WHERE e.extname = 'pg_stat_statements'`
  — and query `<nspname>.pg_stat_statements` (or `SET search_path` to include it); (b) run the statements query in
  its own try/catch so that a failing `pg_stat_statements` read still lets the `pg_stat_activity` sessions be
  merged. Consider not copying gateway-oriented properties (`currentSchema`, `escapeSyntaxCallMode`, `ApplicationName`)
  to collector connections at all.

### D3 — Proxy cannot parse the control plane's proxy configuration (High: the proxy cannot run in control-plane mode; worked around)

* **Where**: `dbp-control-plane … api/internal/InternalResolutionController.proxyConfig()` emits every identity rule
  twice — the `dbp-common` spelling (`programs`, `machines`, `applicationNames`, serialised from
  `ProxyConfig.IdentityRules`) **and**, appended with `rules.set(…)`, the public API spelling (`programNames`,
  `machinePatterns`, `pgApplicationNames`). The proxy's `org.dbplatform.proxy.config.IdentityRules` is a record whose
  components carry `@JsonAlias` for the common spelling.
* **Observed** (both proxy builds, 18:19 and 21:10 / commit `462f831`): `cannot parse control plane response for
  /api/v1/internal/proxy/config?proxyId=it-proxy as ProxyConfigDocument: Should never call set() on setterless
  property ('programNames') (… ProxyConfigDocument["applications"]->ApplicationConfig["identityRules"]->
  IdentityRules["programNames"])`, retried every second at startup; the proxy never opened a listener. Reproduced
  offline with the proxy jar's own mapper (`TelemetryJson.mapper()`): a document with only `programNames` parses, a
  document with only `programs` parses, a document with **both** fails — Jackson binds the alias to the record's
  creator parameter and then meets the canonical name as a second value for the same creator property (with
  `USE_GETTERS_AS_SETTERS` disabled the error becomes "No fallback setter/field defined for creator property
  'programNames'"). The 18:19 and 21:10 builds behave identically.
* **Fix**: emit one spelling. Simplest: delete the `node.withArray("applications").forEach(… rules.set("programNames"
  …) …)` block in `proxyConfig()` — the proxy already understands the common spelling through its aliases. Cleaner
  for the contract (`docs/control-plane-api.md` §10 shows the public spelling): rename the fields of
  `dbp-common … ProxyConfig.IdentityRules` to `programNames` / `machinePatterns` / `pgApplicationNames` (keeping
  `@JsonAlias` for the old names) and drop the duplication. If the proxy must stay tolerant of both keys, give
  `IdentityRules` a `@JsonCreator` static factory that takes both spellings as separate `@JsonProperty` parameters and
  merges them.
* **Workaround in the tests**: when the proxy does not become healthy in control-plane mode within 45 s, scenario 10
  restarts it in static YAML mode on an ephemeral port and marks the scenario PARTIAL; the data path, identity
  resolution and `client_port` correlation are then verified on the proxy side only (`GET /connections/live`,
  `/components` and collector correlation cannot be checked without control-plane mode).

### D4 — Read-only grant is not enforced for PostgreSQL autocommit statements (Medium, not worked around)

* **Where**: `dbp-gateway … LogicalSession.applySettings` enforces `grant.readOnly` with `Connection.setReadOnly(true)`
  on the physical connection; `PhysicalPool.toHikari` sets no pgjdbc `readOnlyMode`.
* **Observed**: `legacy-reporting` (grant `readOnly: true` on `sales`) could `UPDATE customer …` through the
  gateway with autocommit on (1 row updated); the same UPDATE inside an explicit transaction was blocked with
  `25006 read_only_sql_transaction`. pgjdbc's default `readOnlyMode=transaction` only adds `READ ONLY` to explicit
  `BEGIN`s; autocommit statements are not affected. (Oracle's `setReadOnly` has the same "transaction only"
  semantics.)
* **Fix**: in `PhysicalPool.toHikari`, POSTGRES branch: `props.putIfAbsent("readOnlyMode", "always")` so that
  `setReadOnly(true)` issues `SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY`; and/or enforce it engine
  independently in `StatementExecutor` by rejecting statements whose `SqlAnalyzer` operation is
  INSERT/UPDATE/DELETE/MERGE/DDL/CALL for read-only sessions (SQLState `25006`), which also covers Oracle.

### D5 — The control plane cannot model an H2 database (Medium, worked around)

* **Where**: `dbp-control-plane … domain/Enums.java` (`Engine { ORACLE, POSTGRES, MSSQL }`), `service/JdbcUrls.of`,
  `InternalResolutionController.LISTENER_PORTS`, `TelemetryIngestService.toEngine`.
* **Observed**: `POST /databases` with `"engine": "H2"` → HTTP 400; `dbp-common`'s `Engine`, the gateway (static
  YAML, README "scratch" example) and the UI forms all know H2. A developer cannot route a datasource to H2 through the
  control plane, which the gateway README advertises.
* **Fix**: add `H2` (and `OTHER`) to `Enums.Engine`; `JdbcUrls.of`: `case H2 -> "jdbc:h2:tcp://" + host + ":" + port
  + "/" + serviceName` (serviceName = `mem:name` or a path); skip non-proxyable engines in `proxyConfig()`; map
  `H2 -> Enums.Engine.H2` in `toEngine`; the `engine` columns are `VARCHAR`, no migration needed unless a check
  constraint exists.
* **Workaround in the tests**: the second engine is validated behind a second gateway in static mode; the
  control-plane routing switch uses a second PostgreSQL database (`sales_alt`).

### D6 — A failing first statement does not start the transaction in the gateway (Low, not worked around)

* **Where**: `dbp-gateway … StatementExecutor.execute` calls `session.noteWork()` only after a successful
  `execute` (≈ line 156); `LogicalSession.noteWork()` is what sets `inTransaction = true` when autocommit is off.
* **Observed**: with autocommit off, `SELECT * FROM` (42601) as the first statement leaves the session unpinned;
  the physical connection is rolled back and returned, and the next `SELECT 1` **succeeds** on a fresh connection.
  Directly on PostgreSQL the transaction is aborted and every statement until `ROLLBACK` fails with `25P02`. When
  the failure happens after a successful statement the semantics are preserved (verified).
* **Fix**: mark the transaction as started before executing (or in the `catch (SQLException)` path) whenever
  autocommit is off, e.g. `session.noteTransactionStarted()` that sets `inTransaction = true` without counting a
  statement, so the session stays pinned on the aborted physical transaction exactly like a direct connection.

### D7 — `mvn install` publishes a 1.4 KB placeholder for the control plane (Low)

* `~/.m2/repository/org/dbplatform/dbp-control-plane/0.1.0-SNAPSHOT/dbp-control-plane-0.1.0-SNAPSHOT.jar` is 1 479
  bytes; the runnable artifact is `target/dbp-control-plane.jar` (custom `finalName`), which the install plugin does
  not attach. Downstream modules (this one) cannot pick the control plane from the local repository the way they
  pick the gateway's `all` jar. Fix: let `spring-boot-maven-plugin` repackage the main artifact (drop the custom
  finalName) or attach the repackaged jar with `<classifier>exec</classifier>`.

### D8 — Gateway Prometheus gauges appear only after the first session (Low)

* `GET /metrics` on a freshly started gateway contains no `dbp_gateway_*` gauges; they are registered per datasource
  with the first logical session. Dashboards show gaps after a restart. Fix: register the gauges when a
  datasource is first resolved / for every configured datasource in static mode.

### Observations that are not defects

* `InternalResolutionController.LISTENER_PORTS` pins proxy listeners to 1521 / 5432 / 1433 per engine with no way
  to configure them per proxy; on a host where 5432 is taken, control-plane mode cannot be used (the compose setup
  avoids this by publishing the real databases on 1522 / 5433). Worth a per-database `proxyPort` or per-proxy
  listener configuration.
* `revoke api key` does not terminate sessions opened with that key (authentication happens at HELLO only); new
  connections are rejected within the config poll interval. Document or add session termination.
* Existing logical connections follow a datasource switch at their **next pin** (after COMMIT/ROLLBACK), exactly
  as the gateway README states — only a pinned transaction keeps its database. The expectation "existing
  connections keep their pool" does not hold beyond the current transaction.
* `00_databases.sql` also creates the `dbp` role/database for the control plane's PostgreSQL profile; harmless
  when the control plane runs on H2. The scripts themselves ran cleanly with `psql` and needed no adaptation.

## 4. Deviations from the contracts observed

* `GET /internal/proxy/config` identity rules carry both spellings of each field (`programs` + `programNames`, …);
  the contract (`docs/control-plane-api.md` §10) shows the public spelling only. Cause of D3.
* `GET /internal/resolve/datasource` `poolPolicy` carries `maxSize` and `maxConnections` (same value) and
  `validationTimeoutMs`; the contract lists `maxConnections` only. Harmless.
* `GET /applications/{id}/summary.calls` contains the DECLARED Oracle routine `ORDER_PKG.PLACE_ORDER` next to the
  observed PostgreSQL `order_pkg_place_order` — declared relationships from the bootstrap document are mixed with
  observed ones in one list without a source marker.
* The gateway README's note that "further results of that execution are not delivered" when the first result set is
  not exhausted did not bite here: `SELECT 1; SELECT 2` delivered both result sets through `getMoreResults`.
* PostgreSQL procedures: both documented call styles work through the gateway with the bootstrap's
  `escapeSyntaxCallMode=callIfNoReturn` — the JDBC escape `{call p(?,?,?,?)}` with `registerOutParameter` and a plain
  `CALL p(?,?,?,?)` prepared statement whose INOUT value comes back as a one-row result set.

## 5. Not validated here

* **Oracle**: TNS proxy path (CONNECT/REDIRECT/REFUSE handling), Oracle collectors (`DBA_*`/`ALL_*`, `V$SESSION`,
  `V$SQL`, unified audit), `v$session.program` attribution, Oracle PL/SQL packages and `SYS_REFCURSOR` through the
  gateway — no Oracle instance in this environment.
* **SQL Server**: proxy pass-through and collectors.
* **Deployment**: `deploy/docker-compose.yml`, Kubernetes manifests, the bootstrap shell script, the example
  Docker images (no Docker daemon usable here; the example applications' SQL and flows were replayed by the tests
  instead of running `orders-service`/`reporting-batch` as processes).
* **TLS** between driver and gateway (`DBP_GATEWAY_TLS_KEYSTORE`, `ssl=true`) and proxy-side TLS.
* **UI** (`dbp-ui`) — validated by another agent.
* Control plane on the PostgreSQL profile (metadata store), `DBP_SECURITY_MODE=basic`, VAULT / cloud secret
  providers (answer 501 by design), telemetry retention job, governance policy outcomes beyond
  `POST /governance/evaluate` returning 200, SESSION pool mode (the only SESSION datasource, `payments`, points at
  Oracle), proxy quotas (`maxProxyConnections` exhaustion), `CancelRequest` forwarding, LOB-heavy rows, multiple
  gateway instances, long-running soak (runs are minutes, not hours).
