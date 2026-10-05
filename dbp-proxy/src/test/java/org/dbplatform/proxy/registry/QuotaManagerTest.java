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
}
