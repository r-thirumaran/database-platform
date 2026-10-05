# ADR 0006: Logical datasources with per-application routing rules

Status: accepted · Date: 2026-10

## Context

Applications today embed the physical database in their configuration (`jdbc:oracle:thin:@//host/
FREEPDB1`). Moving a domain from Oracle to PostgreSQL, or even from one Oracle service to another, means
touching every consumer's configuration and redeploying. The migration must happen application by
application, with both engines live, and must be reversible.

## Decision

Introduce the **logical datasource** as the only name applications know (`jdbc:dbp://gateway/sales`,
proxy service alias `sales`). A datasource:

* is owned by a team and carries the pool policy and access grants;
* points at a **current** physical database and optionally a **target** one (`state: MIGRATING`);
* has an ordered list of **routing rules** `(priority, applicationId | tag, databaseId, readOnly,
  enabled)`.

Resolution for a session is deterministic: first enabled rule whose `applicationId` matches, else
first whose `tag` matches one of the application's tags, else `currentDatabaseId`. The result (database,
credential, pool policy, config version) is what the gateway caches. `POST /datasources/{id}/switch`
changes the default and records a `MigrationEvent`.

## Consequences

* Migration becomes configuration: add a rule per application, verify with telemetry, switch the
  default, delete the rules, roll back by deleting a rule or switching back.
* One datasource may hold pools to several physical databases at the same time (one per routed
  database); capacity planning must account for both engines during a migration.
* Routing is per **application**, not per statement or per table: an application whose SQL mixes
  tables of two datasources must either see both tables on both engines (replication) or be split
  before the migration. The playbook's readiness checklist flags cross-datasource joins.
* A rule takes effect for new logical sessions; moving all sessions of an application deterministically
  means restarting it (documented).
* The proxy benefits only partially: service-name rewriting can point a logical alias at a different
  Oracle service, but not at another engine.
* `readOnly` on a rule is a safety net during pilots (writes from a supposedly read-only consumer fail
  instead of diverging data).

## Alternatives considered

| Alternative                                         | Why not                                                                                                  |
|-----------------------------------------------------|----------------------------------------------------------------------------------------------------------|
| DNS/service-name swap of the whole database          | Big-bang; no per-application pilot; rollback is also big-bang                                             |
| Per-table routing inside the gateway                 | Requires parsing and splitting SQL and transactions across engines; joins and transactions break; far beyond the POC |
| Feature flags inside each application                | Redeploys; 80+ implementations; no central record of who is where                                         |
| Dual-write implemented by the gateway                | Turns the gateway into a replication engine with conflict semantics; out of scope, delegated to CDC tools |
