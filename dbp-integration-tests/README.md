# dbp-integration-tests

End-to-end validation of the Database Access Platform **as deployed**: real processes, only public interfaces.

```
            pgjdbc ──► dbp-proxy (shaded jar, control-plane mode) ──► PostgreSQL 16 (system binaries, scram auth)
                                                                        ▲
 JUnit 5 ── dbp-jdbc driver ──► dbp-gateway (shaded jar, control-plane mode, port 17420) ──┘
   │                                   │ heartbeats, telemetry, resolve, credential material
   └── REST (java.net.http) ──► dbp-control-plane (Spring Boot jar, dev profile, H2 file store, port 18080)

 JUnit 5 ── dbp-jdbc driver ──► second dbp-gateway (static YAML) ──► H2 TCP server (MODE=Oracle) in the test JVM
```

The demo schema is loaded **unchanged** from `demo/sql/postgres/*.sql` with `psql` (the `\connect` meta-commands
work as written), the control plane is configured by importing `deploy/bootstrap/platform-config.json` with
`sales-postgres` re-pointed at the embedded server (see `target/it/platform-config.it.json` after a run).

## Run

```
mvn -f dbp-integration-tests/pom.xml verify
```

The module is not (yet) part of the root reactor; it only needs the sibling artifacts in the local repository
(`mvn -q install -DskipTests` at the root once, or at least `dbp-protocol`, `dbp-common`, `dbp-jdbc`, `dbp-gateway`)
and the two executable jars:

| Component     | Default location                                            | Override                         |
|---------------|-------------------------------------------------------------|----------------------------------|
| control plane | `../dbp-control-plane/target/dbp-control-plane.jar`          | `-Ddbp.it.controlPlaneJar=/path` |
| proxy         | `../dbp-proxy/target/dbp-proxy-<ver>-all.jar`                | `-Ddbp.it.proxyJar=/path`        |
| gateway       | `org.dbplatform:dbp-gateway:<ver>:all` from `~/.m2` (copied to `target/it/jars`) | `-Ddbp.it.gatewayJar=/path` |

When the proxy jar is missing, scenario 10 is skipped (JUnit assumption), everything else runs.

Prerequisites:

* Java 21, Maven 3.9.
* **PostgreSQL server binaries** (`initdb`, `pg_ctl`, `postgres`, `psql`): `/usr/lib/postgresql/<ver>/bin` is found
  automatically, anything else through `DBP_TEST_PG_BIN=<bin dir>`. PostgreSQL refuses to run as root, so under
  root the server is started through `runuser -u postgres` (or `nobody`) like `dbp-gateway`'s `EmbeddedPg` test
  helper; as an ordinary user the binaries are called directly. zonky embedded-postgres is deliberately not used
  (it cannot run as root in this container and would add 100 MB of binaries to the build).
* Free ports: **18080** (control plane) and **17420** (gateway) are preferred and fall back to ephemeral ports
  when busy (recorded in the results); **5432** must be free for the proxy's control-plane mode (the control
  plane pins PostgreSQL listeners to 5432); when it is busy the proxy runs in static mode on an ephemeral port
  and scenario 10 is marked PARTIAL. PostgreSQL, the admin ports and the H2 ports are always ephemeral.
* No Docker.

Useful switches: `-Dit.test=S03DriverIT` runs one scenario class (the stack starts anyway and the required
steps — import, gateway — are done lazily); `-DskipITs` skips everything.

## What is in here

| File | Purpose |
|------|---------|
| `support/Stack` | Starts PostgreSQL, loads the demo schema, starts the control plane; lazily imports the configuration, issues api keys, starts the gateway / proxy / H2 side stack; stops **everything** when the run ends (JUnit root-store `CloseableResource` + JVM shutdown hook, also on failure) and kills leftover children |
| `support/EmbeddedPg` | System-binaries PostgreSQL (`initdb -A scram-sha-256`, `pg_ctl`, `psql`), `runuser` when root, `pg_stat_statements` preloaded when available |
| `support/ManagedProcess` | Child process with stdout/stderr captured into `target/it/logs/<name>.log`, started under `nice` |
| `support/ControlPlaneApi` | Tiny JSON client for `/api/v1` (public) and `/api/v1/internal` (service token) |
| `support/BackendSampler` | Polls `pg_stat_activity` every 50 ms and keeps the maximum number of `sales_app` backends |
| `support/Results`, `ItExtension` | One row per test (PASS / FAIL / PARTIAL / SKIPPED + notes) written to `target/it/results.md` and printed at the end |
| `S01…S10*IT` | The ten scenarios, ordered (`@Order`), each test with explicit assertions |

Scenarios (see `docs/validation-report.md` for the results):

1. control-plane bootstrap (health, import, api keys, `/internal/resolve`, test-connection, credential material)
2. gateway in control-plane mode (admin health, heartbeat in `/components`, metrics)
3. driver → gateway → PostgreSQL through the public JDBC API (prepared statements, RETURNING / generated keys,
   transactions, batches, `CALL` with INOUT, function call, `REF_CURSOR`, metadata, multi-statements, SQLStates)
4. HikariCP + driver: 200 borrows / 10 threads / 10 logical connections never exceed 4 physical connections
5. reporting-batch load: 20 virtual-thread connections × 50 statements, physical ≤ 4, statements/s recorded
6. telemetry round trip: top queries, catalogue, application summary, dictionary crawl, CALL expansion
   (`viaRoutine`), impact analysis, graph
7. credential rotation: version bump, old pool drained, traffic keeps flowing
8. routing rule and datasource switch (second PostgreSQL database), H2 `MODE=Oracle` behind a static-mode gateway
9. access control: no grant / wrong key / revoked key → `08004`, read-only grant → `25006`
10. proxy path: pgjdbc → proxy → PostgreSQL, attribution by service alias, `client_port` correlation,
    `GET /connections/live`, `3D000` for unknown logical databases

## Where to look when something fails

* `target/it/logs/control-plane.log`, `gateway.log`, `proxy.log`, `gateway-h2.log`, `postgres.log`
* `target/it/results.md` — the per-test table, also printed at the end of the run
* `target/failsafe-reports/` — the usual XML/TXT reports
