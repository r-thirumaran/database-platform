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
 * Refusals use ORA-12514 (unknown service), ORA-12516 (quota / cap) and ORA-12541 (backend unreachable).
 */
public final class OracleConnectionHandler implements ConnectionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(OracleConnectionHandler.class);
    private static final int MAX_HANDSHAKE_ROUNDS = 8;

    @Override
    public void handle(ConnectionContext ctx) throws IOException {
        LiveConnection live = ctx.live();
        ListenerConfig cfg = ctx.config();
        ctx.setHandshakeTimeout();

        TnsConnectPacket connect = readConnectRequest(ctx.clientIn());
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
                cs.requestedService(), cs.program(), cs.host(), cs.user(), connect.usesDeferredForm());

        if (ctx.capReason() != null) {
            refuse(ctx, TnsRefusePacket.ERR_NO_HANDLER, ctx.capReason(), "listener_cap");
            return;
        }
        RouteDecision decision = Router.route(cfg, cs.requestedService());
        if (decision == null) {
            refuse(ctx, TnsRefusePacket.ERR_UNKNOWN_SERVICE,
                    "unknown service '" + cs.requestedService() + "' on listener '" + cfg.name() + "'", "unknown_service");
            return;
        }
        live.setIdentity(ctx.identity().resolve(new IdentityInput(decision.alias(), cs.program(), null, cs.host(),
                ctx.client().getInetAddress())));
        live.setDatasource(decision.datasourceId(), decision.datasource(), decision.route().databaseId());
        String resolved = decision.resolvedService() != null ? decision.resolvedService() : cs.requestedService();
        live.setResolvedService(resolved);

        String quota = ctx.admit();
        if (quota != null) {
            refuse(ctx, TnsRefusePacket.ERR_NO_HANDLER, quota, "quota");
            return;
        }

        String host = decision.route().host();
        int port = decision.route().port();
        String rewritten = cs.rewrite(decision.resolvedService(), host, port);
        if (!rewritten.equals(connect.connectData())) {
            LOG.debug("{} rewritten connect string: {}", live.id(), rewritten);
        }

        if (!connectBackend(ctx, host, port)) {
            return;
        }
        send(ctx, connect.withConnectData(rewritten));

        TnsConnectPacket lastClientConnect = connect;
        for (int round = 0; round < MAX_HANDSHAKE_ROUNDS; round++) {
            TnsPacket reply = TnsPacket.read(ctx.backendIn());
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
                    TnsConnectPacket again = readConnectRequest(ctx.clientIn());
                    if (again == null) {
                        ctx.closed("client closed during RESEND");
                        return;
                    }
                    lastClientConnect = again;
                    String data = TnsConnectString.parse(again.connectData()).rewrite(decision.resolvedService(), host, port);
                    send(ctx, again.withConnectData(data));
                }
                case TnsPacket.TYPE_REDIRECT -> {
                    TnsRedirectPacket redirect = TnsRedirectPacket.parse(reply);
                    if (redirect.needsData()) {
                        TnsPacket data = TnsPacket.read(ctx.backendIn());
                        if (data == null) {
                            ctx.writeClient(TnsRefusePacket.build(TnsRefusePacket.ERR_NO_LISTENER));
                            ctx.backendFailed("backend closed while sending REDIRECT data");
                            return;
                        }
                        redirect = redirect.withData(data);
                    }
                    String rHost = redirect.host();
                    int rPort = redirect.port();
                    LOG.debug("{} following REDIRECT to {}:{} (replacement connect data: {})", live.id(), rHost, rPort,
                            redirect.connectData() != null);
                    if (!connectBackend(ctx, rHost, rPort)) {
                        return;
                    }
                    String data = redirect.connectData() != null ? redirect.connectData()
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
        TnsPacket p = TnsPacket.read(in);
        if (p == null) {
            return null;
        }
        if (p.type() != TnsPacket.TYPE_CONNECT) {
            throw new TnsParseException("expected CONNECT from client, got " + TnsPacket.typeName(p.type()));
        }
        TnsConnectPacket c = TnsConnectPacket.parse(p);
        if (c.isDeferred()) {
            TnsPacket data = TnsPacket.read(in);
            if (data == null) {
                return null;
            }
            c = c.withDeferredData(data);
        }
        return c;
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
