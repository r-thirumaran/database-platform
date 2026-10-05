package org.dbplatform.protocol;

import java.io.IOException;

/**
 * Thrown when a frame or payload violates the DBP wire protocol: unknown message type or value tag,
 * oversize frame, truncated payload, negative length, malformed value data, and so on.
 *
 * <p>It extends {@link IOException} so that both the driver and the gateway can treat it like any
 * other I/O failure on the connection.</p>
 */
public class ProtocolException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with the given message.
     *
     * @param message description of the protocol violation
     */
    public ProtocolException(String message) {
        super(message);
    }

    /**
     * Creates an exception with the given message and cause.
     *
     * @param message description of the protocol violation
     * @param cause   underlying cause
     */
    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
