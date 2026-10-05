# ADR 0009: Spring Boot control plane with PostgreSQL in production and H2 for development

Status: accepted · Date: 2026-10

## Context

The control plane is a conventional service: REST API with OpenAPI, a relational metadata store with
schema migrations, scheduled jobs (collectors, governance), JDBC access to three engines for crawling,
and a bundled single-page UI. It is **not** on the data path, so its framework footprint does not affect
application latency. Contributors should find it familiar, and a developer or evaluator must be able to
start it with no external database.

## Decision

* **Spring Boot 3** (web MVC, Data JPA, validation, actuator, Micrometer/Prometheus, springdoc) for the
  control plane only. Gateway and proxy stay framework-free (ADR 0004).
* Metadata store: **PostgreSQL** in production (`postgres` profile), **H2** file database in the `dev`
  profile for zero-setup evaluation and tests; schema managed by **Flyway** with migrations that run on
  both. The zonky embedded PostgreSQL is used in tests that need real PostgreSQL behaviour.
* The UI build (`dbp-ui`, React/Vite) is served by the control plane; the UI talks only to the public API.
* Configuration via YAML + `DBP_*` environment variables like the other components.

## Consequences

* Fast onboarding: `java -jar dbp-control-plane.jar` with the dev profile gives a working UI with the
  demo dataset (`POST /seed/demo`).
* Two SQL dialects to keep compatible in migrations and queries (H2 in PostgreSQL compatibility mode
  narrows the gap); engine-specific SQL is avoided or isolated.
* H2 is explicitly **not** for production: single process, file-level durability, no backups story;
  the operations guide says so.
* Spring's scheduling is adequate for the POC's collectors; a multi-instance control plane would need
  leader election or a job store (roadmap). The gateway/proxy cache tolerates control plane downtime.
* Oracle/SQL Server JDBC drivers are runtime dependencies of the control plane (collectors), versioned
  centrally in the root `pom.xml`.

## Alternatives considered

| Alternative                                   | Why not                                                                                              |
|-----------------------------------------------|------------------------------------------------------------------------------------------------------|
| Same plain-Java style as the gateway            | REST, JPA, OpenAPI, scheduling and security would be re-implemented; no latency benefit here         |
| Quarkus / Micronaut                            | Fine choices; Spring Boot chosen for contributor familiarity and ecosystem (springdoc, Flyway, actuator) |
| Graph database for the metadata plane          | The graph is small (thousands of nodes) and the API is relational (filters, paging, aggregates); graph queries are computed in memory |
| PostgreSQL only (no H2)                        | Raises the evaluation barrier; Docker is not always available on developer machines                 |
| Oracle as the metadata store                   | Would tie the platform to the engine it helps to migrate away from                                   |
