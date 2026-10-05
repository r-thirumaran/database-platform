package org.dbplatform.gateway;

import org.dbplatform.gateway.config.StaticConfig;
import org.dbplatform.protocol.FrameReader;
import org.dbplatform.protocol.FrameWriter;
import org.dbplatform.protocol.messages.Execute;
import org.dbplatform.protocol.messages.ExecuteDone;
import org.dbplatform.protocol.messages.Hello;
import org.dbplatform.protocol.messages.HelloOk;
import org.dbplatform.protocol.messages.Message;
import org.dbplatform.protocol.messages.Messages;
import org.dbplatform.protocol.messages.ResultSetHeader;
import org.dbplatform.protocol.messages.Rows;
import org.dbplatform.protocol.messages.StatementKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TlsGatewayTest {

    @Test
    void helloAndQueryOverTls(@TempDir Path dir) throws Exception {
        Path ks = dir.resolve("gw.p12");
        Process keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                "-genkeypair", "-alias", "gw", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                "-validity", "2", "-storetype", "PKCS12", "-keystore", ks.toString(), "-storepass", "changeit")
                .redirectErrorStream(true).start();
        keytool.getInputStream().readAllBytes();
        assertThat(keytool.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(keytool.exitValue()).isZero();

        GatewayConfig cfg = GatewayConfig.embedded("gw-tls").withTls(ks, "changeit");
        StaticConfig sc = new StaticConfig("gw-tls", List.of(
                StaticConfig.DatasourceConfig.of("h2", "H2", "jdbc:h2:mem:tls;DB_CLOSE_DELAY=-1", "sa", "", "TRANSACTION", 2)),
                List.of());
        try (GatewayFixture gw = GatewayFixture.start(cfg, sc)) {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) { }
                public void checkServerTrusted(X509Certificate[] c, String a) { }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, null);
            try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket("127.0.0.1", gw.port())) {
                s.startHandshake();
                assertThat(s.getSession().getProtocol()).startsWith("TLSv1");
                FrameWriter out = new FrameWriter(s.getOutputStream());
                FrameReader in = new FrameReader(s.getInputStream());
                Messages.write(out, Hello.of("tls-test", "0", Map.of(Hello.PROP_DATASOURCE, "h2")));
                Message m = Messages.read(in);
                assertThat(m).isInstanceOf(HelloOk.class);
                Messages.write(out, Execute.direct("SELECT 42", StatementKind.STATEMENT, List.of(), Execute.ExecOptions.DEFAULT));
                ResultSetHeader h = (ResultSetHeader) Messages.read(in);
                Rows rows = (Rows) Messages.read(in, h.columnCount());
                assertThat(rows.rows().get(0).get(0)).isEqualTo(42);
                assertThat(Messages.read(in)).isInstanceOf(ExecuteDone.class);
            }
        }
    }
}
