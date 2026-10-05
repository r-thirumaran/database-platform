package org.dbplatform.common.telemetry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Non-blocking telemetry reporter used by gateway and proxy. Events are appended to a bounded in-memory
 * queue (default {@value #DEFAULT_QUEUE_CAPACITY}); a background virtual thread POSTs them as JSON
 * arrays of at most {@value #DEFAULT_BATCH_SIZE} to
 * {@code <controlPlaneUrl>/api/v1/internal/telemetry/{queries|connections|pools}} every
 * {@code flushInterval} (default {@value #DEFAULT_FLUSH_INTERVAL_MS} ms) or as soon as a full batch
 * accumulates. When the queue is full the oldest events are dropped and counted
 * ({@link #droppedCount()}). When the control plane is unreachable the batch is re-queued at the head
 * and retried after {@code flushInterval}. Nothing in this class ever throws to the caller.
 */
public final class TelemetryClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TelemetryClient.class);

    public static final int DEFAULT_QUEUE_CAPACITY = 50_000;
    public static final int DEFAULT_BATCH_SIZE = 500;
    public static final long DEFAULT_FLUSH_INTERVAL_MS = 2_000;
    public static final String SERVICE_TOKEN_HEADER = "X-DBP-Service-Token";

    /** Event category → ingestion endpoint path. */
    public enum Kind {
        QUERY("/api/v1/internal/telemetry/queries"),
        CONNECTION("/api/v1/internal/telemetry/connections"),
        POOL("/api/v1/internal/telemetry/pools");

        private final String path;

        Kind(String path) {
            this.path = path;
        }

        public String path() {
            return path;
        }
    }

    private record Envelope(Kind kind, Object event) {}

    private final String baseUrl;
    private final String serviceToken;
    private final int capacity;
    private final int batchSize;
    private final long flushIntervalNanos;
    private final Duration requestTimeout;
    private final HttpClient http;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition batchReady = lock.newCondition();
    private final ArrayDeque<Envelope> queue;
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong failedBatches = new AtomicLong();
    private volatile boolean running = true;
    private volatile boolean flushRequested;
    private final Thread flusher;
    private long lastWarnNanos;

    public TelemetryClient(String controlPlaneUrl, String serviceToken) {
        this(builder(controlPlaneUrl, serviceToken));
    }

    private TelemetryClient(Builder b) {
        this.baseUrl = stripSlash(Objects.requireNonNull(b.controlPlaneUrl, "controlPlaneUrl"));
        this.serviceToken = b.serviceToken == null ? "" : b.serviceToken;
        this.capacity = Math.max(1, b.queueCapacity);
        this.batchSize = Math.max(1, Math.min(b.batchSize, DEFAULT_BATCH_SIZE));
        this.flushIntervalNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, b.flushInterval.toMillis()));
        this.requestTimeout = b.requestTimeout;
        this.queue = new ArrayDeque<>(Math.min(capacity, 1024));
        this.http = b.httpClient != null ? b.httpClient : HttpClient.newBuilder()
                .connectTimeout(b.connectTimeout)
                .proxy(HttpClient.Builder.NO_PROXY)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.flusher = Thread.ofVirtual().name("dbp-telemetry-flusher").unstarted(this::runLoop);
        if (b.autoStart) {
            this.flusher.start();
        }
    }

    public static Builder builder(String controlPlaneUrl, String serviceToken) {
        return new Builder(controlPlaneUrl, serviceToken);
    }

    /**
     * Builder reading defaults from {@code DBP_CONTROL_PLANE_URL}, {@code DBP_SERVICE_TOKEN},
     * {@code DBP_TELEMETRY_FLUSH_MS}, {@code DBP_TELEMETRY_QUEUE_SIZE}, {@code DBP_TELEMETRY_BATCH_SIZE}.
     */
    public static Builder fromEnv() {
        return new Builder(
                org.dbplatform.common.util.Env.get("DBP_CONTROL_PLANE_URL", "http://localhost:8080"),
                org.dbplatform.common.util.Env.get("DBP_SERVICE_TOKEN", "dev-service-token"))
                .flushInterval(org.dbplatform.common.util.Env.getDuration("DBP_TELEMETRY_FLUSH_MS",
                        Duration.ofMillis(DEFAULT_FLUSH_INTERVAL_MS)))
                .queueCapacity(org.dbplatform.common.util.Env.getInt("DBP_TELEMETRY_QUEUE_SIZE", DEFAULT_QUEUE_CAPACITY))
                .batchSize(org.dbplatform.common.util.Env.getInt("DBP_TELEMETRY_BATCH_SIZE", DEFAULT_BATCH_SIZE));
    }

    // ------------------------------------------------------------------ public API

    public void record(QueryEvent event) {
        enqueue(Kind.QUERY, event);
    }

    public void record(ConnectionEvent event) {
        enqueue(Kind.CONNECTION, event);
    }

    public void record(PoolStats stats) {
        enqueue(Kind.POOL, stats);
    }

    /** Number of events dropped so far (queue full, or undeliverable at close). */
    public long droppedCount() {
        return dropped.get();
    }

    /** Number of events successfully delivered. */
    public long sentCount() {
        return sent.get();
    }

    /** Number of batch POSTs that failed (each is retried unless the client is closing). */
    public long failedBatchCount() {
        return failedBatches.get();
    }

    /** Events currently waiting in the queue. */
    public int queuedCount() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity;
    }

    public String baseUrl() {
        return baseUrl;
    }

    /** Asks the flusher to send what is queued now, without waiting for the interval. Returns immediately. */
    public void flush() {
        lock.lock();
        try {
            flushRequested = true;
            batchReady.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stops accepting events, attempts one final delivery of everything queued (each batch tried once)
     * and waits up to 10 s for the flusher. Never throws.
     */
    @Override
    public void close() {
        if (!running) {
            return;
        }
        running = false;
        lock.lock();
        try {
            batchReady.signalAll();
        } finally {
            lock.unlock();
        }
        try {
            if (flusher.isAlive()) {
                flusher.join(10_000);
            } else if (flusher.getState() == Thread.State.NEW) {
                drainOnClose();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ internals

    private void enqueue(Kind kind, Object event) {
        if (event == null) {
            return;
        }
        if (!running) {
            dropped.incrementAndGet();
            return;
        }
        try {
            lock.lock();
            try {
                if (queue.size() >= capacity) {
                    queue.pollFirst();
                    dropped.incrementAndGet();
                }
                queue.addLast(new Envelope(kind, event));
                if (queue.size() >= batchSize) {
                    batchReady.signal();
                }
            } finally {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            dropped.incrementAndGet();
        }
    }

    private void runLoop() {
        try {
            while (running) {
                List<Envelope> batch = awaitBatch();
                if (batch.isEmpty()) {
                    continue;
                }
                if (!send(batch)) {
                    requeueAtHead(batch);
                    backoff();
                }
            }
            drainOnClose();
        } catch (Throwable t) {
            LOG.warn("telemetry flusher stopped unexpectedly: {}", t.toString());
        }
    }

    /** Waits until a full batch is ready, a flush is requested, the interval elapses or the client closes. */
    private List<Envelope> awaitBatch() {
        long deadline = System.nanoTime() + flushIntervalNanos;
        lock.lock();
        try {
            while (running && !flushRequested && queue.size() < batchSize) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    break;
                }
                try {
                    batchReady.awaitNanos(remaining);
                } catch (InterruptedException e) {
                    running = false;
                    break;
                }
            }
            flushRequested = false;
            return takeBatch();
        } finally {
            lock.unlock();
        }
    }

    /** Caller holds the lock. */
    private List<Envelope> takeBatch() {
        int n = Math.min(batchSize, queue.size());
        if (n == 0) {
            return List.of();
        }
        List<Envelope> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            batch.add(queue.pollFirst());
        }
        return batch;
    }

    private void requeueAtHead(List<Envelope> batch) {
        lock.lock();
        try {
            int room = capacity - queue.size();
            int keepFrom = Math.max(0, batch.size() - room);
            if (keepFrom > 0) {
                dropped.addAndGet(keepFrom); // the oldest events of the failed batch
            }
            for (int i = batch.size() - 1; i >= keepFrom; i--) {
                queue.addFirst(batch.get(i));
            }
        } finally {
            lock.unlock();
        }
    }

    private void backoff() {
        lock.lock();
        try {
            if (running && !flushRequested) {
                batchReady.awaitNanos(flushIntervalNanos);
            }
        } catch (InterruptedException e) {
            running = false;
        } finally {
            lock.unlock();
        }
    }

    private void drainOnClose() {
        while (true) {
            List<Envelope> batch;
            lock.lock();
            try {
                batch = takeBatch();
            } finally {
                lock.unlock();
            }
            if (batch.isEmpty()) {
                return;
            }
            if (!send(batch)) {
                dropped.addAndGet(batch.size());
                // give up on the rest as well: the control plane is unreachable while we shut down
                lock.lock();
                try {
                    dropped.addAndGet(queue.size());
                    queue.clear();
                } finally {
                    lock.unlock();
                }
                return;
            }
        }
    }

    /** Groups the batch by kind and POSTs each group; returns false when any POST failed (the whole batch is retried). */
    private boolean send(List<Envelope> batch) {
        Map<Kind, List<Object>> groups = new EnumMap<>(Kind.class);
        for (Envelope e : batch) {
            groups.computeIfAbsent(e.kind(), k -> new ArrayList<>()).add(e.event());
        }
        boolean ok = true;
        for (Map.Entry<Kind, List<Object>> g : groups.entrySet()) {
            if (!post(g.getKey(), g.getValue())) {
                ok = false;
            }
        }
        return ok;
    }

    private boolean post(Kind kind, List<Object> events) {
        byte[] body;
        try {
            body = TelemetryJson.toJsonBytes(events);
        } catch (RuntimeException e) {
            // unserialisable events are dropped, not retried
            dropped.addAndGet(events.size());
            warn("telemetry serialisation failed for " + kind + ": " + e);
            return true;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + kind.path()))
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header(SERVICE_TOKEN_HEADER, serviceToken)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                sent.addAndGet(events.size());
                return true;
            }
            failedBatches.incrementAndGet();
            if (status >= 400 && status < 500 && status != 408 && status != 429) {
                // the control plane rejected the payload: retrying will not help
                dropped.addAndGet(events.size());
                warn("control plane rejected " + events.size() + " " + kind + " events with HTTP " + status);
                return true;
            }
            warn("telemetry POST " + kind.path() + " returned HTTP " + status + "; will retry");
            return false;
        } catch (IOException | RuntimeException e) {
            failedBatches.incrementAndGet();
            warn("telemetry POST " + kind.path() + " failed: " + e + "; will retry");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
            return false;
        }
    }

    /** Rate-limited warning (at most one per 10 s) so an outage does not flood the log. */
    private void warn(String message) {
        long now = System.nanoTime();
        if (now - lastWarnNanos > TimeUnit.SECONDS.toNanos(10) || lastWarnNanos == 0) {
            lastWarnNanos = now;
            LOG.warn("{} (dropped so far: {})", message, dropped.get());
        } else {
            LOG.debug(message);
        }
    }

    private static String stripSlash(String url) {
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    // ------------------------------------------------------------------ builder

    public static final class Builder {
        private final String controlPlaneUrl;
        private final String serviceToken;
        private int queueCapacity = DEFAULT_QUEUE_CAPACITY;
        private int batchSize = DEFAULT_BATCH_SIZE;
        private Duration flushInterval = Duration.ofMillis(DEFAULT_FLUSH_INTERVAL_MS);
        private Duration connectTimeout = Duration.ofSeconds(3);
        private Duration requestTimeout = Duration.ofSeconds(10);
        private HttpClient httpClient;
        private boolean autoStart = true;

        private Builder(String controlPlaneUrl, String serviceToken) {
            this.controlPlaneUrl = controlPlaneUrl;
            this.serviceToken = serviceToken;
        }

        public Builder queueCapacity(int v) { this.queueCapacity = v; return this; }
        /** Max events per POST (capped at {@value #DEFAULT_BATCH_SIZE}). */
        public Builder batchSize(int v) { this.batchSize = v; return this; }
        public Builder flushInterval(Duration v) { this.flushInterval = Objects.requireNonNull(v); return this; }
        public Builder connectTimeout(Duration v) { this.connectTimeout = Objects.requireNonNull(v); return this; }
        public Builder requestTimeout(Duration v) { this.requestTimeout = Objects.requireNonNull(v); return this; }
        /** Supply your own client (e.g. for TLS configuration). */
        public Builder httpClient(HttpClient v) { this.httpClient = v; return this; }
        /** When false the flusher thread is not started; events are only delivered on {@link #close()}. */
        public Builder autoStart(boolean v) { this.autoStart = v; return this; }

        public TelemetryClient build() {
            return new TelemetryClient(this);
        }
    }
}
