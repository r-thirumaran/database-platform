# Telemetry Events

All platform components report to the control plane with small JSON documents, batched into arrays
and POSTed to `/api/v1/internal/telemetry/*` (see `docs/control-plane-api.md`). The classes live in
`dbp-common` (`org.dbplatform.common.telemetry`) and are serialised with Jackson. Field names below
are exact.

Components buffer events in memory (bounded queue, default 50 000) and flush every
`DBP_TELEMETRY_FLUSH_MS` (default 2000) or when 500 events accumulate. If the control plane is
unreachable the component keeps serving traffic and drops the oldest events once the queue is full
(counting drops in its own metrics). Telemetry must never block the data path.

## QueryEvent (gateway → control plane)

```json
{
  "eventId": "01J…",                     // ULID/UUID, idempotency key
  "timestamp": "2026-10-05T12:00:00.123Z",
  "gatewayId": "gw-1",
  "sessionId": "s-123",
  "applicationId": "…",                  // null in static mode
  "application": "orders-service",
  "team": "sales-platform",              // may be null
  "datasource": "sales",
  "databaseId": "…",                     // null in static mode
  "engine": "ORACLE",
  "sqlHash": "9f2b…",                    // sha-256 (hex) of sqlNormalized
  "sqlNormalized": "SELECT … FROM sales.customer WHERE id = ?",   // literals replaced by ?, whitespace collapsed, max 4000 chars
  "operation": "SELECT | INSERT | UPDATE | DELETE | MERGE | CALL | DDL | TXN | OTHER",
  "tables": [ { "schema": "SALES", "name": "CUSTOMER", "access": "READ | WRITE" } ],
  "routines": [ { "schema": "SALES", "name": "ORDER_PKG.PLACE_ORDER" } ],
  "columns": [ "CUSTOMER.EMAIL" ],       // best effort, may be empty
  "durationMs": 31,
  "rows": 1,                             // rows fetched/affected when known, else -1
  "success": true,
  "sqlState": null,
  "errorCode": 0,
  "errorMessage": null,                  // truncated to 500 chars
  "pinned": false,                       // executed inside a pinned transaction/session
  "poolMode": "TRANSACTION",
  "clientInfo": { "ApplicationName": "…", "module": "…", "action": "…" }
}
```

Table and routine names are reported **as written in the SQL** (schema may be null when the SQL is
unqualified); the control plane resolves them against the catalogue of the target database using the
session's default schema (`defaultSchema` is included when known as `"defaultSchema": "SALES"`).

## ConnectionEvent (proxy → control plane)

```json
{
  "eventId": "…",
  "timestamp": "…",
  "proxyId": "proxy-1",
  "eventType": "OPEN | CLOSE | REFUSED | BACKEND_FAILED",
  "listener": "oracle-main",
  "engine": "ORACLE | POSTGRES | MSSQL | TCP",
  "connectionId": "c-42",                // stable across OPEN/CLOSE of one connection
  "clientAddr": "10.20.3.4",
  "clientPort": 51234,
  "proxyLocalAddr": "10.30.0.9",         // proxy's source address toward the backend
  "proxyLocalPort": 40321,               // proxy's source port toward the backend — joins V$SESSION.PORT / pg_stat_activity.client_port
  "backendHost": "oracle",
  "backendPort": 1521,
  "requestedService": "sales.orders-service",   // what the client asked for (Oracle SERVICE_NAME/SID, PG database)
  "resolvedService": "FREEPDB1",         // what the backend was asked for after rewriting
  "applicationId": "…", "application": "orders-service",  // resolved identity, may be null/"unknown"
  "identitySource": "SERVICE_ALIAS | PROGRAM | APPLICATION_NAME | MACHINE | CIDR | NONE",
  "datasourceId": "…", "datasource": "sales",
  "program": "JDBC Thin Client",         // Oracle CID PROGRAM / PG application_name
  "clientHost": "orders-7f9c",           // Oracle CID HOST / PG none
  "osUser": "app",                       // Oracle CID USER
  "dbUser": "SALES_APP",                 // PG startup user; Oracle: null (not visible before auth)
  "openedAt": "…", "closedAt": null,
  "durationMs": 0,
  "bytesIn": 0, "bytesOut": 0,
  "reason": null                         // REFUSED/BACKEND_FAILED/CLOSE reason text (e.g. "quota exceeded: orders-service/sales 20/20")
}
```

## PoolStats (gateway → control plane)

```json
{ "timestamp": "…", "gatewayId": "gw-1", "datasource": "sales", "datasourceId": "…", "databaseId": "…",
  "engine": "ORACLE", "active": 12, "idle": 4, "waiting": 0, "total": 16, "max": 40,
  "logicalSessions": 180, "pinnedSessions": 12, "credentialVersion": 3 }
```

## Heartbeat (gateway/proxy → control plane)

```json
{ "componentType": "GATEWAY | PROXY", "componentId": "gw-1", "version": "0.1.0", "host": "gw-1.internal",
  "startedAt": "…", "configVersion": 17,
  "stats": { "logicalSessions": 180, "physicalConnections": 61, "eventsDropped": 0,
             "liveConnections": [ ConnectionEventLike… ] } }
```
Proxies include a snapshot of live connections (max 2000) in `stats.liveConnections` so the UI can
show them without a second channel.

## Derivation rules (control plane)

| Observation                                                  | Derived                                                 | source                 |
|--------------------------------------------------------------|---------------------------------------------------------|------------------------|
| QueryEvent SELECT on table T by application A                | Relationship A READS T                                   | GATEWAY                |
| QueryEvent INSERT/UPDATE/DELETE/MERGE on T by A              | Relationship A WRITES T                                  | GATEWAY                |
| QueryEvent CALL routine R by A                               | Relationship A CALLS R, plus A READS/WRITES T for every T in R's transitive dictionary dependencies (`viaRoutineId = R`) | GATEWAY |
| V$SESSION sample: session with PORT p matches ConnectionEvent.proxyLocalPort p & SQL_ID s; V$SQL_PLAN(s) touches T | Relationship A READS/WRITES T | PROXY_CORRELATION |
| V$SESSION sample: PROGRAM/MACHINE matches application identity rules; SQL_ID → tables | Relationship A READS/WRITES T              | COLLECTOR_SESSION      |
| UNIFIED_AUDIT_TRAIL row: CLIENT_PROGRAM_NAME/USERHOST → A; OBJECT → T; ACTION → READ/WRITE | Relationship A READS/WRITES T          | COLLECTOR_AUDIT        |
| DBA_DEPENDENCIES: routine R references T                     | Dependency R REFERENCES T (READS/WRITES when DBA_SOURCE parse is conclusive) | DICTIONARY |
| DBA_TRIGGERS: trigger G on T                                 | Dependency T TRIGGERS G; G's dependencies expanded      | DICTIONARY             |
| DBA_CONSTRAINTS type R: T1 → T2                              | Dependency T1 FOREIGN_KEY T2                            | DICTIONARY             |
| Table written only by application A over the window          | `producerApplicationId` suggestion (`ownerSource = INFERRED`, never overwrites a DECLARED producer) | derived |

Confidence: `GATEWAY` and `COLLECTOR_AUDIT` = 1.0, `PROXY_CORRELATION` = 0.9, `COLLECTOR_SESSION`
= 0.6 (sampling can miss short statements), `DICTIONARY` = 1.0 for REFERENCES and 0.8 for the
READS/WRITES refinement.
