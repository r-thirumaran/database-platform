package org.dbplatform.jdbc;

import org.dbplatform.protocol.ColumnMeta;
import org.dbplatform.protocol.Frame;
import org.dbplatform.protocol.FrameReader;
import org.dbplatform.protocol.FrameWriter;
import org.dbplatform.protocol.ProtocolConstants;
import org.dbplatform.protocol.Warning;
import org.dbplatform.protocol.messages.BatchResult;
import org.dbplatform.protocol.messages.Close;
import org.dbplatform.protocol.messages.CloseCursor;
import org.dbplatform.protocol.messages.CloseStatement;
import org.dbplatform.protocol.messages.ErrorMessage;
import org.dbplatform.protocol.messages.Execute;
import org.dbplatform.protocol.messages.ExecuteBatch;
import org.dbplatform.protocol.messages.ExecuteDone;
import org.dbplatform.protocol.messages.Fetch;
import org.dbplatform.protocol.messages.GeneratedKeys;
import org.dbplatform.protocol.messages.Hello;
import org.dbplatform.protocol.messages.HelloOk;
import org.dbplatform.protocol.messages.Message;
import org.dbplatform.protocol.messages.Messages;
import org.dbplatform.protocol.messages.Metadata;
import org.dbplatform.protocol.messages.Ok;
import org.dbplatform.protocol.messages.OutParams;
import org.dbplatform.protocol.messages.Ping;
import org.dbplatform.protocol.messages.Pong;
import org.dbplatform.protocol.messages.Prepare;
import org.dbplatform.protocol.messages.Prepared;
import org.dbplatform.protocol.messages.ResultSetHeader;
import org.dbplatform.protocol.messages.Rows;
import org.dbplatform.protocol.messages.SavepointSet;
import org.dbplatform.protocol.messages.SetClientInfo;
import org.dbplatform.protocol.messages.SetSavepoint;
import org.dbplatform.protocol.messages.StatementKind;
import org.dbplatform.protocol.messages.UpdateCount;

import java.io.EOFException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fake gateway for driver tests, built on {@code dbp-protocol}: accept loop on an ephemeral port, one
 * thread per connection, every received message recorded, replies produced by a {@link Handler}. The default
 * {@link EchoHandler} implements a small in-memory behaviour (see its documentation); tests may install a
 * scripted handler with {@link #setHandler(Handler)} and delegate to {@link #echo()} for everything else.
 */
public final class FakeGateway implements AutoCloseable {

    /** Produces the replies for one request. */
    @FunctionalInterface
    public interface Handler {
        void handle(Message request, Session session) throws IOException;
    }

    /** Per-connection state and reply channel. */
    public final class Session {
        private final Socket socket;
        private final FrameWriter out;
        public final Map<String, String> helloProperties = new LinkedHashMap<>();
        public final Map<Integer, String> statements = new HashMap<>();
        public final Map<Integer, Cursor> cursors = new HashMap<>();
        public final Map<String, String> clientInfo = new LinkedHashMap<>();
        public final List<String> savepoints = new ArrayList<>();
        public boolean autoCommit = true;
        private int nextStatementId = 1;
        private int nextCursorId = 1;
        private boolean closeAfterReply;

        Session(Socket socket) throws IOException {
            this.socket = socket;
            this.out = new FrameWriter(socket.getOutputStream());
        }

        /** Sends frames to the client. */
        public void reply(Message... messages) throws IOException {
            for (Message m : messages) {
                Messages.write(out, m);
            }
        }

        /** Closes the socket after the current reply has been sent. */
        public void closeAfterReply() {
            closeAfterReply = true;
        }

        public int newStatementId() {
            return nextStatementId++;
        }

        public int newCursorId() {
            return nextCursorId++;
        }
    }

    /** A server-side cursor: remaining rows of a result. */
    public static final class Cursor {
        public final int id;
        public final List<ColumnMeta> columns;
        private final List<List<Object>> remaining;

        public Cursor(int id, List<ColumnMeta> columns, List<List<Object>> rows) {
            this.id = id;
            this.columns = columns;
            this.remaining = new ArrayList<>(rows);
        }

        public Rows take(int n) {
            int take = n <= 0 ? ProtocolConstants.DEFAULT_FETCH_SIZE : Math.min(n, remaining.size());
            take = Math.min(take, remaining.size());
            List<List<Object>> batch = new ArrayList<>(remaining.subList(0, take));
            remaining.subList(0, take).clear();
            return new Rows(id, batch, remaining.isEmpty());
        }

        public int remaining() {
            return remaining.size();
        }
    }

    private final ServerSocket server;
    private final Thread acceptor;
    private final List<Message> received = new CopyOnWriteArrayList<>();
    private final List<Session> sessions = new CopyOnWriteArrayList<>();
    private final EchoHandler echo = new EchoHandler();
    private volatile Handler handler = echo;
    private volatile boolean running = true;
    private final AtomicInteger sessionCounter = new AtomicInteger();

    private FakeGateway(ServerSocket server) {
        this.server = server;
        this.acceptor = new Thread(this::acceptLoop, "fake-gateway-acceptor");
        this.acceptor.setDaemon(true);
    }

    public static FakeGateway start() throws IOException {
        ServerSocket ss = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
        FakeGateway g = new FakeGateway(ss);
        g.acceptor.start();
        return g;
    }

    public int port() {
        return server.getLocalPort();
    }

    public String host() {
        return server.getInetAddress().getHostAddress();
    }

    public String url(String datasource) {
        return "jdbc:dbp://" + host() + ":" + port() + "/" + datasource;
    }

    public String url(String datasource, String query) {
        return url(datasource) + "?" + query;
    }

    public EchoHandler echo() {
        return echo;
    }

    public void setHandler(Handler handler) {
        this.handler = handler == null ? echo : handler;
    }

    public List<Message> received() {
        return Collections.unmodifiableList(new ArrayList<>(received));
    }

    public <T extends Message> List<T> received(Class<T> type) {
        List<T> l = new ArrayList<>();
        for (Message m : received) {
            if (type.isInstance(m)) {
                l.add(type.cast(m));
            }
        }
        return l;
    }

    public <T extends Message> T last(Class<T> type) {
        List<T> l = received(type);
        if (l.isEmpty()) {
            throw new AssertionError("no " + type.getSimpleName() + " received; got " + received);
        }
        return l.get(l.size() - 1);
    }

    public void clearReceived() {
        received.clear();
    }

    public List<Session> sessions() {
        return sessions;
    }

    public int sessionCount() {
        return sessionCounter.get();
    }

    /** Returns the message types received, in order (for sequence assertions). */
    public List<String> receivedTypes() {
        List<String> l = new ArrayList<>();
        for (Message m : received) {
            l.add(m.type().name());
        }
        return l;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                s.setTcpNoDelay(true);
                Thread t = new Thread(() -> serve(s), "fake-gateway-session-" + sessionCounter.incrementAndGet());
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                }
                return;
            }
        }
    }

    private void serve(Socket socket) {
        Session session;
        try {
            session = new Session(socket);
        } catch (IOException e) {
            return;
        }
        sessions.add(session);
        int columnCount = -1;
        try (socket) {
            FrameReader in = new FrameReader(socket.getInputStream());
            while (running) {
                Frame frame = in.readFrame();
                Message request = Messages.decode(frame, columnCount);
                received.add(request);
                handler.handle(request, session);
                if (session.closeAfterReply) {
                    return;
                }
            }
        } catch (EOFException | SocketException e) {
            // client went away
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void close() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // ignore
        }
        for (Session s : sessions) {
            try {
                s.socket.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    // =================================================================================================
    // Echo handler
    // =================================================================================================

    /**
     * In-memory behaviour:
     * <ul>
     *   <li>HELLO → HELLO_OK (engine H2, realistic server properties); api key {@code bad-key} → fatal ERROR 08004.</li>
     *   <li>PREPARE → PREPARED with the number of {@code ?} in the SQL.</li>
     *   <li>EXECUTE of {@code SELECT …}: registered queries ({@link #registerQuery}) or a default 3-row
     *       (ID INTEGER, NAME VARCHAR) result; {@code SELECT <n> ROWS}, {@code SELECT EMPTY}, {@code SELECT TYPES}
     *       (one row with every value tag), {@code SELECT MULTI} (RS, UPDATE_COUNT 5, RS), {@code SELECT WARN},
     *       {@code SELECT * FROM MISSING} → ERROR 42S02. Batches honour fetchSize and maxRows; FETCH continues.</li>
     *   <li>{@code INSERT/UPDATE/DELETE/MERGE} → UPDATE_COUNT 1 (+ GENERATED_KEYS when requested, + warning when
     *       the SQL contains WARN); DDL → UPDATE_COUNT 0; {@code KILL} → fatal ERROR 08006 and socket close;
     *       {@code SLEEP n} → waits n ms then UPDATE_COUNT 0.</li>
     *   <li>{@code {call …}} → OUT_PARAMS for every registered OUT parameter (INOUT values are transformed,
     *       cursor-typed ones become a streamed result item); SQL containing MIXED adds a regular result set
     *       before and an update count after the cursor item.</li>
     *   <li>EXECUTE_BATCH → BATCH_RESULT (1 per element; ERROR 42S02 when an element mentions MISSING).</li>
     *   <li>SET_* / COMMIT / ROLLBACK → OK; SET_SAVEPOINT → SAVEPOINT_SET; SET_CLIENT_INFO {@code fail} → ERROR.</li>
     *   <li>METADATA → a header + rows (getTables has realistic columns; others echo operation and args).</li>
     * </ul>
     */
    public final class EchoHandler implements Handler {

        private final Map<String, QueryResult> queries = new HashMap<>();
        public volatile Map<String, String> serverProperties = defaultServerProperties();
        public volatile String engine = HelloOk.ENGINE_H2;

        public record QueryResult(List<ColumnMeta> columns, List<List<Object>> rows) {
        }

        public void registerQuery(String sql, List<ColumnMeta> columns, List<List<Object>> rows) {
            queries.put(normalise(sql), new QueryResult(columns, rows));
        }

        private static String normalise(String sql) {
            return sql.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
        }

        public static Map<String, String> defaultServerProperties() {
            Map<String, String> p = new LinkedHashMap<>();
            p.put("databaseProductName", "H2");
            p.put("databaseProductVersion", "2.3.232 (2024-08-11)");
            p.put("databaseMajorVersion", "2");
            p.put("databaseMinorVersion", "3");
            p.put("driverName", "H2 JDBC Driver");
            p.put("driverVersion", "2.3.232");
            p.put("identifierQuoteString", "\"");
            p.put("catalogSeparator", ".");
            p.put("catalogTerm", "catalog");
            p.put("schemaTerm", "schema");
            p.put("procedureTerm", "procedure");
            p.put("searchStringEscape", "\\");
            p.put("sqlKeywords", "LIMIT,MINUS,OFFSET,ROWNUM,TOP");
            p.put("extraNameCharacters", "");
            p.put("storesUpperCaseIdentifiers", "true");
            p.put("storesLowerCaseIdentifiers", "false");
            p.put("storesMixedCaseIdentifiers", "false");
            p.put("supportsMixedCaseIdentifiers", "false");
            p.put("supportsSchemasInTableDefinitions", "true");
            p.put("supportsSchemasInDataManipulation", "true");
            p.put("supportsCatalogsInTableDefinitions", "true");
            p.put("supportsCatalogsInDataManipulation", "true");
            p.put("supportsTransactions", "true");
            p.put("supportsSavepoints", "true");
            p.put("supportsBatchUpdates", "true");
            p.put("supportsGetGeneratedKeys", "true");
            p.put("supportsStoredProcedures", "true");
            p.put("supportsNamedParameters", "false");
            p.put("supportsMultipleResultSets", "false");
            p.put("supportsOuterJoins", "true");
            p.put("supportsUnion", "true");
            p.put("supportsUnionAll", "true");
            p.put("defaultTransactionIsolation", "2");
            p.put("maxStatementLength", "0");
            p.put("maxConnections", "0");
            p.put("nullsAreSortedHigh", "false");
            p.put("nullsAreSortedLow", "true");
            p.put("nullPlusNonNullIsNull", "true");
            p.put("isReadOnly", "false");
            p.put("userName", "SA");
            p.put("url", "jdbc:dbp://gateway.example:7420/sales");
            p.put("poolMode", "TRANSACTION");
            return p;
        }

        @Override
        public void handle(Message request, Session s) throws IOException {
            switch (request) {
                case Hello h -> hello(h, s);
                case Ping p -> s.reply(Pong.INSTANCE);
                case Close c -> {
                    s.reply(Ok.INSTANCE);
                    s.closeAfterReply();
                }
                case Prepare p -> {
                    int id = s.newStatementId();
                    s.statements.put(id, p.sql());
                    s.reply(new Prepared(id, countParams(p.sql())));
                }
                case Execute e -> execute(e, s);
                case Fetch f -> fetch(f, s);
                case CloseCursor c -> {
                    s.cursors.remove(c.cursorId());
                    s.reply(Ok.INSTANCE);
                }
                case CloseStatement c -> {
                    s.statements.remove(c.statementId());
                    s.reply(Ok.INSTANCE);
                }
                case ExecuteBatch b -> batch(b, s);
                case SetSavepoint sp -> {
                    String name = sp.name() != null ? sp.name() : "SP_" + (s.savepoints.size() + 1);
                    s.savepoints.add(name);
                    s.reply(new SavepointSet(name));
                }
                case SetClientInfo ci -> {
                    if ("fail".equals(ci.name())) {
                        s.reply(ErrorMessage.of("HY000", "client info '" + ci.name() + "' rejected"));
                    } else {
                        s.clientInfo.put(ci.name(), ci.value());
                        s.reply(Ok.INSTANCE);
                    }
                }
                case Metadata m -> metadata(m, s);
                default -> s.reply(Ok.INSTANCE);
            }
        }

        private void hello(Hello h, Session s) throws IOException {
            s.helloProperties.putAll(h.properties());
            if (h.protocolVersion() != ProtocolConstants.VERSION) {
                s.reply(ErrorMessage.fatal("08004", "unsupported protocol version"));
                s.closeAfterReply();
                return;
            }
            if ("bad-key".equals(h.properties().get(Hello.PROP_API_KEY))) {
                s.reply(ErrorMessage.fatal("08004", "application not authorised for datasource "
                        + h.properties().get(Hello.PROP_DATASOURCE) + ": invalid api key"));
                s.closeAfterReply();
                return;
            }
            s.autoCommit = !"false".equals(h.properties().get(Hello.PROP_AUTOCOMMIT));
            s.reply(new HelloOk("sess-" + sessionCounter.get(), "0.1.0-test", engine, serverProperties));
        }

        private static int countParams(String sql) {
            int n = 0;
            for (int i = 0; i < sql.length(); i++) {
                if (sql.charAt(i) == '?') {
                    n++;
                }
            }
            return n;
        }

        // ---------------------------------------------------------------- EXECUTE

        private static final Pattern N_ROWS = Pattern.compile("SELECT (\\d+) ROWS");
        private static final Pattern SLEEP = Pattern.compile("SLEEP (\\d+)");

        private void execute(Execute e, Session s) throws IOException {
            String sql;
            if (e.isDirect()) {
                sql = e.sql();
            } else {
                sql = s.statements.get(e.statementId());
                if (sql == null) {
                    s.reply(ErrorMessage.of("HY000", "unknown statement id " + e.statementId()));
                    return;
                }
            }
            String norm = normalise(sql);
            Execute.ExecOptions o = e.options();
            boolean isQuery = norm.startsWith("SELECT") || norm.startsWith("WITH");
            boolean isCall = norm.startsWith("{CALL") || norm.startsWith("CALL") || norm.startsWith("{? = CALL")
                    || norm.startsWith("{?= CALL");

            if (norm.equals("KILL")) {
                s.reply(ErrorMessage.fatal("08006", "session killed"));
                s.closeAfterReply();
                return;
            }
            Matcher sleep = SLEEP.matcher(norm);
            if (sleep.matches()) {
                try {
                    Thread.sleep(Long.parseLong(sleep.group(1)));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                s.reply(new UpdateCount(0), ExecuteDone.NO_WARNINGS);
                return;
            }
            if (norm.equals("SELECT * FROM MISSING")) {
                s.reply(new ErrorMessage("42S02", 42102, "Table \"MISSING\" not found", false));
                return;
            }
            if (o.expect() == Execute.Expect.QUERY && !isQuery && !isCall) {
                s.reply(ErrorMessage.of("07005", "statement did not produce a result set"));
                return;
            }
            if (o.expect() == Execute.Expect.UPDATE && isQuery) {
                s.reply(ErrorMessage.of("07005", "statement produced a result set"));
                return;
            }
            if (e.kind() == StatementKind.CALLABLE || isCall) {
                call(e, norm, s);
                return;
            }
            if (isQuery) {
                if (norm.equals("SELECT MULTI")) {
                    sendResultSet(s, defaultColumns(), defaultRows(1), o);
                    s.reply(new UpdateCount(5));
                    sendResultSet(s, defaultColumns(), defaultRows(2), o);
                    s.reply(ExecuteDone.NO_WARNINGS);
                    return;
                }
                QueryResult q = queries.get(norm);
                List<ColumnMeta> columns;
                List<List<Object>> rows;
                Matcher m = N_ROWS.matcher(norm);
                if (q != null) {
                    columns = q.columns();
                    rows = q.rows();
                } else if (m.matches()) {
                    columns = defaultColumns();
                    rows = defaultRows(Integer.parseInt(m.group(1)));
                } else if (norm.equals("SELECT EMPTY")) {
                    columns = defaultColumns();
                    rows = List.of();
                } else if (norm.equals("SELECT TYPES")) {
                    columns = typesColumns();
                    rows = List.of(typesRow());
                } else if (norm.startsWith("SELECT PARAMS")) {
                    // echo the parameters back as one row of STRING descriptions
                    List<ColumnMeta> cols = new ArrayList<>();
                    List<Object> row = new ArrayList<>();
                    for (int i = 0; i < e.params().size(); i++) {
                        cols.add(ColumnMeta.simple("P" + (i + 1), Types.VARCHAR, "VARCHAR"));
                        row.add(describe(e.params().get(i)));
                    }
                    columns = cols;
                    rows = List.of(row);
                } else {
                    columns = defaultColumns();
                    rows = defaultRows(3);
                }
                sendResultSet(s, columns, rows, o);
                List<Warning> warnings = norm.contains("WARN")
                        ? List.of(new Warning("01000", 7, "query warning")) : List.of();
                s.reply(new ExecuteDone(warnings));
                return;
            }
            // updates and DDL
            boolean dml = norm.startsWith("INSERT") || norm.startsWith("UPDATE") || norm.startsWith("DELETE")
                    || norm.startsWith("MERGE");
            s.reply(new UpdateCount(dml ? 1 : 0));
            boolean wantKeys = o.autoGeneratedKeys() == java.sql.Statement.RETURN_GENERATED_KEYS
                    || !o.generatedKeyColumns().isEmpty();
            if (norm.startsWith("INSERT") && wantKeys) {
                String col = o.generatedKeyColumns().isEmpty() ? "ID" : o.generatedKeyColumns().get(0);
                s.reply(new GeneratedKeys(List.of(ColumnMeta.simple(col, Types.BIGINT, "BIGINT")),
                        List.of(List.of(101L))));
            }
            List<Warning> warnings = norm.contains("WARN") ? List.of(new Warning("01000", 8, "update warning")) : List.of();
            s.reply(new ExecuteDone(warnings));
        }

        private void sendResultSet(Session s, List<ColumnMeta> columns, List<List<Object>> rows,
                                   Execute.ExecOptions o) throws IOException {
            List<List<Object>> limited = rows;
            if (o.maxRows() > 0 && rows.size() > o.maxRows()) {
                limited = rows.subList(0, o.maxRows());
            }
            Cursor c = new Cursor(s.newCursorId(), columns, limited);
            Rows first = c.take(o.fetchSize());
            if (!first.last()) {
                s.cursors.put(c.id, c);
            }
            s.reply(new ResultSetHeader(c.id, columns), first);
        }

        private void call(Execute e, String norm, Session s) throws IOException {
            Execute.ExecOptions o = e.options();
            boolean mixed = norm.contains("MIXED");
            if (mixed) {
                sendResultSet(s, defaultColumns(), defaultRows(1), o);
            }
            List<OutParams.Entry> out = new ArrayList<>();
            for (Execute.OutParam p : e.outParams()) {
                Object in = p.index() - 1 < e.params().size() ? e.params().get(p.index() - 1) : null;
                Object value;
                if (DbpCallableStatement.isCursorType(p.jdbcType())) {
                    Cursor c = new Cursor(s.newCursorId(), defaultColumns(), defaultRows(2));
                    Rows first = c.take(o.fetchSize());
                    if (!first.last()) {
                        s.cursors.put(c.id, c);
                    }
                    s.reply(new ResultSetHeader(c.id, c.columns), first);
                    value = c.id;
                } else if (in != null && !(in instanceof org.dbplatform.protocol.TypedNull)) {
                    value = switch (in) {
                        case Integer i -> i + 1;
                        case Long l -> l + 1;
                        case String str -> str.toUpperCase(Locale.ROOT);
                        case BigDecimal bd -> bd.multiply(BigDecimal.valueOf(2));
                        default -> in;
                    };
                } else {
                    value = switch (p.jdbcType()) {
                        case Types.INTEGER -> 42;
                        case Types.BIGINT -> 42L;
                        case Types.SMALLINT -> (short) 7;
                        case Types.VARCHAR, Types.CHAR -> "out-" + p.index();
                        case Types.NUMERIC, Types.DECIMAL -> new BigDecimal("12.50");
                        case Types.DOUBLE, Types.FLOAT -> 2.5d;
                        case Types.BOOLEAN -> Boolean.TRUE;
                        case Types.DATE -> LocalDate.of(2024, 1, 15);
                        case Types.TIMESTAMP -> LocalDateTime.of(2024, 1, 15, 10, 20, 30);
                        case Types.NULL -> null;
                        default -> null;
                    };
                }
                out.add(new OutParams.Entry(p.index(), value));
            }
            if (mixed) {
                s.reply(new UpdateCount(3));
            }
            s.reply(new OutParams(out), ExecuteDone.NO_WARNINGS);
        }

        private void fetch(Fetch f, Session s) throws IOException {
            Cursor c = s.cursors.get(f.cursorId());
            if (c == null) {
                s.reply(ErrorMessage.of("HY000", "unknown cursor " + f.cursorId()));
                return;
            }
            Rows rows = c.take(f.maxRows());
            if (rows.last()) {
                s.cursors.remove(c.id);
            }
            s.reply(rows);
        }

        private void batch(ExecuteBatch b, Session s) throws IOException {
            if (b.statementId() == ProtocolConstants.DIRECT_STATEMENT_ID && b.sql() == null) {
                long[] counts = new long[b.sqls().size()];
                for (int i = 0; i < counts.length; i++) {
                    if (normalise(b.sqls().get(i)).contains("MISSING")) {
                        s.reply(new ErrorMessage("42S02", 42102, "Table \"MISSING\" not found", false));
                        return;
                    }
                    counts[i] = 1;
                }
                s.reply(new BatchResult(counts, List.of()));
                return;
            }
            String sql = b.sql() != null ? b.sql() : s.statements.get(b.statementId());
            if (sql == null) {
                s.reply(ErrorMessage.of("HY000", "unknown statement id " + b.statementId()));
                return;
            }
            if (normalise(sql).contains("MISSING")) {
                s.reply(new ErrorMessage("42S02", 42102, "Table \"MISSING\" not found", false));
                return;
            }
            long[] counts = new long[b.paramSets().size()];
            java.util.Arrays.fill(counts, 1L);
            List<Warning> warnings = normalise(sql).contains("WARN")
                    ? List.of(new Warning("01000", 9, "batch warning")) : List.of();
            s.reply(new BatchResult(counts, warnings));
        }

        private void metadata(Metadata m, Session s) throws IOException {
            List<ColumnMeta> columns;
            List<List<Object>> rows;
            if (m.operation().equals("getTables")) {
                columns = List.of(
                        ColumnMeta.simple("TABLE_CAT", Types.VARCHAR, "VARCHAR"),
                        ColumnMeta.simple("TABLE_SCHEM", Types.VARCHAR, "VARCHAR"),
                        ColumnMeta.simple("TABLE_NAME", Types.VARCHAR, "VARCHAR"),
                        ColumnMeta.simple("TABLE_TYPE", Types.VARCHAR, "VARCHAR"),
                        ColumnMeta.simple("REMARKS", Types.VARCHAR, "VARCHAR"));
                rows = new ArrayList<>();
                rows.add(java.util.Arrays.asList("SALES", "PUBLIC", "CUSTOMERS", "TABLE", null));
                rows.add(java.util.Arrays.asList("SALES", "PUBLIC", "ORDERS", "TABLE", null));
            } else {
                columns = List.of(
                        ColumnMeta.simple("OPERATION", Types.VARCHAR, "VARCHAR"),
                        ColumnMeta.simple("ARGS", Types.VARCHAR, "VARCHAR"));
                List<String> described = new ArrayList<>();
                for (Object a : m.args()) {
                    described.add(describe(a));
                }
                rows = List.of(List.of(m.operation(), String.join("|", described)));
            }
            Cursor c = new Cursor(s.newCursorId(), columns, rows);
            Rows first = c.take(ProtocolConstants.DEFAULT_FETCH_SIZE);
            s.reply(new ResultSetHeader(c.id, columns), first, ExecuteDone.NO_WARNINGS);
        }

        // ---------------------------------------------------------------- data

        public static List<ColumnMeta> defaultColumns() {
            return List.of(
                    new ColumnMeta("ID", "ID", Types.INTEGER, "INTEGER", 10, 0, ColumnMeta.NO_NULLS, "java.lang.Integer",
                            "CUSTOMERS", "PUBLIC", "SALES", true, true, false, false, false, true, 11),
                    new ColumnMeta("NAME", "CUSTOMER_NAME", Types.VARCHAR, "CHARACTER VARYING", 100, 0, ColumnMeta.NULLABLE,
                            "java.lang.String", "CUSTOMERS", "PUBLIC", "SALES", false, false, true, false, false, true, 100));
        }

        public static List<List<Object>> defaultRows(int n) {
            String[] names = {"alice", "bob", "carol", "dave", "erin", "frank", "grace", "heidi", "ivan", "judy"};
            List<List<Object>> rows = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                rows.add(List.of(i, names[(i - 1) % names.length] + (i > names.length ? "-" + i : "")));
            }
            return rows;
        }

        public static List<ColumnMeta> typesColumns() {
            return List.of(
                    ColumnMeta.simple("C_BOOL", Types.BOOLEAN, "BOOLEAN"),
                    ColumnMeta.simple("C_BYTE", Types.TINYINT, "TINYINT"),
                    ColumnMeta.simple("C_SHORT", Types.SMALLINT, "SMALLINT"),
                    ColumnMeta.simple("C_INT", Types.INTEGER, "INTEGER"),
                    ColumnMeta.simple("C_LONG", Types.BIGINT, "BIGINT"),
                    ColumnMeta.simple("C_FLOAT", Types.REAL, "REAL"),
                    ColumnMeta.simple("C_DOUBLE", Types.DOUBLE, "DOUBLE PRECISION"),
                    ColumnMeta.simple("C_DEC", Types.NUMERIC, "NUMERIC"),
                    ColumnMeta.simple("C_STR", Types.VARCHAR, "VARCHAR"),
                    ColumnMeta.simple("C_BYTES", Types.VARBINARY, "VARBINARY"),
                    ColumnMeta.simple("C_DATE", Types.DATE, "DATE"),
                    ColumnMeta.simple("C_TIME", Types.TIME, "TIME"),
                    ColumnMeta.simple("C_TS", Types.TIMESTAMP, "TIMESTAMP"),
                    ColumnMeta.simple("C_TSTZ", Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP WITH TIME ZONE"),
                    ColumnMeta.simple("C_TIMETZ", Types.TIME_WITH_TIMEZONE, "TIME WITH TIME ZONE"),
                    ColumnMeta.simple("C_NULL", Types.INTEGER, "INTEGER"),
                    ColumnMeta.simple("C_NUMSTR", Types.VARCHAR, "VARCHAR"),
                    ColumnMeta.simple("C_BOOLSTR", Types.VARCHAR, "VARCHAR"),
                    ColumnMeta.simple("C_DATESTR", Types.VARCHAR, "VARCHAR"),
                    ColumnMeta.simple("C_TSSTR", Types.VARCHAR, "VARCHAR"),
                    ColumnMeta.simple("C_UUID", Types.VARCHAR, "UUID"),
                    ColumnMeta.simple("C_BIGDEC", Types.NUMERIC, "NUMERIC"));
        }

        public static List<Object> typesRow() {
            List<Object> row = new ArrayList<>();
            row.add(Boolean.TRUE);
            row.add((byte) 7);
            row.add((short) 300);
            row.add(42);
            row.add(9_000_000_000L);
            row.add(1.5f);
            row.add(2.25d);
            row.add(new BigDecimal("12.50"));
            row.add("hello");
            row.add(new byte[] {1, 2, 3});
            row.add(LocalDate.of(2024, 1, 15));
            row.add(LocalTime.of(10, 20, 30));
            row.add(LocalDateTime.of(2024, 1, 15, 10, 20, 30, 500_000_000));
            row.add(OffsetDateTime.of(2024, 1, 15, 10, 20, 30, 0, ZoneOffset.ofHours(2)));
            row.add(OffsetTime.of(10, 20, 30, 0, ZoneOffset.ofHours(2)));
            row.add(null);
            row.add("123.45");
            row.add("true");
            row.add("2024-01-15");
            row.add("2024-01-15 10:20:30.5");
            row.add("123e4567-e89b-12d3-a456-426614174000");
            row.add(new BigDecimal("99999999999999999999"));
            return row;
        }

        /** Describes a decoded parameter value as {@code <Type>:<text>} for assertions. */
        public static String describe(Object v) {
            if (v == null) {
                return "NULL";
            }
            if (v instanceof org.dbplatform.protocol.TypedNull tn) {
                return "TypedNull:" + tn.jdbcType();
            }
            if (v instanceof byte[] b) {
                return "byte[]:" + java.util.HexFormat.of().formatHex(b);
            }
            return v.getClass().getSimpleName() + ":" + v;
        }
    }
}
