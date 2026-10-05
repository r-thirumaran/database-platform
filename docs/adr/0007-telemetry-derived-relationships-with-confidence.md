# ADR 0007: Relationships derived from telemetry, with explicit source and confidence

Status: accepted · Date: 2026-10

## Context

Nobody can say today which service reads or writes which table; there is no documented table
relationship map and the data dictionary only knows object-to-object dependencies, not applications.
Asking 80+ teams to declare their accesses by hand would be slow, incomplete and immediately stale.
Observations come from sources of very different quality: the gateway sees every statement with an
authenticated identity; the proxy sees connections, not statements; database views are sampled; audit
trails are exact but optional and partial.

## Decision

Model **relationships** (application → table/routine: `READS`, `WRITES`, `CALLS`) as first-class rows
derived automatically from telemetry and collector samples, each carrying:

* `source`: `GATEWAY`, `PROXY_CORRELATION`, `COLLECTOR_SESSION`, `COLLECTOR_AUDIT`, `DECLARED`;
* `confidence`: 1.0 / 0.9 / 0.6 / 1.0 / 1.0 respectively (dictionary refinement 0.8 on dependencies);
* `queryCount`, `firstSeenAt`, `lastSeenAt`, `confirmed`, and `viaRoutineId` for indirect access
  through routines and triggers.

Keep them separate from **dependencies** (object → object from the dictionary) and from human
**declarations**; never let a derived row overwrite a declared one; expand `CALLS` through the
dictionary graph so that writes via packages and triggers are visible as writes; mark rows stale after
`DBP_RELATIONSHIP_STALE_DAYS` instead of deleting them.

## Consequences

* The map builds itself from day one of Phase 0 and improves as applications move to the gateway.
* Consumers of the data (impact analysis, governance, UI) can weigh evidence instead of treating all
  edges alike; humans can confirm or declare to raise confidence to 1.0.
* Sampling bias is explicit: `COLLECTOR_SESSION` counts are "samples", not executions; rare consumers
  may be missing until declared; the playbooks say so.
* Storage grows with (applications × objects × sources), which is bounded; raw events are retained only
  for `DBP_TELEMETRY_RETENTION_HOURS`, aggregates per `sqlHash` per hour persist.
* Table names in SQL are resolved against the catalogue (default schema, synonyms); unresolved names
  create placeholder tables rather than being dropped, so nothing observed is lost.

## Alternatives considered

| Alternative                                   | Why not                                                                                                   |
|-----------------------------------------------|-----------------------------------------------------------------------------------------------------------|
| Manual declaration only                       | Incomplete and stale; no way to verify                                                                     |
| Audit trail as the only source                | Requires DBAs to enable and scope audit everywhere; volume; still no application identity without rules    |
| Static code analysis of repositories          | Misses dynamic SQL, ORMs, PL/SQL, non-Java; useful complement, not a source of truth                       |
| Single confidence-free edge set               | Forces either over-trust of samples or discarding them; impact analysis would be wrong in both directions  |
