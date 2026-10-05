package org.dbplatform.common.sql;

import org.dbplatform.common.telemetry.SqlOperation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class SqlOperationClassifierTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "SELECT 1                                                   | SELECT",
            "  /* c */ -- x\n select * from t                           | SELECT",
            "(SELECT id FROM a) UNION (SELECT id FROM b)                 | SELECT",
            "((SELECT 1))                                                | SELECT",
            "WITH x AS (SELECT 1 FROM a) SELECT * FROM x                 | SELECT",
            "WITH x AS (SELECT 1 FROM a) INSERT INTO t SELECT * FROM x   | INSERT",
            "WITH x AS (SELECT 1 FROM a), y AS (SELECT 2) UPDATE t SET a = 1 | UPDATE",
            "WITH x AS (DELETE FROM a RETURNING *) DELETE FROM t         | DELETE",
            "with recursive r as (select 1) merge into t using r on (1=1) when matched then update set a = 1 | MERGE",
            "VALUES (1, 2)                                               | SELECT",
            "INSERT INTO t VALUES (1)                                    | INSERT",
            "insert all into a values (1) select 1 from dual             | INSERT",
            "UPDATE t SET a = 1                                          | UPDATE",
            "DELETE FROM t                                               | DELETE",
            "DELETE t WHERE id = 1                                       | DELETE",
            "MERGE INTO t USING s ON (1=1) WHEN MATCHED THEN UPDATE SET a = 1 | MERGE",
            "REPLACE INTO t VALUES (1)                                   | MERGE",
            "CALL proc()                                                 | CALL",
            "EXEC dbo.proc                                               | CALL",
            "execute proc                                                | CALL",
            "{call proc(?)}                                              | CALL",
            "{ ? = call fn(?) }                                          | CALL",
            "{?=call fn(?)}                                              | CALL",
            "BEGIN proc(:1); END;                                        | CALL",
            "begin\n  pkg.proc;\nend;                                    | CALL",
            "DECLARE v NUMBER; BEGIN v := 1; END;                        | CALL",
            "DECLARE @x INT; SET @x = 1; SELECT * FROM t WHERE id = @x   | SELECT",
            "DECLARE @x INT; INSERT INTO t VALUES (@x)                   | INSERT",
            "DECLARE @x INT                                              | OTHER",
            "BEGIN                                                       | TXN",
            "BEGIN;                                                      | TXN",
            "BEGIN TRANSACTION                                           | TXN",
            "BEGIN TRAN                                                  | TXN",
            "BEGIN WORK                                                  | TXN",
            "BEGIN ISOLATION LEVEL SERIALIZABLE                          | TXN",
            "START TRANSACTION                                           | TXN",
            "COMMIT                                                      | TXN",
            "COMMIT WORK                                                 | TXN",
            "ROLLBACK TO SAVEPOINT sp1                                   | TXN",
            "SAVEPOINT sp1                                               | TXN",
            "RELEASE SAVEPOINT sp1                                       | TXN",
            "END                                                         | TXN",
            "SET TRANSACTION ISOLATION LEVEL READ COMMITTED              | TXN",
            "SET AUTOCOMMIT = 0                                          | TXN",
            "SET NOCOUNT ON                                              | OTHER",
            "SET search_path TO sales                                    | OTHER",
            "CREATE TABLE t (id INT)                                     | DDL",
            "create or replace procedure p as begin null; end;           | DDL",
            "ALTER TABLE t ADD c INT                                     | DDL",
            "ALTER SESSION SET NLS_DATE_FORMAT = 'X'                     | OTHER",
            "DROP TABLE t                                                | DDL",
            "TRUNCATE TABLE t                                            | DDL",
            "TRUNCATE t                                                  | DDL",
            "GRANT SELECT ON t TO u                                      | DDL",
            "COMMENT ON TABLE t IS 'x'                                   | DDL",
            "ANALYZE t                                                   | DDL",
            "EXPLAIN SELECT 1                                            | OTHER",
            "EXPLAIN PLAN FOR SELECT 1                                   | OTHER",
            "SHOW search_path                                            | OTHER",
            "USE salesdb                                                 | OTHER",
            "LOCK TABLE t IN EXCLUSIVE MODE                              | OTHER",
            "this is not sql                                             | OTHER",
            "''                                                          | OTHER",
            "'                                                           | OTHER",
            ";                                                           | OTHER",
            "/* nothing */                                               | OTHER",
    })
    void classifies(String sql, SqlOperation expected) {
        String in = sql == null ? null : sql.trim();
        assertThat(SqlOperationClassifier.classify(in)).as(in).isEqualTo(expected);
    }

    @org.junit.jupiter.api.Test
    void nullIsOther() {
        assertThat(SqlOperationClassifier.classify(null)).isEqualTo(SqlOperation.OTHER);
        assertThat(SqlOperationClassifier.classify("")).isEqualTo(SqlOperation.OTHER);
    }
}
