package org.dbplatform.controlplane.collector;

import java.util.List;
import java.util.Optional;
import org.dbplatform.controlplane.domain.Application;
import org.dbplatform.controlplane.domain.DatabaseInstance;
import org.dbplatform.controlplane.domain.Enums.RelationshipSource;
import org.dbplatform.controlplane.domain.IdentityRules;
import org.dbplatform.controlplane.service.telemetry.LiveConnectionRegistry;

/**
 * Maps an observed database session to an application: proxy correlation (session PORT == proxyLocalPort
 * of a live proxied connection to this database) first, then the identity rules in contract order
 * (serviceAliases → pgApplicationNames / programNames → machinePatterns → cidrs).
 */
public final class SessionAttributor {
    private final LiveConnectionRegistry live;

    public SessionAttributor(LiveConnectionRegistry live) { this.live = live; }

    public record Attribution(String applicationId, RelationshipSource source, String how) {}

    public Optional<Attribution> attribute(DatabaseInstance db, Model.SessionInfo s, List<Application> apps) {
        if (s.port != null) {
            Optional<LiveConnectionRegistry.ProxyConnection> pc = live.correlate(db.getHost(), db.getPort(), s.port);
            if (pc.isPresent() && pc.get().applicationId() != null) {
                return Optional.of(new Attribution(pc.get().applicationId(), RelationshipSource.PROXY_CORRELATION, "proxy port " + s.port));
            }
            if (pc.isPresent()) {
                // proxied but unidentified by the proxy: keep going with the proxy's client attributes
                if (s.program == null) s.program = pc.get().program();
                if (s.machine == null) s.machine = pc.get().clientHost();
                if (s.clientAddr == null) s.clientAddr = pc.get().clientAddr();
            }
        }
        if (s.serviceName != null && s.serviceName.contains(".")) {
            String alias = s.serviceName.substring(s.serviceName.lastIndexOf('.') + 1);
            for (Application a : apps) if (Globs.matchesAny(a.getIdentityRules().getServiceAliases(), alias)) return rule(a, "serviceAlias " + alias);
        }
        for (Application a : apps) {
            IdentityRules r = a.getIdentityRules();
            if (Globs.matchesAny(r.getPgApplicationNames(), s.program) || Globs.matchesAny(r.getProgramNames(), s.program, s.module, s.clientIdentifier)) {
                return rule(a, "program " + (s.program != null ? s.program : s.module));
            }
        }
        for (Application a : apps) if (Globs.matchesAny(a.getIdentityRules().getMachinePatterns(), s.machine)) return rule(a, "machine " + s.machine);
        for (Application a : apps) if (Globs.inAnyCidr(a.getIdentityRules().getCidrs(), s.clientAddr != null ? s.clientAddr : s.machine)) return rule(a, "cidr " + s.clientAddr);
        return Optional.empty();
    }

    private static Optional<Attribution> rule(Application a, String how) {
        return Optional.of(new Attribution(a.getId(), RelationshipSource.COLLECTOR_SESSION, how));
    }
}
