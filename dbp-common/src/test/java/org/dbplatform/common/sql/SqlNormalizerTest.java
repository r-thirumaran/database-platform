package org.dbplatform.common.sql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlNormalizerTest {

    @Test
    void replacesLiteralsNumbersAndBinds() {
        assertThat(SqlNormalizer.normalize("SELECT * FROM t WHERE a = 'x' AND b = 42 AND c = :1 AND d = :name AND e = $2 AND f = 1.5e3 AND g = 0x1F AND h = N'u' AND i = -7 AND j = .5"))
                .isEqualTo("SELECT * FROM t WHERE a = ? AND b = ? AND c = ? AND d = ? AND e = ? AND f = ? AND g = ? AND h = ? AND i = -? AND j = ?");
        assertThat(SqlNormalizer.normalize("SELECT * FROM t WHERE c IN (1, 2,3) AND d = 'x''y' AND e = DATE '2024-01-01'"))
                .isEqualTo("SELECT * FROM t WHERE c IN (?, ?,?) AND d = ? AND e = DATE ?");
        assertThat(SqlNormalizer.normalize("SELECT q'[it's]' FROM dual")).isEqualTo("SELECT ? FROM dual");
        assertThat(SqlNormalizer.normalize("SELECT $$it's$$, $tag$x$tag$ FROM t")).isEqualTo("SELECT ?, ? FROM t");
        assertThat(SqlNormalizer.normalize("SELECT * FROM t WHERE ts > TIMESTAMP '2024-01-01 00:00:00'")).isEqualTo("SELECT * FROM t WHERE ts > TIMESTAMP ?");
    }

    @Test
    void preservesIdentifiersCaseQuotingCastsAssignmentsAndVariables() {
        assertThat(SqlNormalizer.normalize("SELECT t1.col2 FROM tab3 t1")).isEqualTo("SELECT t1.col2 FROM tab3 t1");
        assertThat(SqlNormalizer.normalize("SELECT \"Id\" FROM [dbo].[T 1] WHERE \"Id\" = 'a' AND `x` = 1"))
                .isEqualTo("SELECT \"Id\" FROM [dbo].[T 1] WHERE \"Id\" = ? AND `x` = ?");
        assertThat(SqlNormalizer.normalize("SELECT x::int FROM t WHERE y = 'z'::text")).isEqualTo("SELECT x::int FROM t WHERE y = ?::text");
        assertThat(SqlNormalizer.normalize("BEGIN v := 1; END;")).isEqualTo("BEGIN v := ?; END;");
        assertThat(SqlNormalizer.normalize("SELECT @p1, ? FROM t WHERE id = @id")).isEqualTo("SELECT @p1, ? FROM t WHERE id = @id");
        assertThat(SqlNormalizer.normalize("select Id from Customer where Email = 'a'")).isEqualTo("select Id from Customer where Email = ?");
    }

    @Test
    void collapsesWhitespaceAndRemovesCommentsButKeepsHints() {
        assertThat(SqlNormalizer.normalize("  SELECT  *\n\tFROM\r\n t  WHERE a=1 ")).isEqualTo("SELECT * FROM t WHERE a=?");
        assertThat(SqlNormalizer.normalize("SELECT /*+ FULL(t)\n  INDEX(x) */ * FROM t -- trailing\n WHERE /* block */ a = 1"))
                .isEqualTo("SELECT /*+ FULL(t) INDEX(x) */ * FROM t WHERE a = ?");
        assertThat(SqlNormalizer.normalize("/* leading */ SELECT 1")).isEqualTo("SELECT ?");
        assertThat(SqlNormalizer.normalize("SELECT a , b FROM t WHERE ( a = 1 ) ;")).isEqualTo("SELECT a, b FROM t WHERE (a = ?);");
    }

    @Test
    void truncatesAndHandlesEdgeCases() {
        String in = "SELECT * FROM t WHERE c IN (" + "?,".repeat(5000) + "?)";
        assertThat(SqlNormalizer.normalize(in)).hasSize(SqlNormalizer.MAX_LENGTH);
        assertThat(SqlNormalizer.normalize(in, 10)).isEqualTo("SELECT * F");
        assertThat(SqlNormalizer.normalize(null)).isEmpty();
        assertThat(SqlNormalizer.normalize("")).isEmpty();
        assertThat(SqlNormalizer.normalize("'unterminated")).isEqualTo("?");
        assertThat(SqlNormalizer.normalize("/* unterminated")).isEmpty();
        assertThat(SqlNormalizer.normalize("\"unterminated")).isEqualTo("\"unterminated");
        assertThat(SqlNormalizer.normalize("q'[unterminated")).isEqualTo("?");
    }

    @Test
    void scrubRemovesCommentsAndLiteralContentOnly() {
        assertThat(SqlNormalizer.scrub("SELECT /*+ hint */ a FROM t -- c\n WHERE b = 'it''s' AND n = 42 AND x = :1"))
                .isEqualTo("SELECT a FROM t WHERE b = '' AND n = 42 AND x = :1");
    }

    @Test
    void sha256HexIsDeterministic() {
        assertThat(SqlNormalizer.sha256Hex("")).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(SqlNormalizer.sha256Hex("SELECT ?")).hasSize(64).isEqualTo(SqlNormalizer.sha256Hex("SELECT ?"));
        assertThat(SqlNormalizer.sha256Hex(null)).isEqualTo(SqlNormalizer.sha256Hex(""));
    }
}
