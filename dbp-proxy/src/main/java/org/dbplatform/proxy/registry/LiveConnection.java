package org.dbplatform.proxy.registry;

import org.dbplatform.proxy.config.Engine;
import org.dbplatform.proxy.identity.IdentitySource;
import org.dbplatform.proxy.identity.ResolvedIdentity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Pattern;

/**
 * Mutable record of one proxied client connection. Fields are filled in as the handshake progresses
 * and read concurrently by the admin API, the heartbeat snapshot and the quota manager. Client-supplied
 * strings (requested service, program, host, users, the application name derived from an alias) are
 * {@linkplain #sanitize(String) sanitised} on the way in so that control characters cannot forge log
 * lines, break the admin JSON or reach telemetry.
 */
public final class LiveConnection {
    public enum State { HANDSHAKE, CONNECTING, ESTABLISHED, CLOSING, CLOSED, REFUSED }

    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");

    /** Replaces every control character (including CR, LF, TAB, ESC, NUL) with {@code ?}; null-safe. */
    public static String sanitize(String s) {
        return s == null ? null : CONTROL_CHARS.matcher(s).replaceAll("?");
    }

    private final String id;
    private final String listener;
    private final Engine engine;
    private final String clientAddr;
    private final int clientPort;
    private final Instant openedAt;
    private final long openedNanos = System.nanoTime();
    private final LongAdder bytesIn = new LongAdder();
    private final LongAdder bytesOut = new LongAdder();

    private volatile State state = State.HANDSHAKE;
    private volatile String backendHost;
    private volatile int backendPort;
    private volatile String proxyLocalAddr;
    private volatile int proxyLocalPort;
    private volatile String requestedService;
    private volatile String resolvedService;
    private volatile String applicationId;
    private volatile String application = ResolvedIdentity.UNKNOWN;
    private volatile String teamId;
    private volatile IdentitySource identitySource = IdentitySource.NONE;
    private volatile String datasourceId;
    private volatile String datasource;
    private volatile String databaseId;
    private volatile String program;
    private volatile String clientHost;
    private volatile String osUser;
    private volatile String dbUser;
    private volatile Instant closedAt;
    private volatile String reason;
    private volatile long lastActivityNanos = System.nanoTime();
    private final AtomicBoolean terminalEmitted = new AtomicBoolean();

    public LiveConnection(String id, String listener, Engine engine, String clientAddr, int clientPort) {
        this.id = id;
        this.listener = listener;
        this.engine = engine;
        this.clientAddr = clientAddr;
        this.clientPort = clientPort;
        this.openedAt = Instant.now();
    }

    public String id() {
        return id;
    }

    public String listener() {
        return listener;
    }

    public Engine engine() {
        return engine;
    }

    public String clientAddr() {
        return clientAddr;
    }

    public int clientPort() {
        return clientPort;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public State state() {
        return state;
    }

    public void setState(State s) {
        this.state = s;
    }

    public String backendHost() {
        return backendHost;
    }

    public int backendPort() {
        return backendPort;
    }

    public String backend() {
        return backendHost == null ? null : backendHost + ":" + backendPort;
    }

    public void setBackend(String host, int port) {
        this.backendHost = host;
        this.backendPort = port;
    }

    public String proxyLocalAddr() {
        return proxyLocalAddr;
    }

    public int proxyLocalPort() {
        return proxyLocalPort;
    }

    public void setProxyLocal(String addr, int port) {
        this.proxyLocalAddr = addr;
        this.proxyLocalPort = port;
    }

    public String requestedService() {
        return requestedService;
    }

    public void setRequestedService(String s) {
        this.requestedService = sanitize(s);
    }

    public String resolvedService() {
        return resolvedService;
    }

    public void setResolvedService(String s) {
        this.resolvedService = sanitize(s);
    }

    public String applicationId() {
        return applicationId;
    }

    public String application() {
        return application;
    }

    public String teamId() {
        return teamId;
    }

    public IdentitySource identitySource() {
        return identitySource;
    }

    public void setIdentity(ResolvedIdentity identity) {
        this.applicationId = identity.applicationId();
        this.application = sanitize(identity.application()); // an undeclared alias is client-supplied text
        this.teamId = identity.teamId();
        this.identitySource = identity.source();
    }

    public String datasourceId() {
        return datasourceId;
    }

    public String datasource() {
        return datasource;
    }

    public String databaseId() {
        return databaseId;
    }

    public void setDatasource(String datasourceId, String datasource, String databaseId) {
        this.datasourceId = datasourceId;
        this.datasource = datasource;
        this.databaseId = databaseId;
    }

    public String program() {
        return program;
    }

    public void setProgram(String program) {
        this.program = sanitize(program);
    }

    public String clientHost() {
        return clientHost;
    }

    public void setClientHost(String clientHost) {
        this.clientHost = sanitize(clientHost);
    }

    public String osUser() {
        return osUser;
    }

    public void setOsUser(String osUser) {
        this.osUser = sanitize(osUser);
    }

    public String dbUser() {
        return dbUser;
    }

    public void setDbUser(String dbUser) {
        this.dbUser = sanitize(dbUser);
    }

    public Instant closedAt() {
        return closedAt;
    }

    public void markClosed(String reason) {
        this.closedAt = Instant.now();
        this.reason = reason;
        this.state = State.CLOSED;
    }

    public String reason() {
        return reason;
    }

    /**
     * Claims the right to emit this connection's terminal event (CLOSE / REFUSED / BACKEND_FAILED):
     * true exactly once, so the handler and a proxy shutdown cannot both report the end of a connection.
     */
    public boolean markTerminalEmitted() {
        return terminalEmitted.compareAndSet(false, true);
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public long bytesIn() {
        return bytesIn.sum();
    }

    public long bytesOut() {
        return bytesOut.sum();
    }

    public void addBytesIn(long n) {
        bytesIn.add(n);
        lastActivityNanos = System.nanoTime();
    }

    public void addBytesOut(long n) {
        bytesOut.add(n);
        lastActivityNanos = System.nanoTime();
    }

    public void touch() {
        lastActivityNanos = System.nanoTime();
    }

    public long idleMillis() {
        return (System.nanoTime() - lastActivityNanos) / 1_000_000L;
    }

    public long durationMillis() {
        Instant end = closedAt;
        if (end != null) {
            return Math.max(0, end.toEpochMilli() - openedAt.toEpochMilli());
        }
        return (System.nanoTime() - openedNanos) / 1_000_000L;
    }

    /** JSON-friendly view used by {@code GET /connections} and the heartbeat snapshot. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("connectionId", id);
        m.put("listener", listener);
        m.put("engine", engine.name());
        m.put("state", state.name());
        m.put("clientAddr", clientAddr);
        m.put("clientPort", clientPort);
        m.put("proxyLocalAddr", proxyLocalAddr);
        m.put("proxyLocalPort", proxyLocalPort);
        m.put("backendHost", backendHost);
        m.put("backendPort", backendPort);
        m.put("requestedService", requestedService);
        m.put("resolvedService", resolvedService);
        m.put("applicationId", applicationId);
        m.put("application", application);
        m.put("teamId", teamId);
        m.put("identitySource", identitySource.name());
        m.put("datasourceId", datasourceId);
        m.put("datasource", datasource);
        m.put("databaseId", databaseId);
        m.put("program", program);
        m.put("clientHost", clientHost);
        m.put("osUser", osUser);
        m.put("dbUser", dbUser);
        m.put("openedAt", openedAt.toString());
        m.put("closedAt", closedAt == null ? null : closedAt.toString());
        m.put("durationMs", durationMillis());
        m.put("bytesIn", bytesIn());
        m.put("bytesOut", bytesOut());
        m.put("reason", reason);
        return m;
    }

    @Override
    public String toString() {
        return id + "[" + listener + " " + clientAddr + ":" + clientPort + " app=" + application + " ds=" + datasource + "]";
    }
}
