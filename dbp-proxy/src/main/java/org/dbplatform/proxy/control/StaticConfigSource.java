package org.dbplatform.proxy.control;

import org.dbplatform.proxy.config.ProxyConfigDocument;
import org.dbplatform.proxy.config.StaticConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.function.Consumer;

/** Static mode: {@code DBP_PROXY_CONFIG} YAML, re-read when its modification time changes. */
public final class StaticConfigSource implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(StaticConfigSource.class);

    private final Path file;
    private final int pollSeconds;
    private final Consumer<ProxyConfigDocument> onConfig;
    private volatile boolean running = true;
    private volatile FileTime lastModified;
    private volatile long version;
    private Thread watcher;

    public StaticConfigSource(Path file, int pollSeconds, Consumer<ProxyConfigDocument> onConfig) {
        this.file = file;
        this.pollSeconds = pollSeconds;
        this.onConfig = onConfig;
    }

    public ProxyConfigDocument loadInitial() throws IOException {
        lastModified = Files.getLastModifiedTime(file);
        ProxyConfigDocument doc = StaticConfigLoader.load(file);
        version = doc.configVersion() > 0 ? doc.configVersion() : 1;
        doc = doc.withVersion(version);
        onConfig.accept(doc);
        return doc;
    }

    public void start() {
        if (pollSeconds <= 0) {
            return;
        }
        watcher = Thread.ofVirtual().name("dbp-config-watch").start(this::watchLoop);
    }

    private void watchLoop() {
        while (running) {
            try {
                Thread.sleep(pollSeconds * 1000L);
            } catch (InterruptedException e) {
                return;
            }
            try {
                FileTime now = Files.getLastModifiedTime(file);
                if (!now.equals(lastModified)) {
                    lastModified = now;
                    ProxyConfigDocument doc = StaticConfigLoader.load(file);
                    version = doc.configVersion() > 0 ? doc.configVersion() : version + 1;
                    LOG.info("configuration file {} changed, reloading as version {}", file, version);
                    onConfig.accept(doc.withVersion(version));
                }
            } catch (IOException | RuntimeException e) {
                LOG.error("cannot reload {}: {} (keeping the current configuration)", file, e.toString());
            }
        }
    }

    @Override
    public void close() {
        running = false;
        if (watcher != null) {
            watcher.interrupt();
        }
    }
}
