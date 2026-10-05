package org.dbplatform.common.sql;

import org.dbplatform.common.telemetry.SqlOperation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SqlOperationClassifierTest {

    static Stream<Arguments> cases() {
        return Stream.of(
                row("SELECT 1", SqlOperation.SELECT),
                row("  /* c */ -- x\n select * from t", SqlOperation.SELECT),
                row("(SELECT id FROM a) UNION (SELECT id FROM b)", SqlOperation.SELECT),
                row("((SELECT 1))", SqlOperation.SELECT),
                row("WITH x AS (SELECT 1 FROM a) SELECT * FROM x", SqlOperation.SELECT),
                row("WITH x AS (SELECT 1 FROM a) INSERT INTO t SELECT * FROM x", SqlOperation.INSERT),
                row("WITH x AS (SELECT 1 FROM a), y AS (SELECT 2) UPDATE t SET a = 1", SqlOperation.UPDATE),
                row("WITH x AS (DELETE FROM a RETURNING *) DELETE FROM t", SqlOperation.DELETE),
                row("with recursive r as (select 1) merge into t using r on (1=1) when matched then update set a = 1", SqlOperation.MERGE),
                row("VALUES (1, 2)", SqlOperation.SELECT),
                row("INSERT INTO t VALUES (1)", SqlOperation.INSERT),
                row("insert all into a values (1) select 1 from dual", SqlOperation.INSERT),
                row("UPDATE t SET a = 1", SqlOperation.UPDATE),
                row("DELETE FROM t", SqlOperation.DELETE),
                row("DELETE t WHERE id = 1", SqlOperation.DELETE),
                row("MERGE INTO t USING s ON (1=1) WHEN MATCHED THEN UPDATE SET a = 1", SqlOperation.MERGE),
                row("REPLACE INTO t VALUES (1)", SqlOperation.MERGE),
                row("CALL proc()", SqlOperation.CALL),
                row("EXEC dbo.proc", SqlOperation.CALL),
                row("execute proc", SqlOperation.CALL),
                row("{call proc(?)}", SqlOperation.CALL),
                row("{ ? = call fn(?) }", SqlOperation.CALL),
                row("{?=call fn(?)}", SqlOperation.CALL),
                row("BEGIN proc(:1); END;", SqlOperation.CALL),
                row("begin\n  pkg.proc;\nend;", SqlOperation.CALL),
                row("DECLARE v NUMBER; BEGIN v := 1; END;", SqlOperation.CALL),
                row("DECLARE @x INT; SET @x = 1; SELECT * FROM t WHERE id = @x", SqlOperation.SELECT),
                row("DECLARE @x INT; INSERT INTO t VALUES (@x)", SqlOperation.INSERT),
                row("DECLARE @x INT", SqlOperation.OTHER),
                row("BEGIN", SqlOperation.TXN),
                row("BEGIN;", SqlOperation.TXN),
                row("BEGIN TRANSACTION", SqlOperation.TXN),
                row("BEGIN TRAN", SqlOperation.TXN),
                row("BEGIN WORK", SqlOperation.TXN),
                row("BEGIN ISOLATION LEVEL SERIALIZABLE", SqlOperation.TXN),
                row("START TRANSACTION", SqlOperation.TXN),
                row("COMMIT", SqlOperation.TXN),
                row("COMMIT WORK", SqlOperation.TXN),
                row("ROLLBACK TO SAVEPOINT sp1", SqlOperation.TXN),
                row("SAVEPOINT sp1", SqlOperation.TXN),
                row("RELEASE SAVEPOINT sp1", SqlOperation.TXN),
                row("END", SqlOperation.TXN),
                row("SET TRANSACTION ISOLATION LEVEL READ COMMITTED", SqlOperation.TXN),
                row("SET AUTOCOMMIT = 0", SqlOperation.TXN),
                row("SET NOCOUNT ON", SqlOperation.OTHER),
                row("SET search_path TO sales", SqlOperation.OTHER),
                row("CREATE TABLE t (id INT)", SqlOperation.DDL),
                row("create or replace procedure p as begin null; end;", SqlOperation.DDL),
                row("ALTER TABLE t ADD c INT", SqlOperation.DDL),
                row("ALTER SESSION SET NLS_DATE_FORMAT = 'X'", SqlOperation.OTHER),
                row("DROP TABLE t", SqlOperation.DDL),
                row("TRUNCATE TABLE t", SqlOperation.DDL),
                row("TRUNCATE t", SqlOperation.DDL),
                row("GRANT SELECT ON t TO u", SqlOperation.DDL),
                row("COMMENT ON TABLE t IS 'x'", SqlOperation.DDL),
                row("ANALYZE t", SqlOperation.DDL),
                row("EXPLAIN SELECT 1", SqlOperation.OTHER),
                row("EXPLAIN PLAN FOR SELECT 1", SqlOperation.OTHER),
                row("SHOW search_path", SqlOperation.OTHER),
                row("USE salesdb", SqlOperation.OTHER),
                row("LOCK TABLE t IN EXCLUSIVE MODE", SqlOperation.OTHER),
                row("this is not sql", SqlOperation.OTHER),
                row("''", SqlOperation.OTHER),
                row("'", SqlOperation.OTHER),
                row(";", SqlOperation.OTHER),
                row("/* nothing */", SqlOperation.OTHER)
        );
    }

    private static Arguments row(String sql, SqlOperation op) {
        return Arguments.of(sql, op);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("cases")
    void classifies(String sql, SqlOperation expected) {
        assertThat(SqlOperationClassifier.classify(sql)).as("%s", sql).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void nullIsOther() {
        assertThat(SqlOperationClassifier.classify(null)).isEqualTo(SqlOperation.OTHER);
        assertThat(SqlOperationClassifier.classify("")).isEqualTo(SqlOperation.OTHER);
    }
}
