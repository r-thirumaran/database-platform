package org.dbplatform.proxy.telemetry;

import org.dbplatform.common.telemetry.ConnectionEventType;
import org.dbplatform.common.telemetry.TelemetryClient;
import org.dbplatform.proxy.registry.LiveConnection;

/** Publishes connection events through the shared {@link TelemetryClient} (bounded queue, never blocks). */
public final class TelemetryConnectionEvents implements ConnectionEvents {
    private final TelemetryClient client;
    private final String proxyId;

    public TelemetryConnectionEvents(TelemetryClient client, String proxyId) {
        this.client = client;
        this.proxyId = proxyId;
    }

    @Override
    public void opened(LiveConnection c) {
        client.record(EventMapper.toEvent(c, ConnectionEventType.OPEN, proxyId, null));
    }

    @Override
    public void closed(LiveConnection c, String reason) {
        client.record(EventMapper.toEvent(c, ConnectionEventType.CLOSE, proxyId, reason));
    }

    @Override
    public void refused(LiveConnection c, String reason) {
        client.record(EventMapper.toEvent(c, ConnectionEventType.REFUSED, proxyId, reason));
    }

    @Override
    public void backendFailed(LiveConnection c, String reason) {
        client.record(EventMapper.toEvent(c, ConnectionEventType.BACKEND_FAILED, proxyId, reason));
    }
}
