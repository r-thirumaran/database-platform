# ADR 0004: Java 21 virtual threads for the gateway and the proxy

Status: accepted · Date: 2026-10

## Context

The gateway holds one TCP socket per logical session — potentially thousands — most of them idle
between statements. The proxy relays two sockets per proxied connection. Physical JDBC calls into the
vendor drivers are blocking. The classic choices are a thread per connection (simple, does not scale
to many idle sessions) or a non-blocking reactor (scales, but makes blocking JDBC calls and the
synchronous wire protocol awkward and the code hard to reason about).

## Decision

Gateway and proxy are **plain Java 21 programs using virtual threads** (`Executors.newVirtualThreadPerTaskExecutor()`):
one virtual thread per logical session reading frames and executing requests, one per direction per
proxied connection, blocking I/O everywhere. No Spring, no reactive framework in these components.

## Consequences

* Code reads as straightforward blocking code; the wire protocol's synchronous model maps directly to
  "read frame, execute, write frames".
* Thousands of idle logical sessions cost memory, not platform threads; concurrency is bounded by pool
  size and heap, not by a thread pool.
* Vendor JDBC drivers block inside native socket reads; virtual threads unmount on JDK socket I/O, but
  code paths that use `synchronized` around blocking calls pin the carrier thread (pre-JDK 24
  behaviour). Driver versions are managed centrally (root `pom.xml`) and should be kept current;
  `-Djdk.tracePinnedThreads` is used in load tests to spot pinning.
* Start-up and footprint are small (no framework), which suits many small gateway replicas.
* Requires Java 21+ for gateway and proxy. The **driver** does not use virtual threads and targets the
  same Java release as the rest of the build; if older application runtimes must be supported, the
  driver module can be compiled with a lower `--release` because it only uses `java.base` and `java.sql`.

## Alternatives considered

| Alternative                       | Why not                                                                                                     |
|-----------------------------------|-------------------------------------------------------------------------------------------------------------|
| Netty / NIO reactor               | Blocking JDBC calls would need a separate blocking pool anyway; two threading models in one process          |
| Platform thread per session       | Does not scale to thousands of idle sessions                                                                 |
| Spring Boot (WebFlux/MVC) for gateway and proxy | Neither speaks a custom binary protocol natively; heavier footprint; control plane already uses Spring where it fits (REST, JPA) |
| Kotlin coroutines / Loom-less async | Same colouring problem with blocking drivers                                                               |
