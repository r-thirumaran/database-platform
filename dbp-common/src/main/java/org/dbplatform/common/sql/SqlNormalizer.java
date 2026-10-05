package org.dbplatform.common.sql;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Produces the canonical form of a statement used for grouping and hashing:
 * <ul>
 *   <li>string literals ({@code '…'}, {@code N'…'}, {@code q'[…]'}, {@code $$…$$}), numbers ({@code 42},
 *       {@code 1.5e3}, {@code 0x1F}) and bind markers ({@code :1}, {@code :name}, {@code $1}) become {@code ?};</li>
 *   <li>comments are removed, except Oracle hints {@code /*+ … *}{@code /} which are kept;</li>
 *   <li>whitespace is collapsed to single spaces (none after {@code (}, none before {@code ,}, {@code )}, {@code ;});</li>
 *   <li>identifier case and quoting are preserved; {@code @vars}, {@code ?} and {@code ::casts} are untouched;</li>
 *   <li>the result is truncated to {@value #MAX_LENGTH} characters.</li>
 * </ul>
 * Single pass, no regular expressions; roughly 50-100 ns per character.
 */
public final class SqlNormalizer {

    public static final int MAX_LENGTH = 4000;

    private SqlNormalizer() {}

    public static String normalize(String sql) {
        return normalize(sql, MAX_LENGTH);
    }

    public static String normalize(String sql, int maxLength) {
        if (sql == null) {
            return "";
        }
        String out = scan(sql, Mode.NORMALIZE);
        return out.length() > maxLength ? out.substring(0, maxLength) : out;
    }

    /**
     * Prepares SQL for the regex extractors: all comments removed (hints too), string literal contents
     * replaced by {@code ''}, whitespace collapsed; numbers, binds and identifiers untouched.
     */
    public static String scrub(String sql) {
        return sql == null ? "" : scan(sql, Mode.SCRUB);
    }

    /** Lower-case hex SHA-256 of the UTF-8 bytes of {@code text}. */
    public static String sha256Hex(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private enum Mode { NORMALIZE, SCRUB }

    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    private static String scan(String sql, Mode mode) {
        final boolean normalize = mode == Mode.NORMALIZE;
        final int n = sql.length();
        StringBuilder out = new StringBuilder(Math.min(n, 8192));
        boolean pendingSpace = false;
        int i = 0;
        while (i < n) {
            char c = sql.charAt(i);

            // whitespace: collapse
            if (Character.isWhitespace(c)) {
                pendingSpace = true;
                i++;
                continue;
            }

            // line comment
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                i += 2;
                while (i < n && sql.charAt(i) != '\n') {
                    i++;
                }
                pendingSpace = true;
                continue;
            }

            // block comment (hint kept when normalising)
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                int stop = end < 0 ? n : end + 2;
                boolean hint = i + 2 < n && sql.charAt(i + 2) == '+';
                if (hint && normalize) {
                    pendingSpace = emitSpace(out, pendingSpace, ' ');
                    out.append(collapseWhitespace(sql.substring(i, stop)));
                } else {
                    pendingSpace = true;
                }
                i = stop;
                continue;
            }

            // string literal, with optional N / n / E prefix or Oracle q'…' quoting
            if (c == '\'') {
                i = skipString(sql, i);
                pendingSpace = emitToken(out, pendingSpace, normalize ? "?" : "''");
                continue;
            }
            if ((c == 'N' || c == 'n' || c == 'E' || c == 'e' || c == 'B' || c == 'b' || c == 'X' || c == 'x')
                    && i + 1 < n && sql.charAt(i + 1) == '\'' && (pendingSpace || !prevIsIdentChar(out))) {
                i = skipString(sql, i + 1);
                pendingSpace = emitToken(out, pendingSpace, normalize ? "?" : "''");
                continue;
            }
            if ((c == 'q' || c == 'Q') && i + 2 < n && sql.charAt(i + 1) == '\'' && (pendingSpace || !prevIsIdentChar(out))) {
                i = skipOracleAltQuoted(sql, i);
                pendingSpace = emitToken(out, pendingSpace, normalize ? "?" : "''");
                continue;
            }

            // PostgreSQL dollar quoting $$…$$ / $tag$…$tag$
            if (c == '$' && i + 1 < n && (sql.charAt(i + 1) == '$' || Character.isLetter(sql.charAt(i + 1)))) {
                int tagEnd = i + 1;
                while (tagEnd < n && (Character.isLetterOrDigit(sql.charAt(tagEnd)) || sql.charAt(tagEnd) == '_')) {
                    tagEnd++;
                }
                if (tagEnd < n && sql.charAt(tagEnd) == '$') {
                    String tag = sql.substring(i, tagEnd + 1);
                    int close = sql.indexOf(tag, tagEnd + 1);
                    i = close < 0 ? n : close + tag.length();
                    pendingSpace = emitToken(out, pendingSpace, normalize ? "?" : "''");
                    continue;
                }
            }

            // quoted identifiers: copied verbatim
            if (c == '"' || c == '`' || c == '[') {
                char close = c == '[' ? ']' : c;
                int j = i + 1;
                while (j < n) {
                    if (sql.charAt(j) == close) {
                        if (j + 1 < n && sql.charAt(j + 1) == close && close != ']') {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                int stop = Math.min(n, j + 1);
                pendingSpace = emitSpace(out, pendingSpace, sql.charAt(i));
                out.append(sql, i, stop);
                i = stop;
                continue;
            }

            // identifiers / keywords (may contain digits after the first char)
            if (Identifiers.isIdentifierStart(c)) {
                int j = i + 1;
                while (j < n && Identifiers.isIdentifierChar(sql.charAt(j))) {
                    j++;
                }
                pendingSpace = emitSpace(out, pendingSpace, c);
                out.append(sql, i, j);
                i = j;
                continue;
            }

            // numbers
            if (Character.isDigit(c) || (c == '.' && i + 1 < n && Character.isDigit(sql.charAt(i + 1)) && !prevIsIdentChar(out))) {
                int j = skipNumber(sql, i);
                if (normalize) {
                    pendingSpace = emitToken(out, pendingSpace, "?");
                } else {
                    pendingSpace = emitSpace(out, pendingSpace, c);
                    out.append(sql, i, j);
                }
                i = j;
                continue;
            }

            // bind variables :1 / :name (not :: casts, not := assignment), $1 positional parameters
            if (c == ':' && i + 1 < n) {
                char d = sql.charAt(i + 1);
                if (d == ':' ) {
                    out.append("::");
                    pendingSpace = false;
                    i += 2;
                    continue;
                }
                if (Character.isDigit(d) || Identifiers.isIdentifierStart(d)) {
                    int j = i + 1;
                    while (j < n && Identifiers.isIdentifierChar(sql.charAt(j))) {
                        j++;
                    }
                    if (normalize) {
                        pendingSpace = emitToken(out, pendingSpace, "?");
                    } else {
                        pendingSpace = emitSpace(out, pendingSpace, c);
                        out.append(sql, i, j);
                    }
                    i = j;
                    continue;
                }
            }
            if (c == '$' && i + 1 < n && Character.isDigit(sql.charAt(i + 1))) {
                int j = i + 1;
                while (j < n && Character.isDigit(sql.charAt(j))) {
                    j++;
                }
                if (normalize) {
                    pendingSpace = emitToken(out, pendingSpace, "?");
                } else {
                    pendingSpace = emitSpace(out, pendingSpace, c);
                    out.append(sql, i, j);
                }
                i = j;
                continue;
            }

            // punctuation
            if (c == ',' || c == ')' || c == ';') {
                out.append(c); // no space before
                pendingSpace = false;
                i++;
                continue;
            }
            if (c == '(') {
                pendingSpace = emitSpace(out, pendingSpace, c);
                out.append(c);
                pendingSpace = false; // no space after
                i++;
                continue;
            }
            pendingSpace = emitSpace(out, pendingSpace, c);
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Emits a pending space when needed; returns the new pendingSpace state (always false). */
    private static boolean emitSpace(StringBuilder out, boolean pendingSpace, char next) {
        if (pendingSpace && !out.isEmpty()) {
            char last = out.charAt(out.length() - 1);
            if (last != '(' && last != ' ') {
                out.append(' ');
            }
        }
        return false;
    }

    private static boolean emitToken(StringBuilder out, boolean pendingSpace, String token) {
        // a replaced literal glued to an identifier (e.g. N'x' handled above) still needs separation
        if (!pendingSpace && !out.isEmpty()) {
            char last = out.charAt(out.length() - 1);
            if (Identifiers.isIdentifierChar(last) || last == '?' || last == '\'' || last == '"') {
                out.append(' ');
            }
        }
        emitSpace(out, pendingSpace, token.charAt(0));
        out.append(token);
        return false;
    }

    private static boolean prevIsIdentChar(StringBuilder out) {
        return !out.isEmpty() && Identifiers.isIdentifierChar(out.charAt(out.length() - 1));
    }

    /** {@code i} points at the opening quote; returns index after the closing quote ({@code ''} escapes handled). */
    private static int skipString(String sql, int i) {
        int n = sql.length();
        int j = i + 1;
        while (j < n) {
            char c = sql.charAt(j);
            if (c == '\'') {
                if (j + 1 < n && sql.charAt(j + 1) == '\'') {
                    j += 2;
                    continue;
                }
                return j + 1;
            }
            j++;
        }
        return n;
    }

    /** {@code i} points at the {@code q}; handles {@code q'[…]'}, {@code q'{…}'}, {@code q'(…)'}, {@code q'<…>'}, {@code q'X…X'}. */
    private static int skipOracleAltQuoted(String sql, int i) {
        int n = sql.length();
        char open = sql.charAt(i + 2);
        char close = switch (open) {
            case '[' -> ']';
            case '{' -> '}';
            case '(' -> ')';
            case '<' -> '>';
            default -> open;
        };
        int j = i + 3;
        while (j + 1 < n) {
            if (sql.charAt(j) == close && sql.charAt(j + 1) == '\'') {
                return j + 2;
            }
            j++;
        }
        return n;
    }

    private static int skipNumber(String sql, int i) {
        int n = sql.length();
        int j = i;
        if (sql.charAt(j) == '0' && j + 1 < n && (sql.charAt(j + 1) == 'x' || sql.charAt(j + 1) == 'X')) {
            j += 2;
            while (j < n && Character.digit(sql.charAt(j), 16) >= 0) {
                j++;
            }
            return j;
        }
        while (j < n && Character.isDigit(sql.charAt(j))) {
            j++;
        }
        if (j < n && sql.charAt(j) == '.') {
            j++;
            while (j < n && Character.isDigit(sql.charAt(j))) {
                j++;
            }
        }
        if (j < n && (sql.charAt(j) == 'e' || sql.charAt(j) == 'E')) {
            int k = j + 1;
            if (k < n && (sql.charAt(k) == '+' || sql.charAt(k) == '-')) {
                k++;
            }
            if (k < n && Character.isDigit(sql.charAt(k))) {
                while (k < n && Character.isDigit(sql.charAt(k))) {
                    k++;
                }
                j = k;
            }
        }
        // SQL Server / Oracle numeric suffixes (1d, 1f, 1L)
        if (j < n && (sql.charAt(j) == 'd' || sql.charAt(j) == 'D' || sql.charAt(j) == 'f' || sql.charAt(j) == 'F')
                && (j + 1 >= n || !Identifiers.isIdentifierChar(sql.charAt(j + 1)))) {
            j++;
        }
        return j;
    }

    private static String collapseWhitespace(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean ws = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                ws = true;
            } else {
                if (ws && !sb.isEmpty()) {
                    sb.append(' ');
                }
                ws = false;
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
