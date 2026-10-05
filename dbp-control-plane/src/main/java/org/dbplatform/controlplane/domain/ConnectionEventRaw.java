package org.dbplatform.controlplane.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Raw proxy ConnectionEvent kept for a bounded retention window. */
@Entity
@Table(name = "connection_event_raw")
public class ConnectionEventRaw {
    @Id @Column(length = 64) private String eventId;
    @Column(nullable = false) private Instant receivedAt;
    private Instant eventTime;
    private String proxyId;
    @Column(length = 20) private String eventType;
    private String listener;
    @Column(length = 20) private String engine;
    private String connectionId;
    private String clientAddr;
    private Integer clientPort;
    private String proxyLocalAddr;
    private Integer proxyLocalPort;
    private String backendHost;
    private Integer backendPort;
    private String requestedService;
    private String resolvedService;
    @Column(length = 36) private String applicationId;
    private String applicationName;
    private String identitySource;
    @Column(length = 36) private String datasourceId;
    private String datasourceName;
    private String program;
    private String clientHost;
    private String osUser;
    private String dbUser;
    private Instant openedAt;
    private Instant closedAt;
    private Long durationMs;
    private Long bytesIn;
    private Long bytesOut;
    private String reason;

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public Instant getEventTime() { return eventTime; }
    public void setEventTime(Instant eventTime) { this.eventTime = eventTime; }
    public String getProxyId() { return proxyId; }
    public void setProxyId(String proxyId) { this.proxyId = proxyId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getListener() { return listener; }
    public void setListener(String listener) { this.listener = listener; }
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }
    public String getConnectionId() { return connectionId; }
    public void setConnectionId(String connectionId) { this.connectionId = connectionId; }
    public String getClientAddr() { return clientAddr; }
    public void setClientAddr(String clientAddr) { this.clientAddr = clientAddr; }
    public Integer getClientPort() { return clientPort; }
    public void setClientPort(Integer clientPort) { this.clientPort = clientPort; }
    public String getProxyLocalAddr() { return proxyLocalAddr; }
    public void setProxyLocalAddr(String proxyLocalAddr) { this.proxyLocalAddr = proxyLocalAddr; }
    public Integer getProxyLocalPort() { return proxyLocalPort; }
    public void setProxyLocalPort(Integer proxyLocalPort) { this.proxyLocalPort = proxyLocalPort; }
    public String getBackendHost() { return backendHost; }
    public void setBackendHost(String backendHost) { this.backendHost = backendHost; }
    public Integer getBackendPort() { return backendPort; }
    public void setBackendPort(Integer backendPort) { this.backendPort = backendPort; }
    public String getRequestedService() { return requestedService; }
    public void setRequestedService(String requestedService) { this.requestedService = requestedService; }
    public String getResolvedService() { return resolvedService; }
    public void setResolvedService(String resolvedService) { this.resolvedService = resolvedService; }
    public String getApplicationId() { return applicationId; }
    public void setApplicationId(String applicationId) { this.applicationId = applicationId; }
    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }
    public String getIdentitySource() { return identitySource; }
    public void setIdentitySource(String identitySource) { this.identitySource = identitySource; }
    public String getDatasourceId() { return datasourceId; }
    public void setDatasourceId(String datasourceId) { this.datasourceId = datasourceId; }
    public String getDatasourceName() { return datasourceName; }
    public void setDatasourceName(String datasourceName) { this.datasourceName = datasourceName; }
    public String getProgram() { return program; }
    public void setProgram(String program) { this.program = program; }
    public String getClientHost() { return clientHost; }
    public void setClientHost(String clientHost) { this.clientHost = clientHost; }
    public String getOsUser() { return osUser; }
    public void setOsUser(String osUser) { this.osUser = osUser; }
    public String getDbUser() { return dbUser; }
    public void setDbUser(String dbUser) { this.dbUser = dbUser; }
    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public Long getBytesIn() { return bytesIn; }
    public void setBytesIn(Long bytesIn) { this.bytesIn = bytesIn; }
    public Long getBytesOut() { return bytesOut; }
    public void setBytesOut(Long bytesOut) { this.bytesOut = bytesOut; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
