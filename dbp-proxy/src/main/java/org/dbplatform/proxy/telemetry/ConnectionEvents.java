package org.dbplatform.proxy.telemetry;

import org.dbplatform.proxy.registry.LiveConnection;

/** Sink for connection lifecycle events (OPEN / CLOSE / REFUSED / BACKEND_FAILED). Must never block the data path. */
public interface ConnectionEvents {
    void opened(LiveConnection c);

    void closed(LiveConnection c, String reason);

    void refused(LiveConnection c, String reason);

    void backendFailed(LiveConnection c, String reason);

    ConnectionEvents NOOP = new ConnectionEvents() {
        @Override
        public void opened(LiveConnection c) {
        }

        @Override
        public void closed(LiveConnection c, String reason) {
        }

        @Override
        public void refused(LiveConnection c, String reason) {
        }

        @Override
        public void backendFailed(LiveConnection c, String reason) {
        }
    };
}
