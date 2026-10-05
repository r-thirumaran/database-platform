# dbp-proxy — transparent, protocol-aware database proxy

`dbp-proxy` is the **Phase 0** component of the Database Access Platform: a TCP proxy that sits between
unchanged applications (still using the vendor JDBC/OCI/libpq driver) and Oracle, PostgreSQL or SQL
Server. Applications change **only their connection URL**; the proxy gives the platform

* **application identity** for every connection (service alias, program name, machine, CIDR),
* **logical-service routing**: `sales` → `oracle:1521/FREEPDB1` today, a different database tomorrow,
  without touching the application,
* **connection quotas** per application/datasource and per datasource,
* **connection-level telemetry** (`ConnectionEvent` OPEN / CLOSE / REFUSED / BACKEND_FAILED, heartbeats
  with a live-connection snapshot, Prometheus metrics),
* the **correlation key** that lets the control plane's collector attribute SQL seen in
  `V$SESSION` / `pg_stat_activity` to the originating application (see below).

Plain Java 21 (virtual threads), no framework. Shaded executable jar: `dbp-proxy-<ver>-all.jar`.

## What it does NOT do

**No session multiplexing / connection pooling for Oracle.** After the connect handshake the proxy is a
byte pump. Five reasons an open-source pass-through cannot pool Oracle sessions:

1. TNS/TTC is a proprietary, undocumented protocol whose data stream is negotiated per session (SDU,
   character set, compression, Native Network Encryption keys); packets cannot be re-targeted.
2. Authentication is O5LOGON, a challenge–response bound to the socket; the proxy never sees the
   password and cannot re-authenticate a session on another physical connection.
3. Per-session state (cursors, prefetch buffers, temp tables, package variables, LOB locators,
   transactions) lives in the dedicated server process and cannot be moved between clients.
4. Round trips are stateful (markers/breaks, piggy-backed bind/fetch) — a proxy that interleaves
   clients would corrupt them.
5. Doing it anyway means re-implementing the vendor client; the licence and the maintenance cost
   make that a non-starter for a POC.

Use the **gateway** (`dbp-gateway` + the `jdbc:dbp://` driver) for multiplexing; Oracle itself offers
**DRCP** (Database Resident Connection Pooling) and **Connection Manager in Traffic Director Mode
(CMAN-TDM)** as vendor options. SQL Server listeners are opaque TCP pass-through (see Limitations).

## How applications use it

| Engine     | Before                                           | After (only the URL changes)                                   |
|------------|--------------------------------------------------|----------------------------------------------------------------|
| Oracle     | `jdbc:oracle:thin:@//oracle:1521/FREEPDB1`       | `jdbc:oracle:thin:@//proxy-host:1521/sales.orders-service`     |
| PostgreSQL | `jdbc:postgresql://postgres:5432/sales`          | `jdbc:postgresql://proxy-host:5432/sales.orders-service`       |
| SQL Server | `jdbc:sqlserver://mssql:1433;databaseName=erp`   | `jdbc:sqlserver://proxy-host:1433;databaseName=erp`            |

The service / database name is the **logical datasource** (`sales`). The optional `.orders-service`
suffix is the **application alias**: it resolves the application identity (`identitySource =
SERVICE_ALIAS`) and is stripped before the request reaches the backend. Every Oracle tool works the
same way (`sqlplus app@//proxy-host:1521/sales.orders-service`, tnsnames entries, OCI).

**Dotted service names.** A requested name is first compared as a whole with every route's `match`
(case-insensitive). Only when nothing matches exactly is it split: a name whose prefix up to a dot equals
a route's `match` is parsed as `<match>.<alias>` (longest `match` wins, so a configured `sales.eu` beats
`sales` for `sales.eu.billing`, leaving `billing` as the alias). Consequently a *fully qualified* Oracle
service such as `orcl.example.org` is only treated as `<match>.<alias>` when a route matches `orcl`;
if you front such services, either declare the full name as the route's `match` or use a default route.
Everything after the first dot that follows the match is the alias (`sales.orders.v2` → alias
`orders.v2`). By default an alias nobody declared is still accepted (identity `application = <alias>`,
no `applicationId`); set `DBP_PROXY_STRICT_ALIASES=true` to refuse it (see below).

## Architecture

```
            ┌───────────────────────── dbp-proxy ──────────────────────────┐
 app ──TCP──┤ listener (accept loop, virtual thread per connection)         │
            │   ├─ Oracle: parse CONNECT → route → rewrite → send; follow    │
            │   │          RESEND / REDIRECT / REFUSE; after ACCEPT: pump    ├──TCP── Oracle
            │   ├─ PostgreSQL: SSLRequest→N, StartupMessage → route →       ├──TCP── PostgreSQL
            │   │          rewrite `database` → forward; then pump          │
            │   └─ MSSQL / TCP: pass-through (CIDR identity only)           ├──TCP── SQL Server
            │ registry (live connections) ─ quotas ─ metrics ─ telemetry    │
            │ admin HTTP :7431  /health /metrics /connections /config       │
            └───────────────┬──────────────────────────────────────────────┘
                            │ config fetch + version poll, heartbeat, ConnectionEvents
                      control plane (/api/v1/internal/…)
```

* One accept loop per listener; **two pump threads per connection** (client→backend, backend→client),
  `Socket` + buffered streams, graceful half-close (`shutdownOutput` on EOF, 30 s linger), optional idle
  timeout, byte counters, `maxConnections` cap per listener (default 5000).
* **Hot reload**: listeners are added / removed / re-bound as the configuration changes; a listener whose
  socket is unchanged just gets the new route table. Established connections are never dropped. A listener
  that could not bind (port still in use at start-up) or whose accept loop died is **re-bound on the next
  configuration poll** (`DBP_CONFIG_POLL_SECONDS`, both modes); `/health` reports `DEGRADED` meanwhile.
* **Handshake deadline**: the whole connect handshake — client packets, backend connect, backend replies,
  redirects — must complete within `DBP_PROXY_HANDSHAKE_TIMEOUT_MS` of accept. The deadline is re-armed as
  the socket read timeout before every read, so a client that sends one byte and stalls (or drips bytes)
  is dropped when the deadline passes and its slot released; a PostgreSQL client may send at most two
  SSL/GSS encryption requests before its StartupMessage.
* **Connection ids** are `c-<instance>-<seq>` where `instance` is the proxy's start time in base 36, so ids
  never repeat across restarts and the control plane can pair OPEN/CLOSE events safely.
* **Shutdown** emits a `CLOSE` event (reason `proxy shutdown`) for every established connection before the
  telemetry client flushes, so no connection stays open forever in the control plane.
* Client-supplied strings (service name, `CID.PROGRAM/HOST/USER`, PostgreSQL `user`/`application_name`,
  an undeclared alias) have control characters replaced by `?` before they reach logs, the admin JSON and
  telemetry; the bytes forwarded to the database are never altered.
* Telemetry never blocks the data path (bounded queue in `dbp-common`'s `TelemetryClient`).

### Oracle (TNS) handshake

1. Read the client's CONNECT packet. The connect string is located through `connectDataOffset` /
   `connectDataLength` (never a fixed layout); a string longer than 230 bytes arrives in the following
   DATA packet (deferred form) — both forms are handled and re-emitted the same way.
2. Parse the descriptor (`(DESCRIPTION=(ADDRESS=…)(CONNECT_DATA=(SERVICE_NAME=…)(CID=…))))`), extract
   `SERVICE_NAME`/`SID`, `INSTANCE_NAME`, `CID.PROGRAM/HOST/USER`.
3. Route on the service (`<match>` or `<match>.<alias>`), resolve identity, check quotas.
4. Rewrite: every `ADDRESS` gets the backend host/port, `SERVICE_NAME` is set to the physical service
   (`SID` dropped) when the route has `rewriteServiceName: true`; unknown keys and their order are
   preserved. Lengths are recomputed and the inline/deferred form is chosen by the 230-byte rule.
5. Send to the backend and handle its first packet:
   * **ACCEPT** → forward, data path becomes transparent (Native Network Encryption, markers, breaks
     all pass through untouched);
   * **RESEND** → forward to the client, relay (and rewrite) the client's next CONNECT;
   * **REDIRECT** → the proxy follows it itself (new backend socket to the redirect address, replacement
     connect string when present), so the client never sees a redirect to a port it could not reach
     through the proxy; the correlation key is the port of the final backend socket;
   * **REFUSE** → forwarded, connection closed, `BACKEND_FAILED` event;
   * a malformed packet from the backend (bad length, REDIRECT without HOST/PORT, …) → the client gets
     `ORA-12541`, a `BACKEND_FAILED` event is emitted — it is never counted as a client protocol error.
6. Refusals generated by the proxy are real TNS REFUSE packets, so the client raises the familiar
   error: `ORA-12514` (unknown service, or undeclared alias in strict mode), `ORA-12516` (quota / listener
   cap), `ORA-12541` (backend down or misbehaving).

### PostgreSQL handshake

`SSLRequest` / `GSSENCRequest` → `N` (plaintext between client and proxy); `CancelRequest` → forwarded
to the listener's default backend; `StartupMessage` → route on `database`, rewrite it when the route
says so (the `database` value is spliced into the original bytes — every other key and value, e.g. a
LATIN1 user name, is forwarded byte for byte; length recomputed), forward, then transparent. Proxy
refusals are FATAL `ErrorResponse`s: SQLSTATE `53300` (quota / cap), `3D000` (unknown logical database,
or undeclared alias in strict mode), `08001` (backend unreachable).

## Configuration

Two modes, selected by environment variables (environment always wins):

**Control-plane mode** — `DBP_CONTROL_PLANE_URL`, `DBP_SERVICE_TOKEN`, `DBP_PROXY_ID`: the proxy fetches
`GET /api/v1/internal/proxy/config?proxyId=…` at start (retrying until the control plane answers), polls
`GET /api/v1/internal/config-version` every `DBP_CONFIG_POLL_SECONDS` and hot-reloads on change, sends a
heartbeat every `DBP_HEARTBEAT_SECONDS` with `stats.liveConnections` (max 2000) and reports
`ConnectionEvent`s through the telemetry client.

**Static mode** — `DBP_PROXY_CONFIG=/path/proxy.yaml` with the same structure. The file is re-read when
its modification time changes.

### Environment variables

| Variable                          | Default             | Meaning                                                      |
|-----------------------------------|---------------------|--------------------------------------------------------------|
| `DBP_CONTROL_PLANE_URL`           | –                   | Enables control-plane mode                                   |
| `DBP_SERVICE_TOKEN`               | `dev-service-token` | `X-DBP-Service-Token` for `/api/v1/internal/**`. **When set** (any value), the same header is required on the admin API's `/connections` and `/config` |
| `DBP_PROXY_ID`                    | `proxy-1`           | Component id (`proxyId` in events, `componentId` in heartbeats) |
| `DBP_PROXY_CONFIG`                | –                   | Static YAML file (static mode)                               |
| `DBP_CONFIG_POLL_SECONDS`         | `5`                 | Config-version poll / file watch interval                    |
| `DBP_HEARTBEAT_SECONDS`           | `10`                | Heartbeat interval                                           |
| `DBP_PROXY_LISTEN_ADDRESS`        | `0.0.0.0`           | Bind address for listeners (and the admin API unless `DBP_PROXY_ADMIN_ADDRESS` is set) |
| `DBP_PROXY_ADMIN_ADDRESS`         | = listen address    | Bind address of the admin API; `127.0.0.1` keeps it off the network (then scrape via a sidecar / localhost) |
| `DBP_PROXY_ADMIN_PORT`            | `7431`              | Admin HTTP port                                              |
| `DBP_PROXY_STRICT_ALIASES`        | `false`             | `true`: refuse `<match>.<alias>` when no application declares `<alias>` (Oracle `ORA-12514`, PostgreSQL `3D000`, refusal reason `undeclared application alias …`) |
| `DBP_PROXY_UNKNOWN_APP_MAX_CONNECTIONS` | `0` (unlimited) | Per-datasource cap on live connections whose identity is `NONE` or an undeclared alias (`applicationId` null), so unregistered callers cannot bypass the per-application quotas; refusal `ORA-12516` / `53300` with reason `unknown-application quota exceeded: <datasource> n/max` |
| `DBP_PROXY_IDLE_TIMEOUT_SECONDS`  | `0` (off)           | Close connections idle in both directions for this long      |
| `DBP_PROXY_CONNECT_TIMEOUT_MS`    | `5000`              | Backend TCP connect timeout                                  |
| `DBP_PROXY_HANDSHAKE_TIMEOUT_MS`  | `15000`             | Deadline for the whole client/backend connect handshake, measured from accept (re-armed before every read) |
| `DBP_PROXY_BUFFER_BYTES`          | `32768`             | Pump buffer size per direction                               |
| `DBP_TELEMETRY_FLUSH_MS`, `DBP_TELEMETRY_QUEUE_SIZE` | `2000`, `50000` | Telemetry batching (see `docs/telemetry-events.md`) |
| `DBP_LOG_LEVEL`                   | `INFO`              | Log level for `org.dbplatform.proxy` (`DEBUG` logs every handshake) |

### YAML schema (static mode) — full example

```yaml
proxyId: proxy-1                      # optional, DBP_PROXY_ID wins
listeners:
  - name: oracle-main                 # unique; defaults to <engine>-<port>
    engine: ORACLE                    # ORACLE | POSTGRES | MSSQL | TCP
    port: 1521                        # default: 1521 / 5432 / 1433 per engine; 0 = ephemeral
    bindAddress: 0.0.0.0              # optional, default DBP_PROXY_LISTEN_ADDRESS
    maxConnections: 5000              # optional cap on concurrent client connections
    routes:
      - match: sales                  # logical service: SERVICE_NAME/SID == sales or sales.<alias>
        datasource: sales             # optional, defaults to match (datasourceId defaults to datasource)
        databaseId: db-oracle-1       # optional, reported in telemetry
        host: oracle
        port: 1521
        serviceName: FREEPDB1         # physical service
        rewriteServiceName: true      # set SERVICE_NAME=FREEPDB1 (and drop SID) before forwarding
      - match: inventory
        host: oracle
        port: 1521
        serviceName: FREEPDB1
        rewriteServiceName: true
    defaultRoute:                     # optional: anything else goes here, service passed through
      host: oracle
      port: 1521
      serviceName: null
      rewriteServiceName: false
  - name: postgres-main
    engine: POSTGRES
    port: 5432
    routes:
      - match: sales                  # jdbc:postgresql://proxy:5432/sales.orders-service
        host: postgres
        port: 5432
        serviceName: sales            # physical database name
        rewriteServiceName: true
  - name: mssql-main
    engine: MSSQL                     # opaque pass-through, CIDR identity only
    port: 1433
    defaultRoute: { host: mssql, port: 1433, datasource: erp }
applications:
  - name: orders-service              # id defaults to name
    teamId: sales-platform            # optional
    identityRules:
      serviceAliases: [orders-service]          # sales.orders-service
      programNames: ["JDBC Thin Client/orders"] # Oracle CID PROGRAM, glob (* ?), case-insensitive
      machinePatterns: ["orders-service-*"]     # Oracle CID HOST
      pgApplicationNames: [orders-service]      # PostgreSQL application_name
      cidrs: ["10.20.0.0/16"]
  - name: nightly-batch
    identityRules:
      programNames: ["sqlplus*"]
      cidrs: ["10.20.5.0/24"]
quotas:                               # per (application, datasource) live proxy connections
  - application: orders-service       # or applicationId
    datasource: sales                 # or datasourceId
    maxProxyConnections: 20
datasourceQuotas:                     # per datasource, all applications together
  - datasource: sales
    maxProxyConnections: 200
```

The control-plane document (`GET /internal/proxy/config`) has exactly this shape with ids instead of
names (`datasourceId`, `applicationId`). Both the API spellings of identity rules (`programNames`,
`machinePatterns`, `pgApplicationNames`) and the `dbp-common` spellings (`programs`, `machines`,
`applicationNames`) are accepted.

### Identity resolution (precedence)

1. `SERVICE_ALIAS` — the `<alias>` suffix of the requested service matches an application's
   `serviceAliases` (or its name). An undeclared alias still yields `application = <alias>` with a null
   `applicationId`, because the caller asked for it explicitly — unless `DBP_PROXY_STRICT_ALIASES=true`,
   in which case the connection is refused with a clear reason. Note that a null `applicationId` has no
   per-application quota: with the default settings `sales.anything-new` is only bounded by the datasource
   quota and the listener cap. Use strict aliases and/or `DBP_PROXY_UNKNOWN_APP_MAX_CONNECTIONS` when the
   proxy is reachable by callers you do not control.
2. `PROGRAM` (Oracle `CID.PROGRAM` vs `programNames`) / `APPLICATION_NAME` (PostgreSQL
   `application_name` vs `pgApplicationNames`, then `programNames`). Exact or glob (`*`, `?`), case-insensitive.
3. `MACHINE` — Oracle `CID.HOST` vs `machinePatterns`.
4. `CIDR` — client address vs `cidrs` (IPv4, IPv6, IPv4-mapped IPv6).
5. `NONE` — `"unknown"`.

Quotas are counted on **live** connections in this proxy instance (admission is atomic), per
`(applicationId, datasourceId)`, per `datasourceId`, and — when `DBP_PROXY_UNKNOWN_APP_MAX_CONNECTIONS`
is set — per `datasourceId` for all connections without an `applicationId` together; the refusal reason
is reported verbatim, e.g. `quota exceeded: orders-service/sales 20/20`.

## Running

```bash
# static mode
DBP_PROXY_CONFIG=./proxy.yaml java -jar dbp-proxy/target/dbp-proxy-0.1.0-SNAPSHOT-all.jar

# control-plane mode
DBP_CONTROL_PLANE_URL=http://control-plane:8080 DBP_SERVICE_TOKEN=dev-service-token DBP_PROXY_ID=proxy-1 \
  java -jar dbp-proxy/target/dbp-proxy-0.1.0-SNAPSHOT-all.jar
```

Ports: 1521 (Oracle), 5432 (PostgreSQL), 1433 (SQL Server), 7431 (admin). With the demo docker-compose
the real databases are published on 1522 / 5433 so the proxy can own the conventional ports on the host.

### Admin API (`:7431`)

| Endpoint            | Auth | Content                                                                            |
|---------------------|------|------------------------------------------------------------------------------------|
| `GET /health`       | open | `status` (UP / DEGRADED when a listener failed to bind or stopped accepting), mode, config version, listeners, telemetry and control-plane status |
| `GET /metrics`      | open | Prometheus text format (see below)                                                 |
| `GET /connections`  | token | JSON array of live connections (same fields as `ConnectionEvent` plus `state`)     |
| `GET /config`       | token | Effective configuration and settings — never the service token                     |

`/health` and `/metrics` are always open (health checks, Prometheus). `/connections` and `/config` expose
client addresses, users, program names and the route table: **when `DBP_SERVICE_TOKEN` is set** (it is in
the demo compose file and the Kubernetes manifests) they require the request header
`X-DBP-Service-Token: <token>` and answer `401` otherwise; without a token (static mode on a workstation)
they stay open. The admin API binds to `DBP_PROXY_LISTEN_ADDRESS` (default `0.0.0.0`) so that Prometheus
can scrape `proxy:7431/metrics` from another container; to keep it off the network entirely set
`DBP_PROXY_ADMIN_ADDRESS=127.0.0.1` and scrape through a localhost sidecar or `kubectl port-forward`.

```bash
curl -H "X-DBP-Service-Token: $DBP_SERVICE_TOKEN" http://localhost:7431/connections
```

### Metrics

| Metric | Labels | Meaning |
|--------|--------|---------|
| `dbp_proxy_connections_active` | `listener`, `application`, `datasource`, `backend` | Live proxied connections |
| `dbp_proxy_connections_live` | – | Live connections, all listeners |
| `dbp_proxy_connections_accepted_total` | `listener` | TCP connections accepted |
| `dbp_proxy_connections_refused_total` | `listener`, `reason` (`quota`, `unknown_service`, `undeclared_alias`, `listener_cap`, `no_route`, `protocol`) | Refused by the proxy (`quota` includes the unknown-application cap; `protocol` includes clients that are not database clients, e.g. more than two SSL requests) |
| `dbp_proxy_connections_failed_total` | `listener` | Backend unreachable / backend refused the handshake / backend sent a malformed handshake packet |
| `dbp_proxy_bytes_in_bytes_total`, `dbp_proxy_bytes_out_bytes_total` | `listener` | Bytes from / to clients |
| `dbp_proxy_backend_connect_seconds` | `listener`, `backend` | Backend TCP connect latency (timer with p50/p95/p99) |
| `dbp_proxy_telemetry_events_dropped` | – | Events dropped because the control plane was unreachable |

## Correlation with `V$SESSION.PORT` / `pg_stat_activity.client_port`

The proxy opens one backend socket per client connection. The **source port of that socket**
(`socket.getLocalPort()` on the backend side) is reported as `proxyLocalPort` (with `proxyLocalAddr`) in
every `ConnectionEvent`, in `GET /connections` and in the heartbeat snapshot. On the database side the
same number is visible as:

* Oracle: `V$SESSION.PORT` (client port as seen by the server; the proxy is the client) together with
  `V$SESSION.MACHINE`/`PROGRAM` describing the proxy host,
* PostgreSQL: `pg_stat_activity.client_port` (and `client_addr = proxyLocalAddr`).

The collector samples those views, joins `(client_addr, client_port)` with the live proxy connections
and attributes the session's `SQL_ID` / `query` — and the tables it touches — to `application`,
`datasource` and `team` (relationship source `PROXY_CORRELATION`, confidence 0.9). The proxy follows
Oracle REDIRECTs itself precisely so that this key is the port of the *final* backend socket. The
end-to-end test proves it against PostgreSQL: the registry's `proxyLocalPort` equals
`pg_stat_activity.client_port` of the proxied session.

Note that ports are reused over time: the join must use the connection's `openedAt`/`closedAt` window
(the control plane keeps the OPEN/CLOSE pairs keyed by `connectionId`).

## Limitations (POC)

* **PostgreSQL SSL between client and proxy is not supported**: the proxy answers `N` to `SSLRequest`,
  so clients must use `sslmode=disable` or `prefer` (`require` fails). The proxy → backend leg is
  plaintext too. TLS termination is a follow-up.
* **pgjdbc `application_name`** is only sent in the startup packet when `assumeMinServerVersion>=9.0` is
  set on the URL/properties; otherwise the driver sets it with `SET application_name` after connecting
  and the proxy cannot use it for identity (the collector still sees it in `pg_stat_activity`). Prefer
  the service alias (`sales.orders-service`) — it always works.
* **CancelRequest** carries no routing key (only pid + secret); it is forwarded to the listener's default
  backend (or the first route when there is no default). Cancellation is best effort when a listener
  fronts several backends.
* **SQL Server routing is out of scope**: TDS starts with PRELOGIN and negotiates TLS before the LOGIN7
  packet that carries the database name, so MSSQL listeners are opaque pass-through to their default
  route with CIDR-based identity only. The same applies to the generic `TCP` engine.
* **Oracle TCPS** (TLS) listeners are not supported (the proxy would have to terminate TLS to read the
  connect string). Native Network Encryption works, since it is negotiated after ACCEPT.
* **No session multiplexing** (see above) and no SQL visibility — the gateway provides both.
* The quota counts connections of *this* proxy instance; several proxies in front of the same database
  need the control plane to divide the grant between them.
* Idle timeout is off by default: applications pool connections and expect them to stay open.

## Building and testing

```bash
mvn -pl dbp-proxy package      # unit tests + real end-to-end test on zonky embedded PostgreSQL + shaded jar
```

The Oracle handshake is unit-tested with synthetic CONNECT packets (versions 315/318, inline and
deferred connect strings, 16- and 32-bit length headers), scripted fake backends (ACCEPT, RESEND,
REDIRECT with/without replacement connect data, malformed REDIRECT, REFUSE), stalled clients (handshake
deadline), strict aliases, the unknown-application quota, shutdown CLOSE events and the refuse packet
layout; the PostgreSQL handshake has the same raw-socket tests plus byte-exact startup rewriting against
a scripted backend (`PostgresHandshakeTest`), and `ProxyServerTest` covers listener re-binding. Exercise it for
real with `deploy/docker-compose.yml` (Oracle Free on 1522) and
`jdbc:oracle:thin:@//localhost:1521/sales.orders-service`.
