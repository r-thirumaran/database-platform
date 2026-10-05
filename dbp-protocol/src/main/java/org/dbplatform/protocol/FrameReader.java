package org.dbplatform.protocol;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads frames ({@code u32 length | u8 type | payload}) from a stream with maximum-frame-size enforcement.
 *
 * <ul>
 *   <li>A clean end of stream <em>between</em> frames raises {@link EOFException} (peer closed the connection).</li>
 *   <li>A stream ending <em>inside</em> a frame raises {@link ProtocolException} ("truncated frame").</li>
 *   <li>A length of 0, a length above the configured maximum or an unknown type code raises
 *       {@link ProtocolException}. The maximum applies to the {@code length} field, i.e. type byte plus payload.</li>
 * </ul>
 *
 * <p>Instances are not thread-safe; use one reader per connection.</p>
 */
public final class FrameReader {

    private final DataInputStream in;
    private final int maxFrameBytes;

    /**
     * Creates a reader with the default maximum frame size ({@link ProtocolConstants#DEFAULT_MAX_FRAME_BYTES}).
     *
     * @param in the stream (wrapped in a {@link DataInputStream} unless it already is one)
     */
    public FrameReader(InputStream in) {
        this(in, ProtocolConstants.DEFAULT_MAX_FRAME_BYTES);
    }

    /**
     * Creates a reader.
     *
     * @param in            the stream (wrapped in a {@link DataInputStream} unless it already is one)
     * @param maxFrameBytes maximum accepted value of the frame length field, {@code >= 1}
     */
    public FrameReader(InputStream in, int maxFrameBytes) {
        if (in == null) {
            throw new IllegalArgumentException("in must not be null");
        }
        if (maxFrameBytes < 1) {
            throw new IllegalArgumentException("maxFrameBytes must be >= 1");
        }
        this.in = in instanceof DataInputStream dis ? dis : new DataInputStream(in);
        this.maxFrameBytes = maxFrameBytes;
    }

    /**
     * Returns the configured maximum frame size.
     *
     * @return maximum frame length in bytes
     */
    public int maxFrameBytes() {
        return maxFrameBytes;
    }

    /**
     * Reads the next frame, blocking until it is complete.
     *
     * @return the frame
     * @throws EOFException      if the stream ended cleanly before a new frame started
     * @throws ProtocolException if the frame is oversize, truncated or has an unknown type
     * @throws IOException       on other I/O errors
     */
    public Frame readFrame() throws IOException {
        return readFrame(in, maxFrameBytes);
    }

    /**
     * Reads one frame from a stream with the default maximum frame size.
     *
     * @param in the stream
     * @return the frame
     * @throws IOException see {@link #readFrame()}
     */
    public static Frame readFrame(DataInputStream in) throws IOException {
        return readFrame(in, ProtocolConstants.DEFAULT_MAX_FRAME_BYTES);
    }

    /**
     * Reads one frame from a stream.
     *
     * @param in            the stream
     * @param maxFrameBytes maximum accepted value of the frame length field
     * @return the frame
     * @throws IOException see {@link #readFrame()}
     */
    public static Frame readFrame(DataInputStream in, int maxFrameBytes) throws IOException {
        int first = in.read();
        if (first < 0) {
            throw new EOFException("connection closed by peer");
        }
        long length;
        try {
            length = ((long) first << 24)
                    | ((long) in.readUnsignedByte() << 16)
                    | ((long) in.readUnsignedByte() << 8)
                    | in.readUnsignedByte();
        } catch (EOFException e) {
            throw new ProtocolException("truncated frame: stream ended inside the length prefix", e);
        }
        if (length < 1) {
            throw new ProtocolException("invalid frame length " + length + " (must be >= 1)");
        }
        if (length > maxFrameBytes) {
            throw new ProtocolException("frame length " + length + " exceeds maximum of " + maxFrameBytes + " bytes");
        }
        int typeCode;
        try {
            typeCode = in.readUnsignedByte();
        } catch (EOFException e) {
            throw new ProtocolException("truncated frame: stream ended before the type byte", e);
        }
        MessageType type = MessageType.fromCode(typeCode);
        int payloadLength = (int) (length - 1);
        byte[] payload = new byte[payloadLength];
        try {
            in.readFully(payload);
        } catch (EOFException e) {
            throw new ProtocolException("truncated frame: stream ended inside the payload of " + type
                    + " (expected " + payloadLength + " bytes)", e);
        }
        return new Frame(type, payload);
    }
}
