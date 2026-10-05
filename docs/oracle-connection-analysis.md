# Oracle Connection Analysis — what can and cannot be done "with only a URL change"

This document answers the question that decides the architecture:

> *Can we put something in front of Oracle so that applications keep their Oracle JDBC driver, change
> only the connection URL, and the database sees far fewer connections — the way PgBouncer does for
> PostgreSQL?*

Short answer: **not with an open-source pass-through proxy.** The reasons are structural, not a
matter of effort. What *is* possible with only a URL change is identity, routing, quotas and
visibility (this repository's `dbp-proxy`), and — on the Oracle side — DRCP or Connection Manager
in Traffic Director Mode. Real multiplexing with an open implementation requires swapping the
driver jar for a drop-in driver (`dbp-jdbc`) that talks to a gateway owning the physical pools
(`dbp-gateway`). Business code does not change in either case.

## 1. Why PgBouncer works for PostgreSQL

PgBouncer can hand one server connection to many client connections because PostgreSQL's wire
protocol was designed to be implemented by third parties:

| Property                               | PostgreSQL                                              |
|----------------------------------------|---------------------------------------------------------|
| Protocol specification                 | Public, stable (v3 since 7.4)                            |
| Authentication                         | Cleartext / MD5 / SCRAM — a proxy can terminate it and re-authenticate to the server with its own credentials |
| Session state                          | Small and resettable (`DISCARD ALL`)                     |
| Statement/transaction boundaries       | Explicit `ReadyForQuery` with transaction status byte → the proxy knows exactly when a server connection becomes free |
| Prepared statements                    | Named/unnamed, protocol-level; PgBouncer ≥ 1.21 tracks them |
| Encryption                             | TLS, terminated by the proxy if desired                  |

The proxy therefore understands *every* message boundary and can swap the backend between
transactions safely.

## 2. Why the same approach does not work for Oracle

| Property                               | Oracle (TNS/TTC)                                                                      |
|----------------------------------------|---------------------------------------------------------------------------------------|
| Protocol specification                 | Proprietary and undocumented. Only reverse-engineered implementations exist (python-oracledb "thin", go-ora, node-oracledb thin). Oracle changes TTC data-type negotiation between versions. |
| Authentication                         | O5LOGON challenge/response: the server issues a session key, the client proves password knowledge with AES-derived material. A proxy cannot "forward" a client's authentication to a *different* pre-existing server session; it would have to terminate authentication itself (act as the server) and separately authenticate to Oracle — then splice two independently negotiated sessions. |
| Session negotiation                    | Protocol version, SDU/TDU, character sets, TTC data-type representation, Native Network Encryption and checksumming are negotiated per connection. Two sessions negotiated separately may not be byte-compatible. |
| Session state                          | Large and sticky: NLS settings, `ALTER SESSION`, PL/SQL package variables, global temporary tables, open cursors, `DBMS_SESSION` identifiers, LOB locators, implicit result sets. There is no cheap `DISCARD ALL` equivalent that applications can rely on. |
| Transaction boundaries                 | Not surfaced as a simple protocol status byte; the proxy would have to parse TTC messages (and PL/SQL blocks can commit internally). |
| Encryption                             | Native Network Encryption negotiated inside the data stream; the proxy would have to terminate it to see anything. |

Consequence: a TCP proxy can **forward** Oracle connections 1:1 (and read the cleartext *connect
descriptor* that precedes authentication), but it cannot **merge** them. If 200 instances open 10
connections each, Oracle still sees ~2,000 sessions through such a proxy.

Could one build a protocol-terminating Oracle proxy? In principle yes (the open-source thin drivers
prove the protocol can be implemented), but it would mean re-implementing Oracle's server-side
handshake, maintaining compatibility with every client version, and still fighting session state.
That is a multi-year product, not a platform component. We record this as ADR-0002.

## 3. What Oracle itself offers for connection reduction

### 3.1 DRCP — Database Resident Connection Pooling

* Server-side pool of "pooled servers" managed by a connection broker inside the database.
* Enable once per database (DBA): `EXEC DBMS_CONNECTION_POOL.START_POOL;` and size it with
  `DBMS_CONNECTION_POOL.CONFIGURE_POOL`.
* Clients opt in **with only a URL change**: add `(SERVER=POOLED)` to the connect descriptor —
  `jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=h)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=svc)(SERVER=POOLED)))`.
  The stock thin driver supports it (set `oracle.jdbc.DRCPConnectionClass` to group sessions).
* What it reduces: dedicated server **processes** and their memory. Each client connection is still a
  TCP connection to the broker, but a pooled server is attached only while the client is doing
  work — with Oracle UCP the server is released on logical `close()`; with plain `DriverManager`
  connections the attach/detach happens per connection.
* Limits: not transparent for session state across attach/detach (same caveats as any pooling);
  requires DBA configuration; best results need Oracle UCP on the client.

### 3.2 Oracle Connection Manager — Traffic Director Mode (CMAN TDM)

* Oracle's own proxy for exactly this problem (18c+). Applications change only their connect string
  to point at CMAN. With Proxy Resident Connection Pooling (PRCP) it multiplexes many client
  connections over fewer database sessions, and it can add TLS termination, routing and failover.
* It can do this because it is Oracle code with knowledge of the protocol and the server-side
  pooling hooks.
* Requires Oracle 18c+ database and 18c+ clients (thin driver OK), DBA/network team ownership, and
  it is licensed as part of the Oracle database stack — **check your licence**.
* Session-state restrictions similar to DRCP apply (stateless between transactions for pooling).

### 3.3 Shared server (dispatchers)

Reduces server processes, not sessions. Helps memory, does not help "too many sessions" limits or
visibility.

## 4. What the URL-change-only proxy in this repository does

`dbp-proxy` embraces what is possible before authentication: the Oracle **connect packet** carries
the connect descriptor in cleartext —
`(DESCRIPTION=(CONNECT_DATA=(SERVICE_NAME=sales.orders-service)(CID=(PROGRAM=JDBC Thin Client)(HOST=orders-7f9c)(USER=app))))`.
From that single packet the proxy can:

| Capability                                   | How                                                                      |
|----------------------------------------------|---------------------------------------------------------------------------|
| Identify the application without code change | service alias suffix (`sales.orders-service`), CID `PROGRAM` (set via the thin driver's `v$session.program` property in config), CID `HOST` pattern, or source CIDR |
| Route a *logical* service to a *physical* one | rewrite `SERVICE_NAME` (e.g. `sales` → `FREEPDB1` on cluster A today, cluster B tomorrow) |
| Enforce quotas                               | refuse the Nth+1 connection per application/datasource with a TNS REFUSE (`ORA-12516`) |
| Count and time connections                   | OPEN/CLOSE/REFUSED events with bytes and duration                        |
| Attribute SQL to applications                | the proxy's outbound source port equals `V$SESSION.PORT` on the database → the collector joins `V$SESSION`/`V$SQL`/`V$SQL_PLAN` rows to the proxy's identity |
| Follow redirects transparently               | handles TNS REDIRECT itself so dedicated-server redirects never reach the client |

What it does not do: reduce Oracle session count, inject credentials (applications still hold the
database password until they move to the gateway), or see SQL (after authentication the stream is
opaque, often encrypted).

This makes the proxy the right **Phase 0** instrument: measure, attribute, enforce, route — while
leaving every application untouched except for its URL.

## 5. Where real multiplexing happens: gateway + drop-in driver

```
Application (unchanged business code: Connection / PreparedStatement / ResultSet)
    │  swap jar + URL:  jdbc:dbp://gateway:7420/sales?apiKey=…
    ▼
dbp-jdbc  (zero-dependency JDBC driver speaking the DBP wire protocol)
    │  one TCP connection per *logical* connection
    ▼
dbp-gateway  (owns HikariCP pools per physical database; pins a physical
             connection to a logical session only while a transaction or
             cursor is open; applies credentials fetched centrally)
    │  bounded number of *physical* Oracle sessions (e.g. 60 for 500 logical)
    ▼
Oracle / PostgreSQL / SQL Server
```

Why this is feasible where the proxy is not: the gateway speaks to Oracle with Oracle's own JDBC
driver (no protocol re-implementation) and to the application with a protocol *we* define, in which
statement, transaction and cursor boundaries are explicit. Session state is handled by policy:

| Pool mode     | Pinning                                                   | Use for                                                   |
|---------------|-----------------------------------------------------------|-----------------------------------------------------------|
| `TRANSACTION` | from first statement with autocommit off (or open cursor) until commit/rollback and cursor close | stateless services, batch jobs, most Spring/JPA applications |
| `SESSION`     | for the whole logical connection                          | legacy code relying on package variables, temp tables, `ALTER SESSION`, `DBMS_OUTPUT` |

`SESSION` mode gives no multiplexing for that application but still centralises credentials,
identity and telemetry; it is the safe default while onboarding a legacy app, switching to
`TRANSACTION` once the telemetry shows no session-state dependence.

PL/SQL packages, functions, sub-functions and triggers keep working because the gateway forwards
`CallableStatement` calls (including OUT parameters and `SYS_REFCURSOR` results) unchanged to the
real Oracle driver. Triggers fire inside Oracle exactly as before.

## 6. Recommendation

| Need                                                   | Use                                                            |
|--------------------------------------------------------|----------------------------------------------------------------|
| Visibility, identity, quotas, logical routing with **zero** app change | `dbp-proxy` (URL change only) + collectors                       |
| Fewer Oracle sessions with the **stock** thin driver   | DRCP (DBA enablement + `(SERVER=POOLED)`), or CMAN TDM if licensed and operated by the DBA team |
| Fewer Oracle sessions, central credentials, per-statement telemetry, migration routing, with **no business-code change** | `dbp-jdbc` + `dbp-gateway` (jar + URL + api key) |
| Strong domain ownership for selected domains           | domain APIs / events (later phases)                            |

These are complementary. A realistic sequence is: proxy everywhere (weeks), gateway for the noisiest
and newest services (months), DRCP/CMAN for whatever stays on the stock driver, domain APIs where
ownership justifies it.

## 7. Facts to validate against a live Oracle (tracked for the POC)

The proxy's TNS handling was implemented from protocol knowledge and synthetic packets; it must be
exercised against Oracle Database Free 23 via `deploy/docker-compose.yml`:

1. Connect-packet parsing for thin driver versions 19/21/23 (inline vs deferred connect strings, the
   `connectDataOffset` field).
2. Service-name rewriting accepted by the listener; behaviour with `(SID=…)` descriptors.
3. REDIRECT handling (dedicated server on non-Linux listeners, RAC/SCAN).
4. `V$SESSION.PORT` equals the proxy's outbound port (expected; verify with `pg_stat_activity`-style
   query against `V$SESSION`).
5. `v$session.program` connection property surfacing in `V$SESSION.PROGRAM` for identity rules.
6. Gateway: `SYS_REFCURSOR` OUT parameters, `NUMBER` precision round-trips, `DATE` vs `TIMESTAMP`
   mapping, implicit result sets, `RETURNING INTO`, batch of `MERGE`.
