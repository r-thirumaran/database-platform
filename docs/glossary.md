# Glossary

Terms as used consistently across the documentation, the API and the UI. Where two words are often
confused (relationship/dependency, logical/physical, owner/producer) the contrast is stated explicitly.

| Term | Definition |
|------|------------|
| **Access grant** | Control-plane record allowing one application to use one logical datasource, with limits (`maxLogicalConnections`, `maxProxyConnections`), an optional `poolModeOverride` and a `readOnly` flag. No grant → `08004`. |
| **Api key** | Bearer credential identifying an application to the gateway: `dbp_<prefix>_<secret>`. Issued per application, returned once, stored hashed. Not a database credential. |
| **Application** | A deployable unit registered in the control plane (`SERVICE`, `BATCH`, `UI`, `LEGACY`, `TOOL`) that belongs to a team and connects to datasources. Identified by api key (gateway) or identity rules (proxy, collectors). |
| **Attribution** | Mapping an observed connection or statement to an application. Sources, in precedence order: gateway, audit trail, proxy correlation, session sampling, declared. |
| **Catalogue** | The crawled inventory of physical database objects: schemas, tables, columns, routines (procedures, functions, packages, triggers, views). |
| **Collector** | Control-plane job that reads a physical database's data dictionary (`DICTIONARY`), runtime views (`RUNTIME`) or audit trail (`AUDIT`). Read-only, base-edition views only. |
| **Confidence** | Number in `0..1` attached to a relationship or dependency reflecting how it was derived (gateway 1.0, audit 1.0, proxy correlation 0.9, dictionary REFERENCES 1.0, READ/WRITE refinement 0.8, session sampling 0.6). |
| **Consumer** | Any application with a `READS`, `WRITES` or `CALLS` relationship to a table or routine, directly or via a routine. |
| **Control plane** | `dbp-control-plane`: Spring Boot service holding configuration (teams, applications, databases, credentials, datasources, grants), the metadata plane, telemetry ingestion, collectors and governance; serves the UI. Not on the data path. |
| **Correlation (proxy correlation)** | Joining a database session (`V$SESSION.PORT`, `pg_stat_activity.client_port`) with the proxy's outbound source port (`ConnectionEvent.proxyLocalPort`) to attribute sampled SQL to the proxied application. |
| **Credential** | A database account the platform itself uses (gateway pools, collectors), with a provider (`INLINE`, `ENV`, `FILE`, …), a version and a rotation timestamp. Distinct from api keys. |
| **Cursor** | Server-side (gateway-held) result set identified by `cursorId`; forward-only; keeps the session pinned while open. |
| **Datasource (logical datasource)** | A named logical entry point (`sales`) owned by a team, used in `jdbc:dbp://…/sales` and as the proxy service alias. Routes to a current physical database, with optional routing rules and a migration target. Carries the pool policy. |
| **Database (physical database)** | A concrete Oracle/PostgreSQL/SQL Server instance or PDB/database registered with host, port, service name, platform credential and collector settings. |
| **Dependency** | Static object → object edge from the data dictionary or declared: `REFERENCES`, `READS`, `WRITES`, `FOREIGN_KEY`, `TRIGGERS`, `CALLS`. Contrast with *relationship*. |
| **Discovered (placeholder) table** | Catalogue row created from telemetry for a table not yet seen by a crawl (`discovered: true`); reconciled at the next crawl. |
| **Driver (drop-in driver)** | `dbp-jdbc`: zero-dependency JDBC driver (`org.dbplatform.jdbc.DbpDriver`) speaking the wire protocol to the gateway. |
| **Engine** | `ORACLE`, `POSTGRES`, `MSSQL` (also `H2` for tests, `TCP` for raw pass-through in the proxy). |
| **Gateway** | `dbp-gateway`: server that terminates driver sessions, owns HikariCP pools per physical database, executes statements with the vendor driver, pins physical connections per transaction or session, and reports telemetry. |
| **Governance policy / violation** | Rules evaluated against relationships and ownership (`CROSS_TEAM_DIRECT_ACCESS`, `UNOWNED_TABLE`, `UNDECLARED_CONSUMER`, `WRITE_BY_NON_PRODUCER`, `DIRECT_DB_ACCESS_BYPASSING_PLATFORM`) producing violations with a status. |
| **Identity rules** | Per-application matching rules for proxy and collectors: `serviceAliases`, `programNames`, `pgApplicationNames`, `machinePatterns`, `cidrs` (precedence in that order). |
| **Impact analysis** | Report for a table, column or datasource listing direct and indirect consumers, routines, triggers, dependent views, foreign-key dependents, affected teams and a risk score. |
| **Logical connection / logical session** | One JDBC `Connection` opened by an application against the gateway (one TCP socket, one `sessionId`). Cheap; does not by itself hold a database session. |
| **Metadata plane** | The part of the control plane answering who owns, produces, consumes and depends on what: catalogue, dependencies, relationships, ownership, graph, impact. |
| **Metadata store** | The control plane's own database (PostgreSQL in production, H2 in dev). The platform's only stateful component. |
| **Migration event** | Record of a datasource switch (`fromDatabaseId`, `toDatabaseId`, `at`, `by`, `note`). |
| **Owner (owner team)** | The team accountable for a table or routine. `ownerSource`: `DECLARED`, `INFERRED` (team of the sole writer), `NONE`; `ownerConfirmed` acknowledges an inferred owner. Contrast with *producer* (an application, not a team). |
| **Physical connection** | A vendor JDBC connection in a gateway pool, i.e. one database session. Pinned to at most one logical session at a time. |
| **Pinning** | Binding a logical session to a physical connection: for one transaction (TRANSACTION mode), for the session's life (SESSION mode), and always while a cursor is open. |
| **Pool mode** | `TRANSACTION` (pin per transaction/statement; sessions are shared over time) or `SESSION` (pin for the whole logical session; no sharing). Set per datasource, overridable per grant; reported in `HELLO_OK.serverProperties.poolMode`. |
| **Pool policy** | Per-datasource pool settings: `mode`, `maxConnections`, `minIdle`, `connectionTimeoutMs`, `idleTimeoutMs`, `maxLifetimeMs`, `statementTimeoutSeconds`, `validationQuery`. |
| **Producer** | The single application that authoritatively writes a table (`producerApplicationId`), declared or inferred (sole writer). Other writers raise `WRITE_BY_NON_PRODUCER`. |
| **Proxy** | `dbp-proxy`: transparent, protocol-aware TCP proxy for Oracle TNS, PostgreSQL and TDS. Reads the connect/startup packet for identity and routing, rewrites service names, enforces quotas, relays bytes. Does not pool or multiplex. |
| **Relationship** | Runtime-observed (or declared) application → object edge: `READS`, `WRITES`, `CALLS`, with `source`, `confidence`, `queryCount`, `firstSeenAt`, `lastSeenAt`, `confirmed`, optional `viaRoutineId`. Contrast with *dependency*. |
| **Routine** | Stored code object in the catalogue: `PROCEDURE`, `FUNCTION`, `PACKAGE`, `PACKAGE_BODY`, `TRIGGER`, `VIEW`. |
| **Routing rule** | Per-datasource rule (`priority`, `applicationId` or `tag`, `databaseId`, `readOnly`, `enabled`) sending a specific application to a specific physical database; falls back to `currentDatabaseId`. |
| **Service alias** | Logical service name an application asks the proxy for: `<datasource>` or `<datasource>.<application>` (Oracle `SERVICE_NAME`, PostgreSQL database name). The proxy rewrites it to the backend's real service. |
| **Service token** | Shared secret (`DBP_SERVICE_TOKEN`, header `X-DBP-Service-Token`) protecting `/api/v1/internal/**` used by gateways and proxies. |
| **Session (database session)** | An engine-side session/process (Oracle `V$SESSION` row, PostgreSQL backend). Equals one physical connection from the gateway or one proxied client connection. |
| **Static mode** | Gateway or proxy running without a control plane from a YAML file (`DBP_GATEWAY_CONFIG`, `DBP_PROXY_CONFIG`); api keys only if the gateway file declares `applications`, telemetry without ids. Test environments only. |
| **Switch** | `POST /datasources/{id}/switch`: changes the datasource's `currentDatabaseId`; the default route for all applications without a rule. |
| **Team** | Organisational owner of applications, datasources, tables and routines; has contacts and tags. |
| **Telemetry** | Asynchronous events from components to the control plane: `QueryEvent` (gateway, per statement), `ConnectionEvent` (proxy, per connection), `PoolStats`, heartbeats. Buffered, never blocking the data path, dropped under back-pressure. |
| **Wire protocol** | The DBP v1 framed binary protocol between driver and gateway: length-prefixed, big-endian, synchronous request/response, zero dependencies. |
| **DRCP** | Oracle Database Resident Connection Pooling: server-side pool of server processes, enabled by a DBA (`DBMS_CONNECTION_POOL.START_POOL`), requested with `(SERVER=POOLED)`; works with the thin driver. Pools processes, not client connections. |
| **CMAN TDM** | Oracle Connection Manager in Traffic Director Mode (18c+): Oracle's proxy with proxy-resident connection pooling; a separately licensed product (check your licence). |
| **Shared server** | Oracle configuration where dispatchers share a pool of server processes among sessions; reduces processes, not sessions. |
| **PgBouncer / Pgpool-II** | PostgreSQL connection poolers (session/transaction/statement modes). The model the gateway's TRANSACTION mode follows for all engines. |
