package org.dbplatform.protocol;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

/**
 * Growable big-endian byte buffer with the typed writers of the DBP wire protocol (section 1 and 2 of
 * the specification). Used to build a frame payload; obtain the bytes with {@link #toByteArray()}.
 *
 * <p>All writers return {@code this} so calls can be chained. Instances are not thread-safe.</p>
 */
public final class ProtocolOutput {

    private static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;

    private byte[] buf;
    private int count;

    /** Creates a buffer with a small default capacity. */
    public ProtocolOutput() {
        this(256);
    }

    /**
     * Creates a buffer with the given initial capacity.
     *
     * @param initialCapacity initial capacity in bytes, {@code >= 0}
     */
    public ProtocolOutput(int initialCapacity) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity < 0");
        }
        this.buf = new byte[Math.max(initialCapacity, 16)];
    }

    // ---------------------------------------------------------------- primitives

    /**
     * Writes one unsigned byte.
     *
     * @param v value, only the low 8 bits are used
     * @return this
     */
    public ProtocolOutput writeU8(int v) {
        ensure(1);
        buf[count++] = (byte) v;
        return this;
    }

    /**
     * Writes one signed byte.
     *
     * @param v value, only the low 8 bits are used
     * @return this
     */
    public ProtocolOutput writeI8(int v) {
        return writeU8(v);
    }

    /**
     * Writes a {@code bool} ({@code u8}, 0 or 1).
     *
     * @param v value
     * @return this
     */
    public ProtocolOutput writeBool(boolean v) {
        return writeU8(v ? 1 : 0);
    }

    /**
     * Writes an {@code i16}.
     *
     * @param v value, only the low 16 bits are used
     * @return this
     */
    public ProtocolOutput writeI16(int v) {
        ensure(2);
        buf[count++] = (byte) (v >>> 8);
        buf[count++] = (byte) v;
        return this;
    }

    /**
     * Writes an {@code i32}.
     *
     * @param v value
     * @return this
     */
    public ProtocolOutput writeI32(int v) {
        ensure(4);
        putI32(count, v);
        count += 4;
        return this;
    }

    /**
     * Writes an {@code i64}.
     *
     * @param v value
     * @return this
     */
    public ProtocolOutput writeI64(long v) {
        ensure(8);
        buf[count++] = (byte) (v >>> 56);
        buf[count++] = (byte) (v >>> 48);
        buf[count++] = (byte) (v >>> 40);
        buf[count++] = (byte) (v >>> 32);
        buf[count++] = (byte) (v >>> 24);
        buf[count++] = (byte) (v >>> 16);
        buf[count++] = (byte) (v >>> 8);
        buf[count++] = (byte) v;
        return this;
    }

    /**
     * Writes an {@code f32} ({@link Float#floatToIntBits}).
     *
     * @param v value
     * @return this
     */
    public ProtocolOutput writeF32(float v) {
        return writeI32(Float.floatToIntBits(v));
    }

    /**
     * Writes an {@code f64} ({@link Double#doubleToLongBits}).
     *
     * @param v value
     * @return this
     */
    public ProtocolOutput writeF64(double v) {
        return writeI64(Double.doubleToLongBits(v));
    }

    // ---------------------------------------------------------------- composite primitives

    /**
     * Writes a nullable {@code string} ({@code i32 byteLength} + UTF-8 bytes, {@code -1} = null).
     *
     * @param s value or {@code null}
     * @return this
     */
    public ProtocolOutput writeString(String s) {
        if (s == null) {
            return writeI32(-1);
        }
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeI32(b.length);
        return writeRaw(b, 0, b.length);
    }

    /**
     * Writes nullable {@code bytes} ({@code i32 length} + raw bytes, {@code -1} = null).
     *
     * @param b value or {@code null}
     * @return this
     */
    public ProtocolOutput writeBytes(byte[] b) {
        if (b == null) {
            return writeI32(-1);
        }
        writeI32(b.length);
        return writeRaw(b, 0, b.length);
    }

    /**
     * Appends raw bytes without any length prefix.
     *
     * @param b   source
     * @param off offset into {@code b}
     * @param len number of bytes
     * @return this
     */
    public ProtocolOutput writeRaw(byte[] b, int off, int len) {
        if (len == 0) {
            return this;
        }
        ensure(len);
        System.arraycopy(b, off, buf, count, len);
        count += len;
        return this;
    }

    /**
     * Appends raw bytes without any length prefix.
     *
     * @param b source
     * @return this
     */
    public ProtocolOutput writeRaw(byte[] b) {
        return writeRaw(b, 0, b.length);
    }

    /**
     * Writes a {@code map} ({@code i32 count} + pairs of {@code string}). The map itself must not be
     * {@code null}; {@code null} keys or values are encoded as null strings. Iteration order is preserved.
     *
     * @param map the entries
     * @return this
     */
    public ProtocolOutput writeMap(Map<String, String> map) {
        if (map == null) {
            throw new IllegalArgumentException("map must not be null (use an empty map)");
        }
        writeI32(map.size());
        for (Map.Entry<String, String> e : map.entrySet()) {
            writeString(e.getKey());
            writeString(e.getValue());
        }
        return this;
    }

    /**
     * Writes a {@code string[]} ({@code i32 count} + {@code count} × {@code string}).
     *
     * @param strings the elements, individual elements may be {@code null}; the collection must not be
     * @return this
     */
    public ProtocolOutput writeStringArray(Collection<String> strings) {
        if (strings == null) {
            throw new IllegalArgumentException("string array must not be null (use an empty list)");
        }
        writeI32(strings.size());
        for (String s : strings) {
            writeString(s);
        }
        return this;
    }

    /**
     * Writes a {@code string[]}.
     *
     * @param strings the elements
     * @return this
     */
    public ProtocolOutput writeStringArray(String... strings) {
        if (strings == null) {
            throw new IllegalArgumentException("string array must not be null (use an empty array)");
        }
        writeI32(strings.length);
        for (String s : strings) {
            writeString(s);
        }
        return this;
    }

    // ---------------------------------------------------------------- protocol level structures

    /**
     * Writes a single tagged {@code Value} chosen from the runtime type of {@code value} (see {@link Values#encode}).
     *
     * @param value Java object or {@code null}
     * @return this
     */
    public ProtocolOutput writeValue(Object value) {
        Values.encode(this, value);
        return this;
    }

    /**
     * Writes a {@code Value[]} ({@code i32 count} + {@code count} × {@code Value}).
     *
     * @param values the values (elements may be {@code null}); the collection must not be {@code null}
     * @return this
     */
    public ProtocolOutput writeValues(Collection<?> values) {
        if (values == null) {
            throw new IllegalArgumentException("values must not be null (use an empty list)");
        }
        writeI32(values.size());
        for (Object v : values) {
            Values.encode(this, v);
        }
        return this;
    }

    /**
     * Writes a {@code Value[]}.
     *
     * @param values the values
     * @return this
     */
    public ProtocolOutput writeValues(Object... values) {
        if (values == null) {
            throw new IllegalArgumentException("values must not be null (use an empty array)");
        }
        writeI32(values.length);
        for (Object v : values) {
            Values.encode(this, v);
        }
        return this;
    }

    /**
     * Writes the {@code columnCount × Value} cells of one row <em>without</em> a count prefix (ROWS and
     * GENERATED_KEYS payloads take the column count from the header).
     *
     * @param row the cells
     * @return this
     */
    public ProtocolOutput writeRow(Collection<?> row) {
        for (Object v : row) {
            Values.encode(this, v);
        }
        return this;
    }

    /**
     * Writes a {@code ColumnMeta} structure.
     *
     * @param meta column metadata
     * @return this
     */
    public ProtocolOutput writeColumnMeta(ColumnMeta meta) {
        meta.encode(this);
        return this;
    }

    /**
     * Writes a {@code Warning} structure.
     *
     * @param warning warning
     * @return this
     */
    public ProtocolOutput writeWarning(Warning warning) {
        warning.encode(this);
        return this;
    }

    // ---------------------------------------------------------------- buffer management

    /**
     * Returns the current number of bytes written.
     *
     * @return size in bytes
     */
    public int size() {
        return count;
    }

    /**
     * Reserves four bytes for an {@code i32} that will be filled in later with {@link #putI32At(int, int)}.
     *
     * @return the position of the reserved field
     */
    public int reserveI32() {
        int pos = count;
        writeI32(0);
        return pos;
    }

    /**
     * Overwrites an {@code i32} previously written or reserved at {@code position}.
     *
     * @param position byte position, must lie within the written bytes
     * @param v        value
     * @return this
     */
    public ProtocolOutput putI32At(int position, int v) {
        if (position < 0 || position + 4 > count) {
            throw new IndexOutOfBoundsException("position " + position + " outside written range 0.." + count);
        }
        putI32(position, v);
        return this;
    }

    /** Discards everything written so far, keeping the allocated capacity. */
    public void reset() {
        count = 0;
    }

    /**
     * Returns a copy of the bytes written so far.
     *
     * @return payload bytes
     */
    public byte[] toByteArray() {
        return Arrays.copyOf(buf, count);
    }

    /**
     * Writes the bytes written so far to a stream (no copy).
     *
     * @param out destination
     * @throws IOException from the stream
     */
    public void writeTo(OutputStream out) throws IOException {
        out.write(buf, 0, count);
    }

    /**
     * Wraps the written bytes into a frame of the given type.
     *
     * @param type message type
     * @return a frame carrying a copy of the bytes
     */
    public Frame toFrame(MessageType type) {
        return new Frame(type, toByteArray());
    }

    private void putI32(int pos, int v) {
        buf[pos] = (byte) (v >>> 24);
        buf[pos + 1] = (byte) (v >>> 16);
        buf[pos + 2] = (byte) (v >>> 8);
        buf[pos + 3] = (byte) v;
    }

    private void ensure(int additional) {
        int needed = count + additional;
        if (needed < 0 || needed > MAX_CAPACITY) {
            throw new OutOfMemoryError("protocol buffer would exceed " + MAX_CAPACITY + " bytes");
        }
        if (needed > buf.length) {
            long grown = Math.max((long) buf.length * 2, needed);
            buf = Arrays.copyOf(buf, (int) Math.min(grown, MAX_CAPACITY));
        }
    }
}
