package org.dbplatform.controlplane.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.dbplatform.controlplane.collector.StubJdbc.row;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.dbplatform.controlplane.collector.Model.CrawlResult;
import org.dbplatform.controlplane.collector.Model.DependencyInfo;
import org.dbplatform.controlplane.collector.mssql.MssqlDictionaryCrawler;
import org.dbplatform.controlplane.collector.mssql.MssqlRuntimeSampler;
import org.dbplatform.controlplane.collector.oracle.OracleAuditSampler;
import org.dbplatform.controlplane.collector.oracle.OracleDictionaryCrawler;
import org.dbplatform.controlplane.collector.oracle.OracleRuntimeSampler;
import org.dbplatform.controlplane.collector.postgres.PostgresDictionaryCrawler;
import org.dbplatform.controlplane.collector.postgres.PostgresRuntimeSampler;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Enums;
import org.dbplatform.controlplane.domain.Enums.DependencyKind;
import org.junit.jupiter.api.Test;

/** Crawler / sampler mapping code exercised against canned result sets for the exact dictionary queries. */
class CrawlerUnitTest {

    private static DatabaseInstance db(Enums.Engine engine) {
        DatabaseInstance d = new DatabaseInstance();
        d.setId("db-1"); d.setName("test"); d.setEngine(engine); d.setHost("h"); d.setPort(1);
        return d;
    }

    private static String deps(CrawlResult r) {
        StringBuilder sb = new StringBuilder();
        for (DependencyInfo d : r.dependencies) sb.append(d.from().type()).append(':').append(d.from().name()).append(" -").append(d.kind()).append("-> ").append(d.to().type()).append(':').append(d.to().name()).append('\n');
        return sb.toString();
    }

    @Test
    void oracleDictionaryFallsBackToAllViewsAndRefinesFromSource() throws Exception {
        Instant ddl = Instant.parse("2026-01-01T00:00:00Z");
        StubJdbc stub = new StubJdbc()
                // DBA_* refused → ALL_* used (ORA-00942)
                .fail("FROM DBA_TABLES", new SQLException("ORA-00942: table or view does not exist", "42000", 942))
                .on("FROM ALL_TABLES", List.of(row("OWNER", "SALES", "TABLE_NAME", "ORDERS", "NUM_ROWS", 100L), row("OWNER", "SALES", "TABLE_NAME", "AUDIT_LOG", "NUM_ROWS", 5L), row("OWNER", "SALES", "TABLE_NAME", "CUSTOMER", "NUM_ROWS", 7L)))
                .on("FROM DBA_MVIEWS", List.of())
                .on("FROM DBA_VIEWS", List.of(row("OWNER", "SALES", "VIEW_NAME", "V_ORDERS", "TEXT", "select o.order_id from sales.orders o join sales.customer c on c.id = o.customer_id")))
                .on("FROM DBA_TAB_COLUMNS", List.of(row("OWNER", "SALES", "TABLE_NAME", "ORDERS", "COLUMN_NAME", "ORDER_ID", "COLUMN_ID", 1, "DATA_TYPE", "NUMBER", "DATA_LENGTH", 22, "DATA_PRECISION", 12, "DATA_SCALE", 0, "NULLABLE", "N", "DATA_DEFAULT", null),
                        row("OWNER", "SALES", "TABLE_NAME", "ORDERS", "COLUMN_NAME", "STATUS", "COLUMN_ID", 2, "DATA_TYPE", "VARCHAR2", "DATA_LENGTH", 20, "DATA_PRECISION", null, "DATA_SCALE", null, "NULLABLE", "Y", "DATA_DEFAULT", "'NEW' ")))
                .on("FROM DBA_TAB_COMMENTS", List.of(row("OWNER", "SALES", "TABLE_NAME", "ORDERS", "COMMENTS", "Customer orders")))
                .on("FROM DBA_COL_COMMENTS", List.of(row("OWNER", "SALES", "TABLE_NAME", "ORDERS", "COLUMN_NAME", "STATUS", "COMMENTS", "NEW/PAID")))
                .on("FROM DBA_OBJECTS", List.of(row("OWNER", "SALES", "OBJECT_NAME", "ORDER_PKG", "OBJECT_TYPE", "PACKAGE", "STATUS", "VALID", "LAST_DDL_TIME", ddl),
                        row("OWNER", "SALES", "OBJECT_NAME", "ORDER_PKG", "OBJECT_TYPE", "PACKAGE BODY", "STATUS", "INVALID", "LAST_DDL_TIME", ddl.plusSeconds(60)),
                        row("OWNER", "SALES", "OBJECT_NAME", "TRG_ORDERS_AUDIT", "OBJECT_TYPE", "TRIGGER", "STATUS", "VALID", "LAST_DDL_TIME", ddl),
                        row("OWNER", "SALES", "OBJECT_NAME", "GET_TIER", "OBJECT_TYPE", "FUNCTION", "STATUS", "VALID", "LAST_DDL_TIME", ddl)))
                .on("FROM DBA_PROCEDURES", List.of(row("OWNER", "SALES", "OBJECT_NAME", "ORDER_PKG", "PROCEDURE_NAME", "PLACE_ORDER"), row("OWNER", "SALES", "OBJECT_NAME", "ORDER_PKG", "PROCEDURE_NAME", "CALC_TOTAL")))
                .on("FROM DBA_TRIGGERS", List.of(row("OWNER", "SALES", "TRIGGER_NAME", "TRG_ORDERS_AUDIT", "TRIGGERING_EVENT", "INSERT OR UPDATE ", "TABLE_OWNER", "SALES", "TABLE_NAME", "ORDERS", "STATUS", "ENABLED")))
                .on("FROM DBA_DEPENDENCIES", List.of(row("OWNER", "SALES", "NAME", "ORDER_PKG", "TYPE", "PACKAGE BODY", "REFERENCED_OWNER", "SALES", "REFERENCED_NAME", "ORDERS", "REFERENCED_TYPE", "TABLE"),
                        row("OWNER", "SALES", "NAME", "ORDER_PKG", "TYPE", "PACKAGE BODY", "REFERENCED_OWNER", "SALES", "REFERENCED_NAME", "ORDER_PKG", "REFERENCED_TYPE", "PACKAGE"),
                        row("OWNER", "SALES", "NAME", "TRG_ORDERS_AUDIT", "TYPE", "TRIGGER", "REFERENCED_OWNER", "SALES", "REFERENCED_NAME", "AUDIT_LOG", "REFERENCED_TYPE", "TABLE"),
                        row("OWNER", "SALES", "NAME", "ORDER_PKG", "TYPE", "PACKAGE BODY", "REFERENCED_OWNER", "SALES", "REFERENCED_NAME", "GET_TIER", "REFERENCED_TYPE", "FUNCTION"),
                        row("OWNER", "SALES", "NAME", "V_ORDERS", "TYPE", "VIEW", "REFERENCED_OWNER", "SALES", "REFERENCED_NAME", "ORDERS", "REFERENCED_TYPE", "TABLE")))
                .on("FROM DBA_CONSTRAINTS c", List.of(row("OWNER", "SALES", "TABLE_NAME", "ORDERS", "R_OWNER", "SALES", "R_TABLE_NAME", "CUSTOMER")))
                .on("FROM DBA_SOURCE", List.of(
                        row("OWNER", "SALES", "NAME", "ORDER_PKG", "TYPE", "PACKAGE BODY", "LINE", 1, "TEXT", "PACKAGE BODY order_pkg AS\n"),
                        row("OWNER", "SALES", "NAME", "ORDER_PKG", "TYPE", "PACKAGE BODY", "LINE", 2, "TEXT", "  PROCEDURE place_order(p_id NUMBER) IS BEGIN\n"),
                        row("OWNER", "SALES", "NAME", "ORDER_PKG", "TYPE", "PACKAGE BODY", "LINE", 3, "TEXT", "    INSERT INTO orders (order_id) VALUES (p_id);\n"),
                        row("OWNER", "SALES", "NAME", "ORDER_PKG", "TYPE", "PACKAGE BODY", "LINE", 4, "TEXT", "    SELECT tier INTO v FROM customer WHERE id = p_id;\n"),
                        row("OWNER", "SALES", "NAME", "ORDER_PKG", "TYPE", "PACKAGE BODY", "LINE", 5, "TEXT", "  END;\n  FUNCTION calc_total RETURN NUMBER IS BEGIN\n"),
                        row("OWNER", "SALES", "NAME", "ORDER_PKG", "TYPE", "PACKAGE BODY", "LINE", 6, "TEXT", "    SELECT SUM(x) INTO v FROM sales.orders; RETURN v; END;\nEND;"),
                        row("OWNER", "SALES", "NAME", "TRG_ORDERS_AUDIT", "TYPE", "TRIGGER", "LINE", 1, "TEXT", "BEGIN INSERT INTO audit_log VALUES (1); END;")));
        CrawlResult r = new OracleDictionaryCrawler().crawl(stub.connection(), db(Enums.Engine.ORACLE), List.of("sales"));

        assertThat(stub.executed.stream().anyMatch(s -> s.contains("ALL_TABLES"))).isTrue();
        assertThat(r.tables).extracting(t -> t.name).containsExactlyInAnyOrder("ORDERS", "AUDIT_LOG", "CUSTOMER", "V_ORDERS");
        Model.TableInfo orders = r.table("SALES", "ORDERS");
        assertThat(orders.rowCount).isEqualTo(100L);
        assertThat(orders.comment).isEqualTo("Customer orders");
        assertThat(orders.columns).hasSize(2);
        assertThat(orders.columns.get(0).precision()).isEqualTo(12);
        assertThat(orders.columns.get(0).nullable()).isFalse();
        assertThat(orders.columns.get(1).defaultValue()).isEqualTo("'NEW'");
        assertThat(orders.columns.get(1).comment()).isEqualTo("NEW/PAID");
        assertThat(r.table("SALES", "V_ORDERS").kind).isEqualTo(Enums.TableKind.VIEW);
        assertThat(r.routines).extracting(x -> x.name).containsExactlyInAnyOrder("ORDER_PKG", "ORDER_PKG.PLACE_ORDER", "ORDER_PKG.CALC_TOTAL", "TRG_ORDERS_AUDIT", "GET_TIER");
        Model.RoutineInfo pkg = r.routine("SALES", "ORDER_PKG");
        assertThat(pkg.kind).isEqualTo(Enums.RoutineKind.PACKAGE);
        assertThat(pkg.status).isEqualTo(Enums.RoutineStatus.INVALID);       // body invalid → package flagged
        assertThat(pkg.lastDdl).isEqualTo(ddl.plusSeconds(60));
        Model.RoutineInfo trg = r.routine("SALES", "TRG_ORDERS_AUDIT");
        assertThat(trg.kind).isEqualTo(Enums.RoutineKind.TRIGGER);
        assertThat(trg.triggerTableName).isEqualTo("ORDERS");
        assertThat(trg.triggerEvent).isEqualTo("INSERT OR UPDATE");
        String d = deps(r);
        assertThat(d).contains("ROUTINE:ORDER_PKG -REFERENCES-> ROUTINE:ORDER_PKG.PLACE_ORDER");
        assertThat(d).contains("ROUTINE:ORDER_PKG -REFERENCES-> TABLE:ORDERS");
        assertThat(d).contains("ROUTINE:ORDER_PKG -CALLS-> ROUTINE:GET_TIER");
        assertThat(d).contains("TABLE:ORDERS -TRIGGERS-> ROUTINE:TRG_ORDERS_AUDIT");
        assertThat(d).contains("ROUTINE:TRG_ORDERS_AUDIT -REFERENCES-> TABLE:AUDIT_LOG");
        assertThat(d).contains("ROUTINE:TRG_ORDERS_AUDIT -WRITES-> TABLE:AUDIT_LOG");
        assertThat(d).contains("TABLE:ORDERS -FOREIGN_KEY-> TABLE:CUSTOMER");
        assertThat(d).contains("TABLE:V_ORDERS -REFERENCES-> TABLE:ORDERS");
        assertThat(d).contains("TABLE:V_ORDERS -REFERENCES-> TABLE:CUSTOMER"); // from the view text
        // member-level refinement from the package body text
        assertThat(d).contains("ROUTINE:ORDER_PKG.PLACE_ORDER -WRITES-> TABLE:ORDERS");
        assertThat(d).contains("ROUTINE:ORDER_PKG.PLACE_ORDER -READS-> TABLE:CUSTOMER");
        assertThat(d).contains("ROUTINE:ORDER_PKG.CALC_TOTAL -READS-> TABLE:ORDERS");
        assertThat(d).doesNotContain("ROUTINE:ORDER_PKG.CALC_TOTAL -WRITES->");
        assertThat(d).doesNotContain("ROUTINE:ORDER_PKG -REFERENCES-> ROUTINE:ORDER_PKG\n");
    }

    @Test
    void oracleRuntimeSamplerMapsSessionsAndPlans() throws Exception {
        Instant t = Instant.parse("2026-02-02T10:00:00Z");
        StubJdbc stub = new StubJdbc()
                .on("FROM V$SESSION", List.of(row("SID", 12, "SERIAL#", 345, "USERNAME", "SALES_APP", "STATUS", "ACTIVE", "PROGRAM", "JDBC Thin Client", "MODULE", "orders-service", "ACTION", null,
                        "CLIENT_IDENTIFIER", null, "MACHINE", "orders-7f9c", "OSUSER", "app", "PORT", 40321, "SERVICE_NAME", "FREEPDB1", "LOGON_TIME", t, "SQL_ID", "abc123", "PREV_SQL_ID", "zzz", "SQL_EXEC_START", t)))
                .on("FROM V$SQL WHERE", List.of(row("SQL_ID", "abc123", "SQL_TEXT", "UPDATE sales.orders SET status = :1", "SQL_FULLTEXT", null, "EXECUTIONS", 10L, "ELAPSED_TIME", 5000L, "ROWS_PROCESSED", 10L, "MODULE", "orders-service", "PARSING_SCHEMA_NAME", "SALES", "LAST_ACTIVE_TIME", t),
                        row("SQL_ID", "abc123", "SQL_TEXT", "dup child", "SQL_FULLTEXT", null, "EXECUTIONS", 1L, "ELAPSED_TIME", 1L, "ROWS_PROCESSED", 1L, "MODULE", null, "PARSING_SCHEMA_NAME", "SALES", "LAST_ACTIVE_TIME", t)))
                .on("FROM V$SQL_PLAN", List.of(row("SQL_ID", "abc123", "OBJECT_OWNER", "SALES", "OBJECT_NAME", "ORDERS", "OBJECT_TYPE", "TABLE", "OPERATION", "UPDATE"),
                        row("SQL_ID", "abc123", "OBJECT_OWNER", "SALES", "OBJECT_NAME", "ORDERS", "OBJECT_TYPE", "TABLE", "OPERATION", "TABLE ACCESS"),
                        row("SQL_ID", "abc123", "OBJECT_OWNER", "SALES", "OBJECT_NAME", "ORDERS_PK", "OBJECT_TYPE", "INDEX (UNIQUE)", "OPERATION", "INDEX"),
                        row("SQL_ID", "abc123", "OBJECT_OWNER", "SALES", "OBJECT_NAME", "CUSTOMER", "OBJECT_TYPE", "TABLE", "OPERATION", "TABLE ACCESS")));
        Model.RuntimeSample s = new OracleRuntimeSampler().sample(stub.connection(), db(Enums.Engine.ORACLE), Set.of("zzz"), 100);
        assertThat(s.sessions).hasSize(1);
        Model.SessionInfo sess = s.sessions.get(0);
        assertThat(sess.sessionId).isEqualTo("12,345");
        assertThat(sess.port).isEqualTo(40321);
        assertThat(sess.machine).isEqualTo("orders-7f9c");
        assertThat(sess.logonTime).isEqualTo(t);
        assertThat(s.statements).hasSize(1);               // "zzz" was already known
        Model.SqlInfo sql = s.statements.get(0);
        assertThat(sql.executions).isEqualTo(10);
        assertThat(sql.sqlText).startsWith("UPDATE");
        assertThat(sql.tables).containsExactlyInAnyOrder(new Model.TableTouch("SALES", "ORDERS", true), new Model.TableTouch("SALES", "CUSTOMER", false));
    }

    @Test
    void oracleAuditSamplerReadsIncrementally() throws Exception {
        Instant t = Instant.parse("2026-03-03T10:00:00Z");
        StubJdbc stub = new StubJdbc().on("FROM UNIFIED_AUDIT_TRAIL", List.of(
                row("EVENT_TIMESTAMP", t, "DBUSERNAME", "SALES_APP", "CLIENT_PROGRAM_NAME", "billing.exe", "USERHOST", "billing-vm-1", "OS_USERNAME", "svc", "ACTION_NAME", "UPDATE", "OBJECT_SCHEMA", "SALES", "OBJECT_NAME", "PAYMENT", "SQL_TEXT", "update payment set x = 1", "SESSIONID", "77"),
                row("EVENT_TIMESTAMP", t.plusSeconds(5), "DBUSERNAME", "SALES_APP", "CLIENT_PROGRAM_NAME", "billing.exe", "USERHOST", "billing-vm-1", "OS_USERNAME", "svc", "ACTION_NAME", "SELECT", "OBJECT_SCHEMA", "SALES", "OBJECT_NAME", "ORDERS", "SQL_TEXT", null, "SESSIONID", "77")));
        Model.AuditBatch b = new OracleAuditSampler().sample(stub.connection(), db(Enums.Engine.ORACLE), List.of("SALES"), t.minusSeconds(60), 100);
        assertThat(b.rows).hasSize(2);
        assertThat(b.cursor).isEqualTo(t.plusSeconds(5));
        assertThat(b.rows.get(0).action()).isEqualTo("UPDATE");
        assertThat(stub.executed.get(0)).contains("FETCH FIRST 100 ROWS ONLY");
    }

    @Test
    void postgresDictionaryCrawler() throws Exception {
        StubJdbc stub = new StubJdbc()
                .on("FROM pg_class c", List.of(row("nspname", "sales", "relname", "orders", "relkind", "r"), row("nspname", "sales", "relname", "customer", "relkind", "r"),
                        row("nspname", "sales", "relname", "events", "relkind", "p"), row("nspname", "sales", "relname", "v_orders", "relkind", "v"),
                        row("nspname", "sales", "relname", "mv_daily", "relkind", "m")))
                .on("FROM pg_matviews", List.of(row("schemaname", "sales", "matviewname", "mv_daily", "definition", "select order_id from sales.orders")))
                .on("FROM pg_stat_user_tables", List.of(row("schemaname", "sales", "relname", "orders", "n_live_tup", 42L)))
                .on("FROM pg_attribute a", List.of(
                        row("nspname", "sales", "relname", "orders", "attname", "order_id", "attnum", 1, "data_type", "bigint", "char_length", null, "num_precision", null, "num_scale", null, "nullable", false, "column_default", "nextval('orders_seq'::regclass)"),
                        row("nspname", "sales", "relname", "orders", "attname", "status", "attnum", 3, "data_type", "character varying(20)", "char_length", 20, "num_precision", null, "num_scale", null, "nullable", true, "column_default", "'NEW'::character varying"),
                        row("nspname", "sales", "relname", "orders", "attname", "total", "attnum", 4, "data_type", "numeric(12,2)", "char_length", null, "num_precision", 12, "num_scale", 2, "nullable", true, "column_default", null),
                        row("nspname", "sales", "relname", "v_orders", "attname", "order_id", "attnum", 1, "data_type", "bigint", "char_length", null, "num_precision", null, "num_scale", null, "nullable", true, "column_default", null),
                        row("nspname", "sales", "relname", "mv_daily", "attname", "order_id", "attnum", 1, "data_type", "bigint", "char_length", null, "num_precision", null, "num_scale", null, "nullable", true, "column_default", null)))
                .on("FROM pg_description", List.of(row("nspname", "sales", "relname", "orders", "objsubid", 0, "description", "Orders"), row("nspname", "sales", "relname", "orders", "objsubid", 1, "description", "pk")))
                .on("FROM pg_constraint", List.of(row("nspname", "sales", "relname", "orders", "ref_schema", "sales", "ref_table", "customer")))
                .on("FROM pg_proc", List.of(row("nspname", "sales", "proname", "place_order", "prokind", "p", "prosrc", "BEGIN INSERT INTO sales.orders(order_id) VALUES (1); PERFORM 1 FROM customer; END", "lanname", "plpgsql"),
                        row("nspname", "sales", "proname", "audit_fn", "prokind", "f", "prosrc", "BEGIN INSERT INTO sales.customer VALUES (1); RETURN NEW; END", "lanname", "plpgsql")))
                .on("FROM pg_trigger", List.of(row("nspname", "sales", "tgname", "trg_orders_audit", "relname", "orders", "tgtype", 5, "tgenabled", "O", "proname", "audit_fn", "proc_schema", "sales")))
                .on("FROM pg_views", List.of(row("schemaname", "sales", "viewname", "v_orders", "definition", "SELECT o.order_id FROM sales.orders o JOIN sales.customer c ON c.id = o.customer_id")));
        CrawlResult r = new PostgresDictionaryCrawler().crawl(stub.connection(), db(Enums.Engine.POSTGRES), List.of("sales"));
        assertThat(r.tables).extracting(t -> t.name).containsExactlyInAnyOrder("orders", "customer", "events", "v_orders", "mv_daily");
        // discovery is on pg_catalog only: information_schema lists just the objects the collector role has privileges on (defect D1)
        assertThat(stub.executed).noneMatch(sql -> sql.toLowerCase().contains("information_schema"));
        String tableSql = stub.executed.stream().filter(sql -> sql.contains("FROM pg_class c")).findFirst().orElseThrow();
        assertThat(tableSql).contains("pg_namespace").contains("c.relkind IN ('r', 'p', 'v', 'm')");
        String columnSql = stub.executed.stream().filter(sql -> sql.contains("FROM pg_attribute a")).findFirst().orElseThrow();
        assertThat(columnSql).contains("a.attnum > 0").contains("NOT a.attisdropped").contains("format_type(a.atttypid, a.atttypmod)")
                .contains("a.attnotnull").contains("LEFT JOIN pg_attrdef").contains("pg_get_expr(d.adbin, d.adrelid)");
        // relkind r / p -> TABLE, v -> VIEW, m -> MATERIALIZED_VIEW
        assertThat(r.table("sales", "orders").kind).isEqualTo(Enums.TableKind.TABLE);
        assertThat(r.table("sales", "events").kind).isEqualTo(Enums.TableKind.TABLE);
        assertThat(r.table("sales", "v_orders").kind).isEqualTo(Enums.TableKind.VIEW);
        assertThat(r.table("sales", "mv_daily").kind).isEqualTo(Enums.TableKind.MATERIALIZED_VIEW);
        assertThat(r.table("sales", "mv_daily").definition).contains("sales.orders");
        assertThat(r.table("sales", "orders").rowCount).isEqualTo(42L);
        assertThat(r.table("sales", "orders").comment).isEqualTo("Orders");
        assertThat(r.table("sales", "orders").columns.get(0).comment()).isEqualTo("pk");
        assertThat(r.table("sales", "orders").columns.get(0).nullable()).isFalse();
        assertThat(r.table("sales", "orders").columns.get(0).defaultValue()).isEqualTo("nextval('orders_seq'::regclass)");
        Model.ColumnInfo status = r.table("sales", "orders").columns.get(1);
        assertThat(status.dataType()).isEqualTo("character varying(20)");
        assertThat(status.length()).isEqualTo(20);
        assertThat(status.nullable()).isTrue();
        Model.ColumnInfo total = r.table("sales", "orders").columns.get(2);
        assertThat(total.dataType()).isEqualTo("numeric(12,2)");
        assertThat(total.precision()).isEqualTo(12);
        assertThat(total.scale()).isEqualTo(2);
        assertThat(r.table("sales", "v_orders").columns).hasSize(1);
        assertThat(r.table("sales", "mv_daily").columns).hasSize(1);
        Model.RoutineInfo trg = r.routine("sales", "trg_orders_audit");
        assertThat(trg.kind).isEqualTo(Enums.RoutineKind.TRIGGER);
        assertThat(trg.triggerEvent).isEqualTo("AFTER INSERT");
        assertThat(trg.triggerTableName).isEqualTo("orders");
        assertThat(r.routine("sales", "place_order").kind).isEqualTo(Enums.RoutineKind.PROCEDURE);
        String d = deps(r);
        assertThat(d).contains("TABLE:orders -FOREIGN_KEY-> TABLE:customer");
        assertThat(d).contains("ROUTINE:place_order -WRITES-> TABLE:orders");
        assertThat(d).contains("ROUTINE:place_order -REFERENCES-> TABLE:orders");
        assertThat(d).contains("ROUTINE:place_order -READS-> TABLE:customer");
        assertThat(d).contains("TABLE:orders -TRIGGERS-> ROUTINE:trg_orders_audit");
        assertThat(d).contains("ROUTINE:trg_orders_audit -CALLS-> ROUTINE:audit_fn");
        assertThat(d).contains("ROUTINE:audit_fn -WRITES-> TABLE:customer");
        assertThat(d).contains("TABLE:v_orders -REFERENCES-> TABLE:orders");
        assertThat(d).contains("TABLE:v_orders -REFERENCES-> TABLE:customer");
        assertThat(d).contains("TABLE:mv_daily -REFERENCES-> TABLE:orders");
        assertThat(PostgresDictionaryCrawler.triggerEvent(2 + 4 + 16)).isEqualTo("BEFORE INSERT OR UPDATE");
    }

    private static final List<java.util.Map<String, Object>> ACTIVITY = List.of(row("pid", 4242, "datname", "sales", "usename", "sales_app", "application_name", "orders-service", "client_addr", "10.20.1.2", "client_port", 40321,
            "backend_start", Instant.parse("2026-02-02T10:00:00Z"), "state", "active", "query", "select * from sales.orders where id = $1", "query_start", Instant.parse("2026-02-02T10:00:00Z")));
    private static final List<java.util.Map<String, Object>> STATEMENTS = List.of(row("queryid", 99L, "query", "update sales.orders set status = $1", "calls", 7L, "total_exec_time", 12.5d, "rows", 7L));

    @Test
    void postgresRuntimeSampler() throws Exception {
        // the extension lives in "public" while the collector session may have any search_path: the query is schema qualified
        StubJdbc stub = new StubJdbc()
                .on("FROM pg_stat_activity", ACTIVITY)
                .on("FROM pg_extension", List.of(row("nspname", "public")))
                .on("\"public\".pg_stat_statements", STATEMENTS);
        Model.RuntimeSample s = new PostgresRuntimeSampler().sample(stub.connection(), db(Enums.Engine.POSTGRES), Set.of(), 50);
        assertThat(s.sessions).hasSize(1);
        assertThat(s.sessions.get(0).program).isEqualTo("orders-service");
        assertThat(s.sessions.get(0).port).isEqualTo(40321);
        assertThat(s.sessions.get(0).sqlId).isNotBlank();
        assertThat(s.statements).hasSize(1);
        assertThat(s.statements.get(0).sqlId).isEqualTo("pgss:99");
        assertThat(s.statements.get(0).elapsedMicros).isEqualTo(12500);
        assertThat(s.warnings).isEmpty();
        // the extension schema is resolved from the catalogue, and the statements query never relies on the search_path
        assertThat(stub.executed).anyMatch(sql -> sql.contains("pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace") && sql.contains("e.extname = 'pg_stat_statements'"));
        assertThat(stub.executed).anyMatch(sql -> sql.contains("FROM \"public\".pg_stat_statements ORDER BY calls DESC LIMIT 50"));
        assertThat(stub.executed).noneMatch(sql -> sql.contains("FROM pg_stat_statements"));
    }

    @Test
    void postgresRuntimeSamplerQualifiesWithWhateverSchemaTheExtensionIsIn() throws Exception {
        StubJdbc stub = new StubJdbc()
                .on("FROM pg_stat_activity", ACTIVITY)
                .on("FROM pg_extension", List.of(row("nspname", "Mon\"itor")))
                .on(".pg_stat_statements", STATEMENTS);
        Model.RuntimeSample s = new PostgresRuntimeSampler().sample(stub.connection(), db(Enums.Engine.POSTGRES), Set.of("pgss:1"), 5);
        assertThat(s.statements).hasSize(1);
        assertThat(stub.executed).anyMatch(sql -> sql.contains("FROM \"Mon\"\"itor\".pg_stat_statements ORDER BY calls DESC LIMIT 5"));
    }

    @Test
    void postgresRuntimeSamplerWithoutTheExtensionSamplesSessionsOnly() throws Exception {
        StubJdbc stub = new StubJdbc()
                .on("FROM pg_stat_activity", ACTIVITY)
                .on("FROM pg_extension", List.of());
        Model.RuntimeSample s = new PostgresRuntimeSampler().sample(stub.connection(), db(Enums.Engine.POSTGRES), Set.of(), 50);
        assertThat(s.sessions).hasSize(1);
        assertThat(s.statements).isEmpty();
        assertThat(s.warnings).isEmpty();
        assertThat(stub.executed).noneMatch(sql -> sql.contains("pg_stat_statements ORDER BY"));
    }

    @Test
    void postgresRuntimeSamplerSurvivesAFailingStatementsQuery() throws Exception {
        // D2: the statements query used to abort the whole sample (relation "pg_stat_statements" does not exist) so lastRuntimeRun was never set
        StubJdbc stub = new StubJdbc()
                .on("FROM pg_stat_activity", ACTIVITY)
                .on("FROM pg_extension", List.of(row("nspname", "public")))
                .fail("\"public\".pg_stat_statements ORDER BY", new SQLException("ERROR: permission denied for view pg_stat_statements", "42501"));
        Model.RuntimeSample s = new PostgresRuntimeSampler().sample(stub.connection(), db(Enums.Engine.POSTGRES), Set.of(), 50);
        assertThat(s.sessions).hasSize(1);
        assertThat(s.statements).isEmpty();
        assertThat(s.warnings).singleElement().asString().contains("pg_stat_statements").contains("permission denied");
        // ... and so does a failing extension lookup
        StubJdbc stub2 = new StubJdbc()
                .on("FROM pg_stat_activity", ACTIVITY)
                .fail("FROM pg_extension", new SQLException("boom"));
        Model.RuntimeSample s2 = new PostgresRuntimeSampler().sample(stub2.connection(), db(Enums.Engine.POSTGRES), Set.of(), 50);
        assertThat(s2.sessions).hasSize(1);
        assertThat(s2.warnings).hasSize(1);
        // but a failing pg_stat_activity read is still an error of the run
        StubJdbc stub3 = new StubJdbc().fail("FROM pg_stat_activity", new SQLException("no access"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PostgresRuntimeSampler().sample(stub3.connection(), db(Enums.Engine.POSTGRES), Set.of(), 50))
                .isInstanceOf(SQLException.class);
    }

    @Test
    void mssqlDictionaryAndRuntime() throws Exception {
        Instant t = Instant.parse("2026-02-02T10:00:00Z");
        StubJdbc stub = new StubJdbc()
                .on("FROM sys.tables t", List.of(row("schema_name", "dbo", "table_name", "Orders", "modify_date", t, "row_count", 10L), row("schema_name", "dbo", "table_name", "Customer", "modify_date", t, "row_count", 3L)))
                .on("FROM sys.views v", List.of(row("schema_name", "dbo", "view_name", "vOrders", "modify_date", t, "definition", "CREATE VIEW vOrders AS SELECT * FROM dbo.Orders o JOIN Customer c ON c.Id = o.CustomerId")))
                .on("FROM sys.columns c", List.of(row("schema_name", "dbo", "table_name", "Orders", "column_name", "Id", "column_id", 1, "type_name", "int", "max_length", 4, "precision", 10, "scale", 0, "is_nullable", false, "default_definition", null)))
                .on("FROM sys.foreign_keys", List.of(row("parent_schema", "dbo", "parent_table", "Orders", "ref_schema", "dbo", "ref_table", "Customer")))
                .on("FROM sys.objects o", List.of(row("schema_name", "dbo", "object_name", "PlaceOrder", "type", "P ", "modify_date", t, "definition", "CREATE PROCEDURE PlaceOrder AS INSERT INTO dbo.Orders (Id) VALUES (1); SELECT 1 FROM Customer"),
                        row("schema_name", "dbo", "object_name", "GetTier", "type", "FN", "modify_date", t, "definition", "CREATE FUNCTION GetTier() RETURNS INT AS BEGIN RETURN (SELECT 1 FROM dbo.Customer) END")))
                .on("FROM sys.triggers tr", List.of(row("schema_name", "dbo", "trigger_name", "trgOrdersAudit", "table_name", "Orders", "is_disabled", false, "modify_date", t, "definition", "CREATE TRIGGER trgOrdersAudit ON Orders AFTER INSERT AS INSERT INTO AuditLog VALUES (1)", "events", "INSERT OR UPDATE")))
                .on("FROM sys.sql_expression_dependencies", List.of(row("schema_name", "dbo", "object_name", "PlaceOrder", "type", "P ", "referenced_schema_name", "dbo", "referenced_entity_name", "Orders", "referenced_type", "U "),
                        row("schema_name", "dbo", "object_name", "PlaceOrder", "type", "P ", "referenced_schema_name", null, "referenced_entity_name", "GetTier", "referenced_type", "FN"),
                        row("schema_name", "dbo", "object_name", "vOrders", "type", "V ", "referenced_schema_name", "dbo", "referenced_entity_name", "Orders", "referenced_type", "U ")));
        CrawlResult r = new MssqlDictionaryCrawler().crawl(stub.connection(), db(Enums.Engine.MSSQL), List.of("dbo"));
        assertThat(r.tables).extracting(x -> x.name).containsExactlyInAnyOrder("Orders", "Customer", "vOrders");
        assertThat(r.table("dbo", "Orders").rowCount).isEqualTo(10L);
        assertThat(r.table("dbo", "Orders").columns.get(0).dataType()).isEqualTo("int");
        assertThat(r.routines).extracting(x -> x.name).containsExactlyInAnyOrder("PlaceOrder", "GetTier", "trgOrdersAudit");
        assertThat(r.routine("dbo", "GetTier").kind).isEqualTo(Enums.RoutineKind.FUNCTION);
        assertThat(r.routine("dbo", "trgOrdersAudit").triggerEvent).isEqualTo("INSERT OR UPDATE");
        String d = deps(r);
        assertThat(d).contains("TABLE:Orders -FOREIGN_KEY-> TABLE:Customer");
        assertThat(d).contains("ROUTINE:PlaceOrder -REFERENCES-> TABLE:Orders");
        assertThat(d).contains("ROUTINE:PlaceOrder -CALLS-> ROUTINE:GetTier");
        assertThat(d).contains("ROUTINE:PlaceOrder -WRITES-> TABLE:Orders");
        assertThat(d).contains("ROUTINE:PlaceOrder -READS-> TABLE:Customer");
        assertThat(d).contains("TABLE:Orders -TRIGGERS-> ROUTINE:trgOrdersAudit");
        assertThat(d).contains("TABLE:vOrders -REFERENCES-> TABLE:Orders");
        assertThat(d).contains("TABLE:vOrders -REFERENCES-> TABLE:Customer");

        StubJdbc rt = new StubJdbc().on("FROM sys.dm_exec_sessions", List.of(row("session_id", 55, "login_name", "sales_app", "host_name", "ORDERS-7F9C", "program_name", "orders-service", "status", "running", "login_time", t,
                "client_net_address", "10.20.1.2", "client_tcp_port", 40321, "request_status", "running", "start_time", t, "sql_text", "UPDATE dbo.Orders SET Status = 1", "sql_handle", new byte[]{1, 2, 3})));
        Model.RuntimeSample s = new MssqlRuntimeSampler().sample(rt.connection(), db(Enums.Engine.MSSQL), Set.of(), 10);
        assertThat(s.sessions).hasSize(1);
        assertThat(s.sessions.get(0).sqlId).isEqualTo("010203");
        assertThat(s.sessions.get(0).machine).isEqualTo("ORDERS-7F9C");
        assertThat(s.sessions.get(0).port).isEqualTo(40321);
    }

    @Test
    void sqlRefsTrustTheAnalyzerWhenItParsedTheStatementAndScanProceduralTextOtherwise() {
        var pg = org.dbplatform.common.telemetry.Engine.POSTGRES;
        // parsed by SqlAnalyzer (parseOk): its tables are authoritative, no regex noise from literals
        assertThat(SqlRefs.extract("SELECT 'copied from nowhere' AS note, o.id FROM sales.orders o JOIN sales.customer c ON c.id = o.customer_id", pg))
                .containsExactlyInAnyOrder(new SqlRefs.Ref("sales", "orders", false), new SqlRefs.Ref("sales", "customer", false));
        // a plpgsql body is not a statement the parser understands: the regex scan finds the writes and reads of the body
        assertThat(SqlRefs.extract("BEGIN\n INSERT INTO sales.orders(id) VALUES (1);\n PERFORM 1 FROM customer;\n UPDATE sales.inventory SET qty = qty - 1;\nEND", pg))
                .contains(new SqlRefs.Ref("sales", "orders", true), new SqlRefs.Ref(null, "customer", false), new SqlRefs.Ref("sales", "inventory", true));
        // routine definitions are DDL to the analyzer (parseOk, but the body is not walked): the regex scan still finds the body's tables
        assertThat(SqlRefs.extract("CREATE PROCEDURE PlaceOrder AS INSERT INTO dbo.Orders (Id) VALUES (1); SELECT 1 FROM Customer", org.dbplatform.common.telemetry.Engine.MSSQL))
                .contains(new SqlRefs.Ref("dbo", "Orders", true), new SqlRefs.Ref(null, "Customer", false));
        assertThat(SqlRefs.extract(null, pg)).isEmpty();
        assertThat(SqlRefs.extract("   ", null)).isEmpty();
    }

    @Test
    void sqlRefsAndGlobs() {
        List<SqlRefs.Ref> refs = SqlRefs.extract("MERGE INTO sales.inventory i USING (SELECT * FROM sales.product p JOIN stock s ON s.id = p.id) x ON (1=1) WHEN MATCHED THEN UPDATE SET a = 1", org.dbplatform.common.telemetry.Engine.ORACLE);
        assertThat(refs).contains(new SqlRefs.Ref("sales", "inventory", true), new SqlRefs.Ref("sales", "product", false), new SqlRefs.Ref(null, "stock", false));
        assertThat(SqlRefs.extract("-- comment with FROM nowhere\nSELECT 1 FROM dual", org.dbplatform.common.telemetry.Engine.ORACLE)).isEmpty();
        assertThat(SqlRefs.extract("DELETE FROM \"Sales\".\"Orders\" WHERE id = ?", org.dbplatform.common.telemetry.Engine.POSTGRES)).containsExactly(new SqlRefs.Ref("Sales", "Orders", true));
        assertThat(SqlRefs.splitPackageMembers("PACKAGE BODY p AS\n PROCEDURE a IS BEGIN NULL; END;\n FUNCTION b RETURN NUMBER IS BEGIN RETURN 1; END;\nEND;").keySet()).containsExactly("A", "B");
        assertThat(Globs.matchesAny(List.of("orders-service-*"), "orders-service-7f9c")).isTrue();
        assertThat(Globs.matchesAny(List.of("JDBC Thin Client/orders"), "jdbc thin client/orders")).isTrue();
        assertThat(Globs.matchesAny(List.of("orders-*"), "payment-1")).isFalse();
        assertThat(Globs.inAnyCidr(List.of("10.20.0.0/16"), "10.20.3.4")).isTrue();
        assertThat(Globs.inAnyCidr(List.of("10.20.0.0/16"), "10.21.3.4")).isFalse();
        assertThat(Globs.inAnyCidr(List.of("10.20.3.4/32"), "10.20.3.4/32")).isTrue();
    }
}
