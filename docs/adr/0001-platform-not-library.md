# ADR 0001: Build a platform, not a shared library

Status: accepted · Date: 2026-10

## Context

Thirty-plus backend services and fifty-plus UI services are being decomposed out of a few legacy
application instances. Each new service ships its own connection pool against the same Oracle
databases, with shared credentials copied into every deployment. Symptoms: session explosion
(services × instances × pool size), painful password rotation (stale instances lock accounts), no
visibility of which service touches which table, no table ownership, and an Oracle → PostgreSQL
migration that must happen while both engines coexist.

The first instinct is a shared Java library ("use our DataSource wrapper") that adds identity headers,
credential fetching and telemetry inside every application.

## Decision

Build a **platform** with its own runtime components — a transparent proxy, a gateway with a drop-in
driver, and a control plane with a metadata graph — and keep the application-side footprint to a
zero-dependency JDBC driver (or no change at all for the proxy path).

## Consequences

* Pooling, credentials, quotas, routing and telemetry are enforced in a place applications cannot
  skip, and changed without redeploying applications (credential rotation, migration switch).
* Non-Java and unchangeable applications are covered by the proxy path; a library would exclude them.
* Session reduction is only possible outside the application: a library cannot share a connection
  across JVMs.
* The platform introduces new runtime components to operate (gateway, proxy, control plane) and one
  extra network hop on the data path; this is the price of enforcement and is mitigated by stateless,
  horizontally scalable components and by cached configuration (see [operations.md](../operations.md)).
* The metadata plane (ownership, producers, consumers, impact) needs a system of record; that is the
  control plane, which a library approach would not have.

## Alternatives considered

| Alternative                                   | Why not                                                                                                             |
|-----------------------------------------------|---------------------------------------------------------------------------------------------------------------------|
| Shared DataSource library per language        | Adoption depends on every team; no session reduction; non-Java/legacy excluded; versions drift across 80+ services   |
| Sidecar per application pod                   | Still one pool per instance (no cross-instance sharing); same session count; more moving parts than a gateway       |
| Database-side only (DRCP, shared server, audit) | Addresses server processes and auditing, not identity, credentials, routing, ownership or migration                |
| Service mesh / API gateway in front of the DB  | HTTP meshes do not understand TNS/PG/TDS; no JDBC semantics                                                          |
| Do nothing, raise `PROCESSES`                  | Postpones the problem; still no visibility or ownership                                                              |
