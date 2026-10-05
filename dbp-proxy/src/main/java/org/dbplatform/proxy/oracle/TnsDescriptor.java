package org.dbplatform.proxy.oracle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Minimal recursive parser / serialiser for Oracle TNS connect descriptors such as
 * <pre>
 * (DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=proxy)(PORT=1521))
 *   (CONNECT_DATA=(SERVICE_NAME=sales.orders-service)(CID=(PROGRAM=JDBC Thin Client)(HOST=__jdbc__)(USER=app))))
 * </pre>
 * The grammar is {@code node := '(' key [ '=' (text | node+) ] ')'}. Whitespace between nodes is
 * ignored, quoted values ({@code "..."} / {@code '...'}) are kept verbatim including the quotes,
 * unknown keys and their order are preserved so that a parse/serialise round trip only changes what
 * the proxy deliberately rewrote.
 */
public final class TnsDescriptor {
    private final List<TnsNode> roots;

    private TnsDescriptor(List<TnsNode> roots) {
        this.roots = roots;
    }

    public static TnsDescriptor parse(String text) {
        Parser p = new Parser(text);
        List<TnsNode> roots = new ArrayList<>();
        p.skipWs();
        while (!p.eof()) {
            if (p.peek() != '(') {
                throw new TnsParseException("Expected '(' at offset " + p.pos + " in descriptor");
            }
            roots.add(p.node());
            p.skipWs();
        }
        if (roots.isEmpty()) {
            throw new TnsParseException("Empty TNS descriptor");
        }
        return new TnsDescriptor(roots);
    }

    public static boolean looksLikeDescriptor(String text) {
        if (text == null) {
            return false;
        }
        String t = text.strip();
        return t.startsWith("(") && t.endsWith(")");
    }

    public List<TnsNode> roots() {
        return Collections.unmodifiableList(roots);
    }

    /** First root node ({@code DESCRIPTION} in practice). */
    public TnsNode root() {
        return roots.get(0);
    }

    /** First node with that key found at any depth (breadth-first), or {@code null}. */
    public TnsNode findAny(String key) {
        List<TnsNode> level = roots;
        while (!level.isEmpty()) {
            List<TnsNode> next = new ArrayList<>();
            for (TnsNode n : level) {
                if (n.keyIs(key)) {
                    return n;
                }
                next.addAll(n.children());
            }
            level = next;
        }
        return null;
    }

    /** Every node with that key at any depth, document order. */
    public List<TnsNode> findAll(String key) {
        List<TnsNode> out = new ArrayList<>();
        for (TnsNode r : roots) {
            collect(r, key, out);
        }
        return out;
    }

    private static void collect(TnsNode n, String key, List<TnsNode> out) {
        if (n.keyIs(key)) {
            out.add(n);
        }
        for (TnsNode c : n.children()) {
            collect(c, key, out);
        }
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (TnsNode r : roots) {
            r.serialize(sb);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return serialize();
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean eof() {
            return pos >= s.length();
        }

        char peek() {
            return s.charAt(pos);
        }

        void skipWs() {
            while (!eof() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        TnsNode node() {
            expect('(');
            skipWs();
            int start = pos;
            while (!eof() && peek() != '=' && peek() != ')' && peek() != '(') {
                pos++;
            }
            String key = s.substring(start, pos).strip();
            if (key.isEmpty()) {
                throw new TnsParseException("Empty key at offset " + start);
            }
            TnsNode node = new TnsNode(key);
            skipWs();
            if (eof()) {
                throw new TnsParseException("Unterminated node '" + key + "'");
            }
            if (peek() == ')') {
                pos++;
                node.setValue("");
                return node;
            }
            expect('=');
            skipWs();
            if (eof()) {
                throw new TnsParseException("Unterminated node '" + key + "'");
            }
            if (peek() == '(') {
                while (!eof() && peek() == '(') {
                    node.add(node());
                    skipWs();
                }
                expect(')');
                return node;
            }
            if (peek() == ')') {
                pos++;
                node.setValue("");
                return node;
            }
            if (peek() == '"' || peek() == '\'') {
                char q = peek();
                int vs = pos;
                pos++;
                while (!eof() && peek() != q) {
                    pos++;
                }
                if (eof()) {
                    throw new TnsParseException("Unterminated quoted value for '" + key + "'");
                }
                pos++;
                String v = s.substring(vs, pos);
                skipWs();
                expect(')');
                node.setValue(v);
                return node;
            }
            int vs = pos;
            while (!eof() && peek() != ')' && peek() != '(') {
                pos++;
            }
            String v = s.substring(vs, pos).strip();
            if (eof()) {
                throw new TnsParseException("Unterminated value for '" + key + "'");
            }
            if (peek() == '(') {
                throw new TnsParseException("Mixed scalar and nested value for '" + key + "' at offset " + pos);
            }
            expect(')');
            node.setValue(v);
            return node;
        }

        private void expect(char c) {
            if (eof() || s.charAt(pos) != c) {
                throw new TnsParseException("Expected '" + c + "' at offset " + pos
                        + (eof() ? " (end of input)" : " but found '" + s.charAt(pos) + "'"));
            }
            pos++;
        }
    }
}
