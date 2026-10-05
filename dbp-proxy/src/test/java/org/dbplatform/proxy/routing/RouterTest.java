package org.dbplatform.proxy.routing;

import org.dbplatform.proxy.config.Engine;
import org.dbplatform.proxy.config.ListenerConfig;
import org.dbplatform.proxy.config.RouteConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouterTest {
    static final RouteConfig SALES = new RouteConfig("sales", null, "ds-1", "db-1", "oracle", 1521, "FREEPDB1", true);
    static final RouteConfig SALES_EU = new RouteConfig("sales.eu", null, null, null, "oracle-eu", 1521, "EUPDB", true);
    static final RouteConfig LEGACY = new RouteConfig("legacy", null, null, null, "old", 1521, null, false);
    static final RouteConfig DEFAULT = RouteConfig.defaultRoute("oracle", 1521, null, false);

    @Test
    void exactAliasAndDefaultMatching() {
        ListenerConfig l = new ListenerConfig("o", Engine.ORACLE, null, 1521, null, List.of(SALES, SALES_EU, LEGACY), DEFAULT);

        RouteDecision exact = Router.route(l, "SALES");
        assertThat(exact.route()).isSameAs(SALES);
        assertThat(exact.alias()).isNull();
        assertThat(exact.resolvedService()).isEqualTo("FREEPDB1");
        assertThat(exact.datasource()).isEqualTo("sales");
        assertThat(exact.datasourceId()).isEqualTo("ds-1");

        RouteDecision alias = Router.route(l, "sales.orders-service");
        assertThat(alias.route()).isSameAs(SALES);
        assertThat(alias.alias()).isEqualTo("orders-service");
        assertThat(alias.baseService()).isEqualTo("sales");
        assertThat(alias.needsRewrite()).isTrue();

        RouteDecision longest = Router.route(l, "sales.eu.billing");
        assertThat(longest.route()).isSameAs(SALES_EU);
        assertThat(longest.alias()).isEqualTo("billing");

        RouteDecision legacyAlias = Router.route(l, "legacy.batch");
        assertThat(legacyAlias.route()).isSameAs(LEGACY);
        assertThat(legacyAlias.resolvedService()).as("alias stripped even without rewrite").isEqualTo("legacy");

        RouteDecision legacy = Router.route(l, "legacy");
        assertThat(legacy.resolvedService()).isNull();
        assertThat(legacy.needsRewrite()).isFalse();

        RouteDecision def = Router.route(l, "orcl.example.org");
        assertThat(def.isDefault()).isTrue();
        assertThat(def.route()).isSameAs(DEFAULT);
        assertThat(def.resolvedService()).isNull();
        assertThat(def.datasource()).isNull();

        RouteDecision none = Router.route(l, null);
        assertThat(none.isDefault()).isTrue();
    }

    @Test
    void noMatchAndNoDefaultIsNull() {
        ListenerConfig l = new ListenerConfig("o", Engine.ORACLE, null, 1521, null, List.of(SALES), null);
        assertThat(Router.route(l, "nosuch")).isNull();
        assertThat(Router.route(l, null)).isNull();
        assertThat(Router.route(l, "sales.")).as("empty alias is not an alias").isNull();
    }

    @Test
    void defaultRouteCanRewrite() {
        ListenerConfig l = new ListenerConfig("p", Engine.POSTGRES, null, 0, null, List.of(),
                RouteConfig.defaultRoute("pg", 5432, "postgres", true));
        assertThat(l.port()).isEqualTo(5432);
        RouteDecision d = Router.route(l, "anything");
        assertThat(d.resolvedService()).isEqualTo("postgres");
        assertThat(d.needsRewrite()).isTrue();
    }
}
