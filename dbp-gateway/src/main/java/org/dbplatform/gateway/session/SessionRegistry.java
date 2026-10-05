package org.dbplatform.gateway.session;

import org.dbplatform.gateway.control.AuthException;
import org.dbplatform.gateway.control.SessionResolution;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * All open logical sessions of the gateway plus the counters enforcing the global and per-grant caps.
 */
public final class SessionRegistry {

    private final String gatewayId;
    private final int maxSessions;
    private final Map<String, LogicalSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> perGrant = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> perDatasource = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong();

    public SessionRegistry(String gatewayId, int maxSessions) {
        this.gatewayId = gatewayId;
        this.maxSessions = maxSessions;
    }

    public String nextSessionId() {
        return gatewayId + "-" + counter.incrementAndGet();
    }

    /**
     * Reserves a slot for a new session, enforcing {@code DBP_GATEWAY_MAX_SESSIONS} and the grant's
     * {@code maxLogicalConnections}.
     */
    public synchronized void register(LogicalSession session, SessionResolution resolution) throws AuthException {
        if (maxSessions > 0 && sessions.size() >= maxSessions) {
            throw AuthException.rejected("too many logical connections: gateway limit of " + maxSessions + " reached");
        }
        AtomicInteger grantCount = perGrant.computeIfAbsent(resolution.grantKey(), k -> new AtomicInteger());
        if (resolution.maxLogicalConnections() > 0 && grantCount.get() >= resolution.maxLogicalConnections()) {
            throw AuthException.rejected("too many logical connections for application '"
                    + resolution.identity().application() + "' on datasource '" + resolution.datasource().name()
                    + "' (limit " + resolution.maxLogicalConnections() + ")");
        }
        grantCount.incrementAndGet();
        perDatasource.computeIfAbsent(resolution.datasource().name(), k -> new AtomicInteger()).incrementAndGet();
        sessions.put(session.id(), session);
    }

    public synchronized void unregister(LogicalSession session) {
        if (sessions.remove(session.id()) != null) {
            AtomicInteger g = perGrant.get(session.grantKey());
            if (g != null) {
                g.decrementAndGet();
            }
            AtomicInteger d = perDatasource.get(session.datasource());
            if (d != null) {
                d.decrementAndGet();
            }
        }
    }

    public int size() {
        return sessions.size();
    }

    public int countFor(String datasource) {
        AtomicInteger n = perDatasource.get(datasource);
        return n == null ? 0 : n.get();
    }

    public int pinnedFor(String datasource) {
        int n = 0;
        for (LogicalSession s : sessions.values()) {
            if (s.datasource().equals(datasource) && s.isPinned()) {
                n++;
            }
        }
        return n;
    }

    public int pinned() {
        int n = 0;
        for (LogicalSession s : sessions.values()) {
            if (s.isPinned()) {
                n++;
            }
        }
        return n;
    }

    public int executing() {
        int n = 0;
        for (LogicalSession s : sessions.values()) {
            if (s.isExecuting()) {
                n++;
            }
        }
        return n;
    }

    public Collection<LogicalSession> sessions() {
        return List.copyOf(sessions.values());
    }

    public List<String> datasources() {
        return new ArrayList<>(perDatasource.keySet());
    }
}
