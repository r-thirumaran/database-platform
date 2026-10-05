# DBP Wire Protocol v1

The wire protocol connects the **drop-in JDBC driver** (`dbp-jdbc`) to the **gateway** (`dbp-gateway`).
It is implemented once, in the dependency-free module `dbp-protocol` (`org.dbplatform.protocol`),
and used by both sides. **This document is the contract.** The driver and the gateway are built by
different people; if something here is ambiguous, fix the document first.

Design goals:

* Zero third-party dependencies (the driver jar is dropped into arbitrary application classpaths).
* One TCP connection per logical JDBC connection. Strictly **synchronous request/response**:
  the client sends one request frame and reads frames until the *terminal* response frame arrives.
  There are no request ids and no unsolicited server frames.
* Big-endian, length-prefixed frames. Max frame size is 64 MiB by default (configurable on both
  sides via `dbp.maxFrameBytes`).
* Carry JDBC semantics faithfully (statements, prepared statements, callable statements with OUT
  parameters, result sets with server-side cursors, batches, transactions, savepoints, metadata).

## 1. Framing

```
Frame := u32 length | u8 type | payload[length - 1]
```

* `length` counts the type byte plus the payload (so an empty payload frame has length 1).
* `type` is one of the codes in section 4.
* Multi-byte integers are big-endian two's complement (`DataInput`/`DataOutput` semantics).

Primitive encodings used in payloads:

| Name      | Encoding                                                                  |
|-----------|---------------------------------------------------------------------------|
| `u8`      | 1 byte unsigned                                                           |
| `bool`    | `u8`, 0 = false, 1 = true                                                 |
| `i16/i32/i64` | 2/4/8 bytes signed big-endian                                          |
| `f32/f64` | IEEE 754, 4/8 bytes (`Float.floatToIntBits`, `Double.doubleToLongBits`)  |
| `string`  | `i32 byteLength` + UTF-8 bytes. `byteLength = -1` encodes `null`.         |
| `bytes`   | `i32 length` + raw bytes. `length = -1` encodes `null`.                   |
| `map`     | `i32 count` + `count` × (`string key`, `string value`)                    |
| `string[]`| `i32 count` + `count` × `string`                                          |
| `Value`   | tagged value, see section 2                                               |
| `Value[]` | `i32 count` + `count` × `Value`                                           |

## 2. Values

```
Value := u8 tag | data
```

| tag | name          | data                                              | Java type on the driver side            |
|----:|---------------|---------------------------------------------------|-----------------------------------------|
| 0   | NULL          | –                                                 | `null`                                  |
| 1   | BOOLEAN       | `bool`                                            | `Boolean`                               |
| 2   | BYTE          | `i8`                                              | `Byte`                                  |
| 3   | SHORT         | `i16`                                             | `Short`                                 |
| 4   | INT           | `i32`                                             | `Integer`                               |
| 5   | LONG          | `i64`                                             | `Long`                                  |
| 6   | FLOAT         | `f32`                                             | `Float`                                 |
| 7   | DOUBLE        | `f64`                                             | `Double`                                |
| 8   | DECIMAL       | `string` (`BigDecimal.toPlainString()`)           | `BigDecimal`                            |
| 9   | STRING        | `string`                                          | `String` (also used for CLOB, NCLOB, UUID, XML, JSON, INTERVAL, unknown types) |
| 10  | BYTES         | `bytes`                                           | `byte[]` (also used for BLOB)           |
| 11  | DATE          | `i64 epochDay`                                    | `java.sql.Date` / `LocalDate`           |
| 12  | TIME          | `i64 nanoOfDay`                                   | `java.sql.Time` / `LocalTime`           |
| 13  | TIMESTAMP     | `i64 epochSecond`, `i32 nano` (local date-time, no zone: encode as if the `LocalDateTime` were UTC) | `java.sql.Timestamp` / `LocalDateTime` |
| 14  | TIMESTAMP_TZ  | `i64 epochSecond`, `i32 nano`, `i32 offsetSeconds`| `OffsetDateTime`                        |
| 15  | TIME_TZ       | `i64 nanoOfDay`, `i32 offsetSeconds`              | `OffsetTime`                            |
| 16  | TYPED_NULL    | `i32 jdbcType`                                    | parameters only (`setNull(i, type)`)    |

Rules:

* The **server** chooses the tag from the column's JDBC type when encoding result rows
  (`NUMERIC/DECIMAL → DECIMAL`, `INTEGER → INT`, `BIGINT → LONG`, `CHAR/VARCHAR/CLOB/… → STRING`,
  `BLOB/BINARY → BYTES`, `DATE → DATE`, `TIME → TIME`, `TIMESTAMP → TIMESTAMP`,
  `TIMESTAMP_WITH_TIMEZONE → TIMESTAMP_TZ`, `BOOLEAN/BIT → BOOLEAN`, everything else → `STRING`
  via `getString`, or `getObject().toString()`).
  Oracle `NUMBER` without scale is reported as `NUMERIC` and therefore travels as `DECIMAL`; the
  driver's `getInt/getLong/getDouble` must convert from `BigDecimal`.
* The **client** chooses the tag from the Java object passed to `setXxx` (`setInt → INT`,
  `setString → STRING`, `setBigDecimal → DECIMAL`, `setTimestamp → TIMESTAMP`, `setObject` by
  runtime type, `setNull(i, type) → TYPED_NULL`). Unknown objects are sent as `STRING` using
  `toString()`.
* Result-set access methods must perform the usual JDBC conversions on the client side
  (e.g. `getString` on an `INT` value returns `"42"`, `getInt` on a `DECIMAL` returns `intValue()`,
  `getTimestamp` on a `DATE` works, `getObject(i, LocalDate.class)` etc.).

## 3. Session lifecycle

```
client                         gateway
  | ---- HELLO ----------------> |
  | <--- HELLO_OK | ERROR ------ |
  | ---- (requests) -----------> |
  | <--- (responses) ----------- |
  | ---- CLOSE ----------------> |
  | <--- OK -------------------- |
```

The gateway may close the socket at any time after sending an `ERROR` with `fatal = 1`. The
driver maps that to `SQLNonTransientConnectionException` (SQLState `08xxx`) and marks the
`Connection` closed.

## 4. Message catalogue

Client → server types use `0x01–0x3F`; server → client types use `0x40–0x7F`.

### 4.1 Session control

| code | name           | payload                                                                         | terminal response            |
|-----:|----------------|---------------------------------------------------------------------------------|------------------------------|
| 0x01 | HELLO          | `i16 protocolVersion (=1)`, `string clientName`, `string clientVersion`, `map properties` | HELLO_OK or ERROR   |
| 0x02 | PING           | –                                                                               | PONG                         |
| 0x03 | CLOSE          | –                                                                               | OK                           |

HELLO `properties` keys (all strings):

| key             | required | meaning                                                                 |
|-----------------|----------|-------------------------------------------------------------------------|
| `datasource`    | yes      | logical datasource name, taken from the JDBC URL path                   |
| `apiKey`        | no*      | application credential issued by the control plane (`dbp_<prefix>_<secret>`). *Required unless the gateway runs in `static` auth mode. |
| `application`   | no       | application name hint (used only when the gateway runs without a control plane) |
| `user`          | no       | logical user name passed to `DriverManager.getConnection(url, user, pw)` (informational) |
| `autoCommit`    | no       | `"true"`/`"false"`, default `"true"`                                     |
| `readOnly`      | no       | `"true"`/`"false"`                                                      |
| `schema`        | no       | initial schema                                                          |
| `clientInfo.<name>` | no   | initial client info entries (e.g. `clientInfo.ApplicationName`)         |
| `txIsolation`   | no       | `java.sql.Connection` isolation constant as decimal string              |

Any unknown property is ignored by the gateway.

### 4.2 Statements

| code | name            | payload                                                                                                                  | terminal response |
|-----:|-----------------|--------------------------------------------------------------------------------------------------------------------------|-------------------|
| 0x10 | PREPARE         | `string sql`, `u8 kind`, `i32 autoGeneratedKeys`, `string[] generatedKeyColumns`                                         | PREPARED          |
| 0x11 | EXECUTE         | `i32 statementId` (−1 = direct SQL), `string sql` (null unless direct), `u8 kind`, `Value[] params`, `ExecOptions`, `OutParam[]` | EXECUTE_DONE |
| 0x12 | FETCH           | `i32 cursorId`, `i32 maxRows`                                                                                            | ROWS              |
| 0x13 | CLOSE_CURSOR    | `i32 cursorId`                                                                                                           | OK                |
| 0x14 | CLOSE_STATEMENT | `i32 statementId`                                                                                                        | OK                |
| 0x15 | EXECUTE_BATCH   | `i32 statementId` (−1 = plain statement batch), `string sql` (null unless prepared and not registered), `u8 kind`, `i32 paramSetCount`, `paramSetCount × Value[]`, `string[] sqls` | BATCH_RESULT |

* `kind`: `0 = STATEMENT`, `1 = PREPARED`, `2 = CALLABLE`.
* `autoGeneratedKeys`: `java.sql.Statement.NO_GENERATED_KEYS (2)` or `RETURN_GENERATED_KEYS (1)`.
  When `generatedKeyColumns` is non-empty it wins over the flag.
* `ExecOptions := i32 maxRows, i32 fetchSize, i32 queryTimeoutSeconds, u8 expect, i32 autoGeneratedKeys, string[] generatedKeyColumns`
  with `expect`: `0 = ANY (execute)`, `1 = QUERY (executeQuery)`, `2 = UPDATE (executeUpdate)`.
  The gateway uses `expect` to call the matching physical JDBC method and to validate the result
  shape (e.g. `executeQuery` on an `UPDATE` yields an ERROR with SQLState `07005`).
* `OutParam := i32 index (1-based), i32 jdbcType, i32 scale (−1 none), string typeName (nullable)`.
  Registered OUT parameters for `CALLABLE` executions; the gateway calls `registerOutParameter`.
  INOUT parameters appear both in `params` (as a value) and in `OutParam[]`.
* The PREPARE step does **not** touch the physical database: the gateway merely registers the SQL
  and returns a `statementId`. Physical `PreparedStatement`s are created lazily at EXECUTE time on
  whichever physical connection is bound to the session at that moment, and cached per
  (session, statementId) while a physical connection stays pinned.

### 4.3 Transactions and session settings

| code | name                      | payload                                 | terminal response |
|-----:|---------------------------|-----------------------------------------|-------------------|
| 0x20 | SET_AUTOCOMMIT            | `bool`                                  | OK                |
| 0x21 | COMMIT                    | –                                       | OK                |
| 0x22 | ROLLBACK                  | `string savepointName` (null = full)    | OK                |
| 0x23 | SET_SAVEPOINT             | `string name` (null = unnamed)          | SAVEPOINT_SET     |
| 0x24 | RELEASE_SAVEPOINT         | `string name`                           | OK                |
| 0x25 | SET_TRANSACTION_ISOLATION | `i32 level`                             | OK                |
| 0x26 | SET_READ_ONLY             | `bool`                                  | OK                |
| 0x27 | SET_SCHEMA                | `string schema`                         | OK                |
| 0x28 | SET_CATALOG               | `string catalog`                        | OK                |
| 0x29 | SET_CLIENT_INFO           | `string name`, `string value` (nullable)| OK                |
| 0x2A | SET_NETWORK_TIMEOUT       | `i32 millis`                            | OK                |

Semantics (gateway side):

* `SET_AUTOCOMMIT false` marks the session *transactional*. The first subsequent EXECUTE pins a
  physical connection; COMMIT / ROLLBACK (full) end the transaction and, in `TRANSACTION` pool mode,
  release the physical connection once no cursors remain open.
* `SET_AUTOCOMMIT true` while a transaction is open commits it (JDBC semantics).
* Session settings (isolation, read-only, schema, catalog, client info) are **remembered** by the
  gateway and re-applied to each newly pinned physical connection, so they survive un-pinning.
  Client info entries are also used for telemetry (`ApplicationName`, `ClientUser`, `ClientHostname`)
  and are forwarded to the physical connection via `Connection.setClientInfo` where supported
  (for Oracle this surfaces as `V$SESSION.MODULE/ACTION/CLIENT_IDENTIFIER`).

### 4.4 Metadata

| code | name     | payload                                | terminal response |
|-----:|----------|----------------------------------------|-------------------|
| 0x30 | METADATA | `string operation`, `Value[] args`     | EXECUTE_DONE (preceded by exactly one RESULT_SET_HEADER + ROWS) |

`operation` is the name of a `java.sql.DatabaseMetaData` method that returns a `ResultSet`; `args`
are its arguments in order (`STRING` or `NULL`, `BOOLEAN` for the boolean arguments, `INT` for
`scope`, and `STRING[]` encoded as a single `STRING` joined with `\u0000` for `getTables` types).
Supported operations (the gateway forwards any of these to the physical `DatabaseMetaData`):

`getTables, getColumns, getSchemas, getCatalogs, getTableTypes, getPrimaryKeys, getImportedKeys,
getExportedKeys, getCrossReference, getIndexInfo, getTypeInfo, getProcedures, getProcedureColumns,
getFunctions, getFunctionColumns, getBestRowIdentifier, getVersionColumns, getTablePrivileges,
getColumnPrivileges, getUDTs, getSuperTables, getSuperTypes, getAttributes, getClientInfoProperties,
getPseudoColumns`

Scalar `DatabaseMetaData` values are not requested over the wire; they are delivered once in
`HELLO_OK.serverProperties` (section 4.5). Unknown keys fall back to driver defaults.

### 4.5 Server → client frames

| code | name              | payload                                                                                                   |
|-----:|-------------------|-----------------------------------------------------------------------------------------------------------|
| 0x40 | OK                | –                                                                                                         |
| 0x41 | ERROR             | `string sqlState` (nullable), `i32 vendorCode`, `string message`, `bool fatal`                             |
| 0x42 | HELLO_OK          | `string sessionId`, `string serverVersion`, `string engine`, `map serverProperties`                       |
| 0x43 | PONG              | –                                                                                                         |
| 0x44 | PREPARED          | `i32 statementId`, `i32 parameterCount` (−1 = unknown)                                                    |
| 0x45 | RESULT_SET_HEADER | `i32 cursorId`, `i32 columnCount`, `columnCount × ColumnMeta`                                             |
| 0x46 | ROWS              | `i32 cursorId`, `i32 rowCount`, `rowCount × (columnCount × Value)`, `bool last`                           |
| 0x47 | UPDATE_COUNT      | `i64 count`                                                                                               |
| 0x48 | OUT_PARAMS        | `i32 count`, `count × (i32 index, Value value)`                                                           |
| 0x49 | EXECUTE_DONE      | `i32 warningCount`, `warningCount × Warning`                                                              |
| 0x4A | GENERATED_KEYS    | `i32 columnCount`, `columnCount × ColumnMeta`, `i32 rowCount`, `rowCount × (columnCount × Value)`         |
| 0x4B | BATCH_RESULT      | `i32 count`, `count × i64 updateCount`, `i32 warningCount`, `warningCount × Warning`                      |
| 0x50 | SAVEPOINT_SET     | `string name`                                                                                             |

```
ColumnMeta := string label, string name, i32 jdbcType, string typeName, i32 precision, i32 scale,
              u8 nullable (0 = columnNoNulls, 1 = columnNullable, 2 = columnNullableUnknown),
              string className, string tableName, string schemaName, string catalogName,
              bool signed, bool autoIncrement, bool caseSensitive, bool currency, bool readOnly,
              bool searchable, i32 displaySize
Warning    := string sqlState (nullable), i32 vendorCode, string message
```

`engine` in HELLO_OK is one of `ORACLE`, `POSTGRES`, `MSSQL`, `H2`, `OTHER`.

`serverProperties` keys delivered in HELLO_OK (strings; booleans as `"true"/"false"`):

```
databaseProductName, databaseProductVersion, databaseMajorVersion, databaseMinorVersion,
driverName, driverVersion, identifierQuoteString, catalogSeparator, catalogTerm, schemaTerm,
procedureTerm, searchStringEscape, sqlKeywords, extraNameCharacters,
storesUpperCaseIdentifiers, storesLowerCaseIdentifiers, storesMixedCaseIdentifiers,
supportsMixedCaseIdentifiers, supportsSchemasInTableDefinitions, supportsSchemasInDataManipulation,
supportsCatalogsInTableDefinitions, supportsCatalogsInDataManipulation, supportsTransactions,
supportsSavepoints, supportsBatchUpdates, supportsGetGeneratedKeys, supportsStoredProcedures,
supportsNamedParameters, supportsMultipleResultSets, supportsOuterJoins, supportsUnion,
supportsUnionAll, defaultTransactionIsolation, maxStatementLength, maxConnections,
nullsAreSortedHigh, nullsAreSortedLow, nullPlusNonNullIsNull, isReadOnly, userName, url, poolMode
```

`url` is the **logical** JDBC URL the gateway resolved for this session (never the physical one);
`poolMode` is the pooling mode applied to the session (`TRANSACTION` or `SESSION`).

### 4.6 EXECUTE response sequence

An EXECUTE (or METADATA) request is answered by **zero or more result items**, optional
`OUT_PARAMS`, optional `GENERATED_KEYS`, and the terminal `EXECUTE_DONE`:

```
EXECUTE →   ( RESULT_SET_HEADER ROWS | UPDATE_COUNT )*  OUT_PARAMS?  GENERATED_KEYS?  EXECUTE_DONE
          | ERROR
```

* For every result set the gateway sends `RESULT_SET_HEADER` immediately followed by **one** `ROWS`
  frame holding the first `fetchSize` rows (default 100 when `fetchSize ≤ 0`) with `last = 1` if
  the cursor is exhausted (the server then closes it; the client must not FETCH it again).
  The client fetches further rows with `FETCH(cursorId, n)`.
* Multiple result items implement `Statement.getMoreResults()` / `getUpdateCount()` exactly in
  the order produced by the physical driver.
* A `cursorId` is unique per session for its lifetime; the gateway may keep at most
  `dbp.maxOpenCursorsPerSession` (default 256) cursors open and returns ERROR `HY000` beyond that.
* While any cursor is open the session stays pinned to its physical connection.

### 4.7 Cursor-typed OUT parameters (Oracle `SYS_REFCURSOR`, PostgreSQL `refcursor`)

When a CALLABLE execution registers an OUT parameter whose `jdbcType` is `java.sql.Types.REF_CURSOR`
(2012) or the Oracle legacy code `-10` (`OracleTypes.CURSOR`), the gateway reads the returned
`ResultSet` and streams it like any other result item (`RESULT_SET_HEADER` + first `ROWS`) **before**
`OUT_PARAMS`. The matching `OUT_PARAMS` entry carries an `INT` value equal to that result item's
`cursorId`. The driver's `CallableStatement.getObject(index)` returns a `ResultSet` bound to that
cursor (fetching further rows with `FETCH`); `getMoreResults()` on the statement skips cursor items
that belong to OUT parameters. Non-cursor OUT parameters are carried as ordinary tagged values.

### 4.8 Pinning and cursors across statements

A session may keep several cursors open (one per open `ResultSet`), all on the same pinned physical
connection. `COMMIT`/`ROLLBACK` while cursors are open: the gateway commits/rolls back on the physical
connection and keeps the session pinned until the cursors close (the physical driver decides whether
the cursors survive; Oracle keeps them, PostgreSQL closes non-holdable ones — the next `FETCH` then
returns `ROWS` with `last = 1` and zero rows or an `ERROR`, as the physical driver reports).

## 5. Errors

`ERROR.sqlState` and `vendorCode` are copied from the physical `SQLException` when available.
Gateway-originated errors use:

| sqlState | meaning                                                              |
|----------|----------------------------------------------------------------------|
| `08001`  | cannot reach the physical database / pool exhausted (timeout)        |
| `08004`  | application not authorised for this datasource / invalid api key     |
| `08006`  | connection failure (fatal = 1)                                       |
| `0A000`  | feature not supported                                                |
| `07005`  | statement did not produce the expected result shape                  |
| `HY000`  | general gateway error (e.g. too many cursors, protocol violation)    |
| `HY008`  | statement cancelled / timed out                                      |
| `42000`  | SQL rejected by a platform policy (e.g. blocked statement)           |

## 6. Versioning

`HELLO.protocolVersion` is `1`. A gateway that does not support the requested version answers
`ERROR` (`sqlState = "08004"`, message `unsupported protocol version`) and closes the socket.
Future minor additions must be **appended** to payloads with new optional trailing fields only
when the version is bumped; readers must never rely on trailing garbage.

## 7. TLS

Transport security is plain TLS on the same port when enabled (`ssl=true` in the JDBC URL, gateway
`DBP_GATEWAY_TLS_KEYSTORE`). The protocol itself is unchanged.

## 8. JDBC URL

```
jdbc:dbp://<gateway-host>[:<port>][,<gateway-host2>[:<port2>]...]/<datasource>[?prop=value&prop2=value2]
```

* default port `7420`
* properties: `apiKey`, `application`, `ssl` (`true/false`), `connectTimeoutMs` (default 10000),
  `socketTimeoutMs` (default 0 = none), `fetchSize` (default 100), `maxFrameBytes`,
  `autoCommit`, `schema`, `readOnly`, `clientInfo.<name>`.
* Properties may also be passed through the `java.util.Properties` argument of
  `Driver.connect`; URL values win. `user`/`password` from `DriverManager.getConnection(url, user,
  password)` map to `user` (informational) and, when `apiKey` is absent, `password` is used as the
  api key so that unchanged Spring/Hikari configuration (`username`/`password`) keeps working.
* Multiple hosts are tried in order (simple client-side failover at connect time).

## 9. Reference Java API (dbp-protocol)

```
org.dbplatform.protocol.MessageType      // byte codes above as enum constants with code()
org.dbplatform.protocol.ValueTag         // tags above
org.dbplatform.protocol.FrameReader      // readFrame(DataInputStream) -> Frame(type, payload bytes)
org.dbplatform.protocol.FrameWriter      // writeFrame(DataOutputStream, type, payload)
org.dbplatform.protocol.ProtocolInput    // typed readers over a payload: readString(), readValue(), readMap() ...
org.dbplatform.protocol.ProtocolOutput   // typed writers building a payload byte[]
org.dbplatform.protocol.Values           // encode/decode Value <-> Java object, jdbcType -> tag mapping
org.dbplatform.protocol.messages.*       // records for every message with encode()/decode(ProtocolInput)
                                         // (the ERROR frame's record is named ErrorMessage; Rows.decode needs the column count)
org.dbplatform.protocol.ColumnMeta       // record
org.dbplatform.protocol.ProtocolException // thrown on malformed frames
```

Both the driver and the gateway must use these classes rather than hand-rolling byte handling.
