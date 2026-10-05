# ADR 0003: Custom binary wire protocol instead of gRPC/HTTP

Status: accepted · Date: 2026-10

## Context

The drop-in driver must be added to 80+ applications of different ages: Spring Boot 2 and 3, Java EE
containers, plain `main()` batches, vendor-packaged tools. Anything the driver jar brings onto the
classpath competes with the application's own versions. JDBC is a synchronous API: each `Connection`
executes one request at a time and blocks for the answer; result sets are consumed incrementally;
errors are `SQLException`s with SQLState and vendor code.

## Decision

Define a **custom length-prefixed binary protocol** (`docs/wire-protocol.md`), implemented once in the
dependency-free `dbp-protocol` module and used by both driver and gateway:

* one TCP socket per logical JDBC connection, strictly synchronous request/response, no request ids,
  no unsolicited server frames;
* big-endian framing (`u32 length | u8 type | payload`), tagged values mapping JDBC types one-to-one,
  server-side forward-only cursors with explicit FETCH;
* messages that mirror JDBC calls (PREPARE/EXECUTE/FETCH, transaction and session settings,
  `DatabaseMetaData` operations, OUT parameters, generated keys, batches);
* optional TLS on the same port; versioning through `HELLO.protocolVersion`.

The driver depends only on `dbp-protocol` and is shipped as a single jar with no third-party classes.

## Consequences

* **No classpath conflicts**: no Netty, protobuf, gRPC, Jackson or HTTP client in the application.
* **JDBC semantics are faithful**: blocking calls map to blocking reads; `getMoreResults`, warnings,
  SQLState/vendor codes travel as-is; pinning rules are expressible (cursor open ⇒ pinned).
* The protocol is ours to maintain: codec tests, a versioning discipline (append-only fields on a
  version bump), and documentation are mandatory; the contract document is authoritative.
* No multiplexing of several JDBC connections over one socket and no out-of-band channel: `Statement.
  cancel()` is best effort (`setQueryTimeout` is the reliable path). A future admin channel can add it.
* Tooling (gRPC reflection, HTTP observability) is not available; the gateway's admin port and
  Prometheus metrics cover operations.

## Alternatives considered

| Alternative                              | Why not                                                                                                                        |
|------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------|
| gRPC + protobuf                          | Brings gRPC, Netty/OkHttp, protobuf and Guava onto every application classpath (shading mitigates but bloats and still conflicts with JNI/ALPN pieces); streaming semantics are asynchronous and must be re-synchronised for JDBC |
| HTTP/1.1 + JSON                          | Verbose for row data, type fidelity problems (decimal/temporal), still an HTTP client dependency or raw `HttpURLConnection` with poor streaming |
| Speak the PostgreSQL wire protocol to the driver (pgjdbc as the client) | Elegant for PostgreSQL clients but cannot carry Oracle OUT parameters, REF CURSORs, vendor SQLStates and metadata faithfully; applications would need pgjdbc for Oracle data |
| Java serialisation / RMI                 | Security (deserialisation), versioning, firewalls                                                                               |
| Reuse the vendor protocols (TNS/TDS)     | Undocumented/complex; would tie the gateway to one engine                                                                       |
