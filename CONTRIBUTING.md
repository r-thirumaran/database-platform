# Contributing / Engineering conventions

This repository is an open-source proof of concept. It must stay **vendor-neutral and company-neutral**:
no internal hostnames, team names, product names or credentials. Demo data uses a generic retail
domain (customers, orders, products, inventory, payments).

## Layout

| Path                 | What                                                         | Build          |
|----------------------|--------------------------------------------------------------|----------------|
| `dbp-protocol/`      | Wire protocol codec shared by driver and gateway. **Zero dependencies.** | Maven |
| `dbp-common/`        | Server-side shared code: telemetry models (Jackson), SQL analysis (JSqlParser), small utils | Maven |
| `dbp-jdbc/`          | Drop-in JDBC driver `jdbc:dbp://…`. Depends only on `dbp-protocol`; shaded single jar `dbp-jdbc-<ver>-all.jar` | Maven |
| `dbp-gateway/`       | Gateway server: pools, execution, telemetry. Plain Java 21 (virtual threads), no Spring | Maven |
| `dbp-proxy/`         | Transparent protocol-aware TCP proxy (Oracle TNS, PostgreSQL, SQL Server pass-through). Plain Java 21 | Maven |
| `dbp-control-plane/` | Spring Boot 3 REST API, metadata store, collectors, governance; serves the UI build | Maven |
| `dbp-ui/`            | React + TypeScript + Vite single-page app                    | npm            |
| `dbp-examples/`      | Example applications (legacy via proxy, same code via gateway, batch job) | Maven |
| `demo/`              | Demo schemas and data for Oracle and PostgreSQL              | SQL            |
| `deploy/`            | Dockerfiles, docker-compose, Kubernetes manifests, Helm chart, Prometheus/Grafana | – |
| `docs/`              | Architecture, specifications, runbooks, ADRs                  | –              |

## Java

* Java 21, Maven, package root `org.dbplatform.<module>`.
* Main classes: `org.dbplatform.gateway.GatewayMain`, `org.dbplatform.proxy.ProxyMain`,
  `org.dbplatform.controlplane.ControlPlaneApplication`. Driver class `org.dbplatform.jdbc.DbpDriver`.
* Versions are managed in the root `pom.xml` (`dependencyManagement`). Modules do not pin versions.
* Logging through SLF4J; gateway/proxy ship Logback, control plane uses Spring Boot defaults.
* Configuration: YAML file + environment variables prefixed `DBP_` (env wins). Every option documented
  in the module README.
* Tests: JUnit 5 + AssertJ. Unit tests run with `mvn test`; anything needing a database uses H2 or
  the zonky embedded PostgreSQL (no Docker required). Oracle/SQL Server specific code is unit-tested
  with synthetic inputs and exercised for real through `deploy/docker-compose.yml`.
* Only the module owner edits a module; the root `pom.xml`, `README.md`, `docs/*.md` contracts and
  `deploy/` are edited by the integrator.

## Ports

| Component              | Port(s)                                        |
|------------------------|------------------------------------------------|
| control plane          | 8080 (API + UI)                                |
| gateway                | 7420 (wire protocol), 7421 (admin: /health, /metrics, /sessions) |
| proxy                  | 1521 (Oracle listener), 5432 (PostgreSQL listener), 1433 (SQL Server listener), 7431 (admin: /health, /metrics, /connections) |
| UI dev server          | 5173 (proxies `/api` to 8080)                  |
| demo Oracle            | 1522 → container 1521 (so the proxy can own 1521 on the host) |
| demo PostgreSQL        | 5433 → container 5432                          |

## Git

* Conventional commit messages (`feat(gateway): …`, `fix(proxy): …`, `docs: …`).
* Never commit secrets. `.env` files are ignored; `deploy/.env.example` documents variables.

## Licence

Apache License 2.0. Contributions are accepted under the same licence.
