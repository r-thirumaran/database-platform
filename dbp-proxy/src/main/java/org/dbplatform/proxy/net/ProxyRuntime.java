package org.dbplatform.proxy.net;

import org.dbplatform.proxy.config.ProxySettings;
import org.dbplatform.proxy.identity.IdentityResolver;
import org.dbplatform.proxy.metrics.ProxyMetrics;
import org.dbplatform.proxy.registry.ConnectionRegistry;
import org.dbplatform.proxy.registry.QuotaManager;
import org.dbplatform.proxy.telemetry.ConnectionEvents;

import java.util.concurrent.atomic.AtomicReference;

/** Process-wide collaborators shared by every listener and connection. */
public final class ProxyRuntime {
    private final ProxySettings settings;
    private final ConnectionRegistry registry;
    private final QuotaManager quotas;
    private final ProxyMetrics metrics;
    private final ConnectionEvents events;
    private final AtomicReference<IdentityResolver> identity = new AtomicReference<>(IdentityResolver.empty());

    public ProxyRuntime(ProxySettings settings, ConnectionRegistry registry, QuotaManager quotas, ProxyMetrics metrics, ConnectionEvents events) {
        this.settings = settings;
        this.registry = registry;
        this.quotas = quotas;
        this.metrics = metrics;
        this.events = events == null ? ConnectionEvents.NOOP : events;
    }

    public ProxySettings settings() {
        return settings;
    }

    public ConnectionRegistry registry() {
        return registry;
    }

    public QuotaManager quotas() {
        return quotas;
    }

    public ProxyMetrics metrics() {
        return metrics;
    }

    public ConnectionEvents events() {
        return events;
    }

    public IdentityResolver identity() {
        return identity.get();
    }

    public void setIdentity(IdentityResolver resolver) {
        identity.set(resolver);
    }
}
