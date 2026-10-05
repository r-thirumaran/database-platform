package org.dbplatform.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

/**
 * Entry point: {@code java -jar dbp-gateway-<ver>-all.jar}. Configuration comes from {@code DBP_*} environment
 * variables (see the module README).
 */
public final class GatewayMain {

    private static final Logger LOG = LoggerFactory.getLogger(GatewayMain.class);

    private GatewayMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && ("-h".equals(args[0]) || "--help".equals(args[0]))) {
            System.out.println("dbp-gateway " + Version.CURRENT);
            System.out.println("Configuration via environment: DBP_GATEWAY_PORT, DBP_GATEWAY_ADMIN_PORT, DBP_CONTROL_PLANE_URL,");
            System.out.println("DBP_SERVICE_TOKEN, DBP_GATEWAY_CONFIG (static YAML), DBP_GATEWAY_ID, ... see README.md");
            return;
        }
        Gateway gateway = Gateway.fromEnv();
        CountDownLatch done = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                gateway.stop();
            } finally {
                done.countDown();
            }
        }, "dbp-shutdown"));
        try {
            gateway.start();
        } catch (Exception e) {
            LOG.error("gateway failed to start: {}", e.getMessage(), e);
            System.exit(1);
        }
        done.await();
    }
}
