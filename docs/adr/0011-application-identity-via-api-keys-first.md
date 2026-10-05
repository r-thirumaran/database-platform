# ADR 0011: Application identity via api keys first, workload identity later

Status: accepted · Date: 2026-10

## Context

The gateway must know which application is connecting, to apply grants, quotas, pool modes and to
attribute telemetry. Today applications identify themselves to Oracle with a shared database user and
password. Candidate mechanisms: per-application database accounts, mutual TLS with workload
certificates (SPIFFE/SPIRE, service-mesh identities), OIDC client credentials, or platform-issued api
keys. The estate spans Kubernetes, managed container runtimes and VMs, with no uniform workload
identity in place.

## Decision

Applications authenticate to the gateway with a **platform-issued api key** (`dbp_<prefix>_<secret>`),
one or more per application, passed as the JDBC `password` (or `apiKey` property) so that unchanged
`username`/`password` configuration works. Keys are hashed at rest, revocable, rotatable by overlap,
and resolve to `(applicationId, teamId, tags)` which drive grants, quotas and pool mode. Database
credentials are a separate concept owned by the platform (one per datasource), never given to
applications. Workload identity (mTLS/SPIFFE) is a roadmap item that will sit beside api keys with the
same resolution result.

## Consequences

* Works on every runtime today with no infrastructure prerequisite; adoption cost is "put a secret in
  the application's secret store", which every team already does for the database password.
* Removes database passwords from applications immediately; rotation of DB credentials no longer touches
  applications.
* An api key is a bearer secret: theft from an application's environment grants that application's
  access (bounded by its grants). Detection via `lastUsedAt`, network policy and telemetry; mitigation
  by short overlap rotation. This is no worse than the status quo (a DB password in the same place) and
  strictly better in blast radius.
* The gateway is the enforcement point; the proxy path has no application authentication (the database
  still authenticates the user) and relies on identity rules — documented as a weaker identity.
* `DBP_SECURITY_MODE=none` for the public API in the POC means the *operator* side is not yet
  authenticated; OIDC for the UI/API is the first security roadmap item.

## Alternatives considered

| Alternative                                    | Why not (now)                                                                                                |
|------------------------------------------------|--------------------------------------------------------------------------------------------------------------|
| One database account per application           | Oracle user sprawl (80+ users × databases), grants management on the database, still passwords in apps; attribution could be done this way but loses the pooling model (pools are per credential) |
| mTLS / SPIFFE workload identity                | Right end state; requires an identity infrastructure not present everywhere in the estate today; planned as an addition |
| OIDC client-credentials tokens                 | Needs token acquisition logic in the driver (HTTP client dependency, refresh) and an IdP for workloads; conflicts with the zero-dependency driver constraint |
| Kerberos / OS authentication                   | Not uniform across container runtimes; operationally heavy                                                    |
| Trust the network (source CIDR) as identity    | Insufficient in shared clusters; kept only as the lowest-precedence identity rule for the proxy               |
