package org.dbplatform.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounds-checked big-endian reader over a payload byte array with the typed readers of the DBP wire
 * protocol. Every reader throws {@link ProtocolException} (never {@code IndexOutOfBoundsException})
 * when the payload is truncated or a length or count is malformed.
 *
 * <p>Instances are not thread-safe.</p>
 */
public final class ProtocolInput {

    private final byte[] buf;
    private final int end;
    private int pos;

    /**
     * Creates a reader over the whole array.
     *
     * @param data payload bytes
     */
    public ProtocolInput(byte[] data) {
        this(data, 0, data.length);
    }

    /**
     * Creates a reader over a slice of an array.
     *
     * @param data   backing array
     * @param offset first byte to read
     * @param length number of readable bytes
     */
    public ProtocolInput(byte[] data, int offset, int length) {
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        if (offset < 0 || length < 0 || offset + length > data.length) {
            throw new IndexOutOfBoundsException("offset=" + offset + " length=" + length + " array=" + data.length);
        }
        this.buf = data;
        this.pos = offset;
        this.end = offset + length;
    }

    // ---------------------------------------------------------------- position

    /**
     * Returns the number of unread bytes.
     *
     * @return remaining bytes
     */
    public int remaining() {
        return end - pos;
    }

    /**
     * Returns whether unread bytes remain.
     *
     * @return {@code true} if at least one byte is unread
     */
    public boolean hasRemaining() {
        return pos < end;
    }

    /**
     * Returns the current read position relative to the backing array.
     *
     * @return absolute position
     */
    public int position() {
        return pos;
    }

    /**
     * Fails unless the whole payload has been consumed. Readers must not rely on trailing bytes, but a
     * message decoder may use this to detect obviously malformed frames.
     *
     * @throws ProtocolException if unread bytes remain
     */
    public void expectEnd() throws ProtocolException {
        if (pos != end) {
            throw new ProtocolException((end - pos) + " unexpected trailing byte(s) in payload");
        }
    }

    // ---------------------------------------------------------------- primitives

    /**
     * Reads a {@code u8}.
     *
     * @return value {@code 0..255}
     * @throws ProtocolException if truncated
     */
    public int readU8() throws ProtocolException {
        need(1);
        return buf[pos++] & 0xFF;
    }

    /**
     * Reads an {@code i8}.
     *
     * @return value {@code -128..127}
     * @throws ProtocolException if truncated
     */
    public byte readI8() throws ProtocolException {
        need(1);
        return buf[pos++];
    }

    /**
     * Reads a {@code bool}. Only {@code 0} and {@code 1} are accepted.
     *
     * @return value
     * @throws ProtocolException if truncated or the byte is neither 0 nor 1
     */
    public boolean readBool() throws ProtocolException {
        int b = readU8();
        return switch (b) {
            case 0 -> false;
            case 1 -> true;
            default -> throw new ProtocolException("invalid bool byte " + b);
        };
    }

    /**
     * Reads an {@code i16}.
     *
     * @return value
     * @throws ProtocolException if truncated
     */
    public short readI16() throws ProtocolException {
        need(2);
        int v = ((buf[pos] & 0xFF) << 8) | (buf[pos + 1] & 0xFF);
        pos += 2;
        return (short) v;
    }

    /**
     * Reads an {@code i32}.
     *
     * @return value
     * @throws ProtocolException if truncated
     */
    public int readI32() throws ProtocolException {
        need(4);
        int v = ((buf[pos] & 0xFF) << 24)
                | ((buf[pos + 1] & 0xFF) << 16)
                | ((buf[pos + 2] & 0xFF) << 8)
                | (buf[pos + 3] & 0xFF);
        pos += 4;
        return v;
    }

    /**
     * Reads an {@code i64}.
     *
     * @return value
     * @throws ProtocolException if truncated
     */
    public long readI64() throws ProtocolException {
        need(8);
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (buf[pos + i] & 0xFFL);
        }
        pos += 8;
        return v;
    }

    /**
     * Reads an {@code f32}.
     *
     * @return value
     * @throws ProtocolException if truncated
     */
    public float readF32() throws ProtocolException {
        return Float.intBitsToFloat(readI32());
    }

    /**
     * Reads an {@code f64}.
     *
     * @return value
     * @throws ProtocolException if truncated
     */
    public double readF64() throws ProtocolException {
        return Double.longBitsToDouble(readI64());
    }

    // ---------------------------------------------------------------- composite primitives

    /**
     * Reads a nullable {@code string}.
     *
     * @return the string or {@code null}
     * @throws ProtocolException if truncated or the length is invalid
     */
    public String readString() throws ProtocolException {
        int len = readLength("string");
        if (len < 0) {
            return null;
        }
        String s = new String(buf, pos, len, StandardCharsets.UTF_8);
        pos += len;
        return s;
    }

    /**
     * Reads nullable {@code bytes}.
     *
     * @return a fresh array or {@code null}
     * @throws ProtocolException if truncated or the length is invalid
     */
    public byte[] readBytes() throws ProtocolException {
        int len = readLength("bytes");
        if (len < 0) {
            return null;
        }
        byte[] out = new byte[len];
        System.arraycopy(buf, pos, out, 0, len);
        pos += len;
        return out;
    }

    /**
     * Reads {@code n} raw bytes without a length prefix.
     *
     * @param n number of bytes
     * @return a fresh array
     * @throws ProtocolException if truncated
     */
    public byte[] readRaw(int n) throws ProtocolException {
        if (n < 0) {
            throw new ProtocolException("negative raw length " + n);
        }
        need(n);
        byte[] out = new byte[n];
        System.arraycopy(buf, pos, out, 0, n);
        pos += n;
        return out;
    }

    /**
     * Reads a {@code map} into an insertion-ordered map.
     *
     * @return mutable {@link LinkedHashMap}
     * @throws ProtocolException if truncated or the count is invalid
     */
    public Map<String, String> readMap() throws ProtocolException {
        int n = readCount("map", 8);
        Map<String, String> m = new LinkedHashMap<>(Math.max(16, Math.min(n, 1 << 16)));
        for (int i = 0; i < n; i++) {
            String k = readString();
            String v = readString();
            m.put(k, v);
        }
        return m;
    }

    /**
     * Reads a {@code string[]}.
     *
     * @return mutable list, elements may be {@code null}
     * @throws ProtocolException if truncated or the count is invalid
     */
    public List<String> readStringArray() throws ProtocolException {
        int n = readCount("string[]", 4);
        List<String> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            l.add(readString());
        }
        return l;
    }

    // ---------------------------------------------------------------- protocol level structures

    /**
     * Reads one tagged {@code Value} (see {@link Values#decode}).
     *
     * @return the decoded Java object or {@code null}
     * @throws ProtocolException if malformed
     */
    public Object readValue() throws ProtocolException {
        return Values.decode(this);
    }

    /**
     * Reads a {@code Value[]}.
     *
     * @return mutable list, elements may be {@code null}
     * @throws ProtocolException if malformed
     */
    public List<Object> readValues() throws ProtocolException {
        int n = readCount("Value[]", 1);
        List<Object> l = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            l.add(Values.decode(this));
        }
        return l;
    }

    /**
     * Reads {@code columnCount} cells of a row (no count prefix on the wire).
     *
     * @param columnCount number of cells
     * @return mutable list, elements may be {@code null}
     * @throws ProtocolException if malformed
     */
    public List<Object> readRow(int columnCount) throws ProtocolException {
        if (columnCount < 0) {
            throw new ProtocolException("negative column count " + columnCount);
        }
        if (columnCount > remaining()) {
            throw new ProtocolException("row with " + columnCount + " columns cannot fit in " + remaining() + " bytes");
        }
        List<Object> row = new ArrayList<>(columnCount);
        for (int i = 0; i < columnCount; i++) {
            row.add(Values.decode(this));
        }
        return row;
    }

    /**
     * Reads a {@code ColumnMeta} structure.
     *
     * @return column metadata
     * @throws ProtocolException if malformed
     */
    public ColumnMeta readColumnMeta() throws ProtocolException {
        return ColumnMeta.decode(this);
    }

    /**
     * Reads a {@code Warning} structure.
     *
     * @return warning
     * @throws ProtocolException if malformed
     */
    public Warning readWarning() throws ProtocolException {
        return Warning.decode(this);
    }

    /**
     * Reads an {@code i32 count} prefix and validates it against the remaining bytes, given the minimum
     * encoded size of one element.
     *
     * @param what            description used in error messages
     * @param minElementBytes minimum number of bytes one element occupies on the wire ({@code >= 1})
     * @return the count, {@code >= 0}
     * @throws ProtocolException if negative or impossible to satisfy
     */
    public int readCount(String what, int minElementBytes) throws ProtocolException {
        int n = readI32();
        if (n < 0) {
            throw new ProtocolException("negative " + what + " count " + n);
        }
        if ((long) n * minElementBytes > remaining()) {
            throw new ProtocolException(what + " count " + n + " exceeds remaining payload (" + remaining() + " bytes)");
        }
        return n;
    }

    private int readLength(String what) throws ProtocolException {
        int len = readI32();
        if (len < -1) {
            throw new ProtocolException("invalid " + what + " length " + len);
        }
        if (len > remaining()) {
            throw new ProtocolException(what + " length " + len + " exceeds remaining payload (" + remaining() + " bytes)");
        }
        return len;
    }

    private void need(int n) throws ProtocolException {
        if (end - pos < n) {
            throw new ProtocolException("truncated payload: need " + n + " byte(s), " + (end - pos) + " remaining");
        }
    }
}
