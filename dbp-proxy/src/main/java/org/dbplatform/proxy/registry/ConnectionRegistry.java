package org.dbplatform.proxy.registry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;

/** All connections currently admitted by this proxy (handshake completed up to routing, until close). */
public final class ConnectionRegistry {
    private final ConcurrentHashMap<String, LiveConnection> live = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public String nextId() {
        return "c-" + sequence.incrementAndGet();
    }

    public void add(LiveConnection c) {
        live.put(c.id(), c);
    }

    public boolean remove(LiveConnection c) {
        return live.remove(c.id()) != null;
    }

    public LiveConnection get(String id) {
        return live.get(id);
    }

    public int size() {
        return live.size();
    }

    public Collection<LiveConnection> all() {
        return live.values();
    }

    /** Stable snapshot ordered by connection id (oldest first), optionally capped. */
    public List<LiveConnection> snapshot(int max) {
        List<LiveConnection> list = new ArrayList<>(live.values());
        list.sort(Comparator.comparing(LiveConnection::openedAt).thenComparing(LiveConnection::id));
        return list.size() > max ? new ArrayList<>(list.subList(0, max)) : list;
    }

    public int count(Predicate<LiveConnection> p) {
        int n = 0;
        for (LiveConnection c : live.values()) {
            if (p.test(c)) {
                n++;
            }
        }
        return n;
    }

    public int countForListener(String listener) {
        return count(c -> listener.equals(c.listener()));
    }
}
