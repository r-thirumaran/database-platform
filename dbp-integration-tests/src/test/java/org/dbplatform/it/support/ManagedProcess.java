package org.dbplatform.it.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A child process (control plane, gateway, proxy) with its stdout/stderr captured into a log file under
 * {@code target/it/logs}. Started through {@code nice} when available so the shared CPU stays responsive.
 */
public final class ManagedProcess implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(ManagedProcess.class);

    private final String name;
    private final Process process;
    private final Path log;

    private ManagedProcess(String name, Process process, Path log) {
        this.name = name;
        this.process = process;
        this.log = log;
    }

    public static ManagedProcess start(String name, Path logDir, Path workDir, Map<String, String> env, List<String> command) {
        try {
            Files.createDirectories(logDir);
            Path log = logDir.resolve(name + ".log");
            List<String> cmd = new ArrayList<>();
            if (Files.isExecutable(Path.of("/usr/bin/nice"))) {
                cmd.addAll(List.of("/usr/bin/nice", "-n", "10"));
            }
            cmd.addAll(command);
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
            if (workDir != null) {
                Files.createDirectories(workDir);
                pb.directory(workDir.toFile());
            }
            pb.environment().putAll(env);
            Files.writeString(log, "# " + String.join(" ", cmd) + "\n# env: " + env.keySet() + "\n", StandardCharsets.UTF_8);
            Process p = pb.start();
            LOG.info("started {} (pid {}) -> {}", name, p.pid(), log);
            return new ManagedProcess(name, p, log);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot start " + name, e);
        }
    }

    public String name() {
        return name;
    }

    public long pid() {
        return process.pid();
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public Path log() {
        return log;
    }

    /** Last lines of the captured log, for assertion messages. */
    public String tail(int lines) {
        try {
            List<String> all = Files.readAllLines(log, StandardCharsets.UTF_8);
            return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
        } catch (IOException e) {
            return "<log unreadable: " + e + ">";
        }
    }

    public boolean logContains(String needle) {
        try {
            return Files.readString(log, StandardCharsets.UTF_8).contains(needle);
        } catch (IOException e) {
            return false;
        }
    }

    /** SIGTERM, wait for the grace period, then SIGKILL (descendants included). */
    public void stop(Duration grace) {
        if (!process.isAlive()) {
            return;
        }
        LOG.info("stopping {} (pid {})", name, process.pid());
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warn("{} did not exit within {}s, killing", name, grace.toSeconds());
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    @Override
    public void close() {
        stop(Duration.ofSeconds(25));
    }
}
