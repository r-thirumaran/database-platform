package org.dbplatform.proxy.oracle;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TnsPacketsTest {
    static final String SHORT = TnsDescriptorTest.JDBC;
    static final String LONG = "(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=proxy.very.long.host.name.example.org)(PORT=1521))"
            + "(CONNECT_DATA=(SERVICE_NAME=sales.orders-service)(SERVER=DEDICATED)(INSTANCE_NAME=orcl1)"
            + "(CID=(PROGRAM=JDBC Thin Client with a rather long program name for testing)(HOST=orders-service-7f9c4d5b6-abcde.default.svc.cluster.local)(USER=application-user))))";

    @Test
    void parsesInlineConnectVersion318And315() {
        for (int version : new int[] {318, 315}) {
            byte[] bytes = TnsTestPackets.connect(version, SHORT);
            TnsConnectPacket c = TnsConnectPacket.parse(bytes);
            assertThat(c.version()).isEqualTo(version);
            assertThat(c.versionCompatible()).isEqualTo(300);
            assertThat(c.serviceOptions()).isEqualTo(0x0C41);
            assertThat(c.sdu()).isEqualTo(8192);
            assertThat(c.tdu()).isEqualTo(0xFFFF);
            assertThat(c.ntProtocolCharacteristics()).isEqualTo(0x7F08);
            assertThat(c.valueOf1()).isEqualTo(1);
            assertThat(c.connectDataOffset()).isEqualTo(version == 318 ? 74 : 58);
            assertThat(c.connectDataLength()).isEqualTo(SHORT.length());
            assertThat(c.maxReceivable()).isEqualTo(2048);
            assertThat(c.isDeferred()).isFalse();
            assertThat(c.usesDeferredForm()).isFalse();
            assertThat(c.connectData()).isEqualTo(SHORT);
            assertThat(c.toWireBytes()).isEqualTo(bytes);
        }
    }

    @Test
    void parsesDeferredConnectAndAttachesDataPacket() throws IOException {
        assertThat(LONG.length()).isGreaterThan(TnsConnectPacket.INLINE_LIMIT);
        byte[] wire = TnsTestPackets.connect(318, LONG);
        ByteArrayInputStream in = new ByteArrayInputStream(wire);
        TnsPacket first = TnsPacket.read(in);
        assertThat(first.type()).isEqualTo(TnsPacket.TYPE_CONNECT);
        assertThat(first.length()).isEqualTo(74);
        TnsConnectPacket c = TnsConnectPacket.parse(first);
        assertThat(c.isDeferred()).isTrue();
        assertThat(c.usesDeferredForm()).isTrue();
        assertThat(c.connectDataLength()).isEqualTo(LONG.length());
        TnsPacket data = TnsPacket.read(in);
        assertThat(data.type()).isEqualTo(TnsPacket.TYPE_DATA);
        c = c.withDeferredData(data);
        assertThat(c.connectData()).isEqualTo(LONG);
        assertThat(c.toWireBytes()).isEqualTo(wire);
        assertThat(TnsPacket.read(in)).isNull();
    }

    @Test
    void rewriteKeepsHeaderFieldsFixesLengthsAndSwitchesForms() {
        byte[] original = TnsTestPackets.connect(318, SHORT);
        TnsConnectPacket c = TnsConnectPacket.parse(original);
        String rewritten = TnsConnectString.parse(SHORT).rewrite("FREEPDB1", "oracle", 1522);
        TnsConnectPacket r = c.withConnectData(rewritten);
        byte[] out = r.toWireBytes();
        assertThat(TnsPacket.u16(out, 0)).isEqualTo(74 + rewritten.length());
        assertThat(out.length).isEqualTo(74 + rewritten.length());
        assertThat(TnsPacket.u16(out, 24)).isEqualTo(rewritten.length());
        assertThat(TnsPacket.u16(out, 26)).isEqualTo(74);
        // every fixed byte between the length fields and the connect data is untouched
        for (int i = 4; i < 24; i++) {
            assertThat(out[i]).as("byte " + i).isEqualTo(original[i]);
        }
        for (int i = 28; i < 74; i++) {
            assertThat(out[i]).as("byte " + i).isEqualTo(original[i]);
        }
        assertThat(new String(out, 74, rewritten.length(), StandardCharsets.ISO_8859_1)).isEqualTo(rewritten);

        // growing past 230 bytes produces the deferred form: CONNECT without data + DATA packet
        TnsConnectPacket big = c.withConnectData(LONG);
        assertThat(big.usesDeferredForm()).isTrue();
        assertThat(big.packetBytes().length).isEqualTo(74);
        assertThat(TnsPacket.u16(big.packetBytes(), 0)).isEqualTo(74);
        assertThat(TnsPacket.u16(big.packetBytes(), 24)).isEqualTo(LONG.length());
        assertThat(big.toWire()).hasSize(2);
        byte[] dataPacket = big.toWire().get(1);
        assertThat(dataPacket[4]).isEqualTo((byte) TnsPacket.TYPE_DATA);
        assertThat(TnsPacket.u16(dataPacket, 0)).isEqualTo(10 + LONG.length());
        assertThat(TnsPacket.u16(dataPacket, 8)).isEqualTo(0);
        assertThat(TnsTestPackets.dataText(TnsPacket.wrap(dataPacket))).isEqualTo(LONG);
        assertThat(big.toWireBytes()).isEqualTo(TnsTestPackets.connect(318, LONG));

        // shrinking a deferred string below the limit goes back to inline
        TnsConnectPacket small = big.withConnectData(SHORT);
        assertThat(small.usesDeferredForm()).isFalse();
        assertThat(small.toWireBytes()).isEqualTo(original);
    }

    @Test
    void thirtyTwoBitLengthHeaderIsReadAndPreserved() throws IOException {
        byte[] bytes = TnsTestPackets.connect(315, SHORT, true);
        assertThat(TnsPacket.u16(bytes, 0)).isZero();
        TnsPacket p = TnsPacket.read(new ByteArrayInputStream(bytes));
        assertThat(p.length()).isEqualTo(bytes.length);
        assertThat(p.lengthIs32Bit()).isTrue();
        TnsConnectPacket c = TnsConnectPacket.parse(p);
        assertThat(c.connectData()).isEqualTo(SHORT);
        TnsConnectPacket r = c.withConnectData("(DESCRIPTION=(CONNECT_DATA=(SERVICE_NAME=X)))");
        byte[] out = r.packetBytes();
        assertThat(TnsPacket.u16(out, 0)).isZero();
        assertThat(TnsPacket.u16(out, 2)).isEqualTo(out.length);
    }

    @Test
    void refusePacketLayout() {
        byte[] r = TnsRefusePacket.build(12516);
        String data = TnsRefusePacket.refuseDescriptor(12516);
        assertThat(data).isEqualTo("(DESCRIPTION=(TMP=)(VSNNUM=0)(ERR=12516)(ERROR_STACK=(ERROR=(CODE=12516)(EMFI=4))))");
        assertThat(r.length).isEqualTo(8 + 4 + data.length());
        assertThat(TnsPacket.u16(r, 0)).isEqualTo(r.length);
        assertThat(TnsPacket.u16(r, 2)).isZero();
        assertThat(r[4]).isEqualTo((byte) TnsPacket.TYPE_REFUSE);
        assertThat(r[5]).isZero();
        assertThat(TnsPacket.u16(r, 6)).isZero();
        assertThat(r[8]).isEqualTo((byte) 0x22);
        assertThat(r[9]).isEqualTo((byte) 0x00);
        assertThat(TnsPacket.u16(r, 10)).isEqualTo(data.length());
        assertThat(new String(r, 12, data.length(), StandardCharsets.ISO_8859_1)).isEqualTo(data);
        TnsPacket p = TnsPacket.wrap(r);
        assertThat(TnsRefusePacket.errorCode(p)).isEqualTo(12516);
        assertThat(TnsRefusePacket.errorCode(TnsPacket.wrap(TnsRefusePacket.build(12514)))).isEqualTo(12514);
    }

    @Test
    void redirectPacketWithAndWithoutReplacementConnectData() throws IOException {
        TnsRedirectPacket plain = TnsRedirectPacket.parse(TnsPacket.wrap(
                TnsRedirectPacket.build("(ADDRESS=(PROTOCOL=tcp)(HOST=oracle-node2)(PORT=41234))", null)));
        assertThat(plain.needsData()).isFalse();
        assertThat(plain.host()).isEqualTo("oracle-node2");
        assertThat(plain.port()).isEqualTo(41234);
        assertThat(plain.connectData()).isNull();

        String replacement = "(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=oracle)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=FREEPDB1)(SERVER=DEDICATED)))";
        TnsRedirectPacket withData = TnsRedirectPacket.parse(TnsPacket.wrap(
                TnsRedirectPacket.build("(ADDRESS=(PROTOCOL=tcp)(HOST=10.0.0.5)(PORT=1522))", replacement)));
        assertThat(withData.host()).isEqualTo("10.0.0.5");
        assertThat(withData.port()).isEqualTo(1522);
        assertThat(withData.connectData()).isEqualTo(replacement);

        // data length larger than the packet: data arrives in a DATA packet
        byte[] body = new byte[2];
        TnsPacket.putU16(body, 0, 60);
        TnsRedirectPacket deferred = TnsRedirectPacket.parse(TnsPacket.create(TnsPacket.TYPE_REDIRECT, 0, body));
        assertThat(deferred.needsData()).isTrue();
        TnsRedirectPacket done = deferred.withData(TnsPacket.read(new ByteArrayInputStream(
                TnsTestPackets.data("(ADDRESS=(PROTOCOL=tcp)(HOST=h)(PORT=7))"))));
        assertThat(done.host()).isEqualTo("h");
        assertThat(done.port()).isEqualTo(7);
    }

    @Test
    void readRejectsBogusLengthsAndTruncation() {
        byte[] bogus = new byte[] {0, 2, 0, 0, 1, 0, 0, 0};
        assertThatThrownBy(() -> TnsPacket.read(new ByteArrayInputStream(bogus))).isInstanceOf(TnsParseException.class);
        byte[] truncated = new byte[] {0, 40, 0, 0, 1, 0, 0, 0, 1, 2};
        assertThatThrownBy(() -> TnsPacket.read(new ByteArrayInputStream(truncated))).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> TnsConnectPacket.parse(TnsPacket.create(TnsPacket.TYPE_CONNECT, 0, new byte[4])))
                .isInstanceOf(TnsParseException.class);
    }
}
