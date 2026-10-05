package org.dbplatform.proxy.routing;

import org.dbplatform.proxy.config.ListenerConfig;
import org.dbplatform.proxy.config.RouteConfig;

import java.util.Locale;

/**
 * Picks the backend for a requested logical service. Exact match on {@code route.match} first, then the
 * {@code <match>.<alias>} form (longest match wins, so a configured {@code sales.eu} beats {@code sales}),
 * then the listener's default route (service passed through unchanged unless the default route rewrites).
 * Returns {@code null} when nothing matches and there is no default route.
 */
public final class Router {
    private Router() {
    }

    public static RouteDecision route(ListenerConfig listener, String requestedService) {
        String req = requestedService == null ? null : requestedService.strip();
        if (req != null && !req.isEmpty()) {
            String lower = req.toLowerCase(Locale.ROOT);
            for (RouteConfig r : listener.routes()) {
                if (r.match().equalsIgnoreCase(req)) {
                    return decide(r, req, req, null, false);
                }
            }
            RouteConfig best = null;
            String bestAlias = null;
            for (RouteConfig r : listener.routes()) {
                String prefix = r.match().toLowerCase(Locale.ROOT) + ".";
                if (lower.startsWith(prefix) && lower.length() > prefix.length()
                        && (best == null || r.match().length() > best.match().length())) {
                    best = r;
                    bestAlias = req.substring(prefix.length());
                }
            }
            if (best != null) {
                return decide(best, req, best.match(), bestAlias, false);
            }
        }
        RouteConfig def = listener.defaultRoute();
        if (def == null) {
            return null;
        }
        String resolved = def.rewriteServiceName() && def.serviceName() != null ? def.serviceName() : null;
        return new RouteDecision(def, req, req, null, resolved, true);
    }

    private static RouteDecision decide(RouteConfig r, String requested, String base, String alias, boolean isDefault) {
        String resolved;
        if (r.rewriteServiceName() && r.serviceName() != null) {
            resolved = r.serviceName();
        } else if (alias != null) {
            resolved = base; // alias stripped, physical service == logical name
        } else {
            resolved = null;
        }
        return new RouteDecision(r, requested, base, alias, resolved, isDefault);
    }
}
