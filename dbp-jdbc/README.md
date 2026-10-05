# dbp-jdbc — drop-in JDBC driver

`dbp-jdbc` is the JDBC 4.3 driver applications use instead of the vendor driver to reach a database through
the **gateway** (`dbp-gateway`). Business code (`Connection`, `Statement`, `PreparedStatement`,
`CallableStatement`, `ResultSet`, Spring `JdbcTemplate`, JPA/Hibernate, MyBatis, Flyway) keeps working
unchanged: swap the driver jar, the URL and the credential.

```
jdbc:dbp://gateway-host:7420/<datasource>?apiKey=dbp_<id>_<secret>
driver class: org.dbplatform.jdbc.DbpDriver
```

The driver speaks the [DBP wire protocol v1](../docs/wire-protocol.md) through the `dbp-protocol` codec and
has **no other dependency**. Java 21.

## Installation

Single jar (bundles `dbp-protocol`; drop it on any classpath):

```
dbp-jdbc/target/dbp-jdbc-<version>-all.jar
```

Maven (the plain jar plus `dbp-protocol` as a transitive dependency):

```xml
<dependency>
    <groupId>org.dbplatform</groupId>
    <artifactId>dbp-jdbc</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

The driver registers itself with `DriverManager` on class loading and is discoverable through
`META-INF/services/java.sql.Driver`, so `Class.forName` is not required. `DbpDataSource` is a plain
(non-pooling) `javax.sql.DataSource` with bean properties for containers that configure data sources by
reflection.

Build: `mvn -q -pl dbp-jdbc install` (produces `dbp-jdbc-<ver>.jar` and `dbp-jdbc-<ver>-all.jar`).

## URL and properties

```
jdbc:dbp://<host>[:<port>][,<host2>[:<port2>]...]/<datasource>[?prop=value&prop2=value2]
```

* Default port `7420`; IPv6 literals in brackets (`jdbc:dbp://[::1]:7420/sales`).
* Several hosts are tried **in order** at connect time (client-side failover).
* Properties may be given in the URL or in the `Properties` argument of `DriverManager.getConnection`
  (and therefore in Hikari/Spring `data-source-properties`); **URL values win**.
* `user`/`password` from `DriverManager.getConnection(url, user, password)` map to the informational `user`
  and, when `apiKey` is absent, `password` is used as the api key, so unchanged Spring/Hikari configuration
  (`username`/`password`) keeps working.

| Property            | Default | Meaning                                                                             |
|---------------------|---------|-------------------------------------------------------------------------------------|
| `apiKey`            | –       | Application credential issued by the control plane (`dbp_<id>_<secret>`). Falls back to `password`. Optional when the gateway runs in static auth mode. |
| `application`       | –       | Application name hint (used when the gateway runs without a control plane)          |
| `user`              | –       | Informational user name (telemetry)                                                 |
| `password`          | –       | Used as `apiKey` when `apiKey` is absent                                            |
| `ssl`               | `false` | `true` = TLS on the same port (`SSLSocketFactory.getDefault()`, JVM trust store)    |
| `connectTimeoutMs`  | `10000` | Connect timeout per host                                                            |
| `socketTimeoutMs`   | `0`     | Socket read timeout (`0` = none). A timeout is fatal for the connection (`08006`)   |
| `fetchSize`         | `100`   | Default `Statement.getFetchSize()`: rows per `ROWS` frame / `FETCH`                  |
| `maxFrameBytes`     | 64 MiB  | Maximum frame size accepted and produced (must match the gateway)                   |
| `autoCommit`        | `true`  | Initial auto-commit mode                                                            |
| `readOnly`          | –       | Initial read-only flag                                                              |
| `schema`            | –       | Initial schema                                                                      |
| `txIsolation`       | server default | Initial isolation: `java.sql.Connection` constant (`2`, `8`, …) or name (`READ_COMMITTED`) |
| `clientInfo.<name>` | –       | Initial client info entries, e.g. `clientInfo.ApplicationName=orders-service`       |

Unknown properties are ignored (they are not forwarded to the gateway).

## Framework configuration

### Spring Boot / HikariCP

Spring cannot infer a driver class from `jdbc:dbp://`, so set it explicitly:

```properties
spring.datasource.driver-class-name=org.dbplatform.jdbc.DbpDriver
spring.datasource.url=jdbc:dbp://gateway:7420/sales
spring.datasource.username=orders-service          # informational
spring.datasource.password=${DBP_API_KEY}          # the api key
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.connection-test-query=    # leave empty: Hikari uses Connection.isValid (PING)
spring.datasource.hikari.data-source-properties.fetchSize=200
spring.datasource.hikari.data-source-properties.clientInfo.ApplicationName=orders-service
```

Plain Hikari:

```java
HikariConfig cfg = new HikariConfig();
cfg.setDriverClassName("org.dbplatform.jdbc.DbpDriver");
cfg.setJdbcUrl("jdbc:dbp://gateway:7420/sales");
cfg.setUsername("orders-service");
cfg.setPassword(System.getenv("DBP_API_KEY"));
cfg.addDataSourceProperty("fetchSize", "200");
```

`DbpDataSource` as a bean:

```java
DbpDataSource ds = new DbpDataSource();
ds.setHost("gateway"); ds.setPort(7420); ds.setDatasource("sales");
ds.setApiKey(System.getenv("DBP_API_KEY"));
ds.setApplication("orders-service");
ds.setFetchSize(200);
```

### JPA / Hibernate

Set the dialect **explicitly to the physical engine's dialect**. Hibernate 6 can auto-detect it from
`DatabaseMetaData.getDatabaseProductName()`/version, which the gateway reports for the physical engine the
session was routed to, so it works at startup — but it then silently depends on routing. An explicit value
documents that dependency and fails loudly when the two disagree.

```properties
spring.jpa.database-platform=org.hibernate.dialect.OracleDialect   # or PostgreSQLDialect / SQLServerDialect
# plain Hibernate: hibernate.dialect=org.hibernate.dialect.OracleDialect
spring.jpa.properties.hibernate.jdbc.batch_size=50                 # EXECUTE_BATCH
spring.jpa.properties.hibernate.jdbc.fetch_size=200
```

Identity and sequence generators work (`RETURN_GENERATED_KEYS`, `GENERATED_KEYS` frame). Hibernate asks for
`TYPE_SCROLL_INSENSITIVE` result sets in some code paths; the driver downgrades them to forward-only with a
`SQLWarning` (`01S02`). Scrolling (`ScrollableResults` with backward moves) is not available.

### MyBatis

```xml
<dataSource type="POOLED">
    <property name="driver" value="org.dbplatform.jdbc.DbpDriver"/>
    <property name="url" value="jdbc:dbp://gateway:7420/sales?fetchSize=200"/>
    <property name="username" value="orders-service"/>
    <property name="password" value="${DBP_API_KEY}"/>
</dataSource>
```

MyBatis `<select resultSetType="FORWARD_ONLY">` (the default) is the native mode; `SCROLL_INSENSITIVE` is
downgraded with a warning. Stored procedures with `mode=OUT` and `jdbcType=CURSOR` map to
`registerOutParameter(i, Types.REF_CURSOR)` (positional parameters only).

### Flyway

Flyway's URL-based driver detection does not know `jdbc:dbp`, so hand it a `DataSource`:

```java
Flyway.configure().dataSource(dbpDataSource).load().migrate();
```

The database type is detected from `getDatabaseProductName()` (the physical engine). Use a datasource grant
in **SESSION** pool mode for migrations: Flyway on PostgreSQL takes a session-level advisory lock and
DDL+DML in one migration must stay on one physical connection.

## Supported JDBC features

| Area                 | Supported                                                                                                          |
|----------------------|--------------------------------------------------------------------------------------------------------------------|
| Driver / DataSource  | `DriverManager`, `ServiceLoader`, `DbpDataSource`, multi-host connect-time failover, TLS                           |
| Connection           | `setAutoCommit`, `commit`, `rollback`, savepoints (`setSavepoint`, `rollback(sp)`, `releaseSavepoint`), `setTransactionIsolation`, `setReadOnly`, `setSchema`, `setCatalog`, `setClientInfo` (remembered by the gateway and replayed on re-pin), `setNetworkTimeout` (also the socket read timeout), `isValid` (PING), `getMetaData`, `abort`, `createBlob/createClob/createNClob` (in-memory), `nativeSQL` (identity) |
| Statement            | `execute`, `executeQuery`, `executeUpdate`, `executeLargeUpdate`, generated keys (`RETURN_GENERATED_KEYS`, column names), `getMoreResults`/`getUpdateCount` over multiple result items, `setMaxRows`/`setLargeMaxRows`, `setFetchSize`, `setQueryTimeout`, `addBatch`/`executeBatch`/`executeLargeBatch`, `closeOnCompletion`, warnings |
| PreparedStatement    | All `setXxx` for the value tags (primitives, `BigDecimal`, `String`, `byte[]`, `java.sql.Date/Time/Timestamp` with and without `Calendar`, `java.time` through `setObject`, `setNull(i, type)`, `setObject(i, x[, targetType[, scale]])`, streams/readers and `Blob`/`Clob` (materialised), `setURL`), parameter batches, `clearParameters`, `getParameterMetaData` (count only), `getMetaData` (after a result set exists, `null` before) |
| CallableStatement    | Positional `{call …}` / `{? = call …}`, `registerOutParameter(i, type[, scale \| typeName])`, OUT and INOUT, `getXxx(i)` with the ResultSet conversions, `wasNull`, cursor-typed OUT parameters (`Types.REF_CURSOR` or Oracle `-10`) returned by `getObject(i)` as a `ResultSet`; `getMoreResults` skips cursor items that belong to OUT parameters |
| ResultSet            | Forward-only, read-only, server-side cursor paged with `FETCH`; every `getXxx(int \| label)` with the standard JDBC conversions (strings ↔ numbers/booleans/temporal, numbers between each other with `22003` on overflow, `getTimestamp` on DATE, `getDate/getTime/getTimestamp` with `Calendar`), `getObject(i)` → JDBC standard classes (`OffsetDateTime`/`OffsetTime` for the TZ types), `getObject(i, Class)` for `String`, boxed numbers, `BigDecimal`, `BigInteger`, `Boolean`, `byte[]`, `java.sql.*`, `java.util.Date`, `LocalDate/LocalTime/LocalDateTime/OffsetDateTime/ZonedDateTime/OffsetTime/Instant`, `UUID`, `Blob`/`Clob`; streams, in-memory `Blob`/`Clob`, `getBigDecimal(i, scale)`, `wasNull`, `findColumn` (case-insensitive label, then name), `getMetaData`, `getRow`, `isBeforeFirst/isFirst/isAfterLast` |
| DatabaseMetaData     | Scalars from `HELLO_OK.serverProperties` with driver defaults; every result-set method of the protocol (`getTables`, `getColumns`, `getSchemas`, `getCatalogs`, `getTableTypes`, `getPrimaryKeys`, `getImportedKeys`, `getExportedKeys`, `getCrossReference`, `getIndexInfo`, `getTypeInfo`, `getProcedures`, `getProcedureColumns`, `getFunctions`, `getFunctionColumns`, `getBestRowIdentifier`, `getVersionColumns`, `getTablePrivileges`, `getColumnPrivileges`, `getUDTs`, `getSuperTables`, `getSuperTypes`, `getAttributes`, `getClientInfoProperties`, `getPseudoColumns`) |

Downgraded with a `SQLWarning` (SQLState `01S02`) so framework defaults keep working:
`TYPE_SCROLL_INSENSITIVE` → `TYPE_FORWARD_ONLY`, `HOLD_CURSORS_OVER_COMMIT` → `CLOSE_CURSORS_AT_COMMIT`.

### Not supported (`SQLFeatureNotSupportedException`, SQLState `0A000`)

* Named parameters on `CallableStatement` (`setXxx("name", …)`, `getXxx("name")`,
  `registerOutParameter("name", …)`) — the protocol is positional.
* Scrollable result sets (`TYPE_SCROLL_SENSITIVE` rejected; `absolute`, `relative`, `previous`,
  `first`, `last`, `beforeFirst`, `afterLast` on any result set) and updatable result sets
  (`CONCUR_UPDATABLE`, `updateXxx`, `insertRow`, `deleteRow`, …).
* Streaming LOBs: values are materialised in the frame (`maxFrameBytes`, 64 MiB default); `Blob`/`Clob`
  objects are in-memory copies (`locatorsUpdateCopy() = true`).
* Vendor unwrapping (`unwrap(OracleConnection.class)` …): `isWrapperFor` is `true` only for the driver's own
  types; the physical connection lives in the gateway.
* `createArrayOf`, `createStruct`, `createSQLXML`, `setArray`, `setRef`, `setRowId`, `setSQLXML`,
  `getArray`, `getRef`, `getRowId`, `getSQLXML`, `getURL` (ARRAY/STRUCT/XML columns arrive as `STRING`).
* `prepareStatement(sql, int[] columnIndexes)` (use column names), custom type maps, named cursors.
* XA / distributed transactions (`XADataSource`).
* `Statement.cancel()`: a documented no-op that records a warning (`01000`). The protocol is strictly
  synchronous on one socket, so a cancel cannot reach the gateway while an EXECUTE is in flight; use
  `setQueryTimeout` (the gateway answers `HY008`).
* `ResultSet.isLast()` throws `0A000` when it cannot be answered without fetching the next batch.

## Behaviour worth knowing

* **Prepared statements** are registered with PREPARE lazily at the first execution (the gateway does not
  touch the physical database for that) and executed by statement id. If the gateway reports the id as
  unknown (SQLState `HY000`, message mentioning an unknown / not found statement) the driver re-prepares
  once, transparently.
* **Result sets** receive the first `fetchSize` rows with the result-set header and pull the rest with
  `FETCH`. When the gateway flags the last batch the server closes the cursor; otherwise `close()` sends
  `CLOSE_CURSOR`. Closing a statement closes its result sets; closing the connection closes everything and
  sends `CLOSE`. Reading past `setMaxRows` stops client-side as well.
* **Commit/rollback with open cursors**: the gateway keeps the session pinned until the cursors close;
  whether the cursor survives is decided by the physical driver (Oracle keeps it, PostgreSQL closes
  non-holdable cursors) — read everything before committing.
* **Threads**: JDBC objects of one connection may be used from several threads; all wire I/O is serialised
  on a per-connection lock, requests are strictly sequential.
* **Connection loss**: any I/O error, socket timeout or protocol violation closes the connection; every
  subsequent call throws `SQLNonTransientConnectionException` and `isClosed()` is `true`. Pools detect this
  through `isValid`.
* `getObject(i)` returns `java.sql.Date/Time/Timestamp` for DATE/TIME/TIMESTAMP, `OffsetDateTime` for
  TIMESTAMP WITH TIME ZONE and `OffsetTime` for TIME WITH TIME ZONE; `ResultSetMetaData.getColumnClassName`
  is derived from the JDBC type accordingly (never a vendor class name).
* `DatabaseMetaData.getURL()` is the **logical** URL resolved by the gateway; `getUserName()` the user the
  gateway reports.

## Error SQLStates

Every `SQLException` raised by the driver carries a SQLState. Gateway errors keep the physical driver's
SQLState and vendor code and are mapped to the standard subclasses by class code (`08` connection, `0A`
feature not supported, `22` data, `23` integrity, `28` authorisation, `40` rollback, `42` syntax/access,
`HY008` timeout). States produced by the driver itself:

| SQLState | Exception                             | Meaning                                                                  |
|----------|---------------------------------------|--------------------------------------------------------------------------|
| `08001`  | `SQLNonTransientConnectionException`  | Cannot connect to any gateway host; invalid URL or connection property   |
| `08003`  | `SQLNonTransientConnectionException`  | Operation on a closed connection                                         |
| `08004`  | `SQLNonTransientConnectionException`  | Session rejected by the gateway (invalid api key, protocol version)      |
| `08006`  | `SQLNonTransientConnectionException`  | Connection failure (I/O error, socket timeout, protocol violation, gateway `fatal` error); the connection is closed |
| `0A000`  | `SQLFeatureNotSupportedException`     | Feature not supported (see above)                                        |
| `07001`  | `SQLException`                        | Parameter not set before execution                                       |
| `07005`  | `SQLNonTransientException`            | `executeQuery` did not produce a result set / `executeUpdate` produced one |
| `07009`  | `SQLNonTransientException`            | Column or parameter index out of range, OUT parameter not registered     |
| `22003`  | `SQLDataException`                    | Numeric value out of range for the requested type                        |
| `22018`  | `SQLDataException`                    | Value cannot be converted to the requested type                          |
| `24000`  | `SQLNonTransientException`            | Invalid cursor state (no current row, result set closed)                 |
| `42S22`  | `SQLException`                        | Column label not found                                                   |
| `HY000`  | `SQLNonTransientException`            | Protocol violation in a well-formed stream (e.g. ROWS without header)    |
| `HY010`  | `SQLNonTransientException`            | Statement closed / SQL text passed to a `PreparedStatement`              |
| `HY024`  | `SQLNonTransientException`            | Invalid argument (negative fetch size, unknown flag, …)                  |
| `01S02`  | `SQLWarning`                          | Option value changed (result set type / holdability downgraded)          |
| `01000`  | `SQLWarning`                          | `cancel()` ignored                                                       |

`BatchUpdateException` wraps the gateway error of a failed `executeBatch` with an empty update-count array
(the gateway does not report partial counts).

## Logging

The driver uses `java.util.logging` with the logger `org.dbplatform.jdbc` (also returned by
`Driver.getParentLogger()`). At `FINE` it logs connection establishment and the **type and size of every
frame** sent and received; it never logs SQL text, parameter values or row data. Route it to SLF4J with
`jul-to-slf4j` if the application logs through SLF4J.

```properties
org.dbplatform.jdbc.level=FINE
```

## Notes for the gateway implementation

The driver follows [wire-protocol.md](../docs/wire-protocol.md); interpretations where the document leaves
room:

* The terminal frame must match the request type (`PING` → `PONG`, `PREPARE` → `PREPARED`, `FETCH` → `ROWS`,
  `SET_SAVEPOINT` → `SAVEPOINT_SET`, `EXECUTE`/`METADATA` → `EXECUTE_DONE`, `EXECUTE_BATCH` → `BATCH_RESULT`,
  everything else → `OK`, or `ERROR` anywhere). Any other frame is a protocol violation and closes the
  connection.
* `METADATA` arguments: `String` → `STRING`/`NULL`, `boolean` → `BOOLEAN`, `int` → `INT`,
  `getTables` types → one `STRING` joined with `\u0000` (`null` stays `NULL`, an empty array is `""`),
  `getUDTs` `int[] types` → one `STRING` of decimal values joined with `\u0000`. `getSchemas()` sends zero
  arguments, `getSchemas(catalog, schemaPattern)` two.
* "Unknown statement id" errors should be reported with SQLState `HY000` and a message containing
  `unknown statement` (or `statement … not found`) so the driver re-prepares transparently.
* `SAVEPOINT_SET` must carry a name even for unnamed savepoints (the driver rolls back / releases by name).
* Cursor-typed OUT parameters are detected by the registered type (`2012` or `-10`); the OUT value must be
  the `INT` cursor id of a result item sent before `OUT_PARAMS`.
* The driver sends `TYPED_NULL` for `setNull(i, type)` and for `setXxx(i, null)` (with the type implied by
  the setter), `NULL` only for `setObject(i, null)`.
* `COMMIT` may arrive while auto-commit is on (the driver is lenient like Oracle/H2); treat it as a no-op
  or a physical commit.
