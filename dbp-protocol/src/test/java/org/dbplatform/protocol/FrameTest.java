package org.dbplatform.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FrameTest {

    private static byte[] write(Frame... frames) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        FrameWriter w = new FrameWriter(bos);
        for (Frame f : frames) {
            w.writeFrame(f);
        }
        return bos.toByteArray();
    }

    @Test
    void wireLayoutIsLengthTypePayload() throws IOException {
        byte[] bytes = write(new Frame(MessageType.HELLO, new byte[] {1, 2, 3}));
        // u32 length = 1 (type) + 3 (payload) = 4, u8 type = 0x01, payload
        assertThat(bytes).containsExactly(0, 0, 0, 4, 0x01, 1, 2, 3);

        byte[] empty = write(Frame.empty(MessageType.PONG));
        assertThat(empty).containsExactly(0, 0, 0, 1, 0x43);
    }

    @Test
    void readerRoundTripsSeveralFramesAndThenSignalsCleanEof() throws IOException {
        Frame a = new Frame(MessageType.HELLO, "payload".getBytes());
        Frame b = Frame.empty(MessageType.PING);
        Frame c = new Frame(MessageType.ROWS, new byte[70_000]);
        FrameReader r = new FrameReader(new ByteArrayInputStream(write(a, b, c)));
        assertThat(r.readFrame()).isEqualTo(a);
        assertThat(r.readFrame()).isEqualTo(b);
        assertThat(r.readFrame()).isEqualTo(c);
        assertThatThrownBy(r::readFrame).isExactlyInstanceOf(EOFException.class);
        // and again: still a clean EOF, never an IndexOutOfBounds or NPE
        assertThatThrownBy(r::readFrame).isExactlyInstanceOf(EOFException.class);
    }

    @Test
    void staticHelpersMatchInstanceMethods() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        FrameWriter.writeFrame(new DataOutputStream(bos), MessageType.OK, null);
        FrameWriter.writeFrame(new DataOutputStream(bos), MessageType.ERROR, new byte[] {9}, 16);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
        assertThat(FrameReader.readFrame(in)).isEqualTo(Frame.empty(MessageType.OK));
        assertThat(FrameReader.readFrame(in, 16)).isEqualTo(new Frame(MessageType.ERROR, new byte[] {9}));
    }

    @Test
    void oversizeFramesAreRejectedOnReadWithoutAllocating() {
        // header claims 2^31 - 1 bytes; default limit is 64 MiB
        byte[] header = {0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x01};
        FrameReader r = new FrameReader(new ByteArrayInputStream(header));
        assertThatThrownBy(r::readFrame).isInstanceOf(ProtocolException.class).hasMessageContaining("exceeds maximum");

        // u32 is unsigned: 0xFFFFFFFF must not be treated as -1
        byte[] unsigned = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x01};
        assertThatThrownBy(() -> new FrameReader(new ByteArrayInputStream(unsigned)).readFrame())
                .isInstanceOf(ProtocolException.class).hasMessageContaining("exceeds maximum");

        // configurable limit: a 10 byte frame against a 9 byte limit
        byte[] ten = {0, 0, 0, 10, 0x01, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        assertThatThrownBy(() -> new FrameReader(new ByteArrayInputStream(ten), 9).readFrame())
                .isInstanceOf(ProtocolException.class).hasMessageContaining("exceeds maximum of 9");
        assertThatThrownBy(() -> new FrameReader(new ByteArrayInputStream(ten), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void limitIsInclusive() throws IOException {
        byte[] ten = {0, 0, 0, 10, 0x01, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        Frame f = new FrameReader(new ByteArrayInputStream(ten), 10).readFrame();
        assertThat(f.payload()).hasSize(9);
        assertThat(f.wireLength()).isEqualTo(10);
    }

    @Test
    void oversizeFramesAreRejectedOnWrite() {
        FrameWriter w = new FrameWriter(new ByteArrayOutputStream(), 4);
        assertThatThrownBy(() -> w.writeFrame(MessageType.OK, new byte[4]))
                .isInstanceOf(ProtocolException.class).hasMessageContaining("exceeds maximum");
        assertThatThrownBy(() -> new FrameWriter(OutputStream.nullOutputStream(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroLengthFrameIsRejected() {
        byte[] zero = {0, 0, 0, 0};
        assertThatThrownBy(() -> new FrameReader(new ByteArrayInputStream(zero)).readFrame())
                .isInstanceOf(ProtocolException.class).hasMessageContaining("invalid frame length 0");
    }

    @Test
    void unknownTypeIsRejected() {
        byte[] bad = {0, 0, 0, 1, 0x7E};
        assertThatThrownBy(() -> new FrameReader(new ByteArrayInputStream(bad)).readFrame())
                .isInstanceOf(ProtocolException.class).hasMessageContaining("unknown message type");
    }

    @Test
    void truncatedInputIsRejectedAtEveryCutPoint() throws IOException {
        byte[] full = write(new Frame(MessageType.EXECUTE, new byte[] {1, 2, 3, 4, 5}));
        for (int cut = 1; cut < full.length; cut++) {
            byte[] truncated = Arrays.copyOf(full, cut);
            FrameReader r = new FrameReader(new ByteArrayInputStream(truncated));
            assertThatThrownBy(r::readFrame)
                    .as("stream cut after %d of %d bytes", cut, full.length)
                    .isInstanceOf(ProtocolException.class)
                    .isNotInstanceOf(EOFException.class)
                    .hasMessageContaining("truncated frame");
        }
    }

    @Test
    void readerWorksWithSlowStreamsDeliveringOneByteAtATime() throws IOException {
        byte[] full = write(new Frame(MessageType.FETCH, new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
        ByteArrayInputStream slow = new ByteArrayInputStream(full) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                return super.read(b, off, Math.min(len, 1));
            }
        };
        Frame f = new FrameReader(slow).readFrame();
        assertThat(f).isEqualTo(new Frame(MessageType.FETCH, new byte[] {1, 2, 3, 4, 5, 6, 7, 8}));
    }

    @Test
    void writerFlushesAfterEachFrame() throws IOException {
        int[] flushes = {0};
        OutputStream counting = new OutputStream() {
            @Override
            public void write(int b) {
            }

            @Override
            public void flush() {
                flushes[0]++;
            }
        };
        FrameWriter w = new FrameWriter(counting);
        w.writeFrame(Frame.empty(MessageType.PING));
        w.writeFrame(Frame.empty(MessageType.PING));
        assertThat(flushes[0]).isGreaterThanOrEqualTo(2);
    }

    @Test
    void frameRecordNormalisesNullPayloadAndHasValueEquality() {
        Frame f = new Frame(MessageType.OK, null);
        assertThat(f.payload()).isEmpty();
        assertThat(f).isEqualTo(Frame.empty(MessageType.OK)).hasSameHashCodeAs(Frame.empty(MessageType.OK));
        assertThat(f).isNotEqualTo(Frame.empty(MessageType.PONG));
        assertThat(f.toString()).contains("OK").contains("0 bytes");
        assertThat(f.input().hasRemaining()).isFalse();
        assertThatThrownBy(() -> new Frame(null, new byte[0])).isInstanceOf(NullPointerException.class);
    }

    @Test
    void defaultMaxFrameBytesIs64MiB() {
        assertThat(ProtocolConstants.DEFAULT_MAX_FRAME_BYTES).isEqualTo(64 * 1024 * 1024);
        assertThat(new FrameReader(new ByteArrayInputStream(new byte[0])).maxFrameBytes())
                .isEqualTo(ProtocolConstants.DEFAULT_MAX_FRAME_BYTES);
        assertThat(new FrameWriter(OutputStream.nullOutputStream()).maxFrameBytes())
                .isEqualTo(ProtocolConstants.DEFAULT_MAX_FRAME_BYTES);
        assertThat(ProtocolConstants.VERSION).isEqualTo(1);
        assertThat(ProtocolConstants.DEFAULT_PORT).isEqualTo(7420);
        assertThat(ProtocolConstants.DEFAULT_FETCH_SIZE).isEqualTo(100);
    }
}
