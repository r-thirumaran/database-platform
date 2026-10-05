package org.dbplatform.proxy.oracle;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Semantic view of an Oracle connect string: the requested service and client identification, plus
 * the rewriting used by the proxy before forwarding the CONNECT to the backend.
 */
public final class TnsConnectString {
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

    private final String raw;
    private final TnsDescriptor descriptor;
    private final String serviceName;
    private final String sid;
    private final String instanceName;
    private final String program;
    private final String host;
    private final String user;
    private final String server;
    private final boolean ping;

    private TnsConnectString(String raw, TnsDescriptor descriptor) {
        this.raw = raw;
        this.descriptor = descriptor;
        TnsNode cd = descriptor == null ? null : descriptor.findAny("CONNECT_DATA");
        this.serviceName = cd == null ? null : clean(cd.findValue("SERVICE_NAME"));
        this.sid = cd == null ? null : clean(cd.findValue("SID"));
        this.instanceName = cd == null ? null : clean(cd.findValue("INSTANCE_NAME"));
        this.program = cd == null ? null : clean(cd.findValue("CID", "PROGRAM"));
        this.host = cd == null ? null : clean(cd.findValue("CID", "HOST"));
        this.user = cd == null ? null : clean(cd.findValue("CID", "USER"));
        this.server = cd == null ? null : clean(cd.findValue("SERVER"));
        this.ping = cd != null && cd.find("COMMAND") != null;
    }

    /** Parse leniently: a string that is not a descriptor yields an instance with no fields set. */
    public static TnsConnectString parse(String connectData) {
        if (!TnsDescriptor.looksLikeDescriptor(connectData)) {
            return new TnsConnectString(connectData, null);
        }
        try {
            return new TnsConnectString(connectData, TnsDescriptor.parse(connectData));
        } catch (TnsParseException e) {
            return new TnsConnectString(connectData, null);
        }
    }

    public boolean isDescriptor() {
        return descriptor != null;
    }

    public String raw() {
        return raw;
    }

    public TnsDescriptor descriptor() {
        return descriptor;
    }

    /** SERVICE_NAME, else SID, else {@code null}. */
    public String requestedService() {
        return serviceName != null ? serviceName : sid;
    }

    public String serviceName() {
        return serviceName;
    }

    public String sid() {
        return sid;
    }

    public String instanceName() {
        return instanceName;
    }

    public String program() {
        return program;
    }

    public String host() {
        return host;
    }

    public String user() {
        return user;
    }

    public String server() {
        return server;
    }

    /** {@code (CONNECT_DATA=(COMMAND=ping))} and similar listener commands. */
    public boolean isPing() {
        return ping;
    }

    /**
     * Rewritten connect string: every ADDRESS points at the backend, and when {@code newService} is
     * non-null CONNECT_DATA gets {@code SERVICE_NAME=newService} with any SID removed. Unknown keys are
     * preserved. Returns the raw string unchanged when it is not a descriptor.
     */
    public String rewrite(String newService, String backendHost, int backendPort) {
        if (descriptor == null) {
            return raw;
        }
        List<TnsNode> addresses = descriptor.findAll("ADDRESS");
        for (TnsNode a : addresses) {
            String proto = a.findValue("PROTOCOL");
            if (proto == null || proto.equalsIgnoreCase("tcp") || proto.equalsIgnoreCase("tcps")) {
                a.put("HOST", backendHost);
                a.put("PORT", Integer.toString(backendPort));
            }
        }
        if (newService != null) {
            TnsNode cd = descriptor.findAny("CONNECT_DATA");
            if (cd == null) {
                cd = new TnsNode("CONNECT_DATA");
                descriptor.root().add(cd);
            }
            cd.remove("SID");
            cd.put("SERVICE_NAME", newService);
        }
        return descriptor.serialize();
    }

    /** Best-effort re-decode of a wire (ISO-8859-1) string as UTF-8 for display / telemetry. */
    public static String display(String wire) {
        if (wire == null) {
            return null;
        }
        for (int i = 0; i < wire.length(); i++) {
            if (wire.charAt(i) > 0x7F) {
                return new String(wire.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            }
        }
        return wire;
    }

    /** Strips, unquotes and replaces control characters with {@code ?} (these values end up in logs and telemetry). */
    private static String clean(String v) {
        if (v == null) {
            return null;
        }
        String s = CONTROL_CHARS.matcher(TnsRedirectPacket.unquote(v.strip())).replaceAll("?");
        return s.isEmpty() ? null : s;
    }

    @Override
    public String toString() {
        return raw;
    }
}
