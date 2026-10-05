package org.dbplatform.common.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Local host name resolution: {@code HOSTNAME}/{@code COMPUTERNAME} environment variables first (fast,
 * container friendly), then {@link InetAddress#getLocalHost()}, then {@code localhost}. Cached.
 */
public final class Hostnames {

    private static volatile String cached;

    private Hostnames() {}

    public static String localHostName() {
        String h = cached;
        if (h == null) {
            h = resolve();
            cached = h;
        }
        return h;
    }

    /** Short name (before the first dot). */
    public static String localShortName() {
        String h = localHostName();
        int dot = h.indexOf('.');
        return dot > 0 ? h.substring(0, dot) : h;
    }

    private static String resolve() {
        for (String var : new String[] {"HOSTNAME", "COMPUTERNAME"}) {
            String v = System.getenv(var);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        try {
            String n = InetAddress.getLocalHost().getHostName();
            if (n != null && !n.isBlank()) {
                return n;
            }
        } catch (UnknownHostException | RuntimeException ignored) {
            // fall through
        }
        return "localhost";
    }
}
