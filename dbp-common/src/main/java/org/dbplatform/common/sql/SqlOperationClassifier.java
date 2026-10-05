package org.dbplatform.common.sql;

import org.dbplatform.common.telemetry.SqlOperation;

import java.util.Locale;

/**
 * Classifies a statement by its leading keywords without parsing it. Handles leading comments and
 * parentheses, JDBC {@code {call …}} escapes, CTEs ({@code WITH … INSERT/UPDATE/DELETE/MERGE/SELECT}),
 * Oracle anonymous blocks ({@code BEGIN … END;}, {@code DECLARE … BEGIN … END;}) versus transaction
 * {@code BEGIN}, T-SQL batches starting with {@code DECLARE @…}, and DDL / transaction control keywords.
 */
public final class SqlOperationClassifier {

    private SqlOperationClassifier() {}

    public static SqlOperation classify(String sql) {
        if (sql == null) {
            return SqlOperation.OTHER;
        }
        try {
            return classifyInternal(sql);
        } catch (RuntimeException e) {
            return SqlOperation.OTHER;
        }
    }

    private static SqlOperation classifyInternal(String sql) {
        int pos = skipLeadingNoise(sql, 0);
        if (pos >= sql.length()) {
            return SqlOperation.OTHER;
        }
        if (sql.charAt(pos) == '{') {
            return classifyJdbcEscape(sql, pos);
        }
        String word = wordAt(sql, pos);
        int afterWord = skipLeadingNoise(sql, pos + word.length());
        String next = wordAt(sql, afterWord);

        return switch (word) {
            case "SELECT" -> SqlOperation.SELECT;
            case "INSERT" -> SqlOperation.INSERT;
            case "UPDATE" -> SqlOperation.UPDATE;
            case "DELETE" -> SqlOperation.DELETE;
            case "MERGE" -> SqlOperation.MERGE;
            case "UPSERT", "REPLACE" -> SqlOperation.MERGE;
            case "WITH" -> firstTopLevelDml(sql, afterWord, SqlOperation.SELECT);
            case "VALUES", "TABLE" -> SqlOperation.SELECT;
            case "CALL", "EXEC", "EXECUTE" -> SqlOperation.CALL;
            case "DECLARE" -> afterWord < sql.length() && sql.charAt(afterWord) == '@'
                    ? firstTopLevelDml(sql, afterWord, SqlOperation.OTHER)  // T-SQL batch
                    : SqlOperation.CALL;                                    // PL/SQL anonymous block
            case "BEGIN" -> classifyBegin(next);
            case "START", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE", "ABORT", "END" -> SqlOperation.TXN;
            case "SET" -> switch (next) {
                case "TRANSACTION", "AUTOCOMMIT", "CONSTRAINTS", "XACT_ABORT", "IMPLICIT_TRANSACTIONS" -> SqlOperation.TXN;
                default -> SqlOperation.OTHER;
            };
            case "ALTER" -> switch (next) {
                case "SESSION", "SYSTEM", "DATABASE", "USER", "ROLE" -> SqlOperation.OTHER;
                default -> SqlOperation.DDL;
            };
            case "CREATE", "DROP", "TRUNCATE", "GRANT", "REVOKE", "COMMENT", "RENAME", "ANALYZE",
                    "ANALYSE", "VACUUM", "REINDEX", "CLUSTER", "PURGE", "FLASHBACK", "AUDIT", "NOAUDIT",
                    "ASSOCIATE", "DISASSOCIATE", "REFRESH", "DBCC" -> SqlOperation.DDL;
            default -> SqlOperation.OTHER;
        };
    }

    private static SqlOperation classifyBegin(String next) {
        return switch (next) {
            case "", "TRANSACTION", "TRAN", "WORK", "ISOLATION", "READ", "DEFERRABLE", "NOT", "DISTRIBUTED" -> SqlOperation.TXN;
            default -> SqlOperation.CALL; // BEGIN pkg.proc(...); END;
        };
    }

    private static SqlOperation classifyJdbcEscape(String sql, int pos) {
        // {call x(?)}  {? = call x(?)}  {?=call x}  {oj ...}
        int i = pos + 1;
        int n = sql.length();
        while (i < n && (Character.isWhitespace(sql.charAt(i)) || sql.charAt(i) == '?' || sql.charAt(i) == '=')) {
            i++;
        }
        String w = wordAt(sql, i);
        if (w.equals("CALL")) {
            return SqlOperation.CALL;
        }
        if (w.equals("OJ")) {
            return SqlOperation.SELECT;
        }
        return SqlOperation.OTHER;
    }

    /**
     * Scans forward from {@code from} and returns the first DML keyword found at parenthesis depth 0
     * (outside strings/comments), or {@code dflt}. Used for CTE-led statements and T-SQL batches.
     */
    static SqlOperation firstTopLevelDml(String sql, int from, SqlOperation dflt) {
        int n = sql.length();
        int depth = 0;
        int i = from;
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
                i++;
            } else if (c == ')') {
                depth = Math.max(0, depth - 1);
                i++;
            } else if (c == '\'') {
                i = skipQuoted(sql, i, '\'');
            } else if (c == '"') {
                i = skipQuoted(sql, i, '"');
            } else if (c == '[') {
                i = skipQuoted(sql, i, ']');
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int nl = sql.indexOf('\n', i);
                i = nl < 0 ? n : nl + 1;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else if (Identifiers.isIdentifierStart(c) && (i == 0 || !Identifiers.isIdentifierChar(sql.charAt(i - 1)))) {
                String w = wordAt(sql, i);
                if (depth == 0) {
                    switch (w) {
                        case "SELECT": return SqlOperation.SELECT;
                        case "INSERT": return SqlOperation.INSERT;
                        case "UPDATE": return SqlOperation.UPDATE;
                        case "DELETE": return SqlOperation.DELETE;
                        case "MERGE": return SqlOperation.MERGE;
                        case "EXEC": case "EXECUTE": case "CALL": return SqlOperation.CALL;
                        default: break;
                    }
                }
                i += Math.max(1, w.length());
            } else {
                i++;
            }
        }
        return dflt;
    }

    /** Skips whitespace, comments and opening parentheses starting at {@code pos}. */
    static int skipLeadingNoise(String sql, int pos) {
        int n = sql.length();
        int i = pos;
        while (i < n) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c) || c == '(' || c == ';') {
                i++;
            } else if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int nl = sql.indexOf('\n', i);
                i = nl < 0 ? n : nl + 1;
            } else if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
            } else {
                break;
            }
        }
        return i;
    }

    /** Upper-case identifier word starting at {@code pos} (empty when none). */
    static String wordAt(String sql, int pos) {
        int n = sql.length();
        int j = pos;
        while (j < n && Identifiers.isIdentifierChar(sql.charAt(j))) {
            j++;
        }
        return j > pos ? sql.substring(pos, j).toUpperCase(Locale.ROOT) : "";
    }

    private static int skipQuoted(String sql, int i, char close) {
        int n = sql.length();
        int j = i + 1;
        while (j < n) {
            if (sql.charAt(j) == close) {
                if (j + 1 < n && sql.charAt(j + 1) == close && close != ']') {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return n;
    }
}
