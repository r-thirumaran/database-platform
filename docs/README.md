# Documentation index

Documentation for the Database Access Platform: a transparent proxy, a gateway with a drop-in JDBC
driver, and a control plane with a metadata/ownership graph for Oracle, PostgreSQL and SQL Server.
Written for platform engineers and DBAs; vendor- and company-neutral, demo domain is generic retail.

## Start here

| Document | One line |
|----------|----------|
| [../README.md](../README.md) | Project overview, quick start with Docker Compose, module layout (maintained by the integrator). |
| [architecture.md](architecture.md) | Components, data path versus control path, how proxy, gateway, driver and control plane fit together (maintained by the integrator). |
| [oracle-connection-analysis.md](oracle-connection-analysis.md) | Why Oracle sessions cannot be multiplexed by a generic proxy; DRCP, shared server, CMAN TDM compared with the gateway approach (maintained by the integrator). |
| [glossary.md](glossary.md) | Definitions of the terms used everywhere: logical datasource, physical database, pinning, pool mode, relationship vs dependency, owner/producer/consumer. |
| [faq.md](faq.md) | Short answers to the questions adopters ask first (code changes, triggers, overhead, outages, rotation, migration). |

## Contracts (authoritative for the code)

| Document | One line |
|----------|----------|
| [wire-protocol.md](wire-protocol.md) | DBP wire protocol v1 between driver and gateway: framing, values, messages, errors, JDBC URL. |
| [control-plane-api.md](control-plane-api.md) | REST API v1 of the control plane: teams, applications, databases, credentials, datasources, grants, catalogue, graph, impact, telemetry ingestion, internal resolution. |
| [telemetry-events.md](telemetry-events.md) | `QueryEvent`, `ConnectionEvent`, `PoolStats`, heartbeat formats and the rules that derive relationships and confidence. |
| [metadata-model.md](metadata-model.md) | Entities of the metadata plane, ownership, producer/consumer, impact analysis, confidence and recency. |

## Guides and runbooks

| Document | One line |
|----------|----------|
| [rollout.md](rollout.md) | Phased rollout (0 Observe → 7 Domain APIs) with goals, prerequisites, steps, exit criteria, risks and metrics per phase; Phase 2 contains the Spring/Hikari/JPA/MyBatis snippets. |
| [operations.md](operations.md) | Running each component, consolidated configuration reference, metrics catalogue, alerting, capacity planning, credential rotation runbook, upgrades, troubleshooting, backups. |
| [security.md](security.md) | Threat model and trust boundaries, api keys, service token, credential providers, TLS per hop, network enforcement, auditability, collector least privilege, roadmap. |
| [collectors.md](collectors.md) | Per-engine collectors: exact views, privileges, intervals, licence notes (no Diagnostics Pack views), attribution precedence and confidence, proxy correlation, PL/SQL lineage, placeholder reconciliation. |
| [compatibility.md](compatibility.md) | What JDBC features work through the gateway/driver and what does not, pool-mode semantics, framework notes, driver-vs-proxy decision table. |
| [migration-playbook.md](migration-playbook.md) | Oracle → PostgreSQL per datasource: readiness checklist, SQL hotspots flagged by telemetry, pilot with routing rules, verification, switch, rollback, retirement, rewrite table. |

## Architecture decision records

| ADR | Decision |
|-----|----------|
| [0001](adr/0001-platform-not-library.md) | Build a platform with runtime components, not a shared library. |
| [0002](adr/0002-protocol-aware-passthrough-proxy-no-oracle-multiplexing.md) | Protocol-aware pass-through proxy; no Oracle session multiplexing. |
| [0003](adr/0003-custom-binary-wire-protocol-over-grpc.md) | Custom binary wire protocol (zero-dependency driver, synchronous JDBC semantics) instead of gRPC/HTTP. |
| [0004](adr/0004-java21-virtual-threads-for-gateway-and-proxy.md) | Java 21 virtual threads, blocking I/O, no framework in gateway and proxy. |
| [0005](adr/0005-transaction-pinning-and-session-mode.md) | Transaction pinning by default, explicit SESSION mode for state-dependent applications. |
| [0006](adr/0006-logical-datasource-and-routing-rules.md) | Logical datasources with per-application routing rules as the migration mechanism. |
| [0007](adr/0007-telemetry-derived-relationships-with-confidence.md) | Relationships derived from telemetry with explicit source and confidence. |
| [0008](adr/0008-dictionary-collectors-without-licensed-views.md) | Collectors use only base-edition views (no ASH/AWR). |
| [0009](adr/0009-spring-boot-control-plane-with-postgres-and-h2.md) | Spring Boot control plane; PostgreSQL in production, H2 for development. |
| [0010](adr/0010-selective-sql-compatibility-not-translation.md) | Routing and detection instead of SQL translation. |
| [0011](adr/0011-application-identity-via-api-keys-first.md) | Api keys as application identity first; workload identity later. |

## Conventions

* Contracts (`wire-protocol.md`, `control-plane-api.md`, `telemetry-events.md`, `metadata-model.md`)
  are edited by the integrator; code follows them. If a guide and a contract disagree, the contract wins.
* Terminology follows [glossary.md](glossary.md). Benchmarks are not quoted; where a number depends on
  the environment the documents say "to be measured".
* Licensing statements about vendor products are guidance only; check your own licence terms.
