package org.dbplatform.jdbc;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.Clob;
import java.sql.NClob;
import java.sql.SQLException;

/**
 * In-memory {@link Clob} / {@link NClob} over a {@link String} (the wire protocol materialises CLOB values
 * as STRING).
 */
public final class DbpClob implements NClob {

    private StringBuilder data;
    private boolean freed;

    /**
     * Creates a clob holding {@code text}.
     *
     * @param text the characters, {@code null} = empty
     */
    public DbpClob(String text) {
        this.data = new StringBuilder(text == null ? "" : text);
    }

    private void check() throws SQLException {
        if (freed) {
            throw new SQLException("Clob has been freed", "HY000");
        }
    }

    private int offset(long pos, String what) throws SQLException {
        if (pos < 1 || pos - 1 > data.length()) {
            throw DbpSqlExceptions.invalidArgument("invalid " + what + " position " + pos + " for a Clob of length " + data.length());
        }
        return (int) (pos - 1);
    }

    @Override
    public long length() throws SQLException {
        check();
        return data.length();
    }

    @Override
    public String getSubString(long pos, int length) throws SQLException {
        check();
        int off = offset(pos, "start");
        if (length < 0) {
            throw DbpSqlExceptions.invalidArgument("negative length " + length);
        }
        int end = (int) Math.min((long) off + length, data.length());
        return data.substring(off, end);
    }

    @Override
    public Reader getCharacterStream() throws SQLException {
        check();
        return new StringReader(data.toString());
    }

    @Override
    public Reader getCharacterStream(long pos, long length) throws SQLException {
        check();
        int off = offset(pos, "start");
        if (length < 0 || off + length > data.length()) {
            throw DbpSqlExceptions.invalidArgument("invalid length " + length);
        }
        return new StringReader(data.substring(off, (int) (off + length)));
    }

    @Override
    public InputStream getAsciiStream() throws SQLException {
        check();
        return new ByteArrayInputStream(data.toString().getBytes(StandardCharsets.US_ASCII));
    }

    @Override
    public long position(String searchstr, long start) throws SQLException {
        check();
        if (searchstr == null) {
            return -1;
        }
        int i = data.indexOf(searchstr, offset(start, "start"));
        return i < 0 ? -1 : i + 1L;
    }

    @Override
    public long position(Clob searchstr, long start) throws SQLException {
        return position(searchstr == null ? null : searchstr.getSubString(1, (int) searchstr.length()), start);
    }

    @Override
    public int setString(long pos, String str) throws SQLException {
        return setString(pos, str, 0, str.length());
    }

    @Override
    public int setString(long pos, String str, int offset, int len) throws SQLException {
        check();
        int off = offset(pos, "write");
        String part = str.substring(offset, offset + len);
        int end = Math.min(off + len, data.length());
        data.replace(off, end, part);
        return len;
    }

    @Override
    public OutputStream setAsciiStream(long pos) throws SQLException {
        check();
        int off = offset(pos, "write");
        return new java.io.ByteArrayOutputStream() {
            @Override
            public void close() {
                String s = toString(StandardCharsets.US_ASCII);
                int end = Math.min(off + s.length(), data.length());
                data.replace(off, end, s);
            }
        };
    }

    @Override
    public Writer setCharacterStream(long pos) throws SQLException {
        check();
        int off = offset(pos, "write");
        return new StringWriter() {
            @Override
            public void close() {
                String s = toString();
                int end = Math.min(off + s.length(), data.length());
                data.replace(off, end, s);
            }
        };
    }

    @Override
    public void truncate(long len) throws SQLException {
        check();
        if (len < 0 || len > data.length()) {
            throw DbpSqlExceptions.invalidArgument("invalid truncate length " + len);
        }
        data.setLength((int) len);
    }

    @Override
    public void free() {
        freed = true;
        data = new StringBuilder();
    }

    @Override
    public String toString() {
        return "DbpClob[" + data.length() + " chars" + (freed ? ", freed" : "") + "]";
    }
}
