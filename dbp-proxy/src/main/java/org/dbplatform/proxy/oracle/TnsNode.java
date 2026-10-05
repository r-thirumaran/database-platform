package org.dbplatform.proxy.oracle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One {@code (KEY=value)} or {@code (KEY=(child)(child))} element of an Oracle TNS descriptor.
 * Keys are compared case-insensitively; unknown keys, their order and their text are preserved.
 */
public final class TnsNode {
    private final String key;
    private String value;
    private final List<TnsNode> children = new ArrayList<>();

    public TnsNode(String key) {
        this(key, null);
    }

    public TnsNode(String key, String value) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("TNS node key must not be empty");
        }
        this.key = key;
        this.value = value;
    }

    public String key() {
        return key;
    }

    /** Scalar value, or {@code null} when the node only has children (or is empty). */
    public String value() {
        return value;
    }

    public List<TnsNode> children() {
        return children;
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    public boolean keyIs(String other) {
        return key.equalsIgnoreCase(other);
    }

    public TnsNode setValue(String newValue) {
        this.value = newValue;
        this.children.clear();
        return this;
    }

    public TnsNode add(TnsNode child) {
        this.value = null;
        this.children.add(child);
        return this;
    }

    /** First direct child with the given key (case-insensitive) or {@code null}. */
    public TnsNode child(String childKey) {
        for (TnsNode c : children) {
            if (c.keyIs(childKey)) {
                return c;
            }
        }
        return null;
    }

    public List<TnsNode> childrenNamed(String childKey) {
        List<TnsNode> out = new ArrayList<>();
        for (TnsNode c : children) {
            if (c.keyIs(childKey)) {
                out.add(c);
            }
        }
        return out;
    }

    /** Walk a path of keys ({@code "CONNECT_DATA", "SERVICE_NAME"}), returning the node or {@code null}. */
    public TnsNode find(String... path) {
        TnsNode cur = this;
        for (String p : path) {
            cur = cur.child(p);
            if (cur == null) {
                return null;
            }
        }
        return cur;
    }

    public String findValue(String... path) {
        TnsNode n = find(path);
        return n == null ? null : n.value;
    }

    /** Get-or-create the child with the given key and set its scalar value; returns this node for chaining. */
    public TnsNode put(String childKey, String childValue) {
        TnsNode c = child(childKey);
        if (c == null) {
            add(new TnsNode(childKey, childValue));
        } else {
            c.setValue(childValue);
        }
        return this;
    }

    public boolean remove(String childKey) {
        return children.removeIf(c -> c.keyIs(childKey));
    }

    /** Serialise this node (and its subtree) back to TNS syntax. */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        serialize(sb);
        return sb.toString();
    }

    void serialize(StringBuilder sb) {
        sb.append('(').append(key).append('=');
        if (!children.isEmpty()) {
            for (TnsNode c : children) {
                c.serialize(sb);
            }
        } else if (value != null) {
            sb.append(value);
        }
        sb.append(')');
    }

    @Override
    public String toString() {
        return serialize();
    }

    String upperKey() {
        return key.toUpperCase(Locale.ROOT);
    }
}
