package org.dbplatform.proxy.routing;

import org.dbplatform.proxy.config.RouteConfig;

/**
 * Outcome of matching a requested logical service against a listener's routes.
 *
 * @param route            the chosen route (a configured one or the listener default)
 * @param requestedService what the client asked for, verbatim (may be null)
 * @param baseService      the requested service with any {@code .<alias>} suffix removed
 * @param alias            the application alias suffix, or null
 * @param resolvedService  what the backend will be asked for (null = leave the client's value untouched)
 * @param isDefault        true when the listener default route was used
 */
public record RouteDecision(RouteConfig route, String requestedService, String baseService, String alias,
                            String resolvedService, boolean isDefault) {

    public String datasource() {
        return route.datasource();
    }

    public String datasourceId() {
        return route.datasourceId();
    }

    /** True when the service the backend sees differs from what the client sent. */
    public boolean needsRewrite() {
        return resolvedService != null && !resolvedService.equals(requestedService);
    }
}
