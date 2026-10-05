package org.dbplatform.common.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Identifier helpers: quoting, multi-part names and reserved words used as guards by the regex extractors. */
final class Identifiers {

    /** Words that can never be a table name in the positions the regex extractors look at. */
    static final Set<String> RESERVED = Set.copyOf(List.of(
            "SELECT", "FROM", "WHERE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "NATURAL",
            "ON", "USING", "SET", "VALUES", "INTO", "INSERT", "UPDATE", "DELETE", "MERGE", "AS", "AND", "OR",
            "NOT", "IN", "IS", "NULL", "LIKE", "BETWEEN", "EXISTS", "GROUP", "ORDER", "BY", "HAVING", "UNION",
            "MINUS", "EXCEPT", "INTERSECT", "ALL", "DISTINCT", "WITH", "START", "CONNECT", "PRIOR", "FOR",
            "LIMIT", "OFFSET", "FETCH", "RETURNING", "WHEN", "THEN", "ELSE", "END", "CASE", "PARTITION",
            "SAMPLE", "LATERAL", "ONLY", "TABLE", "DUAL", "UNNEST", "TABLESAMPLE", "WINDOW", "QUALIFY",
            "MATCH_RECOGNIZE", "NOWAIT", "WAIT", "SKIP", "LOCKED", "MODEL", "PIVOT", "UNPIVOT", "OF",
            "MATCHED", "DO", "NOTHING", "CONFLICT", "TOP", "OUTPUT", "DEFAULT", "IF", "EXISTS", "CASCADE",
            "RESTRICT", "PURGE", "INDEX", "VIEW", "SEQUENCE", "TRIGGER", "PROCEDURE", "FUNCTION", "PACKAGE",
            "BEGIN", "DECLARE", "LOOP", "WHILE", "RETURN", "RAISE", "EXCEPTION", "COMMIT", "ROLLBACK",
            "FIRST", "NEXT", "ROWS", "ROW", "PERCENT", "TIES", "ASC", "DESC", "NULLS", "LAST", "OVER",
            "INTERVAL", "DATE", "TIMESTAMP", "TIME", "CURRENT_DATE", "CURRENT_TIMESTAMP", "SYSDATE",
            "SYSTIMESTAMP", "TRUE", "FALSE", "UNKNOWN", "ANY", "SOME", "ESCAPE", "COLLATE", "CAST",
            "STRAIGHT_JOIN", "APPLY", "SEMI", "ANTI", "GLOBAL", "LOCAL", "TEMPORARY", "TEMP", "UNLOGGED",
            "MATERIALIZED", "RECURSIVE", "SEARCH", "CYCLE", "KEY", "SHARE", "MODE", "EXCLUSIVE", "ACCESS",
            "ROW_NUMBER", "RANK", "NO", "ACTION", "ADD", "DROP", "ALTER", "CREATE", "TRUNCATE", "COLUMN",
            "CONSTRAINT", "PRIMARY", "FOREIGN", "REFERENCES", "UNIQUE", "CHECK", "GRANT", "REVOKE",
            "TO", "PUBLIC", "OPTION", "ADMIN", "WORK", "TRANSACTION", "ISOLATION", "LEVEL", "READ", "WRITE",
            "COMMITTED", "UNCOMMITTED", "REPEATABLE", "SERIALIZABLE", "SAVEPOINT", "RELEASE", "LOCK",
            "SIBLINGS", "NOCYCLE", "RETURN", "EXIT", "CONTINUE", "GOTO", "OPEN", "CLOSE", "ELSIF", "ELSEIF",
            "EXEC", "EXECUTE", "CALL", "IMMEDIATE", "BULK", "COLLECT", "FORALL", "PRAGMA", "TYPE", "RECORD",
            "CURSOR", "SUBTYPE", "CONSTANT", "VARIADIC", "ARRAY", "MULTISET", "EXCLUDED", "NEW", "OLD", "INSERTED", "DELETED"));

    private Identifiers() {}

    /** Removes one level of {@code "…"}, {@code […]} or {@code `…`} quoting; returns other input unchanged. */
    static String unquote(String ident) {
        if (ident == null) {
            return null;
        }
        String s = ident.trim();
        int n = s.length();
        if (n >= 2) {
            char a = s.charAt(0);
            char z = s.charAt(n - 1);
            if ((a == '"' && z == '"') || (a == '[' && z == ']') || (a == '`' && z == '`')) {
                return s.substring(1, n - 1).replace("\"\"", "\"").replace("]]", "]");
            }
        }
        return s;
    }

    /** Splits a (possibly quoted) multi-part name on dots outside quotes, removes quoting and a trailing {@code @dblink}. */
    static List<String> splitParts(String name) {
        List<String> parts = new ArrayList<>(3);
        if (name == null) {
            return parts;
        }
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (quote != 0) {
                cur.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '`') {
                quote = c;
                cur.append(c);
            } else if (c == '[') {
                quote = ']';
                cur.append(c);
            } else if (c == '.') {
                parts.add(unquote(cur.toString()));
                cur.setLength(0);
            } else if (c == '@') {
                break; // db link
            } else if (!Character.isWhitespace(c)) {
                cur.append(c);
            }
        }
        if (!cur.isEmpty()) {
            parts.add(unquote(cur.toString()));
        }
        parts.removeIf(String::isEmpty);
        return parts;
    }

    static boolean isReserved(String ident) {
        return ident != null && RESERVED.contains(ident.toUpperCase(Locale.ROOT));
    }

    static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#';
    }

    static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }
}
