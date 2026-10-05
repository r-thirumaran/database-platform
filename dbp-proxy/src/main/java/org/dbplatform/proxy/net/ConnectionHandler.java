package org.dbplatform.proxy.net;

import java.io.IOException;

/** Protocol-specific handshake logic; the context is closed by the caller afterwards. */
public interface ConnectionHandler {
    void handle(ConnectionContext ctx) throws IOException;
}
