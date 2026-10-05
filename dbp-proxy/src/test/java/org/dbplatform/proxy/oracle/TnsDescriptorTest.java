package org.dbplatform.proxy.oracle;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TnsDescriptorTest {
    static final String JDBC = "(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=proxy)(PORT=1521))"
            + "(CONNECT_DATA=(SERVICE_NAME=sales.orders-service)(CID=(PROGRAM=JDBC Thin Client)(HOST=__jdbc__)(USER=app))))";

    @Test
    void roundTripPreservesTextOrderAndUnknownKeys() {
        String in = "(DESCRIPTION=(SDU=65535)(ADDRESS_LIST=(LOAD_BALANCE=on)(ADDRESS=(PROTOCOL=tcp)(HOST=a)(PORT=1))(ADDRESS=(PROTOCOL=tcp)(HOST=b)(PORT=2)))"
                + "(CONNECT_DATA=(SERVER=DEDICATED)(SERVICE_NAME=x)(FUTURE_KEY=(SUB=1)(SUB=2))(TMP=)))";
        TnsDescriptor d = TnsDescriptor.parse(in);
        assertThat(d.serialize()).isEqualTo(in);
        assertThat(d.findAll("ADDRESS")).hasSize(2);
        assertThat(d.root().findValue("CONNECT_DATA", "SERVICE_NAME")).isEqualTo("x");
        assertThat(d.root().find("CONNECT_DATA", "FUTURE_KEY").childrenNamed("SUB")).hasSize(2);
        assertThat(d.root().find("CONNECT_DATA", "TMP").value()).isEmpty();
    }

    @Test
    void parsesValuesWithSpacesQuotesAndWhitespaceBetweenNodes() {
        String in = "(DESCRIPTION =\n  (ADDRESS = (PROTOCOL = TCP)(HOST = db.example.org)(PORT = 1521))\n"
                + "  (CONNECT_DATA = (PROGRAM = \"C:\\Program Files (x86)\\app.exe\")(SERVICE_NAME = svc)))";
        TnsDescriptor d = TnsDescriptor.parse(in);
        assertThat(d.root().findValue("ADDRESS", "HOST")).isEqualTo("db.example.org");
        assertThat(d.root().findValue("CONNECT_DATA", "PROGRAM")).isEqualTo("\"C:\\Program Files (x86)\\app.exe\"");
        assertThat(d.root().findValue("CONNECT_DATA", "SERVICE_NAME")).isEqualTo("svc");
        assertThat(d.serialize()).isEqualTo("(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=db.example.org)(PORT=1521))"
                + "(CONNECT_DATA=(PROGRAM=\"C:\\Program Files (x86)\\app.exe\")(SERVICE_NAME=svc)))");
    }

    @Test
    void keysAreCaseInsensitiveAndMutationsSerialize() {
        TnsDescriptor d = TnsDescriptor.parse(JDBC);
        TnsNode cd = d.findAny("connect_data");
        cd.put("SERVICE_NAME", "FREEPDB1");
        cd.remove("SID");
        d.findAny("ADDRESS").put("HOST", "oracle").put("PORT", "1522");
        assertThat(d.serialize()).isEqualTo("(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=oracle)(PORT=1522))"
                + "(CONNECT_DATA=(SERVICE_NAME=FREEPDB1)(CID=(PROGRAM=JDBC Thin Client)(HOST=__jdbc__)(USER=app))))");
    }

    @Test
    void unquotedValuesMayContainBalancedParentheses() {
        // OCI clients send the executable path unquoted, with spaces replaced by '?' and parentheses kept
        String oci = "(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=proxy)(PORT=1521))"
                + "(CONNECT_DATA=(SERVICE_NAME=sales.orders-service)(CID=(PROGRAM=C:\\Program?Files?(x86)\\Office\\EXCEL.EXE)(HOST=WS01)(USER=bob))))";
        TnsDescriptor d = TnsDescriptor.parse(oci);
        assertThat(d.serialize()).isEqualTo(oci);
        assertThat(d.root().findValue("CONNECT_DATA", "CID", "PROGRAM")).isEqualTo("C:\\Program?Files?(x86)\\Office\\EXCEL.EXE");
        TnsConnectString cs = TnsConnectString.parse(oci);
        assertThat(cs.isDescriptor()).isTrue();
        assertThat(cs.requestedService()).isEqualTo("sales.orders-service");
        assertThat(cs.program()).isEqualTo("C:\\Program?Files?(x86)\\Office\\EXCEL.EXE");
        assertThat(cs.rewrite("FREEPDB1", "oracle", 1522)).isEqualTo("(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=oracle)(PORT=1522))"
                + "(CONNECT_DATA=(SERVICE_NAME=FREEPDB1)(CID=(PROGRAM=C:\\Program?Files?(x86)\\Office\\EXCEL.EXE)(HOST=WS01)(USER=bob))))");
        // still rejected: an unbalanced ')' inside a value closes the node and leaves garbage behind
        assertThatThrownBy(() -> TnsDescriptor.parse("(DESCRIPTION=(CONNECT_DATA=(PROGRAM=foo)bar)(HOST=h)))")).isInstanceOf(TnsParseException.class);
    }

    @Test
    void rejectsPathologicalNestingWithoutStackOverflow() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 20_000; i++) {
            sb.append("(a=");
        }
        sb.append("x");
        for (int i = 0; i < 20_000; i++) {
            sb.append(")");
        }
        assertThatThrownBy(() -> TnsDescriptor.parse(sb.toString())).isInstanceOf(TnsParseException.class);
        assertThat(TnsConnectString.parse(sb.toString()).isDescriptor()).isFalse();
        // the real-world maximum (DESCRIPTION_LIST > DESCRIPTION > ADDRESS_LIST > ADDRESS > HOST) is far below the limit
        assertThat(TnsDescriptor.parse("(DESCRIPTION_LIST=(DESCRIPTION=(ADDRESS_LIST=(ADDRESS=(PROTOCOL=tcp)(HOST=h)(PORT=1)))(CONNECT_DATA=(CID=(PROGRAM=p)))))")
                .findAny("PROGRAM").value()).isEqualTo("p");
    }

    @Test
    void rejectsMalformedInput() {
        assertThatThrownBy(() -> TnsDescriptor.parse("(DESCRIPTION=(A=1)")).isInstanceOf(TnsParseException.class);
        assertThatThrownBy(() -> TnsDescriptor.parse("DESCRIPTION")).isInstanceOf(TnsParseException.class);
        assertThatThrownBy(() -> TnsDescriptor.parse("(=1)")).isInstanceOf(TnsParseException.class);
        assertThat(TnsDescriptor.looksLikeDescriptor("sales")).isFalse();
        assertThat(TnsDescriptor.looksLikeDescriptor(JDBC)).isTrue();
    }

    @Test
    void connectStringExtractsIdentityFieldsAndRewrites() {
        TnsConnectString cs = TnsConnectString.parse(JDBC);
        assertThat(cs.requestedService()).isEqualTo("sales.orders-service");
        assertThat(cs.program()).isEqualTo("JDBC Thin Client");
        assertThat(cs.host()).isEqualTo("__jdbc__");
        assertThat(cs.user()).isEqualTo("app");
        assertThat(cs.isPing()).isFalse();
        String out = cs.rewrite("FREEPDB1", "oracle", 1522);
        assertThat(out).isEqualTo("(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=oracle)(PORT=1522))"
                + "(CONNECT_DATA=(SERVICE_NAME=FREEPDB1)(CID=(PROGRAM=JDBC Thin Client)(HOST=__jdbc__)(USER=app))))");
    }

    @Test
    void sidIsReplacedBySetServiceNameAndKeptWhenNotRewriting() {
        String sid = "(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=proxy)(PORT=1521))(CONNECT_DATA=(SID=sales)(INSTANCE_NAME=i1)))";
        TnsConnectString cs = TnsConnectString.parse(sid);
        assertThat(cs.requestedService()).isEqualTo("sales");
        assertThat(cs.sid()).isEqualTo("sales");
        assertThat(cs.instanceName()).isEqualTo("i1");
        assertThat(cs.rewrite("FREEPDB1", "oracle", 1521))
                .isEqualTo("(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=oracle)(PORT=1521))(CONNECT_DATA=(INSTANCE_NAME=i1)(SERVICE_NAME=FREEPDB1)))");
        assertThat(TnsConnectString.parse(sid).rewrite(null, "oracle", 1521))
                .isEqualTo("(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=oracle)(PORT=1521))(CONNECT_DATA=(SID=sales)(INSTANCE_NAME=i1)))");
    }

    @Test
    void controlCharactersInClientValuesAreNeutralised() {
        String evil = "(DESCRIPTION=(ADDRESS=(PROTOCOL=tcp)(HOST=proxy)(PORT=1521))"
                + "(CONNECT_DATA=(SERVICE_NAME=sales.ok\nINFO forged line)(CID=(PROGRAM=app\u0007\u001b[31m)(HOST=h\r\n)(USER=u\tx))))";
        TnsConnectString cs = TnsConnectString.parse(evil);
        assertThat(cs.requestedService()).isEqualTo("sales.ok?INFO forged line");
        assertThat(cs.program()).isEqualTo("app??[31m");
        assertThat(cs.host()).as("surrounding whitespace is stripped first").isEqualTo("h");
        assertThat(cs.user()).isEqualTo("u?x");
        assertThat(cs.rewrite("FREEPDB1", "oracle", 1521)).as("the wire string itself is not altered").contains("(PROGRAM=app\u0007\u001b[31m)");
        assertThat(TnsConnectString.display("caf\u00C3\u00A9")).as("UTF-8 display decoding still works").isEqualTo("caf\u00E9");
    }

    @Test
    void pingAndNonDescriptorStringsAreTolerated() {
        TnsConnectString ping = TnsConnectString.parse("(DESCRIPTION=(CONNECT_DATA=(COMMAND=ping))(ADDRESS=(PROTOCOL=tcp)(HOST=h)(PORT=1521)))");
        assertThat(ping.isPing()).isTrue();
        assertThat(ping.requestedService()).isNull();
        TnsConnectString bare = TnsConnectString.parse("garbage");
        assertThat(bare.isDescriptor()).isFalse();
        assertThat(bare.rewrite("x", "h", 1)).isEqualTo("garbage");
    }
}
