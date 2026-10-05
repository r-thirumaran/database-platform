# Architecture

The Database Access Platform separates applications from physical database connectivity and, at the
same time, builds the enterprise map of **who owns, produces and consumes which data**. It is made of
three planes and a small set of deployable components.

```
                                 PRODUCT APPLICATIONS
          ┌──────────────────────────┬──────────────────────────┬──────────────────────┐
          │ unchanged legacy apps    │ services / batch jobs    │ selected domains      │
          │ (vendor JDBC driver,     │ (drop-in dbp-jdbc,       │ (domain APIs/events,  │
          │  URL change only)        │  jar + URL change)       │  later phases)        │
          └────────────┬─────────────┴─────────────┬────────────┴──────────────────────┘
                       │ TNS / PG wire             │ DBP wire protocol
                       ▼                           ▼
   DATA PLANE   ┌─────────────┐             ┌──────────────┐
                │  dbp-proxy  │             │ dbp-gateway  │  HikariCP pools per physical DB,
                │ identity,   │             │ logical →    │  transaction/session pinning,
                │ routing,    │             │ physical     │  credentials from control plane,
                │ quotas,     │             │ multiplexing │  per-statement telemetry
                │ conn events │             └──────┬───────┘
                └──────┬──────┘                    │ vendor JDBC (ojdbc, pgjdbc, mssql-jdbc)
                       │ 1:1 TCP                   │
                       ▼                           ▼
                ┌────────────────────────────────────────────┐
                │        Oracle   ·   PostgreSQL   ·   SQL Server       │
                └────────────────────────────────────────────┘
                       ▲ collectors (dictionary + V$ / pg_stat / DMVs)
                       │
 CONTROL &      ┌──────┴───────────────────────────────────────────────┐
 METADATA       │ dbp-control-plane  (Spring Boot, PostgreSQL/H2)      │
 PLANES         │  teams · applications · api keys · databases ·       │
                │  credentials · logical datasources · routing rules · │
                │  access grants · catalogue (tables, columns,         │
                │  routines) · dependencies · relationships · graph ·  │
                │  impact analysis · governance · telemetry sink       │
                └──────┬───────────────────────────────────────────────┘
                       │ REST /api/v1
                ┌──────┴──────┐
                │   dbp-ui    │  dashboard, catalogue, graph explorer, impact, admin
                └─────────────┘
```

## Components

| Component            | Role                                                                                       | Tech                      |
|----------------------|--------------------------------------------------------------------------------------------|---------------------------|
| `dbp-proxy`          | Phase-0 transparent, protocol-aware TCP proxy. Reads the Oracle connect descriptor / PostgreSQL startup message, resolves application identity, rewrites logical service names to physical ones, enforces quotas, emits connection events, exposes the correlation key for SQL attribution. No session multiplexing (see [oracle-connection-analysis.md](oracle-connection-analysis.md)). | Java 21, virtual threads |
| `dbp-jdbc`           | Drop-in JDBC 4.3 driver, `jdbc:dbp://gateway/<datasource>`. Zero dependencies. Implements the DBP wire protocol. | Java 21                  |
| `dbp-protocol`       | Wire protocol codec shared by driver and gateway ([wire-protocol.md](wire-protocol.md)).     | Java 21, no deps         |
| `dbp-gateway`        | Owns physical connection pools; maps many logical sessions onto bounded physical connections; executes statements with the vendor driver; applies credentials fetched centrally; records per-statement telemetry with tables/routines extracted. | Java 21, HikariCP        |
| `dbp-common`         | Telemetry models, control-plane client, SQL analyser (JSqlParser + regex fallback).          | Java 21, Jackson         |
| `dbp-control-plane`  | REST API, metadata store, collectors, derivations, governance, telemetry sink, UI host ([control-plane-api.md](control-plane-api.md)). | Spring Boot 3, PostgreSQL (H2 for dev) |
| `dbp-ui`             | Web portal.                                                                                 | React, TypeScript, Vite, Cytoscape |
| `dbp-examples`       | `orders-service` (same code in direct / proxy / gateway modes), `reporting-batch`, `legacy-reporting`. | Spring Boot / Java |
| `demo/`, `deploy/`   | Retail demo schemas (Oracle PL/SQL packages, functions, triggers; PostgreSQL equivalents), docker compose, k8s, Helm, Prometheus/Grafana. | SQL, YAML |

## Data plane

### Logical vs physical connections

An application's JDBC `Connection` is a **logical session** on the gateway. Physical connections live
in gateway-owned pools, one pool per (physical database, credential). A logical session borrows a
physical connection only when it needs one:

```
autocommit statement:   borrow → execute → stream first rows → (cursor open: stay pinned) → cursor closed → return
transaction:            SET_AUTOCOMMIT false → first EXECUTE pins → … → COMMIT/ROLLBACK → return
SESSION pool mode:      first EXECUTE pins for the life of the logical session
```

Session settings (isolation, read-only, schema, client info) are remembered by the gateway and
re-applied to every newly pinned physical connection, so un-pinning is invisible to the application.

The outcome the platform is built for:

```
500 logical sessions (33 services × instances × pool size)  →  60 physical Oracle sessions
```

### Identity and credentials

```
Application ──(api key: dbp_<prefix>_<secret>)──▶ gateway ──(service token)──▶ control plane
                                                     │  resolve datasource → database, credential ref, pool policy
                                                     ▼
                                              secret provider (ENV / FILE / INLINE-encrypted / Vault / cloud)
                                                     │  username + secret (versioned)
                                                     ▼
                                               physical pool
```

Rotation: rotate the secret in the provider → `POST /credentials/{id}/rotate` → gateways see a new
`credentialVersion` on their next resolve/poll → new physical connections use the new secret, old
ones are drained. No application redeploys, no stale-password lockouts caused by old instances.

### Routing and migration

Applications ask for a **logical datasource** (`sales`). The control plane resolves it per application:
routing rules (`applicationId` → database, then `tag` → database) and finally the datasource's
`currentDatabaseId`. Moving one application from Oracle to PostgreSQL is a routing rule; moving the
domain is `POST /datasources/{id}/switch`. The impact endpoint lists affected consumers before either.

### Telemetry

| Source   | Event                | Carries                                                                 |
|----------|----------------------|-------------------------------------------------------------------------|
| gateway  | `QueryEvent`         | application, datasource, database, normalised SQL + hash, operation, tables (READ/WRITE), routines, duration, rows, errors |
| gateway  | `PoolStats`          | active/idle/waiting/max, logical vs pinned sessions, credential version  |
| proxy    | `ConnectionEvent`    | identity, requested vs resolved service, client addr, **proxy outbound port**, bytes, duration, refusals |
| collectors | sessions, SQL, dictionary | `V$SESSION`/`V$SQL`/`V$SQL_PLAN`, `pg_stat_activity`, DMVs; `ALL_DEPENDENCIES`, triggers, FKs |

Everything is batched, bounded and non-blocking ([telemetry-events.md](telemetry-events.md)).

## Control plane

The control plane is the only place configuration lives. Gateways and proxies start from it (or from
a static YAML when running standalone), poll `configVersion`, and cache aggressively so that a control
plane outage never affects the data path.

Key resources: teams, applications (+ identity rules, api keys), physical databases, credentials,
logical datasources (+ pool policy, routing rules, migration state), access grants
(application ↔ datasource with limits and pool-mode override), governance policies.

## Metadata plane

See [metadata-model.md](metadata-model.md). Two kinds of edges:

* **Dependency** (object → object, static): routine REFERENCES/READS/WRITES table, table TRIGGERS
  trigger, table FOREIGN_KEY table, view → table. Source: data dictionary crawlers or declared.
* **Relationship** (application → object, dynamic): READS / WRITES / CALLS with counts, recency,
  source and confidence. Source: gateway telemetry (exact), proxy correlation, collector sampling,
  audit trail, or declared.

CALLS are expanded through dictionary dependencies, so "orders-service CALLS ORDER_PKG.PLACE_ORDER"
also yields "orders-service WRITES ORDERS via ORDER_PKG.PLACE_ORDER" and, through
`TRG_ORDERS_AUDIT`, "WRITES AUDIT_LOG via trigger". That is how functions, sub-functions and triggers
become visible in the graph without anyone documenting them.

Ownership and producers are curated by humans and suggested by the platform (sole writer → inferred
producer; owner team of the producer → inferred owner). Governance policies turn the graph into
actionable findings: cross-team direct access without a declared consumer, writes by non-producers,
unowned tables, connections bypassing the platform.

## Request flows

### Gateway statement (autocommit)

```
driver → EXECUTE(sql, params)
gateway: analyse SQL (cache) → borrow physical conn → PreparedStatement → execute
       → RESULT_SET_HEADER + first ROWS (fetchSize) | UPDATE_COUNT → EXECUTE_DONE
       → if cursor exhausted: return physical conn; emit QueryEvent (async)
driver → FETCH(cursor, n) … → CLOSE_CURSOR → gateway returns physical conn
```

### Proxy connection (Oracle)

```
client → TNS CONNECT [(SERVICE_NAME=sales.orders-service)(CID=(PROGRAM=…)(HOST=…)(USER=…))]
proxy: parse descriptor → identity (alias/program/host/CIDR) → route sales → oracle:1521/FREEPDB1
     → quota check (REFUSE ORA-12516 if exceeded) → connect backend → send rewritten CONNECT
     → handle RESEND/REDIRECT/ACCEPT → transparent pumping; emit OPEN event with proxyLocalPort
collector: V$SESSION.PORT == proxyLocalPort → session ↔ application; SQL_ID → V$SQL_PLAN → tables
```

### Credential rotation

```
operator rotates secret in provider → POST /credentials/{id}/rotate (version++)
gateway poll: configVersion changed → resolve → credentialVersion 3 ≠ 2 → new pool config
            → soft-evict idle connections, retire active ones on return → PoolStats shows version 3
```

## Deployment shapes

* **Everything in one box** for evaluation: `deploy/docker-compose.yml` (Oracle Free 23, PostgreSQL
  17, platform, examples, Prometheus, Grafana).
* **Kubernetes**: one Deployment per component; gateway scales horizontally (each instance owns its
  pools — set `maxConnections` per datasource with that in mind, or use a small fixed replica count
  per database); proxy as a Deployment or DaemonSet; a `NetworkPolicy` restricting database ports to
  gateway/proxy pods is the enforcement mechanism for "no direct access".
* **Cloud Run style**: gateways are long-running services with min instances > 0; applications on
  Cloud Run connect to the gateway over the VPC connector; their own pool size becomes irrelevant to
  the database.

## Non-goals of the POC

* Universal Oracle → PostgreSQL SQL translation (selective detection only; [migration-playbook.md](migration-playbook.md)).
* Oracle protocol termination / session multiplexing in the proxy (ADR-0002).
* XA / distributed transactions through the gateway.
* Production-grade authentication for the UI (basic auth / none; OIDC is roadmap).
