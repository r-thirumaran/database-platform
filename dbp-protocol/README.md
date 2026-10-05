# dbp-protocol

Codec of the **DBP wire protocol v1** shared by the drop-in JDBC driver (`dbp-jdbc`) and the gateway
(`dbp-gateway`). The contract is [`docs/wire-protocol.md`](../docs/wire-protocol.md); this module
implements it and nothing else.

* **Zero runtime dependencies** – the jar is embedded in the driver and dropped into arbitrary
  application classpaths. Only `java.base` and `java.sql` (JDK modules) are used.
* Java 21, package `org.dbplatform.protocol`.

## What is in here

| Type | Purpose |
|------|---------|
| `MessageType`, `ValueTag` | Byte codes of the message catalogue (section 4) and value tags (section 2), with `code()` / `fromCode(int)`. |
| `Frame`, `FrameReader`, `FrameWriter` | `u32 length \| u8 type \| payload` framing with max-frame-size enforcement (64 MiB default, configurable). Clean EOF between frames raises `EOFException`; anything malformed raises `ProtocolException`. |
| `ProtocolOutput`, `ProtocolInput` | Typed big-endian writers (growable buffer) and bounds-checked readers for every primitive of section 1: `u8`, `bool`, `i16/i32/i64`, `f32/f64`, nullable `string`/`bytes`, `map`, `string[]`, `Value`, `Value[]`, `ColumnMeta`, `Warning`. |
| `Values`, `TypedNull` | `Value` encoding from Java objects (runtime type → tag) and decoding to Java objects, `tagForJdbcType(int)` (server-side `java.sql.Types` → tag), `encodeColumn(...)` (reads a `ResultSet` cell with the getter matching the tag). |
| `ColumnMeta`, `Warning` | Records for the shared structures of section 4.5, with `encode`/`decode` and `from(ResultSetMetaData, int)` / `from(SQLWarning)` helpers. |
| `messages.*` | One record per message (`Hello`, `Execute`, `Rows`, `ErrorMessage`, …) implementing the sealed `Message` interface, `Messages.decode(Frame)` dispatcher, streaming `RowsWriter`. |
| `JdbcUrl` | Parser for `jdbc:dbp://host[:port][,host2[:port2]]/datasource[?k=v&k2=v2]` (section 8). |
| `ProtocolConstants` | `VERSION`, `DEFAULT_PORT`, `DEFAULT_FETCH_SIZE`, `DEFAULT_MAX_FRAME_BYTES`, … |
| `ProtocolException` | `IOException` subclass thrown on any protocol violation. |

## Using the codec

Sending a request and reading the response (driver side):

```java
FrameWriter out = new FrameWriter(socket.getOutputStream(), maxFrameBytes);
FrameReader in  = new FrameReader(socket.getInputStream(), maxFrameBytes);

Messages.write(out, Hello.of("dbp-jdbc", "0.1.0", Map.of(Hello.PROP_DATASOURCE, "sales",
                                                         Hello.PROP_API_KEY, apiKey)));
Message reply = Messages.read(in);
switch (reply) {
    case HelloOk ok      -> sessionId = ok.sessionId();
    case ErrorMessage err -> throw err.toSqlException();
    default               -> throw new ProtocolException("unexpected " + reply.type());
}
```

Reading an EXECUTE response. `ROWS` frames do not carry the column count, so remember it from
the preceding `RESULT_SET_HEADER` and pass it to the decoder:

```java
int columnCount = -1;
while (true) {
    Message m = Messages.read(in, columnCount);
    switch (m) {
        case ResultSetHeader h -> { columnCount = h.columnCount(); /* new result set */ }
        case Rows r            -> { /* r.rows(), r.last() */ }
        case UpdateCount u     -> { /* u.count() */ }
        case OutParams o       -> { /* o.asMap() */ }
        case GeneratedKeys g   -> { /* g.columns(), g.rows() */ }
        case ExecuteDone d     -> { /* d.warnings() */ return; }
        case ErrorMessage e    -> throw e.toSqlException();
        default                -> throw new ProtocolException("unexpected " + m.type());
    }
}
```

Producing rows without materialising them (gateway side):

```java
ResultSetMetaData md = rs.getMetaData();
List<ColumnMeta> columns = new ArrayList<>();
ValueTag[] tags = new ValueTag[md.getColumnCount()];
for (int i = 1; i <= tags.length; i++) {
    ColumnMeta c = ColumnMeta.from(md, i);
    columns.add(c);
    tags[i - 1] = c.valueTag();             // Values.tagForJdbcType(c.jdbcType())
}
Messages.write(out, new ResultSetHeader(cursorId, columns));

RowsWriter rows = new RowsWriter(cursorId, tags.length);
boolean more = true;
while (rows.rowCount() < fetchSize && (more = rs.next())) {
    rows.addRow(rs, tags);                  // uses the getter matching each tag, handles wasNull()
}
rows.writeTo(out, /* last = */ !more);
```

Values: `Values.encode(out, obj)` chooses the tag from the runtime type (`Integer → INT`,
`BigDecimal → DECIMAL`, `java.sql.Timestamp`/`LocalDateTime → TIMESTAMP`, `OffsetDateTime → TIMESTAMP_TZ`,
`TypedNull → TYPED_NULL`, `UUID`/`Character`/anything else → `STRING`, …); `Values.decode(in)` returns
`Integer`, `BigDecimal`, `LocalDate`, `LocalTime`, `LocalDateTime`, `OffsetDateTime`, `OffsetTime`,
`byte[]`, `String`, `TypedNull` or `null`. JDBC conversions (`getInt` on a `DECIMAL`, `getTimestamp` on a
`DATE`, …) are the driver's job.

## Notes on the implementation

* The maximum frame size applies to the `length` field (type byte + payload) on both read and write.
* `ProtocolInput` validates every length and count against the remaining payload before allocating, so a
  hostile peer cannot trigger large allocations with a bogus header.
* Message decoders ignore trailing payload bytes (section 6); use `ProtocolInput.expectEnd()` when strictness
  is wanted.
* The ERROR record is called `ErrorMessage` to avoid clashing with `java.lang.Error`.
* `Values.tagForJdbcType` maps `TINYINT`/`SMALLINT → SHORT`, `REAL → FLOAT`, `FLOAT`/`DOUBLE → DOUBLE`,
  `TIME_WITH_TIMEZONE → TIME_TZ`, `NULL → NULL` in addition to the examples in the spec; everything not
  listed there (`ARRAY`, `STRUCT`, `OTHER`, vendor codes, …) travels as `STRING`.

## Build

```
mvn -q -pl dbp-protocol install
```
