package org.dbplatform.proxy;

import org.dbplatform.common.util.Env;
import org.dbplatform.proxy.config.ProxySettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the transparent proxy. Configuration comes entirely from {@code DBP_*} environment
 * variables (or system properties of the same name), see README.
 */
public final class ProxyMain {
    private static final Logger LOG = LoggerFactory.getLogger(ProxyMain.class);

    private ProxyMain() {
    }

    public static void main(String[] args) {
        ProxySettings settings;
        try {
            settings = ProxySettings.fromEnv(name -> Env.get(name, null));
        } catch (IllegalArgumentException e) {
            LOG.error("invalid configuration: {}", e.getMessage());
            System.exit(2);
            return;
        }
        try {
            ProxyApp app = ProxyApp.start(settings);
            Runtime.getRuntime().addShutdownHook(new Thread(app::close, "dbp-proxy-shutdown"));
            app.awaitTermination();
        } catch (IllegalStateException e) {
            LOG.error("{}", e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            LOG.error("proxy failed to start: {}", e.toString(), e);
            System.exit(1);
        }
    }
}
