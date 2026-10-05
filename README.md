# Database Access Platform

An open-source platform that puts a **control plane, a connection gateway and a data-ownership
graph** between your applications and your databases (Oracle, PostgreSQL, SQL Server) — so that
dozens of independently scaling services stop multiplying database sessions, credentials are
rotated in one place, and everybody can see **who owns, produces and consumes which table**.

> Applications should know the *logical* data source they need, not the physical database behind it.

```
  apps (unchanged)        apps (jar + URL swap)
  vendor JDBC driver      dbp-jdbc driver
        │                        │
        ▼                        ▼
   dbp-proxy  ──────────▶  dbp-gateway ──▶ bounded physical pools ──▶ Oracle / PostgreSQL / SQL Server
   identity, routing,      many logical sessions → few physical sessions,
   quotas, telemetry       central credentials, per-statement telemetry
        │                        │
        └──────────┬─────────────┘
                   ▼
          dbp-control-plane + dbp-ui
          teams · applications · datasources · routing · credentials
          tables · routines · ownership · producers/consumers · graph · impact analysis
```

## Why

Decomposing a long-lived application into 30+ services and 50+ UI services means
**services × instances × pool size** physical connections. For Oracle this quickly becomes session
exhaustion, shared passwords sprayed over deployments, lockouts after rotation, and nobody knowing
which service reads which table — right when Oracle and PostgreSQL must coexist during a migration.

The platform addresses that as one system rather than another connection library:

| Question                                                  | Answered by                                                    |
|-----------------------------------------------------------|----------------------------------------------------------------|
| How many Oracle sessions do we really use, and who opens them? | proxy connection telemetry + collectors (`V$SESSION`)      |
| Can 500 application connections become 60 database sessions? | gateway pools + drop-in JDBC driver                          |
| Can we rotate a database password without redeploying 30 services? | central credentials, versioned, drained by the gateway   |
| Which service generated this query? which table does it touch? | per-statement telemetry with SQL analysis                   |
| Who owns `SALES.CUSTOMER`? who writes it? who reads it?   | metadata plane: ownership, producers, consumers                |
| What breaks if I change `CUSTOMER.EMAIL`?                 | impact analysis over relationships, PL/SQL dependencies and triggers |
| Can `sales` move from Oracle to PostgreSQL without touching consumers? | logical datasources + routing rules + migration switch |
| Is team B querying team A's tables directly?              | governance policies and violations                             |

## The honest part about Oracle

Oracle's wire protocol cannot be pooled by a pass-through proxy the way PgBouncer pools PostgreSQL.
This platform therefore offers two complementary paths — see
[docs/oracle-connection-analysis.md](docs/oracle-connection-analysis.md):

| Path                                   | App change                              | What you get                                                   |
|----------------------------------------|-----------------------------------------|----------------------------------------------------------------|
| **Proxy** (`dbp-proxy`)                | connection URL only                     | identity, logical routing, quotas, connection telemetry, SQL attribution via collectors. Oracle session count unchanged. |
| **Gateway + driver** (`dbp-gateway`, `dbp-jdbc`) | swap driver jar + URL + api key; business code unchanged (`Connection`/`PreparedStatement`/`CallableStatement`/`ResultSet`) | many logical → few physical sessions, central credentials, exact per-statement telemetry, routing for migration. PL/SQL packages, functions, sub-functions, OUT params, ref cursors and triggers keep working. |
| Oracle DRCP / Connection Manager TDM   | URL only (vendor features)              | fewer server processes/sessions with the stock driver; needs DBA enablement / licence |

## Repository layout

| Path                   | What                                                                   |
|------------------------|------------------------------------------------------------------------|
| `dbp-protocol/`        | Wire protocol codec shared by driver and gateway ([spec](docs/wire-protocol.md)) |
| `dbp-common/`          | Telemetry models, control-plane client, SQL analyser                    |
| `dbp-jdbc/`            | Drop-in JDBC driver `jdbc:dbp://gateway:7420/<datasource>`              |
| `dbp-gateway/`         | Connection gateway (pools, pinning, execution, telemetry)               |
| `dbp-proxy/`           | Transparent protocol-aware proxy (Oracle TNS, PostgreSQL, SQL Server pass-through) |
| `dbp-control-plane/`   | Spring Boot REST API, metadata store, collectors, governance, UI host ([API](docs/control-plane-api.md)) |
| `dbp-ui/`              | React portal: dashboard, catalogue, graph explorer, impact analysis, admin |
| `dbp-examples/`        | `orders-service` (same code in direct/proxy/gateway modes), `reporting-batch`, `legacy-reporting` |
| `demo/`                | Retail demo schemas for Oracle (PL/SQL package, functions, triggers) and PostgreSQL |
| `deploy/`              | Docker compose (Oracle Free 23 + PostgreSQL 17 + platform + examples + Prometheus/Grafana), Kubernetes, Helm |
| `docs/`                | Architecture, specifications, rollout, operations, security, ADRs ([index](docs/README.md)) |

## Quick start

Prerequisites: Docker with ~6 GB RAM available (Oracle Database Free needs ≥ 2 GB), or Java 21 +
Maven + Node 22 for running from source.

```bash
# 1. Everything in containers (first start takes a few minutes while Oracle initialises)
cd deploy
cp .env.example .env            # set ORACLE_PASSWORD etc.
docker compose --profile core --profile examples --profile monitoring up -d --build

# 2. Open the portal
open http://localhost:8080      # UI + API (swagger at /swagger-ui.html)
open http://localhost:3000      # Grafana (admin/admin)

# 3. Generate traffic
curl -X POST localhost:8093/orders -H 'content-type: application/json' \
     -d '{"customerId":1,"productId":1,"quantity":2}'      # orders-service through the gateway
curl localhost:8092/orders                                 # orders-service through the proxy
docker compose --profile batch run --rm reporting-batch    # 20 logical connections → few physical
```

Then look at **Dashboard → connections**, **Tables → SALES.ORDERS → consumers** (you will see
`orders-service` writing it *via `ORDER_PKG.PLACE_ORDER`* and `AUDIT_LOG` written *via trigger*),
**Graph**, and **Impact analysis**.

From source without Docker:

```bash
mvn -q -DskipTests package                              # all Java modules
(cd dbp-ui && npm install && npm run build)             # UI (optional; served by the control plane)
java -jar dbp-control-plane/target/dbp-control-plane-*.jar          # :8080, H2 dev store, demo seed
java -jar dbp-gateway/target/dbp-gateway-*-all.jar                  # :7420 (static config or control plane)
java -jar dbp-proxy/target/dbp-proxy-*-all.jar                      # :1521/:5432 listeners
```

See [deploy/README.md](deploy/README.md) and [docs/operations.md](docs/operations.md).

## Using the drop-in driver

```properties
# before
spring.datasource.driver-class-name=oracle.jdbc.OracleDriver
spring.datasource.url=jdbc:oracle:thin:@//oracle-host:1521/FREEPDB1
spring.datasource.username=SALES_APP
spring.datasource.password=********

# after — business code unchanged
spring.datasource.driver-class-name=org.dbplatform.jdbc.DbpDriver
spring.datasource.url=jdbc:dbp://gateway:7420/sales
spring.datasource.username=orders-service
spring.datasource.password=${DBP_API_KEY}        # application api key issued by the control plane
```

Using only the proxy (no driver change):

```properties
spring.datasource.url=jdbc:oracle:thin:@//proxy-host:1521/sales.orders-service
spring.datasource.hikari.data-source-properties.v$session.program=orders-service
```

## How relationships are discovered

Nobody has to document table dependencies by hand:

1. **Gateway telemetry** — every statement is analysed: tables (READ/WRITE), routines called,
   normalised SQL, duration. Exact attribution to the application.
2. **Data dictionary crawlers** — `ALL_DEPENDENCIES`, `ALL_TRIGGERS`, foreign keys, view
   definitions (Oracle); `pg_proc`, `pg_trigger`, constraints (PostgreSQL); `sys.sql_expression_dependencies`
   (SQL Server). This turns *"orders-service CALLS ORDER_PKG.PLACE_ORDER"* into
   *"orders-service WRITES ORDERS, ORDER_ITEM, INVENTORY (via RESERVE_STOCK) and AUDIT_LOG (via trigger)"*.
3. **Runtime collectors** — `V$SESSION`/`V$SQL`/`V$SQL_PLAN`, `pg_stat_activity`, DMVs, joined to
   proxy connections through the proxy's outbound port, or to applications through program names,
   host patterns and CIDRs. Optional Oracle unified audit for exact attribution of legacy apps.
4. **Humans** — declare ownership, producers and intended consumers; confirm or reject what the
   platform inferred. Governance policies compare the two.

## Rollout

Phase 0 observe (proxy + collectors) → Phase 1 gateway foundation → Phase 2 driver adoption →
Phase 3 metadata & ownership → Phase 4 graph & impact in change management → Phase 5 multi-engine
routing → Phase 6 controlled migration → Phase 7 domain APIs. Details in
[docs/rollout.md](docs/rollout.md).

## Status

Proof of concept. Java paths are tested against H2 and embedded PostgreSQL in CI-less local builds;
Oracle and SQL Server paths are exercised through the docker compose stack (see the validation list
at the end of [docs/oracle-connection-analysis.md](docs/oracle-connection-analysis.md)). Not yet
production hardened — see [docs/compatibility.md](docs/compatibility.md) and
[docs/security.md](docs/security.md) for the known gaps.

## Licence

Apache License 2.0.
