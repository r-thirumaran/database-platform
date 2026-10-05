package org.dbplatform.protocol;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes frames ({@code u32 length | u8 type | payload}) to a stream and flushes after every frame.
 *
 * <p>Instances are not thread-safe; use one writer per connection.</p>
 */
public final class FrameWriter {

    private final DataOutputStream out;
    private final int maxFrameBytes;

    /**
     * Creates a writer with the default maximum frame size.
     *
     * @param out the stream (wrapped in a {@link DataOutputStream} unless it already is one)
     */
    public FrameWriter(OutputStream out) {
        this(out, ProtocolConstants.DEFAULT_MAX_FRAME_BYTES);
    }

    /**
     * Creates a writer.
     *
     * @param out           the stream (wrapped in a {@link DataOutputStream} unless it already is one)
     * @param maxFrameBytes maximum frame length (type byte plus payload) this side agrees to send, {@code >= 1}
     */
    public FrameWriter(OutputStream out, int maxFrameBytes) {
        if (out == null) {
            throw new IllegalArgumentException("out must not be null");
        }
        if (maxFrameBytes < 1) {
            throw new IllegalArgumentException("maxFrameBytes must be >= 1");
        }
        this.out = out instanceof DataOutputStream dos ? dos : new DataOutputStream(out);
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
     * Writes a frame and flushes the stream.
     *
     * @param type    message type
     * @param payload payload bytes ({@code null} = empty)
     * @throws ProtocolException if the frame would exceed the maximum frame size
     * @throws IOException       from the stream
     */
    public void writeFrame(MessageType type, byte[] payload) throws IOException {
        writeFrame(out, type, payload, maxFrameBytes);
    }

    /**
     * Writes a frame and flushes the stream.
     *
     * @param frame the frame
     * @throws IOException see {@link #writeFrame(MessageType, byte[])}
     */
    public void writeFrame(Frame frame) throws IOException {
        writeFrame(frame.type(), frame.payload());
    }

    /**
     * Flushes the underlying stream.
     *
     * @throws IOException from the stream
     */
    public void flush() throws IOException {
        out.flush();
    }

    /**
     * Writes one frame with the default maximum frame size and flushes.
     *
     * @param out     the stream
     * @param type    message type
     * @param payload payload bytes ({@code null} = empty)
     * @throws IOException see {@link #writeFrame(MessageType, byte[])}
     */
    public static void writeFrame(DataOutputStream out, MessageType type, byte[] payload) throws IOException {
        writeFrame(out, type, payload, ProtocolConstants.DEFAULT_MAX_FRAME_BYTES);
    }

    /**
     * Writes one frame and flushes.
     *
     * @param out           the stream
     * @param type          message type
     * @param payload       payload bytes ({@code null} = empty)
     * @param maxFrameBytes maximum frame length
     * @throws IOException see {@link #writeFrame(MessageType, byte[])}
     */
    public static void writeFrame(DataOutputStream out, MessageType type, byte[] payload, int maxFrameBytes)
            throws IOException {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        int payloadLength = payload == null ? 0 : payload.length;
        long length = 1L + payloadLength;
        if (length > maxFrameBytes) {
            throw new ProtocolException("frame length " + length + " exceeds maximum of " + maxFrameBytes + " bytes");
        }
        out.writeInt((int) length);
        out.writeByte(type.code());
        if (payloadLength > 0) {
            out.write(payload, 0, payloadLength);
        }
        out.flush();
    }
}
