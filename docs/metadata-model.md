# Metadata Model

The metadata plane answers *who owns what, who produces it, who consumes it, and what breaks if it
changes*. It is populated automatically (data dictionary crawlers, runtime telemetry) and curated by
humans (ownership, declared producers/consumers, classifications).

## Entities

```
Team ──owns──▶ Datasource ──routes-to──▶ Database ──hosts──▶ Schema ──contains──▶ Table ──has──▶ Column
  │                                          │                                   ▲
  ├──owns──▶ Table / Routine                  └──hosts──▶ Routine ──REFERENCES/READS/WRITES──┘
  │                                                        ▲   ▲
  └──runs──▶ Application ──READS/WRITES──▶ Table           │   │
                 │                                         │   │
                 └──CALLS──▶ Routine ──────────────────────┘   └──TRIGGERS── Table
Table ──FOREIGN_KEY──▶ Table
Table ──MIGRATES_TO──▶ Table (on another Database)
Datasource ──MIGRATES_TO──▶ Database (target)
```

| Entity       | Source of truth                                    | Notes |
|--------------|----------------------------------------------------|-------|
| Team         | humans (API/UI/import)                             | contacts, tags |
| Application  | humans; identity rules for attribution             | kind SERVICE/BATCH/UI/LEGACY/TOOL |
| Database     | humans (connection details), crawlers (engine version) | credential for platform use |
| Datasource   | humans                                             | logical name used in `jdbc:dbp://…/<datasource>` and as proxy service alias |
| Table/Column | crawlers; `discovered` placeholders from telemetry | owner/producer/classification curated |
| Routine      | crawlers                                           | procedures, functions, packages, triggers, views |
| Dependency   | crawlers (DICTIONARY), humans (DECLARED)           | object → object |
| Relationship | telemetry (GATEWAY/PROXY_CORRELATION/COLLECTOR_*), humans (DECLARED) | application → object, with counts and recency |
| AccessGrant  | humans                                             | which app may use which datasource and limits |
| Violation    | governance job                                     | derived from relationships vs. ownership/grants |

## Ownership

* Ownership is assigned per **table** or per **routine**; a schema-level bulk assignment is a
  convenience that writes table-level rows.
* `ownerSource`: `DECLARED` (a human set it), `INFERRED` (platform suggestion: the team of the only
  writer in the window), `NONE`.
* `ownerConfirmed` lets a team acknowledge an inferred owner without re-entering it.

## Producer / Consumer

* **Producer**: the application that authoritatively writes a table. One per table
  (`producerApplicationId`), declared or inferred (sole writer).
* **Consumers**: every application with a READS/WRITES/CALLS relationship to the table, direct or via
  a routine. Writers that are not the producer are flagged by the `WRITE_BY_NON_PRODUCER` policy.
* **Cross-team access**: a relationship from an application whose team differs from the table's
  owner team. Allowed when a DECLARED relationship (or a confirmed one) exists; otherwise the
  `CROSS_TEAM_DIRECT_ACCESS` / `UNDECLARED_CONSUMER` policies raise violations.

## Impact analysis

For a table (or column) the control plane walks:

1. direct relationships (apps reading/writing it),
2. routines and triggers that reference it (dictionary), then the applications calling those
   routines (indirect consumers),
3. views built on it and the consumers of those views,
4. foreign keys pointing at it,
5. migration links,

and aggregates the set of affected applications and **teams**, with query counts and recency, into a
risk score (`0..1`) built from: number of consuming teams, write-path complexity (triggers/routines),
query volume, classification, and whether the table is mid-migration.

## Confidence and recency

Relationships carry `source`, `confidence`, `queryCount`, `firstSeenAt`, `lastSeenAt`. The UI shows
stale relationships (no activity for `DBP_RELATIONSHIP_STALE_DAYS`, default 30) differently and the
`unused tables` report lists tables with no runtime access in the window.
