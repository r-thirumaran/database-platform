# Oracle demo schema

Scripts in this directory are mounted into the `gvenzl/oracle-free:23-slim` container at
`/container-entrypoint-initdb.d`. The entrypoint runs them **once**, when the data volume is empty,
in file-name order, as `SYS AS SYSDBA` against the CDB root. That is why every script starts with
`ALTER SESSION SET CONTAINER = FREEPDB1;`.

| File                         | Purpose |
|------------------------------|---------|
| `01_users.sql`               | Accounts `SALES`, `SALES_APP`, `DBP_COLLECTOR`, role `DBP_COLLECTOR_ROLE`, demo profile |
| `02_passwords.sh`            | Replaces the demo passwords with `SALES_PASSWORD`, `SALES_APP_PASSWORD`, `DBP_COLLECTOR_PASSWORD` from the container environment |
| `10_schema.sql`              | Tables, constraints, indexes, sequence, comments, reporting view |
| `20_plsql.sql`               | `ORDER_PKG`, `RESERVE_STOCK`, `GET_CUSTOMER_TIER`, `GET_ORDERS_FOR_CUSTOMER`, triggers |
| `30_data.sql`                | ~200 customers, 60 products, 600+ orders, lines, payments (PL/SQL loops, fixed seed) |
| `40_grants_app.sql`          | DML/EXECUTE grants and private synonyms for `SALES_APP` |
| `50_audit_policy.sql.optional` | Unified audit policy on the SALES tables; rename to `.sql` to enable |

Only `*.sql` and `*.sh` files are executed; this README and the `.optional` file are ignored.

## Accounts and what connects as whom

| Account         | Used by | Privileges |
|-----------------|---------|------------|
| `SALES`         | nobody at runtime (schema owner) | `CREATE TABLE/SEQUENCE/VIEW/PROCEDURE/TRIGGER/TYPE/SYNONYM`, unlimited quota on `USERS` |
| `SALES_APP`     | gateway pools (credential `sales-oracle-app` in the control plane), example apps in `direct` and `proxy` mode | `CREATE SESSION`, DML on the SALES tables, `EXECUTE` on the PL/SQL API, private synonyms; **plus `DBP_COLLECTOR_ROLE`** (see below) |
| `DBP_COLLECTOR` | control-plane collector when a dedicated collector credential is configured | `CREATE SESSION`, `DBP_COLLECTOR_ROLE`, `SELECT` on `SALES.AUDIT_LOG` |

Passwords: the defaults in `01_users.sql` are `Sales#Demo2026`, `SalesApp#Demo2026` and
`Collector#Demo2026`; `deploy/docker-compose.yml` passes the values from `deploy/.env` into the
container and `02_passwords.sh` applies them, so the control plane (credential provider `ENV`) and
the example applications always use the same secret as the database.

## What the collector needs, and why

The control-plane collector runs three jobs against each registered database:

1. **Dictionary crawl** (tables, columns, comments, constraints, routines, triggers, dependencies,
   source): `ALL_TABLES`, `ALL_TAB_COLUMNS`, `ALL_TAB_COMMENTS`, `ALL_CONSTRAINTS`, `ALL_CONS_COLUMNS`,
   `ALL_OBJECTS`, `ALL_PROCEDURES`, `ALL_DEPENDENCIES`, `ALL_TRIGGERS`, `ALL_SOURCE`, `ALL_VIEWS`.
   * `ALL_*` views show what the connected user can see, `DBA_*` views show everything. The
     collector connects as a user that owns nothing, so it needs `SELECT_CATALOG_ROLE` (which
     exposes the `DBA_*` family) or explicit grants on the `DBA_*` views it uses. The role is the
     simplest and is read-only by construction.
2. **Runtime sampling** (who is connected, what is running, which objects a statement touches):
   `V$SESSION`, `V$SQL`, `V$SQLAREA`, `V$SQL_PLAN`, `V$SESSION_CONNECT_INFO`, `V$PROCESS`.
   * `V$` names are public synonyms for `SYS.V_$...` fixed views. They are **not** covered by
     `SELECT_CATALOG_ROLE` on every release, so the demo grants `SELECT ANY DICTIONARY`. The
     least-privilege alternative is explicit grants, e.g.
     ```sql
     GRANT SELECT ON SYS.V_$SESSION              TO DBP_COLLECTOR;
     GRANT SELECT ON SYS.V_$SQL                  TO DBP_COLLECTOR;
     GRANT SELECT ON SYS.V_$SQLAREA              TO DBP_COLLECTOR;
     GRANT SELECT ON SYS.V_$SQL_PLAN             TO DBP_COLLECTOR;
     GRANT SELECT ON SYS.V_$SESSION_CONNECT_INFO TO DBP_COLLECTOR;
     GRANT SELECT ON SYS.V_$PROCESS              TO DBP_COLLECTOR;
     ```
     (grant on the `V_$` view, not on the `V$` synonym). `V$SESSION.PORT` is what the proxy
     correlation joins with `ConnectionEvent.proxyLocalPort`; `V$SESSION.PROGRAM`, `MACHINE`,
     `OSUSER`, `MODULE` are what the identity rules match.
3. **Audit trail** (optional, `collector.auditTrail = true`): `UNIFIED_AUDIT_TRAIL`, readable
   through the `AUDIT_VIEWER` role. Only useful when a policy such as
   `50_audit_policy.sql.optional` is enabled.

These three privilege sets are bundled in `DBP_COLLECTOR_ROLE`.

### Why `SALES_APP` also has the collector role

The control plane keeps **one credential per physical database** and uses it for the gateway pools
*and* the collector (`Database.credentialId`, `docs/control-plane-api.md` section 3). For the demo
to work out of the box with that contract, the application account also receives the read-only
collector role. `DBP_COLLECTOR` is created as the recommended production layout (separate, non-DML
collector account); switch the database credential in the control plane to it as soon as a
collector-specific credential is supported, and then revoke the role from `SALES_APP`:

```sql
REVOKE DBP_COLLECTOR_ROLE FROM SALES_APP;
```

## Session attribution cheat sheet

| Connection path | What identifies the application in Oracle | Identity rule |
|-----------------|-------------------------------------------|---------------|
| gateway (`jdbc:dbp://…`) | nothing needed, the gateway knows the API key; it sets `MODULE`/`ACTION`/`CLIENT_IDENTIFIER` on the pooled session | – |
| proxy (`jdbc:oracle:thin:@//proxy:1521/sales.orders-service`) | requested service `sales.orders-service`; `V$SESSION.PORT` = proxy source port | `serviceAliases` |
| direct (`jdbc:oracle:thin:@//oracle:1521/FREEPDB1`) | `V$SESSION.PROGRAM` (`v$session.program` JDBC property), `MACHINE`, client address | `programNames`, `machinePatterns`, `cidrs` |

## Running the scripts by hand

```bash
docker compose -f deploy/docker-compose.yml exec oracle sqlplus -s / as sysdba @/container-entrypoint-initdb.d/10_schema.sql
```
or against an existing Oracle instance: connect as a DBA to the PDB, drop the first
`ALTER SESSION SET CONTAINER` line, run the files in order.
