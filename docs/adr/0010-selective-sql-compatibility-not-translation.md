# ADR 0010: Selective SQL compatibility — routing and detection, not translation

Status: accepted · Date: 2026-10

## Context

A gateway that sees every statement is a tempting place to translate Oracle SQL into PostgreSQL SQL
and make the migration "transparent". Experience with translation layers shows the hard part is not
syntax (`NVL` → `COALESCE`) but semantics: `''` is `NULL` on Oracle and not on PostgreSQL; `SYSDATE`
advances within a statement while `now()` is frozen per transaction; integer division truncates on
PostgreSQL; `ROWNUM` applies before `ORDER BY`; `DATE` carries a time; `DECODE` matches `NULL`s;
identifier case folding differs; PL/SQL packages have no equivalent. A translator that gets these
subtly wrong corrupts data silently, and applications would come to depend on it forever.

## Decision

The platform follows a **selective compatibility** position, in this order:

1. **Routing first** — per-application routing rules (ADR 0006) make the migration incremental; each
   application is tested against PostgreSQL individually.
2. **Detection second** — gateway telemetry (`sqlNormalized`) is scanned for known Oracle-specific
   constructs and reported per application and statement with counts, so remediation is evidence-based.
3. **Translation only where cheap and provably equivalent** — the gateway keeps a hook for an explicit,
   per-datasource, opt-in list of lexical rewrites (for example `NVL`→`COALESCE`, `FROM DUAL` removal,
   `MINUS`→`EXCEPT`). It is off by default in the POC and never covers semantic differences.
4. **Application remediation otherwise** — hand-written SQL is fixed in the application where it can be
   unit-tested on both engines; ORM-generated SQL is portable by configuration (dialect).

The gateway forwards SQL unchanged unless rule 3 is explicitly enabled for a datasource.

## Consequences

* No silent semantic drift; failures are loud (`42xxx` errors) and visible in telemetry during pilots.
* Applications end up with portable SQL, which also simplifies running on both engines during the
  rollback window.
* The migration effort for hand-written SQL and PL/SQL is not hidden; the playbook makes it an explicit
  readiness item with a rewrite table.
* The detection list is maintained in the control plane and can grow without touching the data path.
* Adopters who want a translation layer can still put one in front of PostgreSQL; the platform does not
  prevent it.

## Alternatives considered

| Alternative                                      | Why not                                                                                              |
|--------------------------------------------------|------------------------------------------------------------------------------------------------------|
| Full Oracle→PostgreSQL translation in the gateway | Semantic traps; permanent dependency on the translator; PL/SQL cannot be translated at runtime anyway |
| Compatibility-extended PostgreSQL distributions   | Valid option for an adopter, outside the platform's scope; routing and detection still apply           |
| Forbid Oracle-specific SQL via policy (`42000`)   | Available as a hook, but blocking statements in production is a bigger risk than reporting them        |
