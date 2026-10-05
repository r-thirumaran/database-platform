package org.dbplatform.proxy.telemetry;

import org.dbplatform.common.telemetry.ConnectionEvent;
import org.dbplatform.common.telemetry.ConnectionEventType;
import org.dbplatform.common.telemetry.Engine;
import org.dbplatform.common.telemetry.IdentitySource;
import org.dbplatform.common.util.Ids;
import org.dbplatform.proxy.registry.LiveConnection;

import java.time.Instant;

/** Builds {@link ConnectionEvent}s (exact field names from {@code docs/telemetry-events.md}) from registry entries. */
public final class EventMapper {
    private EventMapper() {
    }

    public static ConnectionEvent toEvent(LiveConnection c, ConnectionEventType type, String proxyId, String reason) {
        return ConnectionEvent.builder()
                .eventId(Ids.ulid())
                .timestamp(Instant.now())
                .proxyId(proxyId)
                .eventType(type)
                .listener(c.listener())
                .engine(Engine.parse(c.engine().name()))
                .connectionId(c.id())
                .clientAddr(c.clientAddr())
                .clientPort(c.clientPort())
                .proxyLocalAddr(c.proxyLocalAddr())
                .proxyLocalPort(c.proxyLocalPort())
                .backendHost(c.backendHost())
                .backendPort(c.backendPort())
                .requestedService(c.requestedService())
                .resolvedService(c.resolvedService())
                .applicationId(c.applicationId())
                .application(c.application())
                .identitySource(IdentitySource.valueOf(c.identitySource().name()))
                .datasourceId(c.datasourceId())
                .datasource(c.datasource())
                .program(c.program())
                .clientHost(c.clientHost())
                .osUser(c.osUser())
                .dbUser(c.dbUser())
                .openedAt(c.openedAt())
                .closedAt(type == ConnectionEventType.CLOSE ? (c.closedAt() == null ? Instant.now() : c.closedAt()) : null)
                .durationMs(c.durationMillis())
                .bytesIn(c.bytesIn())
                .bytesOut(c.bytesOut())
                .reason(reason)
                .build();
    }
}
