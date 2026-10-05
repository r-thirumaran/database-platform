# ADR 0002: Protocol-aware pass-through proxy, no Oracle session multiplexing

Status: accepted · Date: 2026-10

## Context

Phase 0 needs identity, routing, quotas and connection telemetry for applications that cannot change
code, ideally before any gateway adoption. A "PgBouncer for Oracle" — a proxy that shares Oracle
sessions among clients — would be the most attractive promise, and the question of whether to attempt
it had to be settled early.

Facts about Oracle's client/server protocol relevant here: TNS/TTC is proprietary and undocumented;
authentication (O5LOGON and successors) is a per-connection challenge/response that cannot be replayed
for another client; session state (NLS, package variables, temporary tables, cursors, `ALTER SESSION`)
lives in the server process; transaction boundaries are not observable without decoding the vendor
protocol; native network encryption and TCPS make the stream opaque after the connect packet.
PostgreSQL's protocol is public and PgBouncer exists; SQL Server's TDS is documented but session
state and authentication have the same problems as Oracle.

## Decision

`dbp-proxy` is a **transparent, protocol-aware, pass-through TCP proxy**:

* it decodes only what is readable in clear at connection start — the Oracle TNS CONNECT packet
  (`SERVICE_NAME`/`SID`, `CID` program/host/user), the PostgreSQL startup message (database, user,
  `application_name`), the TDS pre-login/LOGIN7 where not encrypted;
* it rewrites the requested logical service name to the backend's real service, follows Oracle
  connect-time redirects, applies quotas, and then relays bytes in both directions untouched;
* it emits `ConnectionEvent`s whose `proxyLocalPort` lets the collectors correlate database sessions
  with applications;
* it **never multiplexes**: one client connection = one backend connection for its whole life.

## Consequences

* Zero application change beyond host/port (and optionally a service alias), any client language,
  no protocol-semantic risk: what the client sends is what the database receives.
* No session reduction on the proxy path. Reduction comes from the gateway (ADR 0005) or from vendor
  features that are explicitly compatible with the proxy: DRCP (`(SERVER=POOLED)` passes through),
  shared server, CMAN TDM in front of the database (licensing to be checked by the adopter).
* Proxy attribution of *statements* is by correlation and sampling (confidence 0.9), not exact.
* Client-side TLS that starts before the connect/startup packet (TCPS, PostgreSQL `SSLRequest`,
  encrypted TDS login) defeats identity and routing; TLS termination in the proxy is a roadmap item.
* Proxy restarts drop connections; clients' pools reconnect. Deployed with ≥ 2 instances.

## Alternatives considered

| Alternative                                             | Why not                                                                                                       |
|---------------------------------------------------------|---------------------------------------------------------------------------------------------------------------|
| Reverse-engineered Oracle multiplexing proxy             | Undocumented protocol, auth cannot be replayed, session state per process, encryption; unsupportable and fragile |
| PgBouncer for PostgreSQL + nothing for Oracle           | Splits the operating model; PgBouncer's transaction mode has the same semantic caveats the gateway documents, without identity/telemetry integration |
| CMAN TDM as *the* proxy                                  | Vendor-licensed, Oracle-only, no application identity model, no control plane integration; remains compatible as an option behind `dbp-proxy` |
| Plain TCP proxy (HAProxy) with source-IP identity        | No service-name routing/rewriting, no program/host identity, no per-application quotas, CIDR-only attribution |
