package org.dbplatform.protocol;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolInputOutputTest {

    @Test
    void primitivesRoundTripAndMatchDataOutputEncoding() throws IOException {
        ProtocolOutput out = new ProtocolOutput(1);
        out.writeU8(0).writeU8(255).writeI8(-128).writeI8(127)
                .writeBool(true).writeBool(false)
                .writeI16(Short.MIN_VALUE).writeI16(Short.MAX_VALUE).writeI16(-1)
                .writeI32(Integer.MIN_VALUE).writeI32(Integer.MAX_VALUE).writeI32(-1).writeI32(0x01020304)
                .writeI64(Long.MIN_VALUE).writeI64(Long.MAX_VALUE).writeI64(-1L).writeI64(0x0102030405060708L)
                .writeF32(Float.MIN_VALUE).writeF32(Float.NaN).writeF32(-0.0f)
                .writeF64(Double.MAX_VALUE).writeF64(Double.NEGATIVE_INFINITY).writeF64(Math.PI);

        // Reference encoding per spec: DataOutput semantics
        ByteArrayOutputStream ref = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(ref);
        d.writeByte(0); d.writeByte(255); d.writeByte(-128); d.writeByte(127);
        d.writeBoolean(true); d.writeBoolean(false);
        d.writeShort(Short.MIN_VALUE); d.writeShort(Short.MAX_VALUE); d.writeShort(-1);
        d.writeInt(Integer.MIN_VALUE); d.writeInt(Integer.MAX_VALUE); d.writeInt(-1); d.writeInt(0x01020304);
        d.writeLong(Long.MIN_VALUE); d.writeLong(Long.MAX_VALUE); d.writeLong(-1L); d.writeLong(0x0102030405060708L);
        d.writeFloat(Float.MIN_VALUE); d.writeFloat(Float.NaN); d.writeFloat(-0.0f);
        d.writeDouble(Double.MAX_VALUE); d.writeDouble(Double.NEGATIVE_INFINITY); d.writeDouble(Math.PI);
        assertThat(out.toByteArray()).isEqualTo(ref.toByteArray());

        ProtocolInput in = new ProtocolInput(out.toByteArray());
        assertThat(in.readU8()).isEqualTo(0);
        assertThat(in.readU8()).isEqualTo(255);
        assertThat(in.readI8()).isEqualTo((byte) -128);
        assertThat(in.readI8()).isEqualTo((byte) 127);
        assertThat(in.readBool()).isTrue();
        assertThat(in.readBool()).isFalse();
        assertThat(in.readI16()).isEqualTo(Short.MIN_VALUE);
        assertThat(in.readI16()).isEqualTo(Short.MAX_VALUE);
        assertThat(in.readI16()).isEqualTo((short) -1);
        assertThat(in.readI32()).isEqualTo(Integer.MIN_VALUE);
        assertThat(in.readI32()).isEqualTo(Integer.MAX_VALUE);
        assertThat(in.readI32()).isEqualTo(-1);
        assertThat(in.readI32()).isEqualTo(0x01020304);
        assertThat(in.readI64()).isEqualTo(Long.MIN_VALUE);
        assertThat(in.readI64()).isEqualTo(Long.MAX_VALUE);
        assertThat(in.readI64()).isEqualTo(-1L);
        assertThat(in.readI64()).isEqualTo(0x0102030405060708L);
        assertThat(in.readF32()).isEqualTo(Float.MIN_VALUE);
        assertThat(in.readF32()).isNaN();
        assertThat(Float.floatToIntBits(in.readF32())).isEqualTo(Float.floatToIntBits(-0.0f));
        assertThat(in.readF64()).isEqualTo(Double.MAX_VALUE);
        assertThat(in.readF64()).isEqualTo(Double.NEGATIVE_INFINITY);
        assertThat(in.readF64()).isEqualTo(Math.PI);
        assertThat(in.hasRemaining()).isFalse();
        in.expectEnd();
    }

    @Test
    void stringsIncludingNullEmptyAndUnicode() throws ProtocolException {
        String unicode = "héllo wörld – 日本語 – emoji 😀 – \u0000 nul";
        ProtocolOutput out = new ProtocolOutput();
        out.writeString(null).writeString("").writeString("abc").writeString(unicode);
        byte[] bytes = out.toByteArray();

        // null = -1 length, empty = 0 length, byte length (not char length) prefix
        assertThat(Arrays.copyOfRange(bytes, 0, 4)).containsExactly(0xFF, 0xFF, 0xFF, 0xFF);
        assertThat(Arrays.copyOfRange(bytes, 4, 8)).containsExactly(0, 0, 0, 0);
        int unicodeLen = unicode.getBytes(StandardCharsets.UTF_8).length;
        assertThat(unicodeLen).isNotEqualTo(unicode.length());
        int offset = 8 + 4 + 3;
        assertThat(new ProtocolInput(bytes, offset, 4).readI32()).isEqualTo(unicodeLen);

        ProtocolInput in = new ProtocolInput(bytes);
        assertThat(in.readString()).isNull();
        assertThat(in.readString()).isEmpty();
        assertThat(in.readString()).isEqualTo("abc");
        assertThat(in.readString()).isEqualTo(unicode);
        in.expectEnd();
    }

    @Test
    void bytesIncludingNullAndEmpty() throws ProtocolException {
        byte[] data = {0, 1, -1, 127, -128};
        ProtocolOutput out = new ProtocolOutput();
        out.writeBytes(null).writeBytes(new byte[0]).writeBytes(data);
        ProtocolInput in = new ProtocolInput(out.toByteArray());
        assertThat(in.readBytes()).isNull();
        assertThat(in.readBytes()).isEmpty();
        byte[] read = in.readBytes();
        assertThat(read).isEqualTo(data).isNotSameAs(data);
        in.expectEnd();
    }

    @Test
    void mapPreservesOrderAndNulls() throws ProtocolException {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("z", "1");
        m.put("a", null);
        m.put("", "");
        m.put("ünïcödé", "日本");
        ProtocolOutput out = new ProtocolOutput();
        out.writeMap(m).writeMap(Map.of());
        ProtocolInput in = new ProtocolInput(out.toByteArray());
        Map<String, String> read = in.readMap();
        assertThat(read).containsExactlyEntriesOf(m);
        assertThat(in.readMap()).isEmpty();
        in.expectEnd();
    }

    @Test
    void stringArraysIncludingEmptyAndNullElements() throws ProtocolException {
        ProtocolOutput out = new ProtocolOutput();
        out.writeStringArray(List.of()).writeStringArray(Arrays.asList("a", null, "")).writeStringArray("x", "y");
        ProtocolInput in = new ProtocolInput(out.toByteArray());
        assertThat(in.readStringArray()).isEmpty();
        assertThat(in.readStringArray()).containsExactly("a", null, "");
        assertThat(in.readStringArray()).containsExactly("x", "y");
        in.expectEnd();
    }

    @Test
    void valuesArrayAndRow() throws ProtocolException {
        ProtocolOutput out = new ProtocolOutput();
        out.writeValues(List.of()).writeValues(1, "two", null).writeRow(Arrays.asList(3L, null));
        ProtocolInput in = new ProtocolInput(out.toByteArray());
        assertThat(in.readValues()).isEmpty();
        assertThat(in.readValues()).containsExactly(1, "two", null);
        assertThat(in.readRow(2)).containsExactly(3L, null);
        in.expectEnd();
    }

    @Test
    void reserveAndPatchI32() throws ProtocolException {
        ProtocolOutput out = new ProtocolOutput();
        out.writeU8(7);
        int pos = out.reserveI32();
        out.writeU8(9);
        out.putI32At(pos, 123456);
        ProtocolInput in = new ProtocolInput(out.toByteArray());
        assertThat(in.readU8()).isEqualTo(7);
        assertThat(in.readI32()).isEqualTo(123456);
        assertThat(in.readU8()).isEqualTo(9);
        assertThatThrownBy(() -> out.putI32At(3, 1)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> out.putI32At(-1, 1)).isInstanceOf(IndexOutOfBoundsException.class);
    }

    @Test
    void bufferGrowsAndResets() {
        ProtocolOutput out = new ProtocolOutput(1);
        byte[] big = new byte[100_000];
        Arrays.fill(big, (byte) 0x5A);
        out.writeRaw(big).writeRaw(big, 10, 20);
        assertThat(out.size()).isEqualTo(100_020);
        assertThat(out.toByteArray()[99_999]).isEqualTo((byte) 0x5A);
        out.reset();
        assertThat(out.size()).isZero();
        assertThat(out.toByteArray()).isEmpty();
        Frame f = out.writeU8(1).toFrame(MessageType.OK);
        assertThat(f.type()).isEqualTo(MessageType.OK);
        assertThat(f.payload()).containsExactly(1);
    }

    @Test
    void truncatedPrimitivesThrowProtocolException() {
        byte[] three = {1, 2, 3};
        assertThatThrownBy(() -> new ProtocolInput(three).readI32()).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("truncated");
        assertThatThrownBy(() -> new ProtocolInput(three).readI64()).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> new ProtocolInput(new byte[0]).readU8()).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> new ProtocolInput(new byte[1]).readI16()).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> new ProtocolInput(new byte[7]).readF64()).isInstanceOf(ProtocolException.class);
    }

    @Test
    void stringLengthBeyondPayloadIsRejectedWithoutAllocating() {
        byte[] bogus = new ProtocolOutput().writeI32(Integer.MAX_VALUE).writeU8(1).toByteArray();
        assertThatThrownBy(() -> new ProtocolInput(bogus).readString()).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("exceeds remaining");
        assertThatThrownBy(() -> new ProtocolInput(bogus).readBytes()).isInstanceOf(ProtocolException.class);
        byte[] negative = new ProtocolOutput().writeI32(-2).toByteArray();
        assertThatThrownBy(() -> new ProtocolInput(negative).readString()).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("invalid string length");
        byte[] shortPayload = new ProtocolOutput().writeI32(5).writeRaw(new byte[] {1, 2}).toByteArray();
        assertThatThrownBy(() -> new ProtocolInput(shortPayload).readString()).isInstanceOf(ProtocolException.class);
    }

    @Test
    void negativeOrImpossibleCountsAreRejected() {
        byte[] negative = new ProtocolOutput().writeI32(-1).toByteArray();
        assertThatThrownBy(() -> new ProtocolInput(negative).readMap()).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("negative");
        assertThatThrownBy(() -> new ProtocolInput(negative).readStringArray()).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> new ProtocolInput(negative).readValues()).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> new ProtocolInput(new byte[0]).readRow(-1)).isInstanceOf(ProtocolException.class);
        byte[] huge = new ProtocolOutput().writeI32(1_000_000).writeU8(0).toByteArray();
        assertThatThrownBy(() -> new ProtocolInput(huge).readValues()).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("exceeds remaining");
        assertThatThrownBy(() -> new ProtocolInput(new byte[2]).readRow(3)).isInstanceOf(ProtocolException.class);
    }

    @Test
    void boolMustBeZeroOrOne() {
        assertThatThrownBy(() -> new ProtocolInput(new byte[] {2}).readBool()).isInstanceOf(ProtocolException.class)
                .hasMessageContaining("invalid bool");
    }

    @Test
    void expectEndDetectsTrailingBytes() {
        ProtocolInput in = new ProtocolInput(new byte[] {1, 2});
        assertThatThrownBy(in::expectEnd).isInstanceOf(ProtocolException.class).hasMessageContaining("trailing");
    }

    @Test
    void sliceConstructorRespectsBounds() throws ProtocolException {
        byte[] data = {9, 9, 1, 2, 3, 9};
        ProtocolInput in = new ProtocolInput(data, 2, 3);
        assertThat(in.position()).isEqualTo(2);
        assertThat(in.remaining()).isEqualTo(3);
        assertThat(in.readRaw(3)).containsExactly(1, 2, 3);
        assertThat(in.hasRemaining()).isFalse();
        assertThatThrownBy(() -> new ProtocolInput(data, 4, 3)).isInstanceOf(IndexOutOfBoundsException.class);
        assertThatThrownBy(() -> new ProtocolInput(null)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void nullCollectionsAreRejectedOnWrite() {
        ProtocolOutput out = new ProtocolOutput();
        assertThatThrownBy(() -> out.writeMap(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> out.writeStringArray((List<String>) null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> out.writeValues((List<Object>) null)).isInstanceOf(IllegalArgumentException.class);
    }
}
