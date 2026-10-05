# Example applications

Three applications that use the retail demo schema (`demo/`) through the three access paths of the
platform. They are built as part of the reactor and packaged into images by `deploy/docker-compose.yml`.

| Module              | Kind                    | Access path(s)                     | Shows |
|---------------------|-------------------------|------------------------------------|-------|
| `orders-service`    | Spring Boot 3 REST      | direct, proxy, gateway, postgres-direct | *same code, four modes*; OUT parameters, function calls, ref cursors through the gateway |
| `legacy-reporting`  | Spring Boot 3 REST      | proxy only                         | an Oracle-only legacy app onboarded by changing nothing but its JDBC URL |
| `reporting-batch`   | plain Java 21 `main`    | direct, proxy, gateway             | many logical connections → few physical connections through the gateway |

## Build

```bash
# whole reactor (recommended, bundles dbp-jdbc into the examples)
mvn -q package -DskipTests

# examples only, before dbp-jdbc has been built/installed
mvn -q -f dbp-examples/pom.xml package -DskipTests -Ddbp.jdbc.skip      # or -P!with-dbp-jdbc
```

`mvn package` runs the unit tests of each module (`SqlDialectTest`, `BatchConfigTest`,
`LegacyQueriesTest`); none of them needs a database.

Artifacts:

* `orders-service/target/dbp-examples-orders-service-<ver>.jar` (Spring Boot executable)
* `legacy-reporting/target/dbp-examples-legacy-reporting-<ver>.jar` (Spring Boot executable)
* `reporting-batch/target/dbp-examples-reporting-batch-<ver>-all.jar` (shaded, `java -jar`)

Each module has a `Dockerfile` whose build context is the **repository root**
(`docker build -f dbp-examples/orders-service/Dockerfile .`); compose does this for you.

## orders-service

REST API (port 8080 in the container; 8091/8092/8093 on the host for the three compose variants):

| Endpoint | SQL | Exercises |
|----------|-----|-----------|
| `GET /orders?limit=20` | `SELECT … FETCH FIRST ? ROWS ONLY` via `JdbcTemplate` | plain prepared statements, result sets |
| `GET /orders/{id}` | point lookup | – |
| `POST /orders` `{"customerId":1,"productId":2,"qty":3}` | `{call ORDER_PKG.PLACE_ORDER(?,?,?,?)}` | `CallableStatement` with an OUT parameter; the routine fans out into ORDERS, ORDER_ITEM, PAYMENT, INVENTORY and (by trigger) AUDIT_LOG |
| `GET /customers/{id}/tier` | `{? = call GET_CUSTOMER_TIER(?)}` | function call with return value |
| `GET /customers/{id}/orders` | `{call GET_ORDERS_FOR_CUSTOMER(?, ?)}` | `SYS_REFCURSOR` OUT parameter (`"source": "REF_CURSOR"`; falls back to the equivalent query with `"source": "QUERY_FALLBACK"` when the driver in use cannot return a cursor) |
| `GET /health` | `SELECT 1 FROM DUAL` / `SELECT 1` | mode, engine, masked URL and HikariCP pool numbers |
| `GET /actuator/prometheus` | – | HikariCP metrics (`hikaricp_connections_active` etc.) for Grafana |

The mode is selected **only** by `SPRING_PROFILES_ACTIVE`; the Java code is identical:

| Profile | Driver | URL | Credentials | Attribution |
|---------|--------|-----|-------------|-------------|
| `direct` | Oracle thin | `jdbc:oracle:thin:@//oracle:1521/FREEPDB1` | `SALES_APP` / `SALES_APP_PASSWORD` | `v$session.program=orders-service` (collector, identity rule `programNames`) |
| `proxy` | Oracle thin | `jdbc:oracle:thin:@//proxy:1521/sales.orders-service` | same | service alias `orders-service` (proxy), `V$SESSION.PORT` correlation |
| `gateway` | `org.dbplatform.jdbc.DbpDriver` | `jdbc:dbp://gateway:7420/sales` | user `orders-service`, password = API key (`DBP_API_KEY`) | API key (gateway) |
| `postgres-direct` | PostgreSQL | `jdbc:postgresql://postgres:5432/sales?ApplicationName=orders-service&escapeSyntaxCallMode=callIfNoReturn` | `sales_app` | `pg_stat_activity.application_name` |

Engine specific SQL (package prefix, OUT-parameter types, ref-cursor position, health query) is
isolated in `SqlDialect`, keyed by `DBP_DEMO_ENGINE` (`ORACLE` default; the `postgres-direct`
profile sets `POSTGRES`). When the `sales` datasource is switched to PostgreSQL in the control plane,
run the gateway variant with `DBP_DEMO_ENGINE=POSTGRES` and nothing else changes.

Environment variables: `SPRING_PROFILES_ACTIVE`, `SALES_APP_PASSWORD`, `DBP_API_KEY`
(or `DBP_API_KEY_FILE` + `DBP_API_KEY_VAR`, see `docker-entrypoint.sh`), `ORACLE_HOST`,
`PROXY_HOST`, `DBP_GATEWAY_HOST`, `POSTGRES_HOST`, `ORDERS_POOL_SIZE` (default 10), `SERVER_PORT`.

Run locally against the compose stack:

```bash
SPRING_PROFILES_ACTIVE=direct ORACLE_HOST=localhost ORACLE_PORT=1522 SALES_APP_PASSWORD=... \
  java -jar dbp-examples/orders-service/target/dbp-examples-orders-service-0.1.0-SNAPSHOT.jar
SPRING_PROFILES_ACTIVE=proxy   PROXY_HOST=localhost       java -jar ...   # proxy owns host port 1521
SPRING_PROFILES_ACTIVE=gateway DBP_GATEWAY_HOST=localhost DBP_API_KEY=dbp_... java -jar ...
```

## legacy-reporting

Oracle thin only, hand-written SQL with `(+)` outer joins, `NVL`, `SYSDATE`, `TO_CHAR`, `ROWNUM`
(`LegacyQueries`). Before the platform it used `jdbc:oracle:thin:@//oracle:1521/FREEPDB1`; now it uses
`jdbc:oracle:thin:@//proxy:1521/sales.legacy-reporting` and sets `v$session.program=legacy-reporting`.
Nothing else changed.

| Endpoint | SQL |
|----------|-----|
| `GET /report/customers?country=DE` | CUSTOMER ⟕ ORDERS with `(+)`, `NVL`, `TRUNC(SYSDATE)` |
| `GET /report/revenue?months=6` | PAYMENT ⋈ ORDERS, `ADD_MONTHS(TRUNC(SYSDATE,'MM'), -n)`, `TO_CHAR` |
| `GET /report/open-orders?limit=25` | ORDERS ⟕ PAYMENT with `(+)`, `ROWNUM` |
| `GET /health` | `SELECT SYSDATE FROM DUAL` |

Host port in compose: 8094.

## reporting-batch

`java -jar dbp-examples-reporting-batch-<ver>-all.jar` with environment variables:

| Variable | Default | Meaning |
|----------|---------|---------|
| `DBP_JDBC_URL` | – (required) | `jdbc:oracle:thin:@//oracle:1521/FREEPDB1`, `jdbc:oracle:thin:@//proxy:1521/sales.reporting-batch`, `jdbc:dbp://gateway:7420/sales`, `jdbc:postgresql://postgres:5432/sales` |
| `DBP_JDBC_USER` / `DBP_JDBC_PASSWORD` | – | database account, or application name + API key for the gateway |
| `DBP_JDBC_DRIVER` | auto | driver class to load explicitly (`DriverManager` discovers bundled drivers anyway) |
| `DBP_DEMO_ENGINE` | from URL, `ORACLE` for `jdbc:dbp` | `ORACLE` / `POSTGRES` |
| `DBP_BATCH_CONNECTIONS` | 20 | logical connections, one virtual thread each, all kept open for the whole run |
| `DBP_BATCH_DURATION_SECONDS` | 60 | run time |
| `DBP_BATCH_UPDATE_EVERY` / `DBP_BATCH_UPDATE_SIZE` | 10 / 10 | every n-th iteration runs a batch of UPDATEs in one transaction |
| `DBP_BATCH_THINK_MS` | 0 | pause per iteration |
| `DBP_BATCH_REPORT_SECONDS` | 10 | progress line interval |
| `DBP_BATCH_PROGRAM` | `reporting-batch` | `v$session.program` / `ApplicationName` / client info |

Each worker runs seven SELECTs (aggregations, joins, the view, point lookups) per iteration and,
every tenth iteration, a batch of ten `UPDATE INVENTORY SET UPDATED_AT = CURRENT_TIMESTAMP` inside
a transaction. The summary prints statements/sec, p50/p95/p99 latency, rows, errors and the number of
connections opened. Exit code 0 = ran, 1 = could not connect at all, 2 = bad configuration.

Through the gateway (TRANSACTION pool mode) the 20 logical connections are served by a pool of a
few physical connections: compare `Connections` in the UI (gateway logical vs physical) or
`GET /api/v1/stats/pools` with `V$SESSION` (direct/proxy mode shows 20 sessions).

```bash
# via compose (profile batch), gateway mode by default:
docker compose -f deploy/docker-compose.yml --profile batch run --rm reporting-batch
# direct mode, 50 connections, 2 minutes:
docker compose -f deploy/docker-compose.yml --profile batch run --rm \
  -e DBP_JDBC_URL=jdbc:oracle:thin:@//oracle:1521/FREEPDB1 -e DBP_JDBC_USER=SALES_APP \
  -e DBP_JDBC_PASSWORD=$SALES_APP_PASSWORD -e DBP_BATCH_CONNECTIONS=50 -e DBP_BATCH_DURATION_SECONDS=120 \
  reporting-batch
```

## What to look at in the UI afterwards

1. **Applications → orders-service**: relationships `CALLS ORDER_PKG.PLACE_ORDER`, `READS ORDERS/CUSTOMER`
   (source `GATEWAY`), and `WRITES ORDERS, ORDER_ITEM, PAYMENT, INVENTORY, AUDIT_LOG` with
   *via routine* set — the application never named those tables.
2. **Applications → legacy-reporting**: `READS CUSTOMER, ORDERS, PAYMENT` with source `PROXY_CORRELATION`
   (port join with `V$SESSION`) or `COLLECTOR_SESSION`; cross-team access to `sales-platform` tables
   → a `CROSS_TEAM_DIRECT_ACCESS` violation under *Governance*.
3. **Connections**: the three orders-service variants side by side: proxy connections for the proxy
   variant, gateway logical/physical for the gateway variant, and the direct variant visible only
   through the collector (`DIRECT_DB_ACCESS_BYPASSING_PLATFORM`).
4. **Datasources → sales → Pools** while `reporting-batch` runs: `logicalSessions` ≈ 20,
   `active`/`total` physical connections far lower.
5. **Tables → SALES.ORDERS → Impact**: direct consumers, indirect consumers through
   `ORDER_PKG.PLACE_ORDER`, the trigger `TRG_ORDERS_AUDIT`, the view `V_CUSTOMER_ORDER_SUMMARY`.
6. **Queries**: top statements per application; the batch's aggregations dominate by count.
