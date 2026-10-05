package org.dbplatform.proxy.registry;

import org.dbplatform.proxy.config.DatasourceQuotaConfig;
import org.dbplatform.proxy.config.Engine;
import org.dbplatform.proxy.config.QuotaConfig;
import org.dbplatform.proxy.identity.IdentitySource;
import org.dbplatform.proxy.identity.ResolvedIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuotaManagerTest {
    private static LiveConnection conn(ConnectionRegistry r, String appId, String app, String dsId, String ds) {
        LiveConnection c = new LiveConnection(r.nextId(), "l", Engine.POSTGRES, "127.0.0.1", 1);
        c.setIdentity(new ResolvedIdentity(appId, app, null, appId == null ? IdentitySource.NONE : IdentitySource.CIDR));
        c.setDatasource(dsId, ds, null);
        return c;
    }

    @Test
    void perApplicationAndPerDatasourceLimitsCountLiveConnections() {
        ConnectionRegistry registry = new ConnectionRegistry();
        QuotaManager q = new QuotaManager(registry);
        q.update(List.of(new QuotaConfig("a1", null, "ds1", null, 2)), List.of(new DatasourceQuotaConfig("ds1", null, 3)));

        LiveConnection c1 = conn(registry, "a1", "orders", "ds1", "sales");
        LiveConnection c2 = conn(registry, "a1", "orders", "ds1", "sales");
        LiveConnection c3 = conn(registry, "a1", "orders", "ds1", "sales");
        assertThat(q.admit(c1)).isNull();
        assertThat(q.admit(c2)).isNull();
        assertThat(q.admit(c3)).isEqualTo("quota exceeded: orders/sales 2/2");
        assertThat(registry.size()).isEqualTo(2);

        LiveConnection other = conn(registry, "a2", "batch", "ds1", "sales");
        assertThat(q.admit(other)).isNull();
        LiveConnection overDs = conn(registry, "a3", "ui", "ds1", "sales");
        assertThat(q.admit(overDs)).isEqualTo("datasource quota exceeded: sales 3/3");

        q.release(c1);
        assertThat(q.admit(c3)).isNull();
        assertThat(q.admit(conn(registry, "a1", "orders", "ds2", "inventory"))).as("other datasource unconstrained").isNull();
        assertThat(q.admit(conn(registry, null, "unknown", "ds2", "inventory"))).isNull();
    }

    @Test
    void staticModeQuotasByName() {
        ConnectionRegistry registry = new ConnectionRegistry();
        QuotaManager q = new QuotaManager(registry);
        q.update(List.of(new QuotaConfig(null, "orders-service", null, "sales", 1)), List.of());
        assertThat(q.admit(conn(registry, "orders-service", "orders-service", "sales", "sales"))).isNull();
        assertThat(q.admit(conn(registry, "orders-service", "orders-service", "sales", "sales")))
                .isEqualTo("quota exceeded: orders-service/sales 1/1");
        q.update(List.of(), List.of());
        assertThat(q.admit(conn(registry, "orders-service", "orders-service", "sales", "sales"))).as("hot reload lifts the limit").isNull();
    }

    @Test
    void unknownApplicationCapAppliesPerDatasourceToConnectionsWithoutAnApplicationId() {
        ConnectionRegistry registry = new ConnectionRegistry();
        QuotaManager q = new QuotaManager(registry, 1);
        assertThat(q.unknownAppMaxConnections()).isEqualTo(1);
        q.update(List.of(), List.of());

        LiveConnection anon1 = conn(registry, null, "unknown", "ds1", "sales");
        LiveConnection anon2 = conn(registry, null, "unknown", "ds1", "sales");
        assertThat(q.admit(anon1)).isNull();
        assertThat(q.admit(anon2)).isEqualTo("unknown-application quota exceeded: sales 1/1 (application 'unknown' is not registered)");

        // an undeclared service alias has a name but no id: it shares the unknown-application budget
        LiveConnection alias = conn(registry, null, "rogue", "ds1", "sales");
        alias.setIdentity(new ResolvedIdentity(null, "rogue", null, IdentitySource.SERVICE_ALIAS));
        assertThat(q.admit(alias)).startsWith("unknown-application quota exceeded: sales 1/1 (application 'rogue'");

        assertThat(q.admit(conn(registry, "a1", "orders", "ds1", "sales"))).as("registered applications are not capped").isNull();
        assertThat(q.admit(conn(registry, null, "unknown", "ds2", "inventory"))).as("other datasource has its own budget").isNull();
        q.release(anon1);
        assertThat(q.admit(anon2)).isNull();

        assertThat(new QuotaManager(registry).unknownAppMaxConnections()).as("default: unlimited").isZero();
    }
}
