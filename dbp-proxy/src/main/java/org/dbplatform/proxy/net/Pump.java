package org.dbplatform.proxy.net;

import io.micrometer.core.instrument.Counter;
import org.dbplatform.proxy.registry.LiveConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bidirectional byte pump: one virtual thread per direction, graceful half-close ({@code shutdownOutput}
 * on the destination when the source hits EOF), idle timeout (via periodic read timeouts and the
 * connection's last-activity clock) and a linger limit for half-closed connections so sockets never leak.
 */
final class Pump {
    private static final Logger LOG = LoggerFactory.getLogger(Pump.class);
    static final long HALF_CLOSE_LINGER_MS = 30_000;
    static final int TICK_MS = 30_000;

    private final ConnectionContext ctx;
    private final LiveConnection live;
    private final long idleMs;
    private final AtomicBoolean finished = new AtomicBoolean();
    private final AtomicReference<String> reason = new AtomicReference<>();
    private volatile long clientEofAt;
    private volatile long backendEofAt;

    private Pump(ConnectionContext ctx) {
        this.ctx = ctx;
        this.live = ctx.live();
        this.idleMs = ctx.settings().idleTimeoutSeconds() * 1000L;
    }

    /** Runs until both directions are done; returns the close reason. */
    static String run(ConnectionContext ctx) throws IOException {
        return new Pump(ctx).run();
    }

    private String run() throws IOException {
        Socket client = ctx.client();
        Socket backend = ctx.backend();
        int tick = idleMs > 0 ? (int) Math.min(idleMs, TICK_MS) : TICK_MS;
        client.setSoTimeout(tick);
        backend.setSoTimeout(tick);
        Thread b2c = Thread.ofVirtual().name(live.id() + "-b2c").start(() ->
                copy(ctx.backendIn(), ctx.clientOut(), client, backend, false, ctx.bytesOutCounter()));
        copy(ctx.clientIn(), ctx.backendOut(), backend, client, true, ctx.bytesInCounter());
        try {
            b2c.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            finish("interrupted");
        }
        return reason.get() == null ? "closed" : reason.get();
    }

    private void copy(InputStream in, OutputStream out, Socket dest, Socket src, boolean fromClient, Counter counter) {
        byte[] buf = new byte[ctx.settings().bufferBytes()];
        String side = fromClient ? "client" : "backend";
        try {
            while (!finished.get()) {
                int n;
                try {
                    n = in.read(buf);
                } catch (SocketTimeoutException e) {
                    if (finished.get()) {
                        return;
                    }
                    if (idleMs > 0 && live.idleMillis() >= idleMs) {
                        finish("idle timeout after " + (live.idleMillis() / 1000) + " s");
                        return;
                    }
                    long otherEof = fromClient ? backendEofAt : clientEofAt;
                    if (otherEof != 0 && System.currentTimeMillis() - otherEof > HALF_CLOSE_LINGER_MS) {
                        finish("half-close linger expired (" + (fromClient ? "backend" : "client") + " closed first)");
                        return;
                    }
                    continue;
                }
                if (n < 0) {
                    long now = System.currentTimeMillis();
                    if (fromClient) {
                        clientEofAt = now;
                    } else {
                        backendEofAt = now;
                    }
                    reason.compareAndSet(null, side + " closed");
                    try {
                        dest.shutdownOutput();
                    } catch (IOException ignored) {
                        // destination already gone
                    }
                    if ((fromClient ? backendEofAt : clientEofAt) != 0) {
                        finish(side + " closed");
                    }
                    return;
                }
                out.write(buf, 0, n);
                out.flush();
                if (fromClient) {
                    live.addBytesIn(n);
                } else {
                    live.addBytesOut(n);
                }
                counter.increment(n);
            }
        } catch (IOException e) {
            if (!finished.get()) {
                finish(side + " error: " + e.getMessage());
            }
        }
    }

    private void finish(String why) {
        if (finished.compareAndSet(false, true)) {
            reason.compareAndSet(null, why);
            LOG.debug("{} pump finished: {}", live.id(), why);
            ConnectionContext.closeQuietly(ctx.client());
            ConnectionContext.closeQuietly(ctx.backend());
        }
    }
}
