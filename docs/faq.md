# FAQ

Short answers with pointers to the detailed documents. Terminology: [glossary.md](glossary.md).

### Why can't a proxy pool Oracle connections like PgBouncer?

PgBouncer works because the PostgreSQL wire protocol is public, simple and stateless enough at
transaction boundaries: the proxy can read `ReadyForQuery` to know a transaction ended and hand the
server connection to another client. Oracle's TNS/TTC protocol is proprietary and undocumented;
authentication is a per-connection challenge/response that the proxy cannot replay for another client;
session state (NLS settings, package variables, cursors, temp tables) lives in the server process; and
transaction boundaries are not reliably visible without parsing the vendor protocol. A byte-level proxy
can therefore identify, route, count and refuse Oracle connections — which is what `dbp-proxy` does —
but it cannot share one Oracle session between several clients. The vendor-supported ways to pool
Oracle sessions outside the application are **DRCP** (server-side pooling of server processes,
`(SERVER=POOLED)`, started by a DBA with `DBMS_CONNECTION_POOL.START_POOL`; works with the stock thin
driver), **shared server** (dispatchers), and **Oracle Connection Manager in Traffic Director Mode**
(CMAN TDM, 18c+, proxy-resident connection pooling, a separately licensed Oracle product — check your
licence). The platform's answer is to pool at the JDBC level in the gateway, where transaction
boundaries are explicit (`commit`/`rollback`). See `oracle-connection-analysis.md` and
[ADR 0002](adr/0002-protocol-aware-passthrough-proxy-no-oracle-multiplexing.md).

### Do I need to change code?

No for Java applications using standard JDBC: add the driver jar, change the URL to
`jdbc:dbp://gateway:7420/<datasource>`, put the api key in `password`/`apiKey`, set the driver class
name where the framework needs it. See [rollout.md](rollout.md#phase-2--drop-in-driver-adoption).
Applications using vendor-specific JDBC extensions or non-Java clients keep their driver and change
only the host/port to the proxy. See the decision table in [compatibility.md](compatibility.md#driver-vs-proxy-decision-table).

### What about triggers and packages?

They keep working: the gateway executes the application's SQL on a real Oracle connection, so triggers
fire and packages run exactly as before. `CallableStatement` with OUT/INOUT parameters and REF CURSOR
is supported. The only caveat is **package state between transactions**, which requires SESSION pool
mode (see [compatibility.md](compatibility.md#pool-mode-semantics)). The catalogue also records
packages, procedures, functions and triggers with their table dependencies so you can see which
applications call them (see [collectors.md](collectors.md#plsql-lineage)).

### How do you know who owns a table?

You tell the platform (declared ownership, per table or in bulk per schema), or it proposes an owner:
the team of the **only application that writes the table** over the observation window
(`ownerSource = INFERRED`), which a human confirms. Inferred owners never overwrite declared ones.
See [metadata-model.md](metadata-model.md#ownership) and [rollout.md](rollout.md#phase-3--metadata-and-ownership).

### How accurate are the relationships?

Each relationship carries a `source` and a confidence: gateway telemetry and audit-trail rows are exact
(1.0); proxy correlation is 0.9 (identity is exact, but statements are sampled); session sampling with
identity rules is 0.6 (may miss short statements and can be ambiguous). Declared relationships are 1.0.
The UI shows the source and marks relationships stale after `DBP_RELATIONSHIP_STALE_DAYS`. The practical
answer: anything that goes through the gateway is complete; anything that goes through the proxy or
directly is sampled. See [collectors.md](collectors.md#attribution-precedence-and-confidence).

### What is the difference between a dependency and a relationship?

A **dependency** is object → object and static (routine references table, table has foreign key to
table, table has trigger), from the data dictionary or declared. A **relationship** is application →
object and observed at runtime (reads, writes, calls), with counts and recency. Impact analysis walks
both. See [metadata-model.md](metadata-model.md).

### What happens when the control plane is down?

Gateways and proxies keep serving on their cached configuration (resolution results, credentials in
memory, proxy routes and quotas). New sessions for an api key the gateway has seen within
`DBP_AUTH_CACHE_SECONDS` (60 s) and an already-resolved (datasource, application) pair work — stale
cache entries keep serving during the outage; a never-seen api key cannot be authenticated and gets
`08004` until the control plane is back. Telemetry is buffered in a bounded queue (50 000 events by
default) and the oldest events are dropped once it fills; drops are counted in
`dbp_gateway_telemetry_dropped_total` / `dbp_proxy_telemetry_events_dropped`. Credential rotations and
routing changes wait until the control plane returns. The UI is unavailable because it only talks to the
control plane. See [operations.md](operations.md#upgrade-and-zero-downtime-notes).

### What happens when a credential is rotated?

`POST /credentials/{id}/rotate` bumps the credential version; every gateway notices via the config
version poll, fetches the new material, opens new physical connections with it and retires old ones as
they become idle/un-pinned. Applications hold api keys, not DB passwords, so nothing is redeployed.
Runbook in [operations.md](operations.md#credential-rotation-runbook).

### What happens when an api key is revoked?

New sessions with that key fail with SQLState `08004` as soon as the gateway's positive cache entry
expires (at most `DBP_AUTH_CACHE_SECONDS`, 60 s). Existing sessions are **not** terminated — the gateway
authenticates at HELLO only — so they live until the application closes them; restart the workload if
the key was compromised. Rotate by issuing a second key first, then revoking the old one. See
[security.md](security.md#application-identity-api-keys).

### Is SQL translated?

No. The gateway forwards the application's SQL to the physical engine unchanged. Telemetry flags
Oracle-specific constructs so you can fix them in the application, and an optional, explicit, per-datasource
list of lexical rewrites is a possible future hook — off by default. Routing first, detection second,
translation only where cheap, application remediation otherwise. See
[migration-playbook.md](migration-playbook.md#selective-sql-compatibility-position) and [ADR 0010](adr/0010-selective-sql-compatibility-not-translation.md).

### Can batch jobs use it?

Yes. Batch jobs benefit most from identity (`kind: BATCH`), telemetry and bounded pools. Long-running
cursors keep a physical connection pinned for their duration, which is expected; size the pool for it.
Spring Batch's paging readers are pin-friendly; cursor readers hold one pin per step. Very large LOB
rows need a lower `fetchSize`. The `reporting-batch` example in `dbp-examples` runs through the
gateway.

### How does this help the Oracle → PostgreSQL migration?

Four ways: (1) the catalogue and telemetry give the real scope — consumers, routines, triggers, hot and
unused tables — per datasource; (2) telemetry flags SQL compatibility hotspots per application with
counts; (3) routing rules move applications one at a time between Oracle and PostgreSQL without
redeploying them, and switch back in one call; (4) after the switch the collectors prove nothing touches
the old tables before you drop them. See [migration-playbook.md](migration-playbook.md).

### What is the overhead?

One extra network hop and one encode/decode of each request and result batch in the gateway; one
extra hop and a byte relay in the proxy. Numbers are **to be measured** in your environment with the
load generator in `dbp-examples` (compare direct, proxy and gateway modes of `orders-service`). Factors:
network latency between application, gateway and database (keep them in the same zone), `fetchSize`,
row width, and whether a statement had to acquire a physical connection (the wait is part of
`dbp_gateway_statement_duration_seconds`; `dbp_gateway_pool_waiting > 0` shows sessions queueing for
one). The gateway uses virtual threads, so concurrency is bounded by pool size and memory rather than
by threads ([ADR 0004](adr/0004-java21-virtual-threads-for-gateway-and-proxy.md)).

### Does the gateway reduce Oracle sessions for every application?

Only in TRANSACTION pool mode, and proportionally to how idle the application's connections are
between transactions. SESSION mode (needed for session-state-dependent applications) keeps one physical
connection per logical session and reduces nothing; it still centralises credentials, identity and
telemetry. See [operations.md](operations.md#capacity-planning).

### Can two applications share one physical connection at the same time?

Never. A physical connection is pinned to exactly one logical session while it is in use; sharing
happens over time, not concurrently.

### What does the database see as the connecting user?

For the gateway: the datasource's pool account (one per datasource) with `V$SESSION.PROGRAM =
dbp-gateway/<gatewayId>`, and `MODULE / CLIENT_IDENTIFIER / ACTION` (Oracle) set from the application's
client info (`ApplicationName`, `ClientUser`, `action`) while the session is pinned, so DBAs still see
the logical application. For the proxy: whatever the application authenticates as (unchanged; the
proxy's address and port appear as the client). Prefer one database account per datasource rather than
per application.

### Can I use the platform with an application I cannot change at all?

Yes, through the proxy, as long as you can change its connection host/port (or DNS). You get identity
(by service alias, program name, machine or CIDR), quotas, connection telemetry and proxy correlation,
but no pooling, no central credentials and no routing between engines. Callers you do not control can
be fenced with `DBP_PROXY_STRICT_ALIASES=true` (undeclared `sales.<alias>` refused) and
`DBP_PROXY_UNKNOWN_APP_MAX_CONNECTIONS` (cap for unidentified connections).

### Can I correlate a database statement with a request or trace?

Yes, through the gateway: `Connection.setClientInfo("traceparent", …)` (or any name) is sent to the
gateway at once and included in every `QueryEvent` of that session; `action` and `ApplicationName`
additionally reach Oracle as `OCSID.ACTION` / `OCSID.MODULE`. A Spring `DataSource` wrapper that stamps
the current trace on each borrowed connection is in
[operations.md](operations.md#request-level-tracing). OpenTelemetry export is roadmap.

### Does the proxy terminate TLS?

Not in the POC. Oracle native network encryption passes through (the connect packet stays readable);
TCPS, PostgreSQL client SSL (the proxy answers `N` to `SSLRequest`, so use `sslmode=disable|prefer`) and
encrypted TDS logins hide what the proxy needs for identity/routing. See [security.md](security.md#tls).

### How is identity established for proxied connections?

In order: the service alias the client asked for (`<datasource>.<application>` as `SERVICE_NAME`), then
program name / PostgreSQL `application_name`, then machine pattern, then source CIDR. Set
`v$session.program` (Oracle thin) or `ApplicationName` (pgjdbc) in the application's connection
properties to improve it without code changes. See [collectors.md](collectors.md#improving-attribution-without-code-changes).

### Which Oracle views does the collector read, and do they need a licence?

`DBA_*` dictionary views (falling back to `ALL_*`), `V$SESSION`, `V$SQL`, `V$SQL_PLAN` and, optionally,
`UNIFIED_AUDIT_TRAIL`. None requires an extra option. ASH and AWR (`V$ACTIVE_SESSION_HISTORY`,
`DBA_HIST_*`) are deliberately **not** used because they require the Diagnostics Pack. Check your own
licence terms. See [collectors.md](collectors.md#licence-notes).

### Can the gateway be a single point of failure?

Run at least two instances; the driver accepts several hosts and tries them in order at connect time;
the application's pool replaces failed connections. A gateway restart closes its logical sessions
(`08006`), which pools recover from. See [operations.md](operations.md#upgrade-and-zero-downtime-notes).

### Is the control plane on the data path?

No. It is consulted at session start (api key, datasource resolution) and for configuration/credential
changes; results are cached. Statements never pass through it. Telemetry flows to it asynchronously
and is dropped rather than blocking the data path.

### Why a custom binary protocol instead of gRPC or HTTP?

The driver jar must be droppable into any application classpath without dependency conflicts (no
Netty, no protobuf, no HTTP client), and JDBC is synchronous request/response per connection, which a
length-prefixed frame protocol over one TCP socket matches exactly. See
[ADR 0003](adr/0003-custom-binary-wire-protocol-over-grpc.md).

### Why not just use DRCP or CMAN TDM?

They solve server-process pooling for Oracle and nothing else: no application identity beyond
`V$SESSION` columns, no central credentials, no per-statement attribution, no routing to PostgreSQL,
no metadata graph. They are also complementary: a gateway pool can connect to a DRCP-enabled service
if the DBA prefers it, and the proxy can front CMAN. See `oracle-connection-analysis.md`.

### Does the platform replace a schema migration tool or a replication tool?

No. Flyway/Liquibase still manage schema changes (they can run through the gateway, in SESSION mode),
and data replication for a migration is done with a CDC or replication tool of your choice. The
platform observes, routes and reports.

### Where is sensitive data kept?

INLINE credentials are encrypted with `DBP_MASTER_KEY` in the metadata store; api keys are stored
hashed; telemetry carries normalised SQL without literals and no parameter values or rows. The
service token and master key must be changed from their development defaults. See [security.md](security.md).

### What is not covered by the POC?

Non-Java drivers, TLS termination in the proxy, OIDC for the UI, workload identity (mTLS/SPIFFE),
external secret managers (`VAULT`/`GCP_SECRET_MANAGER`/`AWS_SECRETS_MANAGER` answer `501`), row-level
policies, XA, scrollable result sets, LOB locators, SQL translation, OpenTelemetry export. Each is listed with its reason in [compatibility.md](compatibility.md) and
[security.md](security.md#roadmap).
