package org.dbplatform.proxy.identity;

import org.dbplatform.proxy.config.ApplicationConfig;
import org.dbplatform.proxy.config.IdentityRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps a connection to an application. Precedence (first hit wins):
 * <ol>
 *   <li>SERVICE_ALIAS — the {@code <alias>} suffix of the requested service matches an application's
 *       {@code serviceAliases} (or, as a fallback, its name). An alias nobody declares still yields the
 *       alias as application name (with a null id) because the caller asked for it explicitly.</li>
 *   <li>PROGRAM / APPLICATION_NAME — Oracle {@code CID.PROGRAM} against {@code programNames};
 *       PostgreSQL {@code application_name} against {@code pgApplicationNames} then {@code programNames}.</li>
 *   <li>MACHINE — Oracle {@code CID.HOST} against {@code machinePatterns} (glob).</li>
 *   <li>CIDR — client address against {@code cidrs}.</li>
 *   <li>NONE — {@code "unknown"}.</li>
 * </ol>
 */
public final class IdentityResolver {
    private static final Logger LOG = LoggerFactory.getLogger(IdentityResolver.class);
    private final List<ApplicationConfig> applications;
    private final List<List<CidrMatcher>> cidrs;

    public IdentityResolver(List<ApplicationConfig> applications) {
        this.applications = applications == null ? List.of() : List.copyOf(applications);
        this.cidrs = new ArrayList<>(this.applications.size());
        for (ApplicationConfig app : this.applications) {
            List<CidrMatcher> m = new ArrayList<>();
            for (String c : app.identityRules().cidrs()) {
                try {
                    m.add(CidrMatcher.parse(c));
                } catch (IllegalArgumentException e) {
                    LOG.warn("application {}: ignoring invalid CIDR '{}'", app.name(), c);
                }
            }
            cidrs.add(m);
        }
    }

    public static IdentityResolver empty() {
        return new IdentityResolver(List.of());
    }

    public ResolvedIdentity resolve(IdentityInput in) {
        if (in.serviceAlias() != null && !in.serviceAlias().isBlank()) {
            String alias = in.serviceAlias().strip();
            for (ApplicationConfig app : applications) {
                for (String a : app.identityRules().serviceAliases()) {
                    if (a.equalsIgnoreCase(alias)) {
                        return of(app, IdentitySource.SERVICE_ALIAS);
                    }
                }
            }
            for (ApplicationConfig app : applications) {
                if (app.name().equalsIgnoreCase(alias)) {
                    return of(app, IdentitySource.SERVICE_ALIAS);
                }
            }
            return new ResolvedIdentity(null, alias, null, IdentitySource.SERVICE_ALIAS);
        }
        if (in.program() != null && !in.program().isBlank()) {
            for (ApplicationConfig app : applications) {
                if (anyGlob(app.identityRules().programNames(), in.program())) {
                    return of(app, IdentitySource.PROGRAM);
                }
            }
        }
        if (in.pgApplicationName() != null && !in.pgApplicationName().isBlank()) {
            for (ApplicationConfig app : applications) {
                IdentityRules r = app.identityRules();
                if (anyGlob(r.pgApplicationNames(), in.pgApplicationName()) || anyGlob(r.programNames(), in.pgApplicationName())) {
                    return of(app, IdentitySource.APPLICATION_NAME);
                }
            }
        }
        if (in.machine() != null && !in.machine().isBlank()) {
            for (ApplicationConfig app : applications) {
                if (anyGlob(app.identityRules().machinePatterns(), in.machine())) {
                    return of(app, IdentitySource.MACHINE);
                }
            }
        }
        if (in.clientAddress() != null) {
            for (int i = 0; i < applications.size(); i++) {
                for (CidrMatcher m : cidrs.get(i)) {
                    if (m.matches(in.clientAddress())) {
                        return of(applications.get(i), IdentitySource.CIDR);
                    }
                }
            }
        }
        return ResolvedIdentity.unknown();
    }

    private static boolean anyGlob(List<String> patterns, String value) {
        for (String p : patterns) {
            if (GlobMatcher.matches(p, value)) {
                return true;
            }
        }
        return false;
    }

    private static ResolvedIdentity of(ApplicationConfig app, IdentitySource source) {
        return new ResolvedIdentity(app.id(), app.name(), app.teamId(), source);
    }
}
