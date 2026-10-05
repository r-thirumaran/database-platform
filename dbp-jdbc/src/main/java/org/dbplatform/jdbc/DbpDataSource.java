package org.dbplatform.jdbc;

import org.dbplatform.protocol.ProtocolConstants;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLNonTransientConnectionException;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Plain (non-pooling) {@link DataSource} for the DBP driver, configurable as a bean: either {@link #setUrl}
 * or {@link #setHost}/{@link #setPort}/{@link #setDatasource}, plus the connection properties.
 * {@link #setUser}/{@link #setPassword} follow the same mapping as the driver ({@code password} is the api
 * key when {@code apiKey} is not set).
 */
public class DbpDataSource implements DataSource {

    private String url;
    private String host;
    private int port = ProtocolConstants.DEFAULT_PORT;
    private String datasource;
    private String apiKey;
    private String application;
    private String user;
    private String password;
    private Boolean ssl;
    private Integer connectTimeoutMs;
    private Integer socketTimeoutMs;
    private Integer fetchSize;
    private int loginTimeout;
    private PrintWriter logWriter;

    /** Creates an unconfigured data source. */
    public DbpDataSource() {
    }

    /**
     * Creates a data source for a URL.
     *
     * @param url the JDBC URL
     */
    public DbpDataSource(String url) {
        this.url = url;
    }

    // ---------------------------------------------------------------- DataSource

    @Override
    public Connection getConnection() throws SQLException {
        return getConnection(user, password);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        Properties props = buildProperties();
        if (username != null) {
            props.setProperty(DbpDriver.PROP_USER, username);
        }
        if (password != null) {
            props.setProperty(DbpDriver.PROP_PASSWORD, password);
        }
        String effectiveUrl = getEffectiveUrl();
        Connection c = DbpDriver.instance().connect(effectiveUrl, props);
        if (c == null) {
            throw new SQLNonTransientConnectionException("not a DBP JDBC URL: " + effectiveUrl, "08001");
        }
        return c;
    }

    /**
     * Returns the URL that will be used: {@link #getUrl()} when set, otherwise one built from host, port and
     * datasource.
     *
     * @return the JDBC URL
     * @throws SQLException if neither a URL nor host and datasource are configured
     */
    public String getEffectiveUrl() throws SQLException {
        if (url != null && !url.isBlank()) {
            return url;
        }
        if (host == null || host.isBlank() || datasource == null || datasource.isBlank()) {
            throw new SQLNonTransientConnectionException("DbpDataSource needs either url or host and datasource", "08001");
        }
        String h = host.indexOf(':') >= 0 && !host.startsWith("[") ? "[" + host + "]" : host;
        return ProtocolConstants.JDBC_URL_PREFIX + h + ":" + port + "/" + datasource;
    }

    private Properties buildProperties() {
        Properties p = new Properties();
        if (apiKey != null) {
            p.setProperty(DbpDriver.PROP_API_KEY, apiKey);
        }
        if (application != null) {
            p.setProperty(DbpDriver.PROP_APPLICATION, application);
        }
        if (ssl != null) {
            p.setProperty(DbpDriver.PROP_SSL, ssl.toString());
        }
        if (connectTimeoutMs != null) {
            p.setProperty(DbpDriver.PROP_CONNECT_TIMEOUT_MS, connectTimeoutMs.toString());
        } else if (loginTimeout > 0) {
            p.setProperty(DbpDriver.PROP_CONNECT_TIMEOUT_MS, Long.toString(loginTimeout * 1000L));
        }
        if (socketTimeoutMs != null) {
            p.setProperty(DbpDriver.PROP_SOCKET_TIMEOUT_MS, socketTimeoutMs.toString());
        }
        if (fetchSize != null) {
            p.setProperty(DbpDriver.PROP_FETCH_SIZE, fetchSize.toString());
        }
        return p;
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        this.loginTimeout = Math.max(0, seconds);
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() {
        return DbpDriver.instance().getParentLogger();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface != null && iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLFeatureNotSupportedException("cannot unwrap DbpDataSource to " + iface, "0A000");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface != null && iface.isInstance(this);
    }

    // ---------------------------------------------------------------- bean properties

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getDatasource() {
        return datasource;
    }

    public void setDatasource(String datasource) {
        this.datasource = datasource;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApplication() {
        return application;
    }

    public void setApplication(String application) {
        this.application = application;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isSsl() {
        return ssl != null && ssl;
    }

    public void setSsl(boolean ssl) {
        this.ssl = ssl;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs == null ? DbpDriver.DEFAULT_CONNECT_TIMEOUT_MS : connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getSocketTimeoutMs() {
        return socketTimeoutMs == null ? 0 : socketTimeoutMs;
    }

    public void setSocketTimeoutMs(int socketTimeoutMs) {
        this.socketTimeoutMs = socketTimeoutMs;
    }

    public int getFetchSize() {
        return fetchSize == null ? ProtocolConstants.DEFAULT_FETCH_SIZE : fetchSize;
    }

    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }

    @Override
    public String toString() {
        return "DbpDataSource[" + (url != null ? url : host + ":" + port + "/" + datasource) + "]";
    }
}
