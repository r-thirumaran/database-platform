package org.dbplatform.proxy.oracle;

import org.dbplatform.proxy.config.ListenerConfig;
import org.dbplatform.proxy.identity.IdentityInput;
import org.dbplatform.proxy.net.ConnectionContext;
import org.dbplatform.proxy.net.ConnectionHandler;
import org.dbplatform.proxy.registry.LiveConnection;
import org.dbplatform.proxy.routing.RouteDecision;
import org.dbplatform.proxy.routing.Router;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * Oracle Net handshake:
 * <ol>
 *   <li>read the client's CONNECT (plus the DATA packet carrying a deferred connect string),</li>
 *   <li>route on SERVICE_NAME/SID (accepting {@code <match>.<alias>}), resolve identity, check quotas,</li>
 *   <li>rewrite the descriptor (backend address, physical service name) and send it to the backend,</li>
 *   <li>handle the backend's reply: ACCEPT → transparent; RESEND → forward and relay the client's next
 *       CONNECT; REDIRECT → the proxy itself reconnects to the redirect address (the client never sees
 *       it, so ephemeral redirect ports stay reachable); REFUSE → forward and close.</li>
 * </ol>
 * Refusals use ORA-12514 (unknown service, undeclared alias in strict mode), ORA-12516 (quota / cap) and
 * ORA-12541 (backend unreachable, backend closed or sent a malformed packet during the handshake). Every
 * read of the handshake is bounded by the connection's handshake deadline.
 */
public final class OracleConnectionHandler implements ConnectionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(OracleConnectionHandler.class);
    private static final int MAX_HANDSHAKE_ROUNDS = 8;

    /** Invoked before every packet read so the caller can (re-)arm a deadline; {@link #NO_GUARD} for plain streams. */
    @FunctionalInterface
    interface ReadGuard {
        void beforeRead() throws IOException;
    }

    static final ReadGuard NO_GUARD = () -> {
    };

    @Override
    public void handle(ConnectionContext ctx) throws IOException {
        LiveConnection live = ctx.live();
        ListenerConfig cfg = ctx.config();

        TnsConnectPacket connect = readConnectRequest(ctx.clientIn(), ctx::armClientHandshakeTimeout);
        if (connect == null) {
            ctx.closed("client closed before CONNECT");
            return;
        }
        TnsConnectString cs = TnsConnectString.parse(connect.connectData());
        live.setRequestedService(cs.requestedService());
        live.setProgram(TnsConnectString.display(cs.program()));
        live.setClientHost(TnsConnectString.display(cs.host()));
        live.setOsUser(TnsConnectString.display(cs.user()));
        LOG.debug("{} CONNECT v{} service={} program={} host={} user={} deferred={}", live.id(), connect.version(),
                live.requestedService(), live.program(), live.clientHost(), live.osUser(), connect.usesDeferredForm());

        if (ctx.capReason() != null) {
            refuse(ctx, TnsRefusePacket.ERR_NO_HANDLER, ctx.capReason(), "listener_cap");
            return;
        }
        RouteDecision decision = Router.route(cfg, cs.requestedService());
        if (decision == null) {
            refuse(ctx, TnsRefusePacket.ERR_UNKNOWN_SERVICE,
                    "unknown service '" + live.requestedService() + "' on listener '" + cfg.name() + "'", "unknown_service");
            return;
        }
        live.setIdentity(ctx.identity().resolve(new IdentityInput(decision.alias(), cs.program(), null, cs.host(),
                ctx.client().getInetAddress())));
        live.setDatasource(decision.datasourceId(), decision.datasource(), decision.route().databaseId());
        String resolved = decision.resolvedService() != null ? decision.resolvedService() : cs.requestedService();
        live.setResolvedService(resolved);

        if (ctx.settings().strictAliases() && decision.alias() != null && live.applicationId() == null) {
            refuse(ctx, TnsRefusePacket.ERR_UNKNOWN_SERVICE, "undeclared application alias '" + live.application()
                    + "' in service '" + live.requestedService() + "' (DBP_PROXY_STRICT_ALIASES=true)", "undeclared_alias");
            return;
        }

        String quota = ctx.admit();
        if (quota != null) {
            refuse(ctx, TnsRefusePacket.ERR_NO_HANDLER, quota, "quota");
            return;
        }

        String host = decision.route().host();
        int port = decision.route().port();
        String rewritten = cs.rewrite(decision.resolvedService(), host, port);
        if (!rewritten.equals(connect.connectData())) {
            LOG.debug("{} rewritten connect string: {}", live.id(), LiveConnection.sanitize(rewritten));
        }

        if (!connectBackend(ctx, host, port)) {
            return;
        }
        send(ctx, connect.withConnectData(rewritten));

        TnsConnectPacket lastClientConnect = connect;
        for (int round = 0; round < MAX_HANDSHAKE_ROUNDS; round++) {
            TnsPacket reply;
            try {
                reply = readBackend(ctx);
            } catch (TnsParseException e) {
                backendProtocolError(ctx, e);
                return;
            }
            if (reply == null) {
                ctx.writeClient(TnsRefusePacket.build(TnsRefusePacket.ERR_NO_LISTENER));
                ctx.backendFailed("backend " + live.backend() + " closed the connection during the handshake");
                return;
            }
            switch (reply.type()) {
                case TnsPacket.TYPE_ACCEPT -> {
                    ctx.writeClient(reply.bytes());
                    ctx.opened();
                    ctx.pump();
                    return;
                }
                case TnsPacket.TYPE_RESEND -> {
                    LOG.debug("{} backend asked for RESEND", live.id());
                    ctx.writeClient(reply.bytes());
                    TnsConnectPacket again = readConnectRequest(ctx.clientIn(), ctx::armClientHandshakeTimeout);
                    if (again == null) {
                        ctx.closed("client closed during RESEND");
                        return;
                    }
                    lastClientConnect = again;
                    String data = TnsConnectString.parse(again.connectData()).rewrite(decision.resolvedService(), host, port);
                    send(ctx, again.withConnectData(data));
                }
                case TnsPacket.TYPE_REDIRECT -> {
                    String rHost;
                    int rPort;
                    String replacement;
                    try {
                        TnsRedirectPacket redirect = TnsRedirectPacket.parse(reply);
                        if (redirect.needsData()) {
                            TnsPacket data = readBackend(ctx);
                            if (data == null) {
                                ctx.writeClient(TnsRefusePacket.build(TnsRefusePacket.ERR_NO_LISTENER));
                                ctx.backendFailed("backend closed while sending REDIRECT data");
                                return;
                            }
                            redirect = redirect.withData(data);
                        }
                        rHost = redirect.host();
                        rPort = redirect.port();
                        replacement = redirect.connectData();
                    } catch (TnsParseException e) {
                        // the backend, not the client, sent garbage: never report it as a client protocol error
                        backendProtocolError(ctx, e);
                        return;
                    }
                    LOG.debug("{} following REDIRECT to {}:{} (replacement connect data: {})", live.id(), rHost, rPort,
                            replacement != null);
                    if (!connectBackend(ctx, rHost, rPort)) {
                        return;
                    }
                    String data = replacement != null ? replacement
                            : TnsConnectString.parse(lastClientConnect.connectData()).rewrite(decision.resolvedService(), host, port);
                    send(ctx, lastClientConnect.withConnectData(data));
                }
                case TnsPacket.TYPE_REFUSE -> {
                    int code = TnsRefusePacket.errorCode(reply);
                    ctx.writeClient(reply.bytes());
                    ctx.backendFailed("backend refused the connection" + (code > 0 ? " (ORA-" + code + ")" : "")
                            + (cs.isPing() ? " [tnsping]" : ""));
                    return;
                }
                default -> {
                    LOG.warn("{} unexpected {} packet from backend during handshake, switching to pass-through",
                            live.id(), TnsPacket.typeName(reply.type()));
                    ctx.writeClient(reply.bytes());
                    ctx.opened();
                    ctx.pump();
                    return;
                }
            }
        }
        ctx.writeClient(TnsRefusePacket.build(TnsRefusePacket.ERR_NO_LISTENER));
        ctx.backendFailed("handshake did not complete after " + MAX_HANDSHAKE_ROUNDS + " rounds (redirect loop?)");
    }

    /** Read a CONNECT and, when its connect string is deferred, the DATA packet that follows. */
    static TnsConnectPacket readConnectRequest(InputStream in) throws IOException {
        return readConnectRequest(in, NO_GUARD);
    }

    /** As {@link #readConnectRequest(InputStream)}, calling {@code guard} before each packet read (handshake deadline). */
    static TnsConnectPacket readConnectRequest(InputStream in, ReadGuard guard) throws IOException {
        guard.beforeRead();
        TnsPacket p = TnsPacket.read(in);
        if (p == null) {
            return null;
        }
        if (p.type() != TnsPacket.TYPE_CONNECT) {
            throw new TnsParseException("expected CONNECT from client, got " + TnsPacket.typeName(p.type()));
        }
        TnsConnectPacket c = TnsConnectPacket.parse(p);
        if (c.isDeferred()) {
            guard.beforeRead();
            TnsPacket data = TnsPacket.read(in);
            if (data == null) {
                return null;
            }
            c = c.withDeferredData(data);
        }
        return c;
    }

    /** One packet from the backend, bounded by the handshake deadline. May throw {@link TnsParseException}. */
    private static TnsPacket readBackend(ConnectionContext ctx) throws IOException {
        ctx.armBackendHandshakeTimeout();
        return TnsPacket.read(ctx.backendIn());
    }

    /** Malformed packet from the backend: the client gets ORA-12541 and a BACKEND_FAILED event is emitted. */
    private static void backendProtocolError(ConnectionContext ctx, TnsParseException e) throws IOException {
        ctx.writeClient(TnsRefusePacket.build(TnsRefusePacket.ERR_NO_LISTENER));
        ctx.backendFailed("backend " + ctx.live().backend() + " sent a malformed packet during the handshake: " + e.getMessage());
    }

    private static boolean connectBackend(ConnectionContext ctx, String host, int port) throws IOException {
        try {
            ctx.connectBackend(host, port);
            return true;
        } catch (IOException e) {
            ctx.writeClient(TnsRefusePacket.build(TnsRefusePacket.ERR_NO_LISTENER));
            ctx.backendFailed("connect to " + host + ":" + port + " failed: " + e.getMessage());
            return false;
        }
    }

    private static void send(ConnectionContext ctx, TnsConnectPacket packet) throws IOException {
        ctx.writeBackend(packet.toWireBytes());
    }

    private static void refuse(ConnectionContext ctx, int oraError, String reason, String category) throws IOException {
        ctx.writeClient(TnsRefusePacket.build(oraError));
        ctx.refused(reason, category);
    }
}
