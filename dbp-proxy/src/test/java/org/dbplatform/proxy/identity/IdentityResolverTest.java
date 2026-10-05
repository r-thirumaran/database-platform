package org.dbplatform.proxy.identity;

import org.dbplatform.proxy.config.ApplicationConfig;
import org.dbplatform.proxy.config.IdentityRules;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdentityResolverTest {
    static final ApplicationConfig ORDERS = new ApplicationConfig("a1", "orders-service", "t1",
            new IdentityRules(List.of("10.20.0.0/16"), List.of("JDBC Thin Client/orders", "orders-*"), List.of("orders-service-*"),
                    List.of("orders", "orders-service"), List.of("orders-svc")));
    static final ApplicationConfig BATCH = new ApplicationConfig("a2", "nightly-batch", "t1",
            new IdentityRules(List.of("10.20.5.0/24", "::1/128"), List.of("sqlplus*"), List.of("batch?"), List.of(), List.of("nightly-batch")));
    static final IdentityResolver RESOLVER = new IdentityResolver(List.of(ORDERS, BATCH));

    static InetAddress ip(String s) throws Exception {
        return InetAddress.getByName(s);
    }

    @Test
    void precedenceIsAliasProgramMachineCidr() throws Exception {
        // alias wins even when every other rule points at the batch app
        ResolvedIdentity r = RESOLVER.resolve(new IdentityInput("orders", "sqlplus@host", null, "batch1", ip("10.20.5.9")));
        assertThat(r.source()).isEqualTo(IdentitySource.SERVICE_ALIAS);
        assertThat(r.application()).isEqualTo("orders-service");
        assertThat(r.applicationId()).isEqualTo("a1");
        assertThat(r.teamId()).isEqualTo("t1");

        r = RESOLVER.resolve(new IdentityInput(null, "sqlplus@host", null, "orders-service-7", ip("10.20.1.1")));
        assertThat(r.source()).isEqualTo(IdentitySource.PROGRAM);
        assertThat(r.application()).isEqualTo("nightly-batch");

        r = RESOLVER.resolve(new IdentityInput(null, "unknown.exe", null, "orders-service-7f9c", ip("10.20.5.1")));
        assertThat(r.source()).isEqualTo(IdentitySource.MACHINE);
        assertThat(r.application()).isEqualTo("orders-service");

        r = RESOLVER.resolve(new IdentityInput(null, null, null, null, ip("10.20.5.1")));
        assertThat(r.source()).isEqualTo(IdentitySource.CIDR);
        assertThat(r.application()).as("first application in list order wins on overlapping CIDRs").isEqualTo("orders-service");

        r = RESOLVER.resolve(new IdentityInput(null, null, null, null, ip("::1")));
        assertThat(r.source()).isEqualTo(IdentitySource.CIDR);
        assertThat(r.application()).isEqualTo("nightly-batch");

        r = RESOLVER.resolve(new IdentityInput(null, null, null, null, ip("192.168.1.1")));
        assertThat(r.source()).isEqualTo(IdentitySource.NONE);
        assertThat(r.application()).isEqualTo("unknown");
        assertThat(r.applicationId()).isNull();
    }

    @Test
    void postgresApplicationNameMatchesPgNamesThenProgramNamesAndAliasFallsBackToName() throws Exception {
        ResolvedIdentity r = RESOLVER.resolve(new IdentityInput(null, null, "orders-svc", null, null));
        assertThat(r.source()).isEqualTo(IdentitySource.APPLICATION_NAME);
        assertThat(r.application()).isEqualTo("orders-service");
        r = RESOLVER.resolve(new IdentityInput(null, null, "orders-api", null, null));
        assertThat(r.source()).as("programNames also apply to PG application_name").isEqualTo(IdentitySource.APPLICATION_NAME);
        r = RESOLVER.resolve(new IdentityInput("nightly-batch", null, null, null, null));
        assertThat(r.source()).isEqualTo(IdentitySource.SERVICE_ALIAS);
        assertThat(r.applicationId()).isEqualTo("a2");
        r = RESOLVER.resolve(new IdentityInput("not-registered", null, null, null, null));
        assertThat(r.source()).isEqualTo(IdentitySource.SERVICE_ALIAS);
        assertThat(r.application()).isEqualTo("not-registered");
        assertThat(r.applicationId()).isNull();
    }

    @Test
    void globAndCidrMatchers() throws Exception {
        assertThat(GlobMatcher.matches("orders-*", "ORDERS-service")).isTrue();
        assertThat(GlobMatcher.matches("batch?", "batch12")).isFalse();
        assertThat(GlobMatcher.matches("JDBC Thin Client", "jdbc thin client")).isTrue();
        assertThat(GlobMatcher.matches("a.b", "aXb")).isFalse();
        assertThat(CidrMatcher.parse("10.20.0.0/16").matches(ip("10.20.255.1"))).isTrue();
        assertThat(CidrMatcher.parse("10.20.0.0/16").matches(ip("10.21.0.1"))).isFalse();
        assertThat(CidrMatcher.parse("10.20.0.0/16").matches(ip("::ffff:10.20.3.4"))).isTrue();
        assertThat(CidrMatcher.parse("10.20.3.4").matches(ip("10.20.3.4"))).isTrue();
        assertThat(CidrMatcher.parse("10.20.3.4").matches(ip("10.20.3.5"))).isFalse();
        assertThat(CidrMatcher.parse("fd00::/8").matches(ip("fd12::1"))).isTrue();
    }
}
