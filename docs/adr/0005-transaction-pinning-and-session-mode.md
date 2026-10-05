# ADR 0005: Transaction pinning with an explicit SESSION mode

Status: accepted · Date: 2026-10

## Context

The gateway's reason to exist is to let many logical sessions share fewer physical connections. The
sharing granularity determines both the session reduction and the risk of breaking applications that
rely on server-side session state (PL/SQL package variables, temporary tables, `ALTER SESSION`,
`CURRVAL`, `DBMS_OUTPUT`, session-level advisory locks). PgBouncer has the same trade-off and offers
session / transaction / statement modes.

## Decision

Two **pool modes**, set per logical datasource and overridable per access grant:

* **`TRANSACTION`** (default): a logical session acquires ("pins") a physical connection at the first
  statement of a transaction — or for a single autocommit statement — and releases it at COMMIT/ROLLBACK
  once no cursor remains open. Session settings issued through JDBC APIs (`setSchema`, `setClientInfo`,
  isolation, read-only, catalog, network timeout) are remembered by the gateway and replayed on every
  new pin, so they survive un-pinning.
* **`SESSION`**: the physical connection is pinned from first use until the logical session closes.
  Nothing is shared; the application gets identity, credentials, telemetry and quotas only.

Statement-level pooling (release after every statement even inside a transaction) is deliberately not
offered. The applied mode is reported to the driver in `HELLO_OK.serverProperties.poolMode` and in
every `QueryEvent` (`poolMode`, `pinned`).

## Consequences

* Session reduction is proportional to idle time between transactions, which for typical services is
  most of the time. For SESSION-mode consumers there is no reduction (documented in capacity planning).
* The semantics table in [compatibility.md](../compatibility.md#pool-mode-semantics) is part of the
  contract: anything that only works within a pin is listed there.
* `ALTER SESSION`/`SET` sent as SQL is not replayed (the gateway does not interpret SQL for this);
  applications must use the JDBC APIs or SESSION mode.
* A transaction that is left open (no commit) holds a physical connection indefinitely; the gateway
  exposes pinned sessions and their age on its admin port and `statementTimeoutSeconds` bounds
  individual statements, but idle-in-transaction detection is operational (alerts), not automatic.
* Physical `PreparedStatement`s are created lazily at EXECUTE on the currently pinned connection and
  cached only while pinned (the PREPARE message does not touch the database), so the vendor driver's
  implicit statement cache does the long-term work.
* Mixed estates are expected: the same physical database can be behind a TRANSACTION-mode datasource
  for services and a SESSION-mode datasource for the legacy application.

## Alternatives considered

| Alternative                                    | Why not                                                                                                |
|------------------------------------------------|--------------------------------------------------------------------------------------------------------|
| Session mode only                              | No session reduction; the platform would not solve the primary problem                                 |
| Statement mode                                 | Breaks multi-statement transactions; only safe for autocommit read-only traffic, which TRANSACTION mode already handles as one-statement pins |
| Automatic detection of session-state use       | Would require interpreting every SQL statement and PL/SQL call for side effects; false negatives corrupt behaviour silently. Telemetry instead flags candidates (`ALTER SESSION`, `CURRVAL`, temp tables) for humans to decide |
| Replaying `ALTER SESSION` statements on re-pin | Partial solution with surprising failure modes (order, interactions with `DBMS_SESSION`); SESSION mode is the honest answer |
