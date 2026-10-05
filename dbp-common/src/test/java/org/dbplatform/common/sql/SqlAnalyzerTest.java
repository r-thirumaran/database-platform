package org.dbplatform.common.sql;

import org.dbplatform.common.telemetry.AccessType;
import org.dbplatform.common.telemetry.Engine;
import org.dbplatform.common.telemetry.RoutineRef;
import org.dbplatform.common.telemetry.SqlOperation;
import org.dbplatform.common.telemetry.TableAccess;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class SqlAnalyzerTest {

    private static final SqlAnalyzer ANALYZER = new SqlAnalyzer();

    /**
     * engine | sql | operation | tables ("schema.name:R" / "name:W", comma separated, order-insensitive) |
     * routines ("schema.name" qualified, comma separated) | parseOk expected (true / false / "any")
     */
    static Stream<Arguments> statements() {
        return Stream.of(
                // ---------------- Oracle
                c(Engine.ORACLE, "SELECT /*+ FULL(c) */ c.id, c.email FROM sales.customer c WHERE c.id = :1 AND c.status = :status", SqlOperation.SELECT, "sales.customer:R", "", true),
                c(Engine.ORACLE, "SELECT c.name, o.total FROM customer c, orders o WHERE c.id = o.customer_id(+) AND o.total > 100", SqlOperation.SELECT, "customer:R,orders:R", "", "any"),
                c(Engine.ORACLE, "SELECT e.ename, d.dname FROM emp e, dept d WHERE e.deptno = d.deptno(+) AND e.sal > 1000", SqlOperation.SELECT, "emp:R,dept:R", "", "any"),
                c(Engine.ORACLE, "SELECT sysdate FROM dual", SqlOperation.SELECT, "", "", true),
                c(Engine.ORACLE, "SELECT 1 FROM SYS.DUAL", SqlOperation.SELECT, "", "", true),
                c(Engine.ORACLE, "SELECT sales.order_pkg.calc_total(:1) FROM dual", SqlOperation.SELECT, "", "sales.order_pkg.calc_total", true),
                c(Engine.ORACLE, "SELECT order_pkg.calc_total(:1) AS t, nvl(max(id), 0) FROM orders", SqlOperation.SELECT, "orders:R", "order_pkg.calc_total", true),
                c(Engine.ORACLE, "SELECT * FROM TABLE(sales.pkg.list_orders(:1))", SqlOperation.SELECT, "", "sales.pkg.list_orders", true),
                c(Engine.ORACLE, "INSERT INTO sales.orders (id, total) VALUES (:1, :2) RETURNING id INTO :3", SqlOperation.INSERT, "sales.orders:W", "", true),
                c(Engine.ORACLE, "INSERT ALL WHEN total > 100 THEN INTO big_orders (id) VALUES (id) ELSE INTO small_orders (id) VALUES (id) SELECT id, total FROM orders", SqlOperation.INSERT, "orders:R,big_orders:W,small_orders:W", "", false),
                c(Engine.ORACLE, "INSERT ALL INTO t1 (a) VALUES (x) INTO t2 (a) VALUES (x) SELECT x FROM src", SqlOperation.INSERT, "src:R,t1:W,t2:W", "", false),
                c(Engine.ORACLE, "SELECT id FROM orders WHERE status = 'NEW' FOR UPDATE", SqlOperation.SELECT, "orders:R", "", true),
                c(Engine.ORACLE, "SELECT id FROM orders WHERE status = 'NEW' FOR UPDATE OF orders.id NOWAIT", SqlOperation.SELECT, "orders:R", "", "any"),
                c(Engine.ORACLE, "SELECT id, parent_id FROM org_unit START WITH parent_id IS NULL CONNECT BY PRIOR id = parent_id", SqlOperation.SELECT, "org_unit:R", "", "any"),
                c(Engine.ORACLE, "SELECT level, id FROM org CONNECT BY PRIOR id = parent_id START WITH parent_id IS NULL", SqlOperation.SELECT, "org:R", "", "any"),
                c(Engine.ORACLE, "SELECT \"Id\" FROM \"Sales\".\"Customer\" WHERE \"Email\" = 'a@b.c'", SqlOperation.SELECT, "Sales.Customer:R", "", true),
                c(Engine.ORACLE, "{call sales.order_pkg.place_order(?, ?)}", SqlOperation.CALL, "", "sales.order_pkg.place_order", false),
                c(Engine.ORACLE, "{ call order_pkg.place_order(?) }", SqlOperation.CALL, "", "order_pkg.place_order", false),
                c(Engine.ORACLE, "{? = call fn(?)}", SqlOperation.CALL, "", "fn", false),
                c(Engine.ORACLE, "{?= call sales.fn(?)}", SqlOperation.CALL, "", "sales.fn", false),
                c(Engine.ORACLE, "BEGIN sales.order_pkg.place_order(:1, :2); END;", SqlOperation.CALL, "", "sales.order_pkg.place_order", false),
                c(Engine.ORACLE, "begin\n  order_pkg.place_order(:1);\nend;", SqlOperation.CALL, "", "order_pkg.place_order", false),
                c(Engine.ORACLE, "BEGIN place_order; END;", SqlOperation.CALL, "", "place_order", false),
                c(Engine.ORACLE, "DECLARE v NUMBER; BEGIN v := calc(:1); pkg.proc(v); UPDATE orders SET total = v WHERE id = :2; SELECT count(*) INTO v FROM payments; END;", SqlOperation.CALL, "payments:R,orders:W", "pkg.proc,calc", false),
                c(Engine.ORACLE, "BEGIN INSERT INTO audit_log (msg) VALUES ('x'); COMMIT; END;", SqlOperation.CALL, "audit_log:W", "", false),
                c(Engine.ORACLE, "CALL sales.place_order(?, ?)", SqlOperation.CALL, "", "sales.place_order", "any"),
                c(Engine.ORACLE, "MERGE INTO sales.inventory t USING (SELECT sku, qty FROM staging.stock) s ON (t.sku = s.sku) WHEN MATCHED THEN UPDATE SET t.qty = s.qty WHEN NOT MATCHED THEN INSERT (sku, qty) VALUES (s.sku, s.qty)", SqlOperation.MERGE, "sales.inventory:W,staging.stock:R", "", true),
                c(Engine.ORACLE, "MERGE /*+ APPEND */ INTO inventory t USING staging_stock s ON (t.sku = s.sku) WHEN MATCHED THEN UPDATE SET t.qty = s.qty", SqlOperation.MERGE, "inventory:W,staging_stock:R", "", true),
                c(Engine.ORACLE, "INSERT INTO archive.orders SELECT * FROM sales.orders WHERE created < DATE '2024-01-01'", SqlOperation.INSERT, "archive.orders:W,sales.orders:R", "", true),
                c(Engine.ORACLE, "INSERT /*+ APPEND */ INTO t (a) SELECT b FROM s", SqlOperation.INSERT, "t:W,s:R", "", true),
                c(Engine.ORACLE, "SELECT * FROM customer AS OF TIMESTAMP SYSTIMESTAMP - INTERVAL '1' HOUR WHERE id = 1", SqlOperation.SELECT, "customer:R", "", "any"),
                c(Engine.ORACLE, "UPDATE /*+ INDEX(o) */ orders o SET status = 'X' WHERE id = 1", SqlOperation.UPDATE, "orders:W", "", true),
                c(Engine.ORACLE, "DELETE /*+ PARALLEL */ FROM orders WHERE id = 1", SqlOperation.DELETE, "orders:W", "", true),
                c(Engine.ORACLE, "DELETE orders WHERE id = 1", SqlOperation.DELETE, "orders:W", "", "any"),
                c(Engine.ORACLE, "SELECT * FROM sales.customer@remote_db", SqlOperation.SELECT, "sales.customer:R", "", "any"),
                c(Engine.ORACLE, "SELECT * FROM a, b, c WHERE a.id = b.id AND b.id = c.id", SqlOperation.SELECT, "a:R,b:R,c:R", "", true),
                c(Engine.ORACLE, "LOCK TABLE orders IN EXCLUSIVE MODE", SqlOperation.OTHER, "orders:W", "", "any"),
                c(Engine.ORACLE, "ALTER SESSION SET NLS_DATE_FORMAT = 'YYYY'", SqlOperation.OTHER, "", "", "any"),
                c(Engine.ORACLE, "COMMIT", SqlOperation.TXN, "", "", true),
                c(Engine.ORACLE, "ROLLBACK", SqlOperation.TXN, "", "", true),
                c(Engine.ORACLE, "SAVEPOINT sp1", SqlOperation.TXN, "", "", true),
                c(Engine.ORACLE, "SET TRANSACTION READ ONLY", SqlOperation.TXN, "", "", true),
                c(Engine.ORACLE, "WITH recent AS (SELECT * FROM orders WHERE created > ?) SELECT r.id, c.name FROM recent r JOIN customer c ON c.id = r.customer_id", SqlOperation.SELECT, "orders:R,customer:R", "", true),
                c(Engine.ORACLE, "WITH del AS (SELECT id FROM orders WHERE status = 'X') DELETE FROM orders WHERE id IN (SELECT id FROM del)", SqlOperation.DELETE, "orders:W", "", true),
                c(Engine.ORACLE, "SELECT * FROM (SELECT id FROM orders) sub WHERE EXISTS (SELECT 1 FROM payments p WHERE p.order_id = sub.id)", SqlOperation.SELECT, "orders:R,payments:R", "", true),
                c(Engine.ORACLE, "UPDATE orders SET status = 'SHIPPED', shipped_at = SYSDATE WHERE id IN (SELECT order_id FROM shipments WHERE carrier = 'UPS')", SqlOperation.UPDATE, "orders:W,shipments:R", "", true),
                c(Engine.ORACLE, "DELETE FROM order_lines WHERE order_id IN (SELECT id FROM orders WHERE status = 'CANCELLED')", SqlOperation.DELETE, "order_lines:W,orders:R", "", true),
                c(Engine.ORACLE, "TRUNCATE TABLE sales.audit_log", SqlOperation.DDL, "sales.audit_log:W", "", "any"),
                c(Engine.ORACLE, "DROP TABLE tmp_x", SqlOperation.DDL, "tmp_x:W", "", "any"),
                c(Engine.ORACLE, "ALTER TABLE orders ADD note VARCHAR2(100)", SqlOperation.DDL, "orders:W", "", "any"),
                c(Engine.ORACLE, "CREATE TABLE sales.customer_tmp AS SELECT * FROM sales.customer", SqlOperation.DDL, "sales.customer_tmp:W,sales.customer:R", "", "any"),
                c(Engine.ORACLE, "SELECT q'[it's]' FROM dual", SqlOperation.SELECT, "", "", "any"),
                // ---------------- PostgreSQL
                c(Engine.POSTGRES, "SELECT id FROM customer WHERE id = $1 AND email = $2", SqlOperation.SELECT, "customer:R", "", true),
                c(Engine.POSTGRES, "INSERT INTO inventory (sku, qty) VALUES ($1, $2) ON CONFLICT (sku) DO UPDATE SET qty = inventory.qty + EXCLUDED.qty", SqlOperation.INSERT, "inventory:W", "", true),
                c(Engine.POSTGRES, "UPDATE orders SET status = 'SHIPPED' WHERE id = 5 RETURNING id, status", SqlOperation.UPDATE, "orders:W", "", true),
                c(Engine.POSTGRES, "UPDATE orders o SET total = s.total FROM order_summary s WHERE s.order_id = o.id", SqlOperation.UPDATE, "orders:W,order_summary:R", "", true),
                c(Engine.POSTGRES, "DELETE FROM orders USING customer c WHERE orders.customer_id = c.id AND c.status = 'CLOSED'", SqlOperation.DELETE, "orders:W,customer:R", "", true),
                c(Engine.POSTGRES, "DELETE FROM orders o USING customer c, blacklist b WHERE o.customer_id = c.id AND c.email = b.email", SqlOperation.DELETE, "orders:W,customer:R,blacklist:R", "", "any"),
                c(Engine.POSTGRES, "SELECT x::int FROM t WHERE y = 'z'::text", SqlOperation.SELECT, "t:R", "", true),
                c(Engine.POSTGRES, "SELECT my_schema.my_fn(1) AS v", SqlOperation.SELECT, "", "my_schema.my_fn", true),
                c(Engine.POSTGRES, "SELECT * FROM place_order(1, 2)", SqlOperation.SELECT, "", "place_order", true),
                c(Engine.POSTGRES, "SELECT now(), coalesce(a, 0), lower(b) FROM t", SqlOperation.SELECT, "t:R", "", true),
                c(Engine.POSTGRES, "BEGIN", SqlOperation.TXN, "", "", true),
                c(Engine.POSTGRES, "BEGIN ISOLATION LEVEL SERIALIZABLE", SqlOperation.TXN, "", "", true),
                c(Engine.POSTGRES, "START TRANSACTION", SqlOperation.TXN, "", "", true),
                c(Engine.POSTGRES, "END", SqlOperation.TXN, "", "", true),
                c(Engine.POSTGRES, "SET search_path TO sales", SqlOperation.OTHER, "", "", "any"),
                c(Engine.POSTGRES, "CREATE INDEX ix ON t (id)", SqlOperation.DDL, "t:W", "", "any"),
                c(Engine.POSTGRES, "TRUNCATE t", SqlOperation.DDL, "t:W", "", "any"),
                c(Engine.POSTGRES, "SELECT * FROM \"Sales\".\"Customer\" c LEFT JOIN \"Orders\" o ON o.\"CustomerId\" = c.\"Id\"", SqlOperation.SELECT, "Sales.Customer:R,Orders:R", "", true),
                c(Engine.POSTGRES, "WITH moved AS (DELETE FROM orders WHERE status = 'X' RETURNING *) INSERT INTO archive_orders SELECT * FROM moved", SqlOperation.INSERT, "orders:W,archive_orders:W", "", "any"),
                c(Engine.POSTGRES, "INSERT INTO t (a) VALUES ($1), ($2)", SqlOperation.INSERT, "t:W", "", true),
                c(Engine.POSTGRES, "select count(*) from pg_stat_activity", SqlOperation.SELECT, "pg_stat_activity:R", "", true),
                c(Engine.POSTGRES, "SELECT a.id FROM a JOIN b ON a.id = b.a_id LEFT JOIN c ON c.id = b.c_id RIGHT JOIN d ON d.id = c.d_id", SqlOperation.SELECT, "a:R,b:R,c:R,d:R", "", true),
                // ---------------- SQL Server
                c(Engine.MSSQL, "SELECT TOP 10 [Id], [Name] FROM [dbo].[Customer] WITH (NOLOCK) WHERE [Status] = 'A'", SqlOperation.SELECT, "dbo.Customer:R", "", true),
                c(Engine.MSSQL, "SELECT TOP (10) c.Id FROM dbo.Customer c WITH (NOLOCK) INNER JOIN dbo.Orders o WITH (NOLOCK) ON o.CustomerId = c.Id", SqlOperation.SELECT, "dbo.Customer:R,dbo.Orders:R", "", true),
                c(Engine.MSSQL, "EXEC dbo.usp_place_order @customer_id = 1, @total = 20.5", SqlOperation.CALL, "", "dbo.usp_place_order", "any"),
                c(Engine.MSSQL, "EXEC sales.usp_place_order ?, ?", SqlOperation.CALL, "", "sales.usp_place_order", "any"),
                c(Engine.MSSQL, "EXECUTE usp_report", SqlOperation.CALL, "", "usp_report", "any"),
                c(Engine.MSSQL, "EXEC @rc = dbo.usp_x", SqlOperation.CALL, "", "dbo.usp_x", "any"),
                c(Engine.MSSQL, "UPDATE c SET c.Status = 'X' FROM dbo.Customer c INNER JOIN dbo.Orders o ON o.CustomerId = c.Id WHERE o.Total > 10", SqlOperation.UPDATE, "dbo.Customer:W,dbo.Orders:R", "", true),
                c(Engine.MSSQL, "DELETE o FROM dbo.Orders o INNER JOIN dbo.Customer c ON c.Id = o.CustomerId WHERE c.Status = 'X'", SqlOperation.DELETE, "dbo.Orders:W,dbo.Customer:R", "", true),
                c(Engine.MSSQL, "SELECT * INTO #tmp FROM dbo.Orders WHERE Total > 10", SqlOperation.SELECT, "dbo.Orders:R", "", true),
                c(Engine.MSSQL, "DECLARE @x INT; SET @x = 1; SELECT * FROM dbo.Orders WHERE Id = @x", SqlOperation.SELECT, "dbo.Orders:R", "", "any"),
                c(Engine.MSSQL, "BEGIN TRANSACTION", SqlOperation.TXN, "", "", true),
                c(Engine.MSSQL, "BEGIN TRAN", SqlOperation.TXN, "", "", true),
                c(Engine.MSSQL, "INSERT INTO [dbo].[Orders] ([Id], [Total]) OUTPUT INSERTED.Id VALUES (1, 2.5)", SqlOperation.INSERT, "dbo.Orders:W", "", "any"),
                c(Engine.MSSQL, "SELECT * FROM Sales.Customer c CROSS APPLY dbo.fn_orders(c.Id) f", SqlOperation.SELECT, "Sales.Customer:R", "dbo.fn_orders", "any"),
                c(Engine.MSSQL, "MERGE dbo.Inventory AS t USING dbo.Staging AS s ON t.Sku = s.Sku WHEN MATCHED THEN UPDATE SET t.Qty = s.Qty WHEN NOT MATCHED THEN INSERT (Sku, Qty) VALUES (s.Sku, s.Qty);", SqlOperation.MERGE, "dbo.Inventory:W,dbo.Staging:R", "", "any"),
                c(Engine.MSSQL, "SELECT * FROM dbo.Customer WHERE Id = 0x1F", SqlOperation.SELECT, "dbo.Customer:R", "", true),
                c(Engine.MSSQL, "SELECT * FROM [Sales].[Customer] c LEFT JOIN [dbo].[Orders] o ON o.[CustomerId] = c.[Id] WHERE c.[Email] LIKE N'%@x.com'", SqlOperation.SELECT, "Sales.Customer:R,dbo.Orders:R", "", true),
                // ---------------- ANSI / H2 / garbage
                c(Engine.H2, "SELECT * FROM customer WHERE id = ?", SqlOperation.SELECT, "customer:R", "", true),
                c(Engine.OTHER, "SELECT * FROM t WHERE c IN (1,2,3) AND d = 'x''y' AND e = \"Quoted\"", SqlOperation.SELECT, "t:R", "", true),
                c(Engine.OTHER, "INSERT INTO t (a) VALUES (?), (?)", SqlOperation.INSERT, "t:W", "", true),
                c(Engine.OTHER, "select * from t1 full outer join t2 on t1.id = t2.id", SqlOperation.SELECT, "t1:R,t2:R", "", true),
                c(Engine.OTHER, "(SELECT id FROM a) UNION (SELECT id FROM b)", SqlOperation.SELECT, "a:R,b:R", "", true),
                c(Engine.OTHER, "SELECT 1", SqlOperation.SELECT, "", "", true),
                c(Engine.OTHER, "GRANT SELECT ON sales.customer TO app", SqlOperation.DDL, "", "", "any"),
                c(Engine.OTHER, "this is not sql at all ((( ", SqlOperation.OTHER, "", "", false),
                c(Engine.OTHER, "", SqlOperation.OTHER, "", "", false),
                c(Engine.OTHER, "   ", SqlOperation.OTHER, "", "", false),
                c(Engine.OTHER, "SELECT", SqlOperation.SELECT, "", "", false),
                c(Engine.OTHER, "SELECT * FROM", SqlOperation.SELECT, "", "", false),
                c(Engine.OTHER, "'unterminated", SqlOperation.OTHER, "", "", false),
                c(Engine.OTHER, ";;;", SqlOperation.OTHER, "", "", false),
                c(Engine.OTHER, "/* only a comment */", SqlOperation.OTHER, "", "", false),
                c(Engine.OTHER, "-- line comment only", SqlOperation.OTHER, "", "", false),
                c(Engine.OTHER, "SELECT * FROM t WHERE (((((((((((((((((((((((((((((((((((((((((((((((", SqlOperation.SELECT, "t:R", "", false),
                c(Engine.OTHER, "\u0000\u0001\uFFFF SELECT \uD83D\uDE00 FROM t", SqlOperation.OTHER, "t:R", "", false)
        );
    }

    private static Arguments c(Engine engine, String sql, SqlOperation op, String tables, String routines, Object parseOk) {
        return Arguments.of(engine, sql, op, tables, routines, parseOk);
    }

    @ParameterizedTest(name = "[{index}] {0}: {1}")
    @MethodSource("statements")
    void analyzesStatement(Engine engine, String sql, SqlOperation op, String tables, String routines, Object parseOk) {
        SqlAnalysis a = ANALYZER.analyze(sql, engine);
        assertThat(a.operation()).as("operation").isEqualTo(op);
        assertThat(render(a.tables())).as("tables").containsExactlyInAnyOrderElementsOf(split(tables));
        assertThat(a.routines().stream().map(RoutineRef::qualifiedName).toList()).as("routines")
                .containsExactlyInAnyOrderElementsOf(split(routines));
        if (parseOk instanceof Boolean b) {
            assertThat(a.parseOk()).as("parseOk").isEqualTo(b);
        }
        assertThat(a.sqlHash()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(a.normalizedSql()).doesNotContain("\n").doesNotContain("  ");
        assertThat(a.normalizedSql().length()).isLessThanOrEqualTo(SqlNormalizer.MAX_LENGTH);
    }

    @Test
    void mergeAndInsertSelectMarkTargetWriteAndSourcesRead() {
        SqlAnalysis a = ANALYZER.analyze("MERGE INTO inv t USING (SELECT s.sku, s.qty FROM stock s JOIN warehouse w ON w.id = s.wh_id) src ON (t.sku = src.sku) WHEN MATCHED THEN UPDATE SET t.qty = src.qty", Engine.ORACLE);
        assertThat(a.writtenTables()).containsExactly(TableAccess.write(null, "inv"));
        assertThat(a.readTables()).containsExactlyInAnyOrder(TableAccess.read(null, "stock"), TableAccess.read(null, "warehouse"));
        assertThat(a.writes()).isTrue();

        SqlAnalysis self = ANALYZER.analyze("INSERT INTO orders (id) SELECT max(id) + 1 FROM orders", Engine.POSTGRES);
        assertThat(self.tables()).containsExactly(TableAccess.write(null, "orders")); // WRITE wins, no duplicate
    }

    @Test
    void routineNamesSplitByEngineConvention() {
        assertThat(ANALYZER.analyze("{call sales.order_pkg.place_order(?)}", Engine.ORACLE).routines())
                .containsExactly(new RoutineRef("sales", "order_pkg.place_order"));
        assertThat(ANALYZER.analyze("{call order_pkg.place_order(?)}", Engine.ORACLE).routines())
                .containsExactly(new RoutineRef(null, "order_pkg.place_order"));
        assertThat(ANALYZER.analyze("{call place_order(?)}", Engine.ORACLE).routines())
                .containsExactly(new RoutineRef(null, "place_order"));
        assertThat(ANALYZER.analyze("EXEC dbo.usp_place_order 1", Engine.MSSQL).routines())
                .containsExactly(new RoutineRef("dbo", "usp_place_order"));
        assertThat(ANALYZER.analyze("EXEC salesdb.dbo.usp_place_order 1", Engine.MSSQL).routines())
                .containsExactly(new RoutineRef("dbo", "usp_place_order"));
        assertThat(ANALYZER.analyze("SELECT public.place_order(1)", Engine.POSTGRES).routines())
                .containsExactly(new RoutineRef("public", "place_order"));
        assertThat(ANALYZER.analyze("{call \"Sales\".\"Order_Pkg\".\"Place\"(?)}", Engine.ORACLE).routines())
                .containsExactly(new RoutineRef("Sales", "Order_Pkg.Place"));
    }

    @Test
    void tablesAreReportedAsWrittenWithQuotingRemoved() {
        assertThat(ANALYZER.analyze("SELECT * FROM \"Sales\".\"Customer\"", Engine.POSTGRES).tables())
                .containsExactly(TableAccess.read("Sales", "Customer"));
        assertThat(ANALYZER.analyze("SELECT * FROM [dbo].[Order Lines]", Engine.MSSQL).tables())
                .containsExactly(TableAccess.read("dbo", "Order Lines"));
        assertThat(ANALYZER.analyze("select * from Sales.Customer", Engine.ORACLE).tables())
                .containsExactly(TableAccess.read("Sales", "Customer"));
        assertThat(ANALYZER.analyze("select * from customer", Engine.ORACLE).tables().get(0).schema()).isNull();
    }

    @Test
    void columnsAreBestEffortTableDotColumn() {
        assertThat(ANALYZER.analyze("SELECT c.id, c.email FROM sales.customer c WHERE c.status = :1", Engine.ORACLE).columns())
                .containsExactly("customer.id", "customer.email", "customer.status");
        assertThat(ANALYZER.analyze("SELECT id FROM orders WHERE status = 'NEW'", Engine.ORACLE).columns())
                .containsExactly("orders.id", "orders.status");
        assertThat(ANALYZER.analyze("INSERT INTO sales.orders (id, total) VALUES (:1, :2)", Engine.ORACLE).columns())
                .containsExactly("orders.id", "orders.total");
        assertThat(ANALYZER.analyze("UPDATE orders o SET total = s.total FROM order_summary s WHERE s.order_id = o.id", Engine.POSTGRES).columns())
                .contains("order_summary.order_id", "orders.id");
        assertThat(ANALYZER.analyze("SELECT * FROM a, b WHERE x = 1", Engine.ORACLE).columns()).isEmpty(); // ambiguous
        assertThat(ANALYZER.analyze("SELECT id, sysdate, rownum FROM orders", Engine.ORACLE).columns())
                .containsExactly("orders.id");
        assertThat(ANALYZER.analyze("SELECT [Id] FROM [dbo].[Customer] WHERE [Status] = 'A'", Engine.MSSQL).columns())
                .containsExactly("Customer.Id", "Customer.Status");
    }

    @Test
    void normalizedSqlAndHashAreStableAcrossLiteralsAndFormatting() {
        SqlAnalysis a = ANALYZER.analyze("SELECT id FROM orders WHERE status = 'NEW' AND total > 100", Engine.POSTGRES);
        SqlAnalysis b = ANALYZER.analyze("select id\n  from orders\n where status = 'OLD'   and total > 5", Engine.POSTGRES);
        assertThat(a.normalizedSql()).isEqualTo("SELECT id FROM orders WHERE status = ? AND total > ?");
        assertThat(b.normalizedSql()).isEqualTo("select id from orders where status = ? and total > ?");
        assertThat(a.sqlHash()).isNotEqualTo(b.sqlHash()); // case preserved → different text
        assertThat(ANALYZER.analyze("SELECT id FROM orders WHERE status = 'X' AND total > 1", Engine.POSTGRES).sqlHash()).isEqualTo(a.sqlHash());
        assertThat(a.sqlHash()).isEqualTo(SqlNormalizer.sha256Hex(a.normalizedSql())).isEqualTo(SqlAnalyzer.hash("SELECT id FROM orders WHERE status = 'Y' AND total > 2"));
    }

    @Test
    void longStatementsAreTruncatedAndHugeStatementsSkipTheParser() {
        String in = "SELECT * FROM t WHERE c IN (" + "?,".repeat(5000) + "?)";
        SqlAnalysis a = ANALYZER.analyze(in, Engine.ORACLE);
        assertThat(a.normalizedSql()).hasSize(SqlNormalizer.MAX_LENGTH);
        assertThat(a.tables()).containsExactly(TableAccess.read(null, "t"));
        String huge = "SELECT * FROM big WHERE x = 'y' " + "AND 1 = 1 ".repeat(20_000);
        SqlAnalysis h = ANALYZER.analyze(huge, Engine.ORACLE);
        assertThat(h.parseOk()).isFalse();
        assertThat(h.tables()).containsExactly(TableAccess.read(null, "big"));
    }

    @Test
    void cacheReturnsSameInstanceAndIsBounded() {
        SqlAnalyzer small = new SqlAnalyzer(3);
        SqlAnalysis first = small.analyze("SELECT 1 FROM a", Engine.ORACLE);
        assertThat(small.analyze("SELECT 1 FROM a", Engine.ORACLE)).isSameAs(first);
        assertThat(small.analyze("SELECT 1 FROM a", Engine.POSTGRES)).isNotSameAs(first); // keyed by engine too
        small.analyze("SELECT 1 FROM b", Engine.ORACLE);
        small.analyze("SELECT 1 FROM c", Engine.ORACLE);
        assertThat(small.cacheSize()).isEqualTo(3);
        small.clearCache();
        assertThat(small.cacheSize()).isZero();
        assertThat(new SqlAnalyzer(0).analyze("SELECT 1 FROM a", Engine.ORACLE).tables()).hasSize(1);
        assertThat(ANALYZER.analyze(null, null).operation()).isEqualTo(SqlOperation.OTHER);
    }

    @Test
    void neverThrowsOnMutatedInput() {
        List<String> base = statements().map(a -> (String) a.get()[1]).toList();
        Random rnd = new Random(42);
        String alphabet = "'\"()[]{};:$@.,-/*+ \n\tabcXYZ019?#";
        SqlAnalyzer fresh = new SqlAnalyzer(0);
        for (int i = 0; i < 1500; i++) {
            String s = base.get(rnd.nextInt(base.size()));
            StringBuilder sb = new StringBuilder(s);
            int edits = 1 + rnd.nextInt(4);
            for (int e = 0; e < edits && !sb.isEmpty(); e++) {
                int pos = rnd.nextInt(sb.length());
                switch (rnd.nextInt(3)) {
                    case 0 -> sb.deleteCharAt(pos);
                    case 1 -> sb.insert(pos, alphabet.charAt(rnd.nextInt(alphabet.length())));
                    default -> sb.setLength(pos);
                }
            }
            String mutated = sb.toString();
            Engine engine = Engine.values()[rnd.nextInt(Engine.values().length)];
            assertThatCode(() -> {
                SqlAnalysis a = fresh.analyze(mutated, engine);
                assertThat(a).isNotNull();
                assertThat(a.sqlHash()).hasSize(64);
            }).as("input: %s", mutated).doesNotThrowAnyException();
        }
    }

    @Test
    void isThreadSafeAndFastEnough() throws Exception {
        List<String> corpus = statements().map(a -> (String) a.get()[1]).filter(s -> !s.isBlank()).toList();
        SqlAnalyzer uncached = new SqlAnalyzer(0);
        for (int i = 0; i < 5; i++) {
            for (String s : corpus) {
                uncached.analyze(s, Engine.ORACLE); // warm up
            }
        }
        int threads = 4;
        int perThread = 400;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            long t0 = System.nanoTime();
            List<Future<Integer>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    int n = 0;
                    for (int i = 0; i < perThread; i++) {
                        SqlAnalysis a = uncached.analyze(corpus.get(i % corpus.size()), Engine.values()[i % 3]);
                        n += a.sqlHash().length();
                    }
                    return n;
                }));
            }
            for (Future<Integer> f : futures) {
                assertThat(f.get()).isEqualTo(perThread * 64);
            }
            // a wall-clock ceiling, not a throughput floor: the point is thread safety, and a loaded CI box
            // must not turn this into a flaky test (~0.3 s expected, see README for the real numbers)
            assertThat(Duration.ofNanos(System.nanoTime() - t0)).as("1600 uncached analyses on 4 threads").isLessThan(Duration.ofSeconds(60));
        } finally {
            pool.shutdownNow();
        }
        long t0 = System.nanoTime();
        for (int i = 0; i < 20_000; i++) {
            ANALYZER.analyze(corpus.get(i % corpus.size()), Engine.ORACLE);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).as("20000 cached analyses").isLessThan(Duration.ofSeconds(30));
    }

    private static List<String> render(List<TableAccess> tables) {
        return tables.stream().map(t -> t.qualifiedName() + ":" + (t.access() == AccessType.WRITE ? "W" : "R")).toList();
    }

    private static List<String> split(String csv) {
        return csv.isBlank() ? List.of() : Arrays.stream(csv.split(",")).map(String::trim).toList();
    }
}
