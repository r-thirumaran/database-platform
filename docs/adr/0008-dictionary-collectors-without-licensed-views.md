# ADR 0008: Dictionary and runtime collectors that use no separately licensed views

Status: accepted · Date: 2026-10

## Context

The richest source of "who ran what" on Oracle is Active Session History and AWR
(`V$ACTIVE_SESSION_HISTORY`, `DBA_HIST_*`). They require the Diagnostics Pack, an Enterprise Edition
option, and merely querying those views can constitute use of the pack. The platform is open source
and must work on any edition the adopter runs, and must not create a licensing exposure by default.
Equivalent concerns exist for other engines' paid features.

## Decision

Collectors read only **base-edition** views:

* Oracle: `DBA_*` dictionary views (`DBA_TABLES`, `DBA_TAB_COLUMNS`, `DBA_OBJECTS`, `DBA_PROCEDURES`,
  `DBA_ARGUMENTS`, `DBA_TRIGGERS`, `DBA_CONSTRAINTS`, `DBA_CONS_COLUMNS`, `DBA_DEPENDENCIES`,
  `DBA_SOURCE`, `DBA_SYNONYMS`, `DBA_VIEWS`, `DBA_MVIEWS`), `V$SESSION`, `V$SQL`, `V$SQL_PLAN`,
  `V$RESOURCE_LIMIT`, and optionally `UNIFIED_AUDIT_TRAIL`. **Never** `V$ACTIVE_SESSION_HISTORY`,
  `DBA_HIST_*` or `DBMS_WORKLOAD_REPOSITORY`.
* PostgreSQL: `pg_catalog`, `pg_stat_activity`, optional `pg_stat_statements` (contrib).
* SQL Server: `sys.*` catalog views, `sys.dm_exec_*` DMVs, `sys.sql_expression_dependencies`; not Query
  Store by default.

Runtime attribution is therefore **sampled** by the platform itself (`V$SESSION` every
`runtimeIntervalSeconds`), and precision is recovered through proxy correlation and, above all, gateway
telemetry. Privileges are limited to `CREATE SESSION` + `SELECT_CATALOG_ROLE`/`SELECT ANY DICTIONARY`
(+ `AUDIT_VIEWER`), `pg_monitor`, `VIEW SERVER STATE` + `VIEW DEFINITION`.

## Consequences

* No licence surprise from running the collectors; the documentation still tells adopters to check
  their own licence terms, because the platform cannot know them.
* Lower fidelity than ASH for sampled attribution (0.6), which is acceptable because the gateway path
  is the long-term source and the proxy path adds correlation (0.9).
* Collectors are cheap and run frequently; they never read application data.
* Adopters who *do* own the Diagnostics Pack can add an ASH-based collector as an extension; the
  attribution model already has a `source`/`confidence` slot for it.
* Dependency refinement (READ/WRITE from `DBA_SOURCE`) relies on the platform's SQL analyser, with its
  known blind spots for dynamic SQL.

## Alternatives considered

| Alternative                                 | Why not                                                                                              |
|---------------------------------------------|------------------------------------------------------------------------------------------------------|
| ASH/AWR based attribution                   | Diagnostics Pack requirement; excludes Standard Edition and creates audit exposure                     |
| Mandatory unified auditing on every table   | DBA policy and volume concerns; kept optional                                                        |
| Database triggers / logon triggers for attribution | Intrusive on production databases; owned by the platform rather than the DBA                    |
| SQL trace / 10046                           | Heavy, per-session, not continuous                                                                    |
