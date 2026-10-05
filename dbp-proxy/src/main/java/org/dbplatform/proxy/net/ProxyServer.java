package org.dbplatform.proxy.net;

import org.dbplatform.proxy.config.ListenerConfig;
import org.dbplatform.proxy.config.ProxyConfigDocument;
import org.dbplatform.proxy.identity.IdentityResolver;
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
 */
public final class ProxyServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ProxyServer.class);

    private final ProxyRuntime rt;
    private final Map<String, ListenerRuntime> listeners = new LinkedHashMap<>();
    private final Map<String, String> listenerErrors = new LinkedHashMap<>();
    private volatile ProxyConfigDocument current = ProxyConfigDocument.EMPTY;

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
            try {
                ListenerRuntime lr = new ListenerRuntime(rt, lc);
                lr.start();
                listeners.put(lc.name(), lr);
            } catch (IOException e) {
                LOG.error("{}", e.getMessage());
                listenerErrors.put(lc.name(), e.getMessage());
            }
        }
        current = cfg;
        LOG.info("configuration version {} applied: {} listener(s), {} application(s), {} quota(s), {} datasource quota(s)",
                cfg.configVersion(), listeners.size(), cfg.applications().size(), cfg.quotas().size(), cfg.datasourceQuotas().size());
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

    @Override
    public synchronized void close() {
        for (ListenerRuntime l : listeners.values()) {
            l.stop();
        }
        listeners.clear();
    }
}
