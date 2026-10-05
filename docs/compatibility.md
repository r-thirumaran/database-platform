# Compatibility: what works through the gateway and driver

This page is derived from the wire protocol ([wire-protocol.md](wire-protocol.md)): if a JDBC feature
has no message in the protocol, the driver cannot offer it. Unsupported calls fail with
`SQLFeatureNotSupportedException` (SQLState `0A000`) rather than silently degrading, except where
noted. Statements in the "verify" column should be checked against the driver and gateway READMEs of
the version you deploy.

Related: [rollout.md](rollout.md#phase-2--drop-in-driver-adoption), [operations.md](operations.md#troubleshooting),
[faq.md](faq.md).

## Supported JDBC surface

| Area                               | Supported                                                                                                                       | Protocol basis                                       |
|------------------------------------|---------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------|
| `DriverManager` / `DataSource`     | `jdbc:dbp://host[:port][,host2]/<datasource>?…`; `user`/`password` mapping (`password` = api key when `apiKey` absent); multi-host connect-time failover | HELLO, section 8 |
| `Statement`                        | `execute`, `executeQuery`, `executeUpdate`, `executeLargeUpdate`, `getMoreResults`/`getUpdateCount`, `setMaxRows`, `setFetchSize`, `setQueryTimeout`, `addBatch`/`executeBatch`, warnings | EXECUTE (`kind = 0`), `ExecOptions`, EXECUTE_BATCH |
| `PreparedStatement`                | All `setXxx` for the value tags (primitives, `BigDecimal`, `String`, `byte[]`, `java.sql` date/time, `java.time` `LocalDate/LocalTime/LocalDateTime/OffsetDateTime/OffsetTime`, `setNull(i, type)`, `setObject`), batches with parameter sets, `clearParameters`, `getParameterMetaData().getParameterCount()` (when the engine reports it) | PREPARE / EXECUTE (`kind = 1`), `Value` tags |
| `CallableStatement`                | Positional `{call pkg.proc(?, ?)}` and `{? = call f(?)}`; `registerOutParameter(index, type[, scale|typeName])`; OUT and INOUT parameters; `getXxx(index)` on OUT values; Oracle `REF CURSOR` OUT (see below) | EXECUTE (`kind = 2`), `OutParam[]`, OUT_PARAMS |
| `ResultSet`                        | `TYPE_FORWARD_ONLY`, `CONCUR_READ_ONLY`; all `getXxx` with the standard conversions (`getInt` on `DECIMAL`, `getString` on anything, `getObject(i, LocalDate.class)` etc.); `getMetaData()`; `wasNull`; multiple open result sets per session (up to `dbp.maxOpenCursorsPerSession`, 256) | RESULT_SET_HEADER, ROWS, FETCH, `ColumnMeta` |
| Transactions                       | `setAutoCommit`, `commit`, `rollback`, savepoints (`setSavepoint`, `rollback(sp)`, `releaseSavepoint`), `setTransactionIsolation`, `setReadOnly`              | 0x20–0x26 |
| Session settings                   | `setSchema`, `setCatalog`, `setClientInfo` (remembered and replayed on re-pin), `setNetworkTimeout`                                  | 0x27–0x2A |
| Generated keys                     | `prepareStatement(sql, RETURN_GENERATED_KEYS)` and `prepareStatement(sql, String[] columns)`; `getGeneratedKeys()`                   | PREPARE flags, GENERATED_KEYS |
| `DatabaseMetaData`                 | All result-set methods listed in the protocol (`getTables`, `getColumns`, `getPrimaryKeys`, `getImportedKeys`, `getProcedures`, `getFunctions`, `getTypeInfo`, …); scalar methods answered from `HELLO_OK.serverProperties` (product name/version, identifier quoting, `supportsXxx`, …) | METADATA, section 4.5 |
| Multiple result sets               | In the order produced by the physical driver (SQL Server batches, procedures returning several cursors)                             | EXECUTE response sequence |
| Warnings                           | `getWarnings` on statement/result after execution                                                                                  | EXECUTE_DONE warnings |
| `Connection.isValid`               | Implemented with PING                                                                                                              | PING/PONG |
| LOBs                               | `getString/getCharacterStream` on CLOB, `getBytes/getBinaryStream` on BLOB, `setString/setBytes/setCharacterStream/setBinaryStream` (materialised) within the frame limit | STRING / BYTES values |
| TLS                                | `ssl=true`                                                                                                                         | section 7 |

### Oracle REF CURSOR OUT parameters

Register with the vendor-neutral `java.sql.Types.REF_CURSOR` (JDBC 4.2) rather than
`oracle.jdbc.OracleTypes.CURSOR` (which would require the Oracle driver on the classpath; its numeric
value `-10` is also accepted by the gateway). Per [wire-protocol.md §4.7](wire-protocol.md#47-cursor-typed-out-parameters-oracle-sys_refcursor-postgresql-refcursor)
the gateway streams the cursor as a result item (`RESULT_SET_HEADER` + first `ROWS`) before
`OUT_PARAMS`, and the OUT entry carries the item's `cursorId`; `CallableStatement.getObject(index)`
returns a forward-only `ResultSet` bound to that cursor, and `getMoreResults()` skips cursor items that
belong to OUT parameters. The same mechanism serves PostgreSQL `refcursor` OUT parameters (which
require a transaction to be open — `setAutoCommit(false)` — because PostgreSQL closes non-holdable
cursors at commit).

## Not supported in the POC

| Feature                                                          | Behaviour                                                                                       | Why / alternative                                                                                   |
|------------------------------------------------------------------|-------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| Scrollable result sets (`TYPE_SCROLL_*`)                          | `createStatement(TYPE_SCROLL_INSENSITIVE, …)` → `0A000`, or downgraded to forward-only with a warning (verify) | Cursors are server-side and forward-only on the wire (FETCH only moves forward)                     |
| Updatable result sets (`CONCUR_UPDATABLE`)                        | `0A000`                                                                                         | No `updateRow`/`insertRow` messages. Use explicit `UPDATE` statements                               |
| Streaming LOBs beyond the frame limit                             | Values larger than `maxFrameBytes` (64 MiB default) fail with `HY000`/`ProtocolException`; a row batch of `fetchSize` rows must also fit | Values are materialised in the frame. Lower `fetchSize` for wide rows; raise `maxFrameBytes` on both sides; or keep very large LOBs on a proxy path |
| `Blob`/`Clob` locator objects (`createBlob`, `Blob.setBinaryStream`, `Clob.position`) | Client-side materialised implementations at best (verify); locator semantics (`length` without transfer, partial reads) not available | No locator messages                                                                                 |
| Vendor unwrapping (`unwrap(OracleConnection.class)`, `PGConnection`, `SQLServerConnection`) | `isWrapperFor` → `false`, `unwrap` → `SQLException`                                   | The physical connection lives in the gateway. Applications that need `oracle.sql.ARRAY`, `STRUCT`, `OracleTypes.PLSQL_INDEX_TABLE`, `setPlsqlIndexTable`, `setFormOfUse`, `OracleConnection.openProxySession`, `PGCopyManager`, `SQLServerBulkCopy` must stay on the proxy path |
| `createArrayOf`, `createStruct`, `Array`/`Struct` parameters/results | `0A000`; array/struct columns arrive as `STRING` via `toString()`                             | No ARRAY/STRUCT value tags in v1                                                                     |
| Named parameters on `CallableStatement` (`setString("name", …)`)  | `0A000`                                                                                         | `OutParam` and `params` are positional only. Use positional binding                                 |
| XA / distributed transactions (`XADataSource`, `XAConnection`)    | Not provided                                                                                    | Pinning is per logical session and transaction; 2PC would need the gateway to be an XA resource manager |
| `Statement.cancel()`                                              | Best effort: the protocol is strictly synchronous on one socket with no out-of-band channel, so a cancel cannot reach the gateway while an EXECUTE is in flight; `setQueryTimeout` (→ `HY008`) is the reliable mechanism | Future: cancel through the admin channel                                                         |
| Holdable cursors (`ResultSet.HOLD_CURSORS_OVER_COMMIT`) | Not negotiable per statement. A COMMIT/ROLLBACK with open cursors is executed on the physical connection and the session stays pinned until the cursors close; whether the cursor survives is decided by the physical driver — Oracle keeps it, PostgreSQL closes non-holdable cursors (next `FETCH` returns an empty `last` batch or an `ERROR`) — see [wire-protocol.md §4.8](wire-protocol.md#48-pinning-and-cursors-across-statements) | Read everything before committing, or commit after closing result sets                         |
| `Statement.setEscapeProcessing`, `setCursorName`, `getRef`, `getRowId`, `getSQLXML`, `getNClob` as objects | Escape processing is forwarded as part of SQL; others `0A000` or `STRING` | Rarely used                                                                                     |
| Driver-level statement caching                                    | None in the driver; the gateway caches physical `PreparedStatement`s per (session, statementId) while pinned | Rely on the gateway and the physical driver's implicit cache (`oracle.jdbc.implicitStatementCacheSize` in `jdbcProperties`) |
| Connection-level `Properties` that are vendor specific            | Ignored (unknown HELLO properties are dropped)                                                   | Put them in `Database.jdbcProperties` on the control plane; they then apply to the whole pool        |
| `DatabaseMetaData` scalar methods not in `serverProperties`       | Driver defaults                                                                                 | The list in section 4.5 covers what frameworks commonly read                                         |

## Pool-mode semantics

The pool mode is set per datasource (`poolPolicy.mode`) and overridable per grant
(`poolModeOverride`); the applied mode is reported in `HELLO_OK.serverProperties.poolMode`.

| Behaviour                                                | `TRANSACTION`                                                                                               | `SESSION`                                                   |
|----------------------------------------------------------|-------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------|
| When a physical connection is held                        | From the first statement after `setAutoCommit(false)` (or for the duration of one autocommit statement) until COMMIT/ROLLBACK and all cursors are closed | From the first statement until `Connection.close()`          |
| Physical connections per logical session                  | 0 most of the time; ≤ 1 while pinned                                                                        | Exactly 1 after first use                                   |
| Reduction of database sessions                            | Yes: proportional to the fraction of time sessions are idle between transactions                            | None                                                        |
| PL/SQL package state (`PKG.g_var`), `DBMS_SESSION` context | Lost between transactions (next transaction may land on another physical connection)                        | Preserved                                                    |
| Global temporary tables                                   | `ON COMMIT DELETE ROWS`: fine. `ON COMMIT PRESERVE ROWS`: rows visible only inside the pinning transaction; gone afterwards | Preserved                                                    |
| PostgreSQL `TEMP` tables                                  | Only within the transaction that created them (`ON COMMIT DROP` recommended)                                | Preserved                                                    |
| `ALTER SESSION` / `SET` issued as SQL by the application   | Applies to the current physical connection only; **not** replayed                                            | Preserved                                                    |
| `setSchema`, `setClientInfo`, isolation, read-only via JDBC APIs | Remembered by the gateway and replayed on every re-pin                                               | Applied once                                                 |
| `sequence.CURRVAL`                                        | Valid only in the same transaction as `NEXTVAL`                                                              | Valid for the session                                       |
| `DBMS_OUTPUT`, `DBMS_APPLICATION_INFO` read-back           | Only within the pinned transaction                                                                           | Session-wide                                                |
| Session-level advisory locks (`pg_advisory_lock`), `LISTEN/NOTIFY` | Unsafe (lock may be released on another physical connection); use `pg_advisory_xact_lock`            | Work as with a direct connection                             |
| Prepared statements                                       | Physical `PreparedStatement` re-created per pin (cached while pinned)                                        | Cached for the session                                      |
| Open cursor at COMMIT                                      | Keeps the pin until the cursor is closed                                                                     | n/a                                                          |
| Who should use it                                          | Stateless services, ORMs, short transactions                                                                 | Legacy apps, migration tools, anything relying on the rows above |

Mixed estates are normal: one datasource in TRANSACTION mode for services and a second logical
datasource (same physical database, SESSION mode) for the legacy application, each with its own pool.

## Framework notes

| Framework                   | Notes                                                                                                                                                      |
|-----------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Spring Boot `DataSource`    | Set `spring.datasource.driver-class-name=org.dbplatform.jdbc.DbpDriver` (Spring cannot infer a driver from `jdbc:dbp://`). `username` is informational; `password` carries the api key. `spring.sql.init.platform` must be set by hand if used. |
| HikariCP                    | Works unchanged. `connectionTestQuery` unnecessary (`isValid` is implemented). Keep `maxLifetime` ≤ the gateway's view of session limits. Pool size now bounds *logical* sessions; `AccessGrant.maxLogicalConnections` must be ≥ instances × pool size. |
| Spring `JdbcTemplate` / `NamedParameterJdbcTemplate` | Fully supported (positional binding under the hood). `SimpleJdbcCall` uses `DatabaseMetaData.getProcedureColumns` to infer parameters — supported, but set `withoutProcedureColumnMetaAccess()` for packages where Oracle metadata is slow. |
| JPA / Hibernate             | Set the dialect explicitly: `spring.jpa.database-platform` / `hibernate.dialect` (`org.hibernate.dialect.OracleDialect`, `PostgreSQLDialect`, `SQLServerDialect`). Hibernate 6 can auto-detect from `databaseProductName`/version, which the gateway reports for the **physical** engine the session was routed to, so it works at startup — but it then silently depends on routing; an explicit value documents that dependency and fails loudly when the two disagree. `hibernate.jdbc.batch_size` works (EXECUTE_BATCH). Identity/sequence generators work; `SEQUENCE` with `increment_size` pooled optimiser is preferred over `CURRVAL`-based tricks. Entity graphs with LOBs: see the frame limit. |
| MyBatis                     | Works with `POOLED`/`UNPOOLED` data sources or an external Hikari. `useGeneratedKeys` with `keyColumn` maps to `generatedKeyColumns` (required on Oracle to get the key instead of a ROWID). Mapper `resultType` streaming (`ResultHandler`) is fine (forward-only). |
| Flyway                      | Supply a `DataSource` (not URL/driver auto-detection; Flyway's URL-based driver detection does not know `jdbc:dbp`). Database type is detected from `getDatabaseProductName()`, which is the physical engine. Use **SESSION mode** for the migration datasource/grant: Flyway on PostgreSQL takes a session-level advisory lock, and DDL+DML in one migration must stay on one physical connection; Oracle DDL auto-commits, which un-pins in TRANSACTION mode. |
| Liquibase                   | Same as Flyway: pass a `java.sql.Connection`/`DataSource`; locking uses the `DATABASECHANGELOGLOCK` table (fine in both modes) but prefer SESSION mode for the same DDL reasons. |
| jOOQ                        | Set `SQLDialect` explicitly (same reasoning as Hibernate). `fetchLazy` is forward-only, fine.                                                                 |
| Quartz, Spring Batch        | Work; Spring Batch's `JdbcCursorItemReader` holds a cursor open across chunks (a long pin) — acceptable, but size the pool for it. `JdbcPagingItemReader` is pin-friendly. |
| Oracle UCP, `OracleDataSource` | Not applicable: replace by Hikari/any generic pool with the DBP driver.                                                                                  |
| Connection validation in pools (`isValid` vs test query) | `isValid` → PING (no physical round-trip); a test query (`SELECT 1 FROM DUAL`) pins and un-pins a physical connection each time — avoid in TRANSACTION mode. |

## Driver vs proxy: decision table

| Application characteristic                                               | Driver (`jdbc:dbp://`)                 | Proxy (vendor driver, URL change only)        |
|--------------------------------------------------------------------------|----------------------------------------|-----------------------------------------------|
| Java with standard JDBC (ORM, JdbcTemplate, MyBatis)                      | **Yes**                                | Possible, but no pooling benefit               |
| Non-Java client (Python, .NET, SQL*Plus, ETL tools, BI)                   | No (Java driver only in the POC)        | **Yes**                                        |
| Uses vendor JDBC extensions (`oracle.sql.*`, `PGCopyManager`, bulk copy)  | No                                     | **Yes**                                        |
| Needs scrollable/updatable result sets, XA, LOB locators                  | No                                     | **Yes**                                        |
| Relies on session state between transactions                              | Yes, with SESSION mode (no session reduction) | Yes                                     |
| Needs fewer database sessions                                            | **Yes** (TRANSACTION mode)             | No (1 client connection = 1 DB session)        |
| Needs central credentials / no DB password in the app                    | **Yes** (api key)                      | No (the app still authenticates to the DB)     |
| Needs per-statement attribution with tables                              | **Yes** (gateway telemetry, 1.0)       | Partial (`PROXY_CORRELATION`, 0.9, sampled)    |
| Needs routing between engines (Oracle → PostgreSQL migration)            | **Yes**                                | No (protocol is engine-specific); only service-name rewriting within an engine |
| Needs quotas per application                                             | Yes (`maxLogicalConnections`)          | Yes (`maxProxyConnections`)                    |
| Needs TLS from the client with the client talking to the DB protocol     | n/a                                    | Oracle native encryption passes through; TCPS/PostgreSQL SSL/TDS encryption limit identity (see [security.md](security.md#tls)) |
| Cannot change the classpath (vendor-packaged application)                | No                                     | **Yes**                                        |
| Latency budget extremely tight                                           | One extra hop + frame encoding (to be measured) | One extra hop, byte relay (to be measured) |

Default guidance: every Java service goes to the driver; everything else and every exception goes
through the proxy; nothing connects directly.
