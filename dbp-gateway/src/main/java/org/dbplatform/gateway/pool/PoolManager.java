package org.dbplatform.gateway.pool;

import org.dbplatform.common.controlplane.CredentialMaterial;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns every {@link PhysicalPool} of the gateway, keyed by (physical database, credential version). When a newer
 * credential version shows up for a database, the previous pool is drained and closed once idle; new pins go to
 * the new pool.
 */
public final class PoolManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(PoolManager.class);

    /** Supplies the credential material of a datasource at pool creation time. */
    @FunctionalInterface
    public interface CredentialSource {
        CredentialMaterial credentials() throws SQLException;
    }

    private final String gatewayId;
    private final Map<String, PhysicalPool> pools = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public PoolManager(String gatewayId) {
        this.gatewayId = gatewayId;
    }

    public String gatewayId() {
        return gatewayId;
    }

    /**
     * Returns the pool for the given settings, creating it if needed. {@code template} carries everything but the
     * secret, which {@code creds} supplies when a new pool has to be built.
     */
    public PhysicalPool poolFor(PoolSettings template, CredentialSource creds) throws SQLException {
        if (closed) {
            throw new SQLTransientConnectionException("gateway is shutting down", PhysicalPool.STATE_UNABLE);
        }
        String key = template.poolKey();
        PhysicalPool existing = pools.get(key);
        if (existing != null && !existing.isClosed()) {
            existing.addDatasourceName(template.datasourceName());
            return existing;
        }
        ReentrantLock lock = locks.computeIfAbsent(key, k -> new ReentrantLock());
        lock.lock(); // j.u.c lock: creating a pool blocks on the database and must not pin a carrier thread
        try {
            existing = pools.get(key);
            if (existing != null && !existing.isClosed()) {
                existing.addDatasourceName(template.datasourceName());
                return existing;
            }
            CredentialMaterial material = creds.credentials();
            PoolSettings settings = new PoolSettings(template.databaseKey(), template.datasourceName(),
                    template.datasourceId(), template.databaseId(), template.engineHint(), template.jdbcUrl(),
                    material.username() != null ? material.username() : template.username(), material.secret(),
                    template.credentialId(), material.version() > 0 ? material.version() : template.credentialVersion(),
                    template.maxConnections(), template.minIdle(), template.connectionTimeoutMs(), template.idleTimeoutMs(),
                    template.maxLifetimeMs(), template.statementTimeoutSeconds(), template.validationQuery(),
                    template.jdbcProperties(), template.poolMode());
            PhysicalPool created = new PhysicalPool(settings, gatewayId);
            pools.put(key, created);
            drainOlder(created);
            return created;
        } finally {
            lock.unlock();
        }
    }

    private void drainOlder(PhysicalPool fresh) {
        for (PhysicalPool p : pools.values()) {
            if (p != fresh && !p.isClosed() && p.settings().databaseKey().equals(fresh.settings().databaseKey())
                    && p.settings().credentialVersion() < fresh.settings().credentialVersion()) {
                p.drain();
                p.closeIfDrained();
            }
        }
    }

    /** Closes draining pools whose connections have all been returned. Call periodically and after releases. */
    public void sweep() {
        for (PhysicalPool p : pools.values()) {
            if (p.isDraining() && p.closeIfDrained()) {
                pools.remove(p.key(), p);
                locks.remove(p.key());
            }
        }
    }

    /** Live (not closed) pools. */
    public Collection<PhysicalPool> pools() {
        List<PhysicalPool> out = new ArrayList<>();
        for (PhysicalPool p : pools.values()) {
            if (!p.isClosed()) {
                out.add(p);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Pools currently serving a datasource (normally one, two during a credential rotation). */
    public List<PhysicalPool> poolsFor(String datasource) {
        List<PhysicalPool> out = new ArrayList<>();
        for (PhysicalPool p : pools.values()) {
            if (!p.isClosed() && p.datasourceNames().contains(datasource)) {
                out.add(p);
            }
        }
        return out;
    }

    /** Sum of physical connections over all live pools. */
    public int physicalConnections() {
        int n = 0;
        for (PhysicalPool p : pools.values()) {
            n += p.totalConnections();
        }
        return n;
    }

    @Override
    public void close() {
        closed = true;
        for (PhysicalPool p : pools.values()) {
            try {
                p.close();
            } catch (RuntimeException e) {
                LOG.debug("closing pool {} failed: {}", p.key(), e.toString());
            }
        }
        pools.clear();
    }
}
