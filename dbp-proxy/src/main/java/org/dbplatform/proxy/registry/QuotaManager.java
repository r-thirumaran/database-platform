package org.dbplatform.proxy.registry;

import org.dbplatform.proxy.config.DatasourceQuotaConfig;
import org.dbplatform.proxy.config.QuotaConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Admission control: per (application, datasource) and per datasource caps on live proxy connections.
 * Check-and-register is atomic so concurrent handshakes cannot overshoot the limit.
 */
public final class QuotaManager {
    private final ConnectionRegistry registry;
    private volatile Limits limits = new Limits(Map.of(), Map.of());

    private record Limits(Map<String, Integer> appDs, Map<String, Integer> ds) {
    }

    public QuotaManager(ConnectionRegistry registry) {
        this.registry = registry;
    }

    public void update(List<QuotaConfig> quotas, List<DatasourceQuotaConfig> dsQuotas) {
        Map<String, Integer> appDs = new HashMap<>();
        for (QuotaConfig q : quotas) {
            appDs.put(key(q.applicationId(), q.datasourceId()), q.maxProxyConnections());
            if (q.application() != null && q.datasource() != null) {
                appDs.put(key(q.application(), q.datasource()), q.maxProxyConnections());
            }
        }
        Map<String, Integer> ds = new HashMap<>();
        for (DatasourceQuotaConfig q : dsQuotas) {
            ds.put(norm(q.datasourceId()), q.maxProxyConnections());
            if (q.datasource() != null) {
                ds.put(norm(q.datasource()), q.maxProxyConnections());
            }
        }
        this.limits = new Limits(appDs, ds);
    }

    /**
     * Registers the connection when every applicable quota has room; otherwise returns the refusal
     * reason (and leaves the registry untouched).
     */
    public synchronized String admit(LiveConnection c) {
        Limits l = limits;
        Integer appLimit = lookupAppDs(l, c);
        if (appLimit != null) {
            int used = registry.count(o -> sameApp(o, c) && sameDs(o, c));
            if (used >= appLimit) {
                return "quota exceeded: " + c.application() + "/" + c.datasource() + " " + used + "/" + appLimit;
            }
        }
        Integer dsLimit = lookupDs(l, c);
        if (dsLimit != null) {
            int used = registry.count(o -> sameDs(o, c));
            if (used >= dsLimit) {
                return "datasource quota exceeded: " + c.datasource() + " " + used + "/" + dsLimit;
            }
        }
        registry.add(c);
        return null;
    }

    public void release(LiveConnection c) {
        registry.remove(c);
    }

    private static Integer lookupAppDs(Limits l, LiveConnection c) {
        if (c.datasourceId() == null && c.datasource() == null) {
            return null;
        }
        Integer v = null;
        if (c.applicationId() != null) {
            v = l.appDs.get(key(c.applicationId(), c.datasourceId()));
            if (v == null) {
                v = l.appDs.get(key(c.applicationId(), c.datasource()));
            }
        }
        if (v == null && c.application() != null) {
            v = l.appDs.get(key(c.application(), c.datasourceId()));
            if (v == null) {
                v = l.appDs.get(key(c.application(), c.datasource()));
            }
        }
        return v;
    }

    private static Integer lookupDs(Limits l, LiveConnection c) {
        Integer v = c.datasourceId() == null ? null : l.ds.get(norm(c.datasourceId()));
        if (v == null && c.datasource() != null) {
            v = l.ds.get(norm(c.datasource()));
        }
        return v;
    }

    private static boolean sameApp(LiveConnection a, LiveConnection b) {
        if (a.applicationId() != null || b.applicationId() != null) {
            return Objects.equals(a.applicationId(), b.applicationId());
        }
        return eqIgnoreCase(a.application(), b.application());
    }

    private static boolean sameDs(LiveConnection a, LiveConnection b) {
        if (a.datasourceId() != null || b.datasourceId() != null) {
            return Objects.equals(a.datasourceId(), b.datasourceId());
        }
        return eqIgnoreCase(a.datasource(), b.datasource());
    }

    private static boolean eqIgnoreCase(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    private static String key(String app, String ds) {
        return norm(app) + "|" + norm(ds);
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
