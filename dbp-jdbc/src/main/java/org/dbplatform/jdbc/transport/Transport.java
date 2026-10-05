package org.dbplatform.jdbc.transport;

import org.dbplatform.protocol.Frame;
import org.dbplatform.protocol.FrameReader;
import org.dbplatform.protocol.FrameWriter;
import org.dbplatform.protocol.JdbcUrl;
import org.dbplatform.protocol.MessageType;
import org.dbplatform.protocol.ProtocolException;
import org.dbplatform.protocol.messages.Message;
import org.dbplatform.protocol.messages.Messages;
import org.dbplatform.protocol.messages.ResultSetHeader;

import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One TCP (or TLS) connection to a gateway carrying the DBP wire protocol.
 *
 * <p>The protocol is strictly synchronous: {@link #call(Message, int)} writes one request frame and reads
 * frames until the terminal frame for that request type arrives, returning every frame received in order.
 * All wire I/O is serialised on a per-transport lock, so the JDBC objects of one connection may be touched
 * from several threads while requests stay strictly sequential on the socket.</p>
 *
 * <p>Any {@link IOException} (including a {@link ProtocolException}) is fatal for the connection: the socket
 * is closed, {@link #isClosed()} becomes {@code true} and the failure is reported as
 * {@link SQLNonTransientConnectionException} with SQLState {@code 08006}. A failure to connect to every
 * listed host is reported with SQLState {@code 08001}.</p>
 */
public final class Transport implements AutoCloseable {

    /** SQLState for "unable to establish the connection". */
    public static final String STATE_CONNECT_FAILED = "08001";
    /** SQLState for "connection failure" after the session was established. */
    public static final String STATE_CONNECTION_FAILURE = "08006";

    private static final Logger LOG = Logger.getLogger("org.dbplatform.jdbc");

    private final ReentrantLock lock = new ReentrantLock();
    private final Socket socket;
    private final FrameReader reader;
    private final FrameWriter writer;
    private final JdbcUrl.HostPort endpoint;
    private volatile boolean closed;

    private Transport(Socket socket, JdbcUrl.HostPort endpoint, int maxFrameBytes) throws IOException {
        this.socket = socket;
        this.endpoint = endpoint;
        this.reader = new FrameReader(new BufferedInputStream(socket.getInputStream(), 64 * 1024), maxFrameBytes);
        this.writer = new FrameWriter(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024), maxFrameBytes);
    }

    /**
     * Connects to the first reachable host of {@code hosts} (tried in order).
     *
     * @param hosts            gateway endpoints in failover order
     * @param ssl              {@code true} to use {@link SSLSocketFactory#getDefault()}
     * @param connectTimeoutMs connect timeout per host in milliseconds ({@code 0} = system default)
     * @param socketTimeoutMs  read timeout in milliseconds ({@code 0} = none)
     * @param maxFrameBytes    maximum frame size accepted and produced
     * @return the connected transport
     * @throws SQLException with SQLState {@code 08001} if no host could be reached
     */
    public static Transport connect(List<JdbcUrl.HostPort> hosts, boolean ssl, int connectTimeoutMs,
                                    int socketTimeoutMs, int maxFrameBytes) throws SQLException {
        if (hosts == null || hosts.isEmpty()) {
            throw new SQLNonTransientConnectionException("no gateway host given", STATE_CONNECT_FAILED);
        }
        IOException last = null;
        for (JdbcUrl.HostPort hp : hosts) {
            Socket socket = null;
            try {
                socket = ssl ? SSLSocketFactory.getDefault().createSocket() : new Socket();
                socket.setTcpNoDelay(true);
                socket.setKeepAlive(true);
                socket.connect(new InetSocketAddress(hp.host(), hp.port()), Math.max(0, connectTimeoutMs));
                socket.setSoTimeout(Math.max(0, socketTimeoutMs));
                if (socket instanceof javax.net.ssl.SSLSocket ssls) {
                    ssls.startHandshake();
                }
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine("connected to gateway " + hp + (ssl ? " (TLS)" : ""));
                }
                return new Transport(socket, hp, maxFrameBytes);
            } catch (IOException | RuntimeException e) {
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine("cannot connect to gateway " + hp + ": " + e);
                }
                closeQuietly(socket);
                last = e instanceof IOException io ? io : new IOException(e.getMessage(), e);
            }
        }
        SQLNonTransientConnectionException ex = new SQLNonTransientConnectionException(
                "cannot connect to any gateway host " + hosts + ": " + last.getMessage(), STATE_CONNECT_FAILED);
        ex.initCause(last);
        throw ex;
    }

    /**
     * Returns the endpoint this transport is connected to.
     *
     * @return host and port
     */
    public JdbcUrl.HostPort endpoint() {
        return endpoint;
    }

    /**
     * Returns the lock that serialises wire I/O. Callers that need several requests to be uninterrupted by
     * other threads (e.g. close a cursor and then a statement) may hold it around the sequence.
     *
     * @return the per-connection lock
     */
    public ReentrantLock lock() {
        return lock;
    }

    /**
     * Returns whether the socket has been closed (explicitly or after an I/O failure).
     *
     * @return {@code true} if closed
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Changes the socket read timeout.
     *
     * @param millis timeout in milliseconds, {@code 0} = none
     * @throws SQLException if the socket refuses the change
     */
    public void setSocketTimeout(int millis) throws SQLException {
        try {
            socket.setSoTimeout(Math.max(0, millis));
        } catch (IOException e) {
            throw ioFailure(e);
        }
    }

    /**
     * Returns the current socket read timeout.
     *
     * @return timeout in milliseconds, {@code 0} = none
     */
    public int getSocketTimeout() {
        try {
            return socket.getSoTimeout();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Sends one request and reads every frame up to and including the terminal frame for that request type.
     * Intermediate frames ({@code RESULT_SET_HEADER}, {@code ROWS}, {@code UPDATE_COUNT}, {@code OUT_PARAMS},
     * {@code GENERATED_KEYS}) are collected in order; the last element of the result is the terminal frame
     * ({@code OK}, {@code ERROR}, {@code HELLO_OK}, {@code PONG}, {@code PREPARED}, {@code EXECUTE_DONE},
     * {@code BATCH_RESULT}, {@code SAVEPOINT_SET}, or {@code ROWS} for a {@code FETCH}).
     *
     * <p>{@code ROWS} frames are decoded with the column count of the most recent {@code RESULT_SET_HEADER}
     * of this exchange, or with {@code columnCountForRows} when no header preceded them (FETCH).</p>
     *
     * @param request            the request
     * @param columnCountForRows column count for ROWS frames that are not preceded by a header in this
     *                           exchange ({@code -1} when none are expected)
     * @return the frames received, terminal frame last; never empty
     * @throws SQLNonTransientConnectionException on any I/O or protocol failure (the transport is then closed)
     */
    public List<Message> call(Message request, int columnCountForRows) throws SQLException {
        lock.lock();
        try {
            if (closed) {
                throw new SQLNonTransientConnectionException("connection is closed", STATE_CONNECTION_FAILURE);
            }
            MessageType requestType = request.type();
            try {
                byte[] payload = request.encode();
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.fine("-> " + requestType + " (" + payload.length + " bytes)");
                }
                writer.writeFrame(requestType, payload);
                List<Message> replies = new ArrayList<>(4);
                int columnCount = columnCountForRows;
                while (true) {
                    Frame frame = reader.readFrame();
                    if (LOG.isLoggable(Level.FINE)) {
                        LOG.fine("<- " + frame.type() + " (" + frame.payload().length + " bytes)");
                    }
                    if (!frame.type().isServerToClient()) {
                        throw new ProtocolException("unexpected client->server frame " + frame.type()
                                + " received as a response to " + requestType);
                    }
                    Message m = Messages.decode(frame, columnCount);
                    if (m instanceof ResultSetHeader h) {
                        columnCount = h.columnCount();
                    }
                    replies.add(m);
                    if (isTerminal(requestType, frame.type())) {
                        return replies;
                    }
                }
            } catch (IOException e) {
                closeQuietly(socket);
                closed = true;
                throw ioFailure(e);
            }
        } finally {
            lock.unlock();
        }
    }

    private static boolean isTerminal(MessageType request, MessageType response) throws ProtocolException {
        return switch (response) {
            case OK, ERROR, HELLO_OK, PONG, PREPARED, EXECUTE_DONE, BATCH_RESULT, SAVEPOINT_SET -> true;
            case ROWS -> request == MessageType.FETCH;
            case RESULT_SET_HEADER, UPDATE_COUNT, OUT_PARAMS, GENERATED_KEYS -> false;
            default -> throw new ProtocolException("unexpected frame " + response + " in response to " + request);
        };
    }

    private SQLNonTransientConnectionException ioFailure(IOException e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        SQLNonTransientConnectionException ex = new SQLNonTransientConnectionException(
                "connection to gateway " + endpoint + " failed: " + detail, STATE_CONNECTION_FAILURE);
        ex.initCause(e);
        return ex;
    }

    /** Closes the socket; idempotent and never throws. */
    @Override
    public void close() {
        lock.lock();
        try {
            if (!closed) {
                closed = true;
                closeQuietly(socket);
            }
        } finally {
            lock.unlock();
        }
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nothing sensible to do
            }
        }
    }

    @Override
    public String toString() {
        return "Transport[" + endpoint + (closed ? ", closed" : "") + "]";
    }
}
