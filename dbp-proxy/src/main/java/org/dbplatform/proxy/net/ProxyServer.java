package org.dbplatform.proxy.net;

import org.dbplatform.proxy.config.ListenerConfig;
import org.dbplatform.proxy.config.ProxyConfigDocument;
import org.dbplatform.proxy.identity.IdentityResolver;
import org.dbplatform.proxy.registry.LiveConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns the listeners. {@link #apply(ProxyConfigDocument)} hot-reloads a configuration: identity rules
 * and quotas are swapped atomically, listeners are added, removed or re-bound as needed and listeners
 * whose socket is unchanged just get the new route table — live connections are never dropped.
 * {@link #repair()} re-binds listeners that failed to bind (port in use at start-up) or whose accept
 * loop died; the configuration sources call it on every poll.
 */
public final class ProxyServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ProxyServer.class);
    public static final String SHUTDOWN_REASON = "proxy shutdown";

    private final ProxyRuntime rt;
    private final Map<String, ListenerRuntime> listeners = new LinkedHashMap<>();
    private final Map<String, String> listenerErrors = new LinkedHashMap<>();
    private volatile ProxyConfigDocument current = ProxyConfigDocument.EMPTY;
    private boolean closed;

    public ProxyServer(ProxyRuntime rt) {
        this.rt = rt;
    }

    public ProxyRuntime runtime() {
        return rt;
    }

    public synchronized void apply(ProxyConfigDocument cfg) {
        rt.setIdentity(new IdentityResolver(cfg.applications()));
        rt.quotas().update(cfg.quotas(), cfg.datasourceQuotas());

        List<String> gone = new ArrayList<>();
        for (String name : listeners.keySet()) {
            if (cfg.listener(name) == null) {
                gone.add(name);
            }
        }
        for (String name : gone) {
            listeners.remove(name).stop();
        }
        listenerErrors.clear();
        for (ListenerConfig lc : cfg.listeners()) {
            ListenerRuntime existing = listeners.get(lc.name());
            if (existing != null && existing.config().sameSocket(lc) && existing.isRunning()) {
                existing.updateConfig(lc);
                continue;
            }
            if (existing != null) {
                existing.stop();
                listeners.remove(lc.name());
            }
            bind(lc, false);
        }
        current = cfg;
        LOG.info("configuration version {} applied: {} listener(s), {} application(s), {} quota(s), {} datasource quota(s)",
                cfg.configVersion(), listeners.size(), cfg.applications().size(), cfg.quotas().size(), cfg.datasourceQuotas().size());
    }

    /**
     * Re-binds every configured listener that is not running (bind failed at the last apply, or the
     * accept loop died on a socket error). Cheap no-op when everything is up; returns true when a
     * listener was (re)created.
     */
    public synchronized boolean repair() {
        if (closed) {
            return false;
        }
        boolean changed = false;
        for (ListenerConfig lc : current.listeners()) {
            ListenerRuntime existing = listeners.get(lc.name());
            if (existing != null && existing.isRunning()) {
                continue;
            }
            if (existing != null) {
                LOG.warn("listener '{}' is no longer accepting connections, re-binding", lc.name());
                existing.stop();
                listeners.remove(lc.name());
            } else {
                LOG.info("retrying bind of listener '{}'", lc.name());
            }
            changed |= bind(lc, true);
        }
        return changed;
    }

    /** True when a configured listener is missing or not accepting; {@link #repair()} should be called. */
    public synchronized boolean needsRepair() {
        for (ListenerConfig lc : current.listeners()) {
            ListenerRuntime l = listeners.get(lc.name());
            if (l == null || !l.isRunning()) {
                return true;
            }
        }
        return false;
    }

    private boolean bind(ListenerConfig lc, boolean retry) {
        try {
            ListenerRuntime lr = new ListenerRuntime(rt, lc);
            lr.start();
            listeners.put(lc.name(), lr);
            listenerErrors.remove(lc.name());
            return true;
        } catch (IOException e) {
            if (retry) {
                LOG.warn("{} (will retry on the next poll)", e.getMessage());
            } else {
                LOG.error("{}", e.getMessage());
            }
            listenerErrors.put(lc.name(), e.getMessage());
            return false;
        }
    }

    public ProxyConfigDocument current() {
        return current;
    }

    public synchronized Map<String, ListenerRuntime> listeners() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(listeners));
    }

    public synchronized ListenerRuntime listener(String name) {
        return listeners.get(name);
    }

    public synchronized Map<String, String> listenerErrors() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(listenerErrors));
    }

    /**
     * Stops accepting and reports every established connection as CLOSED ({@value #SHUTDOWN_REASON}) so
     * the control plane's OPEN/CLOSE pairs stay balanced. The sockets themselves are not torn down here:
     * the process is exiting and the kernel closes them; a connection that nevertheless ends normally
     * afterwards does not emit a second CLOSE.
     */
    @Override
    public synchronized void close() {
        closed = true;
        for (ListenerRuntime l : listeners.values()) {
            l.stop();
        }
        listeners.clear();
        int reported = 0;
        for (LiveConnection c : rt.registry().all()) {
            if (c.state() == LiveConnection.State.ESTABLISHED && c.markTerminalEmitted()) {
                c.markClosed(SHUTDOWN_REASON);
                rt.events().closed(c, SHUTDOWN_REASON);
                reported++;
            }
        }
        if (reported > 0) {
            LOG.info("reported {} live connection(s) as closed ({})", reported, SHUTDOWN_REASON);
        }
    }
}
