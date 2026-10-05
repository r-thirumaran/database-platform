package org.dbplatform.gateway.session;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings remembered per logical session and re-applied to every newly pinned physical connection.
 */
public final class SessionSettings {

    /** Marker: not set by the client. */
    public static final int UNSET = Integer.MIN_VALUE;

    private boolean autoCommit = true;
    private int transactionIsolation = UNSET;
    private Boolean readOnly;
    private String schema;
    private String catalog;
    private int networkTimeoutMillis = UNSET;
    // read by the admin endpoint and telemetry while the handler thread mutates it
    private final Map<String, String> clientInfo = new java.util.concurrent.ConcurrentHashMap<>();

    public boolean autoCommit() {
        return autoCommit;
    }

    public void autoCommit(boolean v) {
        autoCommit = v;
    }

    public int transactionIsolation() {
        return transactionIsolation;
    }

    public void transactionIsolation(int level) {
        transactionIsolation = level;
    }

    public Boolean readOnly() {
        return readOnly;
    }

    public void readOnly(boolean v) {
        readOnly = v;
    }

    public String schema() {
        return schema;
    }

    public void schema(String s) {
        schema = s;
    }

    public String catalog() {
        return catalog;
    }

    public void catalog(String c) {
        catalog = c;
    }

    public int networkTimeoutMillis() {
        return networkTimeoutMillis;
    }

    public void networkTimeoutMillis(int ms) {
        networkTimeoutMillis = ms;
    }

    /** Live view of the client info entries (name → value, {@code null} values are removed). */
    public Map<String, String> clientInfo() {
        return clientInfo;
    }

    public void clientInfo(String name, String value) {
        if (value == null) {
            clientInfo.remove(name);
        } else {
            clientInfo.put(name, value);
        }
    }
}
