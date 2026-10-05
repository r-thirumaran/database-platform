package org.dbplatform.gateway;

import org.dbplatform.protocol.FrameReader;
import org.dbplatform.protocol.FrameWriter;
import org.dbplatform.protocol.Warning;
import org.dbplatform.protocol.messages.BatchResult;
import org.dbplatform.protocol.messages.Close;
import org.dbplatform.protocol.messages.CloseCursor;
import org.dbplatform.protocol.messages.CloseStatement;
import org.dbplatform.protocol.messages.Commit;
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
import org.dbplatform.protocol.messages.Rollback;
import org.dbplatform.protocol.messages.Rows;
import org.dbplatform.protocol.messages.SavepointSet;
import org.dbplatform.protocol.messages.SetAutoCommit;
import org.dbplatform.protocol.messages.SetSavepoint;
import org.dbplatform.protocol.messages.StatementKind;
import org.dbplatform.protocol.messages.UpdateCount;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Minimal protocol client for the tests (Socket + FrameReader/FrameWriter + Messages). Deliberately not the driver.
 */
public final class TestClient implements AutoCloseable {

    /** ERROR frame received where a success frame was expected. */
    public static final class SqlError extends RuntimeException {
        public final ErrorMessage error;

        SqlError(ErrorMessage e) {
            super(e.sqlState() + " " + e.message());
            this.error = e;
        }

        public String sqlState() {
            return error.sqlState();
        }

        public boolean fatal() {
            return error.fatal();
        }
    }

    public sealed interface Item permits ResultItem, UpdateItem {
    }

    public record ResultItem(ResultSetHeader header, List<List<Object>> rows, boolean last) implements Item {
        public int cursorId() {
            return header.cursorId();
        }

        public Object cell(int row, int col) {
            return rows.get(row).get(col);
        }

        public List<String> labels() {
            return header.columns().stream().map(c -> c.label()).toList();
        }
    }

    public record UpdateItem(long count) implements Item {
    }

    public record ExecResult(List<Item> items, OutParams outParams, GeneratedKeys generatedKeys, List<Warning> warnings) {
        public ResultItem result() {
            for (Item i : items) {
                if (i instanceof ResultItem r) {
                    return r;
                }
            }
            throw new AssertionError("no result set in " + items);
        }

        public List<List<Object>> rows() {
            return result().rows();
        }

        public Object scalar() {
            return result().cell(0, 0);
        }

        public long updateCount() {
            for (Item i : items) {
                if (i instanceof UpdateItem u) {
                    return u.count();
                }
            }
            throw new AssertionError("no update count in " + items);
        }

        public List<ResultItem> results() {
            List<ResultItem> out = new ArrayList<>();
            for (Item i : items) {
                if (i instanceof ResultItem r) {
                    out.add(r);
                }
            }
            return out;
        }
    }

    private final Socket socket;
    private final FrameReader in;
    private final FrameWriter out;
    private HelloOk helloOk;

    private TestClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new FrameReader(socket.getInputStream());
        this.out = new FrameWriter(socket.getOutputStream());
    }

    public static TestClient connect(int port) {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            s.setSoTimeout(60_000);
            return new TestClient(s);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** connect + HELLO in one go. */
    public static TestClient open(int port, Map<String, String> props) {
        TestClient c = connect(port);
        c.hello(props);
        return c;
    }

    public static TestClient open(int port, String datasource) {
        return open(port, Map.of(Hello.PROP_DATASOURCE, datasource));
    }

    public HelloOk helloOk() {
        return helloOk;
    }

    // ------------------------------------------------------------------ raw exchange

    public Message send(Message m) {
        try {
            Messages.write(out, m);
            return Messages.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Message read(int columnCount) {
        try {
            return Messages.read(in, columnCount);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public HelloOk hello(Map<String, String> props) {
        Message m = send(Hello.of("dbp-test", "0", props));
        if (m instanceof ErrorMessage e) {
            throw new SqlError(e);
        }
        helloOk = (HelloOk) m;
        return helloOk;
    }

    /** Sends HELLO expecting an ERROR. */
    public ErrorMessage helloError(Map<String, String> props) {
        Message m = send(Hello.of("dbp-test", "0", props));
        if (m instanceof ErrorMessage e) {
            return e;
        }
        throw new AssertionError("expected ERROR but got " + m);
    }

    public void ok(Message m) {
        Message r = send(m);
        if (r instanceof ErrorMessage e) {
            throw new SqlError(e);
        }
        if (!(r instanceof Ok)) {
            throw new AssertionError("expected OK but got " + r);
        }
    }

    public ErrorMessage expectError(Message m) {
        Message r = send(m);
        if (r instanceof ErrorMessage e) {
            return e;
        }
        throw new AssertionError("expected ERROR but got " + r);
    }

    // ------------------------------------------------------------------ convenience

    public void ping() {
        Message r = send(new Ping());
        if (!(r instanceof Pong)) {
            throw new AssertionError("expected PONG but got " + r);
        }
    }

    public void autoCommit(boolean v) {
        ok(new SetAutoCommit(v));
    }

    public void commit() {
        ok(new Commit());
    }

    public void rollback() {
        ok(Rollback.FULL);
    }

    public void rollbackTo(String savepoint) {
        ok(new Rollback(savepoint));
    }

    public String savepoint(String name) {
        Message r = send(new SetSavepoint(name));
        if (r instanceof ErrorMessage e) {
            throw new SqlError(e);
        }
        return ((SavepointSet) r).name();
    }

    public int prepare(String sql, StatementKind kind) {
        return prepare(new Prepare(sql, kind, Statement.NO_GENERATED_KEYS, List.of()));
    }

    public int prepare(Prepare p) {
        Message r = send(p);
        if (r instanceof ErrorMessage e) {
            throw new SqlError(e);
        }
        return ((Prepared) r).statementId();
    }

    public void closeStatement(int id) {
        ok(new CloseStatement(id));
    }

    public void closeCursor(int id) {
        ok(new CloseCursor(id));
    }

    /** Direct statement execution with expect ANY. */
    public ExecResult exec(String sql) {
        return execute(Execute.direct(sql, StatementKind.STATEMENT, List.of(), Execute.ExecOptions.DEFAULT));
    }

    /** Direct prepared query (expect QUERY). */
    public ExecResult query(String sql, Object... params) {
        return execute(Execute.direct(sql, StatementKind.PREPARED, Arrays.asList(params),
                Execute.ExecOptions.DEFAULT.withExpect(Execute.Expect.QUERY)));
    }

    public ExecResult queryFetch(String sql, int fetchSize, Object... params) {
        return execute(Execute.direct(sql, StatementKind.PREPARED, Arrays.asList(params),
                new Execute.ExecOptions(0, fetchSize, 0, Execute.Expect.QUERY, Statement.NO_GENERATED_KEYS, List.of())));
    }

    /** Direct prepared update (expect UPDATE). */
    public ExecResult update(String sql, Object... params) {
        return execute(Execute.direct(sql, StatementKind.PREPARED, Arrays.asList(params),
                Execute.ExecOptions.DEFAULT.withExpect(Execute.Expect.UPDATE)));
    }

    public ExecResult executePrepared(int statementId, Execute.Expect expect, Object... params) {
        return execute(Execute.prepared(statementId, StatementKind.PREPARED, Arrays.asList(params),
                Execute.ExecOptions.DEFAULT.withExpect(expect)));
    }

    public ExecResult metadata(String op, Object... args) {
        try {
            Messages.write(out, Metadata.of(op, args));
            return collect();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public ExecResult execute(Execute e) {
        try {
            Messages.write(out, e);
            return collect();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private ExecResult collect() {
        List<Item> items = new ArrayList<>();
        OutParams outParams = null;
        GeneratedKeys keys = null;
        int columnCount = -1;
        ResultSetHeader pending = null;
        while (true) {
            Message m = read(columnCount);
            switch (m) {
                case ResultSetHeader h -> {
                    pending = h;
                    columnCount = h.columnCount();
                }
                case Rows r -> {
                    if (pending == null || pending.cursorId() != r.cursorId()) {
                        throw new AssertionError("ROWS without matching header: " + r);
                    }
                    items.add(new ResultItem(pending, r.rows(), r.last()));
                    pending = null;
                }
                case UpdateCount u -> items.add(new UpdateItem(u.count()));
                case OutParams o -> outParams = o;
                case GeneratedKeys g -> keys = g;
                case ExecuteDone d -> {
                    return new ExecResult(items, outParams, keys, d.warnings());
                }
                case ErrorMessage e -> throw new SqlError(e);
                default -> throw new AssertionError("unexpected " + m);
            }
        }
    }

    public Rows fetch(int cursorId, int n, int columnCount) {
        try {
            Messages.write(out, new Fetch(cursorId, n));
            Message m = read(columnCount);
            if (m instanceof ErrorMessage e) {
                throw new SqlError(e);
            }
            return (Rows) m;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Fetches every remaining row of a cursor. */
    public List<List<Object>> drain(ResultItem first, int batch) {
        List<List<Object>> all = new ArrayList<>(first.rows());
        boolean last = first.last();
        while (!last) {
            Rows r = fetch(first.cursorId(), batch, first.header().columnCount());
            all.addAll(r.rows());
            last = r.last();
        }
        return all;
    }

    public BatchResult batch(ExecuteBatch b) {
        Message r = send(b);
        if (r instanceof ErrorMessage e) {
            throw new SqlError(e);
        }
        return (BatchResult) r;
    }

    /** Sends CLOSE, expects OK and closes the socket. */
    public void closeSession() {
        try {
            ok(new Close());
        } finally {
            closeSocket();
        }
    }

    public void closeSocket() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    public boolean isConnected() {
        return !socket.isClosed();
    }

    @Override
    public void close() {
        if (!socket.isClosed()) {
            try {
                closeSession();
            } catch (RuntimeException ignored) {
                closeSocket();
            }
        }
    }
}
