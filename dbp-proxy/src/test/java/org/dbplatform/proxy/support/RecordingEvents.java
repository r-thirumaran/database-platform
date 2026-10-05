package org.dbplatform.proxy.support;

import org.dbplatform.proxy.registry.LiveConnection;
import org.dbplatform.proxy.telemetry.ConnectionEvents;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/** In-memory event sink for tests. */
public final class RecordingEvents implements ConnectionEvents {
    public record Event(String type, Map<String, Object> connection, String reason) {
    }

    public final List<Event> events = new CopyOnWriteArrayList<>();

    @Override
    public void opened(LiveConnection c) {
        events.add(new Event("OPEN", c.toMap(), null));
    }

    @Override
    public void closed(LiveConnection c, String reason) {
        events.add(new Event("CLOSE", c.toMap(), reason));
    }

    @Override
    public void refused(LiveConnection c, String reason) {
        events.add(new Event("REFUSED", c.toMap(), reason));
    }

    @Override
    public void backendFailed(LiveConnection c, String reason) {
        events.add(new Event("BACKEND_FAILED", c.toMap(), reason));
    }

    public List<Event> ofType(String type) {
        return events.stream().filter(e -> e.type().equals(type)).collect(Collectors.toList());
    }

    public void clear() {
        events.clear();
    }
}
