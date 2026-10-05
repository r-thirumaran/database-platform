package org.dbplatform.jdbc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * In-memory {@link Blob} over a {@code byte[]} (the wire protocol materialises BLOB values as BYTES).
 */
public final class DbpBlob implements Blob {

    private byte[] data;
    private boolean freed;

    /**
     * Creates a blob holding a copy of {@code data}.
     *
     * @param data the bytes, {@code null} = empty
     */
    public DbpBlob(byte[] data) {
        this.data = data == null ? new byte[0] : data.clone();
    }

    private void check() throws SQLException {
        if (freed) {
            throw new SQLException("Blob has been freed", "HY000");
        }
    }

    private int offset(long pos, String what) throws SQLException {
        if (pos < 1 || pos - 1 > data.length) {
            throw DbpSqlExceptions.invalidArgument("invalid " + what + " position " + pos + " for a Blob of length " + data.length);
        }
        return (int) (pos - 1);
    }

    @Override
    public long length() throws SQLException {
        check();
        return data.length;
    }

    @Override
    public byte[] getBytes(long pos, int length) throws SQLException {
        check();
        int off = offset(pos, "start");
        if (length < 0) {
            throw DbpSqlExceptions.invalidArgument("negative length " + length);
        }
        int end = (int) Math.min((long) off + length, data.length);
        return Arrays.copyOfRange(data, off, end);
    }

    @Override
    public InputStream getBinaryStream() throws SQLException {
        check();
        return new ByteArrayInputStream(data);
    }

    @Override
    public InputStream getBinaryStream(long pos, long length) throws SQLException {
        check();
        int off = offset(pos, "start");
        if (length < 0 || off + length > data.length) {
            throw DbpSqlExceptions.invalidArgument("invalid length " + length);
        }
        return new ByteArrayInputStream(data, off, (int) length);
    }

    @Override
    public long position(byte[] pattern, long start) throws SQLException {
        check();
        if (pattern == null) {
            return -1;
        }
        int from = offset(start, "start");
        outer:
        for (int i = from; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i + 1L;
        }
        return -1;
    }

    @Override
    public long position(Blob pattern, long start) throws SQLException {
        return position(pattern == null ? null : pattern.getBytes(1, (int) pattern.length()), start);
    }

    @Override
    public int setBytes(long pos, byte[] bytes) throws SQLException {
        return setBytes(pos, bytes, 0, bytes.length);
    }

    @Override
    public int setBytes(long pos, byte[] bytes, int offset, int len) throws SQLException {
        check();
        int off = offset(pos, "write");
        int needed = off + len;
        if (needed > data.length) {
            data = Arrays.copyOf(data, needed);
        }
        System.arraycopy(bytes, offset, data, off, len);
        return len;
    }

    @Override
    public OutputStream setBinaryStream(long pos) throws SQLException {
        check();
        int off = offset(pos, "write");
        return new ByteArrayOutputStream() {
            @Override
            public void close() {
                byte[] written = toByteArray();
                int needed = off + written.length;
                if (needed > data.length) {
                    data = Arrays.copyOf(data, needed);
                }
                System.arraycopy(written, 0, data, off, written.length);
            }
        };
    }

    @Override
    public void truncate(long len) throws SQLException {
        check();
        if (len < 0 || len > data.length) {
            throw DbpSqlExceptions.invalidArgument("invalid truncate length " + len);
        }
        data = Arrays.copyOf(data, (int) len);
    }

    @Override
    public void free() {
        freed = true;
        data = new byte[0];
    }

    @Override
    public String toString() {
        return "DbpBlob[" + data.length + " bytes" + (freed ? ", freed" : "") + "]";
    }
}
