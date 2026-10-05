package org.dbplatform.it.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/** Ephemeral port allocation and "is this conventional port free" probes. */
public final class Ports {
    private Ports() {
    }

    /** A currently free TCP port on 127.0.0.1 (racy by nature; good enough for tests). */
    public static int free() {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress("127.0.0.1", 0));
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** True when nothing listens on the port (all interfaces). */
    public static boolean isFree(int port) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The preferred port when it is free, otherwise an ephemeral one. */
    public static int preferred(int preferred) {
        return isFree(preferred) ? preferred : free();
    }
}
