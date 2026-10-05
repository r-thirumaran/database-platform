package org.dbplatform.proxy.postgres;

import org.dbplatform.proxy.config.ListenerConfig;
import org.dbplatform.proxy.config.RouteConfig;
import org.dbplatform.proxy.identity.IdentityInput;
import org.dbplatform.proxy.net.ConnectionContext;
import org.dbplatform.proxy.net.ConnectionHandler;
import org.dbplatform.proxy.registry.LiveConnection;
import org.dbplatform.proxy.routing.RouteDecision;
import org.dbplatform.proxy.routing.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * PostgreSQL startup handshake: answers SSLRequest / GSSENCRequest with {@code N} (no TLS between client
 * and proxy in the POC), forwards CancelRequest to the listener's default backend, and for a
 * StartupMessage routes on {@code database} (accepting {@code <match>.<alias>}), rewrites the database
 * name when the route says so and then becomes a transparent pump. Refusals are FATAL ErrorResponses with
 * SQLSTATE 53300 (quota / cap), 3D000 (unknown database) or 08001 (backend unreachable).
 */
public final class PostgresConnectionHandler implements ConnectionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(PostgresConnectionHandler.class);
    private static final byte[] NO_SSL = {'N'};

    @Override
    public void handle(ConnectionContext ctx) throws IOException {
        LiveConnection live = ctx.live();
        ListenerConfig cfg = ctx.config();
        ctx.setHandshakeTimeout();

        PgStartupMessage msg;
        while (true) {
            msg = PgStartupMessage.read(ctx.clientIn());
            if (msg == null) {
                ctx.closed("client closed before startup");
                return;
            }
            if (msg.isSslRequest() || msg.isGssEncRequest()) {
                ctx.writeClient(NO_SSL);
                continue;
            }
            if (msg.isCancelRequest()) {
                cancel(ctx, msg);
                return;
            }
            if (msg.isStartup()) {
                break;
            }
            ctx.writeClient(PgErrorResponse.fatal(PgErrorResponse.PROTOCOL_VIOLATION, "unsupported startup message code " + msg.code()));
            ctx.refused("unsupported startup message code " + msg.code(), "protocol");
            return;
        }

        String database = msg.database();
        live.setRequestedService(database);
        live.setDbUser(msg.user());
        live.setProgram(msg.applicationName());
        LOG.debug("{} startup protocol {}.{} user={} database={} application_name={}", live.id(), msg.protocolMajor(),
                msg.protocolMinor(), msg.user(), database, msg.applicationName());

        if (ctx.capReason() != null) {
            refuse(ctx, PgErrorResponse.TOO_MANY_CONNECTIONS, "too many connections for listener '" + cfg.name() + "'", ctx.capReason(), "listener_cap");
            return;
        }
        RouteDecision decision = Router.route(cfg, database);
        if (decision == null) {
            refuse(ctx, PgErrorResponse.INVALID_CATALOG_NAME, "database \"" + database + "\" is not routed by proxy listener '" + cfg.name() + "'",
                    "unknown database '" + database + "' on listener '" + cfg.name() + "'", "unknown_service");
            return;
        }
        live.setIdentity(ctx.identity().resolve(new IdentityInput(decision.alias(), null, msg.applicationName(), null,
                ctx.client().getInetAddress())));
        live.setDatasource(decision.datasourceId(), decision.datasource(), decision.route().databaseId());
        String resolved = decision.resolvedService() != null ? decision.resolvedService() : database;
        live.setResolvedService(resolved);

        String quota = ctx.admit();
        if (quota != null) {
            refuse(ctx, PgErrorResponse.TOO_MANY_CONNECTIONS, "too many connections for " + live.application() + "/" + live.datasource()
                    + " (" + quota + ")", quota, "quota");
            return;
        }

        RouteConfig route = decision.route();
        try {
            ctx.connectBackend(route.host(), route.port());
        } catch (IOException e) {
            ctx.writeClient(PgErrorResponse.fatal(PgErrorResponse.CONNECTION_FAILURE,
                    "proxy could not connect to backend " + route.backend() + ": " + e.getMessage()));
            ctx.backendFailed("connect to " + route.backend() + " failed: " + e.getMessage());
            return;
        }
        PgStartupMessage forward = resolved.equals(database) && msg.hasExplicitDatabase() ? msg : msg.withParam("database", resolved);
        ctx.writeBackend(forward.raw());
        ctx.opened();
        ctx.pump();
    }

    /** CancelRequest: no routing key is available, forward to the default backend (or the first route). */
    private static void cancel(ConnectionContext ctx, PgStartupMessage msg) throws IOException {
        ListenerConfig cfg = ctx.config();
        RouteConfig target = cfg.defaultRoute() != null ? cfg.defaultRoute()
                : cfg.routes().isEmpty() ? null : cfg.routes().get(0);
        if (target == null) {
            ctx.closed("cancel request with no backend");
            return;
        }
        LOG.debug("{} forwarding CancelRequest pid={} to {}", ctx.live().id(), msg.cancelPid(), target.backend());
        ctx.live().setRequestedService("<cancel>");
        try {
            ctx.connectBackend(target.host(), target.port());
        } catch (IOException e) {
            ctx.closed("cancel request: backend unreachable");
            return;
        }
        ctx.writeBackend(msg.raw());
        ctx.pump();
    }

    private static void refuse(ConnectionContext ctx, String sqlState, String clientMessage, String reason, String category) throws IOException {
        ctx.writeClient(PgErrorResponse.fatal(sqlState, clientMessage));
        ctx.refused(reason, category);
    }
}
