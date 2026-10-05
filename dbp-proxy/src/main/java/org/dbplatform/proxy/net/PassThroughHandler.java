package org.dbplatform.proxy.net;

import org.dbplatform.proxy.config.ListenerConfig;
import org.dbplatform.proxy.identity.IdentityInput;
import org.dbplatform.proxy.registry.LiveConnection;
import org.dbplatform.proxy.routing.RouteDecision;
import org.dbplatform.proxy.routing.Router;

import java.io.IOException;

/**
 * Opaque TCP pass-through used for {@code MSSQL} and {@code TCP} listeners: identity by CIDR only, the
 * listener's default route is the backend. TDS starts with a PRELOGIN that negotiates TLS before the
 * LOGIN7 packet carrying the database name is sent, so database-based routing is out of scope for the
 * POC (see README).
 */
public final class PassThroughHandler implements ConnectionHandler {
    @Override
    public void handle(ConnectionContext ctx) throws IOException {
        LiveConnection live = ctx.live();
        ListenerConfig cfg = ctx.config();
        if (ctx.capReason() != null) {
            ctx.refused(ctx.capReason(), "listener_cap");
            return;
        }
        RouteDecision decision = Router.route(cfg, null);
        if (decision == null) {
            ctx.refused("listener '" + cfg.name() + "' has no default route", "no_route");
            return;
        }
        live.setIdentity(ctx.identity().resolve(new IdentityInput(null, null, null, null, ctx.client().getInetAddress())));
        live.setDatasource(decision.datasourceId(), decision.datasource(), decision.route().databaseId());
        live.setResolvedService(decision.route().serviceName());
        String quota = ctx.admit();
        if (quota != null) {
            ctx.refused(quota, "quota");
            return;
        }
        try {
            ctx.connectBackend(decision.route().host(), decision.route().port());
        } catch (IOException e) {
            ctx.backendFailed("connect to " + decision.route().backend() + " failed: " + e.getMessage());
            return;
        }
        ctx.opened();
        ctx.pump();
    }
}
