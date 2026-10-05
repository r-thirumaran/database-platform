package org.dbplatform.proxy.registry;

import org.dbplatform.proxy.config.Engine;
import org.dbplatform.proxy.identity.IdentitySource;
import org.dbplatform.proxy.identity.ResolvedIdentity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionRegistryTest {
    @Test
    void connectionIdsCarryAnInstanceComponentSoTheyDoNotRepeatAcrossRestarts() {
        ConnectionRegistry r = new ConnectionRegistry();
        String first = r.nextId();
        String second = r.nextId();
        assertThat(first).matches("c-[0-9a-z]+-1");
        assertThat(second).matches("c-[0-9a-z]+-2");
        assertThat(first).startsWith("c-" + r.instanceId() + "-");
        assertThat(Long.parseLong(r.instanceId(), 36)).as("instance = start time in base 36")
                .isBetween(System.currentTimeMillis() - 60_000, System.currentTimeMillis() + 1);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void clientSuppliedStringsAreSanitisedBeforeTheyReachLogsEventsAndJson() {
        LiveConnection c = new LiveConnection("c-x-1", "l", Engine.ORACLE, "127.0.0.1", 1);
        c.setRequestedService("sales.ok\nINFO forged");
        c.setResolvedService("FREE\u0000PDB1");
        c.setProgram("sqlplus\u001b[31m");
        c.setClientHost("host\r\n");
        c.setOsUser("u\tser");
        c.setDbUser("db\u0007user");
        c.setIdentity(new ResolvedIdentity(null, "alias\nwith newline", null, IdentitySource.SERVICE_ALIAS));
        assertThat(c.requestedService()).isEqualTo("sales.ok?INFO forged");
        assertThat(c.resolvedService()).isEqualTo("FREE?PDB1");
        assertThat(c.program()).isEqualTo("sqlplus?[31m");
        assertThat(c.clientHost()).isEqualTo("host??");
        assertThat(c.osUser()).isEqualTo("u?ser");
        assertThat(c.dbUser()).isEqualTo("db?user");
        assertThat(c.application()).isEqualTo("alias?with newline");
        assertThat(c.toMap().values()).noneSatisfy(v -> assertThat(String.valueOf(v)).containsAnyOf("\n", "\r", "\u001b", "\u0000"));
        assertThat(LiveConnection.sanitize(null)).isNull();
        assertThat(LiveConnection.sanitize("café plain")).as("non-ASCII text is kept").isEqualTo("café plain");
        c.setProgram(null);
        assertThat(c.program()).isNull();
    }

    @Test
    void terminalEventIsClaimedExactlyOnce() {
        LiveConnection c = new LiveConnection("c-x-2", "l", Engine.POSTGRES, "127.0.0.1", 1);
        assertThat(c.markTerminalEmitted()).isTrue();
        assertThat(c.markTerminalEmitted()).isFalse();
    }
}
